(ns seon.turn-continue-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.ai :as ai]
            [clojure.data.json :as json]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.host :as host]
            [seon.context]
            [seon.eval.drive :as drive]
            [seon.id]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as runtime]
            [seon.task :as task]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.repl :as repl]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(def ^:private read-source
  "(seon.db/q '[:find (sum ?amount) . :where [?order :example/customer \"Ada\"] [?order :example/amount ?amount]])")

(def ^:private done-source "(my.agent/done)")

(defn- provider-refusal []
  (with-open [body (java.io.ByteArrayInputStream.
                    (.getBytes "data: {malformed json\n\n" "UTF-8"))]
    (#'ai/streamed-completion body nil)))

(defn- prove-session
  {:malli/schema [:=> [:cat :keyword [:vector :seon.schema/value] [:int {:min 1}] [:int {:min 1}] [:or :nil :keyword]] :nil]}
  [scenario replies limit expected disposition]
  (let [refusal (provider-refusal)]
      (testing (name scenario)
        (support/with-database
         (fn [connection]
           (config/apply! {:seon.db/connection connection
                          :seon.boot/cluster-name "loop-continue"
                          :seon.config/manifest {:seon.config.ai/no-provider true
                                                 :seon.config.agent/turn-completion-backstop-ms 5000}})
           (support/transacted! connection (support/agent-tx @connection "root" 'my.agents.root))
           (cluster/ensure-cluster-entity! connection "loop-continue" cluster/boot-process-identity)
           (let [ctx (support/fork-cluster-ctx connection)
                 environment (support/environment "loop-continue" connection)
                 routing (support/attempt-routing connection)
                 requests (atom [])]
             (with-open [provider-progress (support/closeable (async/chan 1) async/close!)
                         events (support/closeable (async/chan (async/sliding-buffer 1)) async/close!)
                         faults (support/closeable (async/chan (async/sliding-buffer 16)) async/close!)
                         launcher (support/closeable
                                   (flow/start-work-launcher!
                                    {:seon.env/environment environment
                                     :seon.flow/configuration
                                     (select-keys (support/effective-config) flow/flow-workload-attributes)})
                                   flow/stop-work-launcher!)
                         handle-resource
                         (support/closeable
                          (support/cluster-handle
                           {:seon.env/environment environment :seon.db/connection connection
                            :seon.cluster/name "loop-continue" :seon.sci.eval/ctx ctx
                            :seon.flow/work-launcher @launcher
                            :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                            :seon.db.process/id cluster/boot-process-identity})
                          (fn [handle]
                            (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                            (d/unlisten connection ::continuation)
                            (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                                         :seon.turn.loop/completion]]
                              (async/close! (get handle key)))))]
               (let [handle @handle-resource
                     work-request {:seon.agent/id "juniper"}
                     closed-attempts
                     (fn [database]
                       (db/q '[:find [?attempt ...] :where
                              [?agent :seon.agent/id "juniper"]
                              [?turn :seon.turn/agent ?agent]
                              [?turn :seon.turn/closed-tx _]
                              [?turn :seon.turn/attempts ?attempt]] database))]
                 (swap! routing assoc :seon.agent/fault-channel @faults)
                 (cluster/ensure-entity! connection cluster/boot-process-identity
                                         {:seon.agent/id "juniper" :seon.cluster/name "loop-continue"
                                          :seon.ns/name 'my.agents.juniper})
                 (support/assign-task! connection "juniper")
                 (try
                   (fixture/install! handle routing)
                   (catch Throwable failure
                     (throw (ex-info "Continuation fixture did not publish its required turn closure."
                                     {:seon.test/event :seon.turn/closed-tx
                                      :seon.test/proc :seon.agent/turn
                                      :seon.test/fault (async/poll! @faults)}
                                     failure))))
                 (let [settings (get-in (db/pull @connection '[{:seon.agent/settings [:db/id]}]
                                                [:seon.agent/id "juniper"])
                                        [:seon.agent/settings :db/id])]
                   (is (some? (:db-after (db/transact! connection
                                            [[:db/retract settings :seon.config.ai/no-provider true]
                                             [:db/add settings :seon.config.run/max-episode-runs limit]
                                             [:db/add settings :seon.config.eval/time-limit-ms 10000]
                                             [:db/add settings :seon.config.ai/timeout-ms 30000]])))))
                 (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "loop-continue"
                                :seon.config/manifest {:seon.config.ai/no-provider :seon.config/absent}})
                 (d/listen connection ::continuation (fn [_] (async/offer! @events true)))
                 (let [outside-t (turn/outside-wake-t @connection "juniper")]
                   ; Replace only the external provider response, as the reply tests do.
                   ; The proc, attempt writer, SCI, prompt and work derivation stay real.
                   (with-redefs [ai/complete
                                 (fn [_projection request]
                                   (when (= scenario :slow-provider)
                                     (Thread/sleep 10000)
                                     (async/offer! @provider-progress true)
                                     (Thread/sleep 10000))
                                   (let [index (count (swap! requests conj request))
                                         reply (get replies (dec index) refusal)]
                                     (if (string? reply)
                                       (ai/completion-text
                                        {"choices" [{"message" {"content" reply}
                                                     "finish_reason" "stop"}]})
                                       reply)))]
                     (try
                       (agent/arm! {:seon.turn.loop/cluster handle
                                    :seon.agent/routing routing :seon.agent/id "juniper"})
                       (when (= scenario :slow-provider)
                         (support/await-event! @provider-progress ::provider-still-pending-at-eval-limit))
                       (support/await-event!
                        @events ::session-ended
                        (fn [_]
                          (>= (count (closed-attempts @connection)) expected)))
                       ; A system turn can finish before the proc opens its next
                       ; provider turn. Observe the required closed attempts and
                       ; the proc's completion event before inspecting next work.
                       (let [completion (:seon.turn.loop/completion (agent/armed routing "juniper"))
                             permit (support/await-event! completion ::last-pass-returned)]
                         (try
                           (is (nil? (turn/next-agent-work @connection work-request)))
                           (finally
                             (is (true? (async/offer! completion permit))))))
                       (finally
                         (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"}))))
                   (is (= expected (count @requests)))
                   (let [saved (evaluation/of-agent @connection "juniper")
                         opening (take-while #(= (:seon.cluster.eval/run (first saved))
                                                 (:seon.cluster.eval/run %)) saved)
                         stable-reads (filter
                                       #(let [form (read-string (:seon.cluster.eval/source %))]
                                          (or (= '(seon.agent/settings) form)
                                              (some #{:seon.runtime/listens}
                                                    (tree-seq coll? seq form)))) opening)
                         by-source (group-by :seon.cluster.eval/source saved)]
                     (is (= 2 (count stable-reads)) "both generated reads must exist")
                     (doseq [entry stable-reads]
                       (let [observations (get by-source (:seon.cluster.eval/source entry))]
                         (is (= (count observations) (count (distinct (map :seon.eval/shown observations))))
                             "provider continuation never repeats an unchanged settings/runtime value"))))
                   (is (= expected (count (closed-attempts @connection))))
                   (is (= outside-t (turn/outside-wake-t @connection "juniper"))
                       "self-continuation neither creates an outside wake nor refills the bound")
                   (is (= (- limit expected) (turn/turns-left @connection "juniper")))
                   (is (nil? (turn/next-agent-work @connection work-request)))
                   (is (= (if disposition #{[disposition]} #{})
                          (db/q '[:find ?disposition :where
                                  [?agent :seon.agent/id "juniper"]
                                  [?turn :seon.turn/agent ?agent]
                                  [?turn :seon.turn/disposition ?disposition]] @connection)))
                   (is (nil? (db/q '[:find ?order . :where [?order :example/order "after-done"]] @connection)))
                   (is (empty? (turn/unanswered-wakes @connection "juniper" {})))
                   (when (= scenario :read-then-done)
                     (let [entry (first (filter #(= read-source (:seon.cluster.eval/source %))
                                                (evaluation/of-agent @connection "juniper")))
                           next-prompt (:seon.ai/prompt (second @requests))]
                       (is (= "115" (:seon.eval/shown entry)))
                       (is (and (string? next-prompt)
                                (str/includes? next-prompt (repl/render-ai entry))))))
                   (is (nil? (async/poll! @faults)))
                   (println {:seon.test/scenario scenario :seon.test/provider-attempts (count @requests)
                             :seon.test/turns-left (turn/turns-left @connection "juniper")}))))))))))


; Each independent real cluster scenario fits the runner's existing task bound.
(deftest read-results-arrive-before-done
  (prove-session :read-then-done [read-source done-source] 3 2 :wait))

(deftest provider-progress-has-its-own-bound
  (prove-session :slow-provider [done-source] 3 1 :wait))



;;; A model agent driven through the real path: its own MCP entrance, an outside wake, the turn loop and a
;;; local provider answering fixed replies (`test-support/with-provider`).

(defn- agent-eval
  "The last reply item of `code` evaluated as `agent-id` through `seon.mcp/call`, read back as data."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (->> (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                       "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")
       (map #(support/reply (get % "text")))
       (remove #(and (map? %) (contains? % :seon.repl/request)))
       last))

(defn- model-turns
  "How many call rows of `agent-id` carry a model attempt on `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :int]}
  [database agent-id]
  (or (db/q '[:find (count ?turn) . :in $ ?agent-id
              :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent] [?turn :seon.turn/attempts _]]
            database agent-id)
      0))

(defn- outside-wake!
  "Write one outside message to `agent-id`, as an operator would, and wake its proc."
  {:malli/schema [:=> [:cat :string :seon.agent/id :seon.db/connection :string] :nil]}
  [running agent-id connection text]
  (let [cluster-name (:seon.cluster/name (support/execution-handle nil))
        wake (java.util.concurrent.FutureTask.
              ^java.util.concurrent.Callable
              #(drive/inbound! connection cluster-name cluster/boot-process-identity agent-id text))]
    ;; an outside wake comes from no evaluation: written on a thread with no conveyed custody
    (.start (Thread. wake))
    (.get wake)
    (async/offer! (:seon.cluster.wake/channel
                   (get-in @(:seon.agent/routing (:seon.turn.loop/cluster (get @runtime/running-instances running)))
                           [:seon.agent/armed agent-id]))
                  :seon.agent/wake)
    nil))

(defn- until
  "Poll `ready?` every 50 ms for at most `tries` polls; its last answer."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value] :int] :seon.schema/value]}
  [ready? tries]
  (loop [n tries]
    (let [answer (ready?)]
      (if (or answer (zero? n)) answer (do (Thread/sleep 50) (recur (dec n)))))))

(defn- green-task!
  "Assign `agent-id` the task `tq.end/area` whose proof is red, then let the model define the fix through
  `replies` until the agent's admission closes; answers the provider's request documents."
  {:malli/schema [:=> [:cat :string :seon.agent/id :seon.db/connection [:vector :string]] [:vector :map]]}
  [running agent-id connection replies]
  (agent-eval running agent-id "(ns tq.end) (defn area [r] r)")
  (agent-eval running agent-id "(in-ns 'tq.end) (clojure.test/deftest ^{:seon/tasks #{'tq.end/area}} area-test (clojure.test/is (= 25 (tq.end/area 5))))")
  (let [id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id
                                         :seon.task/ns 'tq.end :seon.task/name "area" :seon.task/kind :function
                                         :seon.task/intent "Square."}))
        ledger (atom [])]
    (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                   :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
    (support/with-provider
     ledger (support/reply-queue replies)
     (fn [server]
       (is (nil? (:seon.repl/error
                  (agent-eval running agent-id (str "(my.agent/settings! "
                                                    (seon.id/printed (assoc (support/provider-config server)
                                                                            :seon.config.ai/no-provider false))
                                                    ")")))))
       (outside-wake! running agent-id connection "Work your task.")
       (until #(:seon.agent/admission-closed-tx
                (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
              600)
       @ledger))))

(deftest ^{:seon/tasks #{'seon.turn/a-wake-reopens-a-finished-task}
           :seon.test/long "Two setup evaluations with their test steps, then one fixture-model pass and its test step."
           :seon.test/long-ms 30000}
  a-done-agent-opens-no-work-turn
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [sent (green-task! running agent-id connection ["(in-ns 'tq.end)\n(defn area [r] (* r r))"])
           database (db/db connection)]
       (is (= 1 (count sent)) "one model call turned the proof green")
       (is (some? (:seon.agent/admission-closed-tx
                   (db/pull database [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id])))
           "the task's end closed the agent's admission")
       (outside-wake! running agent-id connection "Why did you square it?")
       (Thread/sleep 500)
       (is (nil? (turn/next-agent-work (db/db connection) {:seon.agent/id agent-id})) "a wake opens no work turn")
       (is (= 1 (model-turns (db/db connection) agent-id)) "the model was not called again")))))


(deftest ^{:seon/tasks #{'seon.turn/a-task-end-never-reaches-root}
           :seon.test/long "An own-branch agent's setup and settings with their test steps, one fixture-model pass, its proposal's proof run and root's merge gate."
           :seon.test/long-ms 60000}
  a-task-that-ends-green-sends-root-one-merge-request
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           agent-id (str "ended-" (seon.id/id))
           ;; root's home is this member's branch, so the end message and the merge stay inside the fixture
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           ;; the task's proof, defined at the home by root before the agent's branch exists
           _ (agent-eval running "root" (str "(ns tq.endm) (defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                             "(clojure.test/deftest ^{:seon/tasks #{'tq.endm/area}} area-test (clojure.test/is (= 25 (area 5))))"))
           {branch :seon.store/branch target :seon.db/connection} (support/own-branch-agent! running agent-id "(ns tq.endm)")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                            :seon.task/ns 'tq.endm :seon.task/name "area" :seon.task/kind :function
                                            :seon.task/intent "Square."}))
           ledger (atom [])]
       (is (keyword? branch) "the agent has a branch of its own")
       (is (nil? (:seon.error/at (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                                                :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})))
           "root assigns the task to the agent's branch")
       (support/with-provider
        ledger (support/reply-queue ["(in-ns 'tq.endm)\n(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))"])
        (fn [server]
          (agent-eval running agent-id (str "(my.agent/settings! "
                                            (seon.id/printed (assoc (support/provider-config server)
                                                                    :seon.config.ai/no-provider false))
                                            ")"))
          (support/outside-wake-on-branch! running agent-id target "Work your task.")
          (until #(:seon.agent/admission-closed-tx
                   (db/pull (db/db target) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
                 1000)))
       (let [home (db/db connection)
             ended (db/q '[:find [(pull ?m [:seon.message/about :seon.task/end :seon.task/merge]) ...] :in $ ?agent-id
                           :where [?agent :seon.agent/id ?agent-id] [?m :seon.message/from ?agent]
                           [?root :seon.agent/id "root"] [?m :seon.message/to ?root]]
                         home agent-id)
             [message] ended
             proposed (db/q '[:find ?merge . :in $ ?branch :where [?p :seon.task/branch ?branch] [?p :seon.task/merge ?merge]]
                            home branch)
             prepared (agent-eval running "root" (str "(my.program/prepare-merge! {:seon.task/merge \"" (:seon.task/merge message) "\"})"))]
         (is (= 1 (count @ledger)) "one model call turned the proof green")
         (is (= 1 (count ended)) "root received exactly one message from the agent")
         (is (= {:seon.message/about id :seon.task/end :done} (select-keys message [:seon.message/about :seon.task/end]))
             "it names the task and how it ended")
         (is (and (some? proposed) (= proposed (:seon.task/merge message))) "it names the proposal's merge task")
         (is (some? (:seon.source/candidate (:seon.repl/value prepared))) "root's prepare-merge! on it answers a proposal")
         (let [shown (some #(when (= (:seon.task/merge message) (:seon.task/merge %)) %)
                           (get-in (seon.context/current-state home "root") [:seon.context/unread :seon.context/items]))]
           (is (= {:seon.task/end :done :seon.task/proof-counts {:green 1} :seon.task/calls 1}
                  (select-keys (:seon.task/ended shown) [:seon.task/end :seon.task/proof-counts :seon.task/calls]))
               "root's block shows the ended value derived from the agent's branch")))))))

