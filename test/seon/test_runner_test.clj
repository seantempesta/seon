(ns seon.test-runner-test
  "Capture and latest-result facts owned by the in-process test runner."
  (:require [clojure.java.io :as io]
            [dev-cache]
            [seon.dev.dependency-digest :as dependency-digest]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.test-runner-failure-fixture]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support]))

(def ^:private at (java.util.Date. 1785283200000))
(def ^:private project-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(deftest ^{:seon.test/platform "Dependency cache identity excludes checkout locations."}
  dependency-source-digest-does-not-name-the-checkout
  (let [root (doto (io/file project-root "tmp" (str "digest-" (random-uuid))) .mkdirs)
        left (io/file root "left.clj")
        right (io/file root "right.clj")
        digest (fn [file]
                 (let [sha (java.security.MessageDigest/getInstance "SHA-256")]
                   (#'dependency-digest/digest-file! sha file)
                   (vec (.digest sha))))]
    (try
      (spit left "(ns fixture)\n")
      (spit right "(ns fixture)\n")
      (is (= (digest left) (digest right)))
      (is (= (#'dev-cache/sha-256
              [{:seon.dev-cache/namespace 'fixture
                :seon.dev-cache/source-url (str (.toURI left))}])
             (#'dev-cache/sha-256
              [{:seon.dev-cache/namespace 'fixture
                :seon.dev-cache/source-url (str (.toURI right))}])))
      (spit right "(ns changed)\n")
      (is (not= (digest left) (digest right)))
      (finally (test-support/delete-recursively! root)))))

(declare stop-process-tree!)



;; NOT :seon.test/platform: the fixture reaches
;; seon.test-support/populate-published-root!, which deletes and reclones a
;; store directory. The platform tier runs first on every bin/test invocation,
;; so a destructive fixture there deletes before any evidence exists
;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).


(defn- captured-run-with-output
  "Capture the failure fixture namespace through the one capture owner."
  []
  (require 'seon.test-runner-failure-fixture)
  (let [writer (java.io.StringWriter.)
        vars (->> (ns-interns 'seon.test-runner-failure-fixture)
                  vals (filter (comp :test meta)) (sort-by symbol) vec)
        results (binding [clojure.test/*test-out* writer] (runner/run-vars! vars {}))
        total (fn [k] (reduce + 0 (map k results)))]
    {::result {:seon.test.runner/results results
               :seon.test.runner/summary
               #:seon.test.runner{:test-count (count results)
                                  :pass-count (total :seon.test/pass-count)
                                  :fail-count (total :seon.test/fail-count)
                                  :error-count (total :seon.test/error-count)}}
     ::output (str writer)}))

(defn- captured-run []
  (::result (captured-run-with-output)))

(defn- occurrences
  [text fragment]
  (loop [from 0
         found 0]
    (if-let [match-at (str/index-of text fragment from)]
      (recur (+ match-at (count fragment)) (inc found))
      found)))

(deftest captures-counts-and-failure-identities-per-test
  (let [result (captured-run)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))]
    (is (= #:seon.test.runner{:test-count 5
                              :pass-count 1
                              :fail-count 2
                              :error-count 8}
           (:seon.test.runner/summary result)))
    (is (= #:seon.test{:pass-count 1 :fail-count 0 :error-count 0}
           (select-keys
            (by-symbol 'seon.test-runner-failure-fixture/passing-example)
            [:seon.test/pass-count
             :seon.test/fail-count
             :seon.test/error-count])))
    (is (= 1
           (count
            (:seon.test/failing-assertions
             (by-symbol
              'seon.test-runner-failure-fixture/failing-example)))))
    (is (str/includes?
         (:seon.test/failure-message
          (by-symbol
           'seon.test-runner-failure-fixture/failing-example))
         "deliberate broken-test evidence"))))

(deftest assertionless-test-is-an-attributed-failure
  (let [result (captured-run)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))
        assertionless
        (by-symbol
         'seon.test-runner-failure-fixture/assertionless-example)]
    (is (= #:seon.test{:pass-count 0 :fail-count 1 :error-count 0}
           (select-keys assertionless
                        [:seon.test/pass-count
                         :seon.test/fail-count
                         :seon.test/error-count])))
    (is (str/includes?
         (:seon.test/failure-message assertionless)
         "Test seon.test-runner-failure-fixture/assertionless-example completed without assertion evidence."))))

(deftest repeated-identical-errors-have-one-whole-face
  (let [{::keys [result output]} (captured-run-with-output)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))
        repeated-message
        (:seon.test/failure-message
         (by-symbol
          'seon.test-runner-failure-fixture/repeated-identical-error))
        repeated-signature (apply str (repeat 64 "a"))
        distinct-signature (apply str (repeat 64 "b"))
        signature-at (str/index-of output repeated-signature)
        repeated-start (inc (str/last-index-of output "\nERROR in"
                                                signature-at))
        summary-start (or (str/index-of output "\nERROR in" repeated-start) (count output))
        repeated-face (subs output repeated-start summary-start)]
    (testing "all events remain counted while only distinct causes render"
      (is (= 8
             (get-in result [:seon.test.runner/summary
                             :seon.test.runner/error-count])))
      (is (= 2 (occurrences output "ERROR in")))
      (is (= 2 (occurrences output "  signature:")))
      (is (str/includes? output "one repeated refusal"))
      (is (str/includes? output distinct-signature)))
    (testing "one face and the captured fact are whole and deduplicated"
      (is (not (str/includes?
                repeated-face
                "additional failure output elided by bin/test")))
      (is (= 1 (occurrences repeated-message
                            "the same refusal reached the reporter again"))))))

(deftest result-recording-is-total-under-concurrent-test-retraction
  ;; Result recording must not resurrect a retracted program definition.
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (let [run-result (captured-run)
            completion
            {:seon.test.runner/results
             (:seon.test.runner/results run-result)
             :seon.test/run-basis-t (db/basis-t @connection)
             :seon.test.run/provenance (assoc (runner/provenance @connection) :seon.test.run/at at)
             :seon.test/run-at at}
            test-symbol (:seon.test/sym
                         (first (filter #(pos? (:seon.test/fail-count %))
                                        (:seon.test.runner/results run-result))))]
        (is (seq (runner/commit-results! connection completion))
            "first recording updates the existing test definition")
        (let [failure-ids (set (db/q '[:find [?id ...]
                                       :in $ ?s
                                       :where [?t :seon.test/sym ?s]
                                              [?t :seon.test/failures ?f]
                                              [?f :seon.test.failure/id ?id]]
                                     @connection test-symbol))]
        (is (seq failure-ids) "the retracted test owns actual failure components")
        (test-support/transacted!
                     connection
                     [[:db.fn/retractEntity [:seon.test/sym test-symbol]]])
        (let [before (db/basis-t @connection)
              recorded (runner/commit-results! connection completion)]
          (is (= [test-symbol] (:seon.test/symbols recorded)))
          (is (= before (db/basis-t @connection))
              "a refused completion changes no database facts")
          (is (nil? (:db/id (db/pull @connection [:db/id] [:seon.test/sym test-symbol])))
              "result recording never recreates a retracted definition")))))))

(deftest result-facts-live-on-the-test-row-and-reruns-replace-them
  (test-support/with-database
    (fn [connection]
      (let [run-result (captured-run)
            basis-t (db/basis-t @connection)
            completion
            {:seon.test.runner/results
             (:seon.test.runner/results run-result)
             :seon.test/run-basis-t basis-t
             :seon.test.run/provenance (assoc (runner/provenance @connection) :seon.test.run/at at)
             :seon.test/run-at at}]
        (test-support/seed-cluster! connection "test")
        (let [committed (runner/commit-results! connection completion)]
          (is (= committed
                 (mapv #(dissoc (db/pull @connection @#'runner/result-selector
                                        [:seon.test/sym (:seon.test/sym %)]) :db/id)
                       (:seon.test.runner/results run-result)))
              "the returned projection equals the committed facts, including structured failures")
          (is (= (mapv #(select-keys % [:seon.test/sym :seon.test/pass-count
                                       :seon.test/fail-count :seon.test/error-count])
                       (:seon.test.runner/results run-result))
                 (mapv #(select-keys % [:seon.test/sym :seon.test/pass-count
                                       :seon.test/fail-count :seon.test/error-count]) committed)))
          (is (seq (mapcat :seon.test/failures committed))))
        (test-support/transacted!
                     connection
                     (agent/creation-tx
                      {:seon.agent/id "fixture-owner"
                       :seon.cluster/name "test"
                       :seon.ns/name 'seon.test-runner-failure-fixture}))
        (is
         (= #{['seon.test-runner-failure-fixture/failing-example
                "fixture-owner"
                0 1 0 basis-t at]}
            (db/q
             '[:find ?test-symbol ?agent-id ?passes ?failures ?errors
               ?basis ?at
               :in $ ?selected-test
               :where
               [?test :seon.test/sym ?test-symbol]
               [(= ?test-symbol ?selected-test)]
               [?test :seon.test/ns ?namespace]
               [?agent :seon.agent/namespace ?namespace]
               [?agent :seon.agent/id ?agent-id]
               [?test :seon.test/pass-count ?passes]
               [?test :seon.test/fail-count ?failures]
               [?test :seon.test/error-count ?errors]
               [?test :seon.test/run-basis-t ?basis]
               [?test :seon.test/run-at ?at]]
             @connection
             'seon.test-runner-failure-fixture/failing-example)))
        (let [test-ref
              [:seon.test/sym
               'seon.test-runner-failure-fixture/failing-example]
              before (db/pull @connection
                              [:db/id :seon.test/failing-assertions]
                              test-ref)
              next-basis (db/basis-t @connection)
              green
              (runner/commit-results!
               connection
               {:seon.test.runner/results
                [#:seon.test{:sym (second test-ref)
                             :pass-count 1
                             :fail-count 0
                             :error-count 0}]
                :seon.test/run-basis-t next-basis
                :seon.test.run/provenance (runner/provenance @connection)
                :seon.test/run-at (java.util.Date.)})
              after (db/pull @connection
                             [:db/id
                              :seon.test/pass-count
                              :seon.test/fail-count
                              :seon.test/error-count
                              :seon.test/failing-assertions
                              :seon.test/failure-message]
                             test-ref)]
          (is (seq (:seon.test/failing-assertions before))
              "the red run records its content-addressed failing assertion")
          (is (= 1 (:seon.test/pass-count (first green))))
          (is (= (:db/id before) (:db/id after))
              "a rerun updates the existing indexed test row")
          (is (= #:seon.test{:pass-count 1 :fail-count 0 :error-count 0}
                 (select-keys after
                              [:seon.test/pass-count
                               :seon.test/fail-count
                               :seon.test/error-count])))
          (is (nil? (:seon.test/failing-assertions after)))
          (is (nil? (:seon.test/failure-message after))))))))
