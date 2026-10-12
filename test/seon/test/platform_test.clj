(ns seon.test.platform-test
  "A platform run, the members a throwaway JVM loaded from a candidate commit
  answered (`seon.test.platform/run-check!`), recorded on a branch."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test :as test]
            [seon.test.platform :as platform]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.test.platform/a-host-bound-change-has-no-proof-from-the-repl}}
  a-platform-run-recorded-on-a-branch-verifies-its-tests-there
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        branch (keyword (str "platform-test-" (id/id)))
        _ (registry/branch! {:seon.store/store store :seon.store/branch branch
                             :seon.cluster.registry/from (db/commit-id (db/db (:seon.db/connection target)))})
        context (agent/acquire-context! target nil {:seon.agent/branch branch})
        connection (:seon.db/connection context)
        test-symbol 'seon.blob-threshold-test/text-at-threshold-is-inline-over-is-staged
        candidate "092b302d00a0df466556f2d60a025cd06e881442"
        ;; The throwaway JVM is the external effect: its answer is this value.
        checked {:seon.test.run/id "platform-run" :seon.test/passed? true :seon.test.run/git-sha candidate
                 :seon.test.platform/check-ms 1
                 :seon.test/results [{:seon.test/sym test-symbol :seon.test/pass-count 14
                                      :seon.test/fail-count 0 :seon.test/error-count 0
                                      :seon.test.member/began? true :seon.test.member/ended? true}]}]
    (try
      (let [recorded (db/with-branch {:seon.db/connection connection}
                         ;; the branch's own work, as the agent's my.test/check-platform call is
                         #(platform/record! connection (db/db connection) (:seon.cluster/name target) checked))
            database (db/db connection)]
        (is (= [test-symbol] (mapv :seon.test/sym recorded)))
        (is (true? (test/verified? database test-symbol)))
        (is (= candidate (db/q '[:find ?sha . :in $ [?sym ...]
                                 :where [?run :seon.test.run/git-sha ?sha]
                                        [?run :seon.test.run/members ?m] [?m :seon.test.member/symbol ?sym]]
                               database [test-symbol]))
            "the recorded run names the candidate commit it ran on"))
      (finally
        (agent/release-context! context)
        (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))

(deftest ^{:seon/tasks #{'seon.test.platform/a-refused-platform-check-names-no-diagnostic}}
  a-refused-platform-check-carries-its-diagnostic-as-data
  (let [published (platform/published-source)
        database (db/db (:seon.db/connection (support/execution-handle nil)))
        snapshot (str (java.nio.file.Files/createTempDirectory "platform-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        ;; a commit no repository holds: the operator refuses before any JVM boots
        refused (platform/run-check! database published "1111111111111111111111111111111111111111" snapshot
                                     #{'seon.id-test/id-is-unique})
        diagnostic (:seon.test.platform/diagnostic refused)]
    (is (map? published))
    (is (:seon.error/message refused))
    (is (.isFile (java.io.File. ^String (:seon.test.platform/diagnostic-file diagnostic))) "the operator wrote the named file")
    (is (string? (:seon.error/message diagnostic)) "its message is data on the refusal")))
