(ns seon.db.read-observer-test
  "Behavioral contract for synchronous runtime-only database read capture."
  (:require
    [cljs.test :refer [async deftest is]]
    [datahike.api :as d]
    [datahike.impl.entity :as dentity]
    ;; These are the two schema owners required by provenance genesis. Keep
    ;; this test's dependency graph narrower than the full agent runtime.
    [seon.agent.ctx.render-fns]
    [seon.agent.message]
    [seon.db :as db]
    [seon.db.internal :as internal]
    [seon.schema :as schema]
    [seon.test.async :refer [settle!]]))

(schema/register! ::id [:string {:seon.db/identity true}])
(schema/register! ::value :int)
(schema/register! ::at :inst)
(schema/register! ::token :uuid)
(schema/register! ::child :seon.db/ref)

(defn- fresh-seeded
  "Promise of a fresh conn holding a parent and referenced child."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}
        at (js/Date. 1720000000123)
        token (uuid "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_]
                              (db/transact!
                                {:seon.db/conn conn
                                 :seon.db/tx-data
                                 [{:db/id -1
                                   ::id "parent"
                                   ::at at
                                   ::token token
                                   ::child -2}
                                  {:db/id -2
                                   ::id "child"
                                   ::value 7
                                   ::at at
                                   ::token token}]})))
                     (.then (fn [{ok? :seon.db/ok? error :seon.db/error}]
                              (when-not ok?
                                (throw (ex-info "read-observer seed failed"
                                                {:seon.db/error error})))
                              {:conn conn :db @conn :at at :token token}))))))))

(defn- helper-query
  "A compiled helper boundary: capture must see the `seon.db` call within it."
  [db-value id]
  (db/query {:seon.db/db db-value
             :seon.db/query
             '[:find ?value ?at ?token
               :in $ ?id
               :where
               [?e :seon.db.read-observer-test/id ?id]
               [?e :seon.db.read-observer-test/value ?value]
               [?e :seon.db.read-observer-test/at ?at]
               [?e :seon.db.read-observer-test/token ?token]]
             :seon.db/args [id]}))

(defn- observations-for [capture operation]
  (filterv #(= operation (:seon.db/read-operation %))
           (:seon.db/read-observations capture)))

(defn- capture-event
  "Capture one expected semantic read and return its observation."
  [db-value operation thunk]
  (let [capture (db/capture-reads
                  {:seon.db/db db-value
                   :seon.db/thunk thunk})
        events (observations-for capture operation)]
    (is (= 1 (count events))
        (str "expected one " operation " observation"))
    (first events)))

(defn- changed? [db-value observation]
  (db/read-observation-changed?
    {:seon.db/db db-value
     :seon.db/read-observation observation}))

(defn- runtime-handle?
  "True when normalized output retained a DB handle or lazy Entity."
  [value]
  (or (internal/db-value? value)
      (dentity/entity? value)))

(defn- contains-runtime-handle? [value]
  (boolean
    (some runtime-handle?
          (tree-seq coll? seq value))))

(deftest captures-transitive-reads-as-immutable-facts
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db at :at token :token}]
            (let [capture
                  (db/capture-reads
                    {:seon.db/db db-value
                     :seon.db/thunk #(helper-query db-value "child")})
                  query-events
                  (observations-for capture :seon.db.read.operation/query)
                  event (first query-events)]
              (is (= 1 (count query-events))
                  "a semantic query records once; its schema guard is internal")
              (is (= #{[7
                        [:seon.db.read.value/instant 1720000000123]
                        [:seon.db.read.value/uuid
                         "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"]]}
                     (:seon.db/read-result event))
                  "host scalars normalize to immutable tagged values")
              (is (= :seon.db.read.source/captured
                     (:seon.db/read-source event)))
              (is (true? (:seon.db/read-replayable? event)))
              (is (= ["child"]
                     (:seon.db/args (:seon.db/read-request event))))
              (is (not (contains-runtime-handle? event))
                  "observation owns no DB or lazy Entity")

              (let [scalar-capture
                    (db/capture-reads
                      {:seon.db/db db-value
                       :seon.db/thunk
                       (fn []
                         (let [captures (internal/current-read-captures)
                               bigint (js/BigInt "9007199254740993")]
                           (internal/record-read!
                             captures :seon.db.read.operation/query db-value
                             {:seon.db/args [at token bigint]}
                             [at token bigint]
                             true)
                           :captured))})
                    scalar-event
                    (first (observations-for
                             scalar-capture :seon.db.read.operation/query))]
                (is (= [[:seon.db.read.value/instant 1720000000123]
                        [:seon.db.read.value/uuid
                         "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"]
                        [:seon.db.read.value/bigint "9007199254740993"]]
                       (:seon.db/args (:seon.db/read-request scalar-event))))
                (is (= [[:seon.db.read.value/instant 1720000000123]
                        [:seon.db.read.value/uuid
                         "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"]
                        [:seon.db.read.value/bigint "9007199254740993"]]
                       (:seon.db/read-result scalar-event)))
                (is (true? (:seon.db/read-replayable? scalar-event))
                    "common immutable Datahike scalars stay cache-eligible")))))
        (settle! done))))

