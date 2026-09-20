(ns seon.call-preparation
  "Call preparation: declared supplied defaults, and the invocation plan
  derived from the program graph.

  `call preparation` is sci's own name for the seam
  (`:call-preparation-hook`, `reference-code/sci/src/sci/core.cljc:309`) and
  the owner-ruled name for this mechanism (seon-env PRD ruling 5). A
  SUPPLIED DEFAULT is a value the runtime hands to a call for an argument
  the callee's contract DECLARES and the caller OMITTED. The function's own
  `:malli/schema` is the complete request; there is no function-side
  injection metadata, no annotation, and no roster.

  Three facts make a value suppliable, and they live in the database as
  ordinary initialization rows: `:seon.call-preparation/key`,
  `/schema` (the registered schema whose content-addressed
  `:seon.schema/shape` is the value contract), and `/supplier` (the
  function that produces it). Adding a third supplied default is adding one
  row plus one ordinary contracted function.

  Suppliers read the ENVIRONMENT, never a dynamic var (seon-env PRD ruling
  2, amending the r2 draft's sealed `seon.db/*conn*` invariant). The hook
  receives the RUNTIME ctx, the environment rides that ctx, and the
  supplier is called with it — so a closure handed to a virtual thread
  still resolves its own cluster's custody.

  Two modes of database temporality fall out of this with no mode flag
  (PRD ruling 6): a caller that omits `:seon.db/db` gets `(d/db
  connection)` derefed AT CALL TIME by the supplier — always current; a
  caller that passes a database value keeps it unchanged through the whole
  call. Elide for current, pass for consistent.

  The acquired context installs this hook and its cluster-local plan
  state. Full arities and the cached all-default shape retain precedence;
  other shorter calls require a unique placement under the declared slot
  schemas before any missing value is supplied."
  (:require [clojure.test.check.generators :as gen]
            [datahike.core :as datahike]
            [malli.core :as m]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as error]
            [seon.fn.schema-shape :as schema-shape]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(set! *warn-on-reflection* true)

;;; ---------------------------------------------------------------------------
;;; The cluster-local state
;;; ---------------------------------------------------------------------------

(defn state?
  "True for the atom holding one cluster's supplied defaults and plans."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? clojure.lang.IAtom value))

(schema/register-core-predicate! 'seon.call-preparation/state? state?)

(def state-generator
  (gen/fmap atom (gen/return {})))

(schema.edn/load! {})

(def ^:private empty-snapshot
  {:seon.call-preparation/supplied-defaults {}
   :seon.call-preparation/prepared-symbols #{}
   :seon.call-preparation/validators {}
   :seon.call-preparation/refusals []
   :seon.call-preparation/basis-t 0
   ;; -1, not 0: a fresh store's first database value already has a basis, and
   ;; an unchecked state must never compare equal to it.
   :seon.call-preparation/checked-through-t -1})

(defn state
  "Create the call-preparation state owned by ONE acquired cluster context.

  Never process-global across sovereign branches: cluster B compiles the
  same function identity from its own facts rather than reusing A's plan.
  `sci/fork` copies only `:env`, so every fork of a cluster's ctx shares
  this atom by identity — which is correct, because a plan is a property
  of the cluster's program graph, not of a turn."
  {:malli/schema [:=> [:cat] :seon.call-preparation/state]}
  []
  (atom {:seon.call-preparation/snapshot empty-snapshot
         :seon.call-preparation/plans {}}))

(def carrier
  "The ONE key call-preparation state reads under on a sci ctx."
  :seon.call-preparation/state)

(defn install
  "Attach fresh call-preparation state to a cluster's sci ctx.

  `seon.sci.eval/cluster-ctx` calls this beside the projection state, so
  every acquired cluster context carries it and every per-turn
  `sci/fork` inherits it. The hook reads exactly this key: a ctx without
  it is a ctx where call preparation is inert, which is the correct
  behaviour for a scratch or non-Seon context and the WRONG behaviour for
  a cluster — an installed hook with no state was silently inert at every
  live call site until this call landed."
  {:malli/schema [:=> [:cat :map] :map]}
  [ctx]
  (assoc ctx carrier (state) :my.program/base-ctx ctx))

;;; ---------------------------------------------------------------------------
;;; Errors as values
;;; ---------------------------------------------------------------------------

 ;; The error owner requires call preparation. Resolve its inspection Vars
;; once after loading, as seon.error does for its SCI dependency. The supplied
;; projection remains the authority; no error population is copied here.
(def ^:private error-facets (delay (requiring-resolve 'seon.error/facets)))
(def ^:private error-facet-keys (delay (requiring-resolve 'seon.error/facet-keys)))

;;; ---------------------------------------------------------------------------
;;; The declared row attributes — a query over the declaration, not a list
;;; ---------------------------------------------------------------------------

(def ^:private declared-row-attributes
  ;; Read the declaration ONCE, for the reason `seon.env/declared-members`
  ;; records: with no projection in hand `schema/schema-definition` re-reads
  ;; and re-merges every schema resource (152 reads, ~14 ms), and the basis
  ;; derivation asks this question on every conservative refresh. Reading
  ;; once is honest because `:seon.call-preparation/row` is a CORE packaged
  ;; declaration — exact-key redefinition is refused at admission, so this
  ;; value cannot change while the process lives.
  (delay
    (let [definition (schema/schema-definition :seon.call-preparation/row)
          entries (when (and (vector? definition) (= :map (first definition)))
                    (filter vector? (rest definition)))]
      (into [] (map first) entries))))

(defn row-attributes
  "The attributes a supplied-default row is made of.

  Read from the one `:seon.call-preparation/row` declaration, so the
  listener's watch set and the basis derivation cannot drift from the
  schema. This is the fact that replaces an embedded attribute list."
  {:malli/schema [:=> [:cat] [:vector :qualified-keyword]]}
  []
  @declared-row-attributes)

;;; ---------------------------------------------------------------------------
;;; The acquired snapshot
;;; ---------------------------------------------------------------------------

(def ^:private row-query
  '[:find ?key ?schema-key ?fingerprint ?supplier
    :in $
    :where
    [?row :seon.call-preparation/key ?key]
    [?row :seon.call-preparation/schema ?schema]
    [?schema :seon.schema/key ?schema-key]
    [?schema :seon.schema/shape ?shape]
    [?shape :seon.schema.shape/fingerprint ?fingerprint]
    [?row :seon.call-preparation/supplier ?function]
    [?function :seon.fn/sym ?supplier]])

(def ^:private supplier-shape-query
  ;; A supplier's declared call shape and return shape, for the coherence
  ;; proof: one argument (this cluster's environment) and an `:or`
  ;; return — the row's value shape plus declared error facets.
  '[:find ?order ?count ?argument-fingerprint ?return-type
    :in $ ?sym
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/arities ?arity]
    [?arity :seon.fn.arity/order ?order]
    [?arity :seon.fn.arity/argument-count ?count]
    [?arity :seon.fn.arity/arguments ?argument]
    [?argument :seon.fn.argument/index 0]
    [?argument :seon.fn.argument/schema ?argument-shape]
    [?argument-shape :seon.schema.shape/fingerprint ?argument-fingerprint]
    [?arity :seon.fn.arity/return-schema ?return]
    [?return :seon.schema.shape/type ?return-type]])

(def ^:private supplier-return-arms-query
  '[:find ?child-fingerprint
    :in $ ?sym
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/arities ?arity]
    [?arity :seon.fn.arity/order 0]
    [?arity :seon.fn.arity/return-schema ?return]
    [?return :seon.schema.shape/children ?child]
    [?child :seon.schema.shape.child/schema ?child-shape]
    [?child-shape :seon.schema.shape/fingerprint ?child-fingerprint]])

(def ^:private schema-fingerprint-query
  '[:find ?fingerprint .
    :in $ ?key
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/shape ?shape]
    [?shape :seon.schema.shape/fingerprint ?fingerprint]])

(defn- incoherent
  {:malli/schema [:=> [:cat :seon.call-preparation/supplied-default :string :map]
                  :seon.call-preparation/incoherent-supplier-error]}
  [{default-key :seon.call-preparation/key
    supplier :seon.call-preparation/supplier-symbol
    schema-key :seon.call-preparation/schema-key} reason data]
  (error/diagnostic
 {:seon.error/at (java.util.Date.)
 :seon.error/layer :seon.call-preparation/call
 :seon.error/operation 'seon.call-preparation/incoherent
 :seon.error/message (str "The supplied default " default-key " is not admissible: " reason)
 :seon.call-preparation/incoherent-key default-key
 :seon.call-preparation/supplier-symbol supplier
 :seon.call-preparation/schema-key schema-key
 :seon.call-preparation/coherence-expectation reason
 :seon.error/diagnostic-layer :seon.call-preparation/call
 :seon.error/diagnostic-operation 'seon.call-preparation/incoherent
 :seon.error/diagnostic-member default-key
 :seon.error/diagnostic-expected reason
 :seon.error/diagnostic-offending data
 :seon.error/diagnostic-cause :seon.call-preparation/incoherent-supplier
 :seon.error/diagnostic-evidence (assoc data :seon.call-preparation/key default-key)
 :seon.error/data (assoc data :seon.call-preparation/key default-key)}))

(defn- coherent-supplier
  "Prove one row against the program graph, or refuse it as a value.

  Refusing at acquisition is the point: an incoherent row is never
  installed, so a wrong-shaped value can never be handed to a target
  function and disguised as that function's own contract violation."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.call-preparation/supplied-default
         [:or :nil :seon.call-preparation/shape] [:set :seon.call-preparation/shape]]
    [:or :nil :seon.call-preparation/incoherent-supplier-error :seon.db/error-result]]}
  [database
   {schema-key :seon.call-preparation/schema-key
    fingerprint :seon.call-preparation/shape
    supplier :seon.call-preparation/supplier-symbol :as candidate}
   environment-fingerprint error-fingerprints]
  (let [shapes (db/q database supplier-shape-query supplier)]
    (cond
      (and (map? shapes) (contains? shapes :seon.error/at) (contains? shapes :seon.error/layer) (contains? shapes :seon.error/operation)) ;; debt: seon.db/q passes seon.db generic :seon.error/value
 shapes

      (empty? shapes)
      (incoherent candidate
                  (str "no function named " supplier
                       " with a contracted one-argument arity is in this "
                       "cluster's program graph.")
                  {:seon.call-preparation/supplier-symbol supplier})

      :else
      (let [[order argument-count argument-fingerprint return-type]
            (first (sort-by first shapes))
            arms (into #{}
                       (map first)
                       (db/q database supplier-return-arms-query
                             supplier))]
        (cond
          (not (and (zero? (long order)) (= 1 (long argument-count))))
          (incoherent candidate
                      (str supplier " must take exactly one argument, this "
                           "cluster's environment; it declares "
                           argument-count ".")
                      {:seon.call-preparation/supplier-symbol supplier
                       :seon.fn.arity/argument-count argument-count})

          (not= argument-fingerprint environment-fingerprint)
          (incoherent candidate
                      (str supplier "'s argument is not "
                           ":seon.env/environment. A supplier reads the "
                           "environment it is called with and nothing else.")
                      {:seon.call-preparation/supplier-symbol supplier})

          (not= :or return-type)
          (incoherent candidate
                      (str supplier " must declare a union return: its "
                           "value schema or declared error facets. It declares a "
                           (pr-str return-type) ".")
                      {:seon.call-preparation/supplier-symbol supplier})

          (not (and (contains? arms fingerprint)
                    (seq (disj arms fingerprint))
                    (every? error-fingerprints (disj arms fingerprint))))
          (incoherent candidate
                      (str supplier "'s declared return does not agree with "
                           "the row's value schema " schema-key ".")
                      {:seon.call-preparation/supplier-symbol supplier
                       :seon.call-preparation/schema-key schema-key})

          :else nil)))))

