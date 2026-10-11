(ns seon.turn-continue-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
            [seon.test]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.fn :as fn]
            [seon.program :as program]
            [seon.repl :as repl]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(def ^:private read-source
  "(seon.db/q '[:find (sum ?amount) . :where [?order :example/customer \"Ada\"] [?order :example/amount ?amount]])")

(def ^:private done-source "(my.turn/wait {:my.turn/note \"Session complete.\"})")

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
  ;; root's home is this member's branch, so the end message reaches root inside the fixture and the end closes
  (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                             (:seon.cluster/name (support/execution-handle nil))]])
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
                   (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
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
                                           "(deftest identity-test (is (= 3 (area 3))))\n")
                                      "test")
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
                   (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
                 1000)))
       (let [ends (db/q '[:find [?end ...] :in $ ?agent-id
                          :where [?agent :seon.agent/id ?agent-id] [?m :seon.message/from ?agent] [?m :seon.task/end ?end]]
                        (db/db connection) agent-id)]
         (is (= [:capped] ends)
             "the proof went green but a reaching test went red: the task never ended done, and the cap ended it"))))))

(deftest ^{:seon/tasks #{'seon.turn/a-stalled-agent-ends-its-task-to-root}
           :seon.test/long "An own-branch agent's setup and settings with their test steps and two fixture-model passes."
           :seon.test/long-ms 60000}
  an-agent-whose-loop-stops-before-its-proofs-pass-ends-stalled-to-root
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           agent-id (str "stalled-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           _ (support/declare-source! connection 'tq.ends
                                      (str "(ns tq.ends (:require [clojure.test :refer [deftest is]]))\n"
                                           "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                           "(deftest ^{:seon/tasks #{'tq.ends/area}} area-test (is (= 25 (area 5))))\n"))
           {branch :seon.store/branch target :seon.db/connection} (support/own-branch-agent! running agent-id "(ns tq.ends)")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                            :seon.task/ns 'tq.ends :seon.task/name "area" :seon.task/kind :function
                                            :seon.task/intent "Square."}))]
       (is (string? (:seon.task/assignment (task/assign! {:seon.agent/context-source (support/running-cluster running)
                                                          :seon.db/connection connection :seon.task/assigned id
                                                          :seon.task/agent [:seon.agent/id agent-id]
                                                          :seon.task/branch branch})))
           "root assigns the task to the agent's branch")
       ;; two prose replies in a row: the loop gives the first one more call and then stops calling the agent
       (support/with-provider
        (atom []) (support/reply-queue ["I think area should square its input." "Squaring is the fix."])
        (fn [server]
          (agent-eval running agent-id (str "(my.agent/settings! "
                                            (seon.id/printed (assoc (support/provider-config server)
                                                                    :seon.config.ai/no-provider false))
                                            ")"))
          (support/outside-wake-on-branch! running agent-id target "Work your task.")
          (until #(:seon.agent/admission-closed-tx
                   (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
                 400)))
       (is (= [:stalled] (db/q '[:find [?end ...] :in $ ?agent-id
                                 :where [?agent :seon.agent/id ?agent-id] [?m :seon.message/from ?agent] [?m :seon.task/end ?end]]
                               (db/db connection) agent-id))
           "the stopped loop ended the task, and root heard it once")))))

(deftest ^{:seon.test/long "Three setup evaluations with their test steps, then the end's proposal run."
           :seon.test/long-ms 30000}
  a-task-end-is-its-message-and-the-agents-close-in-one-transaction
  (support/with-running-agent
   (fn [running agent-id connection]
     ;; root's home is this member's branch, so the end message stays inside the fixture
     (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                (:seon.cluster/name (support/execution-handle nil))]])
     (agent-eval running agent-id (str "(ns tq.endo (:require [clojure.test :refer [deftest is]]))\n"
                                       "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                       "(deftest ^{:seon/tasks #{'tq.endo/area}} area-test (is (= 25 (area 5))))"))
     (let [id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id
                                            :seon.task/ns 'tq.endo :seon.task/name "area" :seon.task/kind :function
                                            :seon.task/intent "Square."}))
           _ (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection
                            :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id]
                            :seon.task/branch (get-in (db/db connection) [:config :branch])})
           _ (agent-eval running agent-id "(in-ns 'tq.endo)\n(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))")
           ends #(db/q '[:find [?tx ...] :in $ ?agent-id
                         :where [?a :seon.agent/id ?agent-id] [?m :seon.message/from ?a] [?m :seon.task/end _ ?tx]]
                       (db/db connection) agent-id)]
       (is (= :done (turn/task-end (db/db connection) agent-id)) "the fix turned the proof green")
       (#'turn/end-task! (support/running-cluster running) agent-id)
       (#'turn/end-task! (support/running-cluster running) agent-id)
       (is (= [(:db/id (:seon.agent/admission-closed-tx
                        (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id])))]
              (ends))
           "root holds the end's message once, written by the transaction that closed the agent")))))

