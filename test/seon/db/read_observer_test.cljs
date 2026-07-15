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

(defn- candidate [observation]
  (db/read-observation-candidate
    {:seon.db/read-observation observation}))

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
                         (let [captures (internal/current-operation-captures)
                               bigint (js/BigInt "9007199254740993")]
                           (internal/record-operation!
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

(deftest capture-and-replay-preserve-exact-resource-budgets
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [query-budgets
                  {::db/max-work 1234
                   ::db/max-results 17
                   ::db/max-result-weight 4096}
                  pull-budgets
                  {::db/max-work 567
                   ::db/max-results 11
                   ::db/max-result-weight 2048}
                  query-form
                  '[:find ?value
                    :in $ ?id
                    :where
                    [?e :seon.db.read-observer-test/id ?id]
                    [?e :seon.db.read-observer-test/value ?value]]
                  query-result (db/query query-form db-value "child")
                  pull-result (db/pull db-value [::id ::value] [::id "child"])
                  query-event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(db/query
                       (merge
                         {::db/db db-value
                          ::db/query query-form
                          ::db/args ["child"]}
                         query-budgets)))
                  pull-event
                  (capture-event
                    db-value :seon.db.read.operation/pull
                    #(db/pull
                       (merge
                         {::db/db db-value
                          ::db/pull-pattern [::id ::value]
                          ::db/ref [::id "child"]}
                         pull-budgets)))
                  replay-options (atom [])
                  replay-result
                  (with-redefs
                    [d/q (fn [request]
                           (swap! replay-options conj
                                  [:query (select-keys request
                                                       [:max-work
                                                        :max-results
                                                        :max-result-weight])])
                           query-result)
                     d/pull (fn [_ request]
                              (swap! replay-options conj
                                     [:pull (select-keys request
                                                        [:max-work
                                                         :max-results
                                                         :max-result-weight])])
                              pull-result)]
                    [(changed? db-value query-event)
                     (changed? db-value pull-event)])]
              (is (= query-budgets
                     (select-keys (:seon.db/read-request query-event)
                                  (keys query-budgets)))
                  "capture records the exact clamped query bounds")
              (is (= pull-budgets
                     (select-keys (:seon.db/read-request pull-event)
                                  (keys pull-budgets)))
                  "capture records the exact clamped pull bounds")
              (is (= [false false] replay-result)
                  "replay produces the same normalized query and pull results")
              (is (= [[:query {:max-work 1234
                               :max-results 17
                               :max-result-weight 4096}]
                      [:pull {:max-work 567
                              :max-results 11
                              :max-result-weight 2048}]]
                     @replay-options)
                  "replay passes every captured numeric bound to Datahike"))))
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

(deftest reserved-tag-shaped-data-remains-literal-through-replay
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [literal-values
                  [[:seon.db.read.value/instant 1720000000123]
                   [:seon.db.read.value/uuid
                    "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"]
                   [:seon.db.read.value/bigint "9007199254740993"]
                   [:seon.db.read.value/bytes [1 2 255]]]
                  query-form
                  '[:find ?input .
                    :in $ ?input
                    :where [(= 1 1)]]
                  event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(db/query query-form db-value literal-values))
                  normalized-result (:seon.db/read-result event)
                  [restored safe?]
                  (internal/denormalize-read-value normalized-result)]
              (is (true? (:seon.db/read-replayable? event)))
              (is (every? #(= :seon.db.read.value/literal-vector (first %))
                          normalized-result)
                  "literal tag-shaped vectors have an unambiguous escape")
              (is safe?)
              (is (= literal-values restored))
              (is (every? vector? restored)
                  "literal vectors do not become Date, UUID, BigInt, or bytes")
              (is (false? (changed? db-value event))
                  "replay reproduces the literal query result"))))
        (settle! done))))

(deftest normalization-collisions-disable-replay
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [left #js {:side "left"}
                  right #js {:side "right"}
                  colliding-map (assoc {} left :left right :right)
                  colliding-set (conj #{} left right)
                  query-form
                  '[:find ?input .
                    :in $ ?input
                    :where [(= 1 1)]]
                  map-event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(db/query query-form db-value colliding-map))
                  set-event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(db/query query-form db-value colliding-set))]
              (is (= 2 (count colliding-map)))
              (is (= 2 (count colliding-set)))
              (is (false? (:seon.db/read-replayable? map-event))
                  "normalizing opaque host keys cannot authorize a lossy map")
              (is (false? (:seon.db/read-replayable? set-event))
                  "normalizing opaque host values cannot authorize a lossy set")
              (is (true? (changed? db-value map-event)))
              (is (true? (changed? db-value set-event))))))
        (settle! done))))