(def ^:private current-row-transaction-query
  '[:find (max ?tx) . :in $ [?attribute ...] :where [_ ?attribute _ ?tx]])

(def ^:private historical-row-transaction-query
  '[:find (max ?tx) . :in $ [?attribute ...] :where [_ ?attribute _ ?tx _]])

(defn- newest-row-transaction
  "The newest transaction that asserted OR retracted any row fact.

  Derived, never a stored counter. The history view is what makes a
  RETRACTION move this basis; a store kept without history answers from
  its current value, which still moves on every assertion."
  [database attributes]
  ;; This basis keeps the process-local snapshot cache coherent. It is not a
  ;; semantic read performed by the prepared call: the surrounding snapshot
  ;; queries already retain the precise row, schema, and supplier attributes
  ;; whose change can affect preparation. A temporal view plus a variable
  ;; attribute max-tx query necessarily reports `:all`; retaining that internal
  ;; observation made every unrelated result-settlement transaction invalidate
  ;; the evaluated form that preparation had merely admitted.
  (binding [db/*read-evidence-sink* nil]
    (let [history (db/history database)
          historical (when-not (and (map? history) (contains? history :seon.error/at) (contains? history :seon.error/layer) (contains? history :seon.error/operation)) ;; debt: seon.db/history passes seon.db generic :seon.error/value

                       (db/q history historical-row-transaction-query
                             attributes))
          current (db/q database current-row-transaction-query attributes)]
      (long (or (when (number? historical) historical)
                (when (number? current) current)
                0)))))

(defn- by-fingerprint
  [supplied-defaults]
  (into {}
        (map (fn [[_ candidate]]
               [(:seon.call-preparation/shape candidate) candidate]))
        supplied-defaults))

(def ^:private prepared-positional-query
  '[:find [?sym ...]
    :in $ [?fingerprint ...]
    :where
    [?shape :seon.schema.shape/fingerprint ?fingerprint]
    [?argument :seon.fn.argument/schema ?shape]
    [?arity :seon.fn.arity/arguments ?argument]
    [?function :seon.fn/arities ?arity]
    [?function :seon.fn/sym ?sym]])

(def ^:private prepared-entry-query
  '[:find ?sym ?entry-key ?fingerprint
    :in $ [?fingerprint ...]
    :where
    [?value-shape :seon.schema.shape/fingerprint ?fingerprint]
    [?entry :seon.schema.shape.entry/schema ?value-shape]
    [?entry :seon.schema.shape.entry/optional? false]
    [?entry :seon.schema.map-entry/key-keyword ?entry-key]
    [?argument-shape :seon.schema.shape/entries ?entry]
    [?argument :seon.fn.argument/schema ?argument-shape]
    [?arity :seon.fn.arity/arguments ?argument]
    [?function :seon.fn/arities ?arity]
    [?function :seon.fn/sym ?sym]])

(defn- prepared-symbols
  "Every identity in this cluster whose contract could be prepared.

  THE HOT-PATH GATE, and a query rather than a list: an identity absent
  here provably declares no suppliable positional slot and no suppliable
  required map key, so the hook returns its arguments after one set
  lookup — no plan compilation, no Datalog, no supplier call. Derived
  once per snapshot from the same database value the snapshot is derived
  from, so a newly published function is gated in at the same basis the
  row is.

  Deliberately a conservative SUPERSET: a required map key whose value
  shape matches while its keyword does not is admitted here and rejected
  by the plan's own two-part join. Over-including costs one cached empty
  plan; under-including would silently skip preparation."
  [database index]
  (let [fingerprints (vec (keys index))]
    (if (empty? fingerprints)
      #{}
      (let [positional (db/q database prepared-positional-query fingerprints)
            entries (db/q database prepared-entry-query fingerprints)]
        (into (if (and (map? positional) (contains? positional :seon.error/at) (contains? positional :seon.error/layer) (contains? positional :seon.error/operation)) ;; debt: seon.db/q passes seon.db generic :seon.error/value
 #{} (set positional))
              (comp (filter (fn [[_ entry-key fingerprint]]
                              (= entry-key
                                 (:seon.call-preparation/key
                                  (get index fingerprint)))))
                    (map first))
              (when-not (and (map? entries) (contains? entries :seon.error/at) (contains? entries :seon.error/layer) (contains? entries :seon.error/operation)) ;; debt: seon.db/q passes seon.db generic :seon.error/value
 entries))))))

(defn snapshot
  "Derive the complete supplied-default snapshot from one database value.

  Every row is proved against the program graph here; incoherent rows
  become refusals and are NOT installed. The returned value carries its
  own two transactions: `checked-through-t` is the database value this was
  derived from, and `basis-t` is the newest transaction touching any row
  fact — the third element of every plan cache key. It also carries the
  hot-path gate: the set of identities that could be prepared at all."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schema/projection]
    [:or :seon.call-preparation/snapshot :seon.db/error-result]]}
  [database projection]
  (let [rows (db/q database row-query)]
    (if (and (map? rows) (contains? rows :seon.error/at) (contains? rows :seon.error/layer) (contains? rows :seon.error/operation)) ;; debt: seon.db/q passes seon.db generic :seon.error/value

      rows
      (let [environment-fingerprint
            (db/q database schema-fingerprint-query :seon.env/environment)
            error-fingerprints
            ;; debt: seon.db supplied-database-value and supplied-connection still declare :seon.error/value.
            (set (db/q database
                       '[:find [?fingerprint ...] :in $ [?key ...]
                         :where [?schema :seon.schema/key ?key]
                         [?schema :seon.schema/shape ?shape]
                         [?shape :seon.schema.shape/fingerprint ?fingerprint]]
                       (conj (@error-facet-keys projection) :seon.error/value)))
            candidates
            (mapv (fn [[default-key schema-key fingerprint supplier]]
                    {:seon.call-preparation/key default-key
                     :seon.call-preparation/schema-key schema-key
                     :seon.call-preparation/shape fingerprint
                     :seon.call-preparation/supplier-symbol (symbol supplier)})
                  rows)
            proved
            (mapv (fn [candidate]
                    [candidate
                     (coherent-supplier database candidate
                                        environment-fingerprint
                                        error-fingerprints)])
                  candidates)
            ;; Compiling the value validator is part of proving the row: a
            ;; value schema the acquired projection cannot compile is an
            ;; incoherent row, not an exception thrown at acquisition.
            compiled
            (mapv (fn [[candidate refusal]]
                    (if refusal
                      [candidate refusal nil]
                      (let [schema-key
                            (:seon.call-preparation/schema-key candidate)]
                        (try
                          [candidate nil
                           (schema/projection-validator projection schema-key)]
                          (catch Throwable cause
                            [candidate
                             (incoherent
                              candidate
                              (str "this cluster's projection cannot compile "
                                   "its value schema " schema-key ": "
                                   (ex-message cause))
                              {:seon.call-preparation/schema-key schema-key})
                             nil])))))
                  proved)
            admitted
            (into {}
                  (comp (remove second)
                        (map (fn [[candidate _ _]]
                               [(:seon.call-preparation/key candidate)
                                candidate])))
                  compiled)
            fingerprint-index
            (into {}
                  (map (fn [[_ candidate]]
                         [(:seon.call-preparation/shape candidate) candidate]))
                  admitted)]
        {:seon.schema/projection projection
         :seon.call-preparation/supplied-defaults admitted
         :seon.call-preparation/prepared-symbols
         (prepared-symbols database fingerprint-index)
         :seon.call-preparation/validators
         (into {}
               (comp (remove second)
                     (map (fn [[candidate _ valid?]]
                            [(:seon.call-preparation/key candidate) valid?])))
               compiled)
         :seon.call-preparation/refusals (into [] (keep second) compiled)
         :seon.call-preparation/basis-t
         (newest-row-transaction database (row-attributes))
         :seon.call-preparation/checked-through-t (db/basis-t database)}))))

;;; ---------------------------------------------------------------------------
;;; Basis comparison — the correctness boundary; the listener is the optimizer
;;; ---------------------------------------------------------------------------

(defn- adopt
  [current derived]
  (let [existing (:seon.call-preparation/snapshot current)]
    (if (>= (long (:seon.call-preparation/checked-through-t existing))
            (long (:seon.call-preparation/checked-through-t derived)))
      current
      (cond-> (assoc current :seon.call-preparation/snapshot derived)
        ;; A changed row basis invalidates every compiled plan by key;
        ;; dropping them keeps the cache from holding two generations at once.
        (not= (:seon.call-preparation/basis-t existing)
              (:seon.call-preparation/basis-t derived))
        (assoc :seon.call-preparation/plans {})))))

(defn current-snapshot
  "The snapshot valid at `database`, refreshing synchronously if behind.

  Datahike exposes a committed database on its connection BEFORE it
  delivers listener callbacks, so the listener can never be the
  correctness boundary. Every invocation dereferences the connection once
  and linearizes at that database value's basis: checked through it, the
  held snapshot is used as is; behind it, the complete snapshot is derived
  from that immutable value and swapped in. Concurrent refreshes keep the
  newest checked-through basis. An unrelated transaction therefore costs
  at most one conservative re-derivation — and no plan recompilation,
  because the row basis is unchanged."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/state :seon.db/database-value
         :seon.schema/projection]
    [:or :seon.call-preparation/snapshot :seon.db/error-result]]}
  [call-state database projection]
  (let [basis (db/basis-t database)
        held (:seon.call-preparation/snapshot @call-state)]
        (if (>= (long (:seon.call-preparation/checked-through-t held))
                (long basis))
          held
          (let [derived (snapshot database projection)]
            (if (and (map? derived) (contains? derived :seon.error/at) (contains? derived :seon.error/layer) (contains? derived :seon.error/operation)) ;; debt: seon.call-preparation/snapshot passes seon.db generic :seon.error/value

              derived
              (:seon.call-preparation/snapshot
               (swap! call-state adopt derived)))))))

(defn watch!
  "Register the eager listener for supplied-default changes.

  An optimization only: it lets an idle cluster notice a new row without
  waiting for the next invocation's basis comparison. This is a
  system-side listener, not an agent-facing read, which is why it calls
  Datahike directly (ruling #41 keeps listeners out of `seon.db`).
  Returns the key Datahike registered it under."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/state :seon.db/connection
         :seon.schema/projection]
    :keyword]}
  [call-state connection projection]
  (let [attributes (set (row-attributes))]
    (datahike/listen!
     connection
     :seon.call-preparation/rows
     (fn [report]
       (when (some (comp attributes :a) (:tx-data report))
         (let [derived (snapshot (:db-after report) projection)]
           (when-not (and (map? derived) (contains? derived :seon.error/at) (contains? derived :seon.error/layer) (contains? derived :seon.error/operation)) ;; debt: seon.call-preparation/snapshot passes seon.db generic :seon.error/value

             (swap! call-state adopt derived))))))))

;;; ---------------------------------------------------------------------------
;;; Plan derivation — one Datalog query set over the P12 argument addresses
;;; ---------------------------------------------------------------------------

(defn- contract-transaction
  [database sym]
  ;; Like the supplied-default basis, this is cache coherence rather than a
  ;; semantic read. The plan's queries capture the actual contract attributes.
  (binding [db/*read-evidence-sink* nil]
    (db/q database
          '[:find (max ?tx) .
            :in $ ?sym
            :where
            [?function :seon.fn/sym ?sym]
            [?function _ _ ?tx]]
          sym)))

(def ^:private arity-query
  '[:find ?order ?min ?count ?max
    :in $ ?sym
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/arities ?arity]
    [?arity :seon.fn.arity/order ?order]
    [?arity :seon.fn.arity/min ?min]
    [?arity :seon.fn.arity/argument-count ?count]
    [(get-else $ ?arity :seon.fn.arity/max -1) ?max]])

(def ^:private positional-query
  ;; A positional slot is eligible only when the slot's COMPLETE normalized
  ;; shape is a supplied default's value shape. Containing that shape below
  ;; `:or`, a collection, or a regex tail does not make the slot suppliable.
  '[:find ?order ?index ?rest? ?fingerprint
    :in $ ?sym [?fingerprint ...]
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/arities ?arity]
    [?arity :seon.fn.arity/order ?order]
    [?arity :seon.fn.arity/arguments ?argument]
    [?argument :seon.fn.argument/index ?index]
    [?argument :seon.fn.argument/rest? ?rest?]
    [?argument :seon.fn.argument/schema ?shape]
    [?shape :seon.schema.shape/fingerprint ?fingerprint]])

(def ^:private argument-shape-query
  '[:find ?order ?index ?form
    :in $ ?sym
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/arities ?arity]
    [?arity :seon.fn.arity/order ?order]
    [?arity :seon.fn.arity/arguments ?argument]
    [?argument :seon.fn.argument/index ?index]
    [?argument :seon.fn.argument/schema ?shape]
    [?shape :seon.schema.shape/form ?form]])

(defn- argument-validators
  [database current sym]
  (let [projection (:seon.schema/projection current)]
    (into {}
          (map (fn [[order rows]]
                 [order
                  (mapv (fn [[_ _ form]]
                          (m/validator
                           (schema/compilable-form
                            (schema-shape/row-form
                             {:seon.schema.shape/form form})
                            (schema/predicate-functions-in projection))
                           (:seon.schema.projection/compile-options projection)))
                        (sort-by second rows))]))
          (group-by first (db/q database argument-shape-query sym)))))

(def ^:private map-entry-query
  ;; REQUIRED keys of a top-level argument map only. Selection joins BOTH the
  ;; declared key and the value shape: `seon.cluster.wake/route!` declares
  ;; `:seon.cluster.wake/connection` with the `:seon.db/connection` shape, and
  ;; matching on shape alone would fill a key nobody declared as a default.
  '[:find ?order ?index ?entry-key ?fingerprint
    :in $ ?sym [?fingerprint ...]
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/arities ?arity]
    [?arity :seon.fn.arity/order ?order]
    [?arity :seon.fn.arity/arguments ?argument]
    [?argument :seon.fn.argument/index ?index]
    [?argument :seon.fn.argument/rest? false]
    [?argument :seon.fn.argument/schema ?argument-shape]
    [?argument-shape :seon.schema.shape/entries ?entry]
    [?entry :seon.schema.shape.entry/optional? false]
    [?entry :seon.schema.map-entry/key-keyword ?entry-key]
    [?entry :seon.schema.shape.entry/schema ?value-shape]
    [?value-shape :seon.schema.shape/fingerprint ?fingerprint]])

(def ^:private required-map-entry-query
  '[:find ?order ?index ?entry-key
    :in $ ?sym
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/arities ?arity]
    [?arity :seon.fn.arity/order ?order]
    [?arity :seon.fn.arity/arguments ?argument]
    [?argument :seon.fn.argument/index ?index]
    [?argument :seon.fn.argument/rest? false]
    [?argument :seon.fn.argument/schema ?shape]
    [?shape :seon.schema.shape/type :map]
    [?shape :seon.schema.shape/entries ?entry]
    [?entry :seon.schema.shape.entry/optional? false]
    [?entry :seon.schema.map-entry/key-keyword ?entry-key]])

(defn supplied-map-entries
  "Return [arity-order argument-index key] for declared supplied map entries.

  Uses the plan's required, top-level map-entry query and supplier rows.
  Both the key and complete value shape must match, exactly as preparation
  requires. Optional entries and positional defaults are not map entries."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.fn/sym]
    [:or [:vector [:tuple :int :int :qualified-keyword]] :seon.db/error-result]]}
  [database sym]
  (let [rows (db/q database row-query)]
    (if (and (map? rows) (contains? rows :seon.error/at) (contains? rows :seon.error/layer) (contains? rows :seon.error/operation)) ;; debt: seon.db/q passes seon.db generic :seon.error/value

      rows
      (let [declared (into #{} (map (fn [[entry-key _ fingerprint _]] [entry-key fingerprint])) rows)
            fingerprints (vec (distinct (map #(nth % 2) rows)))
            entries (if (seq fingerprints)
                      (db/q database map-entry-query sym fingerprints) [])]
        (if (and (map? entries) (contains? entries :seon.error/at) (contains? entries :seon.error/layer) (contains? entries :seon.error/operation)) ;; debt: seon.db/q passes seon.db generic :seon.error/value

          entries
          (->> entries
               (filter (fn [[_ _ entry-key fingerprint]] (contains? declared [entry-key fingerprint])))
               (map #(subvec (vec %) 0 3))
               sort
               vec))))))

(defn- candidate-indexes
  [candidates]
  (mapv (fn [candidate]
          (mapv :seon.fn.argument/index
                (:seon.call-preparation/inserts candidate)))
        candidates))

(defn- dispatchable
  "The predicate dispatch for one declared arity colliding with one derived
  shape at the same supplied count, or nil when the collision cannot be
  decided from the first argument.

  This is ruling 2's `db?`/`connection?` disambiguation, and the predicate
  is not a hand-written one: it is the row's OWN declared value schema,
  compiled once per acquisition into `:seon.call-preparation/validators`.
  A first argument that satisfies the omitted slot's value schema was
  supplied by the caller, so the DECLARED arity runs unchanged; anything
  else means the caller wrote the shorter shape and the slot is filled.

  Only a LEADING omitted slot is decidable — the ruling says \"the
  predicate on the first argument\" — and only when the call has a first
  argument at all. Every other collision falls through to the declared
  arity, which always wins."
  [exact derived]
  (let [leading (first (:seon.call-preparation/inserts derived))]
    (when (and (:seon.call-preparation/key leading)
               (zero? (long (:seon.fn.argument/index leading))))
      {:seon.call-preparation/key (:seon.call-preparation/key leading)
       :seon.call-preparation/supplied exact
       :seon.call-preparation/omitted derived})))

(defn- resolve-count
  "One supplied-argument count's preparation, or its refusal.

  A hand-declared arity always wins: a call whose count matches one
  declared arity invokes it unchanged, and only its argument-map entries
  are filled. Each fixed arity derives AT MOST ONE shorter shape — the
  arity minus ALL its suppliable slots (ruling 2's all-or-nothing model,
  superseding the r2 draft's omitted-subset combinatorics). When a
  declared arity and a derived shape land on the same count, the leading
  slot's own value schema decides at call time; when two derived shapes
  land on one count, the plan refuses rather than guessing by arity
  order."
  [exact derived]
  (cond
    (> (count exact) 1)
    {:seon.call-preparation/ambiguous? true
     :seon.call-preparation/candidates (candidate-indexes exact)}

    (and (= 1 (count exact)) (= 1 (count derived)))
    (if-let [dispatch (dispatchable (first exact) (first derived))]
      {:seon.call-preparation/ambiguous? false
       :seon.call-preparation/dispatch dispatch}
      (first exact))

    (= 1 (count exact)) (first exact)

    (= 1 (count derived)) (first derived)

    (seq derived)
    {:seon.call-preparation/ambiguous? true
     :seon.call-preparation/candidates (candidate-indexes derived)}))

(defn- slot-of
  [candidate position]
  {:seon.fn.argument/index (long position)
   :seon.call-preparation/key (:seon.call-preparation/key candidate)
   :seon.call-preparation/supplier-symbol
   (:seon.call-preparation/supplier-symbol candidate)})

(defn- arity-plan-rows
  [arities slots-by-arity entries-by-arity]
  (mapv (fn [[order minimum argument-count maximum]]
          (let [order (long order)]
            {:seon.fn.arity/order order
             :seon.fn.arity/min (long minimum)
             :seon.fn.arity/argument-count (long argument-count)
             :seon.call-preparation/variadic? (neg? (long maximum))
             :seon.call-preparation/rest-slot?
             (true? (get-in slots-by-arity [order :rest-slot?]))
             :seon.call-preparation/slots
             (vec (sort-by :seon.fn.argument/index
                           (get-in slots-by-arity [order :slots] [])))
             :seon.call-preparation/entries
             (vec (sort-by :seon.fn.argument/index
                           (get entries-by-arity order [])))}))
        (sort-by first arities)))

(defn plan-for
  "Compile one function identity's invocation plan from database facts.

  One query set over the P12 argument addresses joined to the acquired
  supplied-default rows. It never reads `:seon.fn/spec`, source text, a
  serialized AST string, or a hand-maintained list. Returns nil when the
  identity is not a contracted function in this cluster's program graph."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.call-preparation/snapshot
         :seon.fn/sym]
    [:or :seon.call-preparation/plan :nil]]}
  [database current sym]
  (let [contract-t (contract-transaction database sym)]
    (when (number? contract-t)
      (let [index (by-fingerprint
                   (:seon.call-preparation/supplied-defaults current))
            fingerprints (vec (keys index))
            arities (db/q database arity-query sym)
            positional (when (seq fingerprints)
                         (db/q database positional-query sym fingerprints))
            entries (when (seq fingerprints)
                      (db/q database map-entry-query sym fingerprints))
            positional-slots-by-arity
            (reduce (fn [acc [order position rest? fingerprint]]
                      (let [candidate (get index fingerprint)]
                        (cond-> acc
                          (and candidate (not rest?))
                          (update-in [(long order) :slots]
                                     (fnil conj [])
                                     (slot-of candidate position))
                          (and candidate rest?)
                          (assoc-in [(long order) :rest-slot?] true))))
                    {}
                    positional)
            entries-by-arity
            (reduce (fn [acc [order position entry-key fingerprint]]
                      (let [candidate (get index fingerprint)]
                        ;; Both halves of the ruled join: declared key AND
                        ;; value shape.
                        (if (and candidate
                                 (= entry-key
                                    (:seon.call-preparation/key candidate)))
                          (update acc (long order) (fnil conj [])
                                  (assoc (slot-of candidate position)
                                         :seon.call-preparation/entry-key
                                         entry-key))
                          acc)))
                    {}
                    entries)
            slots-by-arity
            (reduce-kv
             (fn [acc [order position] required]
               (let [entries (filterv #(= position (:seon.fn.argument/index %))
                                      (get entries-by-arity order))]
                 (if (= (set (map #(nth % 2) required))
                        (set (map :seon.call-preparation/entry-key entries)))
                   (update-in acc [order :slots] (fnil conj [])
                              {:seon.fn.argument/index position
                               :seon.call-preparation/entries entries})
                   acc)))
             positional-slots-by-arity
             (group-by (fn [[order position _]] [order position])
                       (db/q database required-map-entry-query sym)))
            validators (when (some #(> (count (:slots %)) 1)
                                   (vals slots-by-arity))
                         (argument-validators database current sym))
            arity-plans
            (mapv (fn [arity]
                    (cond-> arity
                      (and (not (:seon.call-preparation/variadic? arity))
                           (> (count (:seon.call-preparation/slots arity)) 1))
                      (assoc :seon.call-preparation/argument-validators
                             (get validators (:seon.fn.arity/order arity)))))
                  (arity-plan-rows arities slots-by-arity entries-by-arity))
            fixed (remove :seon.call-preparation/variadic? arity-plans)
            exact-by-count
            (reduce (fn [acc arity]
                      (update acc (:seon.fn.arity/argument-count arity)
                              (fnil conj [])
                              {:seon.call-preparation/ambiguous? false
                               :seon.fn.arity/order (:seon.fn.arity/order arity)
                               :seon.call-preparation/inserts []
                               :seon.call-preparation/entries
                               (:seon.call-preparation/entries arity)}))
                    {}
                    fixed)
            ;; ONE derived shape per fixed arity: the arity minus ALL its
            ;; suppliable slots. No middle-omission combinatorics — a
            ;; Partial omissions are decided from the actual argument values
            ;; in prepare; they are never enumerated in this count index.
            derived-by-count
            (reduce
             (fn [acc arity]
               (let [slots (:seon.call-preparation/slots arity)]
                 (if (empty? slots)
                   acc
                   (update acc (- (:seon.fn.arity/argument-count arity)
                                  (count slots))
                           (fnil conj [])
                           {:seon.call-preparation/ambiguous? false
                            :seon.fn.arity/order (:seon.fn.arity/order arity)
                            :seon.call-preparation/inserts slots
                            :seon.call-preparation/entries
                            (:seon.call-preparation/entries arity)}))))
             {}
             fixed)
            by-supplied-count
            (into {}
                  (keep (fn [supplied]
                          (when-let [answer
                                     (resolve-count
                                      (get exact-by-count supplied)
                                      (get derived-by-count supplied))]
                            [supplied answer])))
                  (into (set (keys exact-by-count))
                        (keys derived-by-count)))
            variadic (first (filter :seon.call-preparation/variadic?
                                    arity-plans))]
        (cond-> {:seon.fn/sym sym
                 :seon.call-preparation/contract-t (long contract-t)
                 :seon.call-preparation/basis-t
                 (long (:seon.call-preparation/basis-t current))
                 :seon.call-preparation/arities arity-plans
                 :seon.call-preparation/by-supplied-count by-supplied-count
                 :seon.call-preparation/empty?
                 (every? (fn [arity]
                           (and (empty? (:seon.call-preparation/slots arity))
                                (empty? (:seon.call-preparation/entries arity))
                                (not (:seon.call-preparation/rest-slot?
                                      arity))))
                         arity-plans)}
          variadic
          (assoc :seon.call-preparation/variadic
                 {:seon.fn.arity/order (:seon.fn.arity/order variadic)
                  :seon.fn.arity/min (:seon.fn.arity/min variadic)
                  :seon.call-preparation/rest-slot?
                  (:seon.call-preparation/rest-slot? variadic)
                  :seon.call-preparation/entries
                  (:seon.call-preparation/entries variadic)}))))))

(defn prepared-arities
  "Source argument-count ranges compatible with an invocation plan.

  Fixed arities may omit the plan's suppliable slots. Intermediate counts
  still require prepare's value-dependent unique placement; this count-only
  projection neither chooses positions nor runs suppliers. Variadic calls
  retain their declared minimum: prepare does not insert their fixed slots."
  {:malli/schema [:=> [:cat :seon.call-preparation/plan]
                  :seon.fn/declared-arities]}
  [plan-value]
  (->> (:seon.call-preparation/arities plan-value)
       (map (fn [arity]
              (if (:seon.call-preparation/variadic? arity)
                {:seon.fn.arity/min (:seon.fn.arity/min arity)}
                (let [n (:seon.fn.arity/argument-count arity)]
                  {:seon.fn.arity/min
                   (- n (count (:seon.call-preparation/slots arity)))
                   :seon.fn.arity/max n}))))
       distinct
       (sort-by (juxt :seon.fn.arity/min
                      #(get % :seon.fn.arity/max Long/MAX_VALUE)))
       vec))

(defn plan
  "The cached invocation plan for one function identity in this cluster.

  Cache key is `[function-identity contract-t supplied-default-basis-t]`,
  held one entry per identity so a redefinition REPLACES its plan rather
  than accumulating generations. A newly acquired context starts empty.

  The contract transaction is re-read at most ONCE PER BASIS, not once
  per call: a redefinition necessarily commits, so a database value whose
  basis this entry was already verified against cannot be hiding a newer
  contract. Without that fence the warm path ran an aggregating Datalog
  query on every single prepared call."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/state :seon.db/database-value
         :seon.call-preparation/snapshot :seon.fn/sym]
    [:or :seon.call-preparation/plan :nil]]}
  [call-state database current sym]
  (let [held (get-in @call-state [:seon.call-preparation/plans sym])
        basis (long (:seon.call-preparation/basis-t current))
        through (long (:seon.call-preparation/checked-through-t current))
        usable? (and held
                     (= basis (:seon.call-preparation/basis-t
                               (:seon.call-preparation/plan held))))]
    (cond
      (and usable?
           (= through (:seon.call-preparation/verified-through-t held)))
      (:seon.call-preparation/plan held)

      (and usable?
           (= (:seon.call-preparation/contract-t held)
              (contract-transaction database sym)))
      (do (swap! call-state assoc-in
                 [:seon.call-preparation/plans sym
                  :seon.call-preparation/verified-through-t]
                 through)
          (:seon.call-preparation/plan held))

      :else
      (let [compiled (plan-for database current sym)]
        (when compiled
          (swap! call-state assoc-in [:seon.call-preparation/plans sym]
                 {:seon.call-preparation/contract-t
                  (:seon.call-preparation/contract-t compiled)
                  :seon.call-preparation/verified-through-t through
                  :seon.call-preparation/plan compiled}))
        compiled))))

;;; ---------------------------------------------------------------------------
;;; Supplying one value
;;; ---------------------------------------------------------------------------

(defn- unavailable
  {:malli/schema [:=> [:cat :seon.fn/sym :seon.call-preparation/slot :seon.schema/value]
                  :seon.call-preparation/unavailable-error]}
  [sym slot cause]
  (error/diagnostic
 {:seon.error/at (java.util.Date.)
 :seon.error/layer :seon.call-preparation/call
 :seon.error/operation 'seon.call-preparation/unavailable
 :seon.error/message (str "Cannot call " sym ": " (:seon.call-preparation/key slot)
        " is unavailable. "
        (or (:seon.error/message cause)
            "Its supplier produced no value."))
 :seon.call-preparation/target-symbol sym
 :seon.call-preparation/unavailable-key (:seon.call-preparation/key slot)
 :seon.call-preparation/supplier-symbol (:seon.call-preparation/supplier-symbol slot)
 :seon.fn.argument/index (:seon.fn.argument/index slot)
 :seon.error/diagnostic-layer :seon.call-preparation/call
 :seon.error/diagnostic-operation 'seon.call-preparation/unavailable
 :seon.error/diagnostic-member (:seon.call-preparation/key slot)
 :seon.error/diagnostic-expected "an available supplied default"
 :seon.error/offending cause
 :seon.error/diagnostic-offending cause
 :seon.error/diagnostic-cause :seon.call-preparation/unavailable
 :seon.error/diagnostic-evidence (cond-> {:seon.fn/sym sym
            :seon.call-preparation/key (:seon.call-preparation/key slot)
            :seon.call-preparation/supplier-symbol
            (:seon.call-preparation/supplier-symbol slot)
            :seon.fn.argument/index (:seon.fn.argument/index slot)}
     (:seon.call-preparation/entry-key slot)
     (assoc :seon.call-preparation/entry-key
            (:seon.call-preparation/entry-key slot))
     (map? cause)
     (assoc :seon.call-preparation/cause cause))
 :seon.error/data (cond-> {:seon.fn/sym sym
            :seon.call-preparation/key (:seon.call-preparation/key slot)
            :seon.call-preparation/supplier-symbol
            (:seon.call-preparation/supplier-symbol slot)
            :seon.fn.argument/index (:seon.fn.argument/index slot)}
     (:seon.call-preparation/entry-key slot)
     (assoc :seon.call-preparation/entry-key
            (:seon.call-preparation/entry-key slot))
     (map? cause)
     (assoc :seon.call-preparation/cause cause))}))

(defn supply
  "Call one supplied default's supplier with this call's environment.

  The symbol is resolved LIVE, so hot reload stays visible and no cached
  plan captures a function root. The result is validated against the row's
  value schema even when the ordinary instrumentation dial is `:record`: a
  wrong-shaped success is the supplier's fault and must never reach the
  target disguised as its own contract violation."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/snapshot :seon.env/environment
         :seon.call-preparation/slot :seon.fn/sym]
    [:or :seon.schema/value :seon.call-preparation/unavailable-error :seon.call-preparation/invalid-supplied-value-error]]}
  [current environment slot sym]
  (let [default-key (:seon.call-preparation/key slot)
        symbol-name (:seon.call-preparation/supplier-symbol slot)
        resolved (try (requiring-resolve symbol-name)
                      (catch Throwable _ nil))]
    (if-not (var? resolved)
      (unavailable sym slot
                   (error/diagnostic
 {:seon.error/at (java.util.Date.)
 :seon.error/layer :seon.call-preparation/call
 :seon.error/operation 'seon.call-preparation/supply
 :seon.error/message (str "No callable is installed for "
                                     symbol-name ".")
 :seon.call-preparation/unresolved-symbol symbol-name
 :seon.error/diagnostic-layer :seon.call-preparation/call
 :seon.error/diagnostic-operation 'seon.call-preparation/supply
 :seon.error/diagnostic-member symbol-name
 :seon.error/diagnostic-expected "an installed callable Var"
 :seon.error/diagnostic-offending symbol-name
 :seon.error/diagnostic-cause :seon.call-preparation/unresolved-supplier
 :seon.error/diagnostic-evidence {:seon.call-preparation/supplier-symbol
                                 symbol-name}
 :seon.error/data {:seon.call-preparation/supplier-symbol
                                 symbol-name}}))
      (let [produced (try (resolved environment)
                          (catch Throwable cause
                            (error/diagnostic
 {:seon.error/at (java.util.Date.)
 :seon.error/layer :seon.call-preparation/call
 :seon.error/operation 'seon.call-preparation/supply
 :seon.error/message (or (ex-message cause) "The supplier threw.")
 :seon.call-preparation/thrown-supplier symbol-name
 :seon.error/exception-class (symbol (.getName (class cause)))
 :seon.error/diagnostic-layer :seon.call-preparation/call
 :seon.error/diagnostic-operation 'seon.call-preparation/supply
 :seon.error/diagnostic-member symbol-name
 :seon.error/diagnostic-expected "a returned supplied value"
 :seon.error/offending cause
 :seon.error/diagnostic-offending cause
 :seon.error/diagnostic-cause :seon.call-preparation/supplier-threw
 :seon.error/diagnostic-evidence {:seon.call-preparation/supplier-symbol
                              symbol-name}
 :seon.error/data {:seon.call-preparation/supplier-symbol
                              symbol-name}})))
            valid? (get (:seon.call-preparation/validators current)
                        default-key)]
        (cond
          (seq (@error-facets (:seon.schema/projection current) produced)) (unavailable sym slot produced)

          (or (nil? valid?) (valid? produced)) produced

          :else
          (error/diagnostic
 {:seon.error/at (java.util.Date.)
 :seon.error/layer :seon.call-preparation/call
 :seon.error/operation 'seon.call-preparation/supply
 :seon.error/message (str symbol-name " produced a value that is not "
                (:seon.call-preparation/schema-key
                 (get (:seon.call-preparation/supplied-defaults current)
                      default-key))
                ". This is a fault at the supplier, not at " sym ".")
 :seon.call-preparation/target-symbol sym
 :seon.call-preparation/invalid-key default-key
 :seon.call-preparation/supplier-symbol symbol-name
 :seon.call-preparation/schema-key (:seon.call-preparation/schema-key (get (:seon.call-preparation/supplied-defaults current) default-key))
 :seon.error/offending produced
 :seon.error/diagnostic-layer :seon.call-preparation/call
 :seon.error/diagnostic-operation 'seon.call-preparation/supply
 :seon.error/diagnostic-member default-key
 :seon.error/diagnostic-expected (:seon.call-preparation/schema-key (get (:seon.call-preparation/supplied-defaults current) default-key))
 :seon.error/diagnostic-offending produced
 :seon.error/diagnostic-cause :seon.call-preparation/invalid-supplied-value
 :seon.error/diagnostic-evidence {:seon.fn/sym sym
            :seon.call-preparation/key default-key
            :seon.call-preparation/supplier-symbol symbol-name}
 :seon.error/data {:seon.fn/sym sym
            :seon.call-preparation/key default-key
            :seon.call-preparation/supplier-symbol symbol-name}}))))))

;;; ---------------------------------------------------------------------------
;;; The consumer seam
;;; ---------------------------------------------------------------------------

(defn callee-identity
  "The program identity of a resolved callee, or nil when it has none.

  The database's own `:seon.fn/sym` spelling, because that is what every
  plan and every gate is keyed by. Both
  `clojure.lang.Var` and `sci.lang.Var` carry `:ns`/`:name` metadata;
  anything else — a closure, a computed callee — has no provable identity
  and is left untouched."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "SCI supplies JVM Vars, SCI Vars and interpreted function objects; an unrecognized callable has no durable identity.", :gen/elements [nil false 0 "" :k [] {}]}]] [:maybe :seon.fn/sym]]}
  [callee]
  (let [{ns-value :ns name-value :name} (meta callee)]
    (when (and ns-value name-value)
      (symbol (str ns-value) (str name-value)))))

(defn- decided
  "Resolve a predicate dispatch against the call's actual first argument.

  Ruling 2's disambiguation, evaluated at call time because it depends on
  a value: the leading omitted slot's OWN declared value schema decides
  whether the caller supplied that value or wrote the shorter shape."
  [current answer arguments]
  (if-let [dispatch (:seon.call-preparation/dispatch answer)]
    (let [valid? (get (:seon.call-preparation/validators current)
                      (:seon.call-preparation/key dispatch))]
      (if (and valid? (valid? (nth arguments 0 nil)))
        (:seon.call-preparation/supplied dispatch)
        (:seon.call-preparation/omitted dispatch)))
    answer))

(defn- partial-insertions
  "Keep at most two paths per [declared position, supplied count].

  Matching has polynomial work in the declared arity and actual argument
  count. Two paths suffice to prove ambiguity; enumerating every omitted
  subset would make a large ambiguous arity exponential. No supplier runs
  until the complete placement has been decided."
  [arity arguments]
  (let [slots (into {} (map (juxt :seon.fn.argument/index identity))
                    (:seon.call-preparation/slots arity))
        validators (:seon.call-preparation/argument-validators arity)
        supplied (count arguments)
        retain (fn [states consumed paths]
                 (update states consumed
                         #(into [] (take 2) (concat % paths))))]
    (when (and validators
               (< supplied (:seon.fn.arity/argument-count arity)))
      (get
       (reduce
        (fn [states [position valid?]]
          (reduce-kv
           (fn [next-states consumed paths]
             (cond-> next-states
               (and (< consumed supplied) (valid? (nth arguments consumed)))
               (retain (inc consumed) paths)
               (get slots position)
               (retain consumed (mapv #(conj % (get slots position)) paths))))
           (sorted-map) states))
        (sorted-map 0 [[]])
        (map-indexed vector validators))
       supplied))))

(defn- partial-preparation
  [plan-value arguments]
  (let [candidates
        (into []
              (comp
               (mapcat
                (fn [arity]
                  (map (fn [inserts]
                         {:seon.call-preparation/ambiguous? false
                          :seon.fn.arity/order (:seon.fn.arity/order arity)
                          :seon.call-preparation/inserts inserts
                          :seon.call-preparation/entries
                          (:seon.call-preparation/entries arity)})
                       (partial-insertions arity arguments))))
               (take 2))
              (:seon.call-preparation/arities plan-value))]
    (case (count candidates)
      0 nil
      1 (first candidates)
      {:seon.call-preparation/ambiguous? true
       :seon.call-preparation/candidates (candidate-indexes candidates)})))

(defn prepare
  "Prepare one call's arguments against its plan, or refuse as a value.

  An unavailable-error names a supplier that could not produce a value;
  an invalid-supplied-value-error names a value outside its declared schema;
  an ambiguous-call-error names multiple placements at one supplied count.
  Each refusal prevents entry into the target. Explicit caller arguments
  remain unchanged and the target's ordinary contract validates them.

  Caller presence is tested by argument occupancy and `contains?`, never
  truthiness: a supplied nil reaches ordinary Malli input validation."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/snapshot :seon.env/environment
         [:maybe :seon.call-preparation/plan] :seon.schema/arguments]
    [:or :seon.schema/arguments :seon.call-preparation/ambiguous-call-error :seon.call-preparation/unavailable-error :seon.call-preparation/invalid-supplied-value-error]]}
  [current environment plan-value arguments]
  (if (or (nil? plan-value) (:seon.call-preparation/empty? plan-value))
    arguments
    (let [sym (:seon.fn/sym plan-value)
          supplied (count arguments)
          answer (or (some-> (get (:seon.call-preparation/by-supplied-count
                                   plan-value)
                                  supplied)
                             (as-> found (decided current found arguments)))
                     (partial-preparation plan-value arguments))]
      (cond
        (nil? answer) arguments

        (:seon.call-preparation/ambiguous? answer)
        (error/diagnostic
 {:seon.error/at (java.util.Date.)
 :seon.error/layer :seon.call-preparation/call
 :seon.error/operation 'seon.call-preparation/prepare
 :seon.error/message (str "Cannot call " sym " with " supplied
              " arguments: more than one derived call shape fits that count, "
              "so which positions were named is not determined. Pass the "
              "declared arguments in full.")
 :seon.call-preparation/target-symbol sym
 :seon.call-preparation/supplied-count supplied
 :seon.call-preparation/candidate-count (count (:seon.call-preparation/candidates answer))
 :seon.error/diagnostic-layer :seon.call-preparation/call
 :seon.error/diagnostic-operation 'seon.call-preparation/prepare
 :seon.error/diagnostic-member sym
 :seon.error/diagnostic-expected "one uniquely determined argument placement"
 :seon.error/offending {:seon.call-preparation/candidates (:seon.call-preparation/candidates answer)
                        :seon.schema/arguments arguments}
 :seon.error/diagnostic-offending arguments
 :seon.error/diagnostic-cause :seon.call-preparation/ambiguous-call
 :seon.error/diagnostic-evidence {:seon.fn/sym sym
          :seon.call-preparation/supplied-count supplied
          :seon.call-preparation/candidates
          (:seon.call-preparation/candidates answer)}
 :seon.error/data {:seon.fn/sym sym
          :seon.call-preparation/supplied-count supplied
          :seon.call-preparation/candidates
          (:seon.call-preparation/candidates answer)}})

        :else
        (let [refusal (volatile! nil)
              value-for (fn [slot]
                          (let [produced (supply current environment slot sym)]
                            (when (and (map? produced) (or (:seon.call-preparation/unavailable-key produced) (:seon.call-preparation/invalid-key produced)))
                              (vreset! refusal produced))
                            produced))
              with-inserts
              (reduce (fn [args slot]
                        (let [position (:seon.fn.argument/index slot)
                              produced (if (:seon.call-preparation/entries slot)
                                         {}
                                         (value-for slot))]
                          (if @refusal
                            args
                            (into (conj (subvec args 0 position) produced)
                                  (subvec args position)))))
                      (vec arguments)
                      (:seon.call-preparation/inserts answer))
              filled
              (reduce (fn [args entry]
                        (let [position (:seon.fn.argument/index entry)
                              entry-key (:seon.call-preparation/entry-key entry)
                              target (nth args position nil)]
                          (if (or @refusal
                                  (not (map? target))
                                  (contains? target entry-key))
                            args
                            (let [produced (value-for entry)]
                              (if @refusal
                                args
                                (assoc args position
                                       (assoc target entry-key produced)))))))
                      with-inserts
                      (:seon.call-preparation/entries answer))]
          (or @refusal filled))))))

(defn hook
  "The `:call-preparation-hook` sci calls on every direct Var call.

  `(hook ctx var args) -> args | (reduced result)`
  (`reference-code/sci/src/sci/core.cljc:309`). The ctx is the RUNTIME
  fork's, so the environment it carries is the one this call actually runs
  under — including on a virtual thread, because the ctx travels with the
  code rather than the thread.

  Everything a call needs is read from the ctx: no dynamic var, no cluster
  lookup, no effect request. A ctx with no call-preparation state, no
  environment, or no plan for this callee is passed through untouched, so
  the hook is inert in a non-Seon context. The return is genuinely
  polymorphic — sci's contract is `args` OR a `reduced` result.

  THE HOOK IS ARMED ON EVERY CALL IN THE CLUSTER, so the path for a
  callee that declares nothing suppliable is the one that matters. It
  ends at the snapshot's `prepared-symbols` set: one connection deref,
  one basis comparison, one string, one set lookup — no plan lookup, no
  Datalog, no supplier, no argument copy."
  {:malli/schema
   [:=> [:cat :map [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "SCI's call-preparation hook receives arbitrary host or interpreted callable objects and forwards arguments to that callable's own contract.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.schema/arguments] :seon.schema/value]}
  [ctx callee arguments]
  (let [call-state (get ctx carrier)
        environment (env/of ctx)
        projection (:seon.schema/projection ctx)
        connection (when (and call-state environment projection)
                     (:seon.db/connection environment))
        database (when connection (db/db connection))]
    (if-not (and database (not (and (map? database) (contains? database :seon.error/at) (contains? database :seon.error/layer) (contains? database :seon.error/operation)) ;; debt: seon.db/db passes seon.db generic :seon.error/value
))
      arguments
      (let [current (current-snapshot call-state database projection)]
        (if (and (map? current) (contains? current :seon.error/at) (contains? current :seon.error/layer) (contains? current :seon.error/operation)) ;; debt: seon.call-preparation/current-snapshot passes seon.db generic :seon.error/value

          arguments
          (let [sym (callee-identity callee)]
            (if-not (contains? (:seon.call-preparation/prepared-symbols current)
                               sym)
              arguments
              (let [environment (env/scope environment {:my.program/executing-ctx ctx})
                    prepared (prepare current environment
                                      (plan call-state database current sym)
                                      (vec arguments))]
                (if (and (map? prepared) (or (:seon.call-preparation/unavailable-key prepared) (:seon.call-preparation/invalid-key prepared) (:seon.call-preparation/candidate-count prepared)))
                  (reduced prepared)
                  prepared)))))))))
