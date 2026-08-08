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

  This namespace is P17 slice S1: the rows, the plan-derivation query, and
  the cluster-local plan cache. Wiring the hook into
  `seon.sci.eval/evaluate` and `kernel/invoke` — and the complete argument
  transformation with its three failure faces — is S2. [[prepare]] here is
  the composition seam S2 finishes, not the finished behavior matrix."
  (:require [clojure.test.check.generators :as gen]
            [datahike.core :as datahike]
            [seon.db :as db]
            [seon.env :as env]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(set! *warn-on-reflection* true)

;;; ---------------------------------------------------------------------------
;;; The cluster-local state
;;; ---------------------------------------------------------------------------

(defn state?
  "True for the atom holding one cluster's supplied defaults and plans."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? clojure.lang.IAtom value))

(schema/register-core-predicate! 'seon.call-preparation/state? state?)

(def state-generator
  (gen/fmap atom (gen/return {})))

(schema.edn/load! {})

(def ^:private empty-snapshot
  {:seon.call-preparation/supplied-defaults {}
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

  S2 calls this in `seon.sci.eval/cluster-ctx` beside the projection
  state; until then a probe or test attaches it to a scratch ctx."
  {:malli/schema [:=> [:cat :map] :map]}
  [ctx]
  (assoc ctx carrier (state)))

;;; ---------------------------------------------------------------------------
;;; Errors as values
;;; ---------------------------------------------------------------------------

(defn- error-value
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

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
  ;; proof: one argument (this cluster's environment) and a two-armed `:or`
  ;; return — the row's value shape plus `:seon.error/value`.
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
  [default-key reason data]
  (error-value
   :seon.call-preparation/incoherent-supplier
   (str "The supplied default " default-key " is not admissible: " reason)
   (assoc data :seon.call-preparation/key default-key)))

(defn- coherent-supplier
  "Prove one row against the program graph, or refuse it as a value.

  Refusing at acquisition is the point: an incoherent row is never
  installed, so a wrong-shaped value can never be handed to a target
  function and disguised as that function's own contract violation."
  [database
   {default-key :seon.call-preparation/key
    schema-key :seon.call-preparation/schema-key
    fingerprint :seon.call-preparation/shape
    supplier :seon.call-preparation/supplier-symbol}
   environment-fingerprint error-fingerprint]
  (let [shapes (db/q database supplier-shape-query (str supplier))]
    (cond
      (error-value? shapes) shapes

      (empty? shapes)
      (incoherent default-key
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
                             (str supplier)))]
        (cond
          (not (and (zero? (long order)) (= 1 (long argument-count))))
          (incoherent default-key
                      (str supplier " must take exactly one argument, this "
                           "cluster's environment; it declares "
                           argument-count ".")
                      {:seon.call-preparation/supplier-symbol supplier
                       :seon.fn.arity/argument-count argument-count})

          (not= argument-fingerprint environment-fingerprint)
          (incoherent default-key
                      (str supplier "'s argument is not "
                           ":seon.env/environment. A supplier reads the "
                           "environment it is called with and nothing else.")
                      {:seon.call-preparation/supplier-symbol supplier})

          (not= :or return-type)
          (incoherent default-key
                      (str supplier " must declare a two-armed return: its "
                           "value schema or :seon.error/value. It declares a "
                           (pr-str return-type) ".")
                      {:seon.call-preparation/supplier-symbol supplier})

          (not= arms #{fingerprint error-fingerprint})
          (incoherent default-key
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
  (let [history (db/history database)
        historical (when-not (error-value? history)
                     (db/q history historical-row-transaction-query
                           attributes))
        current (db/q database current-row-transaction-query attributes)]
    (long (or (when (number? historical) historical)
              (when (number? current) current)
              0))))

(defn snapshot
  "Derive the complete supplied-default snapshot from one database value.

  Every row is proved against the program graph here; incoherent rows
  become refusals and are NOT installed. The returned value carries its
  own two transactions: `checked-through-t` is the database value this was
  derived from, and `basis-t` is the newest transaction touching any row
  fact — the third element of every plan cache key."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schema/projection]
    [:or :seon.call-preparation/snapshot :seon.error/value]]}
  [database projection]
  (let [rows (db/q database row-query)]
    (if (error-value? rows)
      rows
      (let [environment-fingerprint
            (db/q database schema-fingerprint-query :seon.env/environment)
            error-fingerprint
            (db/q database schema-fingerprint-query :seon.error/value)
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
                                        error-fingerprint)])
                  candidates)
            admitted
            (into {}
                  (comp (remove second)
                        (map (fn [[candidate _]]
                               [(:seon.call-preparation/key candidate)
                                candidate])))
                  proved)]
        {:seon.call-preparation/supplied-defaults admitted
         :seon.call-preparation/validators
         (into {}
               (map (fn [[default-key candidate]]
                      [default-key
                       (schema/projection-validator
                        projection
                        (:seon.call-preparation/schema-key candidate))]))
               admitted)
         :seon.call-preparation/refusals (into [] (keep second) proved)
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
    [:or :seon.call-preparation/snapshot :seon.error/value]]}
  [call-state database projection]
  (let [basis (db/basis-t database)]
    (if (error-value? basis)
      basis
      (let [held (:seon.call-preparation/snapshot @call-state)]
        (if (>= (long (:seon.call-preparation/checked-through-t held))
                (long basis))
          held
          (let [derived (snapshot database projection)]
            (if (error-value? derived)
              derived
              (:seon.call-preparation/snapshot
               (swap! call-state adopt derived)))))))))

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
           (when-not (error-value? derived)
             (swap! call-state adopt derived))))))))

