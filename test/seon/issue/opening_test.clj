(ns seon.issue.opening-test
  "An issue worker's opening is data: what decides done, what its dialled
  candidate adds, and the reads offered; its read refreshes."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.issue.opening :as opening]
            [seon.test-support :as support]))

(deftest an-issue-opening-is-data-and-its-read-refreshes
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "worker"))
      (support/transacted! connection
                           [{:seon.issue/id "opening-probe" :seon.issue/title "Probe the opening"
                             :seon.issue/status :open :seon.issue/severity :cleanup
                             :seon.issue/problem "Make the probe pass."
                             :seon.issue/tests #{[:seon.test/sym 'seon.issue-test/issue-worker-creation-is-atomic]}
                             :seon.issue/agent [:seon.agent/id "worker"]}])
      (let [unit {:seon.db/db @connection :seon.agent/id "worker"}
            captured (atom [])
            shown (binding [db/*read-evidence-sink* captured] (opening/render-ai unit))
            evidence (db/read-evidence @captured)]
        (is (= [{:seon.issue/id "opening-probe" :seon.issue/title "Probe the opening"
                 :seon.issue.opening/done-when-tests
                 {'seon.issue-test/issue-worker-creation-is-atomic :unrun}
                 :seon.render/operations ['(my.issue/status {:seon.issue/id "opening-probe"})]}]
               shown)
            "an undialled worker sees what decides done and the status read")
        (is (= '(seon.issue.opening/openings (seon.db/db) "worker") (opening/render-form unit))
            "the form face is the read that finds the openings again")
        (is (= [] (opening/render-ai (assoc unit :seon.agent/id "nobody"))) "no issue is an empty vector")
        (support/transacted! connection
                             [{:seon.issue/id "opening-second" :seon.issue/title "A second issue"
                               :seon.issue/status :open :seon.issue/severity :cleanup
                               :seon.issue/problem "Another."
                               :seon.issue/tests #{[:seon.test/sym 'seon.issue-test/issue-worker-creation-is-atomic]}
                               :seon.issue/agent [:seon.agent/id "worker"]}])
        (is (false? (db/read-evidence-current? @connection evidence))
            "a new assignment changes the shown read")))))
