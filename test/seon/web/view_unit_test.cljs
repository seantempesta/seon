(ns seon.web.view-unit-test
  "Focused lifecycle proof for active database-derived render units."
  (:require
   [cljs.test :refer [async deftest is]]
   [clojure.set :as set]
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
(schema/register! ::unrelated :int)

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
                    :seon.db/tx-data [{::id "debug"
                                       ::value 1
                                       ::unrelated 1}]})))
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

(defn- complete-change
  "Build complete routing evidence for a known immutable-db transaction."
  [attributes]
  {:seon.db/changed-attrs attributes
   :seon.db/attr-index
   (into {} (map #(vector % []) attributes))})

(defn- replay-all-dirty-tokens
  "Test oracle: tokens whose retained reads differ on one immutable db value."
  [state dbv]
  (into #{}
        (keep
         (fn [[token retained]]
           (let [observations (::unit/read-observations retained)]
             (when (or (empty? observations)
                       (some #(db/read-observation-changed?
                               {:seon.db/db dbv
                                :seon.db/read-observation %})
                             observations))
               token))))
        (::unit/units state)))

(defn- attach-oracle-unit
  [state dbv unit-name producer]
  (unit/attach-consumer
   {::unit/state state
    ::unit/coordinate {::oracle-unit unit-name}
    ::unit/consumer-id "oracle"
    :seon.db/db dbv
    ::unit/database-coordinate (db.coordinate/resolved dbv)
    ::unit/renderer-token "oracle-v1"
    ::unit/producer producer}))

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
             (is (= {::unit/candidate? true
                     ::unit/read-replays 0
                     ::unit/producer-invocations 1
                     ::unit/serializations 1
                     ::unit/suppression
                     :seon.web.view-unit.suppression/emitted
                     ::unit/retained-output-bytes
                     (js/Buffer.byteLength
                      (::unit/serialized-element first-attach) "utf8")}
                    (::unit/unit-metrics first-attach))
                 "first activation records one complete producer boundary")
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
                      (is (= 2
                             (get-in unchanged
                                     [::unit/unit-metrics
                                      ::unit/read-replays])))
                      (is (= 0
                             (get-in unchanged
                                     [::unit/unit-metrics
                                      ::unit/producer-invocations])))
                      (is (= :seon.web.view-unit.suppression/equal-reads
                             (get-in unchanged
                                     [::unit/unit-metrics
                                      ::unit/suppression])))
                      (let [equal-output
                            (unit/transition-unit
                             {::unit/state (::unit/state unchanged)
                              ::unit/token token
                              :seon.db/db @conn
                              ::unit/database-coordinate
                              (db.coordinate/resolved @conn)
                              ::unit/renderer-token "debug-v2"
                              ::unit/producer producer})]
                        (is (true? (::unit/rendered? equal-output)))
                        (is (false? (::unit/emitted? equal-output)))
                        (is (= 1
                               (get-in equal-output
                                       [::unit/unit-metrics
                                        ::unit/producer-invocations])))
                        (is (= 1
                               (get-in equal-output
                                       [::unit/unit-metrics
                                        ::unit/serializations])))
                        (is (= :seon.web.view-unit.suppression/equal-output
                               (get-in equal-output
                                       [::unit/unit-metrics
                                        ::unit/suppression])))
                        equal-output))))
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
                            ::unit/renderer-token "debug-v2"
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
                      (is (= 1
                             (get-in changed
                                     [::unit/unit-metrics
                                      ::unit/producer-invocations])))
                      (is (= 1
                             (get-in changed
                                     [::unit/unit-metrics
                                      ::unit/serializations])))
                      (is (= 2 @replays)
                          "a changed first result does not short-circuit later reads")
                      (is (re-find #"debug value 2"
                                   (::unit/serialized-element changed)))
                      (is (= 3 @renders))
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

