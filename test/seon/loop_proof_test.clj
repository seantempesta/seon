(ns seon.loop-proof-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as async.flow]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.schema :as schema]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest ^{:seon.test/long "Starts a work launcher and armer, installs the order scenario through a real turn, then settles two wakes and a withheld-permit fault; 10.1 s measured in the check JVM at 525d62694."
           :seon.test/long-ms 20000}
  running-fixture-settles-its-seeded-wake
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "running-fixture"
                    :seon.config/manifest
                    ;; a core fault is stored and delivered, and the graph
                    ;; that raised it keeps running for the rest of the scenario
                    {:seon.config.run/max-episode-runs :seon.config/absent}})
     (support/transacted! connection (support/agent-tx @connection "root" 'my.agents.root))
     (cluster/ensure-cluster-entity! connection "running-fixture" cluster/boot-process-identity)
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "running-fixture" connection)
           routing (support/attempt-routing connection)
           occurrences #(set (db/q '[:find [?o ...] :where [?o :seon.error.occurrence/id]] (db/db connection)))
           occurrences-before (occurrences)
           events (async/chan (async/sliding-buffer 1))]
       (with-open [launcher (support/closeable
                             (flow/start-work-launcher!
                              {:seon.env/environment environment
                               :seon.flow/configuration
                               (select-keys (support/effective-config) flow/flow-workload-attributes)})
                             flow/stop-work-launcher!)]
         (let [handle (support/cluster-handle
                       {:seon.env/environment environment
                        :seon.db/connection connection :seon.cluster/name "running-fixture"
                        :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                        :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                        :seon.db.process/id cluster/boot-process-identity})
               armer (flow/start-graph!
                      {::flow/graph-definition
                       {:procs {:seon.agent/armer
                                {:proc (flow/var-process
                                        #'agent/armer-step :io
                                        (env/carry {:seon.turn.loop/cluster handle
                                                    :seon.agent/routing routing} environment))}}
                        :conns [] :io-exec (:seon.flow/executor handle)}})
               settled? #(and (seq (evaluation/of-agent @connection "juniper"))
                               (nil? (turn/open-for-agent @connection [:seon.agent/id "juniper"]))
                               (empty? (turn/unanswered-wakes @connection "juniper" {})))
               await-settled! (fn []
                                (let [deadline (async/timeout 10000)]
                                  (loop []
                                    (when-not (settled?)
                                      (when (= deadline (second (async/alts!! [deadline events] :priority true)))
                                        (throw (ex-info "Fixture wake did not settle within its evaluation limit"
                                                        {:seon.probe/armed (keys (:seon.agent/armed @routing))
                                                         :seon.probe/next (turn/next-agent-work @connection {:seon.agent/id "juniper"})})))
                                      (recur)))))]
           (d/listen connection ::running-fixture (fn [_] (async/offer! events true)))
           (wake/route! {:seon.cluster.wake/connection connection
                         :seon.cluster.wake/channels #(agent/channels routing)
                         :seon.cluster.wake/fenced? #(agent/fenced-route? routing %1 %2)
                         :seon.cluster.wake/armer-channel (:seon.cluster.wake/channel handle)
                         :seon.cluster.wake/render-channel (:seon.render/context-channel handle)
                         :seon.render.web/interest (atom :all)
                         :seon.cluster.wake/key ::running-route})
           (try
             (do
               (let [population
                     (fn [] (set (keys (get-in (schema/projection-from-database
                                                (db/db connection))
                                               [:seon.schema.projection/forms]))))
                     declared-population (population)
                     installed (fixture/install-running! handle routing)
                     database (db/db connection)]
                 ;; The scenario's shapes are FACTS, not a registry leak: the
                 ;; agent declares them in an ordinary turn, the writer commits
                 ;; one :seon.schema/key entity each, and the cluster's population
                 ;; DERIVED AT ITS AUTHORITY is its declared population plus
                 ;; EXACTLY those keys — asserted by key, because a count
                 ;; answers "fine" for a swap.
                 (is (empty? (set/intersection declared-population
                                               fixture/schema-keys))
                     "The scenario's keys must not already be declared.")
                 (is (= (set/union declared-population fixture/schema-keys)
                        (population)))
                 (is (= (vec (sort fixture/schema-keys))
                        (:seon.schema/keys installed)))
                 (is (= fixture/schema-keys
                        (into #{}
                              (filter #(string?
                                        (:seon.schema/form
                                         (db/pull database [:seon.schema/form]
                                                  [:seon.schema/key %]))))
                              fixture/schema-keys))
                     "Every scenario key is backed by its own durable declaration entity.")
                 ;; THE LIVE PROJECTION IS NOT A SEPARATE OPINION. After a
                 ;; declaration commit the context carries exactly the
                 ;; population derived at that basis. Comparing the WRITER's
                 ;; canonical printing of a storable form with the reader's
                 ;; printing dropped `:example/order` and `:example/order-row`
                 ;; here while their entities were committed facts, and the
                 ;; runner's drift detector then read their later re-derivation
                 ;; as keys a test had added (2026-09-17). By key, both
                 ;; directions: a count answers "fine" for a swap.
                 (let [carried (set (keys (get-in (env/of ctx)
                                                  [:seon.schema/projection
                                                   :seon.schema.projection/forms])))]
                   (is (empty? (set/difference (population) carried))
                       "every declaration the transaction committed is carried")
                   (is (empty? (set/difference carried (population)))
                       "the carried projection declares nothing the database does not"))))
             (await-settled!)
             (is (some? (agent/armed routing "juniper")))
             (is (= 29 (turn/turns-left @connection "juniper")))
             (let [basis (db/basis-t @connection)]
               (support/transacted! connection [{:seon.message/id "running-fixture/wake"
                                                :seon.message/to [:seon.agent/id "juniper"]
                                                :seon.message/content "Probe the running runtime component."}])
               (await-settled!)
               (is (= 29 (turn/turns-left @connection "juniper")))
               (is (some #(> (:t %) basis) (evaluation/of-agent @connection "juniper"))))
             (let [recorded (set/difference (occurrences) occurrences-before)]
               ;; The faults themselves are the failing value the report prints.
               (is (= [] (mapv #(db/pull (db/db connection)
                                         [:seon.error.occurrence/message
                                          :seon.error/producer
                                          {:seon.error.occurrence/turn [:seon.turn/id]}
                                          {:seon.error/_occurrences
                                           [:seon.error/layer :seon.error/operation :seon.error/frame]}]
                                         %)
                               recorded))))
             (testing "a lost turn permit faults at the declared completion allowance"
               (let [entry (agent/armed routing "juniper")
                     completion (:seon.turn.loop/completion entry)
                     settings (:db/id (:seon.agent/settings
                                       (db/pull @connection '[{:seon.agent/settings [:db/id]}]
                                                [:seon.agent/id "juniper"])))]
                 (support/await-event! completion ::idle-permit)
                 (support/transacted! connection [[:db/add settings :seon.config.agent/turn-completion-backstop-ms 100]])
                 (is (some? (:db-after (db/transact! connection
                                          (turn/open-tx
                                           {:seon.turn/id "running-fixture/withheld"
                                            :seon.turn/agent [:seon.agent/id "juniper"]
                                            :seon.turn/opened-tx "datomic.tx"
                                            :seon.turn.work/situation :call})))))
                 (try
                   (let [start (System/nanoTime)]
                     (async/offer! (:seon.cluster.wake/channel entry) :seon.agent/wake)
                     ;; the proc's throw is recorded by its fault port: one
                     ;; stored occurrence naming the run and the allowance
                     (let [message "Agent \"juniper\" run \"running-fixture/withheld\" did not publish turn permit within 100 ms."
                           stored #(seq (db/q '[:find [?o ...] :in $ ?m
                                                :where [?o :seon.error.occurrence/message ?m]]
                                              % message))]
                       (support/await-event! connection ::evaluation-limit-fault stored)
                       (is (= 1 (count (stored (db/db connection)))))
                       (is (< (/ (- (System/nanoTime) start) 1e6) 2000))))
                   (finally
                     (support/transacted! connection [[:db/add settings :seon.config.agent/turn-completion-backstop-ms 600000]])
                     (async/offer! completion :seon.agent/ready)))))
             (finally
               (wake/unlisten! {:seon.cluster.wake/connection connection :seon.cluster.wake/key ::running-route})
               (async.flow/stop (::flow/graph armer))
               ;; the armer's exit is its executor's termination, not a
               ;; channel event (seon.flow/join-graph!)
               (is (nil? (flow/join-graph!
                          {::flow/executor (::flow/executor armer)
                           :seon.await/bound
                           {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
                            :seon.await/config-value 5000
                            :seon.await/deadline-nanos (+ (System/nanoTime) (* 5000 1000000))}
                           :seon.await/diagnostic {:seon.error/layer ::running-fixture
                                                   :seon.error/operation `running-fixture-settles-its-seeded-wake
                                                   :seon.error/expected ::armer-exited}})))
               (doseq [id (keys (:seon.agent/armed @routing))]
                 (agent/disarm! {:seon.agent/routing routing :seon.agent/id id}))
               (d/unlisten connection ::running-fixture)
               (doseq [channel [events (:seon.cluster.wake/channel handle)
                                (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
                 (async/close! channel))))))))))

(deftest concurrent-arms-share-one-graph
  (support/with-database
   (fn [connection]
     (let [_ (config/apply! {:seon.db/connection connection
                            :seon.boot/cluster-name "loop-arm-proof"})
           ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.env/environment (support/environment "loop-arm-proof" connection)
                    :seon.db/connection connection
                    :seon.cluster/name "loop-arm-proof"
                    :seon.sci.eval/ctx ctx
                    :seon.flow/executor
                    (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                    :seon.db.process/id cluster/boot-process-identity})
           routing (support/attempt-routing connection)
           ready (java.util.concurrent.CountDownLatch. 2)
           start (java.util.concurrent.CountDownLatch. 1)
           request {:seon.turn.loop/cluster handle
                    :seon.agent/routing routing :seon.agent/id "concurrent"}
           _ (support/transacted! connection
                                  (agent/creation-tx {:seon.agent/id "concurrent"
                                                     :seon.ns/name 'my.agents.concurrent
                                                     :seon.cluster/name "loop-arm-proof"
                                                     :seon.agent/branch (get-in (db/db connection) [:config :branch])}))
           arms (mapv (fn [_]
                        (future
                          (.countDown ready)
                          (support/await-event! start ::start-arms)
                          (agent/arm! request)))
                      (range 2))
           entries (atom [])]
       (try
         (support/await-event! ready ::arms-ready)
         (.countDown start)
         (doseq [arm arms]
           (swap! entries conj (support/await-event! arm ::armed)))
         (let [same? (identical? (first @entries) (second @entries))
               routed? (every? #(identical? % (agent/armed routing "concurrent")) @entries)]
           (is same? "the live armer and source installer must acquire the same graph")
           (is routed? "every caller receives the published routing entry"))
         (finally
           (.countDown start)
           (doseq [entry (distinct @entries)]
             (agent/disarm! {:seon.agent/id "concurrent" :seon.agent/routing routing})
             (async/close! (:seon.schedule/channel entry)))
           (doseq [arm arms] (future-cancel arm))
           (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                        :seon.turn.loop/completion]]
             (async/close! (get handle key)))))))))
