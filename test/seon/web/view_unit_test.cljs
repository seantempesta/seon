(ns seon.web.view-unit-test
  "Focused lifecycle proof for active database-derived render units."
  (:require
   [cljs.test :refer [async deftest is]]
   [datahike.api :as d]
   [seon.agent.ctx.render-fns]
   [seon.agent.message]
   [seon.db :as db]
   [seon.db.coordinate :as db.coordinate]
   [seon.schema :as schema]
   [seon.test.async :refer [settle!]]
   [seon.web.view-unit :as unit]))

(schema/register! ::id [:string {:seon.db/identity true}])
(schema/register! ::value :int)

(defn- fresh-conn
  []
  (let [config {:store {:backend :memory :id (random-uuid)}
                :schema-flexibility :write
                :keep-history? true}]
    (-> (d/create-database config)
        (.then (fn [_] (d/connect config {:sync? false})))
        (.then
         (fn [conn]
           (-> (db/ensure-provenance! {:seon.db/conn conn})
               (.then
                (fn [_]
                  (db/transact!
                   {:seon.db/conn conn
                    :seon.db/tx-data [{::id "debug" ::value 1}]})))
               (.then
                (fn [{ok? :seon.db/ok? error :seon.db/error}]
                  (when-not ok?
                    (throw (ex-info "view-unit seed failed"
                                    {:seon.db/error error})))
                  conn))))))))

(defn- debug-value
  [dbv]
  (db/query {:seon.db/db dbv
             :seon.db/query
             '[:find ?value .
               :where
               [?entity :seon.web.view-unit-test/id "debug"]
               [?entity :seon.web.view-unit-test/value ?value]]}))

(defn- debug-schema-present?
  [dbv]
  (contains? (db/installed-schema dbv) ::value))

(deftest lazy-debug-unit-replays-one-snapshot-and-releases-on-final-close
  (async done
    (-> (fresh-conn)
        (.then
         (fn [conn]
           (let [renders (atom 0)
                 producer (fn [dbv]
                            (swap! renders inc)
                            [:section {:id "debug-unit"}
                             (str "debug value " (debug-value dbv)
                                  ", schema " (debug-schema-present? dbv))])
                 coordinate {:seon.web.debug/agent-id "root"
                             :seon.web.debug/unit :raw-context}
                 before @conn
                 first-attach
                 (unit/attach-consumer
                  {::unit/state unit/empty-state
                   ::unit/coordinate coordinate
                   ::unit/consumer-id "tab-a"
                   :seon.db/db before
                   ::unit/database-coordinate (db.coordinate/resolved before)
                   ::unit/renderer-token "debug-v1"
                   ::unit/producer producer})
                 token (::unit/token first-attach)
                 shared-attach
                 (unit/attach-consumer
                  {::unit/state (::unit/state first-attach)
                   ::unit/coordinate coordinate
                   ::unit/consumer-id "tab-b"
                   :seon.db/db before
                   ::unit/database-coordinate (db.coordinate/resolved before)
                   ::unit/renderer-token "debug-v1"
                   ::unit/producer producer})]
             (is (= 1 @renders)
                 "closed content renders once at first activation, not catalog time")
             (is (= #{"tab-a" "tab-b"}
                    (::unit/consumers
                     (get-in (::unit/state shared-attach)
                             [::unit/units token])))
                 "equivalent consumers share one active derivation")
             (let [retained (get-in (::unit/state shared-attach)
                                    [::unit/units token])]
               (is (= 2 (count (::unit/read-observations retained)))
                   "the helper query and schema read are captured")
               (is (not (contains? retained :seon.db/db)))
               (is (not (contains? retained ::unit/producer))
                   "active state retains neither database nor producer"))
             (is (re-find #"debug value 1"
                          (::unit/serialized-element shared-attach)))
             (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data [{::id "unrelated" ::value 9}]})
                 (.then
                  (fn [{ok? :seon.db/ok? error :seon.db/error}]
                    (when-not ok?
                      (throw (ex-info "unrelated transaction failed"
                                      {:seon.db/error error})))
                    (let [unchanged
                          (unit/transition-unit
                           {::unit/state (::unit/state shared-attach)
                            ::unit/token token
                            :seon.db/db @conn
                            ::unit/database-coordinate (db.coordinate/resolved @conn)
                            ::unit/renderer-token "debug-v1"
                            ::unit/producer producer})]
                      (is (false? (::unit/rendered? unchanged))
                          "equal replayed helper result skips the producer")
                      (is (false? (::unit/emitted? unchanged)))
                      (is (= 1 @renders))
                      unchanged)))
                 (.then
                  (fn [unchanged]
                    (-> (db/transact!
                         {:seon.db/conn conn
                          :seon.db/tx-data [{::id "debug" ::value 2}]})
                        (.then (fn [response] [unchanged response])))))
                 (.then
                  (fn [[unchanged
                        {ok? :seon.db/ok? error :seon.db/error}]]
                    (when-not ok?
                      (throw (ex-info "relevant transaction failed"
                                      {:seon.db/error error})))
                    (let [replays (atom 0)
                          original db/read-observation-changed?
                          changed
                          (with-redefs
                           [db/read-observation-changed?
                            (fn [request]
                              (swap! replays inc)
                              (original request))]
                           (unit/transition-unit
                            {::unit/state (::unit/state unchanged)
                             ::unit/token token
                             :seon.db/db @conn
                             ::unit/database-coordinate (db.coordinate/resolved @conn)
                             ::unit/renderer-token "debug-v1"
                             ::unit/producer producer}))
                          first-close
                          (unit/detach-consumer
                           {::unit/state (::unit/state changed)
                            ::unit/token token
                            ::unit/consumer-id "tab-a"})
                          final-close
                          (unit/detach-consumer
                           {::unit/state (::unit/state first-close)
                            ::unit/token token
                            ::unit/consumer-id "tab-b"})]
                      (is (true? (::unit/rendered? changed)))
                      (is (true? (::unit/emitted? changed)))
                      (is (= 2 @replays)
                          "a changed first result does not short-circuit later reads")
                      (is (re-find #"debug value 2"
                                   (::unit/serialized-element changed)))
                      (is (= 2 @renders))
                      (is (false? (::unit/released? first-close)))
                      (is (true? (::unit/released? final-close)))
                      (is (= unit/empty-state (::unit/state final-close))
                          "final close drops consumers, observations, and output"))))))))
        (settle! done))))

