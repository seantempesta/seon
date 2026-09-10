(ns seon.loop-proof-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as async.flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.ai :as ai]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.render :as render]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- bytes-evidence [text]
  (let [encoded (.getBytes ^String text "UTF-8")]
    {:seon.test/bytes (alength encoded)
     :seon.test/sha256 (schema/sha-256 [encoded])}))

(defn- stored-text [database]
  (str/join "\n\n" (map repl/render-ai (evaluation/of-agent database "juniper"))))

(deftest running-fixture-settles-its-seeded-wake
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "running-fixture"
                    :seon.config/manifest
                    {:seon.config.run/max-episode-runs :seon.config/absent}})
     (db/transact! connection [{:seon.agent/id "root"
                               :seon.agent/namespace {:seon.ns/name 'my.agents.root}}])
     (cluster/ensure-cluster-entity! connection "running-fixture" cluster/boot-process-identity)
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "running-fixture" connection)
           routing (agent/routing)
           faults (async/chan (async/sliding-buffer 16))
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
               _ (swap! routing assoc :seon.agent/fault-channel faults)
               armer (flow/start-graph!
                      {::flow/graph-definition
                       {:procs {:seon.agent/armer
                                {:proc (flow/var-process
                                        #'agent/armer-step :io
                                        (env/carry {:seon.turn.loop/cluster handle
                                                    :seon.agent/routing routing} environment))}}
                        :conns [] :io-exec (:seon.flow/executor handle)}
                       ::flow/joins
                       {::faults #(flow/join-error-fanout!
                                  {::flow/started (::flow/started %)
                                   ::flow/fault-channel faults ::flow/tag {}})}})
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
                         :seon.cluster.wake/fault-channel faults
                         :seon.cluster.wake/key ::running-route})
           (try
             (fixture/install-running! handle routing)
             (await-settled!)
             (is (some? (agent/armed routing "juniper")))
             (is (= 19 (turn/turns-left @connection "juniper")))
             (is (seq (db/q '[:find ?turn :where [?agent :seon.agent/id "juniper"]
                              [?agent :seon.agent/runtime ?runtime]
                              [?runtime :seon.runtime/turns ?turn]
                              [?turn :seon.turn/reply ""] [?turn :seon.turn/closed-tx]] @connection)))
             (let [basis (db/basis-t @connection)]
               (db/transact! connection [{:seon.message/id "running-fixture/wake"
                                         :seon.message/to [:seon.agent/id "juniper"]
                                         :seon.message/inbox [:seon.agent/id "juniper"]
                                         :seon.message/content "Probe the running runtime component."}])
               (await-settled!)
               (is (= 19 (turn/turns-left @connection "juniper")))
               (is (some #(> (:t %) basis) (evaluation/of-agent @connection "juniper"))))
             (is (not (some? (async/poll! faults))) "the installer and both wakes are fault-free")
             (testing "a lost turn permit faults at the agent evaluation limit"
               (let [entry (agent/armed routing "juniper")
                     completion (:seon.turn.loop/completion entry)
                     settings (:db/id (:seon.agent/settings
                                       (db/pull @connection '[{:seon.agent/settings [:db/id]}]
                                                [:seon.agent/id "juniper"])))]
                 (support/await-event! completion ::idle-permit)
                 (db/transact! connection [[:db/add settings :seon.config.eval/time-limit-ms 100]])
                 (is (nil? (:seon.error/kind
                            (db/transact! connection
                                          (turn/open-tx
                                           {:seon.turn/id "running-fixture/withheld"
                                            :seon.turn/agent [:seon.agent/id "juniper"]
                                            :seon.turn/opened-tx "datomic.tx"
                                            :seon.turn.work/situation :call})))))
                 (try
                   (let [start (System/nanoTime)]
                     (async/offer! (:seon.cluster.wake/channel entry) :seon.agent/wake)
                     (let [fault (support/await-event! faults ::evaluation-limit-fault)
                           data (ex-data (::async.flow/ex fault))]
                       (is (= :seon.agent/turn-completion-backstop (:seon.error/kind data)))
                       (is (= "running-fixture/withheld" (:seon.turn/id data)))
                       (is (= 100 (:seon.config.agent/turn-completion-backstop-ms data)))
                       (is (< (/ (- (System/nanoTime) start) 1e6) 2000))))
                   (finally
                     (db/transact! connection [[:db/add settings :seon.config.eval/time-limit-ms 10000]])
                     (async/offer! completion :seon.agent/ready)))))
             (finally
               (wake/unlisten! {:seon.cluster.wake/connection connection :seon.cluster.wake/key ::running-route})
               (async.flow/stop (::flow/graph armer))
               (support/await-event! (:seon.turn.loop/completion handle) ::armer-stopped)
               (doseq [id (keys (:seon.agent/armed @routing))]
                 (agent/disarm! {:seon.agent/routing routing :seon.agent/id id}))
               (d/unlisten connection ::running-fixture)
               (doseq [channel [events faults (:seon.cluster.wake/channel handle)
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
           routing (agent/routing)
           faults (async/chan (async/sliding-buffer 16))
           _ (swap! routing assoc :seon.agent/fault-channel faults)
           ready (java.util.concurrent.CountDownLatch. 2)
           start (java.util.concurrent.CountDownLatch. 1)
           request {:seon.turn.loop/cluster handle
                    :seon.agent/routing routing :seon.agent/id "concurrent"}
           _ (db/transact! connection
                           [{:seon.agent/id "concurrent"
                             :seon.agent/namespace {:seon.ns/name 'my.agents.concurrent}}])
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
             (async.flow/stop (:seon.flow/graph entry))
             (support/await-event! (:seon.agent/turn-stopped entry) ::graph-stopped)
             (doseq [key [:seon.cluster.wake/channel :seon.schedule/channel
                          :seon.turn.loop/completion :seon.agent/turn-stopped]]
               (async/close! (get entry key))))
           (doseq [arm arms] (future-cancel arm))
           (async/close! faults)
           (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                        :seon.turn.loop/completion]]
             (async/close! (get handle key)))))))))

(deftest terminal-provider-refusal-is-durable
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.db/connection connection
                    :seon.cluster/name "loop-refusal-proof"
                    :seon.sci.eval/ctx ctx
                    :seon.db.process/id cluster/boot-process-identity})
           target (-> (:seon.ai/primary (ai/targets (support/effective-config)))
                      (dissoc :seon.config.ai/no-auth)
                      (assoc :seon.ai/api-key-variable "SEON_LOOP_PROOF_UNSET_CREDENTIAL"
                             :seon.ai/prompt "Probe terminal refusal."))]
       (try
         (assert (nil? (System/getenv "SEON_LOOP_PROOF_UNSET_CREDENTIAL")))
         (is (nil? (:seon.error/kind
                    (db/transact! connection [{:seon.agent/id "root"}]))))
         (is (nil? (:seon.error/kind
                    (db/transact! connection
                                  (turn/open-tx {:seon.turn/id "refusal-proof" :seon.turn/agent [:seon.agent/id "root"] :seon.turn/opened-tx "datomic.tx"})))))
         (let [failure (ai/complete target)
               _ (is (= :seon.ai/no-credential (:seon.error/kind failure)))
               result (turn/settle! {:seon.turn.loop/cluster handle
                                     :seon.turn.loop/now (java.util.Date.)
                                     :seon.agent/id "root"
                                     :seon.turn/id "refusal-proof"
                                     :seon.error/value failure})]
           (is (some? (:db-after (:seon.turn.loop/outcome result))) (pr-str result))
           (is (some? (:seon.turn/closed-tx
                       (db/pull @connection [:seon.turn/closed-tx]
                                [:seon.turn/id "refusal-proof"]))))
           (is (= #{[:seon.ai/no-credential]}
                  (db/q '[:find ?kind :where [?e :seon.error/id]
                           [?e :seon.error/kind ?kind]] @connection))))
         (finally
           (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                        :seon.turn.loop/completion]]
             (async/close! (get handle key)))))))))

