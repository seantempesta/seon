(ns seon.cluster.agent-arming-test
  "THE CLASS: a created agent whose absence of execution reads as health.

  A creation committed a worker and returned a healthy status, and nothing
  armed it until the next boot, because the cluster's armer is woken only by
  a wake-matching datom and the creation wrote none.

  The fix is a declaration, not a code path: `:seon.agent/id` carries
  `:seon.wake/arms true`, so the commit that creates an agent wakes the ONE
  armer that arms boot-time agents."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as async.flow]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.test-support :as support]))

(defn- running-cluster
  "Stand up one cluster the way boot does: armer graph plus the ONE route.

  Everything an agent graph needs is present BEFORE any agent exists, so
  the only thing under test is whether creating one arms it."
  [connection cluster-name body]
  (support/seed-cluster! connection cluster-name)
  (let [ctx (support/fork-cluster-ctx connection cluster-name)
        environment (support/environment cluster-name connection)
        routing (support/attempt-routing connection)]
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration
                            (select-keys (support/effective-config)
                                         flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment
                     :seon.db/connection connection
                     :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx
                     :seon.flow/work-launcher @launcher
                     :seon.flow/executor
                     (cluster/projection-executor
                      (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            armer (flow/start-graph!
                   {:seon.flow/graph-definition
                    {:procs {:seon.agent/armer
                             {:proc (flow/var-process
                                     #'agent/armer-step :io
                                     (env/carry {:seon.turn.loop/cluster handle
                                                 :seon.agent/routing routing}
                                                environment))}}
                     :conns []
                     :io-exec (:seon.flow/executor handle)}})
            route-key (keyword (str *ns*) cluster-name)]
        (wake/route! {:seon.cluster.wake/connection connection
                      :seon.cluster.wake/channels #(agent/channels routing)
                      :seon.cluster.wake/fenced? #(agent/fenced-route? routing %1 %2)
                      :seon.cluster.wake/armer-channel
                      (:seon.cluster.wake/channel handle)
                      :seon.cluster.wake/render-channel
                      (:seon.render/context-channel handle)
                      :seon.render.web/interest (atom #{})
                      :seon.cluster.wake/key route-key})
        (try
          (body {:seon.turn.loop/cluster handle
                 :seon.agent/routing routing})
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key route-key})
            (async.flow/stop (:seon.flow/graph armer))
            (support/await-event! (:seon.turn.loop/completion handle)
                                  ::armer-stopped)
            (doseq [id (keys (:seon.agent/armed @routing))]
              (agent/disarm! {:seon.agent/routing routing :seon.agent/id id}))
            (doseq [channel [(:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle)
                             (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))

(defn- await-armed!
  "Await the armer's own routing entry for `agent-id` under the backstop."
  [routing agent-id]
  (support/await-event! routing [::armed agent-id]
                        (fn [state]
                          (contains? (:seon.agent/armed state) agent-id))))

(deftest an-agent-created-while-the-cluster-runs-is-armed-by-its-own-creation
  ;; The reported blocker: creation committed, work derivable, nothing
  ;; running. Nothing here calls `arm!`; the assertion is that the armer
  ;; did, woken by the declared arming attribute the creation asserts.
  (support/with-database
   (fn [connection]
     (running-cluster
      connection "late-arming"
      (fn [{handle :seon.turn.loop/cluster routing :seon.agent/routing}]
        (is (contains? (wake/arming-attributes (db/db connection)) :seon.agent/id)
            "agent identity is a declared arming attribute")
        (is (nil? (wake/declarations-refusal (db/db connection)))
            "and the declaration population carries a cluster")
        (is (nil? (wake/arming-refusal (db/db connection)))
            "which `route!` refuses separately, so a missing arming
             declaration can never be mistaken for an idle cluster")
        (let [created (cluster/ensure-entity!
                       connection (:seon.db.process/id handle)
                       {:seon.agent/id "late-worker"
                        :seon.cluster/name "late-arming"
                        :seon.ns/name 'my.agents.late-worker})]
          (is (string? (:seon.agent/id created)))
          (await-armed! routing "late-worker")
          (is (some? (agent/armed routing "late-worker"))
              "the armer armed an agent created while the cluster ran")
          ;; each proc records its own faults: none is stored
          (is (empty? (db/q '[:find ?error :where [?error :seon.error/signature]]
                            (db/db connection)))
              "with no core fault recorded")))))))