;;; A green end observed without a model call: the agent's fix arrives through its own MCP entrance, then the
;;; end is observed on the agent's own execution handle as its loop observes it.

(defn- branch-agent!
  "An own-branch agent assigned the task `<ns>/<task-name>` (root's, at the member's home), whose proof is red on
  its branch; answers the agent's id and its execution handle."
  {:malli/schema [:=> [:cat :string :seon.db/connection :symbol :string] :map]}
  [running connection ns-sym task-name]
  (let [agent-id (str "end-" task-name "-" (seon.id/id))
        {branch :seon.store/branch} (support/own-branch-agent! running agent-id (str "(ns " ns-sym ")"))
        id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns ns-sym
                                         :seon.task/name task-name :seon.task/kind :function :seon.task/intent "Square."}))
        assigned (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection
                                :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})]
    (when (:seon.error/at assigned) (throw (ex-info (:seon.error/message assigned) assigned)))
    {:seon.agent/id agent-id :seon.task/id id}))

(defn- fixed-end!
  "Evaluate `fix` as `agent-id` in `ns-sym`, then answer its execution handle, whose task has ended :done."
  {:malli/schema [:=> [:cat :string :seon.agent/id :symbol :string] :map]}
  [running agent-id ns-sym fix]
  (agent-eval running agent-id (str "(in-ns '" ns-sym ")\n" fix))
  (agent/acquire-context! (support/running-cluster running) agent-id))

(defn- end!
  "Observe the end of `agent-id`'s task on its execution handle `execution`, inside its branch's custody, as the
  agent's own loop does."
  {:malli/schema [:=> [:cat :string :map :seon.agent/id] :nil]}
  [running execution agent-id]
  (db/call-with-custody {:seon.db/connection (:seon.db/connection execution)}
    #(#'turn/end-task! (assoc execution :seon.db.process/id (:seon.db.process/id (support/running-cluster running))) agent-id)))

(defn- answering
  "The proposals recorded at `home` answering `agent-id`'s assignment."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:vector :string]]}
  [home agent-id]
  (vec (db/q '[:find [?proposal ...] :in $ ?agent-id
               :where [?g :seon.agent/id ?agent-id] [?a :seon.task/agent ?g] [?p :seon.task/proposes ?a]
               [?p :seon.task/proposal ?proposal]]
             home agent-id)))

(defn- end-messages
  "The end messages `agent-id` sent root, as stored at `home`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:vector :map]]}
  [home agent-id]
  (vec (db/q '[:find [(pull ?m [:seon.message/id :seon.task/end :seon.task/merge]) ...] :in $ ?agent-id
               :where [?a :seon.agent/id ?agent-id] [?m :seon.message/from ?a] [?m :seon.task/end _]]
             home agent-id)))

(defn- root-home!
  "Make the running member's branch root's home, so tasks, merges and end messages stay inside the fixture."
  {:malli/schema [:=> [:cat :seon.db/connection] :nil]}
  [connection]
  (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                             (:seon.cluster/name (support/execution-handle nil))]])
  nil)

