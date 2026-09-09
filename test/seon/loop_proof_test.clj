(ns seon.loop-proof-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
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

(deftest virtual-loop-end-to-end
  (support/with-database
   (fn [connection]
     (let [configured
           (db/transact!
            connection
            [{:seon.cluster/name "loop-proof"}
             (:seon.config/desired-row
              (config/compile-manifest
               {:seon.boot/cluster-name "loop-proof"
                :seon.config/manifest {:seon.config.ai/no-provider true}}))
             {:seon.agent/id "other"
              :seon.agent/namespace {:seon.ns/name 'my.agents.other}}
             {:seon.agent/id "unobserved"
              :seon.agent/namespace {:seon.ns/name 'my.agents.unobserved}}
             {:seon.agent/id "root"
              :seon.agent/namespace {:seon.ns/name 'my.agents.root}}])
           _ (is (nil? (:seon.error/kind configured)))
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
                                     (:seon.turn/closed-at
                                      (db/pull @connection [:seon.turn/closed-at]
                                               [:seon.turn/id id])))]
                   (is (string? id) (pr-str result))
                   (when (and id (not (closed?)))
                     (support/await-event! events ::closed (fn [_] (closed?))))
                   (is (boolean (closed?)))
                   id))]
           (swap! routing assoc :seon.agent/fault-channel faults)
           (d/listen connection ::proof
                     (fn [report]
                       (swap! transactions conj report)
                       (async/offer! events true)))
           (try
             (testing "agent creation uses the same retained-read opening"
               (let [created (cluster/ensure-entity!
                              connection cluster/boot-process-identity
                              {:seon.agent/id "juniper" :seon.cluster/name "loop-proof"
                               :seon.ns/name 'my.agents.juniper})
                     bootstrap-id (:seon.turn/id created)
                     closed? #(some? (:seon.turn/closed-at
                                      (db/pull @connection [:seon.turn/closed-at]
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
                   (is (every? (comp seq :seon.cluster.eval/read-evidence) saved))
                   (is (nil? (:seon.turn/id refresh)) (pr-str (:seon.turn/forms refresh))))))
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
                 (is (= ["(help)" "(my.agent/identity)" "(my.plan/items)"
                         "(my.message/inbox)" "(my.agent/settings)"]
                        (mapv :seon.cluster.eval/source (take 5 saved))))
                 (is (= 6 (count saved)))
                 (is (every? (comp seq :seon.cluster.eval/read-evidence) saved)
                     "every seeded read stores its dependency evidence")
                 (is (= 4 (count (db/q '[:find [?key ...] :in $ ?name :where
                                           [?n :seon.ns/name ?name]
                                           [?s :seon.schema/ns ?n]
                                           [?s :seon.schema/key ?key]] @connection 'my.agents.juniper))))
                 (is (str/starts-with? (:seon.cluster.eval/source (last saved)) "(seon.db/q"))
                 (is (= [["Ada" 115] ["Bea" 100] ["Cy" 40]]
                        (sort (db/q '[:find ?customer (sum ?amount)
                                :where [?order :example/customer ?customer]
                                       [?order :example/amount ?amount]] @connection))))
                 (is (= [fixture/instruction]
                        (db/q '[:find [?content ...] :where
                                [?agent :seon.agent/id "juniper"]
                                [?message :seon.cluster.message/to ?agent]
                                [?message :seon.cluster.message/content ?content]] @connection)))
                 (is (every? :seon.eval/value saved))
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
                       unchanged (turn/system-turn request)]
                   (is (every? #(= :unchanged (:seon.turn/status %))
                               (:seon.turn/forms unchanged)))
                   (is (nil? (:seon.turn/id unchanged)))
                   (is (= basis (db/basis-t @connection))))
                 (testing "the first ordinary wake retains the seeded opening once"
                   (agent/arm! {:seon.turn.loop/cluster handle
                                :seon.agent/routing routing :seon.agent/id "juniper"})
                   (fixture/submit! handle routing "(my.agent/done)")
                   (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                   (let [after (evaluation/of-agent @connection "juniper")
                         occurrences (frequencies (map :seon.cluster.eval/source after))]
                     (doseq [entry saved]
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
                   (is (= (mapv :seon.eval/value saved)
                          (mapv :seon.eval/value
                                (evaluation/of-agent @connection "juniper"))))
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
                 (is (= ["2" "4" "6"] (mapv :seon.eval/value saved)))
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
                              [{:seon.cluster.message/id "proof-wake"
                                :seon.cluster.message/to [:seon.agent/id "juniper"]
                                :seon.cluster.message/content "Read the changed message."
                                :seon.cluster.message/at (java.util.Date. 0)}])
                     wake-t (db/basis-t (:db-after message))
                     pending (turn/unanswered-wakes @connection "juniper" {})]
                 (is (seq pending))
                 (let [system (turn/system-turn request)
                       changed (filter #(= :changed (:seon.turn/status %))
                                       (:seon.turn/forms system))]
                   (is (some #{"(my.message/inbox)"}
                             (map :seon.cluster.eval/source changed)))
                   (is (every? #{"(my.message/inbox)" "(my.agent/settings)"}
                               (map :seon.cluster.eval/source changed)))
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
               (let [written (db/transact!
                              connection
                              [{:seon.cluster.message/id "proof-wake-2"
                                :seon.cluster.message/to [:seon.agent/id "juniper"]
                                :seon.cluster.message/content "A second changed message."
                                :seon.cluster.message/at (java.util.Date. 1)}])
                     wake-t (db/basis-t (:db-after written))
                     closed? #(seq (db/q '[:find ?turn :in $ ?since
                                           :where [?agent :seon.agent/id "juniper"]
                                           [?turn :seon.turn/agent ?agent]
                                           [?turn :seon.turn/id _ ?t]
                                           [(>= ?t ?since)]
                                           [?turn :seon.turn/reply ""]
                                           [?turn :seon.turn/closed-at]]
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
                   (is (= "(my.message/inbox)" (:seon.cluster.eval/source (first fresh)))
                       "changed read must precede the no-provider reply")
                   (is (= ["(my.message/inbox)"] (mapv :seon.cluster.eval/source fresh))
                       "no-provider turns do not invent placeholder forms")
                   (is (empty? (turn/unanswered-wakes @connection "juniper" {})))
                   (println {:seon.test/stage :ordinary-wake
                             :seon.test/sources (mapv :seon.cluster.eval/source fresh)}))))
             (testing "root's generated query executes without caller aliases"
               (let [transaction (bootstrap/supervision-tx
                                  @connection cluster/boot-process-identity
                                  (java.util.Date.) "other")
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
                   {:seon.agent/id "other"
                    :seon.turn/id id
                    :seon.turn/opened-at (java.util.Date. 0)
                    :seon.turn/starting-ns [:seon.ns/name 'my.agents.other]
                    :seon.turn/reply source
                    :seon.turn/plan-digest (turn/plan-digest sources)
                    :seon.turn/sources sources}))
                 (is (= 1 (:seon.boot/recovered-runs (#'cluster/recover-runs! connection))))
                 (let [saved (evaluation/of-agent @connection "other")
                       basis (db/basis-t @connection)]
                   (is (= 1 (count saved)))
                   (is (every? :seon.cluster.eval/interrupted-at saved))
                   (is (some? (:seon.turn/closed-at
                               (db/pull @connection [:seon.turn/closed-at] [:seon.turn/id id]))))
                   (is (nil? (turn/next-agent-work @connection {:seon.agent/id "other"})))
                   (is (nil? (db/q '[:find ?e . :where
                                     [?e :seon.agent/id "must-not-execute"]] @connection)))
                   (is (= 0 (:seon.boot/recovered-runs (#'cluster/recover-runs! connection))))
                   (is (= basis (db/basis-t @connection))))))
             (testing "a system-only turn without the wake's results answers nothing"
               (db/transact!
                connection
                [{:seon.cluster.message/id "unobserved-wake"
                  :seon.cluster.message/to [:seon.agent/id "unobserved"]
                  :seon.cluster.message/content "This agent has no inbox read."
                  :seon.cluster.message/at (java.util.Date. 2)}])
               (let [pending (turn/unanswered-wakes @connection "unobserved" {})
                     system (turn/system-turn
                             (assoc request :seon.agent/id "unobserved"))]
                 (is (= 1 (count pending)))
                 (is (string? (:seon.turn/id system)) (pr-str system))
                 (is (= pending (turn/unanswered-wakes @connection "unobserved" {})))))
             (testing "the scenario crosses the real query, write, message, and session boundaries"
               (agent/arm! {:seon.turn.loop/cluster handle
                           :seon.agent/routing routing :seon.agent/id "juniper"})
               (submit "(seon.db/q '[:find ?customer (sum ?amount) :where [?order :example/customer ?customer] [?order :example/amount ?amount]])")
               (is (str/includes? (:seon.eval/value (last (evaluation/of-agent @connection "juniper"))) "115"))
               (submit "(seon.db/transact! [{:example/order \"a3\" :example/customer \"Ada\" :example/amount 40}])")
               (submit "(seon.db/q '[:find (sum ?amount) . :where [?order :example/customer \"Ada\"] [?order :example/amount ?amount]])")
               (is (= "155" (:seon.eval/value (last (evaluation/of-agent @connection "juniper")))))
               (submit "(my.message/send {:my.message/to \"root\" :my.message/content \"Ada had the largest total, 115. I added an order of 40 and verified the new total is 155.\"})")
               (is (= 1 (db/q '[:find (count ?message) . :where
                                [?agent :seon.agent/id "juniper"]
                                [?message :seon.cluster.message/from ?agent]
                                [?message :seon.cluster.message/content ?content]
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