(deftest observed-attribute-routing-never-omits-a-replay-all-dirty-unit
  (async done
    (-> (fresh-conn)
        (.then
         (fn [conn]
           (let [unit-specs
                 [[::literal-query
                   (fn [dbv]
                     [:section (debug-value dbv)])]
                  [::exact-index
                   (fn [dbv]
                     [:section
                      (pr-str
                       (db/index-datoms
                        {:seon.db/db dbv
                         :seon.db/index :aevt
                         :seon.db/components [::value]
                         :seon.db/index-limit 32
                         :seon.db/seek? false}))])]
                  [::mixed-literals
                   (fn [dbv]
                     [:section
                      (debug-value dbv)
                      (pr-str
                       (db/index-datoms
                        {:seon.db/db dbv
                         :seon.db/index :aevt
                         :seon.db/components [::value]
                         :seon.db/index-limit 32
                         :seon.db/seek? false}))])]
                  [::dynamic-query
                   (fn [dbv]
                     [:section
                      (pr-str
                       (db/query
                        {:seon.db/db dbv
                         :seon.db/query
                         '[:find ?result
                           :in $ ?attribute
                           :where [?entity ?attribute ?result]]
                         :seon.db/args [::value]}))])]
                  [::pull
                   (fn [dbv]
                     [:section
                      (pr-str
                       (db/pull
                        {:seon.db/db dbv
                         :seon.db/pull-pattern [::value]
                         :seon.db/ref [::id "debug"]}))])]
                  [::zero-read (constantly [:section "constant"])]]
                 attach-state
                 (fn [dbv]
                   (reduce
                    (fn [{state ::unit/state tokens ::tokens}
                         [unit-name producer]]
                      (let [attached
                            (attach-oracle-unit state dbv unit-name producer)]
                        {::unit/state (::unit/state attached)
                         ::tokens
                         (assoc tokens unit-name (::unit/token attached))}))
                    {::unit/state unit/empty-state ::tokens {}}
                    unit-specs))
                 scenarios
                 (vec
                  (for [change-value? [false true]
                        change-unrelated? [false true]
                        additional? [false true]
                        :when (or change-value? change-unrelated? additional?)]
                    {::change-value? change-value?
                     ::change-unrelated? change-unrelated?
                     ::additional? additional?}))
                 provenance-attributes
                 #{:db/txInstant :seon.db/user :seon.db/process}]
             (->
              (reduce
               (fn [chain
                    [scenario-number
                     {change-value? ::change-value?
                      change-unrelated? ::change-unrelated?
                      additional? ::additional?}]]
                 (.then
                  chain
                  (fn [checked]
                    (let [before @conn
                          {initial-state ::unit/state tokens ::tokens}
                          (attach-state before)
                          value (+ 2 (* scenario-number 10))
                          unrelated (+ 3 (* scenario-number 10))
                          debug-update
                          (cond-> {::id "debug"}
                            change-value? (assoc ::value value)
                            change-unrelated? (assoc ::unrelated unrelated))
                          tx-data
                          (cond-> [debug-update]
                            additional?
                            (conj {::id (str "other-" scenario-number)
                                   ::value (+ 100 scenario-number)}))
                          domain-attributes
                          (cond-> #{}
                            change-value? (conj ::value)
                            change-unrelated? (conj ::unrelated)
                            additional? (into #{::id ::value}))
                          routing-attributes
                          (into provenance-attributes domain-attributes)]
                      (-> (db/transact!
                           {:seon.db/conn conn
                            :seon.db/tx-data tx-data})
                          (.then
                           (fn [{ok? :seon.db/ok? error :seon.db/error}]
                             (when-not ok?
                               (throw
                                (ex-info "oracle scenario transaction failed"
                                         {:seon.db/error error
                                          ::scenario-number scenario-number})))
                             (let [after @conn
                                   dirty
                                   (replay-all-dirty-tokens initial-state after)
                                   candidates
                                   (unit/candidate-tokens
                                    {::unit/state initial-state
                                     ::unit/change
                                     (complete-change routing-attributes)})
                                   narrow-tokens
                                   (set (map tokens
                                             [::literal-query
                                              ::exact-index
                                              ::mixed-literals]))]
                               (is (set/subset? dirty candidates)
                                   (str
                                    "candidate routing omitted replay-all dirty "
                                    "tokens for " (pr-str domain-attributes)
                                    ": "
                                    (pr-str (set/difference dirty candidates))))
                               (when (= #{::unrelated} domain-attributes)
                                 (is (empty?
                                      (set/intersection narrow-tokens candidates))
                                     "unrelated changes select no literal units")
                                 (is
                                  (set/subset?
                                   (set (map tokens
                                             [::dynamic-query ::pull ::zero-read]))
                                   candidates)
                                  "unproved and zero-read units remain broad"))
                               (inc checked)))))))))
               (js/Promise.resolve 0)
               (map-indexed vector scenarios))
              (.then
               (fn [checked]
                 (is (= 7 checked)
                     "the generated matrix checks every nonempty change")))))))
        (settle! done))))
