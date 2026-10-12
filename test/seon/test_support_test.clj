(ns seon.test-support-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.cluster.process :as process]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [seon.profile :as profile]
            [malli.core :as m]
            [malli.instrument :as mi]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.render.value :as value]
            [seon.batch]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]
            [seon.test.runner :as runner]
            [seon.turn.world :as world]))

(deftest ^{:seon/tasks #{'seon.test-support/file-backed-branch-without-republication}
           :seon.test/long "Three acquisitions include whole-backend synchronization; measured samples took 28.85–32.33 s and 1.39 GB allocated each."
           :seon.test/long-ms 180000}
  a-file-backed-fixture-costs-a-branch
  (dotimes [sample 3]
    (let [root (io/file "tmp" "round-waste-fixture" (str (random-uuid)))
          costs [:seon.profile/ms :seon.profile/alloc-bytes]]
      (try
        (let [measured
              (profile/measure
               #(test-support/with-published-file-database
                 root :round-waste-fixture
                 (fn [connection]
                   (some? (db/pull (db/db connection) [:seon.fn/sym]
                                  [:seon.fn/sym 'seon.id/id])))
                 (fn [stage f]
                   (let [m (profile/measure f)]
                     (println (value/core-text {:sample sample :stage stage :cost (select-keys m costs)} 0))
                     (:seon.profile/value m)))))]
          (println (value/core-text {:sample sample :stage :fixture :cost (select-keys measured costs)} 0))
          (is (true? (:seon.profile/value measured)))
          (is (< (:seon.profile/ms measured) 60000))
          (is (< (:seon.profile/alloc-bytes measured) 3000000000)))
        (finally (test-support/delete-recursively! root))))))

(deftest ^{:seon.test/long "Acquires a private file frontend under the fixture cost proof's measured budget."
           :seon.test/long-ms 60000}
  a-file-fixture-inherits-program-and-isolates-writes
  (let [parent (:seon.db/connection (test-support/execution-handle db/*conn*))
        selector [:seon.fn/sym :seon.program/definition-digest]
        declaration (db/pull (db/db parent) selector [:seon.fn/sym 'seon.id/id])
        cluster-name (str "fixture-private-" (random-uuid))
        root (io/file "tmp" "file-fixture-isolation" (str (random-uuid)))]
    (try
      (test-support/with-published-file-database
       root :file-fixture-isolation
       (fn [connection]
         (is (= declaration (db/pull (db/db connection) selector [:seon.fn/sym 'seon.id/id])))
         (test-support/seed-cluster! connection cluster-name)
         (is (= cluster-name (:seon.cluster/name
                             (db/pull (db/db connection) [:seon.cluster/name]
                                      [:seon.cluster/name cluster-name]))))
         (is (.isDirectory (io/file root "store")))))
      (is (nil? (db/pull (db/db parent) [:seon.cluster/name] [:seon.cluster/name cluster-name])))
      (finally (test-support/delete-recursively! root)))))

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
                   {:seon.test-support/extra-schema [{:seon.task/intent 42}]}
                   (fn [_] (reset! body-ran true)))
                  (catch clojure.lang.ExceptionInfo error (ex-data error)))]
    (is (false? @body-ran))
    (is (string? (:seon.db.write.attempt/request-id failure)))
    (is (= [0 :seon.task/intent] (:seon.db/path failure)))
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
  ;; the offending entity, instead of an empty world several assertions later.
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
       (is (= [0 {:seon.agent/id "refused-fixture-write"
                  :seon.agent/not-an-installed-attribute 1}]
              (:seon.test-support/offending-entity (ex-data failure)))
           "the failure names the authored entity, not the schema it was read against")
       (is (nil? (db/q '[:find ?id . :where [_ :seon.agent/id ?id]]
                       (db/db connection)))
           "the refused entity seeded nothing")
       (let [report (test-support/transacted!
                     connection (test-support/agent-tx @connection "admitted-fixture-write"))]
         (is (some? (:db-after report)))
         (is (= "admitted-fixture-write"
                (db/q '[:find ?id . :where [_ :seon.agent/id ?id]]
                      (db/db connection)))))))))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  a-long-refusal-is-stored-whole-and-shown-bounded
  ;; The original value stays whole; only the shown text is clipped, by the
  ;; floor (CLAUDE.md "three points"; root ruling 2026-09-27). Write
  ;; admission's refusal carries the entity schema it was read against, so it
  ;; is longer than any display bound: the stored failure keeps the whole
  ;; refusal as its error value in the blob tier, and the reporter shows the
  ;; floor's bounded text beside that reference.
  (test-support/with-database
   (fn [connection]
     (let [failure (try
                     (test-support/transacted!
                      connection
                      [{:seon.cluster/name "unclipped-refusal-fixture"}])
                     nil
                     (catch clojure.lang.ExceptionInfo error error))
           options (#'runner/report-options)
           reported (#'runner/report-value options failure)
           report (#'runner/failure-report options {:type :error :actual failure})
           shown (:seon.test.failure/actual report)
           database (db/db connection)
           test-symbol (first (sort (db/q '[:find [?symbol ...] :where [_ :seon.test/sym ?symbol]] database)))
           run (runner/provenance database)
           committed (runner/commit-results!
                      connection
                      {:seon.test.run/provenance run
                       :seon.test/run-basis-t (:seon.test.run/basis-t run)
                       :seon.test/run-at (:seon.test.run/at run)
                       :seon.test.run/branch :current-src
                       :seon.db/db database
                       :seon.test.runner/results
                       [{:seon.test/sym test-symbol :seon.test/pass-count 0
                         :seon.test/fail-count 0 :seon.test/error-count 1
                         :seon.test.failure/reports [report]}]})
           digest (db/q '[:find ?digest . :in $ ?shown
                          :where [?f :seon.test.failure/actual ?shown]
                                 [?f :seon.test.failure/actual-blob ?digest]]
                        (db/db connection) shown)
           ;; the blob is the thrown value's artifact, read back as its value
           artifact (some->> digest (blob/get connection) value/read-artifact)
           whole (when (:seon.sci.admit/print-node artifact) (value/artifact-value artifact))]
       (is (some? failure) "the fixture write is refused")
       (is (vector? committed))
       (is (= reported shown) "the stored shown text is the reporter's text")
       (is (<= (count reported) (inc (:throwable-chars @#'seon.render.value/text-limits)))
           "the shown text is bounded by the floor")
       (is (string? digest) "the stored failure references its whole value")
       (is (nil? (:seon.render.value/unreadable-artifact artifact)))
       (is (= (ex-message failure) (:seon.error/message (first (:seon.error/chain whole))))
           "the whole refusal is retrievable from the stored failure as its error value")))))


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
                   (schema/canonical-schema-entities packaged-forms))
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
                    (schema/canonical-schema-entities packaged-forms)))
        (is (= expected-schema-keys (set actual-schema-keys)))
        (is (= (->> (db/identity-attributes database)
                    (filter #(seq (db/datoms database :avet %)))
                    vec)
               (db/populated-identity-attributes database))
            "the browse catalogue derives from installed identities in datoms")
        ;; A member world holds boot's cluster facts, written on its
        ;; checkpoint by this process (`seon.test/prepare-checkpoint!`).
        (is (= #{cluster/boot-process-identity
                 config/managing-process-identity
                 (process/id (process/current-identity))}
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
  explicit-synthetic-schema-entities-extend-only-that-database
  (let [options
        {::test-support/extra-schema
         (test-support/file-store-probe-schema ::marker)}]
    (is (= #{"installed"}
           (test-support/with-database
             options
             (fn [connection]
               (db/transact! connection [{::marker "installed"}])
               (test-support/file-store-markers connection ::marker))))
        "the leased database sees its own synthetic marker entities")
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
    (is (= [3] (:actual (last @reports)))
        "the reported failure's actual is the smallest failing input itself, a value")
    (is (str/includes? (:message (last @reports)) "Seed 20260728")
        "the message is prose naming the seed needed to replay the counterexample")))

(deftest ^{:seon.test/platform
           "Moving part: the one test bracket every other test forks through."}
  a-falsified-property-reports-its-smallest-input-as-the-actual-value
  (let [input "a\\b"
        failed (tc/quick-check 5 (prop/for-all [value (gen/return input)] (not= value input)) :seed 1)
        reports (atom [])]
    (binding [test/report #(swap! reports conj %)]
      (test-support/assert-check! failed))
    (is (= [input] (:actual (last @reports)))
        "the actual is the generated value itself, so the value printer prints its backslash once")))

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

(deftest a-cluster-handle-takes-its-dials-from-its-worlds-effective-config
  ;; CLASS: `cluster-handle` took every dial from the shipped defaults, so a
  ;; test's manifest config never reached a host or agent built from its
  ;; handle. The handle naming a connection and a cluster reads that world's
  ;; effective config through seon.config; one naming no cluster world keeps
  ;; the shipped decisions; a caller-supplied dial still wins.
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "handle-config" {:seon.config.eval/time-limit-ms 1234})
     (let [effective (config/effective (db/db connection) "handle-config")
           handle (test-support/cluster-handle {:seon.db/connection connection
                                                :seon.cluster/name "handle-config"})]
       (is (= 1234 (:seon.config.eval/time-limit-ms effective)) "the manifest reached the world")
       (is (= 1234 (:seon.config.eval/time-limit-ms handle)) "and the handle built from it")
       (is (= (config/result-caps effective) (:seon.sci.admit/caps handle)))
       (is (= 77 (:seon.config.eval/time-limit-ms
                  (test-support/cluster-handle {:seon.db/connection connection
                                                :seon.cluster/name "handle-config"
                                                :seon.config.eval/time-limit-ms 77})))
           "the caller wins"))
     (is (= (:seon.config.eval/time-limit-ms config/defaults)
            (:seon.config.eval/time-limit-ms (test-support/cluster-handle {:seon.db/connection connection})))
         "a handle naming no cluster keeps the shipped decisions"))))

(deftest a-seeded-cluster-records-the-source-commit-a-fork-records
  ;; CLASS: `seed-cluster!` wrote no :seon.source/commit-id, so every agent test
  ;; request in a seeded world refused "The cluster records no adopted source
  ;; commit". It now writes the entity a fork records (registry/fork-entity) with the
  ;; executing member's cluster's adopted commit.
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "seeded-source")
     (let [database (db/db connection)
           own (:seon.source/commit-id
                (db/pull database [:seon.source/commit-id]
                         [:seon.cluster/name (:seon.cluster/name (test-support/execution-handle connection))]))
           seeded (db/pull database [:seon.source/commit-id {:seon.cluster/config [:seon.config/cluster]}]
                           [:seon.cluster/name "seeded-source"])]
       (is (uuid? own) "the executing member's cluster names its adopted commit")
       (is (= own (:seon.source/commit-id seeded)))
       (is (= "seeded-source" (get-in seeded [:seon.cluster/config :seon.config/cluster])))))))

(deftest a-code-request-batch-evaluates-each-form
  (test-support/with-database
   (fn [connection]
     (let [world (world/world {:seon.db/connection connection :seon.boot/cluster-name "default"
                                     :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection)) (db/db connection) connection)
 :seon.batch/sink (seon.batch/sink) :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms seon.config/defaults) :seon.agent/id "code-request"})
           batch (seon.batch/evaluate-batch
                  (dissoc (test-support/code-request {:seon.batch/world world :seon.batch/text "(inc 1) (inc 2)"})
                          :seon.batch/settle))]
       (is (= [2 3] (mapv :seon.batch/value (:seon.batch/entries batch))))))))

(deftest a-running-agent-cluster-publishes-into-its-own-checkout
  (test-support/with-running-agent
    (fn [_running _agent-id connection]
      (let [root ((requiring-resolve 'seon.merge/checkout) (db/db connection)
                  (:seon.cluster/name (test-support/execution-handle connection)))
            live (.getCanonicalPath (io/file (System/getProperty "user.dir")))]
        (is (string? root) "the fixture cluster names a working root")
        (is (not= live root) "a fixture cluster never publishes into the live checkout")
        (is (.isDirectory (io/file root)))))))