(deftest malformed-known-read-requests-conservatively-change
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [query-event
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(helper-query db-value "child"))
                  pull-event
                  (capture-event
                    db-value :seon.db.read.operation/pull
                    #(db/pull db-value [::id ::value] [::id "child"]))
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
                  malformed
                  [(update query-event :seon.db/read-request
                           dissoc :seon.db/args)
                   (update pull-event :seon.db/read-request
                           dissoc :seon.db/ref)
                   (assoc-in entity-event
                             [:seon.db/read-request :seon.db/args]
                             [])
                   (assoc schema-event :seon.db/read-request
                          {:seon.db/args []})
                   (assoc basis-event :seon.db/read-request
                          {:seon.db/args []})
                   (assoc query-event :seon.db/read-result
                          [:seon.db.read.value/instant "not-ms"])]
                  replay
                  (db/capture-reads
                    {:seon.db/db db-value
                     :seon.db/thunk
                     #(mapv (partial changed? db-value) malformed)})]
              (is (every? true? (:seon.db/result replay))
                  "missing, extra, and malformed normalized facts all miss")
              (is (empty? (:seon.db/read-observations replay))
                  "rejecting malformed replay facts performs no public reads"))))
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
              (is (every? :seon.db/read-candidate-broad?
                          (map candidate non-replayable-events))
                  "the same unsafe admission boundary widens routing")
              (is (empty? (:seon.db/read-observations replay))
                  "conservative misses do not invoke public read paths"))))
        (settle! done))))

(deftest observation-candidates-narrow-only-proved-attribute-reads
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [literal-query
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(helper-query db-value "child"))
                  dynamic-query
                  (capture-event
                    db-value :seon.db.read.operation/query
                    #(db/query
                       {:seon.db/db db-value
                        :seon.db/query
                        '[:find ?value
                          :in $ ?attribute
                          :where [?entity ?attribute ?value]]
                        :seon.db/args [::value]}))
                  exact-prefix
                  (capture-event
                    db-value :seon.db.read.operation/index-datoms
                    #(db/index-datoms
                       {:seon.db/db db-value
                        :seon.db/index :aevt
                        :seon.db/components [::value]
                        :seon.db/index-limit 10
                        :seon.db/seek? false}))
                  seek-range
                  (capture-event
                    db-value :seon.db.read.operation/index-datoms
                    #(db/index-datoms
                       {:seon.db/db db-value
                        :seon.db/index :aevt
                        :seon.db/components [::value]
                        :seon.db/index-limit 10
                        :seon.db/seek? true}))
                  reverse-prefix
                  (capture-event
                    db-value :seon.db.read.operation/rseek-datoms
                    #(db/rseek-datoms
                       {:seon.db/db db-value
                        :seon.db/index :avet
                        :seon.db/components [::value]
                        :seon.db/index-limit 10
                        :seon.db/index-prefix? true}))
                  reverse-range
                  (capture-event
                    db-value :seon.db.read.operation/rseek-datoms
                    #(db/rseek-datoms
                       {:seon.db/db db-value
                        :seon.db/index :avet
                        :seon.db/components [::value]
                        :seon.db/index-limit 10
                        :seon.db/index-prefix? false}))
                  literal-candidate (candidate literal-query)]
              (is (false? (:seon.db/read-candidate-broad?
                            literal-candidate)))
              (is (= #{::id ::value ::at ::token}
                     (:seon.db/read-candidate-attributes
                       literal-candidate))
                  "Datahike projects every literal query attribute")
              (is (= {:seon.db/read-candidate-broad? false
                      :seon.db/read-candidate-attributes #{::value}}
                     (candidate exact-prefix))
                  "an exact AEVT attribute prefix owns one bucket")
              (is (= {:seon.db/read-candidate-broad? false
                      :seon.db/read-candidate-attributes #{::value}}
                     (candidate reverse-prefix))
                  "an exact reverse AVET prefix owns one bucket")
              (is (true? (:seon.db/read-candidate-broad?
                           (candidate dynamic-query)))
                  "a variable attribute position widens")
              (is (true? (:seon.db/read-candidate-broad?
                           (candidate seek-range)))
                  "a comparator range remains broad")
              (is (true? (:seon.db/read-candidate-broad?
                           (candidate reverse-range)))
                  "an unbounded reverse range remains broad")
              (is (true? (:seon.db/read-candidate-broad?
                           (candidate
                             (assoc literal-query
                                    :seon.db/read-source
                                    :seon.db.read.source/foreign))))
                  "foreign observations cannot authorize narrowing"))))
        (settle! done))))