(deftest ^{:seon/tasks #{'seon.turn/an-end-proposes-at-most-once}
           :seon.test/long "An own-branch agent's first request, assignment proof run and fix with its test step, then two proposal runs."
           :seon.test/long-ms 60000}
  an-end-proposes-once-whatever-interrupted-it
  (support/with-running-agent
   (fn [running _ connection]
     (root-home! connection)
     (support/declare-source! connection 'tq.endp
                              (str "(ns tq.endp (:require [clojure.test :refer [deftest is]]))\n"
                                   "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                   "(deftest ^{:seon/tasks #{'tq.endp/area}} area-test (is (= 25 (area 5))))\n"))
     (let [{agent-id :seon.agent/id} (branch-agent! running connection 'tq.endp "area")
           execution (fixed-end! running agent-id 'tq.endp "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))")]
       (is (= :done (turn/task-end (db/db (:seon.db/connection execution)) agent-id)) "the fix turned the proof green")
       ;; an end interrupted after it proposed and before it told root
       (db/call-with-custody {:seon.db/connection (:seon.db/connection execution)}
         #((requiring-resolve 'my.program/proposal!)
           {:my.program/context (assoc execution :my.program/base-ctx (:seon.sci.eval/base-ctx execution))
            :seon.db/connection (:seon.db/connection execution) :seon.agent/id agent-id}))
       ;; the branch moves on before the end is observed again
       (agent-eval running agent-id "(+ 1 2)")
       (end! running execution agent-id)
       (let [home (db/db connection)
             proposals (answering home agent-id)
             [message :as messages] (end-messages home agent-id)]
         (is (= 1 (count proposals)) "the end proposed once")
         (is (= 1 (count messages)) "root heard the end once")
         (is (= (db/q '[:find ?m . :in $ ?p :where [?x :seon.task/proposal ?p] [?x :seon.task/merge ?m]] home (first proposals))
                (:seon.task/merge message))
             "the message names the one proposal's merge task"))))))

(defn- git!
  "Run git in `root`; its trimmed output, thrown when it fails."
  {:malli/schema [:=> [:cat :string [:* :string]] :string]}
  [root & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" root args)]
    (when-not (zero? exit) (throw (ex-info err {:git args})))
    (str/trim out)))

(defn- declare-file!
  "Write `text` at `path` under the checkout `root`, commit it and declare it at the cluster head unrun, as
  publication declares a checkout's file."
  {:malli/schema [:=> [:cat :seon.db/connection :string :string :string] :nil]}
  [connection root path text]
  (io/make-parents (io/file root path))
  (spit (io/file root path) text)
  (git! root "add" path)
  (git! root "commit" "-q" "-m" (str "declare " path))
  (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})]
    (support/transacted! connection (fn/reconcile-tx (db/db connection) entities (mapv program/row-identity entities))))
  nil)

(def ^:private landing-source
  (str "(ns tq.endl\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  r)\n\n"
       "(defn side\n  \"The side.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  r)\n\n"
       "(deftest ^{:seon/tasks #{'tq.endl/area}} area-test (is (= 25 (area 5))))\n\n"
       "(deftest ^{:seon/tasks #{'tq.endl/side}} side-test (is (= 4 (side 2))))\n"))

(deftest ^{:seon/tasks #{'seon.turn/a-green-end-lands-its-merge}
           :seon.test/long "Two own-branch agents' first requests, assignment proof runs and fixes with their test steps, two proposal runs and two lands (each one prepare-merge!: a nested seon.test/run on a new candidate commit)."
           :seon.test/long-ms 240000}
  a-green-end-lands-its-merge-without-root
  (support/with-running-agent
   (fn [running _ connection]
     (root-home! connection)
     (let [path "test/tq/endl.clj"
           root (support/temp-root "end-land")
           merge-row #(dissoc (db/pull (db/db connection) [:seon.task/outcome :seon.task/landed :seon.task/landed-git
                                                          :seon.task/refusal :seon.task/refused-tests]
                                       [:seon.task/id %]) :db/id)]
       (try
         (git! root "init" "-q")
         (git! root "config" "user.email" "end@test.invalid")
         (git! root "config" "user.name" "end-test")
         (declare-file! connection root path landing-source)
         (support/apply-config! connection (:seon.cluster/name (support/execution-handle nil))
                                {:seon.config.root/land? true :seon.config.fs/working-root root})
         (testing "a green end lands on main, on disk and in one git commit, and root's block shows it"
           (let [{agent-id :seon.agent/id} (branch-agent! running connection 'tq.endl "area")
                 fix "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  (* r r))"
                 execution (fixed-end! running agent-id 'tq.endl fix)
                 before (git! root "rev-parse" "HEAD")
                 _ (end! running execution agent-id)
                 [message] (end-messages (db/db connection) agent-id)
                 landed (merge-row (:seon.task/merge message))
                 shown (some #(when (= (:seon.task/merge message) (:seon.task/merge %)) (:seon.task/ended %))
                             (get-in (seon.context/current-state (db/db connection) "root") [:seon.context/unread :seon.context/items]))]
             (is (= :done (:seon.task/end message)) "the task ended green")
             (is (= :accepted (:seon.task/outcome landed)) "the merge task is decided with no root")
             (is (uuid? (:seon.task/landed landed)) "it names the main commit the landing made")
             (is (= (git! root "rev-parse" "HEAD") (:seon.task/landed-git landed)) "and the checkout's new git commit")
             (is (= before (git! root "rev-parse" "HEAD^")) "one commit on the previous head")
             (is (= fix (:seon.fn/source (db/pull (db/db connection) [:seon.fn/source] [:seon.fn/sym 'tq.endl/area])))
                 "main holds the agent's definition")
             (is (str/includes? (slurp (io/file root path)) fix) "the file holds it")
             (is (= (select-keys landed [:seon.task/landed :seon.task/landed-git])
                    (select-keys shown [:seon.task/landed :seon.task/landed-git]))
                 "root's block shows the landing")))
         (testing "a red gate leaves the merge task undecided with its refusal, and lands nothing"
           (let [{agent-id :seon.agent/id} (branch-agent! running connection 'tq.endl "side")
                 ;; a test reaching side lands on main after the agent's branch forked, so only the gate sees it
                 _ (declare-file! connection root path
                                  (str (slurp (io/file root path))
                                       "\n(deftest side-identity-test (is (= 3 (side 3))))\n"))
                 head (git! root "rev-parse" "HEAD")
                 execution (fixed-end! running agent-id 'tq.endl
                                       "(defn side\n  \"The side.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  (* r r))")
                 _ (end! running execution agent-id)
                 [message] (end-messages (db/db connection) agent-id)
                 refused (merge-row (:seon.task/merge message))]
             (is (= :done (:seon.task/end message)) "the task ended green on its branch")
             (is (= {:seon.task/refusal :seon.source/red-test-error :seon.task/refused-tests #{'tq.endl/side-identity-test}}
                    (update refused :seon.task/refused-tests set))
                 "the merge task carries the refusal and stays undecided")
             (is (= head (git! root "rev-parse" "HEAD")) "nothing is committed")))
         (finally (support/delete-root! root)))))))

(deftest ^{:seon/tasks #{'seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done}
           :seon.test/long "An own-branch agent's setup and settings with their test steps, one run on main and one fixture-model pass with its test step."
           :seon.test/long-ms 60000}
  a-green-proof-with-reached-tests-only-unverified-or-red-on-main-ends-done
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           agent-id (str "reached-unverified-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           ;; besides the proof, `area` is reached by a test red on main (and still red after the change) and a
           ;; declared-long test the step never runs: neither is the change's
           _ (support/declare-source! connection 'tq.endu
                                      (str "(ns tq.endu (:require [clojure.test :refer [deftest is]]))\n"
                                           "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                           "(deftest ^{:seon/tasks #{'tq.endu/area}} area-test (is (= 25 (area 5))))\n"
                                           "(deftest legacy-test (is (= 0 (area 1))))\n"
                                           "(deftest ^{:seon.test/long \"never run by a step\" :seon.test/long-ms 6000}"
                                           " slow-test (is (pos? (area 2))))\n")
                                      "test")
           main-run (seon.test/run {:seon.test/policy :named :seon.test/execution (support/execution-handle nil)
                                    :seon.test/recording-connection connection :seon.test/identities #{'tq.endu/legacy-test}})
           _ (is (= [[0 1]] (mapv (juxt :seon.test/pass-count :seon.test/fail-count) (:seon.test/results main-run)))
                 "legacy-test is red on main before the change")
           {branch :seon.store/branch target :seon.db/connection} (support/own-branch-agent! running agent-id "(ns tq.endu)")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                            :seon.task/ns 'tq.endu :seon.task/name "area" :seon.task/kind :function
                                            :seon.task/intent "Square."}))]
       (is (string? (:seon.task/assignment (task/assign! {:seon.agent/context-source (support/running-cluster running)
                                                          :seon.db/connection connection :seon.task/assigned id
                                                          :seon.task/agent [:seon.agent/id agent-id]
                                                          :seon.task/branch branch})))
           "root assigns the task to the agent's branch")
       (support/with-provider
        (atom []) (support/reply-queue ["(in-ns 'tq.endu)\n(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))"])
        (fn [server]
          (agent-eval running agent-id (str "(my.agent/settings! "
                                            (seon.id/printed (assoc (support/provider-config server)
                                                                    :seon.config.ai/no-provider false
                                                                    :seon.config.run/max-episode-runs 1))
                                            ")"))
          (support/outside-wake-on-branch! running agent-id target "Work your task.")
          (until #(:seon.agent/admission-closed-tx
                   (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
                 1000)))
       (let [message (db/q '[:find (pull ?m [:seon.task/end]) . :in $ ?agent-id
                             :where [?agent :seon.agent/id ?agent-id] [?m :seon.message/from ?agent] [?m :seon.task/end _]]
                           (db/db connection) agent-id)]
         (is (= :done (:seon.task/end message))
             "the proof is green and the change broke no test: a test red on main and one never run do not block"))))))

(deftest ^{:seon/tasks #{'seon.turn/a-landing-acquires-roots-world-when-root-has-no-branch-row}}
  root-without-a-branch-row-lands-in-the-clusters-world
  ;; Root's own context is its cluster: a root row with no home branch (this world's) is the cluster's live
  ;; branch, never a nil branch handed to `seon.cluster.agent/acquire-context!`.
  (support/with-running-agent
   (fn [running _ connection]
     (let [live (get-in (db/db connection) [:config :branch])
           cluster-handle (support/running-cluster running)]
       (is (nil? (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id "root"])))
           "root holds no branch row here")
       (is (= live (get-in (db/db (:seon.db/connection (#'turn/root-execution cluster-handle))) [:config :branch]))
           "root's world is the cluster's live branch")))))
