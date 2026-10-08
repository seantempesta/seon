(ns seon.sci.program-change-report-test
  "The program-change fast path advances the acquired program only when the
  settlement report's datoms name every change: its write started from the
  committed value the caller names (equal basis), whose program the context
  acquired (`acquired-database?`), and its after-value ends at its own
  transaction (Datahike's writer hands every report of a batch the batch's
  final value, datahike/writer.cljc:229-264)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest a-batched-report-does-not-advance-the-acquired-program
  (support/with-database
   (fn [connection]
     ;; Tasks carry no program identity; the agent entity (with its seeded
     ;; namespace, a program entity) is setup.
     (support/transacted! connection (support/agent-tx @connection "report-writer"))
     (let [covers? #'sci.eval/installation-covers-program-change?
           note #(vector (support/task-row % "x"))
           ctx (support/fork-cluster-ctx connection)
           acquired (db/db connection)
           _ (support/transacted! connection (note "betweenreprt"))
           written-on (db/db connection)
           own (support/transacted! connection (note "ownreportrow"))
           later (support/transacted! connection (note "laterreportr"))
           projection (db/carried-projection (:db-after own))]
       (is (true? (covers? ctx written-on own [] projection))
           (str "a report written on a value holding the acquired program, ending at its own "
                "transaction, names every change (an unrelated commit before it changes nothing)"))
       (is (not (covers? ctx written-on (assoc own :db-after (:db-after later)) [] projection))
           "a batched report, whose after-value also holds a later transaction, is not covered")
       (is (not (covers? ctx acquired own [] projection))
           "nor is a report that did not start from the value the caller names")
       (support/transacted! connection (support/agent-tx @connection "report-program"))
       (let [on (db/db connection)
             after-program (support/transacted! connection (note "afterprogram"))]
         (is (not (covers? ctx on after-program [] projection))
             "nor one written on a value whose program the context did not acquire"))))))
