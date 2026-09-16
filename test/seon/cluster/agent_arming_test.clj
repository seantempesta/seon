(ns seon.cluster.agent-arming-test
  "THE CLASS: a created agent whose absence of execution reads as health.

  `seon.issue/start!` created a worker, its plan and its opening in one
  transaction, returned a healthy status, and `seon.turn/next-agent-work`
  answered `:generate` — and nothing ran until the next boot, because the
  cluster's armer is woken only by a wake-matching datom and the creation
  wrote none. Calling `arm!` by hand unblocked it instantly, which is what
  every fixture and every trial session had been doing.

  Both halves are declarations, not code paths:

  1. `:seon.agent/id` carries `:seon.wake/arms true`, so the commit that
     creates an agent wakes the ONE armer that arms boot-time agents.
  2. `:seon.issue/agent` carries `:seon.wake/listen true`, so the
     assignment datom IS the worker's first wake — no synthetic message."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as async.flow]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.env :as env]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.issue :as issue]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- running-cluster
  "Stand up one cluster the way boot does: armer graph plus the ONE route.

  Everything an agent graph needs is present BEFORE any agent exists, so
  the only thing under test is whether creating one arms it."
  [connection cluster-name body]
  (support/seed-cluster! connection cluster-name)
  (let [ctx (support/fork-cluster-ctx connection cluster-name)
        environment (support/environment cluster-name connection)
        routing (agent/routing)
        faults (async/chan (async/sliding-buffer 16))]
    (swap! routing assoc :seon.agent/fault-channel faults)
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
                     :io-exec (:seon.flow/executor handle)}
                    :seon.flow/joins
                    {::faults
                     #(flow/join-error-fanout!
                       {:seon.flow/started (:seon.flow/started %)
                        :seon.flow/fault-channel faults
                        :seon.flow/tag {}})}})
            route-key (keyword (str *ns*) cluster-name)]
        (wake/route! {:seon.cluster.wake/connection connection
                      :seon.cluster.wake/channels #(agent/channels routing)
                      :seon.cluster.wake/fenced? #(agent/fenced-route? routing %1 %2)
                      :seon.cluster.wake/armer-channel
                      (:seon.cluster.wake/channel handle)
                      :seon.cluster.wake/render-channel
                      (:seon.render/context-channel handle)
                      :seon.render.web/interest (atom #{})
                      :seon.cluster.wake/fault-channel faults
                      :seon.cluster.wake/key route-key})
        (try
          (body {:seon.turn.loop/cluster handle
                 :seon.agent/routing routing
                 :seon.agent/fault-channel faults})
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key route-key})
            (async.flow/stop (:seon.flow/graph armer))
            (support/await-event! (:seon.turn.loop/completion handle)
                                  ::armer-stopped)
            (doseq [id (keys (:seon.agent/armed @routing))]
              (agent/disarm! {:seon.agent/routing routing :seon.agent/id id}))
            (doseq [channel [faults
                             (:seon.cluster.wake/channel handle)
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
      (fn [{handle :seon.turn.loop/cluster routing :seon.agent/routing
            faults :seon.agent/fault-channel}]
        (is (= #{:seon.agent/id} (wake/arming-attributes (db/db connection)))
            "agent identity is the declared arming attribute")
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
          (is (nil? (:seon.error/kind created)) (pr-str created))
          (await-armed! routing "late-worker")
          (is (some? (agent/armed routing "late-worker"))
              "the armer armed an agent created while the cluster ran")
          ;; ARMED IS NOT ENOUGH: the reported symptom was derivable work
          ;; nobody executed, so the terminal fact is the agent's own
          ;; stored evaluation, not the routing entry.
          (support/await-event!
           connection ::late-worker-turned
           (fn [database]
             (and (seq (evaluation/of-agent database "late-worker"))
                  (nil? (turn/open-for-agent database
                                             [:seon.agent/id "late-worker"])))))
          (is (seq (evaluation/of-agent (db/db connection) "late-worker"))
              "and its turn loop advanced without a boot or a hand arm")
          (is (nil? (async/poll! faults)) "with no fault on the way")))))))

