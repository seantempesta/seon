(ns seon.namespace-agent-loop-test
  "The first namespace-agent loop, slice 1: one issue worker started on its
  candidate branch C runs there, armed alone, and leaves the base H untouched."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.issue :as issue]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- digests [database]
  (set (db/q '[:find ?e ?d :where [?e :seon.program/definition-digest ?d]] database)))

(deftest an-issue-worker-started-on-its-candidate-branch-runs-there-alone
  (support/with-database
   (fn [h]
     (support/seed-cluster! h "nsa-loop")
     (let [h-before (db/db h)]
      (with-open [scope (support/closeable
                        (agent/acquire-context! (support/execution-handle h) nil
                                                {:seon.agent/isolate? true})
                        agent/release-context!)]
      (let [c @scope
           conn (:seon.db/connection c)
           branch (:seon.agent/branch c)
           issue-id "nsa-worker-on-candidate"
           worker (id/id [issue-id])
           test-sym (first (sort (db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] h-before)))
           start {:seon.db/connection conn :seon.issue/id issue-id :seon.issue/budget 3
                  :seon.ns/name 'my.agents.nsa-loop :seon.agent/branch branch
                  :seon.config.ai/no-provider true}
           routing (agent/routing)
           faults (async/chan (async/sliding-buffer 16))
           route-key (keyword (str *ns*) issue-id)
           environment (support/environment "nsa-loop" conn)
           ctx (support/fork-cluster-ctx conn "nsa-loop")]
       (swap! routing assoc :seon.agent/fault-channel faults)
       (db/call-with-custody {:seon.db/connection conn} (fn [] ; C's writes are C's work
       (with-open [launcher (support/closeable
                             (flow/start-work-launcher!
                              {:seon.env/environment environment
                               :seon.flow/configuration (select-keys (support/effective-config)
                                                                     flow/flow-workload-attributes)})
                             flow/stop-work-launcher!)]
         (let [handle (support/cluster-handle
                       {:seon.env/environment environment :seon.db/connection conn
                        :seon.cluster/name "nsa-loop" :seon.sci.eval/ctx ctx
                        :seon.flow/work-launcher @launcher
                        :seon.flow/executor (cluster/projection-executor
                                             (:seon.sci.eval/projection-state ctx))
                        :seon.db.process/id cluster/boot-process-identity})
               request {:seon.turn.loop/cluster handle :seon.agent/routing routing
                        :seon.agent/id worker}]
           (is (not= branch (get-in h-before [:config :branch])) "C is its own branch")
           (is (some? test-sym) "the inherited population carries a success test")
           (support/transacted! conn [{:seon.issue/id issue-id :seon.issue/status :open
                                       :seon.issue/title "Start one worker on its branch"
                                       :seon.issue/severity :cleanup
                                       :seon.issue/problem "The worker must run on C."
                                       :seon.issue/tests #{[:seon.test/sym test-sym]}}])
           (is (string? (get-in (issue/start! start) [:seon.issue/agent :seon.agent/id])))
           (is (= issue-id (:seon.issue/assigned-issue-id (issue/start! start)))
               "a repeated start at the same budget refuses through the issue rules")
           (wake/route! {:seon.cluster.wake/connection conn
                         :seon.cluster.wake/channels #(agent/channels routing)
                         :seon.cluster.wake/fenced? #(agent/fenced-route? routing %1 %2)
                         :seon.cluster.wake/armer-channel (:seon.cluster.wake/channel handle)
                         :seon.cluster.wake/render-channel (:seon.render/context-channel handle)
                         :seon.render.web/interest (atom #{})
                         :seon.cluster.wake/fault-channel faults
                         :seon.cluster.wake/key route-key})
           (try
             (agent/arm! request)
             (is (= #{worker} (set (keys (:seon.agent/armed @routing)))) "only the new worker is armed")
             (is (< 1 (count (db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] (db/db conn))))
                 "although C inherits H's agents")
             (is (= branch (:seon.agent/branch (db/pull (db/db conn) [:seon.agent/branch]
                                                        [:seon.agent/id worker])))
                 "the worker's row carries C")
             (is (= branch (get-in @(:seon.db/connection
                                    (:seon.turn.loop/cluster (agent/armed routing worker)))
                                   [:config :branch]))
                 "and its armed execution holds C's connection, no third branch")
             (support/await-event! conn ::assignment-answered
                                   #(empty? (turn/unanswered-wakes % worker {})))
             (is (seq (evaluation/of-agent (db/db conn) worker)) "its virtual reply evaluated on C")
             (is (nil? (async/poll! faults)) "with no fault on the way")
             (finally
               (is (= (db/commit-id h-before) (db/commit-id (db/db h)))
                   "no worker, issue, evaluation, effect or fault fact reached H")
               (is (= (digests h-before) (digests (db/db h))) "H's definition digests hold")
               (agent/disarm! request)
               (wake/unlisten! {:seon.cluster.wake/connection conn
                                :seon.cluster.wake/key route-key})
               (doseq [k [:seon.cluster.wake/channel :seon.render/context-channel
                          :seon.turn.loop/completion]]
                 (async/close! (get handle k)))
               (async/close! faults)))))))))))))