(deftest nested-scopes-compose-and-foreign-or-lazy-reads-miss
  (async done
    (-> (js/Promise.all #js [(fresh-seeded) (fresh-seeded)])
        (.then
          (fn [fixtures]
            (let [{outer-db :db} (aget fixtures 0)
                  {foreign-db :db} (aget fixtures 1)
                  outer
                  (db/capture-reads
                    {:seon.db/db outer-db
                     :seon.db/thunk
                     (fn []
                       (helper-query outer-db "child")
                       (db/capture-reads
                         {:seon.db/db outer-db
                          :seon.db/thunk
                          #(helper-query outer-db "child")}))})
                  inner (:seon.db/result outer)
                  outer-queries
                  (observations-for outer :seon.db.read.operation/query)
                  inner-queries
                  (observations-for inner :seon.db.read.operation/query)]
              (is (= 2 (count outer-queries))
                  "outer scope includes reads performed inside the inner scope")
              (is (= 1 (count inner-queries))
                  "inner scope owns only reads from its dynamic extent")

              (let [foreign
                    (db/capture-reads
                      {:seon.db/db outer-db
                       :seon.db/thunk #(helper-query foreign-db "child")})
                    event (first (observations-for
                                   foreign :seon.db.read.operation/query))]
                (is (= :seon.db.read.source/foreign
                       (:seon.db/read-source event)))
                (is (false? (:seon.db/read-replayable? event)))
                (is (not (contains-runtime-handle? event))))

              (let [lazy-capture
                    (db/capture-reads
                      {:seon.db/db outer-db
                       :seon.db/thunk
                       #(db/entity-lazy
                          outer-db
                          [:seon.db.read-observer-test/id "parent"])})
                    event (first (observations-for
                                   lazy-capture
                                   :seon.db.read.operation/entity-lazy))]
                (is (= {:db/id (:db/id (:seon.db/result lazy-capture))}
                       (:seon.db/read-result event)))
                (is (false? (:seon.db/read-replayable? event)))
                (is (not (contains-runtime-handle? event))))

              (let [entity-capture
                    (db/capture-reads
                      {:seon.db/db outer-db
                       :seon.db/thunk
                       #(db/entity
                          outer-db
                          [:seon.db.read-observer-test/id "parent"])})
                    event (first (observations-for
                                   entity-capture
                                   :seon.db.read.operation/entity))]
                (is (false? (:seon.db/read-replayable? event))
                    "a touched entity with a lazy non-component ref misses")
                (is (not (contains-runtime-handle? event))
                    "nested Entity values normalize to immutable :db/id facts"))

              (let [error
                    (try
                      (db/capture-reads
                        {:seon.db/db outer-db
                         :seon.db/thunk #(js/Promise.resolve :too-late)})
                      nil
                      (catch :default e e))]
                (is (some? error))
                (is (= :seon.db/asynchronous-read-capture
                       (:seon.db/error (ex-data error))))))))
        (settle! done))))

(deftest semantic-read-replay-invalidates-only-changed-results
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{conn :conn db-value :db}]
            (let [query-event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(helper-query db-value "child"))
                  pull-event
                  (capture-event
                    db-value :seon.db.read.operation/pull
                    #(db/pull db-value
                              [::id ::value ::at ::token]
                              [::id "child"]))
                  entity-event
                  (capture-event
                    db-value :seon.db.read.operation/entity
                    #(db/entity db-value [::id "child"]))
                  schema-event
                  (capture-event
                    db-value :seon.db.read.operation/installed-schema
                    #(db/installed-schema db-value))
                  basis-event
                  (capture-event
                    db-value :seon.db.read.operation/basis-t
                    #(db/basis-t db-value))
                  observations
                  [query-event pull-event entity-event schema-event basis-event]
                  replay-capture
                  (db/capture-reads
                    {:seon.db/db db-value
                     :seon.db/thunk
                     #(mapv (partial changed? db-value) observations)})]
              (is (every? true? (map :seon.db/read-replayable? observations))
                  "all five semantic reads are replayable")
              (is (= [false false false false false]
                     (:seon.db/result replay-capture))
                  "unchanged reads preserve their normalized results")
              (is (empty? (:seon.db/read-observations replay-capture))
                  "raw replay helpers do not recursively record reads")

              (-> (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data [{::id "child" ::value 7}]})
                  (.then
                    (fn [{ok? :seon.db/ok? error :seon.db/error}]
                      (is ok? (str "equal assertion succeeds: " (pr-str error)))
                      (let [same-db @conn]
                        (is (every? false?
                                    (map (partial changed? same-db)
                                         [query-event pull-event entity-event
                                          schema-event]))
                            "an equal assertion preserves every equal result"))))
                  (.then
                    (fn [_]
                      (db/transact!
                        {:seon.db/conn conn
                         :seon.db/tx-data [{::id "parent" ::value 99}]})))
                  (.then
                    (fn [{ok? :seon.db/ok? error :seon.db/error}]
                      (is ok? (str "unrelated transaction succeeds: "
                                   (pr-str error)))
                      (let [unrelated-db @conn]
                        (is (false? (changed? unrelated-db query-event)))
                        (is (false? (changed? unrelated-db pull-event)))
                        (is (false? (changed? unrelated-db entity-event)))
                        (is (false? (changed? unrelated-db schema-event)))
                        (is (true? (changed? unrelated-db basis-event))
                            "basis reads intentionally depend on every tx"))))
                  (.then
                    (fn [_]
                      (db/transact!
                        {:seon.db/conn conn
                         :seon.db/tx-data [{::id "child" ::value 8}]})))
                  (.then
                    (fn [{ok? :seon.db/ok? error :seon.db/error}]
                      (is ok? (str "relevant transaction succeeds: "
                                   (pr-str error)))
                      (let [changed-db @conn]
                        (is (true? (changed? changed-db query-event)))
                        (is (true? (changed? changed-db pull-event)))
                        (is (true? (changed? changed-db entity-event)))
                        (is (false? (changed? changed-db schema-event)))
                        (is (true? (changed? changed-db basis-event))))))))))
        (settle! done))))

(deftest tagged-request-values-round-trip-through-replay
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db at :at token :token}]
            (let [bigint (js/BigInt "9007199254740993")
                  byte-value (js/Uint8Array. #js [1 2 255])
                  query-form
                  '[:find ?at ?token ?bigint ?bytes
                    :in $ ?at ?token ?bigint ?bytes
                    :where [(= 1 1)]]
                  event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(db/query query-form db-value at token bigint byte-value))
                  args (:seon.db/args (:seon.db/read-request event))
                  replay
                  (db/capture-reads
                    {:seon.db/db db-value
                     :seon.db/thunk #(changed? db-value event)})]
              (is (= [[:seon.db.read.value/instant 1720000000123]
                      [:seon.db.read.value/uuid
                       "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"]
                      [:seon.db.read.value/bigint "9007199254740993"]
                      [:seon.db.read.value/bytes [1 2 255]]]
                     args)
                  "all observer-owned request tags are immutable data")
              (is (false? (:seon.db/result replay))
                  "denormalized request tags reproduce the captured result")
              (is (empty? (:seon.db/read-observations replay))
                  "tag replay does not emit another observation"))))
        (settle! done))))

(deftest unsafe-or-unknown-observations-conservatively-change
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [query-event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(helper-query db-value "child"))
                  t (db/basis-t db-value)
                  non-replayable-events
                  [(assoc query-event
                          :seon.db/read-source :seon.db.read.source/foreign
                          :seon.db/read-replayable? false)
                   (capture-event
                     db-value :seon.db.read.operation/entity-lazy
                     #(db/entity-lazy db-value [::id "child"]))
                   (capture-event
                     db-value :seon.db.read.operation/history
                     #(db/history db-value))
                   (capture-event
                     db-value :seon.db.read.operation/as-of
                     #(db/as-of db-value t))
                   (capture-event
                     db-value :seon.db.read.operation/since
                     #(db/since db-value t))
                   (assoc query-event
                          :seon.db/read-operation
                          :seon.db.read.operation/future-unknown)
                   (assoc-in query-event
                             [:seon.db/read-request :seon.db/args]
                             [[:seon.db.read.value/instant "not-ms"]])]
                  replay
                  (db/capture-reads
                    {:seon.db/db db-value
                     :seon.db/thunk
                     #(mapv (partial changed? db-value)
                            non-replayable-events)})]
              (is (every? true? (:seon.db/result replay))
                  "foreign, lazy, temporal, unknown, and malformed reads miss")
              (is (empty? (:seon.db/read-observations replay))
                  "conservative misses do not invoke public read paths"))))
        (settle! done))))