;;; ---------------------------------------------------------------------------
;;; Plan derivation — one Datalog query set over the P12 argument addresses
;;; ---------------------------------------------------------------------------

(def ^:private contract-transaction-query
  '[:find (max ?tx) .
    :in $ ?sym
    :where
    [?function :seon.fn/sym ?sym]
    [?function _ _ ?tx]])

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

(defn- by-fingerprint
  [supplied-defaults]
  (into {}
        (map (fn [[_ candidate]]
               [(:seon.call-preparation/shape candidate) candidate]))
        supplied-defaults))

(defn- subsets-of-size
  [items size]
  (cond
    (zero? size) [[]]
    (> size (count items)) []
    :else (let [head (first items)
                tail (vec (rest items))]
            (into (mapv #(into [head] %) (subsets-of-size tail (dec size)))
                  (subsets-of-size tail size)))))

(defn- resolve-count
  "One supplied-argument count's unique preparation, or its refusal.

  Exact full-arity precedence first: a call whose count matches a declared
  arity invokes that arity unchanged, and only its argument-map entries are
  filled. Otherwise exactly one arity and one omitted-slot subset may fit;
  more than one is a structural ambiguity that refuses rather than guessing
  by arity order."
  [exact expansions]
  (cond
    (= 1 (count exact)) (first exact)

    (seq exact)
    {:seon.call-preparation/ambiguous? true
     :seon.call-preparation/candidates (mapv (constantly []) exact)}

    (= 1 (count expansions)) (first expansions)

    (seq expansions)
    {:seon.call-preparation/ambiguous? true
     :seon.call-preparation/candidates
     (mapv (fn [candidate]
             (mapv :seon.fn.argument/index
                   (:seon.call-preparation/inserts candidate)))
           expansions)}))

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
    [:or :seon.call-preparation/plan :seon.error/value :nil]]}
  [database current sym]
  (let [contract-t (db/q database contract-transaction-query sym)]
    (when (number? contract-t)
      (let [index (by-fingerprint
                   (:seon.call-preparation/supplied-defaults current))
            fingerprints (vec (keys index))
            arities (db/q database arity-query sym)
            positional (when (seq fingerprints)
                         (db/q database positional-query sym fingerprints))
            entries (when (seq fingerprints)
                      (db/q database map-entry-query sym fingerprints))
            slots-by-arity
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
            arity-plans (arity-plan-rows arities slots-by-arity
                                         entries-by-arity)
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
            expansions-by-count
            (reduce
             (fn [acc arity]
               (let [slots (:seon.call-preparation/slots arity)
                     total (:seon.fn.arity/argument-count arity)]
                 (reduce
                  (fn [acc omitted]
                    (update acc (- total (count omitted))
                            (fnil conj [])
                            {:seon.call-preparation/ambiguous? false
                             :seon.fn.arity/order (:seon.fn.arity/order arity)
                             :seon.call-preparation/inserts (vec omitted)
                             :seon.call-preparation/entries
                             (:seon.call-preparation/entries arity)}))
                  acc
                  (mapcat #(subsets-of-size slots %)
                          (range 1 (inc (count slots)))))))
             {}
             fixed)
            by-supplied-count
            (into {}
                  (keep (fn [supplied]
                          (when-let [answer
                                     (resolve-count
                                      (get exact-by-count supplied)
                                      (get expansions-by-count supplied))]
                            [supplied answer])))
                  (into (set (keys exact-by-count))
                        (keys expansions-by-count)))
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

(defn plan
  "The cached invocation plan for one function identity in this cluster.

  Cache key is `[function-identity contract-t supplied-default-basis-t]`,
  held one entry per identity so a redefinition REPLACES its plan rather
  than accumulating generations. A newly acquired context starts empty."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/state :seon.db/database-value
         :seon.call-preparation/snapshot :seon.fn/sym]
    [:or :seon.call-preparation/plan :seon.error/value :nil]]}
  [call-state database current sym]
  (let [held (get-in @call-state [:seon.call-preparation/plans sym])
        basis (long (:seon.call-preparation/basis-t current))]
    (if (and held
             (= basis (:seon.call-preparation/basis-t
                       (:seon.call-preparation/plan held)))
             (= (:seon.call-preparation/contract-t held)
                (db/q database contract-transaction-query sym)))
      (:seon.call-preparation/plan held)
      (let [compiled (plan-for database current sym)]
        (when (and compiled (not (error-value? compiled)))
          (swap! call-state assoc-in [:seon.call-preparation/plans sym]
                 {:seon.call-preparation/contract-t
                  (:seon.call-preparation/contract-t compiled)
                  :seon.call-preparation/plan compiled}))
        compiled))))

;;; ---------------------------------------------------------------------------
;;; Supplying one value
;;; ---------------------------------------------------------------------------

(defn- unavailable
  [sym slot cause]
  (error-value
   :seon.call-preparation/unavailable
   (str "Cannot call " sym ": " (:seon.call-preparation/key slot)
        " is unavailable. "
        (or (:seon.error/message cause)
            "Its supplier produced no value."))
   (cond-> {:seon.fn/sym sym
            :seon.call-preparation/key (:seon.call-preparation/key slot)
            :seon.call-preparation/supplier-symbol
            (:seon.call-preparation/supplier-symbol slot)
            :seon.fn.argument/index (:seon.fn.argument/index slot)}
     (:seon.call-preparation/entry-key slot)
     (assoc :seon.call-preparation/entry-key
            (:seon.call-preparation/entry-key slot))
     (map? cause)
     (assoc :seon.call-preparation/cause cause))))

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
    [:or :seon.schema/value :seon.error/value]]}
  [current environment slot sym]
  (let [default-key (:seon.call-preparation/key slot)
        symbol-name (:seon.call-preparation/supplier-symbol slot)
        resolved (try (requiring-resolve symbol-name)
                      (catch Throwable _ nil))]
    (if-not (var? resolved)
      (unavailable sym slot
                   (error-value :seon.call-preparation/unresolved-supplier
                                (str "No callable is installed for "
                                     symbol-name ".")
                                {:seon.call-preparation/supplier-symbol
                                 symbol-name}))
      (let [produced (try (resolved environment)
                          (catch Throwable cause
                            (error-value
                             :seon.call-preparation/supplier-threw
                             (or (ex-message cause) "The supplier threw.")
                             {:seon.call-preparation/supplier-symbol
                              symbol-name})))
            valid? (get (:seon.call-preparation/validators current)
                        default-key)]
        (cond
          (error-value? produced) (unavailable sym slot produced)

          (or (nil? valid?) (valid? produced)) produced

          :else
          (error-value
           :seon.call-preparation/invalid-supplied-value
           (str symbol-name " produced a value that is not "
                (:seon.call-preparation/schema-key
                 (get (:seon.call-preparation/supplied-defaults current)
                      default-key))
                ". This is a fault at the supplier, not at " sym ".")
           {:seon.fn/sym sym
            :seon.call-preparation/key default-key
            :seon.call-preparation/supplier-symbol symbol-name}))))))

