(ns seon.error.attribution-test
  "One regression per class of attribution at the producer (health plan §3,
  §5): who produced a failure, the event graph one recorded failure writes,
  when its fault is resolved, and the one work queue."
  (:require [clojure.test :refer [deftest is testing]]
            [my.task :as my.task]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error :as error]
            [seon.error.refusal :as refusal]
            [seon.fault :as fault]
            [seon.problems :as problems]
            [seon.task :as task]
            [seon.test-support :as test-support]))

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
           receipts (vec (repeatedly (+ 2 limit) #(fault/fault! environment (refused misuse) context)))
           row (signature-row connection (:seon.error/signature (first receipts)))]
       (is (every? :seon.fault/committed? receipts))
       (testing "the signature names its producer, the occurrence the caller"
         (is (= `misuse (:seon.error/producer row)))
         (is (= 'seon.problems/open-faults (:seon.instrument/fn row)) "the refusing function, never the recorder")
         (is (= #{`misuse} (set (map :seon.error/caller (:seon.error/occurrences row))))))
       (testing "the producer's task answers the signature, riding the same write"
         (is (= [[`misuse]] (mapv :seon.task/must-read (:seon.task/_faults row)))))
       (testing "occurrences are bounded per producer digest; the rest is a count"
         (is (= limit (count (:seon.error/occurrences row))))
         (is (= (+ 2 limit) (reduce + (map :seon.error.occurrence/count (:seon.error/occurrences row))))))))))

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
                                  :seon.error/producer-digest (apply str (repeat 64 "0"))}
                                 context)
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
