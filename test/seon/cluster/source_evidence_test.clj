(ns ^{:seon.test/platform "Moving part: source publication evidence and branch-head races."}
    seon.cluster.source-evidence-test
  "Publication evidence and races, using the source suite's canonical store helpers."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.source-test :as source-fixture]
            [seon.cluster.source :as source]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support]))

(deftest ^{:seon.test/long "Complete publications and competing branch heads verify durable evidence."
           :seon.test/long-ms 600000}
  latest-test-evidence-survives-rebuilding-from-an-older-base
  (#'source-fixture/with-store
    (fn [opened]
      (let [manifest @test-support/source-manifest
            population 'seon.cluster/populate-source!
            first-publication (#'source-fixture/publish opened @#'source-fixture/digest-a population
                                       {:seon.fn/manifest manifest})
            first-db (source/database opened (:seon.source/commit-id first-publication))
            test-symbol 'seon.id-test/data-shape-and-explicit-length-determine-identity
            run (assoc (runner/provenance first-db)
                       :seon.test.run/git-sha (apply str (repeat 40 "a")))
            completion {:seon.test/reach-digests (runner/reach-digests first-db [test-symbol])
                        :seon.test.run/provenance run
                        :seon.test/run-basis-t (:seon.test.run/basis-t run)
                        :seon.test/run-at (:seon.test.run/at run)
                        :seon.test.runner/results
                        [{:seon.test/sym test-symbol :seon.test/pass-count 1
                          :seon.test/fail-count 1 :seon.test/error-count 0
                          :seon.test.failure/reports
                          [{:seon.test.failure/type :fail
                            :seon.test.failure/expected "(= 1 2)"
                            :seon.test.failure/actual "(not (= 1 2))"
                            :seon.test.failure/reported-file "id_test.clj"
                            :seon.test.failure/line 42}]}]}
            empty-recording (source/record-results!
                             opened (assoc completion :seon.test.runner/results []))
            empty-head (source/current opened)
            empty-database (source/database opened (:seon.source/commit-id empty-head))
            recorded (source/record-results! opened completion)
            recorded-commit (:seon.source/commit-id (source/current opened))
            rebuilt (#'source-fixture/publish opened @#'source-fixture/digest-a population {:seon.fn/manifest manifest})
            rebuilt-db (source/database opened (:seon.source/commit-id rebuilt))]
        (is (string? (:seon.source/toolchain-digest manifest)))
        (is (= (:seon.source/toolchain-digest manifest)
               (db/q '[:find ?digest . :where [_ :seon.source/toolchain-digest ?digest]] rebuilt-db))
            "The published base records the actual manifest's producer identity.")
        (is (= [] empty-recording))
        (is (not= (:seon.source/commit-id first-publication)
                  (:seon.source/commit-id empty-head)))
        (is (< (:max-tx first-db) (:max-tx empty-database))
            "An empty request still records its fresh run event.")
        (is (= (:seon.test.run/id run)
               (:seon.test.run/id
                (db/pull empty-database [:seon.test.run/id]
                         [:seon.test.run/id (:seon.test.run/id run)]))))
        (is (= 1 (:seon.test/pass-count (first recorded))) (pr-str recorded))
        (is (= (get (:seon.test/reach-digests completion) test-symbol)
               (:seon.test/reach-digest
                (db/pull rebuilt-db [:seon.test/reach-digest]
                         [:seon.test/sym test-symbol]))))
        (is (not= recorded-commit (:seon.source/commit-id first-publication)))
        (is (= @#'source-fixture/digest-a
               (db/q '[:find ?digest . :in $ ?symbol
                       :where [?test :seon.test/sym ?symbol]
                              [?test :seon.test/run ?run]
                              [?run :seon.test.run/program-digest ?digest]]
                     rebuilt-db test-symbol)))
        (is (= (assoc run :seon.test.run/branch :current-src
                         :seon.test.run/tested-branch (:seon.test.run/branch run))
               (dissoc (db/pull rebuilt-db '[*]
                                    [:seon.test.run/id (:seon.test.run/id run)]) :db/id)))
        (let [selector [:seon.test/reach-unknown
                        {:seon.test/failures
                         [:seon.test.failure/id :seon.test.failure/type
                          :seon.test.failure/expected :seon.test.failure/actual
                          :seon.test.failure/line :seon.test.failure/seen-count
                          :seon.test.failure/last-seen-at
                          {:seon.test.failure/file [:seon.fn.file/relative-path]}
                          {:seon.test.failure/first-run [:seon.test.run/id]}
                          {:seon.test.failure/last-run [:seon.test.run/id]}]}]
              before (db/pull (source/database opened recorded-commit) selector
                              [:seon.test/sym test-symbol])
              after (db/pull rebuilt-db selector [:seon.test/sym test-symbol])]
          (is (string? (:seon.test/reach-unknown before)))
          (is (= 1 (count (:seon.test/failures before))))
          (is (= before after) "rebuilding carries membership diagnostics and component evidence"))
        (is (= #{recorded-commit} (d/parent-commit-ids rebuilt-db))
            "the latest published evidence is the parent, not the older :db base")
        (let [changed (#'source-fixture/upsert opened (:seon.source/commit-id rebuilt) @#'source-fixture/digest-b [])
              changed-db (source/database opened (:seon.source/commit-id changed))]
          (is (= (:seon.test.run/id run)
                 (get-in (db/pull changed-db '[{:seon.test/run [:seon.test.run/id]}]
                                  [:seon.test/sym test-symbol])
                         [:seon.test/run :seon.test.run/id])))
          (is (not= @#'source-fixture/digest-b
                    (db/q '[:find ?digest . :in $ ?symbol
                            :where [?test :seon.test/sym ?symbol]
                                   [?test :seon.test/run ?run]
                                   [?run :seon.test.run/program-digest ?digest]]
                          changed-db test-symbol)))
          (is (empty? (#'source-fixture/scratch-branches opened)))
          (is (= #{:db :current-src} (set (registry/roster opened))))
          (let [run (runner/provenance changed-db)
                completion (assoc completion
                                  :seon.test.run/provenance run
                                  :seon.test/run-basis-t (:seon.test.run/basis-t run)
                                  :seon.test/run-at (:seon.test.run/at run))
                commit-results! runner/commit-results!
                advanced (atom nil)
                attempts (atom 0)
                recorded
                (with-redefs [runner/commit-results!
                              (fn [connection completed]
                                (let [result (commit-results! connection completed)]
                                  (when (and (= (:seon.test.run/id run)
                                                (get-in completed [:seon.test.run/provenance :seon.test.run/id]))
                                             (= 1 (swap! attempts inc)))
                                    (reset! advanced
                                            (#'source-fixture/upsert opened (:seon.source/commit-id changed)
                                                    @#'source-fixture/digest-c [])))
                                  result))]
                  (source/record-results! opened completion))]
            (is (= 2 @attempts) "the stale attempt is reapplied once to the new head")
            (is (= 1 (:seon.test/pass-count (first recorded))))
            (is (empty? (#'source-fixture/scratch-branches opened)))
            (let [recorded-db (source/database opened
                                               (:seon.source/commit-id (source/current opened)))]
              (is (= #{(:seon.source/commit-id @advanced)}
                     (d/parent-commit-ids recorded-db))
                  "the successfully recorded evidence descends from the competing publication")
              (is (= @#'source-fixture/digest-c (db/q '[:find ?digest .
                                      :where [_ :seon.source/digest ?digest]]
                                    recorded-db)))
              (is (= run (dissoc (db/pull recorded-db '[*]
                                         [:seon.test.run/id (:seon.test.run/id run)]) :db/id)))
              (let [next-run (runner/provenance recorded-db)
                    next-completion (assoc completion
                                           :seon.test.run/provenance next-run
                                           :seon.test/run-basis-t (:seon.test.run/basis-t next-run)
                                           :seon.test/run-at (:seon.test.run/at next-run))
                    conflicts (atom 0)
                    recorded
                    (with-redefs [runner/commit-results!
                                  (fn [connection completed]
                                    (let [result (commit-results! connection completed)
                                          ordinal (when (= (:seon.test.run/id next-run)
                                                           (get-in completed [:seon.test.run/provenance :seon.test.run/id]))
                                                    (swap! conflicts inc))]
                                      (when (and ordinal (<= ordinal 3))
                                        (let [before (:seon.source/commit-id (source/current opened))
                                              published (#'source-fixture/upsert opened before
                                                                (if (= 1 ordinal) @#'source-fixture/digest-a @#'source-fixture/digest-b) [])]
                                          (is (= (= 3 ordinal)
                                                 (= before (:seon.source/commit-id published)))
                                              "A and B move the head; repeating B with empty rows leaves it unchanged")))
                                      result))]
                      (source/record-results! opened next-completion))
                    final-db (source/database opened
                                              (:seon.source/commit-id (source/current opened)))]
                (is (= 1 (:seon.test/pass-count (first recorded))) (pr-str recorded))
                (is (= 3 @conflicts)
                    "two changed seals conflict; the third, unchanged B seal needs no retry")
                (is (= @#'source-fixture/digest-b (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                     final-db)))
                (is (number? (db/q '[:find ?run . :in $ ?id :where [?run :seon.test.run/id ?id]]
                                   final-db (:seon.test.run/id next-run))))
                (is (empty? (#'source-fixture/scratch-branches opened)))))))))))


(deftest ^{:seon.test/long "One complete publication verifies the entire missing-activation report."
           :seon.test/long-ms 600000}
  publication-refuses-each-missing-activation-prerequisite-before-fork
  (let [missing [{:seon.activation/schema-key :missing/schema}
                 {:seon.activation/required-attribute :missing/attribute}
                 {:seon.activation/config-dial :missing/default}
                 {:seon.activation/lookup-attribute :missing/identity
                  :seon.activation/lookup-value "absent"}
                 {:seon.activation/executable-symbol 'missing/function}]]
    (#'source-fixture/with-store
     (fn [opened]
       (let [data (with-bindings {#'source-fixture/*activation-missing* missing}
                    (#'source-fixture/refusal
                     #(#'source-fixture/publish opened @#'source-fixture/digest-a)))]
         (is (= :seon.cluster.source/activation-incomplete (:seon.cluster.source/rule data)))
         (is (= #{:db} (set (registry/roster opened)))
             "No published source or cluster branch exists after refusal.")
         (is (empty? (#'source-fixture/scratch-branches opened)))
         (is (= missing (:seon.activation/missing data))
             "One refusal retains every missing prerequisite, rather than stopping at the first."))))))
