(ns seon.test-runner-test
  "Capture and latest-result facts owned by the in-process test runner."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [dev-cache]
            [seon.dev.dependency-digest :as dependency-digest]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.test-runner-failure-fixture]
            [sci.core :as sci]
            [seon.error :as error]
            [seon.operator.runtime]
            [seon.test :as sut]
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

(defn- throwaway-test-var
  "One test Var in its own throwaway namespace, with optional once fixtures."
  {:malli/schema [:=> [:cat :seon.instrument/callable [:vector :seon.instrument/callable]]
                  :seon.test/var]}
  [body fixtures]
  (let [namespace-object (create-ns (symbol (str "runner.probe" (random-uuid))))]
    (alter-meta! namespace-object assoc :clojure.test/once-fixtures fixtures)
    (doto (intern namespace-object 'probe) (alter-meta! assoc :test body))))

(deftest a-value-testing-label-is-captured-as-its-printed-string
  (let [test-var (throwaway-test-var
                  #(testing [:seon.fn/sym 'a/b] (is (= 1 2) "a value label")) [])]
    (try
      (let [result (runner/run-var! test-var {})]
        (is (= 1 (:seon.test/fail-count result)) (pr-str result))
        (is (zero? (:seon.test/error-count result)) (pr-str result))
        (is (= [[[0 "[:seon.fn/sym a/b]"]]]
               (mapv :seon.test.failure/contexts (:seon.test.failure/reports result)))))
      (finally (remove-ns (ns-name (:ns (meta test-var))))))))

(deftest a-huge-execution-failure-becomes-bounded-text
  ;; A fixture's contract refusal escapes the body, as the 2026-09-23 run's did;
  ;; its ex-data holds the whole one-megabyte argument. One megabyte, not ten:
  ;; building a refusal prints its whole offending leaf (`seon.sci.admit/leaf!`),
  ;; about 0.5 s per megabyte (docs/seon/issues/bounded-admission-prints-a-whole-leaf-before-its-bound.md).
  (let [huge (apply str (repeat 1000000 "x"))
        test-var (throwaway-test-var
                  #(is true)
                  [(fn [_] (error/problem-sentence [huge] {} nil nil))])]
    (try
      (let [loaded (atom {})
            result (#'sut/bounded-result loaded @loaded
                                         (symbol test-var) (constantly test-var) 5000 {:seon.sci.eval/ctx (sci/init {})}
                                         (fn [_] nil))
            message (:seon.test/failure-message result)]
        (is (= 1 (:seon.test/error-count result)))
        (is (str/includes? message "Test execution failed"))
        (is (str/includes? message "seon.error/problem-sentence refused operation")
            "the stored text names the refusal")
        (is (< (count message) 10000)
            "the whole argument in the refusal's ex-data is not printed"))
      (finally (remove-ns (ns-name (:ns (meta test-var))))))))

(deftest a-reported-throwable-names-its-whole-cause-chain
  (let [test-var (throwaway-test-var
                  #(throw (ex-info "the outer refusal" {::member :the-member}
                                   (IllegalStateException. "the root cause")))
                  [])]
    (try
      (let [message (:seon.test/failure-message (runner/run-var! test-var {}))]
        (is (str/includes? message "the outer refusal"))
        (is (str/includes? message "the root cause") "the cause survives")
        (is (str/includes? message ":the-member") "the ex-data survives"))
      (finally (remove-ns (ns-name (:ns (meta test-var))))))))

(deftest the-drift-snapshot-names-the-running-clusters
  (let [running (set (keys @seon.operator.runtime/running-instances))]
    (is (seq running) "a running cluster is the subject")
    (is (= running (::runner/snapshot-live-clusters (#'runner/ambient-snapshot))))))

(deftest a-metadata-only-change-changes-the-reach-digest
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            test-symbol (symbol (str "reach.probe" (random-uuid)) "check")
            source "(deftest check (is true))"
            row (test-support/program-row database [:seon.test/sym test-symbol] source)
            _ (test-support/transacted!
               connection [(test-support/namespace-row database (symbol (namespace test-symbol))) row])
            before (get (runner/reach-digests (db/db connection) [test-symbol]) test-symbol)
            ;; The writer's digest owner, with the namespace row this test stored.
            changed (test-support/revised-row (db/db connection) row
                                              {:seon.test/fixture "a fixture-only change"})]
        (is (string? before))
        (is (= source (:seon.test/source changed)))
        (is (not= (:seon.program/definition-digest row) (:seon.program/definition-digest changed))
            "the producer's digest sees the metadata")
        (test-support/transacted! connection [changed])
        (is (not= before (get (runner/reach-digests (db/db connection) [test-symbol]) test-symbol))
            "reuse keyed by reach sees the same change")))))

(deftest undigested-declarations-never-share-a-reuse-key
  ;; Rows admitted before the digest was required: the writer now refuses
  ;; them, so the pure value is built with Datahike's own `with`.
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            ns-name (symbol (str "reach.undigested" (random-uuid)))
            rows (mapv #(test-support/program-row database [:seon.test/sym (symbol (str ns-name) %1)] %2)
                       ["one" "two"] ["(deftest one (is true))" "(deftest two (is false))"])
            symbols (mapv :seon.test/sym rows)
            _ (test-support/transacted! connection (into [(test-support/namespace-row database ns-name)] rows))
            digested (runner/reach-digests (db/db connection) symbols)
            undigested (:db-after (d/with (db/db connection)
                                          (for [row rows]
                                            [:db/retract [:seon.test/sym (:seon.test/sym row)]
                                             :seon.program/definition-digest (:seon.program/definition-digest row)])))
            refusal (runner/reach-digests undigested symbols)]
        (is (every? string? (map digested symbols)) "the digested rows have reuse keys")
        (is (= :seon.program/definition-digest (:seon.error/member refusal)) (pr-str refusal))
        (is (= symbols (:seon.error/offending refusal)) "the refusal names both tests")
        (is (not-any? #(string? (get refusal %)) symbols) "no reuse key is answered")))))

(deftest a-failure-over-huge-values-reports-and-identifies-within-the-render-bound
  ;; Default died of OutOfMemoryError "Requested array size exceeds VM limit"
  ;; (2026-09-24T23:27Z, seon.test.overlay-world-test): failure-identity and
  ;; failure-report pr-str'd whole expected/actual values, and a test error
  ;; carried a whole program overlay. Each value here prints as over 200 MB
  ;; while holding one shared 1 MiB string; before the fix one whole print of
  ;; a tenth of it took 713 ms and allocated 112 MB.
  (let [mebibyte (.repeat "x" (* 1024 1024))
        huge (vec (repeat 200 mebibyte))
        test-var #'a-failure-over-huge-values-reports-and-identifies-within-the-render-bound
        options (#'runner/report-options)
        bound (* 4 (:seon.render.profile/token-budget (:seon.render/profile options)))
        capture (atom {::runner/order [] ::runner/results {}})
        report! (fn [event]
                  (with-out-str
                    (binding [test/*test-out* *out*
                              test/*report-counters* (ref test/*initial-report-counters*)
                              test/*testing-vars* [test-var]]
                      (#'runner/capture-and-report-event!
                       options capture #{'seon.test-runner-test}
                       (fn [_] (throw (ex-info "Raw assertion reporter bypass" {})))
                       (atom #{}) event))))
        event {:type :fail :var test-var :expected huge :actual {:seon.test/overlay huge}}
        threads ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        thread (.getId (Thread/currentThread))
        allocated-before (.getThreadAllocatedBytes threads thread)
        started (System/nanoTime)
        output (report! event)
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)
        allocated (- (.getThreadAllocatedBytes threads thread) allocated-before)
        _ (report! (assoc event :actual {:seon.test/overlay (conj huge "different")}))
        [first-report second-report] (:seon.test.failure/reports
                                      (first (#'runner/captured-results @capture)))]
    (is (< elapsed-ms 1000) (str "capturing and reporting the failure took " elapsed-ms " ms"))
    (is (< allocated (* 256 1024 1024))
        (str "the failure allocated " allocated " bytes; one whole print is over 400 MB"))
    (is (< (count output) bound) (str "the gate log printed " (count output) " chars"))
    (doseq [field [:seon.test.failure/expected :seon.test.failure/actual]]
      (is (< (count (get first-report field)) bound) (str field " is bounded by the render profile"))
      (is (str/includes? (get first-report field) ":seon.print/omitted") (str field " names its elision")))
    (is (= (:seon.test/failure-identity first-report)
           (#'runner/failure-identity options (symbol test-var) event))
        "the identity is stable across renders")
    (is (not= (:seon.test/failure-identity first-report)
              (:seon.test/failure-identity second-report))
        "a claim that renders differently has a different identity")))
