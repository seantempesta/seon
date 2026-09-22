(ns seon.test-support-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.fn :as seon.fn]
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
  failed-base-construction-retries-without-caller-interruption
  (test-support/with-database
    (fn [connection]
      (let [attempts (atom 0)
            construction-started (atom nil)
            continue-construction (atom nil)
            caller-finished (atom nil)
            builder-thread (atom nil)
            base (#'test-support/retrying-base
                   (fn []
                     (if (= 1 (swap! attempts inc))
                       (throw (ex-info "first construction failed" {::attempt 1}))
                       (do
                         (reset! builder-thread (Thread/currentThread))
                         (reset! construction-started true)
                         (test-support/await-event! continue-construction ::continue-construction some?)
                         (#'test-support/create-base nil)))))
            projection (db/carried-projection (db/db connection))]
        (is (true? (:seon.test.run/unavailable @base)))
        (is (false? (realized? base)))
        (let [caller (doto
                       (Thread.
                        ^Runnable
                        (fn []
                          (try
                            (schema/call-with-projection projection #(deref base))
                            (reset! caller-finished :returned)
                            (catch InterruptedException _
                              (reset! caller-finished :interrupted)))))
                       (.setDaemon true)
                       (.start))]
          (try
            (test-support/await-event! construction-started ::construction-started some?)
            (.interrupt caller)
            (is (= :interrupted
                   (test-support/await-event! caller-finished ::caller-finished some?)))
            (is (.isDaemon ^Thread @builder-thread))
            (is (identical? (ClassLoader/getSystemClassLoader)
                            (.getContextClassLoader ^Thread @builder-thread)))
            (finally (reset! continue-construction true))))
        (let [constructed @base]
          (try
            (is (some? (:seon.test-support/connection constructed))
                (pr-str (select-keys constructed [:seon.error/message])))
            (when-let [base-connection (::test-support/connection constructed)]
              (is (pos? (db/q '[:find (count ?e) . :where [?e :seon.fn/sym]]
                              (db/db base-connection))))
              (is (delay? (::test-support/sci-context constructed))))
            (is (= 2 @attempts))
            (is (identical? constructed @base))
            (is (realized? base))
            (finally
              (when (::test-support/connection constructed)
                (#'test-support/close-base! constructed)))))))))

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
                     connection [{:seon.agent/id "admitted-fixture-write"}])]
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

(defn- file-digests
  [root]
  (into (sorted-map)
        (for [file (file-seq (io/file root)) :when (.isFile file)]
          [(str (.relativize (.toPath (io/file root)) (.toPath file)))
           (vec (.digest (java.security.MessageDigest/getInstance "SHA-256")
                         (java.nio.file.Files/readAllBytes (.toPath file))))])))

;; The one test in this namespace that is NOT :seon.test/platform: it reaches
;; seon.test-support/populate-published-operator-root!, which deletes and
;; reclones a store directory. The platform tier runs first on every bin/test
;; invocation, so a destructive fixture there deletes before the run has
;; produced any evidence
;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md);
;; seon.test.runner/verify-platform-tier-carries-no-destructive-drill! refuses
;; the tier when this declaration drifts back.
(deftest ^{:seon.test/fixture-observation "The assertions compare physical store bytes and private backend paths during simultaneous fixture acquisitions."
           :seon.test/long "Copy one published store and acquire two independently reidentified copies concurrently; hash every published file before and after both private writes to prove byte preservation."
           :seon.test/long-ms 10000}
  simultaneous-fixture-bases-never-open-the-published-store
  (let [root (str "tmp/fixture-base-isolation/" (random-uuid))
        begin (java.util.concurrent.CountDownLatch. 1)
        written (java.util.concurrent.CountDownLatch. 2)
        closed (java.util.concurrent.CountDownLatch. 2)]
    (try
      (test-support/populate-published-operator-root! root)
      (let [before (file-digests (str root "/data/store"))
            acquire
            (fn [own other]
              (future
                (try
                (test-support/await-event! begin ::begin-acquisition)
                (with-open [resource
                            (test-support/closeable
                             (#'test-support/create-base root)
                             #'test-support/close-base!)]
                  (let [base @resource
                        connection (::test-support/connection base)
                        configuration (::test-support/configuration base)
                        projection (db/carried-projection (db/db connection))]
                    (schema/call-with-projection
                     projection
                     (fn []
                       (let [result (db/transact! connection [{:seon.ns/name own}])]
                         (.countDown written)
                         (test-support/await-event! written ::both-private-writes)
                         {::path (get-in configuration [:store :path])
                          ::id (get-in configuration [:store :id])
                          ::private-root (::test-support/private-root base)
                          ::result-error (:seon.db.write.attempt/request-id result)
                          ::subjects (db/q '[:find (count ?f) . :where [?f :seon.fn/sym]]
                                           @connection)
                          ::own (db/q '[:find ?n . :in $ ?n :where [_ :seon.ns/name ?n]]
                                      @connection own)
                          ::other (db/q '[:find ?n . :in $ ?n :where [_ :seon.ns/name ?n]]
                                        @connection other)})))))
                  (finally (.countDown closed)))))
            left (acquire 'fixture-base.left 'fixture-base.right)
            right (acquire 'fixture-base.right 'fixture-base.left)]
        (.countDown begin)
        (let [results (mapv (fn [task]
                              (try
                                (test-support/await-event! task ::private-base-closed)
                                (catch Throwable failure failure)))
                            [left right])]
          (test-support/await-event! closed ::both-private-bases-closed)
          (doseq [result results]
            (when (instance? Throwable result) (throw result)))
          (is (seq before) "the source is an actual published store")
          (is (= before (file-digests (str root "/data/store")))
              "concurrent connect, write and release preserve every published byte")
          (is (= 2 (count (set (map ::path results)))))
          (is (= 2 (count (set (map ::id results)))))
          (doseq [result results]
            (is (pos? (::subjects result)) "the complete production constructor supplied subjects")
            (is (nil? (::result-error result)))
            (is (some? (::own result)))
            (is (nil? (::other result)))
            (is (not (.exists (io/file (::private-root result))))))))
      (finally (test-support/delete-recursively! root)))))

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
           child-only schema state cannot leak into the next test")
      (is (= :seon.db/attribute-not-installed
             (get-in markers [:seon.error/data :seon.error/diagnostic-cause]))
          "reading the leaked-attribute candidate is a typed refusal naming
           attribute-not-installed — stronger leak evidence than absence"))))

(deftest warmed-fixtures-only-acquire-isolated-branches
  (test-support/with-database (fn [_] nil))
  (let [base @(deref #'test-support/database-base)
        base-connection (::test-support/connection base)
        projection (db/carried-projection (db/db base-connection))
        branches (d/branches base-connection)
        holders (::test-support/holders @(deref #'test-support/base-state))
        counts (atom {})
        observations {#'cluster/populate-source! ::population
                      #'seon.fn/build-manifest ::analysis
                      (requiring-resolve 'seon.sci.eval/build-base-ctx) ::sci-base
                      #'d/branch! ::branch
                      #'d/delete-branch! ::delete-branch}
        wrappers (into {}
                       (map (fn [[v phase]]
                              (let [original @v]
                                [v (fn [& arguments]
                                     (swap! counts update phase (fnil inc 0))
                                     (apply original arguments))])))
                       observations)
        run (fn [options body]
              (test-support/await-event!
               (future (test-support/with-database options body))
               ::ordinary-fixture-completion))]
    (with-redefs-fn wrappers
      (fn []
        (run {::test-support/extra-schema (test-support/file-store-probe-schema ::warm-marker)}
             (fn [connection]
               (test-support/transacted! connection [{::warm-marker "first"}])
               (is (= #{"first"} (test-support/file-store-markers connection ::warm-marker)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"intentional fixture body failure"
                             (run {} (fn [connection]
                                       (is (identical? projection (db/carried-projection (db/db connection))))
                                       (throw (ex-info "intentional fixture body failure" {}))))))
        (run {} (fn [connection]
                  (is (identical? projection (db/carried-projection (db/db connection))))
                  (is (not (contains? (:schema @connection) ::warm-marker)))))))
    (is (= {::branch 3 ::delete-branch 3} @counts)
        "Three branches, including the throwing body, replay no population, analysis or SCI base construction.")
    (is (= branches (d/branches base-connection)))
    (is (integer? holders) "The observed base must carry its actual hold count.")
    (is (= holders (::test-support/holders @(deref #'test-support/base-state))))
    (is (identical? base @(deref #'test-support/database-base)))))

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

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  the-canonical-base-opens-the-published-store
  ;; The base already contains indexed declarations. Opening a fixture must
  ;; never invoke population, including when callers omit its path.
  (let [create-base (ns-resolve 'seon.test-support 'create-base)
        close-base! (ns-resolve 'seon.test-support 'close-base!)
        base (create-base nil)]
    (try
      (let [connection (:seon.test-support/connection base)
            database (db/db connection)]
        (is (some? (db/carried-projection database))
            "the fresh base carries the projection its writes validate against")
        (is (seq (db/q '[:find [?key ...]
                         :where [_ :seon.schema/key ?key]]
                       database))
            "with the canonical schema rows populated")
        (is (seq (db/q '[:find [?sym ...]
                         :where [_ :seon.fn/sym ?sym]]
                       database))
            "and the published program graph is present")
        (is (string?
             (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                   database))
            "and the publication is sealed"))
      (finally (close-base! base)))))

;;; ---------------------------------------------------------------------------
;;; A test owns nothing global — including a live cluster's custody
;;; ---------------------------------------------------------------------------

(deftest ^{:seon.test/platform
           "Moving part: the custody an in-process test body inherits from a live cluster."}
  a-test-body-inherits-no-ambient-cluster-custody
  ;; THE ROOT CAUSE of the 2026-09-17 registry leak: an in-process test ran on
  ;; a thread that still carried the agent evaluation's `seon.db` custody, so a
  ;; fixture helper using an elided arity wrote the LIVE cluster's datoms and
  ;; nothing said so. Without the bindings the elided arity refuses and names
  ;; what it needed.
  (test-support/with-database
    (fn [connection]
      (let [refusal (db/call-without-custody #(db/transact! {:tx-data []}))]
        (is ((schema/projection-validator
              (schema/handed-projection) :seon.schema/validation-refusal) refusal)
            "an elided write with no custody is a typed refusal, never a silent write")
        (is (= :seon.db/connection (:seon.schema/expected-value refusal)))
        (is (str/includes? (:seon.error/message refusal) "connection")
            "the refusal names what was missing"))
      (is (map? (db/transact! connection {:tx-data []}))
          "an explicit connection still writes its own branch"))))

(defn- publication-base
  "One cheap stand-in base carrying the publication it was constructed under.

  `retrying-base` knows nothing about the canonical population: it keys, holds
  and retires whatever `construct` returns, and `close-base!` releases it. A
  synthetic construction therefore proves the cache-miss and retirement rules
  without paying the canonical base's construction each time.

  The marker is the publication's own VALUE. It was briefly `pr-str`'d, and
  that compared printed BYTES across threads: construction runs on the base's
  daemon thread, where `*print-namespace-maps*` holds its root `false`, while
  the assertion ran in a worker whose test thread has the REPL's `true`, so one
  value printed two ways and the regression failed cold only."
  [published constructed]
  (fn []
    (let [publication @published
          configuration {:store {:backend :memory :id (random-uuid)}
                         :keep-history? true
                         :schema-flexibility :read}
          _ (d/create-database configuration)
          connection (d/connect configuration)]
      (d/transact connection [{:db/id -1
                               :seon.test-support-test/publication
                               (first (:seon.source/commit-id publication))}])
      (swap! constructed conj publication)
      {:seon.test-support/configuration configuration
       :seon.test-support/connection connection
       :seon.test-support/closed (atom false)})))

(defn- base-publication-datom
  "The publication marker this base carries, as DATA."
  [base]
  (d/q '[:find ?publication .
         :where [_ :seon.test-support-test/publication ?publication]]
       @(:seon.test-support/connection base)))

(deftest ^{:seon.test/platform
           "Moving part: the shared base every other test forks through must
            follow the publication its run executes under."}
  the-shared-base-follows-the-published-commit
  ;; The defect this kills: a base built once per JVM keeps the program rows
  ;; and contracts of the publication current when it was first forced, so an
  ;; adopted accreted arity is refused INSIDE a run while the same call answers
  ;; from the prepl. The publication key makes a converged adoption a cache
  ;; miss by construction; nothing rebuilds a base by hand.
  (let [published (atom {:seon.source/commit-id #{:first}})
        constructed (atom [])
        base (#'test-support/retrying-base #(deref published)
                                           (publication-base published constructed))
        first-held (test-support/acquire-base! base)
        first-base (:seon.test-support/value first-held)]
    (try
      (is (= {:seon.source/commit-id #{:first}}
             (:seon.test-support/publication-key first-base))
          "the base records the publication it was built from")
      (is (= :first (base-publication-datom first-base)))
      (let [again (test-support/acquire-base! base)]
        (is (identical? first-base (:seon.test-support/value again))
            "an unchanged publication is the same base")
        (test-support/release-base! base again))
      ;; the published commit advances, exactly as development adoption does
      (reset! published {:seon.source/commit-id #{:second}})
      (let [second-held (test-support/acquire-base! base)
            second-base (:seon.test-support/value second-held)]
        (try
          (is (= {:seon.source/commit-id #{:second}}
                 (:seon.test-support/publication-key second-base))
              "the next run's base is built from the new publication")
          (is (= :second (base-publication-datom second-base))
              "and carries a row only the new publication has")
          (is (= [{:seon.source/commit-id #{:first}}
                  {:seon.source/commit-id #{:second}}]
                 @constructed)
              "exactly one construction per publication")
          ;; A run still holding the old base completes on it: Datahike refuses
          ;; to delete a branch under an active connection, so retirement must
          ;; wait for the last holder.
          (is (false? @(:seon.test-support/closed first-base)))
          (is (= :first (base-publication-datom first-base)))
          (test-support/release-base! base first-held)
          (is (true? (test-support/await-event!
                      (:seon.test-support/closed first-base)
                      :seon.test-support-test/retired-base-closed
                      true?))
              "the retired base is closed once its last holder releases it")
          (is (false? @(:seon.test-support/closed second-base))
              "the base this run holds is untouched by that retirement")
          (is (= :second (base-publication-datom second-base)))
          (finally
            (test-support/release-base! base second-held)
            (#'test-support/close-base! second-base))))
      (finally
        (#'test-support/close-base! first-base))))
  ;; THE WORKER PATH. An isolated worker's `seon.test.published-base` snapshot
  ;; is immutable for the JVM's life, so its key never moves: there is nothing
  ;; to follow, the base is constructed once, and it stays realized across the
  ;; whole run. That is the same object under a constant key.
  (let [constructed (atom [])
        published (atom {:seon.source/commit-id #{:snapshot}})
        base (#'test-support/retrying-base (constantly
                                            {:seon.test-support/published-base
                                             "/snapshot/checkout"})
                                           (publication-base published constructed))
        held (test-support/acquire-base! base)
        value (:seon.test-support/value held)]
    (try
      (is (true? (realized? base)))
      (reset! published {:seon.source/commit-id #{:ignored}})
      (let [again (test-support/acquire-base! base)]
        (is (identical? value (:seon.test-support/value again))
            "an immutable snapshot key never misses, whatever else moves")
        (is (true? (realized? base))
            "and the worker's base stays realized across its whole run")
        (test-support/release-base! base again))
      (is (= 1 (count @constructed)) "exactly one construction in a worker")
      (is (= :snapshot (base-publication-datom value)))
      (is (false? @(:seon.test-support/closed value))
          "nothing is retired while the key stands")
      (finally
        (test-support/release-base! base held)
        (#'test-support/close-base! value)))))

(deftest ^{:seon.test/platform
           "Moving part: the derivation that decides whether a shared base is
            still coherent with the program its run executes."}
  the-publication-key-derives-from-the-published-head
  (let [first-commit (random-uuid)
        second-commit (random-uuid)]
    (is (= {:seon.test-support/published-base "/snapshot/checkout"}
           (#'test-support/publication-key "/snapshot/checkout"
                                           (sorted-set first-commit)))
        "an isolated worker's snapshot is immutable for the JVM's life")
    (is (= {:seon.source/commit-id (sorted-set first-commit)}
           (#'test-support/publication-key nil (sorted-set first-commit)))
        "a development JVM keys on the `:current-src` head it runs under")
    (is (not= (#'test-support/publication-key nil (sorted-set first-commit))
              (#'test-support/publication-key nil (sorted-set second-commit)))
        "an advanced head is a different publication, hence a cache miss")
    (is (= {:seon.test-support/published-base :seon.test-support/no-store}
           (#'test-support/publication-key nil nil))
        "nothing observable is its own key member, never a silent nil")
    (is (= (#'test-support/publication-key)
           (#'test-support/publication-key
            (System/getProperty "seon.test.published-base")
            (#'test-support/published-commit-ids)))
        "the supplied arity is the whole derivation")
    ;; Which case this JVM is in is DERIVED from the same facts the mechanism
    ;; reads, never a flag: an isolated worker has the snapshot property and no
    ;; store head can move its key; a development JVM has held stores.
    (let [snapshot (System/getProperty "seon.test.published-base")
          commits (#'test-support/published-commit-ids)]
      (cond
        snapshot
        (do (is (= {:seon.test-support/published-base snapshot}
                   (#'test-support/publication-key))
                "a worker's key IS its immutable snapshot")
            (is (= (#'test-support/publication-key)
                   (#'test-support/publication-key snapshot commits))
                "and no store head this JVM holds can move it"))
        commits
        (do (is (= {:seon.source/commit-id commits}
                   (#'test-support/publication-key))
                "a development JVM keys on the heads it holds")
            (doseq [commit commits]
              (is (uuid? commit) "a head this JVM holds is a commit ID")))
        :else
        (is (= {:seon.test-support/published-base :seon.test-support/no-store}
               (#'test-support/publication-key))
            "no snapshot and no held store is its own key member")))
    (is (identical? @test-support/source-manifest @test-support/source-manifest)
        "the manifest is derived once per publication, not per fixture")))