(deftest ^{:seon/tasks #{'seon.turn/a-reached-red-test-keeps-a-task-from-ending-done}
           :seon.test/long "An own-branch agent's setup and settings with their test steps and one fixture-model pass with its test step."
           :seon.test/long-ms 60000}
  a-change-greening-its-proof-and-reddening-a-reaching-test-does-not-end-done
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           agent-id (str "reached-red-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           ;; the proof wants a square; another test reaching `area` holds its identity
           _ (support/declare-source! connection 'tq.endr
                                      (str "(ns tq.endr (:require [clojure.test :refer [deftest is]]))\n"
                                           "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                           "(deftest ^{:seon/tasks #{'tq.endr/area}} area-test (is (= 25 (area 5))))\n"
                                           "(deftest identity-test (is (= 3 (area 3))))\n"))
           {branch :seon.store/branch target :seon.db/connection} (support/own-branch-agent! running agent-id "(ns tq.endr)")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                            :seon.task/ns 'tq.endr :seon.task/name "area" :seon.task/kind :function
                                            :seon.task/intent "Square."}))]
       (is (string? (:seon.task/assignment (task/assign! {:seon.agent/context-source (support/running-cluster running)
                                                          :seon.db/connection connection :seon.task/assigned id
                                                          :seon.task/agent [:seon.agent/id agent-id]
                                                          :seon.task/branch branch})))
           "root assigns the task to the agent's branch")
       (support/with-provider
        (atom []) (support/reply-queue ["(in-ns 'tq.endr)\n(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))"])
        (fn [server]
          (agent-eval running agent-id (str "(my.agent/settings! "
                                            (seon.id/printed (assoc (support/provider-config server)
                                                                    :seon.config.ai/no-provider false
                                                                    :seon.config.run/max-episode-runs 1))
                                            ")"))
          (support/outside-wake-on-branch! running agent-id target "Work your task.")
          (until #(:seon.agent/admission-closed-tx
                   (db/pull (db/db target) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
                 1000)))
       (let [ends (db/q '[:find [?end ...] :in $ ?agent-id
                          :where [?agent :seon.agent/id ?agent-id] [?m :seon.message/from ?agent] [?m :seon.task/end ?end]]
                        (db/db connection) agent-id)]
         (is (= [:capped] ends)
             "the proof went green but a reaching test went red: the task never ended done, and the cap ended it"))))))
