(ns seon.state
  "Holistic system-state reconcile — make the DB's MANAGED datoms match a
   desired set of entity-maps. ONE primitive ([[reconcile!]]) over the whole
   declarative-state surface (context blocks, routes, core entities): seed,
   config override, reset, and restore are all expressions of it — we write
   the reconcile once, never a loader per state area.

   The managed slice is defined by PROVENANCE, not a taxonomy: a row is
   managed iff its first-assertion tx refs a stable database process in the
   managed scope (for example boot or config); agent-authored REPL rows sit
   outside it and are NEVER touched. There are no
   entity 'kinds' — datahike has none. An entity is its attribute set,
   identity is a per-attribute `:db.unique/identity` property, and the
   managed/authored split is pure provenance. So reconcile takes NO kind
   argument and runs NO id-attr-registry loop: it operates in attribute
   space — presence, provenance, connection."
  (:require
    [clojure.set :as set]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.schema :as schema]))

;; A desired entity-map MUST carry exactly one `:db.unique/identity` attr (the
;; upsert handle); `:map` is the honest schema (desired sets are heterogeneous
;; — routes, core entities, blocks), with the one-identity-attr invariant
;; enforced at runtime by [[reconcile!]] (errors-as-values), since Malli can't
;; express "has SOME identity attr" generically.
(schema/register! ::desired-entity :map)
(schema/register! ::desired [:vector ::desired-entity])
(schema/register! ::ok?       :boolean)
(schema/register! ::error     :string)
(schema/register! ::changed?  :boolean)
(schema/register! ::operations [:int {:min 0}])
(schema/register! ::attempts   [:int {:min 1}])
(schema/register! ::coordinate :seon.db.coordinate/coordinate)
(schema/register! ::tx-data    [:vector :any])

(schema/register!
  ::reconcile-request
  [:map {:closed true}
   [::desired ::desired]
   [:seon.db/managed-scope :seon.db/managed-scope]
   [:seon.db/managed-identity-attrs :seon.db/managed-identity-attrs]])

(schema/register!
  ::reconcile-response
  [:or
   [:map
    [::ok? [:= true]]
    [::changed? ::changed?]
    [::operations ::operations]
    [::attempts ::attempts]
    [::coordinate ::coordinate]]
   [:map
    [::ok? [:= false]]
    [::error ::error]
    [::attempts {:optional true} ::attempts]]])

(defn- desired-identity
  "The single registered `:db.unique/identity` `[attr value]` a desired
   entity-map carries — datahike's find-or-create handle for the upsert. nil
   when the map carries ZERO or MORE-THAN-ONE identity attr: neither upserts
   safely (none allocates a fresh eid every run; two risk a Conflicting-upserts
   throw), so [[reconcile!]] rejects such a set rather than duplicate rows."
  [m]
  (let [ids (filterv schema/identity-attr? (keys m))]
    (when (= 1 (count ids))
      (let [a (first ids)] [a (get m a)]))))

(def ^:private max-reconcile-attempts 3)

(defn- schema-entry
  [installed attr]
  (get installed attr))

(defn- cardinality-many?
  [installed attr]
  (= :db.cardinality/many (:db/cardinality (schema-entry installed attr))))

(defn- component-ref?
  [installed attr]
  (true? (:db/isComponent (schema-entry installed attr))))

(defn- ref-attr?
  [installed attr]
  (= :db.type/ref (:db/valueType (schema-entry installed attr))))

(defn- value-eid
  "Resolve a current or desired ref value to its eid when it already exists."
  [identity-eids value]
  (cond
    (int? value) value
    (vector? value) (get identity-eids value)
    (map? value) (:db/id value)
    :else nil))

(declare normalize-current-entity normalize-desired-entity)

(defn- normalize-current-one
  [entities identity-eids installed attr value]
  (cond
    (component-ref? installed attr)
    (normalize-current-entity entities identity-eids installed value)

    (ref-attr? installed attr)
    (value-eid identity-eids value)

    :else
    (db/decode-edn-value attr value)))

(defn- normalize-desired-one
  [entities identity-eids installed attr value]
  (cond
    (component-ref? installed attr)
    (normalize-desired-entity entities identity-eids installed value)

    (ref-attr? installed attr)
    (or (value-eid identity-eids value)
        (when (map? value)
          (when-let [identity (desired-identity value)]
            (get identity-eids identity)))
        value)

    :else value))

