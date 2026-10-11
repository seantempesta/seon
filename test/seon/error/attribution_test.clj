(ns seon.error.attribution-test
  "One regression per class of attribution at the producer (health plan §3,
  §5): who produced a failure, the event graph one recorded failure writes,
  when its fault is resolved."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.batch]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.branch :as cluster.branch]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.fault :as fault]
            [seon.problems :as problems]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(def context
  {:seon.error/layer ::boundary
   :seon.error/operation `boundary
   :seon.db.process/id "attribution-test"})

(defn world
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

(defn signature-row
  [connection signature]
  (db/pull (db/db connection)
           [:seon.error/producer :seon.instrument/fn
            {:seon.error/occurrences [:seon.error.occurrence/count :seon.error/caller]}
            {:seon.task/_faults [:seon.task/id :seon.task/sym :seon.task/must-read]}]
           [:seon.error/signature signature]))

(defn triaged-task!
  "The task root files for the recorded `signature` through the task writer,
  linked to it as a task answering faults (`:seon.task/faults`); answers the task's handle and symbol."
  [connection signature producer]
  (let [{handle :seon.task/id} (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                              :seon.task/ns (symbol (namespace producer))
                                              :seon.task/name (str "fix-" (name producer)) :seon.task/kind :function
                                              :seon.task/intent "Make the producer stop failing."
                                              :seon.task/must-read #{producer}})]
    (test-support/transacted! connection [[:db/add [:seon.task/id handle] :seon.task/faults [:seon.error/signature signature]]])
    [handle (:seon.task/sym (db/pull (db/db connection) [:seon.task/sym] [:seon.task/id handle]))]))

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
       (testing "the recording writes no task: root or an agent triages it"
         (is (empty? (:seon.task/_faults row))))
       (testing "occurrences are bounded per producer digest"
         (is (= limit (count (:seon.error/occurrences row)))))
       (testing "a repeat past the cap makes no commit; its count rides the next commit"
         (is (= basis unwritten) "two repeats wrote nothing")
         (is (every? :seon.fault/folded? repeats))
         (is (= limit (stored-sum)) "the held count is not stored yet")
         (fault/fault! environment (ex-info "another failure" {}) context)
         (is (= (+ 2 limit) (stored-sum)) "the next fault's commit carried it"))))))

(deftest a-fault-in-an-agents-world-is-copied-to-the-cluster
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
               (is (= `misuse (:seon.error/producer (signature-row cluster (:seon.error/signature receipt))))
                   "the cluster copy names the producer, where root reads it")
               (testing "a definition only the agent's branch holds: the copy keeps the digest that ran"
                 ;; the fixture declares its namespace: an ambient my.probe row
                 ;; in the captured world is not the fixture's
                 (test-support/transacted!
                  attempt [(test-support/namespace-entity (db/db attempt) 'my.probe)
                           (test-support/program-fn-entity (db/db attempt) 'my.probe/make-row "(defn make-row [] 1)"
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

(deftest only-a-compiled-producer-not-redefined-by-the-batch-is-a-fault
  ;; A failure produced by the agent's own definition (or redefinition) is its
  ;; mistake: delivered to it, never recorded as a core fault.
  (let [error (fn [producer] (ex-info "probe" {:seon.batch/error-value {:seon.error/producer producer :seon.error/message "probe"}}))
        batch {:seon.batch/entries [{:seon.batch/error (error `misuse) :seon.batch/ordinal 0}
                                    {:seon.batch/ordinal 1 :seon.batch/change {:seon.batch/entities [{:seon.fn/sym `misuse}]}}
                                    {:seon.batch/error (error `misuse) :seon.batch/ordinal 2}
                                    {:seon.batch/error (error 'probe/f) :seon.batch/ordinal 3}]}]
    (is (= [0] (mapv :seon.batch/ordinal (#'seon.batch/produced-failures batch)))
        "the compiled producer before the batch redefines it; an agent's definition never")))
