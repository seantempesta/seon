(ns seon.test-support-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [malli.core :as m]
            [malli.instrument :as mi]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]
            [seon.test.runner :as runner]))

(deftest environment-retains-the-database-projection
  (test-support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           environment (test-support/environment "carried-projection" connection)]
       (is (some? projection))
       (is (identical? projection (:seon.schema/projection environment)))))))


(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  fixture-setup-refusals-stop-before-the-body
  (let [body-ran (atom false)
        failure (try
                  (test-support/with-database
                   {:seon.test-support/extra-schema [{:my.plan.item/title 42}]}
                   (fn [_] (reset! body-ran true)))
                  (catch clojure.lang.ExceptionInfo error (ex-data error)))]
    (is (false? @body-ran))
    (is (string? (:seon.db.write.attempt/request-id failure)))
    (is (= [0 :my.plan.item/title] (:seon.db/path failure)))
    (is (= 42 (:seon.db/offending failure))))
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "checked-fixture")
     (is (= "checked-fixture"
            (db/q '[:find ?name . :where [_ :seon.cluster/name ?name]]
                  (db/db connection))))
     (is (string?
          (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                (db/db connection)))))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  a-refused-fixture-write-is-reported-at-the-write
  ;; The recurring failure class: a fixture that discards `transact!`'s answer
  ;; reads ABSENCE OF SIGNAL as health. One write path means admission's
  ;; refusal stops the fixture where it happened, naming the diagnostic and
  ;; the offending row, instead of an empty world several assertions later.
  (test-support/with-database
   (fn [connection]
     (let [reached-downstream? (atom false)
           failure (try
                     (test-support/transacted!
                      connection
                      [{:seon.agent/id "refused-fixture-write"
                        :seon.agent/not-an-installed-attribute 1}])
                     (reset! reached-downstream? true)
                     nil
                     (catch clojure.lang.ExceptionInfo error error))]
       (is (false? @reached-downstream?)
           "a refused fixture write never returns to its seeding fixture")
       (is (some? failure))
       (is (string? (:seon.db.write.attempt/request-id (ex-data failure))))
       (is (= [0 :seon.agent/not-an-installed-attribute]
              (:seon.db/path (ex-data failure))))
       (is (str/includes? (ex-message failure)
                          ":seon.agent/not-an-installed-attribute")
           "the failure carries write admission's own diagnostic")
       (is (str/includes? (ex-message failure)
                          (str "Offending row 0: "
                               (pr-str {:seon.agent/id "refused-fixture-write"
                                        :seon.agent/not-an-installed-attribute 1})))
           "the failure names the authored row, not the schema it was read against")
       (is (nil? (db/q '[:find ?id . :where [_ :seon.agent/id ?id]]
                       (db/db connection)))
           "the refused row seeded nothing")
       (let [report (test-support/transacted!
                     connection (test-support/agent-tx @connection "admitted-fixture-write"))]
         (is (some? (:db-after report)))
         (is (= "admitted-fixture-write"
                (db/q '[:find ?id . :where [_ :seon.agent/id ?id]]
                      (db/db connection)))))))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  a-long-refusal-reaches-the-failure-message-whole
  ;; A TEST DIAGNOSTIC IS NOT AN AGENT PROJECTION. The AI profile's token
  ;; budget is shared across the rendered value, so write admission's refusal
  ;; — whose `ex-data` carries the entity schema it was read against — used to
  ;; arrive in the gate log as
  ;; `{:seon.print/omitted 3458 :seon.print/prefix "Fixture write was refused
  ;; at the write: …"}`. The report named less than the runner knew, which is
  ;; the absence-as-health class wearing the reporter's clothes (AGENTS §2.4).
  (test-support/with-database
   (fn [connection]
     (let [failure (try
                     (test-support/transacted!
                      connection
                      [{:seon.cluster/name "unclipped-refusal-fixture"}])
                     nil
                     (catch clojure.lang.ExceptionInfo error error))
           reported (#'runner/report-value (#'runner/report-options) failure)]
       (is (some? failure) "the fixture write is refused")
       (is (< 204 (count (ex-message failure)))
           "the refusal is longer than the profile's string bound, or this proves nothing")
       (is (str/includes? reported (ex-message failure))
           "the reporter carries write admission's complete refusal")
       (is (not (str/includes? reported ":seon.print/omitted"))
           "no elision value stands in for a test diagnostic")))))


(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  a-canonical-database-is-the-production-source-population
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            packaged-forms (schema.edn/packaged-forms)
            installed
            (into #{} (filter keyword?) (keys (:schema database)))
            expected-schema-keys
            (into
             (into #{}
                   (map :seon.schema/key)
                   (schema/canonical-schema-rows packaged-forms))
             (keep :seon.schema/key)
             (mapcat :seon.fn.file/rows
                     (:seon.fn.manifest/artifacts @test-support/source-manifest)))
            actual-schema-keys
            (db/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             database)]
        (is (every? installed
                    (schema/canonical-database-attributes (schema/handed-projection))))
        (is (not (contains? installed :seon.schema/created-at)))
        (is (every? #(not (contains? % :seon.schema/created-at))
                    (schema/canonical-schema-rows packaged-forms)))
        (is (= expected-schema-keys (set actual-schema-keys)))
        (is (= (->> (db/identity-attributes database)
                    (filter #(seq (db/datoms database :avet %)))
                    vec)
               (db/populated-identity-attributes database))
            "the browse catalogue derives from installed identities in datoms")
        (is (= #{cluster/boot-process-identity
                 config/managing-process-identity}
               (set
                (db/q
                 '[:find [?process-id ...]
                   :where
                   [?process :seon.db.process/id ?process-id]]
                 database))))
        (let [before (:max-tx @connection)]
          ((ns-resolve 'seon.cluster 'accrete-schema-population!)
           connection nil)
          (is (= before (:max-tx @connection))
              "clock-free schema reconciliation is idempotent"))))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  an-instrumentation-test-restores-the-entering-contracts-on-failure
  (let [roots (into {} (map (juxt identity deref)) (instrument/instrumented))
        schemas (m/function-schemas)]
    (is (seq roots) "the regression must enter with real armed contracts")
    (is (= ::deliberate-failure
           (try
             (test-support/preserving-instrumentation-state
              (fn []
                (let [[candidate callable] (first roots)]
                  (alter-var-root candidate mi/-f->original)
                  (is (identical? (mi/-f->original callable) @candidate))
                  (is (not (identical? callable @candidate)))
                  (is (not (contains? (instrument/instrumented) candidate))))
                (throw (ex-info "fixture failure" {::cause ::deliberate-failure}))))
             (catch clojure.lang.ExceptionInfo error (::cause (ex-data error))))))
    (is (= (set (keys roots)) (instrument/instrumented)))
    (is (every? (fn [[v callable]] (identical? callable @v)) roots))
    (is (= schemas (m/function-schemas)))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  config-reconciliation-cannot-retract-the-schema-population
  (test-support/with-database
    (fn [connection]
      (let [before
            (db/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             @connection)
            result
            (config/apply!
             {:seon.db/connection connection
              :seon.config/manifest config/defaults
              :seon.boot/cluster-name "fixture-proof"})
            after
            (db/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             @connection)]
        (is (pos-int? (:seon.reconcile/operations result)))
        (is (= (set before) (set after)))))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  explicit-synthetic-schema-rows-extend-only-that-database
  (let [options
        {::test-support/extra-schema
         (test-support/file-store-probe-schema ::marker)}]
    (is (= #{"installed"}
           (test-support/with-database
             options
             (fn [connection]
               (db/transact! connection [{::marker "installed"}])
               (test-support/file-store-markers connection ::marker))))
        "the leased database sees its own synthetic marker rows")
    (let [[installed? markers]
          (test-support/with-database
            (fn [connection]
              [(contains? (:schema @connection) ::marker)
               (test-support/file-store-markers connection ::marker)]))]
      (is (false? installed?)
          "a released lease is rebranched from the immutable base; the
           child-only schema state cannot leak into the next test"))))


(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  shared-support-observes-events-refusals-and-cleanup
  (let [events (async/chan 1)
        path (str "tmp/test-support/" (random-uuid))
        file (java.io.File. path "nested/value.edn")]
    (.mkdirs (.getParentFile file))
    (spit file "{}")
    (async/>!! events ::published)
    (is (= ::published
           (test-support/await-event! events ::published)))
    (let [state (atom ::ready)]
      (is (= ::ready (test-support/await-event! state ::already-published)))
      (is (empty? (.getWatches state)))
      (let [waiting (future (test-support/await-event! state ::changed nil?))]
        (reset! state nil)
        (is (nil? (test-support/await-event! waiting ::watch-finished)))
        (is (empty? (.getWatches state)))))
    (let [failure (ex-info "event publisher failed" {::publisher :failed})]
      (is (identical? failure
                      (try
                        (test-support/await-event!
                         (future (throw failure)) ::failed-publisher)
                        (catch Throwable caught caught)))))
    (is (= {::rule ::refused}
           (test-support/refusal-data
            #(throw (ex-info "refused" {::rule ::refused})))))
    (let [refusal {:seon.error/at (java.util.Date.)
                   :seon.error/layer ::observation
                   :seon.error/operation `test-support/refusal-data}]
      (is (identical? refusal (test-support/refusal-data (constantly refusal))))
      (is (= test-support/committed
             (test-support/refusal-data
              (constantly (dissoc refusal :seon.error/operation))))))
    (test-support/delete-recursively! path)
    (is (not (.exists (java.io.File. path))))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  shared-property-reporting-is-a-clojure-test-assertion
  (let [property (prop/for-all [value gen/small-integer]
                   (> (inc value) value))
        passed (tc/quick-check 10 property :seed 20260728)
        empty-check (tc/quick-check 0 property :seed 20260728)
        failed (tc/quick-check 10
                              (prop/for-all [value (gen/choose 3 10)]
                                (< value 3))
                              :seed 20260728)
        reports (atom [])]
    (binding [test/report #(swap! reports conj %)]
      (doseq [check [passed empty-check failed]]
        (test-support/assert-check! check)))
    (is (= [:pass :fail :fail] (mapv :type @reports))
        "zero trials and a falsified property both fail the shared proof boundary")
    (is (= [3] (get-in failed [:shrunk :smallest])))
    (is (str/includes? (:message (last @reports))
                       ":smallest [3]")
        "the reported failure retains the smallest failing input")
    (is (str/includes? (:message (last @reports)) ":seed 20260728")
        "the report retains the seed needed to replay the counterexample")))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  recursive-cleanup-never-follows-a-symlink-out-of-tmp
  ;; The 2026-07-29 data-loss incident: a scratch root under tmp/ linked the
  ;; source tree for its classpath, and cleanup walked the link and deleted 55
  ;; tracked paths. A sandbox check on the ROOT is worthless if the walk can
  ;; leave the sandbox, so the sentinel below must survive its own link's
  ;; deletion.
  (let [scratch (java.nio.file.Files/createTempDirectory
                 (.toPath (io/file "tmp"))
                 "cleanup-symlink-proof"
                 (make-array java.nio.file.attribute.FileAttribute 0))
        outside (java.nio.file.Files/createTempDirectory
                 (.toPath (io/file "tmp"))
                 "cleanup-sentinel"
                 (make-array java.nio.file.attribute.FileAttribute 0))
        sentinel (.resolve outside "must-survive.txt")
        link (.resolve scratch "linked-elsewhere")
        nofollow (into-array java.nio.file.LinkOption
                             [java.nio.file.LinkOption/NOFOLLOW_LINKS])]
    (try
      (spit (.toFile sentinel) "do not delete me")
      (java.nio.file.Files/createSymbolicLink
       link outside (make-array java.nio.file.attribute.FileAttribute 0))
      (test-support/delete-recursively! (str scratch))
      (is (not (java.nio.file.Files/exists (.toPath (.toFile scratch)) nofollow))
          "the scratch directory itself is gone")
      (is (java.nio.file.Files/exists sentinel nofollow)
          "a file reached only through a symlink SURVIVES cleanup")
      (is (java.nio.file.Files/exists outside nofollow)
          "the link's target directory survives; only the link was removed")
      (finally
        (test-support/delete-recursively! (str outside))))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  fixture-resources-close-through-setup-and-cleanup-failures
  (doseq [acquired-count (range 4)
          failing-cleanup [nil 0 1 2]]
    (let [events (atom [])
          acquire! (fn [ordinal]
                     (when (= ordinal acquired-count)
                       (throw (ex-info "setup failed" {})))
                     (swap! events conj [::opened ordinal])
                     (test-support/closeable
                      ordinal
                      (fn [value]
                        (swap! events conj [::closed value])
                        (when (= value failing-cleanup)
                          (throw (ex-info "cleanup failed" {}))))))]
      (is (thrown? Exception
                   (with-open [first-resource (acquire! 0)
                               second-resource (acquire! 1)
                               third-resource (acquire! 2)]
                     (is (= [0 1 2] [@first-resource @second-resource @third-resource]))
                     (throw (ex-info "body failed" {})))))
      (is (= (concat (map #(vector ::opened %) (range acquired-count))
                     (map #(vector ::closed %) (reverse (range acquired-count))))
             @events)))))
