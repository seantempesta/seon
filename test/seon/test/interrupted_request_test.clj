(ns seon.test.interrupted-request-test
  "A request that throws leaves no member open, and open test evidence makes
  only its own problem family unknown."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.problems :as problems]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(def ^:private finished 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol)
(def ^:private interrupted 'seon.id-test/data-shape-and-explicit-length-determine-identity)

(defn- admitted-run!
  "Admit both members and record `finished` green; `interrupted` stays open."
  [connection]
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
                         [finished interrupted])}
        completion (fn [results]
                     {:seon.db/db database
                      :seon.test.runner/results results
                      :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                      :seon.test/run-at (:seon.test.run/at provenance)
                      :seon.test.run/provenance provenance
                      :seon.test.run/terminated? true})
        _ (support/transacted! connection [[:db.fn/call sut/admit-run admission]])
        committed (runner/commit-results!
                   connection
                   (completion [{:seon.test/sym finished
                                 :seon.test.member/began? true :seon.test.member/ended? true
                                 :seon.test/pass-count 1 :seon.test/fail-count 0
                                 :seon.test/error-count 0}]))]
    (is (not (:seon.error/at committed)) (pr-str committed))
    {:seon.test.run/id (:seon.test.run/id provenance) ::completion (completion [])}))

(deftest ^{:seon.test/long "admit-run measured 13.7-15.3 s on default pid 90963 (2026-09-23), the rest under 1 s: it derives the program digest from every program row changed since the source seal (docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md)." :seon.test/long-ms 30000}
  a-thrown-request-records-its-unfinished-members-as-failed-obligations
  (support/with-database
   (fn [connection]
     (let [{run-id :seon.test.run/id completion ::completion} (admitted-run! connection)
           failure (ex-info "resolution exploded" {::probe true}
                            (IllegalStateException. "the underlying cause"))
           recorded (runner/record-interrupted! connection completion failure)
           results (runner/run-results (db/db connection) run-id)
           by-symbol (into {} (map (juxt :seon.test/sym identity)) results)
           latest (runner/latest-results (db/db connection) [interrupted])]
       (is (= [interrupted] (mapv :seon.test/sym recorded)) (pr-str recorded))
       (is (not (:seon.error/at results)) "the run's evidence is complete")
       (is (= 0 (:seon.test/error-count (by-symbol finished))) "a recorded member is untouched")
       (is (= 1 (:seon.test/error-count (by-symbol interrupted))) "interrupted is red, never green")
       (is (= 1 (:seon.test/error-count (first latest))) "it stays an obligation")
       (is (every? #(str/includes? (:seon.test/failure-message (first latest)) %)
                   ["resolution exploded" "the underlying cause" "probe true"])
           (str "the record names the whole cause chain: " (:seon.test/failure-message (first latest))))
       (is (= [] (runner/record-interrupted! connection completion failure))
           "a second observation writes nothing")))))

(deftest ^{:seon.test/long "admit-run measured 13.7-15.3 s on default pid 90963 (2026-09-23), the rest under 1 s: it derives the program digest from every program row changed since the source seal (docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md)." :seon.test/long-ms 30000}
  unknown-test-evidence-leaves-the-other-problem-families-derivable
  (support/with-database
   (fn [connection]
     (admitted-run! connection)
     (let [found (problems/problems (db/db connection) {})]
       (is (not (:seon.error/at found)) "problems answers the family map")
       (is (= :seon.test/population-unknown
              (:seon.test/execution-refusal (first (:seon.problems/failed-tests-unknown found))))
           (pr-str found))
       (is (not (contains? found :seon.problems/failed-tests)))))))