(deftest virtual-loop-end-to-end
  (support/with-database
   (fn [connection]
     (let [runtime-read
           '(seon.db/pull
             '[{:seon.agent/runtime
                [{:seon.runtime/turns
                  [:seon.turn/id {:seon.turn/opened-tx [:db/txInstant]}
                   {:seon.turn/closed-tx [:db/txInstant]}]}
                 {:seon.runtime/trigger
                  [:seon.message/id :seon.message/content
                   {:seon.message/from [:seon.agent/id]}]}
                 {:seon.runtime/listens [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}]}]
             [:seon.agent/id "juniper"])
           turn-dependent? #(#{'(seon.agent/effective-settings) runtime-read}
                              (read-string (:seon.cluster.eval/source %)))
           configured
           (db/transact!
            connection
            [(:seon.config/desired-row
              (config/compile-manifest
               {:seon.boot/cluster-name "loop-proof"
                :seon.config/manifest {:seon.config.ai/no-provider true}}))
             {:seon.agent/id "other"
              :seon.agent/namespace {:seon.ns/name 'my.agents.other}}
             {:seon.agent/id "unobserved"
              :seon.agent/namespace {:seon.ns/name 'my.agents.unobserved}}])
           _ (is (nil? (:seon.error/kind configured)) (pr-str configured))
           _ (cluster/ensure-cluster-entity! connection "loop-proof" cluster/boot-process-identity)
           ctx (support/fork-cluster-ctx connection)
           environment (support/environment "loop-proof" connection)
           routing (agent/routing)
           transactions (atom [])
           faults (async/chan (async/sliding-buffer 16))
           events (async/chan (async/sliding-buffer 1))]
       (with-open [launcher-resource
                   (support/closeable
                    (flow/start-work-launcher!
                     {:seon.env/environment environment
                      :seon.flow/configuration
                      (select-keys (support/effective-config)
                                   flow/flow-workload-attributes)})
                    flow/stop-work-launcher!)]
         (let [handle (support/cluster-handle
                       {:seon.env/environment environment
                        :seon.db/connection connection
                        :seon.cluster/name "loop-proof"
                        :seon.flow/work-launcher @launcher-resource
                        :seon.flow/executor
                        (cluster/projection-executor
                         (:seon.sci.eval/projection-state ctx))
                        :seon.sci.eval/ctx ctx
                        :seon.db.process/id cluster/boot-process-identity})
               request {:seon.turn.loop/cluster handle
                        :seon.agent/id "juniper"
                        :seon.turn/write? true}
               prompt #(render/acquire-context!
                        (merge handle
                               {:seon.db/db @connection
                                :seon.turn/id
                                (:seon.turn/id
                                 (db/pull @connection [:seon.turn/id]
                                          (get-in (last (evaluation/of-agent @connection "juniper"))
                                                  [:seon.cluster.eval/run :db/id])))
                                :seon.agent/id "juniper"
                                :seon.sci.eval/time-limit-ms
                                (:seon.config.eval/time-limit-ms handle)}))
               submit
               (fn [source]
                 (let [result (turn/virtual-turn!
                               (assoc request
                                      :seon.agent/routing routing
                                      :seon.cluster.reply/text source))
                       id (:seon.turn/id result)
                       closed? #(and id
                                     (:seon.turn/closed-tx
                                      (db/pull @connection [:seon.turn/closed-tx]
                                               [:seon.turn/id id])))]
                   (is (string? id) (pr-str result))
                   (when (and id (not (closed?)))
                     (try
                       (support/await-event! events ::closed (fn [_] (closed?)))
                       (catch Throwable failure
                         (let [turn-row (db/pull @connection '[*] [:seon.turn/id id])]
                           (prn {:seon.test/unfinished-turn turn-row
                                 :seon.test/evaluations
                                 (mapv #(select-keys % [:seon.cluster.eval/source
                                                       :seon.cluster.eval/error
                                                       :seon.cluster.eval/ordinal
                                                       :seon.eval/shown])
                                       (filter #(= (:db/id turn-row)
                                                   (get-in % [:seon.cluster.eval/run :db/id]))
                                               (evaluation/of-agent @connection "juniper")))}))
                         (when-let [fault (async/poll! faults)]
                           (prn (update-vals fault
                                            #(if (instance? Throwable %)
                                               {:seon.error/message (ex-message %)
                                                :seon.error/data (ex-data %)} %))))
                         (throw failure))))
                   (is (boolean (closed?)))
                   id))]
           (swap! routing assoc :seon.agent/fault-channel faults)
           (d/listen connection ::proof
                     (fn [report]
                       (swap! transactions conj report)
                       (async/offer! events true)))
           (try
             (testing "root boot stores the current system opening once"
               (let [created (cluster/ensure-entity!
                              connection cluster/boot-process-identity
                              {:seon.agent/id "root" :seon.cluster/name "loop-proof"
                               :seon.ns/name 'my.agents.root})
                     bootstrap-id (:seon.turn/id created)
                     root-request {:seon.turn.loop/cluster handle
                                   :seon.agent/routing routing :seon.agent/id "root"}]
                 (is (string? bootstrap-id) (pr-str created))
                 (agent/arm! root-request)
                 (support/await-event!
                  events ::root-opening
                  (fn [_] (:seon.turn/closed-tx
                           (db/pull @connection [:seon.turn/closed-tx]
                                    [:seon.turn/id bootstrap-id]))))
                 (agent/disarm! root-request)
                 (let [saved (evaluation/of-agent @connection "root")
                       sources (mapv :seon.cluster.eval/source saved)
                       planned (:seon.turn/forms
                                (turn/system-turn (assoc root-request :seon.turn/write? false)))]
                   (is (seq saved))
                   (is (empty? (keep :seon.cluster.eval/error saved)))
                   (is (= sources (mapv :seon.cluster.eval/source planned)))
                   (is (= sources (vec (distinct sources))))
                   (is (= "(help)" (first sources)))
                   (is (some #{"(seon.cluster.status/snapshot {})"} sources))
                   (is (str/starts-with? (:seon.eval/shown (first saved))
                                        "The prompt shows your namespace my.agents.root")))))
             (testing "agent creation uses the same retained-read opening"
               (let [created (cluster/ensure-entity!
                              connection cluster/boot-process-identity
                              {:seon.agent/id "juniper" :seon.cluster/name "loop-proof"
                               :seon.ns/name 'my.agents.juniper})
                     bootstrap-id (:seon.turn/id created)
                     closed? #(some? (:seon.turn/closed-tx
                                      (db/pull @connection [:seon.turn/closed-tx]
                                               [:seon.turn/id bootstrap-id])))]
                 (is (string? bootstrap-id) (pr-str created))
                 (agent/arm! {:seon.turn.loop/cluster handle
                              :seon.agent/routing routing :seon.agent/id "juniper"})
                 (support/await-event! events ::seeded-opening (fn [_] (closed?)))
                 (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                 (let [saved (evaluation/of-agent @connection "juniper")
                       refresh (turn/system-turn request)]
                   (println {:seon.test/stage :creation
                             :seon.test/reads (mapv (fn [entry]
                                                    [(:seon.cluster.eval/source entry)
                                                     (count (:seon.cluster.eval/read-evidence entry))]) saved)})
                   (is (seq saved))
                   (let [declared (#'turn/declared-sources
                                   handle @connection "juniper" 'my.agents.juniper)]
                     (is (= (mapv :seon.cluster.eval/source saved)
                            (mapv :seon.cluster.eval/source
                                  (#'turn/system-plan @connection (:seon.turn/forms declared) {})))
                         "creation stores the generator's exact source, including reader quotes"))
                   (is (every? (comp seq :seon.cluster.eval/read-evidence)
                               (remove #(str/starts-with? (:seon.cluster.eval/source %) "(dir ") saved)))
                   (is (= ['(seon.agent/effective-settings) runtime-read]
                          (mapv (comp read-string :seon.cluster.eval/source)
                                (remove #(= :unchanged (:seon.turn/status %))
                                        (:seon.turn/forms refresh))))
                       "closing the creation turn changes the derived turn count"))))
             (fixture/install! handle routing)
             (testing "fresh opening and stable stored prompt"
               (let [first-id (turn/next-id @connection "loop-proof" "juniper")
                     opening (turn/system-turn request)
                     saved (evaluation/of-agent @connection "juniper")
                     text (stored-text @connection)
                     first-prompt (prompt)
                     second-prompt (prompt)]
                 (is (string? (:seon.turn/id opening)) (pr-str (keys opening)))
                 (is (= first-id (:seon.turn/id opening)))
                 (is (= "(help)" (:seon.cluster.eval/source (first saved))))
                 (is (= (mapv :seon.cluster.eval/source (:seon.turn/forms opening))
                        (mapv :seon.cluster.eval/source saved)))
                 (is (= (count saved) (count (distinct (map :seon.cluster.eval/source saved)))))
                 ;; Acquired doc/dir macros return quoted program data; their missing
                 ;; refresh evidence is recorded separately from database reads.
                 (is (every? (comp seq :seon.cluster.eval/read-evidence)
                             (remove #(str/starts-with? (:seon.cluster.eval/source %) "(dir ") saved))
                     "every seeded database read and help store dependency evidence")
                 (is (= 4 (count (db/q '[:find [?key ...] :in $ ?name :where
                                           [?n :seon.ns/name ?name]
                                           [?s :seon.schema/ns ?n]
                                           [?s :seon.schema/key ?key]] @connection 'my.agents.juniper))))
                 (is (some #(= 'seon.db/q (first (read-string (:seon.cluster.eval/source %)))) saved))
                 (is (= [["Ada" 115] ["Bea" 100] ["Cy" 40]]
                        (sort (db/q '[:find ?customer (sum ?amount)
                                :where [?order :example/customer ?customer]
                                       [?order :example/amount ?amount]] @connection))))
                 (is (= [fixture/instruction]
                        (db/q '[:find [?content ...] :where
                                [?agent :seon.agent/id "juniper"]
                                [?message :seon.message/to ?agent]
                                [?message :seon.message/content ?content]] @connection)))
                 (is (every? :seon.eval/shown saved))
                 (is (not-any? :seon.cluster.eval/error saved))
                 (is (= 1 (count (set (map :seon.cluster.eval/run saved)))))
                 (is (= text (stored-text @connection)))
                 (is (= (:seon.cluster.prompt/text first-prompt)
                        (:seon.cluster.prompt/text second-prompt)))
                 (is (= (bytes-evidence text)
                        (bytes-evidence (:seon.cluster.prompt/text first-prompt)))
                     "the provider prompt consists exactly of stored evaluations")
                 (println {:seon.test/stage :opening
                           :seon.test/evaluations (count saved)
                           :seon.test/stored (bytes-evidence text)
                           :seon.test/prompt
                           (bytes-evidence (:seon.cluster.prompt/text first-prompt))})
                 (let [basis (db/basis-t @connection)
                       refreshed (turn/system-turn request)]
                   (is (= ['(seon.agent/effective-settings) runtime-read]
                          (mapv (comp read-string :seon.cluster.eval/source)
                                (remove #(= :unchanged (:seon.turn/status %))
                                        (:seon.turn/forms refreshed))))
                       "settings and runtime observe turns; the other opening reads stay unchanged")
                   (is (string? (:seon.turn/id refreshed)))
                   (is (= (inc basis) (db/basis-t @connection))))
                 (testing "the first ordinary wake retains the seeded opening once"
                   (agent/arm! {:seon.turn.loop/cluster handle
                                :seon.agent/routing routing :seon.agent/id "juniper"})
                   (fixture/submit! handle routing "(my.agent/done)")
                   (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                   (let [after (evaluation/of-agent @connection "juniper")
                         occurrences (frequencies (map :seon.cluster.eval/source after))]
                     (doseq [entry (remove turn-dependent? saved)]
                       (is (= 1 (get occurrences (:seon.cluster.eval/source entry)))
                           (pr-str occurrences)))
                     (is (str/starts-with? (stored-text @connection) text))))
                 (turn/compact! {:seon.db/connection connection
                                 :seon.agent/id "juniper"})
                 (is (empty? (evaluation/of-agent @connection "juniper")))
                 (let [regenerated (turn/system-turn request)
                       after (stored-text @connection)]
                   (is (string? (:seon.turn/id regenerated)))
                   (is (= (mapv :seon.cluster.eval/source saved)
                          (mapv :seon.cluster.eval/source
                                (evaluation/of-agent @connection "juniper"))))
                   (is (= (mapv :seon.eval/shown (remove turn-dependent? saved))
                          (mapv :seon.eval/shown
                                (remove turn-dependent?
                                        (evaluation/of-agent @connection "juniper"))))
                       "compaction rereads current runtime facts; other observations stay equal")
                   (is (= after (stored-text @connection))
                       "saved bytes remain exact within the new generation")
                   (is (= after (:seon.cluster.prompt/text (prompt))))
                   (println {:seon.test/stage :compact
                             :seon.test/stored (bytes-evidence after)}))))
             (agent/arm! {:seon.turn.loop/cluster handle
                          :seon.agent/routing routing
                          :seon.agent/id "juniper"})
             (testing "three-form reply, actual handles, and additive history"
               (let [prefix (stored-text @connection)
                     _ (reset! transactions [])
                     id (submit "(+ 1 1)\n(+ 2 2)\n(+ 3 3)")
                     reports @transactions
                     saved (take-last 3 (evaluation/of-agent @connection "juniper"))
                     agent-ctx (get-in (agent/armed routing "juniper")
                                       [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx])]
                 (is (= 3 (count saved)))
                 (is (= ["2" "4" "6"] (mapv :seon.eval/shown saved)))
                 (doseq [[entry expected] (map vector saved [2 4 6])]
                   (let [handle-symbol (:seon.repl/handle (repl/entity-emission entry))]
                     (is (= expected (some-> (sci/resolve agent-ctx handle-symbol) deref)))
                     (is (nil? (sci/resolve ctx handle-symbol)))))
                 (is (str/starts-with? (stored-text @connection) prefix))
                 (is (= (stored-text @connection)
                        (:seon.cluster.prompt/text (prompt))))
                 (println {:seon.test/stage :virtual
                           :seon.test/turn id
                           :seon.test/transactions (count reports)
                           :seon.test/datoms (mapv #(count (:tx-data %)) reports)
                           :seon.test/stored (bytes-evidence (stored-text @connection))})))
             (submit "(my.message/inbox)")
             (agent/disarm! {:seon.agent/routing routing
                             :seon.agent/id "juniper"})
             (testing "changed reads answer only observed wakes"
               (let [prefix (stored-text @connection)
                     message (db/transact!
                              connection
                              [{:seon.message/id "proof-wake" :seon.message/to [:seon.agent/id "juniper"] :seon.message/content "Read the changed message." :seon.message/inbox [:seon.agent/id "juniper"]}])
                     wake-t (db/basis-t (:db-after message))
                     pending (turn/unanswered-wakes @connection "juniper" {})]
                 (is (seq pending))
                 (let [system (turn/system-turn request)
                       changed (filter #(= :changed (:seon.turn/status %))
                                       (:seon.turn/forms system))]
                   (is (some #{"(my.message/inbox)"}
                             (map :seon.cluster.eval/source changed)))
                   (is (= #{runtime-read '(my.message/inbox) '(seon.agent/effective-settings)
                            '(seon.db/pull '[{:seon.message/_inbox
                                             [:seon.message/id :seon.message/content
                                              {:seon.message/from [:seon.agent/id]}]}]
                                           [:seon.agent/id "juniper"])}
                          (set (map (comp read-string :seon.cluster.eval/source) changed))))
                   (is (str/starts-with? (stored-text @connection) prefix))
                   (is (= (stored-text @connection)
                          (:seon.cluster.prompt/text (prompt))))
                   (is (= pending (turn/unanswered-wakes @connection "juniper" {})))
                   (is (< (turn/latest-answering-turn-t @connection "juniper") wake-t)
                       "a system-only read refresh does not answer the wake")
                   (println {:seon.test/stage :wake
                             :seon.test/changed (count changed)
                             :seon.test/wake-t wake-t
                             :seon.test/answer-t
                             (turn/latest-answering-turn-t @connection "juniper")}))))
             (testing "the ordinary wake path refreshes reads before its reply"
               (let [_ (is (nil? (:seon.error/kind
                                 (config/apply! {:seon.db/connection connection
                                                 :seon.boot/cluster-name "loop-proof"
                                                 :seon.config/manifest
                                                 {:seon.config.ai/no-provider :seon.config/absent}}))))
                     _ (is (nil? (:seon.config.ai/no-provider
                                  (config/effective @connection "loop-proof"))))
                     _ (is (true? (:seon.config.ai/no-provider
                                   (ai/agent-overlay @connection "juniper"))))
                     faults-before (set (db/q '[:find [?e ...] :where [?e :seon.error/id]] @connection))
                     written (db/transact!
                              connection
                              [{:seon.message/id "proof-wake-2" :seon.message/to [:seon.agent/id "juniper"] :seon.message/content "A second changed message." :seon.message/inbox [:seon.agent/id "juniper"]}])
                     wake-t (db/basis-t (:db-after written))
                     closed? #(seq (db/q '[:find ?turn :in $ ?since
                                           :where [?agent :seon.agent/id "juniper"]
                                           [?turn :seon.turn/agent ?agent]
                                           [?turn :seon.turn/id _ ?t]
                                           [(>= ?t ?since)]
                                           [?turn :seon.turn/reply ""]
                                           [?turn :seon.turn/closed-tx]]
                                         @connection wake-t))]
                 (agent/arm! {:seon.turn.loop/cluster handle
                              :seon.agent/routing routing
                              :seon.agent/id "juniper"})
                 (when-not (closed?)
                   (support/await-event! events ::wake-turn-closed (fn [_] (closed?))))
                 (agent/disarm! {:seon.agent/routing routing
                                 :seon.agent/id "juniper"})
                 (let [fresh (filter #(>= (:t %) wake-t)
                                     (evaluation/of-agent @connection "juniper"))]
                   (is (seq fresh))
                   (is (= 'seon.db/pull (first (read-string (:seon.cluster.eval/source (first fresh)))))
                       "changed read must precede the no-provider reply")
                   (is (= #{runtime-read '(my.message/inbox) '(seon.agent/effective-settings)
                             '(seon.db/pull '[{:seon.message/_inbox
                                              [:seon.message/id :seon.message/content
                                               {:seon.message/from [:seon.agent/id]}]}]
                                            [:seon.agent/id "juniper"])}
                          (set (map (comp read-string :seon.cluster.eval/source) fresh)))
                       "no-provider turns do not invent placeholder forms")
                   (is (empty? (turn/unanswered-wakes @connection "juniper" {})))
                   (is (= faults-before
                          (set (db/q '[:find [?e ...] :where [?e :seon.error/id]] @connection)))
                       "a no-provider reply closes without recording a refusal")
                   (is (= 19 (turn/turns-left @connection "juniper")))
                   (println {:seon.test/stage :ordinary-wake
                             :seon.test/sources (mapv :seon.cluster.eval/source fresh)}))))
             (testing "root's generated query executes without caller aliases"
               (let [transaction (bootstrap/supervision-tx
                                  @connection cluster/boot-process-identity
                                  "other")
                     query-source (some #(when (and (map? %)
                                                    (str/includes?
                                                     (:seon.cluster.eval/source % "")
                                                     "(seon.db/q"))
                                           (:seon.cluster.eval/source %))
                                        (tree-seq coll? seq transaction))]
                 (is (string? query-source))
                 (when query-source
                   (let [preview (turn/preview-sources
                                  {:seon.turn.loop/cluster handle
                                   :seon.db/db @connection
                                   :seon.sci.eval/ctx ctx
                                   :seon.agent/id "root"
                                   :seon.ns/name 'my.agents.root
                                   :seon.cluster.reply/text query-source
                                   :seon.sci.admit/caps (:seon.sci.admit/caps handle)})
                         result (get-in preview [:seon.turn.loop/evaluated-sources 0
                                                 :seon.sci.eval/evaluation])]
                     (is (some? result) (pr-str preview))
                     (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
                     (is (nil? (:seon.error/kind result)) (pr-str result))))))
             (testing "boot closes durable intent and never reexecutes it"
               (let [id (turn/next-id @connection "loop-proof" "other")
                     source "(seon.db/transact! [{:seon.agent/id \"must-not-execute\"}])"
                     sources (turn/planned-sources source 'my.agents.other 10000)]
                 (db/transact!
                  connection
                  (turn/system-run-tx
                   @connection
                   {:seon.agent/id "other" :seon.turn/id id :seon.turn/opened-tx "datomic.tx" :seon.turn/starting-ns [:seon.ns/name 'my.agents.other] :seon.turn/reply source :seon.turn/sources sources}))
                 (is (= 1 (:seon.boot/recovered-runs (#'cluster/recover-runs! connection))))
                 (let [saved (evaluation/of-agent @connection "other")
                       basis (db/basis-t @connection)]
                   (is (= 1 (count saved)))
                   (is (every? :seon.cluster.eval/interrupted-at saved))
                   (is (some? (:seon.turn/closed-tx
                               (db/pull @connection [:seon.turn/closed-tx] [:seon.turn/id id]))))
                   (is (nil? (turn/next-agent-work @connection {:seon.agent/id "other"})))
                   (is (nil? (db/q '[:find ?e . :where
                                     [?e :seon.agent/id "must-not-execute"]] @connection)))
                   (is (= 0 (:seon.boot/recovered-runs (#'cluster/recover-runs! connection))))
                   (is (= basis (db/basis-t @connection))))))
             (testing "a system-only turn without the wake's results answers nothing"
               (db/transact!
                connection
                [{:seon.message/id "unobserved-wake" :seon.message/to [:seon.agent/id "unobserved"] :seon.message/content "This agent has no inbox read." :seon.message/inbox [:seon.agent/id "unobserved"]}])
               (let [pending (turn/unanswered-wakes @connection "unobserved" {})
                     system (turn/system-turn
                             (assoc request :seon.agent/id "unobserved"))]
                 (is (= 1 (count pending)))
                 (is (string? (:seon.turn/id system)) (pr-str system))
                 (is (= pending (turn/unanswered-wakes @connection "unobserved" {})))))
             (testing "the scenario crosses the real query, write, message, and session boundaries"
               (agent/arm! {:seon.turn.loop/cluster handle
                           :seon.agent/routing routing :seon.agent/id "juniper"})
               (testing "the exact saved trial query evaluates before its fabricated-response error"
                 (let [trial (edn/read-string
                              (slurp "docs/prds/context-generation/research/help_trial_2026_09_09.edn"))
                       reply-text (get-in trial [:seon.trial/completion :seon.ai/text])
                       _ (is (string? reply-text))
                       parsed (turn/planned-sources reply-text 'my.agents.juniper (count reply-text))
                       _ (is (= 2 (count parsed)))
                       _ (is (= "(seon.db/q '[:find ?e ?attr ?v :where [?e ?attr ?v]])"
                                (:seon.cluster.eval/source (first parsed))))
                       id (submit reply-text)
                       turn-eid (:db/id (db/pull @connection [:db/id] [:seon.turn/id id]))
                       saved (filterv #(= turn-eid (get-in % [:seon.cluster.eval/run :db/id]))
                                      (evaluation/of-agent @connection "juniper"))]
                   (is (= 2 (count saved)))
                   (is (str/starts-with? (:seon.cluster.eval/source (first saved)) "(seon.db/q"))
                   (is (nil? (:seon.cluster.eval/error (first saved))))
                   (is (seq (:seon.eval/shown (first saved))))
                   (is (seq (:seon.cluster.eval/read-evidence (first saved))))
                   (is (str/includes? (:seon.cluster.eval/error (second saved))
                                      "You wrote a response. Only the REPL writes responses; send forms and wait."))
                   (is (str/starts-with? (:seon.cluster.eval/source (second saved)) "#:seon.repl"))))
               (submit "(seon.db/q '[:find ?customer (sum ?amount) :where [?order :example/customer ?customer] [?order :example/amount ?amount]])")
               (is (str/includes? (:seon.eval/shown (last (evaluation/of-agent @connection "juniper"))) "115"))
               (submit "(seon.db/transact! [{:example/order \"a3\" :example/customer \"Ada\" :example/amount 40}])")
               (submit "(seon.db/q '[:find (sum ?amount) . :where [?order :example/customer \"Ada\"] [?order :example/amount ?amount]])")
               (is (= "155" (:seon.eval/shown (last (evaluation/of-agent @connection "juniper")))))
               (submit "(my.message/send {:my.message/to \"root\" :my.message/content \"Ada had the largest total, 115. I added an order of 40 and verified the new total is 155.\"})")
               (is (= 1 (db/q '[:find (count ?message) . :where
                                [?agent :seon.agent/id "juniper"]
                                [?message :seon.message/from ?agent]
                                [?message :seon.message/content ?content]
                                [(clojure.string/includes? ?content "155")]] @connection)))
               (submit "(clojure.test/deftest order-total (clojure.test/is (= 155 (seon.db/q '[:find (sum ?amount) . :where [?order :example/customer \"Ada\"] [?order :example/amount ?amount]]))))")
               (submit "(my.test/run)")
               (is (= [1 0 0]
                      (let [result (db/pull @connection
                                            [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count]
                                            [:seon.test/sym "my.agents.juniper/order-total"])]
                        (mapv result [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count]))))
               (submit "(my.agent/done)\n(seon.db/transact! [{:example/order \"after-done\" :example/customer \"Ada\" :example/amount 999}])")
               (is (nil? (db/q '[:find ?order . :where [?order :example/order "after-done"]] @connection)))
               (is (nil? (turn/next-agent-work @connection {:seon.agent/id "juniper"}))))
             (is (empty? (db/q '[:find [?attempt ...]
                                 :where [?attempt :seon.ai.attempt/id]] @connection)))
             (finally
               (agent/disarm! {:seon.agent/routing routing
                               :seon.agent/id "juniper"})
               (d/unlisten connection ::proof)
               (doseq [channel [events faults
                                (:seon.cluster.wake/channel handle)
                                (:seon.render/context-channel handle)
                                (:seon.turn.loop/completion handle)]]
                 (async/close! channel))))))))))
