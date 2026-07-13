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