(deftest literal-query-units-derive-and-clean-reverse-attribute-buckets
  (async done
    (-> (fresh-conn)
        (.then
         (fn [conn]
           (let [dbv @conn
                 coordinate {:seon.web.debug/agent-id "root"
                             :seon.web.debug/unit :query-only}
                 attached
                 (unit/attach-consumer
                  {::unit/state unit/empty-state
                   ::unit/coordinate coordinate
                   ::unit/consumer-id "tab-a"
                   :seon.db/db dbv
                   ::unit/database-coordinate (db.coordinate/resolved dbv)
                   ::unit/renderer-token "query-v1"
                   ::unit/producer
                   (fn [value]
                     [:section {:id "query-only"} (debug-value value)])})
                 token (::unit/token attached)
                 state (::unit/state attached)
                 complete-change
                 (fn [attributes]
                   {:seon.db/changed-attrs attributes
                    :seon.db/attr-index
                    (into {} (map #(vector % []) attributes))})]
             (is (= #{token}
                    (get-in state [::unit/tokens-by-attribute ::id])))
             (is (= #{token}
                    (get-in state [::unit/tokens-by-attribute ::value])))
             (is (empty? (::unit/broad-tokens state)))
             (is (= #{}
                    (unit/candidate-tokens
                     {::unit/state state
                      ::unit/change (complete-change #{::unrelated})}))
                 "an unrelated attribute selects no query unit")
             (is (= #{token}
                    (unit/candidate-tokens
                     {::unit/state state
                      ::unit/change (complete-change #{::value})})))
             (let [detached
                   (unit/detach-consumer
                    {::unit/state state
                     ::unit/token token
                     ::unit/consumer-id "tab-a"})]
               (is (true? (::unit/released? detached)))
               (is (= unit/empty-state (::unit/state detached))
                   "final release removes units and every reverse bucket")))))
        (settle! done))))
