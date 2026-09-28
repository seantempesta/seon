(ns seon.call-preparation
  "Call preparation: declared supplied defaults, and the invocation plan
  derived from the program graph.

  The callee's armed contract wrapper prepares its own calls: at arming,
  `arm-plan` decides from the contract and the supplied-default rows whether
  the function declares anything suppliable, and only then composes
  `preparing` outside the Malli wrapper. A SUPPLIED DEFAULT is a value the
  runtime hands to a call for an argument the callee's contract DECLARES and
  the caller OMITTED. The function's own `:malli/schema` is the complete
  request; there is no function-side injection metadata, no annotation, and
  no roster.

  Three facts make a value suppliable, and they live in the database as
  ordinary initialization rows: `:seon.call-preparation/key`,
  `/schema` (the registered schema whose declared name is the value
  contract), and `/supplier` (the
  function that produces it). Adding a third supplied default is adding one
  row plus one ordinary contracted function.

  Suppliers read the ENVIRONMENT, never a dynamic var (seon-env PRD ruling
  2). The environment is the CALLING evaluation's: the kernel arm names the
  context each evaluation runs (`seon.sci.kernel/world-ctx`) and travels with
  its work across threads, so a function made in one context and called from
  another evaluation (a test member, a disposable check) supplies that
  evaluation's world.

  Two modes of database temporality fall out of this with no mode flag
  (PRD ruling 6): a caller that omits `:seon.db/db` gets `(d/db
  connection)` derefed AT CALL TIME by the supplier — always current; a
  caller that passes a database value keeps it unchanged through the whole
  call. Elide for current, pass for consistent.

  Full arities and the all-default shape retain precedence; other shorter
  calls require a unique placement under the declared slot schemas before any
  missing value is supplied."
  (:require [clojure.core.cache.wrapped :as cache]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [datahike.db]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.instrument :as mi]
            [seon.batch]
            [seon.db :as db]
            [seon.error.refusal :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(set! *warn-on-reflection* true)

;;; ---------------------------------------------------------------------------
;;; The snapshot memo's holder type
;;; ---------------------------------------------------------------------------

(defn state?
  "True for the atom a projection holds its snapshot memo in."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? clojure.lang.IAtom value))

(schema/register-core-predicate! 'seon.call-preparation/state? state?)

;; The generator the packaged `:seon.call-preparation/state` declaration names.
(def state-generator
  (gen/fmap atom (gen/return {})))

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Errors as values
;;; ---------------------------------------------------------------------------

 ;; The error owner requires call preparation. Resolve its inspection Vars
;; once after loading, as seon.error does for its SCI dependency. The supplied
;; projection remains the authority; no error population is copied here.
(def ^:private error-output-validators (delay (requiring-resolve 'seon.error/declared-output-validators)))
(def ^:private error-declared-schema-keys (delay (requiring-resolve 'seon.error/declared-schema-keys)))

;;; ---------------------------------------------------------------------------
;;; The declared entity attributes — a query over the declaration, not a list
;;; ---------------------------------------------------------------------------

(def ^:private declared-entity-attributes
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

(defn entity-attributes
  "The attributes a supplied-default row is made of.

  Read from the one `:seon.call-preparation/row` declaration, so the
  listener's watch set and the basis derivation cannot drift from the
  schema. This is the fact that replaces an embedded attribute list."
  {:malli/schema [:=> [:cat] [:vector :qualified-keyword]]}
  []
  @declared-entity-attributes)

;;; ---------------------------------------------------------------------------
;;; The acquired snapshot
;;; ---------------------------------------------------------------------------

(def ^:private entity-query
  '[:find ?key ?schema-key ?supplier
    :in $
    :where
    [?entity :seon.call-preparation/key ?key]
    [?entity :seon.call-preparation/schema ?schema]
    [?schema :seon.schema/key ?schema-key]
    [?entity :seon.call-preparation/supplier ?function]
    [?function :seon.fn/sym ?supplier]])

;;; ---------------------------------------------------------------------------
;;; Contract arities — read from the projection's compiled Malli registry
;;; ---------------------------------------------------------------------------

(defn- argument-facts
  "One argument slot: its index, rest position, compiled schema and authored
  schema name; for a map (inline or named, through `m/deref-all`) its
  `[key optional? value-form]` entries."
  {:malli/schema [:=> [:cat :int :boolean [:fn malli.core/schema?]] :map]}
  [index rest? child]
  (let [form (m/form child)
        target (m/deref-all child)]
    (cond-> {:seon.fn.argument/index index :seon.fn.argument/rest? rest? ::argument-schema child}
      (qualified-keyword? form) (assoc :seon.call-preparation/schema-key form)
      (and (not rest?) (= :map (m/type target)))
      (assoc ::map-entries (mapv (fn [[entry-key properties value]]
                               [entry-key (true? (:optional properties)) (m/form value)])
                             (m/children target))))))

(defn- arity-facts
  "One compiled function schema's arities, ordered and indexed as
  `seon.program/contract-facts` orders them (`m/-function-schema-arities`,
  then the `:cat`/`:catn` input's children); a variadic arity's last slot is its rest."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:vector :map]]}
  [compiled]
  (mapv (fn [order arity]
          (let [{input :input output :output kind :arity minimum :min} (m/-function-info arity)
                slots (mapv #(if (m/schema? %) % (last %)) (m/children input))
                variadic? (= :varargs kind)]
            {:seon.fn.arity/order order
             :seon.fn.arity/min minimum
             :seon.fn.arity/argument-count (count slots)
             :seon.call-preparation/variadic? variadic?
             ::arity-output output
             ::arity-arguments (mapv #(argument-facts %1 (and variadic? (= %1 (dec (count slots)))) %2)
                               (range) slots)}))
        (range) (m/-function-schema-arities compiled)))

(defn- contract-arities
  "`{sym arities}` for every function contract `projection` compiled. Malli's
  registry retains the compiled schemas (`mr/schema`); this reads them once per
  projection, held by the projection itself (`schema/projection-cache-value`)."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:map-of :qualified-symbol [:vector :map]]]}
  [projection]
  (schema/projection-cache-value
   projection ::contract-arities
   #(let [registry (:seon.schema.projection/registry projection)]
      (into {} (keep (fn [sym] (some->> (mr/schema registry sym) arity-facts (vector sym))))
            (keys (:seon.schema.projection/function-contracts projection))))))

(defn- incoherent
  {:malli/schema [:=> [:cat :seon.call-preparation/supplied-default :string :map]
                  :seon.call-preparation/incoherent-supplier-error]}
  [{default-key :seon.call-preparation/key
    supplier :seon.call-preparation/supplier-symbol
    schema-key :seon.call-preparation/schema-key} reason data]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.call-preparation/call 'seon.call-preparation/incoherent
   {:seon.error/message (str "The supplied default " default-key " is not admissible: " reason)
  :seon.call-preparation/incoherent-key default-key
  :seon.call-preparation/supplier-symbol supplier
  :seon.call-preparation/schema-key schema-key
  :seon.call-preparation/coherence-expectation reason
  :seon.error/data (assoc data :seon.call-preparation/key default-key)
  :seon.error/offending data}))

(defn- coherent-supplier
  "Prove one row against the program's contracts, or refuse it as a value.

  Refusing at acquisition is the point: an incoherent row is never
  installed, so a wrong-shaped value can never be handed to a target
  function and disguised as that function's own contract violation.
  Schemas are matched by declared name, never by structure."
  {:malli/schema
   [:=> [:cat [:map-of :qualified-symbol [:vector :map]] :seon.call-preparation/supplied-default
         [:set :qualified-keyword]]
    [:or :nil :seon.call-preparation/incoherent-supplier-error]]}
  [contracts
   {schema-key :seon.call-preparation/schema-key
    supplier :seon.call-preparation/supplier-symbol :as candidate}
   error-keys]
  (let [{order :seon.fn.arity/order argument-count :seon.fn.arity/argument-count
         [argument] ::arity-arguments output ::arity-output}
        (first (filter #(seq (::arity-arguments %)) (get contracts supplier)))
        arms (when (and output (= :or (m/type output))) (set (map m/form (m/children output))))]
    (cond
      (nil? argument)
      (incoherent candidate
                  (str "no function named " supplier
                       " with a contracted one-argument arity is in this "
                       "cluster's program graph.")
                  {:seon.call-preparation/supplier-symbol supplier})

      (not (and (zero? (long order)) (= 1 (long argument-count))))
      (incoherent candidate
                  (str supplier " must take exactly one argument, this "
                       "cluster's environment; it declares "
                       argument-count ".")
                  {:seon.call-preparation/supplier-symbol supplier
                   :seon.fn.arity/argument-count argument-count})

      (not= :seon.env/environment (:seon.call-preparation/schema-key argument))
      (incoherent candidate
                  (str supplier "'s argument is not "
                       ":seon.env/environment. A supplier reads the "
                       "environment it is called with and nothing else.")
                  {:seon.call-preparation/supplier-symbol supplier})

      (nil? arms)
      (incoherent candidate
                  (str supplier " must declare a union return: its "
                       "value schema or declared error declared-schemas. It declares a "
                       (pr-str (m/type output)) ".")
                  {:seon.call-preparation/supplier-symbol supplier})

      (not (and (contains? arms schema-key)
                (seq (disj arms schema-key))
                (every? error-keys (disj arms schema-key))))
      (incoherent candidate
                  (str supplier "'s declared return does not agree with "
                       "the entity's value schema " schema-key ".")
                  {:seon.call-preparation/supplier-symbol supplier
                   :seon.call-preparation/schema-key schema-key})

      :else nil)))

(defn- newest-entity-transaction
  "The newest transaction that asserted OR retracted any row fact.

  Derived, never a stored counter. The history view is what makes a
  RETRACTION move this basis; a store kept without history answers from
  its current value, which still moves on every assertion.

  One AEVT range per row attribute, so the work is proportional to the row
  facts. The former variable-attribute `(max ?tx)` query over the history
  view scanned far beyond them: 220-274 ms on default's program
  (2026-09-23), for an answer these ranges give in ~3 ms."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-keyword]]
                  [:or :seon.db/basis-t :seon.db/error-result]]}
  [database attributes]
  ;; This basis keeps the process-local plan cache coherent. It is not a
  ;; semantic read performed by the prepared call: the surrounding snapshot
  ;; queries already retain the precise entity, schema, and supplier attributes
  ;; whose change can affect preparation, so it is kept out of the sink.
  (binding [db/*read-evidence-sink* nil]
    (let [history (db/history database)
          ;; Only the declared no-history refusal means "read the current
          ;; value"; any other refused history read is this basis's error.
          source (if (= :seon.config.db/keep-history?
                        (:seon.config/error-key history))
                   database
                   history)
          ranges (when-not (db/read-refusal? source)
                   (mapv #(db/datoms source :aevt %) attributes))]
      (or (when (db/read-refusal? source) source)
          (first (filter db/read-refusal? ranges))
          (transduce (comp cat (map :tx)) max 0 ranges)))))

(defn- by-schema-key
  {:malli/schema [:=> [:cat :seon.call-preparation/supplied-defaults]
                  [:map-of :qualified-keyword :seon.call-preparation/supplied-default]]}
  [supplied-defaults]
  (into {} (map (fn [[_ candidate]] [(:seon.call-preparation/schema-key candidate) candidate]))
        supplied-defaults))

(defn- derive-snapshot
  "The snapshot's program-derived members, from one database value.

  Every row is proved against the program graph here; incoherent rows
  become refusals and are NOT installed. `basis-t` is the newest
  transaction touching any row fact. `checked-through-t` names one database value,
  not the program, so `snapshot` adds it after the memo."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schema/projection]
    [:or :map :seon.db/error-result]]}
  [database projection]
  (let [entities (db/q database entity-query)]
    (if (db/read-refusal? entities)

      entities
      (let [contracts (contract-arities (db/carried-projection database))
            ;; debt: seon.db supplied-database-value and supplied-connection still declare :seon.error/value.
            error-keys (conj (set (@error-declared-schema-keys projection)) :seon.error/value)
            candidates
            (mapv (fn [[default-key schema-key supplier]]
                    {:seon.call-preparation/key default-key
                     :seon.call-preparation/schema-key schema-key
                     :seon.call-preparation/supplier-symbol (symbol supplier)})
                  entities)
            proved
            (mapv (fn [candidate]
                    [candidate (coherent-supplier contracts candidate error-keys)])
                  candidates)
            ;; Compiling the value validator is part of proving the entity: a
            ;; value schema the acquired projection cannot compile is an
            ;; incoherent entity, not an exception thrown at acquisition.
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
            basis (newest-entity-transaction database (entity-attributes))]
        (if (db/read-refusal? basis)
          basis
          {:seon.call-preparation/supplied-defaults admitted
           :seon.call-preparation/validators
           (into {}
                 (comp (remove second)
                       (map (fn [[candidate _ valid?]]
                              [(:seon.call-preparation/key candidate) valid?])))
                 compiled)
           :seon.call-preparation/refusals (into [] (keep second) compiled)
           :seon.call-preparation/basis-t basis})))))

;;; ---------------------------------------------------------------------------
;;; The snapshot memo — keyed by what the derivation reads
;;; ---------------------------------------------------------------------------

(def ^:private snapshot-queries
  ;; Every query `derive-snapshot` issues. The read set below is derived from
  ;; these forms by Datahike itself, so a query added here widens the key.
  [entity-query])

(def ^:private snapshot-read-attributes
  ;; `datahike.query/query-attribute-dependencies` (query.cljc:2923) is the
  ;; projection Datahike's own query cache compares, pure over the query form.
  ;; The entity attributes add the basis ranges, and `schema/projection-attributes`
  ;; the carried projection's, whose contracts the derivation reads. `:all`
  ;; from any query means no revision can prove the key, and the revision tier
  ;; is not used.
  (delay
    (reduce (fn [attributes query]
              (let [dependencies (d/query-attribute-dependencies query)]
                (if (or (= :all attributes) (= :all dependencies))
                  :all
                  (into attributes dependencies))))
            (into (set (entity-attributes)) schema/projection-attributes)
            snapshot-queries)))

(def snapshot-cache-policy
  "Bound the snapshots one projection retains independently of the number of commits."
  {::snapshot-cache-size 24
   ::snapshot-cache-reason
   "Retain eight recent program populations under each of their three keys (a committed value's completed snapshot by commit, its read-attribute revisions and its commit) for the branches and test forks sharing one projection; an older population derives again."})

(defn- snapshot-memo
  "The snapshot memo held by `projection` itself, or nil without a holder.

  The same core.cache wrapped LRU `seon.db`'s projection memo uses, retained
  by the projection's own runtime holder (`schema/projection-cache-value`):
  compiled validators belong to the projection they were compiled in, and
  equal programs share one projection object through `db/carried-projection`,
  so no second key for the projection is needed and none outlives it."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:or :nil [:fn seon.call-preparation/state?]]]}
  [projection]
  ;; Below `snapshot`, which arming reads per contract, so its callees run
  ;; unarmed for cost; their contracts hold at every other caller.
  (let [memo ((mi/-f->original schema/projection-cache-value)
              projection ::snapshot-memo
              #(cache/lru-cache-factory {} :threshold (::snapshot-cache-size snapshot-cache-policy)))]
    (when ((mi/-f->original state?) memo) memo)))

(defn- snapshot-revision-key
  "The part of Datahike's cache-context the derivation depends on.

  Datahike advances one attribute revision per changed attribute and the
  conservative revision on a schema or unknown change
  (`datahike.query/advance-query-cache-context`, query.cljc:2568); its own
  query cache compares exactly these members within one connection
  generation (`source-context-unchanged?`, query.cljc:2963). A commit
  touching none of the read attributes keys the same snapshot."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.schema/value]]}
  [database]
  (let [context (:cache-context database)]
    [::revisions
     (:datahike.cache/connection-id context)
     (:datahike.cache/generation context)
     (:datahike.cache/conservative-revision context)
     (select-keys (:datahike.cache/attribute-revisions context) @snapshot-read-attributes)]))

(defn- memoized-snapshot
  "The derived members for a committed value, through the two-tier memo.

  A revision miss (a new connection or branch) tries the commit, which
  names one value in every connection (`datahike/writing.cljc:363`), and
  only then derives. The delay is stored before it is forced, so concurrent
  misses share one derivation. A refusal is returned, never retained."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       [:fn seon.call-preparation/state?] :map]
                  [:or :map :seon.db/error-result]]}
  [database projection memo committed]
  (let [commit-key [::commit (:datahike.value/commit-id committed)]
        ;; A memoized derivation is no caller's read: only a miss would
        ;; record it, so it stays out of the caller's evidence.
        derivation (fn [_] (delay (binding [db/*read-evidence-sink* nil]
                                    (derive-snapshot database projection))))
        derived
        (if (= :all @snapshot-read-attributes)
          @(cache/lookup-or-miss memo commit-key derivation)
          @(cache/lookup-or-miss
            memo (snapshot-revision-key database)
            (fn [_] (delay @(cache/lookup-or-miss memo commit-key derivation)))))]
    (when (db/read-refusal? derived)
      (cache/evict memo commit-key)
      (cache/evict memo (snapshot-revision-key database)))
    derived))

(defn snapshot
  "The complete supplied-default snapshot at one database value.

  A function of the program rows it reads, memoized by their identity: a
  committed value keys by Datahike's revisions of the attributes the
  derivation's queries read (`snapshot-read-attributes`), then by its
  commit, in the memo its projection holds. An unrelated commit, or a new
  branch at an already-derived commit, reads the held members. A
  speculative, as-of or history value has no committed identity and
  derives (`report-snapshot` answers the writer's report value).
  `checked-through-t` is the basis of `database` itself.

  The completed snapshot is held first by the value's commit, which fixes
  its basis: a repeated answer is one identity read and one lookup
  (`[::snapshot commit]`). Every value is
  answered by its own snapshot, so an older value or another branch sharing
  one cluster context never reads a newer program's members."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schema/projection]
    [:or :seon.call-preparation/snapshot :seon.db/error-result]]}
  [database projection]
  (let [committed (datahike.db/committed-value-identity database)
        memo (when committed ((mi/-f->original snapshot-memo) projection))
        held-key (when memo [::snapshot (:datahike.value/commit-id committed)])]
    (or (when held-key (cache/lookup memo held-key))
        (let [derived (if memo
                        (memoized-snapshot database projection memo committed)
                        (derive-snapshot database projection))]
          (if (db/read-refusal? derived)
            derived
            (let [completed (assoc derived
                                   :seon.schema/projection projection
                                   :seon.call-preparation/checked-through-t (db/basis-t database))]
              (when held-key (cache/miss memo held-key completed))
              completed))))))

(defn report-snapshot
  "The snapshot at a transaction report's `:db-after`.

  It is read from the report's `:db-before` when the report changed no
  attribute the derivation reads. A writer-side gate holds a speculative `:db-after` with no committed
  identity, so `snapshot` would derive the whole program there (174-188 ms
  on default, 2026-09-23). The report's final datoms name every attribute
  it changed; when none is in `snapshot-read-attributes`, the program
  members equal the committed `:db-before`'s, whose memo answers. Only
  `checked-through-t` is `:db-after`'s own."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report :seon.schema/projection]
    [:or :seon.call-preparation/snapshot :seon.db/error-result]]}
  [{before :db-before after :db-after :as report} projection]
  (let [attributes @snapshot-read-attributes]
    ;; Logical datoms: a vector element moved or replaced in its cell reads
    ;; as its own attribute, so a reorder-only write derives afresh.
    (if (or (= :all attributes)
            (some (comp attributes :a) (db/logical-datoms report)))
      (snapshot after projection)
      (let [held (snapshot before projection)]
        (if (db/read-refusal? held)
          held
          (assoc held :seon.call-preparation/checked-through-t (db/basis-t after)))))))

;;; ---------------------------------------------------------------------------
;;; Plan derivation — the contract's arities joined to the supplied defaults
;;; ---------------------------------------------------------------------------

(defn- candidate-indexes
  [candidates]
  (mapv (fn [candidate]
          (mapv :seon.fn.argument/index
                (:seon.call-preparation/inserts candidate)))
        candidates))

(defn- resolve-count
  "One supplied-argument count's preparation, or its refusal.

  A declared arity always wins: a call whose count matches one declared
  arity invokes it unchanged, and only its argument-map entries are filled;
  an argument outside that arity's contract is refused by the contract, never
  reinterpreted as a shorter shape. Each fixed arity derives AT MOST ONE
  shorter shape — the arity minus ALL its suppliable slots (ruling 2's
  all-or-nothing model). When two derived shapes land on one count, the plan
  refuses rather than guessing by arity order."
  [exact derived]
  (cond
    (> (count exact) 1)
    {:seon.call-preparation/ambiguous? true
     :seon.call-preparation/candidates (candidate-indexes exact)}

    (= 1 (count exact)) (first exact)

    (= 1 (count derived)) (first derived)

    (seq derived)
    {:seon.call-preparation/ambiguous? true
     :seon.call-preparation/candidates (candidate-indexes derived)}))

(def ^:private world-candidates
  "A supplied default for each member of an evaluation's world
  (`seon.batch/world-members`), keyed by the schema name a function declares
  it under. Its value comes from the calling evaluation's world
  (`seon.batch/*world*`) and nowhere else."
  (into {} (for [[schema-key member] seon.batch/world-members]
             [schema-key {:seon.call-preparation/key member
                          :seon.call-preparation/schema-key schema-key
                          :seon.call-preparation/supplier-symbol `seon.batch/world-members}])))

(defn- world-member?
  "Whether `schema-key` names a member of an evaluation's world."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An argument slot's declared schema is any Malli form.", :gen/elements [nil :k [] {}]}]] :boolean]}
  [schema-key]
  (contains? seon.batch/world-members schema-key))

(defn- slot-of
  [candidate position]
  {:seon.fn.argument/index (long position)
   :seon.call-preparation/key (:seon.call-preparation/key candidate)
   :seon.call-preparation/supplier-symbol
   (:seon.call-preparation/supplier-symbol candidate)})

(defn- arity-plan
  "One compiled arity joined to the supplied defaults by declared schema name:
  a slot by its schema name, a map entry by its key AND value schema name, and
  a map whose required entries are all supplied as a slot of its own."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :seon.call-preparation/supplied-default] :map]
                  :seon.call-preparation/arity-plan]}
  [index {arguments ::arity-arguments variadic? :seon.call-preparation/variadic? :as arity}]
  (let [entries (vec (for [{position :seon.fn.argument/index map-entries ::map-entries} arguments
                           [entry-key optional? value] map-entries
                           :let [candidate (get index value)]
                           ;; A required entry is supplied, and so is an optional one
                           ;; whose schema is a world member (owner ruling): a function
                           ;; whose meaning needs that member absent declares another
                           ;; name. Any other optional entry's absence is the caller's.
                           :when (and (or (not optional?) (world-member? value))
                                      (= entry-key (:seon.call-preparation/key candidate)))]
                       (cond-> (assoc (slot-of candidate position) :seon.call-preparation/entry-key entry-key)
                         optional? (assoc :seon.call-preparation/optional? true))))
        slots (vec (for [{position :seon.fn.argument/index rest? :seon.fn.argument/rest?
                          schema-key :seon.call-preparation/schema-key map-entries ::map-entries} arguments
                         :let [candidate (get index schema-key)
                               required (into #{} (keep (fn [[entry-key optional? _]] (when-not optional? entry-key)))
                                              map-entries)
                               own (filterv #(= position (:seon.fn.argument/index %)) entries)]
                         slot [(when (and candidate (not rest?)) (slot-of candidate position))
                               (when (and (nil? candidate) (seq required)
                                          (= required (set (keep #(when-not (:seon.call-preparation/optional? %)
                                                                    (:seon.call-preparation/entry-key %))
                                                                 own))))
                                 {:seon.fn.argument/index position :seon.call-preparation/entries own})]
                         :when slot]
                     slot))]
    (cond-> {:seon.fn.arity/order (:seon.fn.arity/order arity)
             :seon.fn.arity/min (long (:seon.fn.arity/min arity))
             :seon.fn.arity/argument-count (:seon.fn.arity/argument-count arity)
             :seon.call-preparation/variadic? variadic?
             :seon.call-preparation/rest-slot?
             (boolean (some #(and (:seon.fn.argument/rest? %) (get index (:seon.call-preparation/schema-key %)))
                            arguments))
             :seon.call-preparation/slots slots
             :seon.call-preparation/entries entries}
      (and (not variadic?) (> (count slots) 1))
      (assoc :seon.call-preparation/argument-validators (mapv (comp m/validator ::argument-schema) arguments)))))

(defn- planned-arities
  "`sym`'s contract arities in `projection` planned against `current`, or nil.
  A world member's schema name is planned from the world, whatever row also
  names it."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.call-preparation/snapshot :qualified-symbol]
                  [:maybe :seon.call-preparation/arity-plans]]}
  [projection current sym]
  (let [index (merge (by-schema-key (:seon.call-preparation/supplied-defaults current)) world-candidates)]
    (some->> (get (contract-arities projection) sym)
             (mapv #(arity-plan index %)))))

(defn supplied-map-entries
  "Return [arity-order argument-index key] for declared supplied map entries.

  The entries `plan-for` fills: a map argument's entry, inline or named,
  whose key and value schema name are one admitted supplied-default row's.
  Positional defaults are not map entries."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.fn/sym]
    [:or [:vector [:tuple :int :int :qualified-keyword]] :seon.db/error-result]]}
  [database sym]
  (let [entities (db/q database entity-query)
        current (when-not (db/read-refusal? entities) (snapshot database (db/carried-projection database)))]
    (or (first (filter db/read-refusal? [entities current]))
        (->> (for [{order :seon.fn.arity/order entries :seon.call-preparation/entries} (planned-arities (db/carried-projection database) current sym)
                   {index :seon.fn.argument/index entry-key :seon.call-preparation/entry-key} entries]
               [order index entry-key])
             sort
             vec))))

(defn- compiled-plan
  "One identity's invocation plan from its planned contract arities."
  [sym arity-plans]
  (let [fixed (remove :seon.call-preparation/variadic? arity-plans)
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
                   (:seon.call-preparation/entries variadic)}))))

(defn plan-for
  "Compile one function identity's invocation plan from its contract.

  The database value's carried projection holds the compiled contract
  (`contract-arities`); its slots and map entries, inline or named, are
  joined to the acquired supplied-default rows by declared schema name.
  Returns nil when the identity has no contract in this cluster's program
  graph, and the database owner's read error when its rows could not be
  read: an unreadable program graph is never \"not contracted\"."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.call-preparation/snapshot
         :seon.fn/sym]
    [:or :seon.call-preparation/plan :nil :seon.db/error-result]]}
  [database current sym]
  ;; The plan is a function of the supplied-default entities and this contract.
  ;; The snapshot memo and the projection's index record no read, so these
  ;; two reads are what enter the caller's evidence.
  (let [reads [(db/q database entity-query)
               (db/q database '[:find ?spec . :in $ ?sym
                                :where [?function :seon.fn/sym ?sym] [?function :seon.fn/spec ?spec]]
                     sym)]
        arity-plans (when-not (some db/read-refusal? reads)
                      (planned-arities (db/carried-projection database) current sym))]
    (if (or (some db/read-refusal? reads) (nil? arity-plans))
      (first (filter db/read-refusal? reads))
      (compiled-plan sym arity-plans))))

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

(defn arm-plan
  "The invocation plan an armed wrapper of `sym` prepares its calls with, or nil.

  Decided once when the contract is armed, from the contract `projection`
  compiled and the supplied-default rows `current` admitted: a contract that
  declares no suppliable slot or entry has no plan, so its wrapper gets no
  preparation step and its calls pay nothing. A row or contract change takes
  effect at the next arming, the ordinary path for program rows."
  {:malli/schema [:=> [:cat :seon.call-preparation/snapshot :seon.schema/projection :seon.fn/sym]
                  [:or :nil :seon.call-preparation/plan]]}
  [current projection sym]
  ;; One contract's arities, not the projection's whole contract index: an
  ;; agent's definition arms against a projection made for it alone.
  (let [index (by-schema-key (:seon.call-preparation/supplied-defaults current))
        compiled (some->> (mr/schema (:seon.schema.projection/registry projection) sym)
                          arity-facts
                          (mapv #(arity-plan index %))
                          (compiled-plan sym))]
    (when-not (:seon.call-preparation/empty? compiled true)
      compiled)))

;;; ---------------------------------------------------------------------------
;;; Supplying one value
;;; ---------------------------------------------------------------------------

(defn- unavailable
  {:malli/schema [:=> [:cat :seon.fn/sym :seon.call-preparation/slot :seon.schema/value]
                  :seon.call-preparation/unavailable-error]}
  [sym slot cause]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.call-preparation/call 'seon.call-preparation/unavailable
   {:seon.error/message (str "Cannot call " sym ": " (:seon.call-preparation/key slot)
        " is unavailable. "
        (or (:seon.error/message cause)
            "Its supplier produced no value."))
  :seon.call-preparation/target-symbol sym
  :seon.call-preparation/unavailable-key (:seon.call-preparation/key slot)
  :seon.call-preparation/supplier-symbol (:seon.call-preparation/supplier-symbol slot)
  :seon.fn.argument/index (:seon.fn.argument/index slot)
  :seon.error/offending cause
  :seon.error/data (cond-> {:seon.fn/sym sym
            :seon.call-preparation/key (:seon.call-preparation/key slot)
            :seon.call-preparation/supplier-symbol
            (:seon.call-preparation/supplier-symbol slot)
            :seon.fn.argument/index (:seon.fn.argument/index slot)}
     (:seon.call-preparation/entry-key slot)
     (assoc :seon.call-preparation/entry-key
            (:seon.call-preparation/entry-key slot))
     (map? cause)
     (assoc :seon.call-preparation/cause cause))
  :seon.error/expected "an available supplied default"}))



(declare supply-from-world supply-from-row)

(defn supply
  "One supplied value: a world member from the calling evaluation's world,
  any other row's by calling its supplier with this call's environment.

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
  (let [row (get (:seon.call-preparation/supplied-defaults current) (:seon.call-preparation/key slot))]
    (cond
      ;; An optional entry is filled from a world only: its absence means
      ;; something to its callee (an evaluate request without :seon.db/db
      ;; reads its context's custody), and a row answering from the thread's
      ;; custody filled it with a sibling branch's database
      ;; (seon.sci.eval-test custody reds, r9-r12). No world: it stays absent.
      (:seon.call-preparation/optional? slot) (supply-from-world slot sym)
      (not= `seon.batch/world-members (:seon.call-preparation/supplier-symbol slot))
      (supply-from-row current environment slot sym)
      ;; an evaluation of the one path: its world is the only source
      seon.batch/*world* (supply-from-world slot sym)
      ;; an entrant not yet converted to the one path binds no world: its
      ;; member's row supplier answers, as before (deleted with the last
      ;; converted entrant, S4-S12)
      row (supply-from-row current environment
                           (assoc slot :seon.call-preparation/supplier-symbol (:seon.call-preparation/supplier-symbol row))
                           sym)
      :else (supply-from-world slot sym))))

(defn- supply-from-world
  "The calling evaluation's world member for `slot`, or the refusal naming
  the schema key the world lacks: no global, no default, never nil."
  {:malli/schema [:=> [:cat :seon.call-preparation/slot :seon.fn/sym]
                  [:or :seon.schema/value :seon.call-preparation/unavailable-error]]}
  [slot sym]
  (let [member (:seon.call-preparation/key slot)
        world seon.batch/*world*
        v (get world member ::absent)]
    (if (or (nil? v) (= ::absent v))
      (unavailable sym slot {:seon.error/message
                             (str "This evaluation's world holds no "
                                  (some (fn [[k m]] (when (= m member) k)) seon.batch/world-members)
                                  (when-not world " (no evaluation is running on this thread)")
                                  "; pass it explicitly.")})
      v)))

(defn- supply-from-row
  "Call one supplied-default row's supplier with this call's environment."
  {:malli/schema
   [:=> [:cat :seon.call-preparation/snapshot :seon.env/environment
         :seon.call-preparation/slot :seon.fn/sym]
    [:or :seon.schema/value :seon.call-preparation/unavailable-error :seon.call-preparation/invalid-supplied-value-error]]}
  [current environment slot sym]
  (let [default-key (:seon.call-preparation/key slot)
        symbol-name (:seon.call-preparation/supplier-symbol slot)
        ;; A namespace that fails to load is the declared unresolved case;
        ;; its Throwable rides the refusal whole (class, message, ex-data,
        ;; cause chain) as the supplier-threw case already carries its own.
        resolved (try (requiring-resolve symbol-name)
                      (catch Throwable cause cause))]
    (if-not (var? resolved)
      (unavailable sym slot
                   (cond-> {:seon.error/at (java.util.Date.)
                            :seon.error/layer :seon.call-preparation/call
                            :seon.error/operation 'seon.call-preparation/supply
                            :seon.error/message
                            (str "No callable is installed for " symbol-name "."
                                 (when (instance? Throwable resolved)
                                   (str " Resolving it threw " (.getName (class resolved))
                                        ": " (ex-message resolved))))
                            :seon.call-preparation/unresolved-symbol symbol-name
                            :seon.error/data {:seon.call-preparation/supplier-symbol
                                              symbol-name}
                            :seon.error/expected "an installed callable Var"}
                     (instance? Throwable resolved)
                     (assoc :seon.error/exception-class (symbol (.getName (class resolved)))
                            :seon.error/offending resolved)))
      (let [produced (try (resolved environment)
                          (catch Throwable cause
                            (seon.error.refusal/diagnostic (java.util.Date.) :seon.call-preparation/call 'seon.call-preparation/supply
                             {:seon.error/message (or (ex-message cause) "The supplier threw.")
  :seon.call-preparation/thrown-supplier symbol-name
  :seon.error/exception-class (symbol (.getName (class cause)))
  :seon.error/offending cause
  :seon.error/data {:seon.call-preparation/supplier-symbol
                              symbol-name}
  :seon.error/expected "a returned supplied value"})))
            valid? (get (:seon.call-preparation/validators current)
                        default-key)]
        (cond
          (or (:seon.call-preparation/thrown-supplier produced)
              (some (fn [[_ valid?]] (valid? produced))
                    (@error-output-validators (:seon.schema/projection current)
                                              (:malli/schema (meta resolved)) 1))) (unavailable sym slot produced)

          (or (nil? valid?) (valid? produced)) produced

          :else
          (seon.error.refusal/diagnostic (java.util.Date.) :seon.call-preparation/call 'seon.call-preparation/supply
           {:seon.error/message (str symbol-name " produced a value that is not "
                (:seon.call-preparation/schema-key
                 (get (:seon.call-preparation/supplied-defaults current)
                      default-key))
                ". This is a fault at the supplier, not at " sym ".")
  :seon.call-preparation/target-symbol sym
  :seon.call-preparation/invalid-key default-key
  :seon.call-preparation/supplier-symbol symbol-name
  :seon.call-preparation/schema-key (:seon.call-preparation/schema-key (get (:seon.call-preparation/supplied-defaults current) default-key))
  :seon.error/offending produced
  :seon.error/data {:seon.fn/sym sym
            :seon.call-preparation/key default-key
            :seon.call-preparation/supplier-symbol symbol-name}}))))))

;;; ---------------------------------------------------------------------------
;;; The consumer seam
;;; ---------------------------------------------------------------------------

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

  `environment` is the calling evaluation's world.

  An unavailable-error names a supplier that could not produce a value;
  an invalid-supplied-value-error names a value outside its declared schema;
  an ambiguous-call-error names multiple placements at one supplied count.
  Each refusal prevents entry into the target. Explicit caller arguments
  remain unchanged and the target's ordinary contract validates them.

  Caller presence is tested by argument occupancy and `contains?`, never
  truthiness: a supplied nil reaches ordinary Malli input validation."
  {:seon/tasks #{"775e3222a980"}
   :malli/schema
   [:=> [:cat :seon.call-preparation/snapshot :seon.env/environment
         [:maybe :seon.call-preparation/plan] :seon.schema/arguments]
    [:or :seon.schema/arguments :seon.call-preparation/ambiguous-call-error :seon.call-preparation/unavailable-error :seon.call-preparation/invalid-supplied-value-error]]}
  [current environment plan-value arguments]
  (if (or (nil? plan-value) (:seon.call-preparation/empty? plan-value))
    arguments
    (let [sym (:seon.fn/sym plan-value)
          supplied (count arguments)
          answer (or (get (:seon.call-preparation/by-supplied-count plan-value)
                          supplied)
                     (partial-preparation plan-value arguments))]
      (cond
        (nil? answer) arguments

        (:seon.call-preparation/ambiguous? answer)
        (seon.error.refusal/diagnostic (java.util.Date.) :seon.call-preparation/call 'seon.call-preparation/prepare
         {:seon.error/message (str "Cannot call " sym " with " supplied
              " arguments: more than one derived call shape fits that count, "
              "so which positions were named is not determined. Pass the "
              "declared arguments in full.")
  :seon.call-preparation/target-symbol sym
  :seon.call-preparation/supplied-count supplied
  :seon.call-preparation/candidate-count (count (:seon.call-preparation/candidates answer))
  :seon.error/offending {:seon.call-preparation/candidates (:seon.call-preparation/candidates answer)
                        :seon.schema/arguments arguments}
  :seon.error/expected "one uniquely determined argument placement"
  :seon.call-preparation/candidates (:seon.call-preparation/candidates answer)
  :seon.call-preparation/offending-arguments arguments})

        :else
        ;; Each reduction stops at the first refusal (`reduced`), so no later
        ;; supplier runs; a stopped reduction returns the refusal map, never
        ;; the argument vector.
        (let [value-for (fn [slot]
                          (let [produced (supply current environment slot sym)]
                            (if (and (map? produced)
                                     (or (:seon.call-preparation/unavailable-key produced)
                                         (:seon.call-preparation/invalid-key produced)))
                              (reduced produced)
                              produced)))
              with-inserts
              (reduce (fn [args slot]
                        (let [position (:seon.fn.argument/index slot)
                              produced (if (:seon.call-preparation/entries slot) {} (value-for slot))]
                          (if (reduced? produced)
                            produced
                            (into (conj (subvec args 0 position) produced) (subvec args position)))))
                      (vec arguments)
                      (:seon.call-preparation/inserts answer))]
          (if (map? with-inserts)
            with-inserts
            (reduce (fn [args entry]
                      (let [position (:seon.fn.argument/index entry)
                            entry-key (:seon.call-preparation/entry-key entry)
                            target (nth args position nil)]
                        (if (or (not (map? target)) (contains? target entry-key))
                          args
                          (let [produced (value-for entry)]
                            (cond
                              ;; an optional member the world lacks stays absent
                              (and (reduced? produced) (:seon.call-preparation/optional? entry)) args
                              (reduced? produced) produced
                              :else (assoc args position (assoc target entry-key produced)))))))
                    with-inserts
                    (:seon.call-preparation/entries answer))))))))

(defn preparing
  "Compose the supplied-default step outside one armed contract wrapper.

  `world` answers the calling evaluation's environment at call time
  (`seon.instrument` reads it from the kernel arm), never the environment of
  the context the function was created in. Prepared arguments enter
  `wrapped`, whose contract validates them; a refusal is the call's value and
  the callee is never entered.

  A call no evaluation makes (host work on a thread serving no arm: Datahike's
  writer calling a transaction function, a listener, boot) has no world to
  supply from, so it is not prepared: its arguments reach `wrapped` as given
  and the callee's own contract answers for them. Preparation never authors a
  value for a caller that is not an evaluation."
  {:malli/schema [:=> [:cat :seon.call-preparation/snapshot :seon.call-preparation/plan
                       [:fn clojure.core/ifn?] [:fn clojure.core/ifn?]]
                  [:fn clojure.core/ifn?]]}
  [current plan-value world wrapped]
  ;; The step runs on every call of a prepared function, so it calls
  ;; `prepare` unarmed; the wrapped contract still validates what it returns.
  ;;
  ;; The world is read only when this call can receive a supplied default: a
  ;; call matching a declared arity with no map-entry default is never
  ;; prefixed, so it needs no environment. `world` derives the custody's
  ;; database on every call it answers (`calling-environment`, ~1.9 us): at
  ;; default one seon.db/q made 5 such calls and a pull 4, each a
  ;; one-argument `carried-projection` or `schema-database` call that
  ;; supplies nothing; a q under a host world took 64.6 us against 42.6 us
  ;; with none (2026-09-27). A host test body counted 62 resolutions for one
  ;; q, pull and pull-many (tests-0627r).
  (let [prepare-call (mi/-f->original prepare)
        by-count (:seon.call-preparation/by-supplied-count plan-value)]
    (fn [& arguments]
      (let [answer (get by-count (count arguments))]
        (if-let [environment (when-not (and answer
                                             (not (:seon.call-preparation/ambiguous? answer))
                                             (empty? (:seon.call-preparation/inserts answer))
                                             (empty? (:seon.call-preparation/entries answer)))
                               (world))]
          (let [prepared (prepare-call current environment plan-value (vec arguments))]
            (if (vector? prepared)
              (apply wrapped prepared)
              prepared))
          (apply wrapped arguments))))))