(deftest awaited-operation-capture-retains-write-query-order-and-coordinates
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{conn :conn db-value :db}]
            (internal/capture-operations!
              db-value
              (fn ^:async capture-write-query! []
                (let [written
                      (await
                        (db/transact!
                          {:seon.db/conn conn
                           :seon.db/tx-data
                           [{::id "child" ::value 11}]}))
                      scalar
                      (db/query
                        {:seon.db/db @conn
                         :seon.db/query
                         '[:find ?value .
                           :where
                           [?e :seon.db.read-observer-test/id "child"]
                           [?e :seon.db.read-observer-test/value ?value]]})]
                  {:written written
                   :scalar scalar
                   :head (db/head-coordinate @conn)})))))
        (.then
          (fn [capture]
            (let [operations (:seon.db/read-observations capture)
                  [write query] operations
                  result (:seon.db/result capture)]
              (is (= 2 (count operations)))
              (is (= [0 1] (mapv :seon.db/operation-position operations)))
              (is (= [:seon.db.read.operation/transact
                      :seon.db.read.operation/query]
                     (mapv :seon.db/read-operation operations)))
              (is (every? true? (map :seon.db/operation-ok? operations)))
              (is (every? #(= :seon.db.read.source/captured
                              (:seon.db/read-source %))
                          operations)
                  "a post-write read on the same attachment remains captured")
              (is (= [{::id "child" ::value 11}]
                     (:seon.db/tx-data (:seon.db/read-request write))))
              (is (= 11 (:scalar result)))
              (is (= 11 (:seon.db/read-result query)))
              (is (= (:seon.db/coordinate (:written result))
                     (:seon.db/operation-coordinate write)))
              (is (= (:head result)
                     (:seon.db/operation-coordinate query))))))
        (settle! done))))

(deftest failed-transaction-envelope-is-an-operation-failure
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{conn :conn db-value :db}]
            (internal/capture-operations!
              db-value
              (fn ^:async capture-failed-write! []
                (await
                  (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data
                     [{:seon.db.read-observer-test/unregistered 1}]}))))))
        (.then
          (fn [capture]
            (let [operations (:seon.db/read-observations capture)
                  operation (first operations)
                  envelope (:seon.db/result capture)]
              (is (= 1 (count operations)))
              (is (false? (:seon.db/ok? envelope))
                  "the Clojure eval can succeed while the write envelope fails")
              (is (false? (:seon.db/operation-ok? operation)))
              (is (false? (:seon.db/ok?
                            (:seon.db/read-result operation))))
              (is (= 0 (:seon.db/operation-position operation))))))
        (settle! done))))

(deftest awaited-operation-capture-is-nested-and-fiber-local
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [capture-one
                  (internal/capture-operations!
                    db-value
                    (fn ^:async capture-one! []
                      (await (js/Promise.resolve :yield))
                      (helper-query db-value "child")))
                  capture-two
                  (internal/capture-operations!
                    db-value
                    (fn ^:async capture-two! []
                      (let [inner
                            (await
                              (internal/capture-operations!
                                db-value
                                (fn ^:async capture-inner! []
                                  (await (js/Promise.resolve :yield))
                                  (helper-query db-value "parent"))))]
                        (helper-query db-value "child")
                        inner)))]
              (js/Promise.all #js [capture-one capture-two]))))
        (.then
          (fn [captures]
            (let [one (aget captures 0)
                  two (aget captures 1)
                  inner (:seon.db/result two)]
              (is (= 1 (count (:seon.db/read-observations one)))
                  "a concurrent capture never receives the other fiber's reads")
              (is (= 2 (count (:seon.db/read-observations two)))
                  "an outer capture includes its nested scope and later read")
              (is (= 1 (count (:seon.db/read-observations inner)))
                  "the nested capture owns only its dynamic extent")
              (is (= [0 1]
                     (mapv :seon.db/operation-position
                           (:seon.db/read-observations two)))))))
        (settle! done))))

(deftest historical-query-is-not-current-attachment-evidence
  (async done
    (-> (fresh-seeded)
        (.then
          (fn [{db-value :db}]
            (let [historical (db/as-of db-value (dec (db/basis-t db-value)))
                  capture
                  (db/capture-reads
                    {:seon.db/db db-value
                     :seon.db/thunk
                     #(db/query
                        {:seon.db/db historical
                         :seon.db/query
                         '[:find (count ?e) .
                           :where
                           [?e :seon.db.read-observer-test/id]]})})
                  operation (first (:seon.db/read-observations capture))]
              (is (= :seon.db.read.source/foreign
                     (:seon.db/read-source operation)))
              (is (false? (:seon.db/read-replayable? operation)))
              (is (< (:seon.db.coordinate/t
                       (:seon.db/operation-coordinate operation))
                     (:seon.db.coordinate/t (db/head-coordinate db-value)))))))
        (settle! done))))
