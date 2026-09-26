(ns seon.sci.program-change-report-test
  "The program-change fast path trusts a settlement report only when its
  after-value ends at its own transaction: Datahike's writer hands every report
  of a batch the batch's final value (datahike/writer.cljc:229-264)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{"361088de3d56"}} a-batched-report-does-not-advance-the-acquired-program
  (support/with-database
   (fn [connection]
     ;; Notes carry no program identity, so an exact report of them is covered;
     ;; the agent row (with its seeded namespace, a program row) is setup.
     (support/transacted! connection (support/agent-tx @connection "report-writer"))
     (let [covers? #'sci.eval/installation-covers-program-change?
           note #(vector {:my.note/id (str "report-note-" %) :my.note/agent [:seon.agent/id "report-writer"]
                          :my.note/content "x"})
           before (db/db connection)
           own (support/transacted! connection (note "own"))
           later (support/transacted! connection (note "later"))
           projection (db/carried-projection (:db-after own))]
       (is (true? (covers? before own [] projection))
           "a report whose after-value is its own transaction names every change")
       (is (not (covers? before (assoc own :db-after (:db-after later)) [] projection))
           "a batched report, whose after-value also holds a later transaction, is not covered")
       (is (not (covers? before later [] projection))
           "nor is a report whose before-value is not the acquired value")))))
