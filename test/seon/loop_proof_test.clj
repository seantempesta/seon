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
  (str/join "\n\n" (map repl/render-ai (evaluation/of-agent database "proof"))))

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
             {:seon.cluster.agent/id "proof"
              :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.proof}}
             {:seon.cluster.agent/id "other"
              :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.other}}
             {:seon.cluster.agent/id "unobserved"
              :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.unobserved}}
             {:seon.cluster.agent/id "root"
              :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.root}}])
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
                        :seon.cluster.agent/id "proof"
                        :seon.turn/write? true}
               prompt #(render/acquire-context!
                        (merge handle
                               {:seon.db/db @connection
                                :seon.turn/id
                                (:seon.turn/id
                                 (db/pull @connection [:seon.turn/id]
                                          (get-in (last (evaluation/of-agent @connection "proof"))
                                                  [:seon.cluster.eval/run :db/id])))
                                :seon.cluster.agent/id "proof"
                                :seon.sci.eval/time-limit-ms
                                (:seon.config.eval/time-limit-ms handle)}))
               submit
               (fn [source]
                 (let [result (turn/virtual-turn!
                               (assoc request
                                      :seon.cluster.agent/routing routing
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
           (swap! routing assoc :seon.cluster.agent/fault-channel faults)
           (d/listen connection ::proof
                     (fn [report]
                       (swap! transactions conj report)
                       (async/offer! events true)))
           (try
             (testing "fresh opening and stable stored prompt"
               (let [first-id (turn/next-id @connection "loop-proof" "proof")
                     opening (turn/system-turn request)
                     saved (evaluation/of-agent @connection "proof")
                     text (stored-text @connection)
                     first-prompt (prompt)
                     second-prompt (prompt)]
                 (is (string? (:seon.turn/id opening)) (pr-str (keys opening)))
                 (is (= first-id (:seon.turn/id opening)))
                 (is (seq saved))
                 (is (every? :seon.eval/value saved))
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
                 (turn/compact! {:seon.db/connection connection
                                 :seon.cluster.agent/id "proof"})
                 (is (empty? (evaluation/of-agent @connection "proof")))
                 (let [regenerated (turn/system-turn request)
                       after (stored-text @connection)]
                   (is (string? (:seon.turn/id regenerated)))
                   (is (= (mapv :seon.cluster.eval/source saved)
                          (mapv :seon.cluster.eval/source
                                (evaluation/of-agent @connection "proof"))))
                   (is (= (mapv :seon.eval/value saved)
                          (mapv :seon.eval/value
                                (evaluation/of-agent @connection "proof"))))
                   (is (= after (stored-text @connection))
                       "saved bytes remain exact within the new generation")
                   (is (= after (:seon.cluster.prompt/text (prompt))))
                   (println {:seon.test/stage :compact
                             :seon.test/stored (bytes-evidence after)}))))
             (agent/arm! {:seon.turn.loop/cluster handle
                          :seon.cluster.agent/routing routing
                          :seon.cluster.agent/id "proof"})
             (testing "three-form reply, actual handles, and additive history"
               (let [prefix (stored-text @connection)
                     _ (reset! transactions [])
                     id (submit "(+ 1 1)\n(+ 2 2)\n(+ 3 3)")
                     reports @transactions
                     saved (take-last 3 (evaluation/of-agent @connection "proof"))
                     agent-ctx (get-in (agent/armed routing "proof")
                                       [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx])]
                 (is (= 3 (count reports)))
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
             (submit "(my.message/inbox {})")
             (agent/disarm! {:seon.cluster.agent/routing routing
                             :seon.cluster.agent/id "proof"})
             (testing "changed reads answer only observed wakes"
               (let [prefix (stored-text @connection)
                     message (db/transact!
                              connection
                              [{:seon.cluster.message/id "proof-wake"
                                :seon.cluster.message/to [:seon.cluster.agent/id "proof"]
                                :seon.cluster.message/content "Read the changed message."
                                :seon.cluster.message/at (java.util.Date. 0)}])
                     wake-t (db/basis-t (:db-after message))]
                 (is (= 1 (count (turn/unanswered-wakes @connection "proof" {}))))
                 (let [system (turn/system-turn request)
                       changed (filter #(= :changed (:seon.turn/status %))
                                       (:seon.turn/forms system))]
                   (is (= ["(my.message/inbox {})"]
                          (mapv :seon.cluster.eval/source changed)))
                   (is (str/starts-with? (stored-text @connection) prefix))
                   (is (= (stored-text @connection)
                          (:seon.cluster.prompt/text (prompt))))
                   (is (= 1 (count (turn/unanswered-wakes @connection "proof" {}))))
                   (is (< (turn/latest-answering-turn-t @connection "proof") wake-t)
                       "a system-only read refresh does not answer the wake")
                   (println {:seon.test/stage :wake
                             :seon.test/changed (count changed)
                             :seon.test/wake-t wake-t
                             :seon.test/answer-t
                             (turn/latest-answering-turn-t @connection "proof")}))))
             (testing "the ordinary wake path refreshes reads before its reply"
               (let [written (db/transact!
                              connection
                              [{:seon.cluster.message/id "proof-wake-2"
                                :seon.cluster.message/to [:seon.cluster.agent/id "proof"]
                                :seon.cluster.message/content "A second changed message."
                                :seon.cluster.message/at (java.util.Date. 1)}])
                     wake-t (db/basis-t (:db-after written))
                     closed? #(seq (db/q '[:find ?turn :in $ ?since
                                           :where [?agent :seon.cluster.agent/id "proof"]
                                           [?turn :seon.turn/agent ?agent]
                                           [?turn :seon.turn/id _ ?t]
                                           [(>= ?t ?since)]
                                           [?turn :seon.turn/reply "(+ 1 1)"]
                                           [?turn :seon.turn/closed-at]]
                                         @connection wake-t))]
                 (agent/arm! {:seon.turn.loop/cluster handle
                              :seon.cluster.agent/routing routing
                              :seon.cluster.agent/id "proof"})
                 (when-not (closed?)
                   (support/await-event! events ::wake-turn-closed (fn [_] (closed?))))
                 (agent/disarm! {:seon.cluster.agent/routing routing
                                 :seon.cluster.agent/id "proof"})
                 (let [fresh (filter #(>= (:t %) wake-t)
                                     (evaluation/of-agent @connection "proof"))]
                   (is (seq fresh))
                   (is (= "(my.message/inbox {})" (:seon.cluster.eval/source (first fresh)))
                       "changed read must precede the no-provider reply")
                   (is (empty? (turn/unanswered-wakes @connection "proof" {})))
                   (println {:seon.test/stage :ordinary-wake
                             :seon.test/sources (mapv :seon.cluster.eval/source fresh)}))))
             (testing "root's generated query executes without caller aliases"
               (let [transaction (bootstrap/supervision-tx
                                  @connection cluster/boot-process-identity
                                  (java.util.Date.) "other")
                     query-source (some #(when (and (map? %)
                                                    (str/starts-with?
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
                                   :seon.cluster.agent/id "root"
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
                     source "(seon.db/transact! [{:seon.cluster.agent/id \"must-not-execute\"}])"
                     sources (turn/planned-sources source 'my.agents.other 10000)]
                 (db/transact!
                  connection
                  (turn/system-run-tx
                   @connection
                   {:seon.cluster.agent/id "other"
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
                   (is (nil? (turn/next-agent-work @connection {:seon.cluster.agent/id "other"})))
                   (is (nil? (db/q '[:find ?e . :where
                                     [?e :seon.cluster.agent/id "must-not-execute"]] @connection)))
                   (is (= 0 (:seon.boot/recovered-runs (#'cluster/recover-runs! connection))))
                   (is (= basis (db/basis-t @connection))))))
             (testing "a system-only turn without the wake's results answers nothing"
               (db/transact!
                connection
                [{:seon.cluster.message/id "unobserved-wake"
                  :seon.cluster.message/to [:seon.cluster.agent/id "unobserved"]
                  :seon.cluster.message/content "This agent has no inbox read."
                  :seon.cluster.message/at (java.util.Date. 2)}])
               (let [pending (turn/unanswered-wakes @connection "unobserved" {})
                     system (turn/system-turn
                             (assoc request :seon.cluster.agent/id "unobserved"))]
                 (is (= 1 (count pending)))
                 (is (string? (:seon.turn/id system)) (pr-str system))
                 (is (= pending (turn/unanswered-wakes @connection "unobserved" {})))))
             (is (empty? (db/q '[:find [?attempt ...]
                                 :where [?attempt :seon.ai.attempt/id]] @connection)))
             (finally
               (agent/disarm! {:seon.cluster.agent/routing routing
                               :seon.cluster.agent/id "proof"})
               (d/unlisten connection ::proof)
               (doseq [channel [events faults
                                (:seon.cluster.wake/channel handle)
                                (:seon.render/context-channel handle)
                                (:seon.turn.loop/completion handle)]]
                 (async/close! channel))))))))))
