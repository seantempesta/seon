(ns seon.error.attribution-test
  "One regression per class of attribution at the producer (health plan §3,
  §5): who produced a failure, the event graph one recorded failure writes,
  when its fault is resolved, and the one work queue."
  (:require [clojure.test :refer [deftest is testing]]
            [my.task :as my.task]
            [seon.batch]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.branch :as cluster.branch]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error :as error]
            [seon.error.refusal :as refusal]
            [seon.fault :as fault]
            [seon.problems :as problems]
            [seon.sci.eval :as sci.eval]
            [seon.task :as task]
            [seon.test-support :as test-support]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as turn.world]))

(def ^:private context
  {:seon.error/layer ::boundary
   :seon.error/operation `boundary
   :seon.db.process/id "attribution-test"})

(defn- world
  "The environment a boundary holds for one fixture cluster, with root seeded as boot does."
  [connection cluster-name]
  (test-support/seed-cluster! connection cluster-name {})
  (cluster/seed-root-agent! connection cluster-name cluster/boot-process-identity)
  (env/environment {:seon.boot/cluster-name cluster-name
                    :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps (config/effective (db/db connection) cluster-name))}))

(defn- misuse
  "Hands an armed contract a map where it declares a vector: an input violation this function produced."
  []
  (problems/open-faults {:seon.error/at (java.util.Date.)} {}))

(defn- refused
  "The armed refusal `f` throws."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo refusal refusal)))

(deftest blame-follows-the-producer
  (testing "an input violation names the compiled caller that passed the value"
    (let [refusal (refused misuse)]
      (is (= `misuse (:seon.error/producer (refusal/producer (ex-data refusal) refusal))))
      (is (= `misuse (:seon.error/caller (ex-data refusal))) "the armed wrapper names its caller")))
  (testing "an interpreted caller is the first SCI frame that is not the refusing function"
    (is (= 'my.probe/make-row
           (:seon.error/producer
            (refusal/producer {:seon.instrument/fn 'seon.problems/open-faults :seon.instrument/arm :input
                               :seon.sci.eval/stacktrace [['seon.problems/open-faults 137 1]
                                                          ['my.probe/make-row 1 20] ['my.probe 1 1]]}
                              nil)))))
  (testing "the agent's own form produced it: no definition is blamed"
    (is (= {} (refusal/producer {:seon.instrument/fn 'seon.problems/open-faults :seon.instrument/arm :input
                                 :seon.sci.eval/stacktrace [['seon.problems/open-faults 137 1] ['my.probe 1 1]]}
                                nil))))
  (testing "an output violation names the function that returned the value"
    (is (= 'seon.problems/open-faults
           (:seon.error/producer
            (refusal/producer {:seon.instrument/fn 'seon.problems/open-faults :seon.instrument/arm :output} nil))))))

(defn- signature-row
  [connection signature]
  (db/pull (db/db connection)
           [:seon.error/producer :seon.instrument/fn
            {:seon.error/occurrences [:seon.error.occurrence/count :seon.error/caller]}
            {:seon.task/_faults [:seon.task/id :seon.task/must-read]}]
           [:seon.error/signature signature]))

(deftest a-recorded-violation-lands-on-the-event-graph
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "attribution-graph")
           limit (:seon.config.error/recurrence-limit (config/effective (db/db connection) "attribution-graph"))
           capped (vec (repeatedly limit #(fault/fault! environment (refused misuse) context)))
           basis (db/basis-t (db/db connection))
           repeats (vec (repeatedly 2 #(fault/fault! environment (refused misuse) context)))
           unwritten (db/basis-t (db/db connection))
           receipts (into capped repeats)
           row (signature-row connection (:seon.error/signature (first receipts)))
           stored-sum #(reduce + (map :seon.error.occurrence/count
                                      (:seon.error/occurrences (signature-row connection (:seon.error/signature (first receipts))))))]
       (is (every? :seon.fault/committed? receipts))
       (testing "the signature names its producer, the occurrence the caller"
         (is (= `misuse (:seon.error/producer row)))
         (is (= 'seon.problems/open-faults (:seon.instrument/fn row)) "the refusing function, never the recorder")
         (is (= #{`misuse} (set (map :seon.error/caller (:seon.error/occurrences row))))))
       (testing "the producer's task answers the signature, riding the same write"
         (is (= [[`misuse]] (mapv :seon.task/must-read (:seon.task/_faults row)))))
       (testing "occurrences are bounded per producer digest"
         (is (= limit (count (:seon.error/occurrences row)))))
       (testing "a repeat past the cap makes no commit; its count rides the next commit"
         (is (= basis unwritten) "two repeats wrote nothing")
         (is (every? :seon.fault/folded? repeats))
         (is (= limit (stored-sum)) "the held count is not stored yet")
         (fault/fault! environment (ex-info "another failure" {}) context)
         (is (= (+ 2 limit) (stored-sum)) "the next fault's commit carried it"))))))

(deftest a-fault-its-task-answers-stays-open-until-the-proof-holds
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "attribution-resolution")
           ;; a declared error value whose producer stated the digest that ran:
           ;; the program's current digest differs, as after a fix
           receipt (fault/fault! environment
                                 {:seon.error/at (java.util.Date.) :seon.error/layer ::boundary
                                  :seon.error/operation 'seon.problems/problems :seon.error/message "refused"
                                  :seon.error/producer 'seon.problems/problems
                                  :seon.error/producer-digest (apply str (repeat 64 "0"))
                                  :seon.task/refused :unproven :seon.task/holder 'seon.problems}
                                 ;; a declared value names its extension of the base
                                 (assoc context :seon.error/declared-schema :seon.task/refused-error))
           signature (:seon.error/signature receipt)
           handle (-> (signature-row connection signature) :seon.task/_faults first :seon.task/id)]
       (is (string? handle) "the producer's task answers the fault")
       (is (nil? (error/resolution (db/db connection) signature))
           "a changed digest without a red-then-green proof leaves it open")
       (is (nil? (task/resolution (db/db connection) handle)) "so its task stays open")))))

(deftest the-queue-offers-open-tasks-and-a-held-task-refuses-another-claim
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "attribution-queue")
           receipt (fault/fault! environment (refused misuse) context)
           database (db/db connection)
           handle (-> (signature-row connection (:seon.error/signature receipt)) :seon.task/_faults first :seon.task/id)
           queued (some #(when (= handle (:seon.task/id %)) %) (my.task/open database))]
       (is (= 1 (:seon.task/occurrences queued)))
       (is (= [(list 'my.task/claim! {:seon.task/assigned handle})] (:seon.render/operations queued)))
       (let [claimed (my.task/claim! {:seon.db/connection connection :seon.agent/id "root" :seon.task/assigned handle})]
         (is (= handle (:seon.task/assigned claimed)))
         (is (not-any? #(= handle (:seon.task/id %)) (my.task/open (db/db connection))) "a held task leaves the queue")
         (is (= :claimed (:seon.task/refused (my.task/claim! {:seon.db/connection connection :seon.agent/id "mcp/other"
                                                               :seon.task/assigned handle})))))))))

(deftest a-fault-in-an-agents-world-carries-its-task-to-the-cluster
  ;; The fixture branch is the agent's; a sibling `:cluster-<name>` branch of the
  ;; same store is the cluster, open as a running one's (seon.fault-test's
  ;; copy fixture). The boundary names no agent: the world does.
  (let [cluster-name (str "attribution-home-" (random-uuid))
        home (cluster.branch/cluster-branch cluster-name)]
    (test-support/with-database
     (fn [attempt]
       (let [origin (get-in (db/db attempt) [:config :branch])
             store (:seon.store/store (test-support/execution-handle attempt))]
         (test-support/apply-config! attempt cluster-name {})
         (test-support/transacted!
          attempt (into (test-support/agents-tx @attempt ["root"])
                        (agent/creation-tx {:seon.agent/id "worker" :seon.ns/name 'my.agents.worker
                                            :seon.cluster/name cluster-name :seon.agent/branch origin})))
         (registry/branch! {:seon.store/store store :seon.store/branch home
                            :seon.cluster.registry/from (db/commit-id (db/db attempt))})
         (let [cluster (db/carry-connection-projection-state!
                        (store/open-branch! store home)
                        (:seon.sci.eval/projection-state (meta attempt)))]
           (try
             (let [receipt (binding [seon.batch/*world* {:seon.agent/id "worker"}]
                             (db/call-with-custody
                              {:seon.db/connection cluster}
                              #(fault/fault! (env/environment
                                              {:seon.boot/cluster-name cluster-name
                                               :seon.db/connection cluster
                                               :seon.sci.admit/caps (config/result-caps config/defaults)})
                                             (refused misuse)
                                             context)))]
               (is (true? (:seon.fault/committed? receipt)))
               (is (= [[`misuse]] (mapv :seon.task/must-read (:seon.task/_faults (signature-row cluster (:seon.error/signature receipt)))))
                   "the cluster copy carries the producer's task, where a claim is written")
               (testing "a definition only the agent's branch holds: the copy keeps the digest that ran"
                 (test-support/transacted!
                  attempt [(test-support/program-fn-entity (db/db attempt) 'my.probe/make-row "(defn make-row [] 1)"
                                                           {:seon.schema.admission/source :agent})])
                 (let [ran (:seon.program/definition-digest
                            (db/pull (db/db attempt) [:seon.program/definition-digest] [:seon.fn/sym 'my.probe/make-row]))
                       interpreted (binding [seon.batch/*world* {:seon.agent/id "worker"}]
                                     (fault/fault! (env/environment
                                                    {:seon.boot/cluster-name cluster-name :seon.db/connection attempt
                                                     :seon.sci.admit/caps (config/result-caps config/defaults)})
                                                   (ex-info "refused" {:seon.error/at (java.util.Date.) :seon.error/layer ::boundary
                                                                       :seon.error/operation 'seon.problems/open-faults
                                                                       :seon.error/producer 'my.probe/make-row})
                                                   context))
                       copied (db/pull (db/db cluster) [{:seon.error/occurrences [:seon.error/producer-digest]}]
                                       [:seon.error/signature (:seon.error/signature interpreted)])]
                   (is (string? ran))
                   (is (nil? (db/pull (db/db cluster) [:db/id] [:seon.fn/sym 'my.probe/make-row])) "the cluster has no such definition")
                   (is (= [ran] (mapv :seon.error/producer-digest (:seon.error/occurrences copied)))))))
             (finally
               (store/release-branch! cluster)
               (registry/retire-branch! {:seon.store/store store :seon.store/branch home})))))))))

(deftest a-definitions-failure-in-an-evaluation-is-its-fault-and-task
  ;; Health loop E1 (fault 103511's class): an evaluation whose definition
  ;; handed an armed contract a bad value. The agent reads the producer in
  ;; its error value; the fold records it as that definition's fault, whose
  ;; task the queue offers. An agent's own form producing it is no fault.
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "attribution-evaluation")
           run (fn [text]
                 (seon.batch/evaluate-batch
                  (dissoc (turn.batch/code-request
                           {:seon.batch/world (turn.world/agent-world
                                               {:seon.db/connection connection :seon.cluster/name "attribution-evaluation"
                                                :seon.env/environment environment
                                                :seon.sci.eval/base-ctx (sci.eval/base-ctx (db/db connection))}
                                               {:seon.agent/id "root" :seon.db.process/id "attribution-test"})
                            :seon.batch/start-ns 'user
                            :seon.batch/text text})
                          :seon.batch/settle)))
           faults-of (fn [producer]
                       (db/q '[:find [?signature ...] :in $ ?producer
                               :where [?e :seon.error/producer ?producer] [?e :seon.error/signature ?signature]]
                             (db/db connection) producer))
           batch (run "(defn make-row [] (seon.problems/open-faults {:seon.error/at (java.util.Date.)} {})) (make-row)")
           error (:seon.batch/error-value (ex-data (get-in batch [:seon.batch/entries 1 :seon.batch/error])))
           [signature] (faults-of 'user/make-row)]
       (testing "the error value names the definition that produced the bad value"
         (is (= 'user/make-row (:seon.error/producer error)))
         (is (= 'seon.problems/open-faults (:seon.instrument/fn error))))
       (testing "the fold records it as the producer's fault, answered by its task"
         (is (string? signature))
         (is (= 'user/make-row (:seon.error/producer (first (problems/open-faults (db/db connection) signature))))
             "the open fault names who it counts against")
         (is (= [['user/make-row]] (mapv :seon.task/must-read (:seon.task/_faults (signature-row connection signature)))))
         (is (some #(= (:seon.task/id (first (:seon.task/_faults (signature-row connection signature)))) (:seon.task/id %))
                   (my.task/open (db/db connection)))))
       (testing "the agent's own form is its mistake, returned and never recorded"
         (let [before (db/basis-t (db/db connection))]
           (run "(seon.problems/open-faults {:seon.error/at (java.util.Date.)} {})")
           (is (= before (db/basis-t (db/db connection))))))))))