(defn- normalize-many
  [normalize-one value]
  (into #{} (map normalize-one) (or value [])))

(defn- normalize-current-value
  [entities identity-eids installed attr value]
  (if (cardinality-many? installed attr)
    (normalize-many #(normalize-current-one entities identity-eids installed attr %) value)
    (normalize-current-one entities identity-eids installed attr value)))

(defn- normalize-desired-value
  [entities identity-eids installed attr value]
  (if (cardinality-many? installed attr)
    (normalize-many #(normalize-desired-one entities identity-eids installed attr %) value)
    (normalize-desired-one entities identity-eids installed attr value)))

(defn- current-entity-map
  [entities identity-eids value]
  (if (map? value)
    value
    (if-let [eid (value-eid identity-eids value)]
      (get entities eid)
      value)))

(defn- normalize-current-entity
  [entities identity-eids installed value]
  (let [entity (current-entity-map entities identity-eids value)]
    (into {}
          (keep (fn [[attr attr-value]]
                  (when (not= :db/id attr)
                    [attr (normalize-current-value
                            entities identity-eids installed attr attr-value)])))
          entity)))

(defn- normalize-desired-entity
  [entities identity-eids installed entity]
  (into {}
        (keep (fn [[attr attr-value]]
                (when (not= :db/id attr)
                  [attr (normalize-desired-value
                          entities identity-eids installed attr attr-value)])))
        entity))

(defn- attr-equivalent?
  [entities identity-eids installed attr current desired]
  (= (normalize-current-value entities identity-eids installed attr current)
     (normalize-desired-value entities identity-eids installed attr desired)))

(defn- canonical-desired-entity
  "Express one desired map in database terms.

   Datahike has no empty cardinality-many datom: an empty collection means the
   attribute is absent. Remove only those impossible presences up front so the
   remaining map-presence comparisons continue to carry their full signal."
  [installed entity]
  (into {}
        (remove (fn [[attr value]]
                  (and (cardinality-many? installed attr)
                       (empty? value))))
        entity))

(defn- desired-validation-error
  [desired identity-attrs]
  (let [identities (mapv desired-identity desired)
        outside    (into #{}
                         (comp (keep first) (remove identity-attrs))
                         identities)
        duplicates (->> identities frequencies
                        (keep (fn [[identity n]]
                                (when (and identity (> n 1)) identity)))
                        (sort-by pr-str)
                        vec)]
    (cond
      (some nil? identities)
      (str "reconcile!: every desired entity-map must carry exactly ONE "
           ":seon.db/identity attribute (the upsert handle). Offending "
           "maps' keys: "
           (pr-str (mapv #(vec (keys %))
                         (remove desired-identity desired))))

      (seq outside)
      (str "reconcile!: desired identities fall outside "
           ":seon.db/managed-identity-attrs: "
           (pr-str (sort outside)))

      (seq duplicates)
      (str "reconcile!: duplicate desired identities: "
           (pr-str duplicates))

      :else nil)))

(defn- entity-exact-tx
  [entities identity-eids installed identity desired current]
  (if-not current
    [desired]
    (let [[identity-attr identity-value] identity
          attrs (-> (set/union (set (keys current)) (set (keys desired)))
                    (disj :db/id identity-attr))
          changed
          (->> attrs
               (filter
                 (fn [attr]
                   (let [current? (contains? current attr)
                         desired? (contains? desired attr)]
                     (or (not= current? desired?)
                         (and current? desired?
                              (not (attr-equivalent?
                                     entities identity-eids installed attr
                                     (get current attr)
                                     (get desired attr))))))))
               (sort-by str)
               vec)
          retracts
          (into []
                (keep (fn [attr]
                        (when (contains? current attr)
                          [:db.fn/retractAttribute identity attr])))
                changed)
          additions
          (reduce (fn [m attr]
                    (if (contains? desired attr)
                      (assoc m attr (get desired attr))
                      m))
                  {identity-attr identity-value}
                  changed)]
      (cond-> retracts
        (> (count additions) 1) (conj additions)))))

(defn- compile-reconcile-tx
  [{::keys [installed-schema rows]} desired scope identity-attrs]
  (let [installed       installed-schema
        entities        (into {}
                              (map (fn [{::keys [entity]}]
                                     [(:db/id entity) entity]))
                              rows)
        identity-eids   (into {}
                              (mapcat (fn [{::keys [entity]}]
                                        (keep (fn [[attr value]]
                                                (when (schema/identity-attr? attr)
                                                  [[attr value]
                                                   (:db/id entity)]))
                                              entity)))
                              rows)
        desired         (mapv #(canonical-desired-entity installed %) desired)
        identities      (mapv desired-identity desired)
        desired-set     (set identities)
        managed         (into {}
                              (keep (fn [{::keys [entity first-process]}]
                                      (let [identities
                                            (into #{}
                                                  (keep (fn [attr]
                                                          (when (contains? entity attr)
                                                            [attr (get entity attr)])))
                                                  identity-attrs)]
                                        (when (and (contains? scope first-process)
                                                   (seq identities))
                                          [(:db/id entity) identities]))))
                              rows)
        managed-by-id   (reduce-kv
                          (fn [m eid entity-identities]
                            (reduce #(assoc %1 %2 eid) m entity-identities))
                          {}
                          managed)
        collision       (some
                          (fn [identity]
                            ;; A not-yet-installed identity attr cannot resolve
                            ;; a lookup-ref. Treat it as a new entity here; the
                            ;; public transact path installs its schema, the
                            ;; full-head fence rejects that now-stale first
                            ;; attempt, and the bounded retry recompiles against
                            ;; the installed shape.
                            (when (contains? installed (first identity))
                              (when-let [existing
                                         (get identity-eids identity)]
                                (when (not= existing
                                            (get managed-by-id identity))
                                  identity))))
                          identities)]
    (if collision
      {::ok? false
       ::error (str "reconcile!: desired identity already belongs to an "
                    "entity outside the managed provenance scope: "
                    (pr-str collision))}
      (let [entity-tx
            (into []
                  (mapcat
                    (fn [[identity entity]]
                      (entity-exact-tx
                        entities identity-eids installed identity entity
                        (when-let [eid (get managed-by-id identity)]
                          (get entities eid)))))
                  (sort-by (comp pr-str first)
                           (map vector identities desired)))
            stale-eids
            (->> managed
                 (keep (fn [[eid entity-identities]]
                         (when (empty?
                                 (set/intersection entity-identities
                                                   desired-set))
                           eid)))
                 sort)
            tx-data
            (into entity-tx
                  (map (fn [eid] [:db.fn/retractEntity eid]))
                  stale-eids)]
        {::ok? true ::tx-data tx-data}))))

(def ^:private reconcile-state-query
  '[:find ?e (pull ?e [*])
    :in $ [?identity-attr ...]
    :where
    [?e ?identity-attr _]])

(def ^:private reconcile-lookup-ref-query
  '[:find ?e (pull ?e [*])
    :in $ [[?identity-attr ?identity-value] ...]
    :where
    [?e ?identity-attr ?identity-value]])

(def ^:private reconcile-provenance-query
  '[:find ?e ?tx
    :in $ [?identity-attr ...]
    :where
    [?e ?identity-attr _]
    [?e _ _ ?tx]])

(def ^:private reconcile-transaction-process-query
  '[:find ?tx ?process-id
    :in $ [?identity-attr ...]
    :where
    [?e ?identity-attr _]
    [?e _ _ ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id ?process-id]])

(defn- member-value!
  [operation member result-key]
  (if (true? (::protocol/success? member))
    (get member result-key)
    (throw (ex-info (str "reconcile!: " operation " failed")
                    {:seon.db/error member
                     :seon.error/kind :core-bug}))))

(defn- acquisition-rows
  [entity-rows provenance-rows transaction-process-rows]
  (let [process-by-tx (into {} transaction-process-rows)
        first-tx-by-eid
        (reduce (fn [by-eid [eid tx]]
                  (update by-eid eid #(if (or (nil? %) (< tx %)) tx %)))
                {}
                provenance-rows)]
    (mapv (fn [[eid entity]]
            (let [first-tx (get first-tx-by-eid eid)]
              {::entity entity
               ::first-tx first-tx
               ::first-process (get process-by-tx first-tx)}))
          entity-rows)))

(defn- lookup-ref-pairs
  [value]
  (cond
    (and (vector? value)
         (= 2 (count value))
         (schema/identity-attr? (first value)))
    #{value}

    (map? value)
    (into #{} (mapcat lookup-ref-pairs) (vals value))

    (coll? value)
    (into #{} (mapcat lookup-ref-pairs) value)

  :else #{}))

(defn- ^:async acquire-reconcile-state!
  [desired identity-attrs]
  (let [lookup-refs (lookup-ref-pairs desired)
        members
        (cond->
          [{::protocol/operation protocol/schema-operation}
           {::protocol/operation protocol/query-operation
            ::protocol/query-form reconcile-state-query
            ::protocol/arguments [(vec identity-attrs)]}
           {::protocol/operation protocol/query-operation
            ::protocol/query-form reconcile-provenance-query
            ::protocol/arguments [(vec identity-attrs)]}
           {::protocol/operation protocol/query-operation
            ::protocol/query-form reconcile-transaction-process-query
            ::protocol/arguments [(vec identity-attrs)]}]
          (seq lookup-refs)
          (conj {::protocol/operation protocol/query-operation
                 ::protocol/query-form reconcile-lookup-ref-query
                 ::protocol/arguments [(vec lookup-refs)]}))
        result (await
                 (db/execute-many
                   {::db/members members}))
        [schema-member entity-member provenance-member process-member
         lookup-member]
        (::db/results result)]
    {::db/coordinate (::db/coordinate result)
     ::installed-schema
     (member-value! "schema acquisition" schema-member ::protocol/schema)
     ::rows
     (acquisition-rows
       (vec
         (into {}
               (cond->
                 (member-value! "entity acquisition" entity-member
                                :datahike.query/result)
                 lookup-member
                 (into (member-value! "lookup-ref acquisition" lookup-member
                                      :datahike.query/result)))))
       (member-value! "provenance acquisition"
                      provenance-member :datahike.query/result)
       (member-value! "transaction process acquisition"
                      process-member :datahike.query/result))}))

(defn- stale-coordinate-envelope?
  [envelope]
  (let [data (get-in envelope [:seon.db/error :seon.error/data])]
    (or (= :transaction/stale-basis (:error data))
        (= protocol/stale-coordinate-error
           (:seon.db.protocol/error-kind data)))))

(defn- envelope-message
  [envelope]
  (or (get-in envelope [:seon.db/error :seon.error/message])
      (pr-str (:seon.db/error envelope))))

(defn ^:async reconcile!
  "Make the MANAGED datoms match `:seon.state/desired`.

   `:seon.state/desired` is a vector of entity-maps. The authority returns the
   managed rows and installed schema at ONE immutable coordinate. The compiler
   consumes that ordinary data and makes these attribute / connection moves —
   no Datahike handle and no entity 'kind' loop:

     1. ADD new desired entities by their OWN `:db.unique/identity` attr.
     2. For retained entities, compare scalar, cardinality-many, ref, and
        component values. Retract changed/omitted attributes before adding the
        exact desired values; component retractions cascade to owned children.
     3. ENUMERATE the current managed population PURELY BY PROVENANCE over the
        explicit process and identity attribute scopes.
     4. RETRACT (via `:db.fn/retractEntity`, which cascade-retracts component
        children) every managed entity whose identity is ABSENT from the
        desired set. Rows outside the managed process scope
        are NEVER touched.

   The operations land in ONE atomic transaction guarded by the acquired
   coordinate. A concurrent winner makes the serialized writer reject the
   stale coordinate, so reconcile reacquires/recompiles up to three times. An
   empty diff submits NO transaction. Writes inherit the ambient
   `seon.db/with-tx-context` user/process refs, so the caller establishes the
   appropriate boot or config process — the
   re-added rows then stay managed for the next reconcile. Errors are values:
   a desired map lacking exactly one identity attr, or a failed transact, comes
   back as `{:seon.state/ok? false :seon.state/error …}`.

     (db/with-tx-context {:seon.db/user [:seon.agent/id \"root\"]
                          :seon.db/process
                          [:seon.db.process/id :seon.db.process/boot]}
       (fn [] (seon.state/reconcile!
                {:seon.state/desired    [{:seon.route/name :main …} …]
                 :seon.db/managed-scope
                 #{:seon.db.process/boot :seon.db.process/config}
                 :seon.db/managed-identity-attrs
                 #{:seon.route/name :my.skills/name :seon.config/id}})))"
  {:malli/schema [:=> [:cat ::reconcile-request] ::reconcile-response]}
  [{::keys [desired]
    scope :seon.db/managed-scope
    identity-attrs :seon.db/managed-identity-attrs}]
  (if-let [validation-error
           (desired-validation-error desired identity-attrs)]
    {::ok? false ::error validation-error}
    (loop [attempt 1]
      (let [acquired (await (acquire-reconcile-state! desired identity-attrs))
            expected-coordinate (::db/coordinate acquired)
            compiled (compile-reconcile-tx
                       acquired desired scope identity-attrs)]
          (if (false? (::ok? compiled))
            (assoc compiled ::attempts attempt)
            (let [tx-data (::tx-data compiled)]
              (if (empty? tx-data)
                {::ok? true
                 ::changed? false
                 ::operations 0
                 ::attempts attempt
                 ::coordinate expected-coordinate}
                (let [envelope
                      (await (db/transact!
                               {:seon.db/expected-coordinate expected-coordinate
                                :seon.db/tx-data tx-data}))]
                  (cond
                    (true? (:seon.db/ok? envelope))
                    {::ok? true
                     ::changed? true
                     ::operations (count tx-data)
                     ::attempts attempt
                     ::coordinate (:seon.db/coordinate envelope)}

                    (and (stale-coordinate-envelope? envelope)
                         (< attempt max-reconcile-attempts))
                    (recur (inc attempt))

                    (stale-coordinate-envelope? envelope)
                    {::ok? false
                     ::attempts attempt
                     ::error (str "reconcile!: database head changed during "
                                  attempt " consecutive compile/commit "
                                  "attempts")}

                    :else
                    {::ok? false
                     ::attempts attempt
                     ::error (str "reconcile! transact failed: "
                                  (envelope-message envelope))})))))))))
