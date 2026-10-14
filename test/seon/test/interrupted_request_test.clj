(ns seon.test.interrupted-request-test
  "A request that throws leaves no member open, and open test evidence makes
  only its own problem family unknown."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [seon.db :as db]
            [seon.problems :as problems]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(def ^:private finished 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol)
(def ^:private interrupted 'seon.id-test/a-host-object-has-no-identity)
(def ^:private red 'seon.id-test/map-and-set-order-never-changes-an-identity)

(defn- outcome [test-symbol fails]
  {:seon.test/sym test-symbol :seon.test.member/began? true :seon.test.member/ended? true
   :seon.test/pass-count (- 1 fails) :seon.test/fail-count fails :seon.test/error-count 0})

(defn- admitted-run!
  "Admit `members` in one run and record `results`; by default `finished` green,
  `red` red and `interrupted` open."
  ([connection]
   (admitted-run! connection [finished red interrupted] [(outcome finished 0) (outcome red 1)]))
  ([connection members results]
  (let [handle (support/execution-handle connection)
        database (db/db connection)
        provenance (runner/provenance database)
        admission {:seon.test.run/provenance provenance
                   :seon.test.run/cluster [:seon.cluster/name (:seon.cluster/name handle)]
                   :seon.test.run/input-digest
                   (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]] database)
                   :seon.test.run/policy :named
                   :seon.test.run/include-long? false
                   :seon.test.run/members
                   (mapv #(hash-map :seon.test.member/symbol % :seon.test.member/reasons #{:named})
                         members)}
        completion (fn [results]
                     {:seon.db/db database
                      :seon.test.runner/results results
                      :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                      :seon.test/run-at (:seon.test.run/at provenance)
                      :seon.test.run/provenance provenance
                      :seon.test.run/terminated? true})
        _ (support/transacted! connection [[:db.fn/call sut/admit-run admission]])
        committed (runner/commit-results! connection (completion results))]
    (is (not (:seon.error/at committed)))
    {:seon.test.run/id (:seon.test.run/id provenance) ::completion (completion [])})))

(deftest ^{:seon.test/long "admit-run measured 13.7-15.3 s on default pid 90963 (2026-09-23), the rest under 1 s: it derives the program digest from every program entity changed since the source seal (docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md)." :seon.test/long-ms 30000}
  a-thrown-request-stops-its-run-and-records-no-result-for-its-unfinished-members
  (support/with-database
   (fn [connection]
     (let [{run-id :seon.test.run/id completion ::completion} (admitted-run! connection)
           failure (ex-info "The test world's program overlay was refused" {::probe true}
                            (IllegalStateException. "the underlying cause"))
           recorded (runner/record-interrupted! connection completion failure)
           database (db/db connection)
           results (runner/run-results database run-id)
           latest (first (runner/latest-results database [interrupted]))]
       (is (= [] recorded) "the open member gains no recorded result")
       (is (= :request-threw
              (:seon.test.run/stopped (db/pull database [:seon.test.run/stopped] [:seon.test.run/id run-id])))
           "the throw is the run's, as its stop cause")
       (is (:seon.error/at results) "the run's evidence is incomplete, never a red member")
       (is (:seon.error/at latest) "the open member reads as an unknown")
       (is (not-any? #(contains? latest %) [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count])
           "an unknown carries no counts")
       (is (false? (runner/designed-red? latest)) "a member that never ran is no designed red")
       (is (= 0 (:seon.test/error-count (first (runner/latest-results database [finished]))))
           "a recorded member is untouched")
       (is (= [] (runner/record-interrupted! connection completion failure))
           "a second observation records no result")))))

(deftest ^{:seon.test/long "admit-run measured 13.7-15.3 s on default pid 90963 (2026-09-23), the rest under 1 s: it derives the program digest from every program entity changed since the source seal (docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md)." :seon.test/long-ms 30000}
  one-incomplete-member-is-its-own-unknown-and-its-run-mates-stay-readable
  (support/with-database
   (fn [connection]
     (let [{run-id :seon.test.run/id} (admitted-run! connection)
           database (db/db connection)
           never 'seon.id-test/no-execution-is-recorded-for-this-symbol
           registry {:registry (:seon.schema.projection/registry (db/carried-projection database))}
           latest (runner/latest-results database [finished red interrupted never])
           by-symbol (group-by (some-fn :seon.test/sym :seon.test/error-test-symbol) latest)
           unknown (first (by-symbol interrupted))
           found (problems/problems database {})]
       (is (m/validate :seon.test/latest-results latest registry))
       (is (= #{finished interrupted red} (set (keys by-symbol))) "never-run is absent")
       (is (= [1 0 0] (map (first (by-symbol finished)) [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count])))
       (is (= 1 (:seon.test/fail-count (first (by-symbol red)))))
       (is (m/validate :seon.test/result-unknown unknown registry))
       (is (= [:seon.test.run/id run-id] (:seon.test/run unknown)) "the unknown keeps its run")
       (is (not-any? #(contains? unknown %) [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count])
           "an unknown carries no counts")
       (is (= (dissoc unknown :seon.error/at)
              (dissoc (sut/recorded-result database interrupted) :seon.error/at)))
       (is (false? (sut/verified? database interrupted)) "unknown never verifies")
       (is (true? (#'sut/green? {:seon.test/sym finished :seon.test/pass-count 1
                                 :seon.test/fail-count 0 :seon.test/error-count 0}))
           "a captured result, not yet recorded at a basis, is judged by its counts")
       ;; The branch inherits the store's problems; judge only this fixture's members.
       (let [members #{finished red interrupted}
             own (fn [k entities] (set/intersection members (set (map k entities))))]
         (is (= #{red} (own :seon.test/sym (:seon.problems/failed-tests found)))
             "the red stays visible; the unknown and the green are not failed")
         (is (= #{interrupted}
                (own :seon.test/error-test-symbol (:seon.problems/failed-tests-unknown found)))
             "the open member alone is unknown"))
       (is (str/includes? (problems/ai-prose found) (str interrupted)) "the unknown is shown by name")))))

(deftest ^{:seon.test/long "admit-run measured 13.7-15.3 s on default pid 90963 (2026-09-23), the rest under 1 s: it derives the program digest from every program entity changed since the source seal (docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md)." :seon.test/long-ms 30000}
  a-huge-assertion-message-is-a-blob-and-readiness-reads-only-counts
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "default")
     (let [{run-id :seon.test.run/id completion ::completion} (admitted-run! connection)
           huge (apply str (repeat (* 10 1024 1024) \x))
           committed (runner/commit-results!
                      connection
                      (assoc completion :seon.test.runner/results
                             [{:seon.test/sym interrupted
                               :seon.test.member/began? true :seon.test.member/ended? true
                               :seon.test/pass-count 0 :seon.test/fail-count 1 :seon.test/error-count 0
                               :seon.test/failure-message huge}]))
           database (db/db connection)
           threshold (db/q '[:find ?n . :where [_ :seon.config.eval.result/blob-threshold ?n]] database)
           longest (fn [query] (reduce max 0 (map count (db/q query database))))
           threads (java.lang.management.ManagementFactory/getThreadMXBean)
           thread (.getId (Thread/currentThread))
           before (.getThreadAllocatedBytes threads thread)
           latest (runner/latest-results database [interrupted])
           allocated (- (.getThreadAllocatedBytes threads thread) before)]
       (is (not (:seon.error/at committed)))
       (is (integer? threshold) "the seeded cluster declares the blob threshold")
       (is (= #{[(count huge) true]}
              ;; This run's reports only: the branch inherits the store's.
              (set (db/q '[:find ?size ?blob? :in $ ?run-id
                           :where [?run :seon.test.run/id ?run-id] [?run :seon.test.run/members ?m]
                           [?m :seon.test.member/failures ?r]
                           [?r :seon.test.failure/message-size ?size]
                           [?r :seon.test.failure/message-blob ?blob]
                           [(string? ?blob) ?blob?]] database run-id)))
           "the member's report names the message by blob digest and size")
       (is (>= threshold (longest '[:find [?m ...] :where [_ :seon.test.failure/message ?m]]))
           "no stored assertion message is inline above the threshold")
       (is (>= threshold (longest '[:find [?m ...] :where [_ :seon.test/failure-message ?m]]))
           "the test entity keeps no joined copy of the claim")
       (is (= 1 (:seon.test/fail-count (first latest))))
       (is (not (contains? (first latest) :seon.test/failure-message))
           "a result read builds no failure text")
       (is (< allocated (quot (count huge) 2)) "the read allocation stays bounded")
       (is (not (str/includes? (sut/failure-message (first latest)) "seon.blob/get"))
           "the shown text is the claim only: its blob digest is data on the report")))))

(deftest ^{:seon.test/long "admit-run measured 13.7-15.3 s on default pid 90963 (2026-09-23), the rest under 1 s: it derives the program digest from every program entity changed since the source seal (docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md)." :seon.test/long-ms 30000}
  a-deleted-tests-red-member-is-history-not-a-failed-test
  (support/with-database
   (fn [connection]
     (let [deleted 'seon.id-test/a-test-deleted-after-its-red-run
           handle (support/execution-handle connection)
           database (db/db connection)
           provenance (runner/provenance database)
           red-result (fn [test-symbol]
                        {:seon.test/sym test-symbol
                         :seon.test.member/began? true :seon.test.member/ended? true
                         :seon.test/pass-count 0 :seon.test/fail-count 1 :seon.test/error-count 0})
           _ (support/transacted!
              connection
              [[:db.fn/call sut/admit-run
                {:seon.test.run/provenance provenance
                 :seon.test.run/cluster [:seon.cluster/name (:seon.cluster/name handle)]
                 :seon.test.run/input-digest
                 (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]] database)
                 :seon.test.run/policy :named
                 :seon.test.run/include-long? false
                 :seon.test.run/members
                 (mapv #(hash-map :seon.test.member/symbol % :seon.test.member/reasons #{:named})
                       [red deleted])}]])
           committed (runner/commit-results!
                      connection
                      {:seon.db/db database
                       :seon.test.runner/results [(red-result red) (red-result deleted)]
                       :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                       :seon.test/run-at (:seon.test.run/at provenance)
                       :seon.test.run/provenance provenance
                       :seon.test.run/terminated? true})
           database (db/db connection)
           failed (set (map :seon.test/sym (:seon.problems/failed-tests (problems/problems database {}))))]
       (is (not (:seon.error/at committed)))
       (is (nil? (db/q '[:find ?t . :in $ ?s :where [?t :seon.test/sym ?s]] database deleted))
           "the deleted symbol has no test entity")
       (is (= 1 (:seon.test/fail-count (first (runner/latest-results database [deleted]))))
           "its member evidence stays readable as history")
       (is (contains? failed red) "a current test's red is listed")
       (is (not (contains? failed deleted)) "a symbol that is no test entity is not a failed test")))))

(deftest ^{:seon.test/long "two admit-runs, each measured 13.7-15.3 s on default pid 90963 (2026-09-23; docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md); the reads under test are about 1 ms." :seon.test/long-ms 45000}
  latest-results-reads-one-batch-whatever-the-runs-it-spans
  (support/with-database
   (fn [connection]
     (let [first-run (:seon.test.run/id (admitted-run! connection))
           reads (fn []
                   (let [captured (atom [])
                         latest (binding [db/*read-evidence-sink* captured]
                                  (runner/latest-results (db/db connection) [finished red interrupted]))]
                     [(count @captured) (group-by (some-fn :seon.test/sym :seon.test/error-test-symbol) latest)]))
           [one-run-reads _] (reads)
           second-run (:seon.test.run/id (admitted-run! connection [finished] [(outcome finished 1)]))
           [two-run-reads by-symbol] (reads)]
       (is (= one-run-reads two-run-reads)
           "the reads are one batch: a second run the answer spans adds none")
       (is (= [[:seon.test.run/id second-run] 1]
              ((juxt :seon.test/run :seon.test/fail-count) (first (by-symbol finished))))
           "a test's latest run answers, not an older green")
       (is (= [[:seon.test.run/id first-run] 1]
              ((juxt :seon.test/run :seon.test/fail-count) (first (by-symbol red)))))
       (is (= [:seon.test.run/id first-run] (:seon.test/run (first (by-symbol interrupted))))
           "the open member stays its own unknown in its own run")
       (is (:seon.error/at (first (by-symbol interrupted))))))))