(deftest starting-an-issue-leaves-one-unanswered-wake-its-first-reply-answers
  ;; The second half of the same blocker: an armed worker still never took
  ;; a provider turn, because `start!` left nothing unanswered and the
  ;; trials had to send it a synthetic message. The assignment IS the wake.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "issue-wake")
     (let [issue-id "start-arms-and-wakes"
           agent-id (id/id [issue-id])
           test-sym (first (sort (db/q '[:find [?s ...]
                                         :where [_ :seon.test/sym ?s]]
                                       (db/db connection))))]
       (is (some? test-sym) "the canonical population carries test entities")
       (db/transact! connection
                     [{:seon.issue/id issue-id
                       :seon.issue/title "Verify the assignment wakes its worker"
                       :seon.issue/status :open
                       :seon.issue/severity :blocker
                       :seon.issue/problem "The worker must turn on assignment."
                       :seon.issue/tests #{[:seon.test/sym test-sym]}}])
       (testing "the assignment attribute is declared a turn-opening wake"
         (let [database (db/db connection)]
           (is (contains? (wake/wake-attributes database) :seon.issue/agent))
           (is (contains? (wake/turn-opening-attributes database)
                          :seon.issue/agent))
           (is (not (contains? (wake/inside-attributes database)
                               :seon.issue/agent))
               "an assignment arrives from outside, so it refills the bound")
           (is (not (contains? (wake/unindexed-listened-attributes database)
                               :seon.issue/agent))
               "and it is in :avet, or every seek would read as absent")))
       ;; BUDGET 3, NOT 1. Measured 2026-09-16: the generated opening
       ;; consumes the episode bound, so a worker started with budget 1 has
       ;; no ordinary turn left and can never answer its own assignment —
       ;; filed as `a-workers-generated-opening-spends-its-issue-budget`.
       ;; This regression is about the wake, so it does not stand on that.
       (let [started (issue/start! {:seon.db/connection connection
                                    :seon.issue/id issue-id
                                    :seon.issue/budget 3
                                    :seon.ns/name 'my.agents.issue-wake
                                    :seon.config.ai/no-provider true})]
         (is (nil? (:seon.error/kind started)) (pr-str started))
         (let [wakes (turn/unanswered-wakes (db/db connection) agent-id {})]
           (is (= 1 (count wakes)) (pr-str wakes))
           (is (= :seon.issue/agent (:seon.wake/attribute (first wakes)))
               "the assignment datom is the worker's one unanswered wake")
           (is (= (:db/id (db/pull (db/db connection) [:db/id]
                                   [:seon.issue/id issue-id]))
                  (:db/id (first wakes)))
               "carried by the issue entity itself"))
         ;; NOTHING IS SUBMITTED HERE. The worker is armed by its own
         ;; creation and the assignment is its only wake, so its loop must
         ;; reach an accepted reply on the no-provider path by itself —
         ;; that is the whole claim.
         (running-cluster
          connection "issue-wake"
          (fn [{routing :seon.agent/routing}]
            (await-armed! routing agent-id)
            ;; ONE await, on the ONE terminal fact. Awaiting the opening's
            ;; closure first and the answer second gave the second await its
            ;; own clock and made the assertion between them race the loop's
            ;; next turn; measured, the whole sequence settles well inside
            ;; one backstop.
            (support/await-event!
             connection ::issue-assignment-answered
             (fn [database]
               (empty? (turn/unanswered-wakes database agent-id {}))))
            (let [database (db/db connection)
                  opening (db/pull database
                                   '[:seon.turn/closed-tx :seon.turn/attempts]
                                   [:seon.turn/id (id/id [:seon.issue/opening
                                                          issue-id])])]
              (is (empty? (turn/unanswered-wakes database agent-id {}))
                  "the worker's first accepted reply answers the assignment")
              (is (some? (:seon.turn/closed-tx opening))
                  "system turn 0 stored the opening and closed")
              (is (nil? (:seon.turn/attempts opening))
                  "and attempted no provider, so it answered nothing itself")
              (is (seq (evaluation/of-agent database agent-id))
                  "the worker got there by turning, not by being handed a reply")
              (is (< 1 (count (db/q '[:find [?t ...] :in $ ?a :where
                                      [?agent :seon.agent/id ?a]
                                      [?t :seon.turn/agent ?agent]]
                                    database agent-id)))
                  "a second, ordinary turn is what answered the assignment")))))))))
