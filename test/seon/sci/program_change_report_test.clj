(ns seon.sci.program-change-report-test
  "The program-change fast path trusts a settlement report only when its
  after-value ends at its own transaction (Datahike's writer hands every report
  of a batch the batch's final value, datahike/writer.cljc:229-264) and its
  before-value holds the acquired program (equal acquisition-attribute
  revisions, as `acquired-database?` compares)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{"361088de3d56"}} a-batched-report-does-not-advance-the-acquired-program
  (support/with-database
   (fn [connection]
     ;; Notes carry no program identity; the agent row (with its seeded
     ;; namespace, a program row) is setup.
     (support/transacted! connection (support/agent-tx @connection "report-writer"))
     (let [covers? #'sci.eval/installation-covers-program-change?
           note #(vector {:my.note/id (str "report-note-" %) :my.note/agent [:seon.agent/id "report-writer"]
                          :my.note/content "x"})
           before (db/db connection)
           _ (support/transacted! connection (note "between"))
           own (support/transacted! connection (note "own"))
           later (support/transacted! connection (note "later"))
           projection (db/carried-projection (:db-after own))]
       (is (true? (covers? before own [] projection))
           (str "a report whose after-value is its own transaction names every change, even after "
                "an unrelated commit left the acquisition revisions unchanged"))
       (is (not (covers? before (assoc own :db-after (:db-after later)) [] projection))
           "a batched report, whose after-value also holds a later transaction, is not covered")
       ;; a program change between the acquired value and the report
       (support/transacted! connection (support/agent-tx @connection "report-program"))
       (is (not (covers? before (support/transacted! connection (note "after-program")) [] projection))
           "nor is a report whose before-value no longer holds the acquired program")))))
