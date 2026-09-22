(ns seon.cluster.source-evidence-test
  "Publication evidence and races, using the source suite's canonical store helpers."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.source-test :as source-fixture]
            [seon.cluster.source :as source]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.test.runner :as runner]
            [seon.test-support :as support])
  (:import [java.util.concurrent CountDownLatch]))

(deftest ^{:seon.test/fixture-observation
           "A database branch cannot isolate evidence retained across a physical-store rebuild and a concurrent publication."
           :seon.test/long "Publish two fixture files, record completions and serialize concurrent result recording with source publication."
           :seon.test/long-ms 10000}
  latest-test-evidence-survives-rebuilding-from-an-older-base
  (#'source-fixture/with-store
    (fn [opened]
      (let [manifest (:seon.fn/manifest opened)
            population 'seon.cluster.source-test/populate-program!
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
        (is (= recorded-commit (:seon.source/commit-id rebuilt))
            "The unchanged digest returns the latest commit, including its test evidence.")
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
                start (CountDownLatch. 1)
                ready (CountDownLatch. 2)
                recording (future
                            (.countDown ready)
                            (support/await-event! start "concurrent recording start")
                            (source/record-results! opened completion))
                publication (future
                              (.countDown ready)
                              (support/await-event! start "concurrent publication start")
                              (#'source-fixture/publish opened @#'source-fixture/digest-c))]
            (try
              (support/await-event! ready "both source writers entered")
              (.countDown start)
              (let [recorded (support/await-event! recording "result recording completed")
                    _ (support/await-event! publication "publication completed")
                    final-db (source/database opened
                                              (:seon.source/commit-id (source/current opened)))]
                (is (= 1 (:seon.test/pass-count (first recorded))) (pr-str recorded))
                (is (= @#'source-fixture/digest-c
                       (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] final-db)))
                (is (number? (db/q '[:find ?run . :in $ ?id :where [?run :seon.test.run/id ?id]]
                                   final-db (:seon.test.run/id run))))
                (is (empty? (#'source-fixture/scratch-branches opened))))
              (finally
                (.countDown start)
                (doseq [request [recording publication]]
                  (when-not (realized? request) (future-cancel request)))))))))))
