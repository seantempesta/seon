(ns seon.reconcile
  "Declared configuration converges into database facts: the pure exact
  diff and its one apply operation.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — B2 wave, from
  b2-plan §7; the algorithm is quarried from State A's provenance-scoped
  reconciler and simplified by the writer's serial execution). The
  schemas and function contracts are SEALED once the sealed suite
  lands: test/seon/reconcile_test.clj is NOT YET AUTHORED (it needs the
  fresh provenance attributes — :seon.db/user/:seon.db/process — which
  arrive with the config package). No implementation lane starts before
  that suite is committed.

  The model:

  - The managed slice is defined by PROVENANCE, never a taxonomy: an
    entity is managed when its identity's first assertion carries the
    managing process identity in its transaction metadata, or when its
    identity was explicitly adopted. Reconcile takes no kind argument
    and runs in attribute space.
  - Every desired entity map carries exactly ONE identity attribute —
    the upsert handle. Zero or two refuse, never guess.
  - `plan` is PURE over a database value: the exact tx-data that
    converges the managed population onto the desired one — changed
    attributes retracted and re-asserted, absent-from-desired managed
    entities retracted entirely. An EMPTY plan means converged.
  - CONVERGED = ZERO TRANSACTIONS. Datahike commits a transaction
    entity even for empty tx-data, so `reconcile!` computes the plan
    FIRST and issues NO transaction when it is empty. The observable
    acceptance fact: `:max-tx` is identical before and after a
    converged re-apply.
  - A non-empty plan recomputes INSIDE the writer via
    `[:db.fn/call #'reconcile-call request]` — the N2 idiom. There is
    no stale basis inside the serial writer, so State A's three-attempt
    retry is deleted, not ported.
  - A desired identity already owned by an entity OUTSIDE the managed
    scope refuses loudly, never silently adopts.
  - Drift repair is not a feature: a hand-edited fact diverges from
    desired and the next apply converges it. Nothing detects drift;
    reconcile just converges.

  Crash walk: `plan` is pure; `reconcile!` is ONE atomic transaction —
  a kill leaves it fully applied or absent, and re-apply converges
  either way."
  (:require [clojure.set :as set]
            [seon.db :as seon.db]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas: resources/seon/schema.edn owns this namespace's registrations.
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn- refuse!
  [rule data]
  (throw
   (ex-info
    (str "Reconciliation refused: " (name rule) ".")
    (merge {:seon.error/kind ::refused
            ::rule rule}
           data))))

(defn- identity-attributes
  ;; One resolution for the whole scan. `schema/identity-attr?`'s one-argument
  ;; arity resolves the declaration population per call, which with no
  ;; projection supplied re-reads and re-merges every schema resource per key
  ;; — measured 2026-08-07 at 25,916 ms and 286,672 resource reads for this one
  ;; function (issue packaged-forms-rereads-every-schema-resource-per-call).
  ([] (identity-attributes (schema/declaration-population)))
  ([forms]
   (into #{}
         (filter #(schema/identity-attr? forms %))
         (keys forms))))

(defn- desired-identity
  [identity-attrs desired]
  (let [attrs (into [] (filter identity-attrs) (keys desired))]
    (case (count attrs)
      0 (refuse! ::no-identity {::desired-map desired})
      1 (let [attr (first attrs)]
          [attr (get desired attr)])
      (refuse! ::two-identities
               {::desired-map desired
                ::identity-attributes (set attrs)}))))

(defn- desired-identities
  [identity-attrs desired]
  (let [identities (mapv #(desired-identity identity-attrs %) desired)
        duplicate (some (fn [[identity n]]
                          (when (> n 1) identity))
                        (frequencies identities))]
    (when duplicate
      (refuse! ::duplicate-identity {::identity duplicate}))
    identities))

(defn- installed-identity-attributes
  [db identity-attrs]
  (into #{}
        (filter #(contains? (:schema db) %))
        identity-attrs))

(defn- current-identity-facts
  [db identity-attrs]
  (into []
        (mapcat
         (fn [attr]
           (map (fn [[eid value]]
                  {:db/id eid ::identity [attr value]})
                (seon.db/q
                 '[:find ?entity ?value
                   :in $ ?identity-attr
                   :where
                   [?entity ?identity-attr ?value]]
                 db
                 attr))))
        identity-attrs))

(defn- first-assertion-transactions
  [db identity-attrs]
  (if-not (true? (get-in db [:config :keep-history?]))
    {}
    (let [history (seon.db/history db)]
      (reduce
       (fn [first-by-identity attr]
         (reduce
          (fn [result [eid value tx]]
            (update result
                    [eid [attr value]]
                    #(if (or (nil? %) (< tx %)) tx %)))
          first-by-identity
          (seon.db/q
           '[:find ?entity ?value ?tx
             :in $ ?identity-attr
             :where
             [?entity ?identity-attr ?value ?tx true]]
           history
           attr)))
       {}
       identity-attrs))))

(defn- process-by-transaction
  [db]
  (into {}
        (seon.db/q
         '[:find ?tx ?process-id
           :where
           [?tx :seon.db/process ?process]
           [?process :seon.db.process/id ?process-id]]
         db)))

(defn- cardinality-many?
  [db attr]
  (= :db.cardinality/many
     (get-in db [:schema attr :db/cardinality])))

(defn- ref-attribute?
  [db attr]
  (= :db.type/ref
     (get-in db [:schema attr :db/valueType])))

(defn- component-ref?
  [db attr]
  (true? (get-in db [:schema attr :db/isComponent])))

(defn- value-eid
  [identity-eids value]
  (cond
    (number? value) value
    (vector? value) (get identity-eids value)
    (map? value) (:db/id value)
    :else nil))

(declare normalize-current-entity normalize-desired-entity)

(defn- normalize-current-one
  [db entities identity-eids attr value]
  (cond
    (component-ref? db attr)
    (normalize-current-entity db entities identity-eids value)

    (ref-attribute? db attr)
    (value-eid identity-eids value)

    :else value))

(defn- normalize-desired-one
  [db entities identity-eids identity-attrs attr value]
  (cond
    (component-ref? db attr)
    (normalize-desired-entity
     db entities identity-eids identity-attrs value)

    (ref-attribute? db attr)
    (or (value-eid identity-eids value)
        (when (map? value)
          (let [attrs (into [] (filter identity-attrs) (keys value))]
            (when (= 1 (count attrs))
              (get identity-eids
                   [(first attrs) (get value (first attrs))]))))
        value)

    :else value))

(defn- normalize-value
  [db attr normalize-one value]
  (if (cardinality-many? db attr)
    (into #{} (map normalize-one) (or value []))
    (normalize-one value)))

(defn- current-entity-map
  [entities identity-eids value]
  (if (map? value)
    value
    (some->> (value-eid identity-eids value)
             (get entities))))

(defn- normalize-current-entity
  [db entities identity-eids value]
  (into {}
        (keep
         (fn [[attr attr-value]]
           (when (not= :db/id attr)
             [attr
              (normalize-value
               db attr
               #(normalize-current-one
                 db entities identity-eids attr %)
               attr-value)])))
        (current-entity-map entities identity-eids value)))

(defn- normalize-desired-entity
  [db entities identity-eids identity-attrs entity]
  (into {}
        (keep
         (fn [[attr attr-value]]
           (when (not= :db/id attr)
             [attr
              (normalize-value
               db attr
               #(normalize-desired-one
                 db entities identity-eids identity-attrs attr %)
               attr-value)])))
        entity))

(defn- attr-equivalent?
  [db entities identity-eids identity-attrs attr current desired]
  (= (normalize-value
      db attr
      #(normalize-current-one db entities identity-eids attr %)
      current)
     (normalize-value
      db attr
      #(normalize-desired-one
        db entities identity-eids identity-attrs attr %)
      desired)))

(defn- canonical-desired-entity
  [db entity]
  (into {}
        (remove
         (fn [[attr value]]
           (and (cardinality-many? db attr)
                (empty? value))))
        entity))

(defn- entity-exact-tx
  [db entities identity-eids identity-attrs identity desired current]
  (if-not current
    [desired]
    (let [[identity-attr identity-value] identity
          attrs (-> (set/union (set (keys current))
                               (set (keys desired)))
                    (disj :db/id identity-attr))
          changed
          (->> attrs
               (filter
                (fn [attr]
                  (let [current? (contains? current attr)
                        desired? (contains? desired attr)]
                    (or (not= current? desired?)
                        (and current?
                             desired?
                             (not
                              (attr-equivalent?
                               db entities identity-eids identity-attrs
                               attr
                               (get current attr)
                               (get desired attr))))))))
               (sort-by str)
               vec)
          retracts
          (into []
                (keep
                 (fn [attr]
                   (when (contains? current attr)
                     [:db.fn/retractAttribute identity attr])))
                changed)
          additions
          (reduce
           (fn [result attr]
             (if (contains? desired attr)
               (assoc result attr (get desired attr))
               result))
           {identity-attr identity-value}
           changed)]
      (cond-> retracts
        (> (count additions) 1) (conj additions)))))

(declare plan-transaction-data)

(defn plan
  "The exact tx-data converging `db` onto the desired population.
  Pure. Empty vector = converged, and the caller must then issue NO
  transaction. Refuses `::no-identity` / `::two-identities` (a desired
  map without exactly one registered identity attribute),
  `::duplicate-identity` (two desired maps with one upsert handle), and
  `::identity-outside-scope` (a desired identity already owned by an
  entity whose provenance is neither the managing process nor an
  adopted identity)."
  {:malli/schema [:=> [:cat :seon.db/database-value ::request]
                  :seon.store/transaction-data]}
  [db request]
  ;; ONE declaration population for the whole plan. Every `seon.db` read below
  ;; resolves its own when nothing is supplied, and `plan` pulls once PER
  ;; MANAGED ENTITY — thousands of complete classpath re-reads, which wedged
  ;; `seon.reconcile-test` and `seon.config-application-test` at the 300 s
  ;; liveness backstop (2026-08-07). `db/pull` deliberately takes no population
  ;; argument, so the operation supplies the one it already resolved for its
  ;; own extent; this is the same value, made visible, not a cache.
  (let [forms (schema/declaration-population)]
    (schema/call-with-forms forms #(plan-transaction-data forms db request))))

(defn- plan-transaction-data
  [forms db request]
  (let [{::keys [desired process adopt-identities]} request
        adopt-identities (or adopt-identities #{})
        identity-attrs (identity-attributes forms)
        identities (desired-identities identity-attrs desired)
        installed-attrs
        (installed-identity-attributes db identity-attrs)
        facts (current-identity-facts db installed-attrs)
        entity-identities
        (reduce
         (fn [result {:keys [db/id] ::keys [identity]}]
           (update result id (fnil conj #{}) identity))
         {}
         facts)
        identity-eids
        (into {}
              (map (juxt ::identity :db/id))
              facts)
        first-tx
        (first-assertion-transactions db installed-attrs)
        process-by-tx (process-by-transaction db)
        managed-eids
        (into #{}
              (keep
               (fn [[eid entity-ids]]
                 (when
                  (some
                   (fn [identity]
                     (or (contains? adopt-identities identity)
                         (= process
                            (get process-by-tx
                                 (get first-tx [eid identity])))))
                   entity-ids)
                   eid)))
              entity-identities)
        managed-by-identity
        (into {}
              (mapcat
               (fn [eid]
                 (map (fn [identity] [identity eid])
                      (get entity-identities eid))))
              managed-eids)
        outside
        (some
         (fn [identity]
           (when-let [eid (get identity-eids identity)]
             (when (not= eid (get managed-by-identity identity))
               identity)))
         identities)]
    (when outside
      (refuse! ::identity-outside-scope {::identity outside}))
    (let [entities
          ;; Reconciliation can inspect or change only managed entities. Pull
          ;; after provenance has established that finite slice; wildcard-
          ;; pulling every identity-bearing program entity made the cost of a
          ;; config apply proportional to the whole source fork.
          (into {}
                (map
                 (fn [eid]
                   [eid (seon.db/pull db '[*] eid)]))
                managed-eids)
          desired
          (mapv #(canonical-desired-entity db %) desired)
          desired-set (set identities)
          entity-tx
          (into []
                (mapcat
                 (fn [[identity entity]]
                   (entity-exact-tx
                    db entities identity-eids identity-attrs
                    identity
                    entity
                    (some->> (get managed-by-identity identity)
                             (get entities)))))
                (sort-by (comp pr-str first)
                         (map vector identities desired)))
          stale-eids
          (->> managed-eids
               (filter
                (fn [eid]
                  (empty?
                   (set/intersection
                    desired-set
                    (get entity-identities eid)))))
               sort
               vec)]
      (into entity-tx
            (map (fn [eid] [:db.fn/retractEntity eid]))
            stale-eids))))

(declare reconcile-call)

(defn reconcile!
  "Apply the plan through the one connection, converged = zero writes.
  Computes `plan` against the connection's current value first; an
  empty plan issues NO transaction and returns
  {::converged? true ::operations 0} — `:max-tx` provably unchanged.
  A non-empty plan commits exactly one transaction that recomputes
  inside the writer via `[:db.fn/call #'reconcile-call request]`,
  returning {::converged? false ::operations n}. Refusals are `plan`'s,
  surfaced before any transaction when the pre-check already sees them
  and atomically from inside the writer otherwise."
  {:malli/schema [:=> [:cat :seon.db/connection ::request] ::result]}
  [connection request]
  (let [tx-data (plan @connection request)
        operations (count tx-data)]
    (if (zero? operations)
      {::converged? true
       ::operations 0}
      (let [result
            (seon.db/transact!
             connection
             {:tx-data [[:db.fn/call #'reconcile-call request]]
              :tx-meta
              {:seon.db/process
               [:seon.db.process/id (::process request)]}})]
        (if (:seon.error/kind result)
          result
          {::converged? false
           ::operations operations})))))

(defn reconcile-call
  "The in-writer recomputation — the N2 transition idiom.
  Invoked as [:db.fn/call #'reconcile-call request]: one pure function
  of the mid-transaction database value returning the final tx-data."
  {:malli/schema [:=> [:cat :seon.db/database-value ::request]
                  :seon.store/transaction-data]}
  [db request]
  (plan db request))