;;; ---------------------------------------------------------------------------
;;; The consumer seam
;;; ---------------------------------------------------------------------------

(defn var-symbol
  "The program identity of a resolved callee, or nil when it has none.

  Both `clojure.lang.Var` and `sci.lang.Var` carry `:ns`/`:name` metadata;
  anything else — a closure, a computed callee — has no provable identity
  and is left untouched."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :qualified-symbol]]}
  [callee]
  (let [{ns-value :ns name-value :name} (meta callee)]
    (when (and ns-value name-value)
      (symbol (str ns-value) (str name-value)))))

(defn prepare
  "Prepare one call's arguments against its plan, or refuse as a value.

  S1 composition seam: it proves that a plan, the suppliers, and the sci
  hook compose. S2 owns the complete behavior matrix — the
  `db?`/`connection?` predicate dispatch that preserves ruling #41's
  positional shortcut, and the three failure faces in their final form.

  Caller presence always wins, tested by argument occupancy and
  `contains?`, never truthiness: a supplied nil reaches ordinary Malli
  input validation."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/snapshot :seon.env/environment
         [:maybe :seon.call-preparation/plan] :seon.schema/arguments]
    [:or :seon.schema/arguments :seon.error/value]]}
  [current environment plan-value arguments]
  (if (or (nil? plan-value) (:seon.call-preparation/empty? plan-value))
    arguments
    (let [sym (:seon.fn/sym plan-value)
          supplied (count arguments)
          answer (get (:seon.call-preparation/by-supplied-count plan-value)
                      supplied)]
      (cond
        (nil? answer) arguments

        (:seon.call-preparation/ambiguous? answer)
        (error-value
         :seon.call-preparation/ambiguous-call
         (str "Cannot call " sym " with " supplied
              " arguments: more than one arity and omitted-slot set fits, so "
              "which positions were named is not determined.")
         {:seon.fn/sym sym
          :seon.call-preparation/supplied-count supplied
          :seon.call-preparation/candidates
          (:seon.call-preparation/candidates answer)})

        :else
        (let [refusal (volatile! nil)
              value-for (fn [slot]
                          (let [produced (supply current environment slot sym)]
                            (when (error-value? produced)
                              (vreset! refusal produced))
                            produced))
              with-inserts
              (reduce (fn [args slot]
                        (let [position (:seon.fn.argument/index slot)
                              produced (value-for slot)]
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
  polymorphic — sci's contract is `args` OR a `reduced` result."
  {:malli/schema
   [:=> [:cat :map :seon.schema/value :seon.schema/arguments]
    :seon.schema/value]}
  [ctx callee arguments]
  (let [call-state (get ctx carrier)
        environment (env/of ctx)
        sym (when (and call-state environment) (var-symbol callee))
        projection (:seon.schema/projection ctx)
        connection (when sym (:seon.db/connection environment))
        database (when (and projection connection) (db/db connection))]
    (if-not (and database (not (error-value? database)))
      arguments
      (let [current (current-snapshot call-state database projection)]
        (if (error-value? current)
          arguments
          (let [prepared (prepare current environment
                                  (plan call-state database current (str sym))
                                  (vec arguments))]
            (if (error-value? prepared)
              (reduced prepared)
              prepared)))))))
