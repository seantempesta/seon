(ns seon.test-runner-test
  "Declared latest-result facts owned by the JVM test runner."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [seon.test.cache :as cache]
            [dev-cache]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.env :as env]
            [seon.error :as error]
            [seon.instrument :as instrument]
            [seon.fn :as functions]
            [seon.sci.eval :as eval]
            [seon.cluster.boot-test]
            [seon.test-runner-failure-fixture]
            [seon.test.runner :as runner]
            [seon.test.arm :as arm]
            [seon.test-support :as test-support])
  (:import [java.io PrintWriter]
           [java.lang ProcessHandle]
           [java.util.concurrent CountDownLatch TimeUnit]))

(deftest ^{:seon.test/platform "Coordinator consumes the prepared worker count."}
  coordinator-uses-the-prepared-worker-count
  (is (= 2 (#'runner/worker-count 16 "2")))
  (is (= 1 (#'runner/worker-count 16 "1")))
  (is (= 8 (#'runner/worker-count 16 nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be positive"
                        (#'runner/worker-count 16 "0"))))

(def ^:private at (java.util.Date. 1785283200000))
(def ^:private git-sha (apply str (repeat 40 "a")))
(def ^:private run-id "test-run-1")
(def ^:private fake-cache-digest (apply str (repeat 64 "a")))
(def ^:private project-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(deftest ^{:seon.test/platform "Dependency cache identity excludes checkout locations."}
  dependency-source-digest-does-not-name-the-checkout
  (let [root (doto (io/file project-root "tmp" (str "digest-" (random-uuid))) .mkdirs)
        left (io/file root "left.clj")
        right (io/file root "right.clj")
        digest (fn [file]
                 (let [sha (java.security.MessageDigest/getInstance "SHA-256")]
                   (#'dev-cache/digest-file! sha file)
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

(deftest ^{:seon.test/platform "Published bases follow the selected first-party bytes."}
  snapshot-digest-follows-selected-bytes
  (let [root (doto (io/file project-root "tmp" (str "snapshot-digest-" (random-uuid))) .mkdirs)
        left (io/file root "left")
        right (io/file root "right")]
    (try
      (doseq [checkout [left right]]
        (.mkdirs (io/file checkout "src"))
        (spit (io/file checkout "dev_cache.clj") "identical cache owner")
        (spit (io/file checkout "src/selected.clj") "(ns selected)"))
      (let [digest (#'dev-cache/test-digest (str left) fake-cache-digest)]
        (is (= digest (#'dev-cache/test-digest (str right) fake-cache-digest)))
        (spit (io/file right "src/selected.clj") "(ns selected) (def changed true)")
        (is (not= digest (#'dev-cache/test-digest (str right) fake-cache-digest))))
      (finally (test-support/delete-recursively! root)))))

(declare stop-process-tree!)

(deftest ^{:seon.test/platform "Source edits do not rebuild dependency classes."}
  dependency-configuration-excludes-first-party-source
  (let [root (doto (io/file project-root "tmp" (str "dependency-inputs-" (random-uuid))) .mkdirs)
        log (io/file root "git.log")
        child (.start (doto (ProcessBuilder. ^java.util.List ["git" "init" "-q" (str root)])
                        (.redirectErrorStream true) (.redirectOutput log)))]
    (try
      (is (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS))
      (when-not (.isAlive child)
        (is (zero? (.exitValue child)) (slurp log))
        (spit (io/file root "deps.edn") "{:deps {}}")
        (.mkdirs (io/file root "src"))
        (spit (io/file root "src/fixture.clj") "(ns fixture)")
        (let [before (#'dev-cache/dependency-configuration-digest root)]
          (spit (io/file root "src/fixture.clj") "(ns fixture) (def changed true)")
          (is (= before (#'dev-cache/dependency-configuration-digest root)))
          (spit (io/file root "deps.edn") "{:deps {} :aliases {:test {}}}")
          (is (not= before (#'dev-cache/dependency-configuration-digest root)))))
      (finally
        (stop-process-tree! child)
        (test-support/delete-recursively! root)))))

;; NOT :seon.test/platform: the fixture reaches
;; seon.test-support/populate-published-root!, which deletes and reclones a
;; store directory. The platform tier runs first on every bin/test invocation,
;; so a destructive fixture there deletes before any evidence exists
;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).
(deftest ^{:seon.test/fixture-observation "Cache reuse is verified against a real published file store and its immutable manifest across launcher invocations."}
  consecutive-cache-invocations-reuse-the-published-base
  (let [root (doto (io/file project-root "tmp" (str "base-reuse-" (random-uuid))) .mkdirs)
        supplied (System/getProperty "seon.test.published-base")
        published (or supplied (str (io/file root "standalone/base")))]
    (try
      (when-not supplied
        ;; Direct iteration uses the same real publication fixture as boot tests.
        (test-support/populate-published-root! published)
        (spit (io/file published "manifest.edn") (pr-str @test-support/source-manifest))
        (spit (io/file root "standalone/ready.edn")
              (pr-str {:seon.test.cache/digest
                       (#'dev-cache/test-digest (str project-root)
                                                (System/getProperty "java.class.path"))})))
      (let [original-ready (io/file (.getParentFile (io/file published)) "ready.edn")
              ready-value (edn/read-string (slurp original-ready))
              digest (:seon.test.cache/digest ready-value)
              directory (doto (io/file root "target/test-published-bases" digest) .mkdirs)
              ready (io/file directory "ready.edn")
              base (io/file directory "base")
              before (cache/manifest published)]
          ;; Use the real published store read-only; only retention metadata
          ;; belongs to this regression. No fake database or publisher.
          (java.nio.file.Files/createSymbolicLink
           (.toPath base) (.toPath (io/file published))
           (make-array java.nio.file.attribute.FileAttribute 0))
          (spit ready (pr-str ready-value))
          (dotimes [ordinal 2]
            (let [log (io/file root (str ordinal ".log"))
                  child (.start
                         (doto (ProcessBuilder.
                                ^java.util.List
                                ["bb" "--config" (str (io/file project-root "bb.edn"))
                                 "-m" "seon.test.cache" (str root) (str project-root)
                                 digest (System/getProperty "java.class.path")
                                 (str (.pid (ProcessHandle/current)))])
                           (.directory project-root)
                           (.redirectErrorStream true)
                           (.redirectOutput log)))]
              (try
                (is (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS))
                (when-not (.isAlive child)
                  (is (zero? (.exitValue child)) (slurp log))
                  (is (str/includes? (slurp log) "REUSE cached base") (slurp log))
                  (is (not (str/includes? (slurp log) "PUBLISH cached base")) (slurp log)))
                (finally (stop-process-tree! child)))))
          (is (= ready-value (edn/read-string (slurp ready))))
          (is (= before (cache/manifest (str base))))
          (is (.isDirectory (io/file published "data/store"))))
      (finally (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/platform "Cache reclamation preserves live users and external files."}
  published-base-retention-preserves-live-users-and-symlink-targets
  (let [root (doto (io/file project-root "tmp" (str "base-retention-" (random-uuid))) .mkdirs)
        parent (doto (io/file root "cache") .mkdirs)
        linked-parent (io/file root "linked-cache")
        sentinel (doto (io/file root "sentinel") .mkdirs)
        now (System/currentTimeMillis)
        current (ProcessHandle/current)]
    (try
      (spit (io/file sentinel "keep") "untouched")
      (doseq [ordinal (range 6)]
        (let [directory (doto (io/file parent (str ordinal)) .mkdirs)
              ready (io/file directory "ready.edn")]
          (spit ready "{}")
          (is (.setLastModified ready (- now (* ordinal 1000))))))
      (let [live (io/file parent "5")
            references (doto (io/file live "references") .mkdirs)]
        (spit (io/file references "live.edn")
              (pr-str {:seon.test.cache/pid (.pid current)
                       :seon.test.cache/started
                       (str (.orElse (.startInstant (.info current)) nil))})))
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file parent "4" "sentinel")) (.toPath sentinel)
       (make-array java.nio.file.attribute.FileAttribute 0))
      (java.nio.file.Files/createSymbolicLink
       (.toPath linked-parent) (.toPath parent)
       (make-array java.nio.file.attribute.FileAttribute 0))
      (#'cache/reap! linked-parent)
      (is (= #{"0" "1" "2" "5"} (set (map #(.getName %) (.listFiles parent)))))
      (is (= "untouched" (slurp (io/file sentinel "keep"))))
      (is (.setLastModified (io/file parent "0/ready.edn")
                            (- now (* 25 60 60 1000))))
      (#'cache/reap! parent)
      (is (not (.exists (io/file parent "0"))))
      (is (.isDirectory (io/file parent "5")))
      (finally (test-support/delete-recursively! root)))))

(defn- captured-run-with-output []
  (let [writer (java.io.StringWriter.)
        result
        (binding [clojure.test/*test-out* writer]
          (runner/run!
           {:seon.test.runner/namespaces
            ['seon.test-runner-failure-fixture]
            :seon.test.run/id run-id
            :seon.test.run/at at
            :seon.test.run/git-sha git-sha}))]
    {::result result
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

(defn- next-task
  [tasks]
  (let [[before _] (swap-vals! tasks #(if (seq %) (subvec % 1) %))]
    (first before)))

(defn- await-created!
  [^java.nio.file.WatchService watcher filename]
  (loop []
    (let [watch-key (.poll watcher 10 TimeUnit/SECONDS)]
      (when-not watch-key
        (throw
         (ex-info "The child did not publish its filesystem event."
                  {:seon.test-runner/expected-file filename})))
      (let [created?
            (some #(= filename (str (.context ^java.nio.file.WatchEvent %)))
                  (.pollEvents watch-key))]
        (.reset watch-key)
        (if created?
          filename
          (recur))))))

(defn- install-single-worker-getconf!
  [fake-bin]
  (let [fake-getconf (io/file fake-bin "getconf")]
    (spit fake-getconf "#!/usr/bin/env bash\necho 2\n")
    (is (.setExecutable fake-getconf true false))))

(defn- stop-process-tree!
  [^Process process]
  (when (.isAlive process)
    (with-open [child-handles (.descendants (.toHandle process))]
      (run! #(.destroyForcibly ^java.lang.ProcessHandle %)
            (reverse (vec (iterator-seq (.iterator child-handles))))))
    (.destroyForcibly process)
    (.get (.onExit (.toHandle process))
          test-support/event-backstop-seconds TimeUnit/SECONDS)))

(defn- as-orchestrator
  "Give an isolated launcher fixture its declared caller role. The lane
  admission regression separately verifies inherited lane identity."
  [^ProcessBuilder builder]
  (.remove (.environment builder) "SEON_CODEX_LANE")
  (.put (.environment builder) "SEON_TEST_ORCHESTRATOR" "1")
  builder)

(def ^:private fake-dev-cache-prologue
  (str
   "if [ \"${1-}\" = \"-T:dev-cache\" ]; then\n"
   "  if [ -n \"${SEON_FAKE_CACHE_CALL-}\" ]; then\n"
   "    printf '%s\\n' \"$*\" >\"$SEON_FAKE_CACHE_CALL\"\n"
   "  fi\n"
   "  printf '\"fixture-classpath\"\\n' >\"$SEON_FAKE_CACHE_PATH/classpath.edn\"\n"
   "  printf '#:seon.dev-cache{:digest \"%s\", :test-digest \"%s\", :path \"%s\", :test-classpath-file \"%s/classpath.edn\"}\\n' \\\n"
   "    \"$SEON_FAKE_CACHE_DIGEST\" \"$SEON_FAKE_CACHE_DIGEST\" \"$SEON_FAKE_CACHE_PATH\" \"$SEON_FAKE_CACHE_PATH\"\n"
   "  exit 0\n"
   "fi\n"))

(defn- start-injected-worker!
  [case-name program]
  (let [root (doto (io/file project-root "tmp" "test-runner-exchange"
                            (str case-name "-" (random-uuid)))
               .mkdirs)
        logs (doto (io/file root "logs") .mkdirs)
        error-log (io/file logs "worker-stderr.log")
        process (.start
                 (doto
                  (ProcessBuilder.
                   ^java.util.List ["/usr/bin/python3" "-u" "-c" program
                                    (.getCanonicalPath root)])
                  (.redirectError error-log)))]
    {::runner/worker-id (str case-name)
     ::runner/worker-process process
     ::runner/worker-reader (io/reader (.getInputStream process))
     ::runner/worker-writer (PrintWriter. (.getOutputStream process) true)
     ::runner/worker-retired? (atom false)
     ::runner/worker-root (.getCanonicalPath root)
     ::runner/worker-error-log (.getCanonicalPath error-log)}))

(defn- stop-injected-worker!
  [worker]
  (let [process ^Process (::runner/worker-process worker)
        root (io/file (::runner/worker-root worker))]
    (when (.isAlive process)
      (.destroyForcibly process)
      (.get (.onExit (.toHandle process))
            test-support/event-backstop-seconds TimeUnit/SECONDS))
    (when (.exists root)
      (test-support/delete-recursively! root))))

(defn- exchange-task
  [case-name]
  {::runner/task-id (str case-name)
   ::runner/task-ordinal 1
   ::runner/task-symbols [(str "seon.exchange-test/" case-name)]})

(defn- execute-injected-task!
  [worker task]
  (#'runner/execute-worker-task!
   (atom {::runner/description "injected worker exchange"
          ::runner/at-nanos (System/nanoTime)
          ::runner/at (java.time.Instant/now)})
   worker task))

(defn- assert-one-terminal-error!
  [case-name result expected-kind]
  (is (= {::runner/test-count 1
          ::runner/pass-count 0
          ::runner/fail-count 0
          ::runner/error-count 1}
         (::runner/task-summary result))
      (str case-name " contributes one terminal task to the total tally"))
  (is (= 1 (count (::runner/task-results result))))
  (is (= (str "seon.exchange-test/" case-name)
         (:seon.test/sym (first (::runner/task-results result)))))
  (is (= expected-kind
         (get-in result [::runner/worker-exchange-result
                         :seon.error/kind])))
  (is (= [(str "seon.exchange-test/" case-name)]
         (get-in result [::runner/worker-exchange-result
                         ::runner/task-symbols]))))

(deftest the-gate-runs-under-the-contracts-a-cluster-runs-under
  (testing "a worker JVM arms instrumentation from the shipped decisions"
    (is (pos? (count (instrument/instrumented)))
        "bin/test worker JVMs arm contract instrumentation exactly as boot
         does; a gate that never asks the contract question reports health
         about a subject it cannot see")
    (let [failure (try (error/value "not a fact")
                       (catch Exception thrown thrown))]
      (is (= :seon.instrument/contract-violated
             (:seon.error/kind (ex-data failure)))
          "and a violated contract stops the call inside the gate, exactly
           as it does on every live cluster")))
  (testing "the armable set IS the set a cluster arms, derived the same way"
    ;; The gate's blind spot was namespaces no test happened to require:
    ;; `seon.artifact` and `seon.test` carried contracts every live cluster
    ;; enforced and this gate never asked about. Instrumentation selects
    ;; LOADED vars carrying `:malli/schema`, so the two sets are equal
    ;; exactly when every declared program namespace is loaded in this
    ;; worker — which is what `arm-contracts!` now guarantees.
    ;;
    ;; The comparison is deliberately over what is LOADED rather than over
    ;; `instrument/instrumented`: pooled workers run many tests per JVM and
    ;; `seon.instrument-test` legitimately arms and removes instrumentation,
    ;; so an armed-set snapshot here would be asserting another test's
    ;; timing (AGENTS §5: own nothing global).
    (let [program (set (#'runner/declared-program-namespaces))
          loaded (into #{} (filter find-ns) program)
          declared-contracts
          (into #{}
                (comp (mapcat (fn [namespace-name]
                                (some-> (find-ns namespace-name) ns-interns)))
                      (map val)
                      (filter (fn [candidate]
                                (some-> candidate meta :malli/schema))))
                program)]
      (is (pos? (count program))
          "the program namespaces are genuinely discovered, so an empty
           derivation cannot make this comparison trivially true")
      (is (= program loaded)
          "every namespace a live cluster loads is loaded here, so every
           contract it arms is armable here")
      (is (contains? loaded 'seon.artifact))
      (is (contains? loaded 'seon.test)
          "including the two the gate never used to reach")
      (is (contains? declared-contracts (find-var 'seon.test/run))
          "and `seon.test/run`, the agent-facing test verb, is among the
           declared contracts this worker can arm"))))

(deftest fast-and-worker-arm-the-complete-program-contract-set
  (is (identical? (var-get #'runner/arm-contracts!) #'arm/arm-contracts!)
      "The worker delegates to the exact arming Var used by test-fast.")
  ;; Run this same observation in test-fast and in the isolated worker.
  ;; Both enter initialize-contracts!; observe actual wrappers rather than
  ;; trusting its count or a hand-maintained list of expected functions.
  (let [program (#'runner/declared-program-namespaces)
        expected (instrument/armable program)
        actual (into #{} (filter expected) (instrument/instrumented))]
    (is (seq expected) "Absent contracts must not pass set equality.")
    (is (= expected actual))
    (is (= :seon.instrument/contract-violated
           (:seon.error/kind
            (ex-data (try (error/value "not a fact")
                          (catch Exception failure failure))))))))

(deftest a-worker-rearms-only-when-a-task-stripped-its-contracts
  ;; CLASS: a pooled worker runs many tests per JVM, and `instrument/remove!`
  ;; is total by design. One suite proving removal — or one arming a narrow
  ;; filter and stripping everything in its `finally` — left every LATER task
  ;; in that worker unarmed, so those tasks asserted the earlier suite's
  ;; timing rather than their own subject: red in the pool, green in
  ;; isolation, and the confirmation phase's `parallel-only` verdict was the
  ;; only thing that said so.
  ;;
  ;; The armed state is DERIVED at the seam that admits the work rather than
  ;; remembered from initialization, so nothing has to be declared and nothing
  ;; can drift.
  (let [reassert! (ns-resolve 'seon.test.runner 'reassert-contracts!)
        arm-var (ns-resolve 'seon.test.runner 'arm-contracts!)
        arms (atom [])
        arming {:seon.test.runner/projection {:seon.schema.projection/forms {}}
                :seon.test.runner/namespaces '[seon.db]
                ;; THE DECISION IS CARRIED, NOT RE-DERIVED. Asking
                ;; `config/result-caps` again under the contracts the first
                ;; arm installed killed the worker outright, and every
                ;; namespace it held was reported red against its own owner.
                :seon.test.runner/decision {:seon.test.runner/decisions {}
                                            :seon.test.runner/caps {}
                                            :seon.test.runner/program '[seon.db]}
                :seon.test.runner/instrumented 3}
        re-arm-with
        (fn [installed]
          (with-redefs-fn
            {arm-var (fn [decision projection worker-id namespaces]
                       (swap! arms conj [worker-id (count namespaces)
                                         (contains?
                                          projection
                                          :seon.schema.projection/forms)
                                         (contains?
                                          decision
                                          :seon.test.runner/caps)])
                       {:seon.instrument/instrumented 3})
             #'instrument/instrumented (constantly (set (range installed)))}
            #(reassert! arming "pool-1")))]
    (testing "a stripped worker re-arms before the next task"
      (re-arm-with 0)
      (is (= [["pool-1" 1 true true]] @arms)
          "re-armed once, with the worker's own projection, namespaces and
           the arming decision it made BEFORE any contract was installed"))
    (testing "an intact worker does not re-arm"
      (re-arm-with 3)
      (is (= 1 (count @arms))))
    (testing "a worker carrying EXTRA wrappers is left alone"
      ;; a test arming a filter of its own is expected to undo it; re-arming
      ;; over that would fight the subject rather than protect it.
      (re-arm-with 5)
      (is (= 1 (count @arms))))
    (testing "before initialization there is nothing to re-arm"
      (with-redefs-fn
        {arm-var (fn [& _] (throw (ex-info "must not arm" {})))}
        #(is (nil? (reassert! nil "pool-1")))))))

(deftest root-owning-tasks-never-co-run-inside-one-worker-group
  (let [group-a-tasks (atom [:a-1 :a-2])
        group-b-tasks (atom [:b-1])
        group-a-active (atom 0)
        group-a-maximum (atom 0)
        total-active (atom 0)
        cross-group-overlap? (atom false)
        a-started (CountDownLatch. 1)
        b-started (CountDownLatch. 1)
        release-a (CountDownLatch. 1)
        execute-a
        (fn [task]
          (let [active (swap! group-a-active inc)]
            (swap! group-a-maximum max active)
            (when (> (swap! total-active inc) 1)
              (reset! cross-group-overlap? true))
            (try
              (when (= :a-1 task)
                (.countDown a-started)
                (is (.await b-started 10 TimeUnit/SECONDS)
                    "the disjoint worker group must be able to overlap")
                (is (.await release-a 10 TimeUnit/SECONDS)))
              task
              (finally
                (swap! total-active dec)
                (swap! group-a-active dec)))))
        execute-b
        (fn [task]
          (is (.await a-started 10 TimeUnit/SECONDS))
          (when (> (swap! total-active inc) 1)
            (reset! cross-group-overlap? true))
          (.countDown b-started)
          (.countDown release-a)
          (swap! total-active dec)
          task)
        group-a
        (future
          (#'runner/drain-worker-tasks!
           #(next-task group-a-tasks) execute-a))
        group-b
        (future
          (#'runner/drain-worker-tasks!
           #(next-task group-b-tasks) execute-b))]
    (is (= [:a-1 :a-2] @group-a))
    (is (= [:b-1] @group-b))
    (is (= 1 @group-a-maximum)
        "one root-owning worker group has no concurrent execution shape")
    (is (true? @cross-group-overlap?)
        "distinct root-owning worker groups remain deliberately concurrent")))

(deftest isolated-confirmations-overlap-with-bounded-parallelism
  (let [started (CountDownLatch. 2)
        release (CountDownLatch. 1)
        confirm!
        (fn [_progress result]
          (.countDown started)
          (is (.await started 10 TimeUnit/SECONDS)
              "both independent confirmations must start before either exits")
          (is (.await release 10 TimeUnit/SECONDS))
          (assoc result ::runner/parallel-failure :parallel-only))
        task-results
        [{::runner/task-id :first
          ::runner/task-summary {::runner/fail-count 1
                                 ::runner/error-count 0}}
         {::runner/task-id :pass
          ::runner/task-summary {::runner/fail-count 0
                                 ::runner/error-count 0}}
         {::runner/task-id :second
          ::runner/task-summary {::runner/fail-count 0
                                 ::runner/error-count 1}}]
        run (future
              (#'runner/confirm-task-results!
               2 nil #{:first :second} task-results confirm!))]
    (is (.await started 10 TimeUnit/SECONDS)
        "confirmation scheduling must not serialize clean JVM launches")
    (.countDown release)
    (let [confirmed (deref run 10000 ::confirmation-backstop)]
      (is (not= ::confirmation-backstop confirmed))
      (is (= [:first :pass :second]
             (mapv ::runner/task-id confirmed))
          "concurrent confirmation preserves task attribution order")
      (is (= [:parallel-only nil :parallel-only]
             (mapv ::runner/parallel-failure confirmed))))))

(deftest unlaunchable-confirmation-worker-does-not-suppress-the-tally
  (let [task-results
        [{::runner/task-id :unlaunchable
          ::runner/task-ordinal 7
          ::runner/task-symbols ["seon.example-test/unlaunchable"]
          ::runner/task-summary {::runner/test-count 1
                                 ::runner/pass-count 2
                                 ::runner/fail-count 1
                                 ::runner/error-count 0}}
         {::runner/task-id :green
          ::runner/task-ordinal 8
          ::runner/task-symbols ["seon.example-test/green"]
          ::runner/task-summary {::runner/test-count 1
                                 ::runner/pass-count 3
                                 ::runner/fail-count 0
                                 ::runner/error-count 0}}]
        confirmed* (atom nil)
        summary* (atom nil)
        output
        (with-out-str
          (let [confirmed
                (#'runner/confirm-task-results!
                 1 nil #{:unlaunchable} task-results
                 (fn [_progress _result]
                   (throw
                    (ex-info
                     "Injected confirmation process launch refusal."
                     {:seon.error/kind
                      ::runner/worker-launch-failure
                      ::runner/injected? true}))))
                summary (#'runner/summarize-task-results confirmed)]
            (reset! confirmed* confirmed)
            (reset! summary* summary)
            (#'runner/print-final-tally! summary confirmed)))
        unconfirmed (first @confirmed*)
        failure (::runner/confirmation-failure unconfirmed)]
    (is (= #:seon.test.runner{:test-count 2
                              :pass-count 5
                              :fail-count 1
                              :error-count 0}
           @summary*)
        "confirmation launch is outside the already-complete bulk tally")
    (is (= :unconfirmed (::runner/parallel-failure unconfirmed)))
    (is (= :seon.test.runner/confirmation-worker-launch-failure
           (:seon.error/kind failure)))
    (is (= ["seon.example-test/unlaunchable"]
           (::runner/task-symbols failure))
        "the typed launch failure carries the task known before readiness")
    (is (str/includes? output "Ran 2 tests containing 6 assertions."))
    (is (str/includes? output "1 failures, 0 errors."))
    (is (str/includes? output "Unconfirmed tasks — 1 task(s)")
        "the tally counts unconfirmed work rather than listing it silently")
    (is (str/includes? output " - seon.example-test/unlaunchable")
        "and names the task, so the reader is not left with a bare count")
    (is (str/includes? output "[INJECTED FIXTURE]")
        "fixture output cannot be mistaken for a production launch line")))

(deftest exit-before-readiness-is-one-attributed-terminal-value
  (let [worker (start-injected-worker! "readiness-exit"
                                       "import sys; sys.exit(17)\n")]
    (try
      (let [result
            (#'runner/worker-exchange!
             {::runner/worker worker
              ::runner/exchange-id "readiness-exit/readiness"
              ::runner/expected-worker-event :ready
              ::runner/completion-bound-seconds 1})]
        (is (= ::runner/worker-exited (:seon.error/kind result)))
        (is (= "readiness-exit" (::runner/worker-id result)))
        (is (= 17 (::runner/worker-exit result)))
        (is (= :ready (::runner/missing-worker-event result)))
        (is (true? @(::runner/worker-retired? worker))))
      (finally
        (stop-injected-worker! worker)))))

(deftest kill-after-command-acceptance-is-one-attributed-task-result
  (let [accepted-name "accepted"
        worker
        (start-injected-worker!
         "killed"
         (str "import os, signal, sys\n"
              "root = sys.argv[1]\n"
              "sys.stdin.readline()\n"
              "open(os.path.join(root, '" accepted-name "'), 'w').write('accepted')\n"
              "signal.pause()\n"))
        root (io/file (::runner/worker-root worker))
        watcher (.newWatchService (java.nio.file.FileSystems/getDefault))]
    (try
      (.register (.toPath root)
                 watcher
                 (into-array java.nio.file.WatchEvent$Kind
                             [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE]))
      (let [result-future
            (future (execute-injected-task! worker (exchange-task "killed")))
            _ (is (= accepted-name (await-created! watcher accepted-name)))
            process ^Process (::runner/worker-process worker)]
        (.destroyForcibly process)
        (.get (.onExit (.toHandle process))
              test-support/event-backstop-seconds TimeUnit/SECONDS)
        (let [result (deref result-future
                            (* 1000 test-support/event-backstop-seconds)
                            ::task-backstop)]
          (is (not= ::task-backstop result))
          (assert-one-terminal-error! "killed" result
                                      ::runner/worker-exited)))
      (finally
        (.close watcher)
        (stop-injected-worker! worker)))))

(deftest a-worker-dying-during-re-arm-names-the-re-arm
  (let [worker
        (start-injected-worker!
         "re-arm-death"
         (str "import os, sys\n"
              "sys.stdin.readline()\n"
              "print('SEON_TEST_WORKER_EDN {:seon.test.runner/worker-event :re-arming :seon.test.runner/worker-id \\\"re-arm-death\\\" :seon.test.runner/exchange-id \\\"re-arm-death\\\"}', flush=True)\n"
              "os._exit(17)\n"))]
    (try
      (let [result (execute-injected-task! worker (exchange-task "re-arm-death"))]
        (assert-one-terminal-error! "re-arm-death" result ::runner/re-arm-failed)
        (is (= 17 (get-in result [::runner/worker-exchange-result
                                  ::runner/worker-exit])))
        (is (= :worker-exchange
               (#'runner/parallel-failure-classification result
                {::runner/task-summary {::runner/fail-count 0
                                         ::runner/error-count 0}}))))
      (finally (stop-injected-worker! worker)))))

(deftest checked-write-failure-is-one-attributed-task-result
  (let [worker (start-injected-worker! "write-failure"
                                       "import sys; sys.exit(19)\n")
        process ^Process (::runner/worker-process worker)]
    (try
      (.get (.onExit (.toHandle process))
            test-support/event-backstop-seconds TimeUnit/SECONDS)
      (let [result (execute-injected-task! worker
                                           (exchange-task "write-failure"))]
        (assert-one-terminal-error! "write-failure" result
                                    ::runner/worker-write-failure))
      (finally
        (stop-injected-worker! worker)))))

(deftest live-worker-exceeding-its-bound-is-one-attributed-task-result
  (let [worker
        (start-injected-worker!
         "bounded"
         (str "import signal, sys\n"
              "sys.stdin.readline()\n"
              "signal.pause()\n"))]
    (try
      (let [result
            ;; Inject at the task admission bound, including its declared
            ;; body and priming terms, so this fixture trips in one second.
            (with-redefs-fn
              {#'runner/task-exchange-bound-seconds (constantly 1)}
              #(execute-injected-task! worker (exchange-task "bounded")))]
        (assert-one-terminal-error! "bounded" result
                                    ::runner/worker-exchange-bound)
        (is (false? (.isAlive ^Process (::runner/worker-process worker)))
            "the bounded worker is retired before another dispatch"))
      (finally
        (stop-injected-worker! worker)))))

(deftest ordinary-worker-reply-is-one-attributed-terminal-value
  (let [worker
        (start-injected-worker!
         "ordinary"
         (str
          "import sys\n"
          "sys.stdin.readline()\n"
          "print('SEON_TEST_WORKER_EDN {:seon.test.runner/worker-event :task-complete "
          ":seon.test.runner/worker-id \\\"ordinary\\\" "
          ":seon.test.runner/exchange-id \\\"ordinary\\\" "
          ":seon.test.runner/task-id \\\"ordinary\\\" "
          ":seon.test.runner/task-symbols [\\\"seon.exchange-test/ordinary\\\"] "
          ":seon.test.runner/task-summary {:seon.test.runner/test-count 1 "
          ":seon.test.runner/pass-count 1 :seon.test.runner/fail-count 0 "
          ":seon.test.runner/error-count 0} :seon.test.runner/task-results [] "
          ":seon.test.runner/task-elapsed-ms 1}', flush=True)\n"))]
    (try
      (let [result (execute-injected-task! worker (exchange-task "ordinary"))]
        (is (= {::runner/test-count 1
                ::runner/pass-count 1
                ::runner/fail-count 0
                ::runner/error-count 0}
               (::runner/task-summary result)))
        (is (= "ordinary" (::runner/task-id result)))
        (is (nil? (::runner/worker-exchange-result result))))
      (finally
        (stop-injected-worker! worker)))))

(deftest boot-tests-have-no-namespace-wide-execution-shape
  (let [namespace-object (find-ns 'seon.cluster.boot-test)
        fixtures (meta namespace-object)]
    (is (empty? (::test/once-fixtures fixtures)))
    (is (seq (::test/each-fixtures fixtures)))
    (is (not (#'runner/atomic-namespace-task? namespace-object))
        "each boot test receives a private base clone and can be queued alone")))

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
            (by-symbol "seon.test-runner-failure-fixture/passing-example")
            [:seon.test/pass-count
             :seon.test/fail-count
             :seon.test/error-count])))
    (is (= 1
           (count
            (:seon.test/failing-assertions
             (by-symbol
              "seon.test-runner-failure-fixture/failing-example")))))
    (is (str/includes?
         (:seon.test/failure-message
          (by-symbol
           "seon.test-runner-failure-fixture/failing-example"))
         "deliberate broken-test evidence"))))

(deftest assertionless-test-is-an-attributed-failure
  (let [result (captured-run)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))
        assertionless
        (by-symbol
         "seon.test-runner-failure-fixture/assertionless-example")]
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
          "seon.test-runner-failure-fixture/repeated-identical-error"))
        repeated-signature (apply str (repeat 64 "a"))
        distinct-signature (apply str (repeat 64 "b"))
        signature-at (str/index-of output repeated-signature)
        repeated-start (inc (str/last-index-of output "\nERROR in"
                                                signature-at))
        summary-start (str/index-of output "\nRan" repeated-start)
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

(deftest persistent-recording-failure-refuses-a-successful-gate
  (doseq [[summary expected-exit]
          [[#:seon.test.runner{:test-count 1
                               :pass-count 1
                               :fail-count 0
                               :error-count 0}
            1]
           [#:seon.test.runner{:test-count 1
                               :pass-count 0
                               :fail-count 1
                               :error-count 0}
            1]]]
    (let [exit* (atom nil)
          output
          (with-out-str
            (reset!
             exit*
             (#'runner/finish-run!
              {::runner/summary summary
               ::runner/task-results []
               ::runner/run-result
               {:seon.test.runner/results []}
               ::runner/skipped []
               ::runner/selection-mode "explicit"
               ::runner/git-sha git-sha
               ::runner/bulk nil
               ::runner/record-results!
               #(throw
                 (ex-info
                  "the evidence sink is deliberately unavailable"
                  {:seon.error/kind
                   ::runner/injected-persistent-recording-failure}))
               ::runner/recording-label "persistent results"})))]
      (is (= expected-exit @exit*)
          "a gate requires durable evidence as well as passing tests")
      (is (str/includes? output "Ran 1 tests containing 1 assertions."))
      (is (= 1 (occurrences output
                            "bin/test: persistent results NOT recorded:")))
      (is (str/includes?
           output
           ":seon.test.runner/injected-persistent-recording-failure"))
      (is (< (str/index-of output "Ran 1 tests")
             (str/index-of output
                           "bin/test: persistent results NOT recorded:"))
          "the complete tally is visible before persistence is attempted"))))

(deftest explicit-result-root-directs-bare-gate-evidence
  (is (= "/tmp/isolated-operator-root"
         (#'runner/configured-persistent-results-root
          "/checkout-root" "/tmp/isolated-operator-root")))
  (is (= "/checkout-root"
         (#'runner/configured-persistent-results-root
          "/checkout-root" nil))))

(deftest result-recording-is-total-under-concurrent-test-retraction
  ;; The class this kills: the presence decision ran as a caller
  ;; pre-read, so a test row retracted between building the record
  ;; transaction and the writer executing it stranded the retract's
  ;; lookup ref and rejected the WHOLE result transaction. record-tx
  ;; now runs as a transaction function: the writer re-decides, the
  ;; absent branch recreates the row, and recording always commits.
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
            "first recording installs the row")
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
        (let [recorded (runner/commit-results! connection completion)]
          (is (not (:seon.error/kind recorded))
              "recording after the retraction still commits")
          (is (some #(= test-symbol (:seon.test/sym %)) recorded)
              "the retracted row was recreated by the writer's decision")
          (is (= failure-ids
                 (set (db/q '[:find [?id ...] :in $ ?s
                              :where [?t :seon.test/sym ?s]
                                     [?t :seon.test/failures ?f]
                                     [?f :seon.test.failure/id ?id]]
                            @connection test-symbol)))
              "canonical failure identities survive recreation without conflicting tempids")))))))

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
         (= #{["seon.test-runner-failure-fixture/failing-example"
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
             "seon.test-runner-failure-fixture/failing-example")))
        (let [test-ref
              [:seon.test/sym
               "seon.test-runner-failure-fixture/failing-example"]
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

(deftest the-agent-fork-callable-returns-the-committed-projection
  (test-support/with-database
    (fn [connection]
      (let [ctx
            (env/carry-state
             (test-support/fork-cluster-ctx connection)
             (env/environment-state
              (test-support/environment "test-result-agent" connection)))
            evaluate
            (fn [source]
              (eval/evaluate
               {:seon.cluster.eval/source source
                :seon.cluster.eval/ns [:seon.ns/name 'seon.test-runner-test]
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps
                (config/result-caps (config/defaults))
                :seon.sci.eval/time-limit-ms 5000
                :seon.config/on-core-error :panic
                :seon.db/db (db/db connection)
                :seon.db/connection connection}))
            ;; An agent-authored deftest becomes a `:seon.test` row through the
            ;; canonical path — evaluate, analyze the evaluation's program row,
            ;; commit it — exactly as a turn admits a declaration. Without the
            ;; row the test has no call graph, and `seon.test/run` answers the
            ;; typed unknown rather than guessing that it is safe to run here.
            admit!
            (fn [source]
              (let [evaluation (evaluate source)
                    analysis (functions/analyze-forms
                              (db/db connection)
                              [{:seon.cluster.eval/source source
                                :seon.cluster.eval/ns [:seon.ns/name 'seon.test-runner-test]
                                :seon.program/row (:seon.program/row evaluation)}])
                    row (when-not (:seon.error/kind analysis)
                          (second (first analysis)))]
                (is (map? row) (pr-str analysis))
                (when row
                  (test-support/transacted!
                   connection [(dissoc row :seon.sci.eval/evaluated?)]))))
            _ (evaluate
               "(require '[clojure.test :refer [deftest is]])")
            _ (admit!
               "(clojure.test/deftest agent-fork-example (clojure.test/is (= 4 (+ 2 2))))")
            result
            (:seon.sci.admit/value
             (evaluate "(seon.test/run #'agent-fork-example)"))
            stored (db/pull (db/db connection)
                            @#'runner/result-selector
                            [:seon.test/sym (:seon.test/sym result)])]
        (is (= result stored))))))

(deftest the-effectful-sink-refuses-the-default-cluster
  (let [refusal
        (test-support/refusal-data
         #(runner/record!
           {:seon.test.runner/run-result (captured-run)
            :seon.boot/cluster-name "default"
            :seon.boot/root "tmp/test-result-default-refusal"}))]
    (is (= :seon.test.runner/default-cluster-refused
           (:seon.error/kind refusal)))))

(deftest liveness-dump-includes-coordinator-and-worker-virtual-threads
  (let [release (CountDownLatch. 1)
        paths (volatile! [])
        fixture-root
        (io/file project-root "tmp" "test-runner-worker-dump"
                 (str (random-uuid)))
        child-source (io/file fixture-root "WorkerDump.java")
        child-ready (io/file fixture-root "child-ready")
        child-program
        (str
         "import java.nio.file.*;\n"
         "import java.util.concurrent.CountDownLatch;\n"
         "public class WorkerDump {\n"
         "  public static void main(String[] args) throws Exception {\n"
         "    var release = new CountDownLatch(1);\n"
         "    Thread.ofVirtual().name(\"seon-worker-virtual-thread-proof\")"
         ".start(() -> { try { release.await(); } catch (Exception ignored) {} });\n"
         "    Files.writeString(Path.of(args[0]), \"ready\");\n"
         "    Thread.currentThread().join();\n"
         "  }\n"
         "}\n")
        watcher (.newWatchService (java.nio.file.FileSystems/getDefault))
        child* (atom nil)
        thread
        (-> (Thread/ofVirtual)
            (.name "seon-coordinator-virtual-thread-proof")
            (.start
             (reify Runnable
               (run [_]
                 (.await release)))))]
    (try
      (.mkdirs fixture-root)
      (.register (.toPath fixture-root)
                 watcher
                 (into-array
                  java.nio.file.WatchEvent$Kind
                  [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE]))
      (spit child-source child-program)
      (let [child (.start
                   (ProcessBuilder.
                    ^java.util.List
                    [(str (io/file (System/getProperty "java.home")
                                   "bin" "java"))
                     (.getCanonicalPath child-source)
                     (.getCanonicalPath child-ready)]))
            _ (reset! child* child)
            _ (is (= "child-ready" (await-created! watcher "child-ready")))
            dumps (#'runner/persist-virtual-thread-dumps!
                   [(java.lang.ProcessHandle/current) (.toHandle child)])
            _ (vreset! paths (mapv ::runner/dump-path dumps))
            by-pid (into {} (map (juxt #(-> % ::runner/dump-process .pid)
                                       identity)) dumps)
            coordinator-dump
            (slurp (::runner/dump-path
                    (by-pid (.pid (java.lang.ProcessHandle/current)))))
            worker-dump
            (slurp (::runner/dump-path (by-pid (.pid child))))
            diagnostic
            (#'runner/liveness-diagnostic
             (atom {::runner/description "test diagnostic"
                    ::runner/at-nanos (System/nanoTime)
                    ::runner/at (java.time.Instant/now)})
             300
             (java.time.Instant/now)
             [(.toHandle child)]
             dumps)
            diagnostic-text (::runner/text diagnostic)]
        (is (= 2 (count dumps)))
        (is (str/includes? coordinator-dump
                           "seon-coordinator-virtual-thread-proof"))
        (is (str/includes? worker-dump
                           "seon-worker-virtual-thread-proof"))
        (is (every? #(str/includes? % "\"virtual\": true")
                    [coordinator-dump worker-dump])
            "both retained diagnostics are virtual-thread-aware JVM dumps")
        (is (every? #(str/includes? diagnostic-text %)
                    @paths)
            "the liveness diagnostic names both retained JVM dumps"))
      (finally
        (.countDown release)
        (.join thread)
        (.close watcher)
        (when-let [^Process child @child*]
          (when (.isAlive child)
            (.destroyForcibly child)
            (.get (.onExit (.toHandle child)) 10 TimeUnit/SECONDS)))
        (doseq [dump-path @paths]
          (io/delete-file dump-path true))
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(def ^:private launcher-source-copy
  (str "cp \"$origin/src/seon/fs.clj\" src/seon/fs.clj\n"
       "mkdir -p src/seon/test\n"
       "cp -R \"$origin/src/seon/test/.\" src/seon/test/\n"))

(defn- publish-fixture-head! [checkout]
  (let [directory (io/file checkout "target/test-published-bases" fake-cache-digest)
        base (doto (io/file directory "base") .mkdirs)
        log (io/file checkout "tmp/head.txt")
        _ (io/make-parents log)
        child (.start (doto (ProcessBuilder. ^java.util.List
                                             ["git" "-C" (.getPath checkout) "rev-parse" "HEAD"])
                        (.redirectOutput log)))]
    (try
      (when-not (and (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS)
                     (zero? (.exitValue child)))
        (throw (ex-info "Fixture HEAD was not readable." {})))
      (spit (io/file base "manifest.edn")
            (pr-str (functions/build-manifest
                     {:seon.fn/root (.getCanonicalPath checkout)
                      :seon.fn/roots ["src" "test"]})))
      (spit (io/file directory "ready.edn")
            (pr-str {:seon.test.cache/digest fake-cache-digest}))
      (cache/record-head! (.getCanonicalPath checkout) fake-cache-digest
                          (str/trim (slurp log)))
      (finally (stop-process-tree! child)))))

(defn- launcher-checkout!
  "Give a launcher fixture its own cache authority as well as its own run roots."
  ([fixture-root] (launcher-checkout! fixture-root project-root))
  ([fixture-root origin]
  (let [checkout (io/file fixture-root "checkout")
        log (io/file fixture-root "checkout.log")
        script (str "set -euo pipefail\n"
                    "origin=$1\ncheckout=$2\n"
                    "mkdir -p \"$checkout/docs\" \"$checkout/bin\" \"$checkout/src/seon/test\" \"$checkout/test\" \"$checkout/.agents/skills\" \"$checkout/.claude\" \"$checkout/.clj-kondo\"\n"
                    "cd \"$checkout\"\n"
                    "cp -R \"$origin/bin/.\" bin/\n"
                    launcher-source-copy
                    "printf '{:paths [\"src\"]}\\n' > bb.edn\n"
                    "printf 'tmp/\\ntarget/\\n' > .gitignore\n"
                    "touch docs/fixture test/fixture_test.clj .agents/skills/fixture .clj-kondo/fixture\n"
                    "ln -s .agents/skills seon-skills\n"
                    "ln -s ../.agents/skills .claude/skills\n"
                    "ln -s \"$origin/reference-code\" reference-code\n"
                    "git init -q\ngit add -- docs bin src test bb.edn .gitignore .agents .claude .clj-kondo seon-skills reference-code\n"
                    "git -c user.name=\"$(git -C \"$origin\" config user.name)\" -c user.email=\"$(git -C \"$origin\" config user.email)\" commit -qm baseline\n")
        _ (.mkdirs fixture-root)
        child (.start (doto (ProcessBuilder. ^java.util.List
                                             ["/bin/bash" "-c" script "launcher-checkout"
                                              (.getPath origin) (.getPath checkout)])
                        (.redirectErrorStream true)
                        (.redirectOutput log)))]
    (try
      (when-not (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS)
        (throw (ex-info "Launcher fixture checkout did not finish."
                        {::runner/worker-root (.getPath checkout)})))
      (when-not (zero? (.exitValue child))
        (throw (ex-info "Launcher fixture checkout failed."
                        {::runner/task-output (slurp log)})))
      (publish-fixture-head! checkout)
      checkout
      (finally (stop-process-tree! child))))))

(defn- fixture-command! [checkout arguments & [orchestrator?]]
  (let [log (io/file checkout "tmp" (str "command-" (random-uuid) ".log"))
        _ (io/make-parents log)
        child (.start (cond-> (doto (ProcessBuilder. ^java.util.List arguments)
                        (.directory checkout)
                        (.redirectErrorStream true)
                        (.redirectOutput log))
                       orchestrator? as-orchestrator))]
    (try
      (when-not (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS)
        (throw (ex-info "Launcher fixture command exceeded its bound."
                        {::runner/task-output (slurp log)})))
      [(.exitValue child) (slurp log)]
      (finally (stop-process-tree! child)))))

(defn- overlay-admission-proof! [launcher-source]
  (let [root (doto (io/file project-root "tmp" (str "overlay-admission-" (random-uuid))) .mkdirs)]
    (try
      (let [checkout (launcher-checkout! root)
            api (io/file checkout "src/probe/api.clj")
            caller (io/file checkout "src/probe/caller.clj")
            unrelated (io/file checkout "src/probe/unrelated.clj")
            caller-source "(ns probe.caller (:require [probe.api :as api]))\n(defn call [x] (api/value x))\n"
            ready (io/file checkout "target/test-published-bases" fake-cache-digest "ready.edn")
            marker (io/file checkout "tmp/jvm-launched")
            command (fn [paths]
                      (fixture-command!
                       checkout
                       (into ["bash" "-c"
                              "PATH=\"$PWD/tmp/fake-bin:$PATH\" exec bin/test --fast --paths \"$@\" -- seon.fixture-test"
                              "overlay-proof"] paths)))]
        (io/make-parents api)
        (spit api "(ns probe.api)\n(defn value [x] x)\n(defn unchanged [x] x)\n")
        (spit caller caller-source)
        (spit unrelated "(ns probe.unrelated (:require [probe.api :as api]))\n(defn call [x] (api/unchanged x))\n")
        (spit (io/file checkout "bin/test") launcher-source)
        (is (zero? (first (fixture-command! checkout ["git" "add" "src" "bin/test"]))))
        (is (zero? (first (fixture-command! checkout ["git" "commit" "-qm" "overlay baseline"]))))
        (publish-fixture-head! checkout)
        (let [record (slurp ready)
              executable (io/file checkout "tmp/fake-bin/clojure")]
          (io/make-parents executable)
          (spit executable (str "#!/bin/sh\necho LAUNCHED > '" (.getCanonicalPath marker) "'\n"))
          (.setExecutable executable true)
          (io/delete-file ready)
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (= 64 status) output)
            (is (str/includes? output "orchestrator must run: bin/test --prepare-head-base") output)
            (is (not (.exists marker)) "absent baseline refuses before a JVM"))
          (spit ready (pr-str (assoc (edn/read-string record) :seon.test.cache/git-sha "stale")))
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (= 64 status) output)
            (is (not (.exists marker)) "a stale graph is never accepted"))
          (spit ready record)
          (let [[status output] (command ["src//probe/api.clj"])]
            (is (= 64 status) output)
            (is (not (.exists marker)) "path aliases cannot evade graph identity matching"))
          (spit api "(ns probe.api)\n(defn value [x y] [x y])\n(defn unchanged [x] x)\n")
          (spit caller "(ns probe.caller (:require [probe.api :as api]))\n(defn call [x] (api/value x x))\n")
          (spit unrelated "(ns probe.unrelated (:require [probe.api :as api]))\n(defn call [x] (api/unchanged (inc x)))\n")
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (= 64 status) output)
            (is (str/includes? output "add changed caller files: src/probe/caller.clj") output)
            (is (not (str/includes? output "src/probe/unrelated.clj")) output)
            (is (not (.exists marker)) "incomplete overlay refuses before a JVM"))
          (let [[status output]
                (fixture-command!
                 checkout
                 ["bash" "-c"
                  "PATH=\"$PWD/tmp/fake-bin:$PATH\" exec bin/test --paths src/probe/api.clj -- seon.fixture-test"]
                 true)]
            (is (= 64 status) output)
            (is (str/includes? output "add changed caller files: src/probe/caller.clj") output)
            (is (not (.exists marker)) "cold admission also refuses before a JVM"))
          (let [[status output] (command ["src/probe/api.clj" "src/probe/caller.clj"])]
            (is (zero? status) output)
            (is (.exists marker) "the complete overlay reaches the runner"))
          (io/delete-file marker true)
          (spit caller caller-source)
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (zero? status) output)
            (is (.exists marker) "a caller unchanged from HEAD need not be overlaid"))
          ;; Reuse a real indexed HEAD manifest through the gate's cache-hit
          ;; preparation. Its dependency entry point must see HEAD bytes,
          ;; and no coordinator or test process is admitted by this command.
          (spit (io/file checkout "tmp/expected-api")
                "(ns probe.api)\n(defn value [x] x)\n(defn unchanged [x] x)\n")
          (.mkdirs (io/file checkout "target/test-published-bases" fake-cache-digest "base/data/store"))
          (spit ready (pr-str (dissoc (edn/read-string record) :seon.test.cache/git-sha)))
          (spit executable
                (str "#!/bin/bash\nset -euo pipefail\n"
                     "cmp src/probe/api.clj \"$SEON_FAKE_CACHE_PATH/../../expected-api\"\n"
                     fake-dev-cache-prologue
                     "echo UNEXPECTED_RUNNER >&2\nexit 99\n"))
          (let [roots-before (set (map #(.getName %) (.listFiles (io/file checkout "tmp/test-runs"))))
                [status output]
                (fixture-command!
                 checkout
                 ["bash" "-c"
                  (str "mkdir -p tmp/cache/" fake-cache-digest "\n"
                       "export PATH=\"$PWD/tmp/fake-bin:$PATH\"\n"
                       "export SEON_FAKE_CACHE_PATH=\"$PWD/tmp/cache/" fake-cache-digest "\"\n"
                       "export SEON_FAKE_CACHE_DIGEST=" fake-cache-digest "\n"
                       "exec bin/test --prepare-head-base")]
                 true)]
            (is (zero? status) output)
            (is (str/includes? output "published overlay baseline for HEAD") output)
            (is (= (:seon.test.cache/git-sha (edn/read-string record))
                   (:seon.test.cache/git-sha (edn/read-string (slurp ready)))))
            (is (= roots-before
                   (set (map #(.getName %) (.listFiles (io/file checkout "tmp/test-runs")))))
                "successful preparation removes its disposable run root")
            (is (not (str/includes? output "UNEXPECTED_RUNNER")) output))))
      (finally (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/platform "Selected overlays refuse missing changed callers before launching a JVM."}
  selected-overlays-require-a-current-graph-and-every-changed-caller
  (overlay-admission-proof! (slurp (io/file project-root "bin/test"))))

(deftest ^{:seon.test/platform "Launcher fixtures carry newly required test helpers."}
  launcher-checkout-carries-new-cache-dependencies
  (let [root (doto (io/file project-root "tmp" (str "launcher-dependencies-" (random-uuid))) .mkdirs)]
    (try
      (let [origin (launcher-checkout! (io/file root "origin"))
            cache-file (io/file origin "src/seon/test/cache.clj")
            dependency (io/file origin "src/seon/test/fixture_dependency.clj")
            log (io/file root "require.log")]
        (spit dependency "(ns seon.test.fixture-dependency)\n(def loaded :new-dependency-loaded)\n")
        (spit cache-file
              (str (slurp cache-file)
                   "\n(require '[seon.test.fixture-dependency])\n"))
        (let [checkout (launcher-checkout! (io/file root "consumer") origin)
              child (.start
                     (doto (ProcessBuilder.
                            ^java.util.List
                            ["bb" "-e"
                             "(require 'seon.test.cache) (prn seon.test.fixture-dependency/loaded)"])
                       (.directory checkout)
                       (.redirectErrorStream true)
                       (.redirectOutput log)))]
          (try
            (is (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS))
            (is (and (not (.isAlive child)) (zero? (.exitValue child))) (slurp log))
            (is (str/includes? (slurp log) ":new-dependency-loaded"))
            (finally (stop-process-tree! child)))))
      (finally (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/platform "Orphaned gates remain visible and are never reclaimed by a waiting lane."}
  orphaned-gates-are-announced-by-wait-and-preamble
  (let [root (doto (io/file project-root "tmp" (str "orphaned-gates-" (random-uuid))) .mkdirs)
        checkout (launcher-checkout! root)
        pid-file (io/file root "orphan.pid")
        log (io/file root "probe.log")
        orphan (atom nil)]
    (try
      (let [launcher (.start
                      (doto (ProcessBuilder. ^java.util.List
                                             ["bash" "-c" "sleep 120 >/dev/null 2>&1 & echo $!"])
                        (.redirectOutput pid-file)))]
        (try
          (is (.waitFor launcher test-support/event-backstop-seconds TimeUnit/SECONDS))
          (let [pid (Long/parseLong (str/trim (slurp pid-file)))
                handle (.orElseThrow (ProcessHandle/of pid))]
            (reset! orphan handle)
            (doseq [[slot holder] [[1 pid] [2 (.pid launcher)]
                                   [3 (.pid (ProcessHandle/current))]]]
              (let [directory (doto (io/file checkout "tmp/test-slots" (str "slot-" slot)) .mkdirs)
                    run-root (doto (io/file checkout "tmp/test-runs" (str "run.fixture-" slot)) .mkdirs)]
                (spit (io/file directory "pid") (str holder "\n"))
                (spit (io/file directory "run-root") (str (.getCanonicalPath run-root) "\n"))
                (spit (io/file run-root "test-run.txt")
                      (str "phase=snapshot elapsed-seconds=1\n"
                           "runner-pid=" pid " runner-started-at=fixture\n"
                           "phase=published-base elapsed-seconds=7\n"))
                (.setLastModified run-root (- (System/currentTimeMillis) (* 2 24 60 60 1000)))))
            (let [script (str "set -euo pipefail\nsource bin/_test-slot\n"
                              "test_slot_wait_seconds=0\n"
                              "if acquire_test_slot; then exit 91; else test \"$?\" = 75; fi\n"
                              "echo PREAMBLE\nmkdir -p fake-bin\n"
                              "printf '#!/bin/sh\\nexit 66\\n' > fake-bin/git\nchmod +x fake-bin/git\n"
                              "if PATH=\"$PWD/fake-bin:$PATH\" bin/test --fast --paths docs/fixture -- seon.fixture-test; then exit 92; else test \"$?\" = 66; fi\n"
                              "test -f tmp/test-slots/slot-1/pid\ntest -f tmp/test-slots/slot-2/pid\n"
                              "rm fake-bin/git\n"
                              "for runner_state in dead absent; do\n"
                              "  printf '%s\\n' " (.pid launcher) " > tmp/test-slots/slot-3/pid\n"
                              "  ledger=tmp/test-runs/run.fixture-3/test-run.txt\n"
                              "  if [ \"$runner_state\" = dead ]; then printf 'runner-pid=%s\\n' " (.pid launcher) " > \"$ledger\"; else : > \"$ledger\"; fi\n"
                              "  if acquire_test_slot; then exit 93; else test \"$?\" = 75; fi\n"
                              "  test ! -e tmp/test-slots/slot-3\n"
                              "  test -f tmp/test-slots/slot-1/pid\ntest -f tmp/test-slots/slot-2/pid\n"
                              "  mkdir tmp/test-slots/slot-3\n"
                              "  printf '%s\\n' \"$PWD/tmp/test-runs/run.fixture-3\" > tmp/test-slots/slot-3/run-root\n"
                              "done\nrm -r tmp/test-slots/slot-3\necho EXHAUST_RECLAIMED\n"
                              "printf '#!/bin/sh\\necho FIXTURE_FAST_EXIT\\nexit 0\\n' > fake-bin/clojure\nchmod +x fake-bin/clojure\n"
                              "PATH=\"$PWD/fake-bin:$PATH\" bin/test --fast --paths docs/fixture -- seon.fixture-test\n"
                              "test -d tmp/test-runs/run.fixture-1\ntest -d tmp/test-runs/run.fixture-2\n")
                  child (.start (doto (ProcessBuilder. ^java.util.List ["bash" "-c" script])
                                  (.directory checkout)
                                  (.redirectErrorStream true)
                                  (.redirectOutput log)))]
              (try
                (is (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS))
                (is (and (not (.isAlive child)) (zero? (.exitValue child))) (slurp log))
                (let [output (slurp log)
                      boundary (.indexOf output "PREAMBLE")]
                  (is (pos? boundary) output)
                  (when (pos? boundary)
                    (doseq [part [(subs output 0 boundary) (subs output boundary)]
                            slot [1 2]]
                      (is (str/includes? part (str "orphaned gate pid " pid " (parent dead) holds slot-" slot)) part)
                      (is (str/includes? part (.getCanonicalPath (io/file checkout "tmp/test-runs" (str "run.fixture-" slot)))) part)
                      (is (str/includes? part "last PHASE: phase=published-base elapsed-seconds=7") part)))
                  (is (str/includes? output "FIXTURE_FAST_EXIT") output)
                  (is (str/includes? output "EXHAUST_RECLAIMED") output)
                  (is (not (str/includes? output "holds slot-3")) output)
                  (is (.isAlive handle) "announcing an orphan never kills it"))
                (finally (stop-process-tree! child)))))
          (finally (stop-process-tree! launcher))))
      (finally
        (when-let [handle @orphan]
          (.destroyForcibly ^ProcessHandle handle)
          (.get (.onExit ^ProcessHandle handle) test-support/event-backstop-seconds TimeUnit/SECONDS))
        (test-support/delete-recursively! root)))))

(deftest interrupted-launcher-awaits-its-runner-before-retaining-the-root
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-interrupt"
                 (str (random-uuid)))
        checkout (launcher-checkout! fixture-root)
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        reaped-file (io/file fixture-root "child-reaped.txt")
        run-parent (io/file fixture-root "runs")
        cache-path (io/file fixture-root "cache" fake-cache-digest)
        fake-runner
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         fake-dev-cache-prologue
         "child=\n"
         "stop() {\n"
         "  trap - TERM\n"
         "  kill -TERM \"$child\" 2>/dev/null || true\n"
         "  wait \"$child\" 2>/dev/null || true\n"
         "  printf '%s\\n' \"$child\" >\"$SEON_FAKE_REAPED\"\n"
         "  exit 143\n"
         "}\n"
         "trap stop TERM\n"
         "/usr/bin/python3 -c 'import signal; signal.pause()' &\n"
         "child=$!\n"
         "echo \"FAKE_RUNNER_READY $child\"\n"
         "wait \"$child\"\n")
        process (atom nil)
        output-reader (atom nil)]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      (.mkdirs cache-path)
      (install-single-worker-getconf! fake-bin)
      (spit fake-clojure fake-runner)
      (is (.setExecutable fake-clojure true false))
      (let [builder
            (doto
             (ProcessBuilder.
              ^java.util.List
              [(str (io/file checkout "bin" "test"))
               "--paths" "bin/test" "--" "seon.test-runner-test"])
              (as-orchestrator)
              (.directory checkout)
              (.redirectErrorStream true))
            _ (.put (.environment builder)
                    "SEON_TEST_RUN_PARENT" (.getCanonicalPath run-parent))
            _ (.put (.environment builder)
                    "SEON_FAKE_REAPED" (.getCanonicalPath reaped-file))
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_DIGEST" fake-cache-digest)
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_PATH"
                    (.getCanonicalPath cache-path))
            _ (.put (.environment builder)
                    "PATH"
                    (str (.getCanonicalPath fake-bin)
                         java.io.File/pathSeparator
                         (System/getenv "PATH")))
            launched (.start builder)
            _ (reset! process launched)
            ready-prefix "FAKE_RUNNER_READY "
            ready (promise)
            output (promise)
            reader-thread
            (Thread.
             ^Runnable
             (fn []
               (deliver
                output
                (try
                  (with-open [reader (io/reader (.getInputStream launched))]
                    (loop [lines []]
                      (if-let [line
                               (.readLine ^java.io.BufferedReader reader)]
                        (do
                          (when (str/starts-with? line ready-prefix)
                            (deliver ready line))
                          (recur (conj lines line)))
                        lines)))
                  (catch Throwable failure
                    failure)))))
            _ (.setName reader-thread "test-runner-readiness-reader")
            _ (reset! output-reader reader-thread)
            _ (.start reader-thread)
            readiness-backstop-millis
            (* 3 1000 test-support/event-backstop-seconds)
            ready-line
            (deref ready readiness-backstop-millis ::readiness-backstop)
            _ (when (= ::readiness-backstop ready-line)
                (throw
                 (ex-info "The fake runner did not publish readiness."
                          {:seon.test-runner/output
                           (deref output 1000 :still-running)})))
            child-pid
            (Long/parseLong
             (subs ready-line (count ready-prefix)))]
        (.destroy launched)
        (.get (.onExit (.toHandle launched)) 20 TimeUnit/SECONDS)
        (let [complete-output (deref output 10000 :reader-did-not-finish)
              children (some-> (java.lang.ProcessHandle/of child-pid)
                               (.orElse nil))
              run-roots (vec (.listFiles run-parent))
              run-root (first run-roots)
              record (when run-root
                       (slurp (io/file run-root "test-run.txt")))]
          (is (pos? (.exitValue launched))
              (pr-str complete-output))
          (is (= 1 (count run-roots))
              "the interrupted invocation retained exactly its evidence root")
          (is (not (and children (.isAlive children)))
              "the runner owner reaped its exact child before launcher exit")
          (is (= (str child-pid) (str/trim (slurp reaped-file))))
          (is (str/includes? record "runner-pid="))
          (is (str/includes? record "runner-reaped-at="))
          (is (str/includes? record "retained-reason=signal-TERM"))
          (is (< (str/index-of record "runner-reaped-at=")
                 (str/index-of record "retained-reason="))
              "the runner exit publication precedes root retention")))
      (finally
        (when-let [^Process launched @process]
          (when (.isAlive launched)
            (.destroyForcibly launched)
            (.get (.onExit (.toHandle launched)) 10 TimeUnit/SECONDS)))
        (when-let [^Thread reader-thread @output-reader]
          (.join reader-thread
                 (* 1000 test-support/event-backstop-seconds)))
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest worker-exit-backstop-names-and-fails-a-stuck-child
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-stuck-child"
                 (str (random-uuid)))
        parent-script (io/file fixture-root "worker-parent")
        child-script (io/file fixture-root "stuck-child.py")
        child-program
        (str
         "import signal\n"
         "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
         "print('READY', flush=True)\n"
         "signal.pause()\n")
        parent-program
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         "/usr/bin/python3 \"$1\" &\n"
         "child=$!\n"
         "echo \"CHILD_PID $child\"\n"
         "wait \"$child\"\n")
        process* (atom nil)]
    (try
      (.mkdirs fixture-root)
      (spit child-script child-program)
      (spit parent-script parent-program)
      (is (.setExecutable parent-script true false))
      (let [process (.start
                     (doto
                      (ProcessBuilder.
                       ^java.util.List
                       [(str parent-script) (.getCanonicalPath child-script)])
                      (.redirectErrorStream true)))
            _ (reset! process* process)
            reader (io/reader (.getInputStream process))
            child-pid-line (.readLine ^java.io.BufferedReader reader)
            child-pid (Long/parseLong (subs child-pid-line
                                            (count "CHILD_PID ")))
            _ (is (= "READY" (.readLine ^java.io.BufferedReader reader)))
            refusal
            (with-redefs-fn
              {#'runner/process-tree-exit-backstop-seconds 1}
              #(test-support/refusal-data
                (fn []
                  (#'runner/stop-owned-process-tree!
                   (#'runner/process-tree-ownership process)))))]
        (is (= :seon.test.runner/process-tree-exit-backstop
               (:seon.error/kind refusal)))
        (is (true? (:seon.test.runner/forced-completion? refusal)))
        (is (contains? (into #{} (map ::runner/process-id)
                             (::runner/processes refusal))
                       child-pid)
            "the loud refusal names the exact child that ignored termination")
        (is (not (.isAlive process))))
      (finally
        (when-let [^Process process @process*]
          (when (.isAlive process)
            (.destroyForcibly process)
            (.get (.onExit (.toHandle process)) 10 TimeUnit/SECONDS)))
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest worker-root-cleanup-awaits-recorded-child-completion
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-child-completion"
                 (str (random-uuid)))
        worker-root (io/file fixture-root "worker-root")
        release-fifo (io/file fixture-root "release")
        child-ready (io/file fixture-root "child-ready")
        child-stopping (io/file fixture-root "child-stopping")
        child-complete (io/file fixture-root "child-complete.txt")
        child-source (io/file fixture-root "late-writer.py")
        parent-script (io/file fixture-root "worker-parent")
        child-program
        (str
         "import os, signal, sys\n"
         "worker_root, release_fifo, child_ready, child_stopping, child_complete = sys.argv[1:]\n"
         "def publish(path, text):\n"
         "    with open(path, 'w') as event:\n"
         "        event.write(text)\n"
         "def stop(_signal, _frame):\n"
         "    publish(child_stopping, 'child still owns root')\n"
         "    with open(release_fifo, 'r') as release:\n"
         "        release.read(1)\n"
         "    with open(os.path.join(worker_root, 'late.txt'), 'w') as late:\n"
         "        late.write('late write completed')\n"
         "    publish(child_complete, 'child completion published')\n"
         "    sys.exit(0)\n"
         "signal.signal(signal.SIGTERM, stop)\n"
         "publish(child_ready, 'child ready')\n"
         "signal.pause()\n")
        parent-program
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         "/usr/bin/mkfifo \"$2\"\n"
         "/usr/bin/python3 \"$1\" \"$3\" \"$2\" \"$4\" \"$5\" \"$6\" &\n"
         "wait \"$!\"\n")
        process (atom nil)
        watcher (.newWatchService (java.nio.file.FileSystems/getDefault))]
    (try
      (.mkdirs worker-root)
      (.register (.toPath fixture-root)
                 watcher
                 (into-array
                  java.nio.file.WatchEvent$Kind
                  [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE]))
      (spit child-source child-program)
      (spit parent-script parent-program)
      (is (.setExecutable parent-script true false))
      (let [launched
            (.start
             (doto
              (ProcessBuilder.
               ^java.util.List
               [(str parent-script)
                (.getCanonicalPath child-source)
                (.getCanonicalPath release-fifo)
                (.getCanonicalPath worker-root)
                (.getCanonicalPath child-ready)
                (.getCanonicalPath child-stopping)
                (.getCanonicalPath child-complete)])
              (.directory fixture-root)
              (.redirectErrorStream true)))
            _ (reset! process launched)]
        (is (= "child-ready" (await-created! watcher "child-ready")))
        (let [cleanup-count (atom 0)
              cleanup
              (future
                (#'runner/stop-owned-process-tree!
                 (#'runner/process-tree-ownership launched))
                (.waitFor launched)
                (swap! cleanup-count inc)
                (test-support/delete-recursively! worker-root))]
          (is (= "child-stopping"
                 (await-created! watcher "child-stopping")))
          (is (zero? @cleanup-count)
              "cleanup cannot run while a recorded child still owns the root")
          (is (.isDirectory worker-root))
          (with-open [release (io/writer release-fifo)]
            (.write release "x")
            (.flush release))
          (is (= "child-complete.txt"
                 (await-created! watcher "child-complete.txt")))
          (is (not= ::cleanup-backstop
                    (deref cleanup 10000 ::cleanup-backstop)))
          (is (= 1 @cleanup-count)
              "root cleanup fires exactly once after child completion")
          (is (= "child completion published" (slurp child-complete)))
          (is (not (.exists worker-root)))))
      (finally
        (.close watcher)
        (when-let [^Process launched @process]
          (when (.isAlive launched)
            (.destroyForcibly launched)
            (.get (.onExit (.toHandle launched)) 10 TimeUnit/SECONDS)))
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest stale-dependency-cache-is-refused-or-selected-and-recorded
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-worker-cache"
                 (str (random-uuid)))
        checkout (launcher-checkout! fixture-root)
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        run-parent (io/file fixture-root "runs")
        cache-path (io/file fixture-root "cache" fake-cache-digest)
        cache-call (io/file fixture-root "cache-call.txt")
        transcript (io/file fixture-root "test-run.txt")
        fake-runner
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         fake-dev-cache-prologue
         "prepare=false\n"
         "for argument in \"$@\"; do\n"
         "  if [ \"$argument\" = \"--prepare-base\" ]; then prepare=true; fi\n"
         "done\n"
         "if [ \"$prepare\" = true ]; then mkdir -p \"${!#}/data/store\"; echo '{}' > \"${!#}/manifest.edn\"; exit 0; fi\n"
         "test \"$1\" = -Scp\n"
         "test \"$2\" = fixture-classpath\n"
         "test -f workers/pool-1/.gitignore\n"
         "test ! -L workers/pool-1/.gitignore\n"
         "test -d workers/pool-1/docs\n"
         "test ! -L workers/pool-1/docs\n"
         "test -d workers/pool-1/.clj-kondo\n"
         "test ! -L workers/pool-1/.clj-kondo\n"
         "cp test-run.txt \"$SEON_FAKE_TRANSCRIPT\"\n")]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      (.mkdirs cache-path)
      (install-single-worker-getconf! fake-bin)
      (spit fake-clojure fake-runner)
      (is (.setExecutable fake-clojure true false))
      (let [mismatched-digest (apply str (repeat 64 "b"))
            builder
            (doto
             (ProcessBuilder.
              ^java.util.List
              [(str (io/file checkout "bin" "test"))
               "--paths" "bin/test" "--" "seon.test-runner-test"])
              (as-orchestrator)
              (.directory checkout)
              (.redirectErrorStream true))
            _ (.put (.environment builder)
                    "SEON_TEST_RUN_PARENT" (.getCanonicalPath run-parent))
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_DIGEST" mismatched-digest)
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_PATH" (.getCanonicalPath cache-path))
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_CALL" (.getCanonicalPath cache-call))
            _ (.put (.environment builder)
                    "PATH"
                    (str (.getCanonicalPath fake-bin)
                         java.io.File/pathSeparator
                         (System/getenv "PATH")))
            launched (.start builder)
            output (slurp (.getInputStream launched))]
        (is (pos? (.waitFor launched)) output)
        (is (str/includes? output "dependency cache digest mismatch") output)
        (is (str/includes? output mismatched-digest) output)
        (is (str/includes? (slurp cache-call)
                           "-T:dev-cache ensure-cache")))
      (let [builder
            (doto
             (ProcessBuilder.
              ^java.util.List
              [(str (io/file checkout "bin" "test"))
               "--paths" "bin/test" "--" "seon.test-runner-test"])
              (as-orchestrator)
              (.directory checkout)
              (.redirectErrorStream true))
            _ (.put (.environment builder)
                    "SEON_TEST_RUN_PARENT" (.getCanonicalPath run-parent))
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_DIGEST" fake-cache-digest)
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_PATH" (.getCanonicalPath cache-path))
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_CALL" (.getCanonicalPath cache-call))
            _ (.put (.environment builder)
                    "SEON_FAKE_TRANSCRIPT" (.getCanonicalPath transcript))
            _ (.put (.environment builder)
                    "PATH"
                    (str (.getCanonicalPath fake-bin)
                         java.io.File/pathSeparator
                         (System/getenv "PATH")))
            launched (.start builder)
            output (slurp (.getInputStream launched))]
        (is (zero? (.waitFor launched)) output)
        (is (str/includes? (slurp transcript)
                           (str "dev-cache-digest=" fake-cache-digest)))
        (is (= 1 (count (.listFiles run-parent)))
            "only the earlier cache-refusal snapshot remains"))
      (finally
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest a-preparation-phase-that-outruns-its-declared-bound-fails-loudly
  (testing "The preparation phases had no bound: on 2026-09-17 every gate
            refused inside `dependency-cache-and-classpath` for about an hour
            while four invocations sat at `phase=snapshot`, and the wedge read
            as an ordinary slot queue. A phase that outruns its declared bound
            now fails the gate with one line naming the phase, the elapsed
            seconds, the bound, and where that phase's own output is."
    (let [fixture-root
          (io/file project-root "tmp" "test-runner-phase-bound"
                   (str (random-uuid)))
          checkout (launcher-checkout! fixture-root)
          fake-bin (io/file fixture-root "bin")
          fake-clojure (io/file fake-bin "clojure")
          run-parent (io/file fixture-root "runs")
          ;; The dev-cache tool JVM never returns. Nothing else in this gate
          ;; is reached, so the phase name in the refusal is the whole report.
          wedged-runner
          (str "#!/usr/bin/env bash\n"
               "set -euo pipefail\n"
               "if [ \"${1-}\" = \"-T:dev-cache\" ]; then\n"
               "  sleep 600\n"
               "fi\n"
               "exit 0\n")
          process (atom nil)]
      (try
        (.mkdirs fake-bin)
        (.mkdirs run-parent)
        (install-single-worker-getconf! fake-bin)
        (spit fake-clojure wedged-runner)
        (is (.setExecutable fake-clojure true false))
        (let [builder
              (doto
               (ProcessBuilder.
                ^java.util.List
                [(str (io/file checkout "bin" "test"))
                 "--paths" "bin/test" "--" "seon.test-runner-test"])
                (as-orchestrator)
                (.directory checkout)
                (.redirectErrorStream true))
              _ (.put (.environment builder)
                      "SEON_TEST_RUN_PARENT" (.getCanonicalPath run-parent))
              _ (.put (.environment builder)
                      "SEON_TEST_DEPENDENCY_CACHE_SECONDS" "1")
              _ (.put (.environment builder)
                      "PATH"
                      (str (.getCanonicalPath fake-bin)
                           java.io.File/pathSeparator
                           (System/getenv "PATH")))
              launched (.start builder)
              _ (reset! process launched)
              output (slurp (.getInputStream launched))]
          (is (.waitFor launched test-support/event-backstop-seconds
                        TimeUnit/SECONDS)
              "the wedged phase is bounded, not awaited forever")
          (is (pos? (.exitValue launched)) output)
          (is (str/includes?
               output
               "PHASE dependency-cache-and-classpath EXCEEDED ITS BOUND")
              output)
          (is (str/includes? output "bound-seconds=1") output)
          (is (re-find #"elapsed-seconds=\d+" output) output)
          (is (str/includes?
               output "dependency-cache-and-classpath.log")
              "the one line names where that phase's own output is")
          (let [retained (vec (.listFiles run-parent))
                transcript (some (fn [^java.io.File root]
                                   (let [ledger (io/file root "test-run.txt")]
                                     (when (.isFile ledger) (slurp ledger))))
                                 retained)]
            (is (str/includes?
                 (str transcript)
                 "phase=dependency-cache-and-classpath exceeded-bound-seconds=1")
                (str transcript))))
        (finally
          (when-let [^Process launched @process]
            (when (.isAlive launched)
              (stop-process-tree! launched)))
          (when (.exists fixture-root)
            (test-support/delete-recursively! fixture-root)))))))

(deftest a-fresh-run-root-is-claimed-before-population-and-sweep
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-root-claim"
                 (str (random-uuid)))
        checkout (launcher-checkout! fixture-root)
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        fake-cp (io/file fake-bin "cp")
        run-parent (io/file fixture-root "runs")
        cache-path (io/file fixture-root "cache" fake-cache-digest)
        claim-observation (io/file fixture-root "claim-observation.txt")
        process (atom nil)
        unclaimed-roots
        (mapv #(io/file run-parent (str "run.unclaimed-" %)) (range 4))
        fake-runner
        (str "#!/usr/bin/env bash\n"
             "set -euo pipefail\n"
             fake-dev-cache-prologue
             "for argument in \"$@\"; do\n"
             "  if [ \"$argument\" = --prepare-base ]; then mkdir -p \"${!#}/data/store\"; echo '{}' > \"${!#}/manifest.edn\"; fi\n"
             "done\n"
             "exit 0\n")
        observing-cp
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         "for root in \"$SEON_TEST_RUN_PARENT\"/run.*; do\n"
         "  if [ -f \"$root/test-run.txt\" ]; then\n"
         "    /bin/cp \"$root/test-run.txt\" \"$SEON_FAKE_CLAIM_OBSERVATION\"\n"
         "    break\n"
         "  fi\n"
         "done\n"
         "exec /bin/cp \"$@\"\n")]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      (.mkdirs cache-path)
      (doseq [[ordinal root] (map-indexed vector unclaimed-roots)]
        (.mkdirs root)
        (is (.setLastModified root
                              (- (System/currentTimeMillis)
                                 (* 1000 (inc ordinal))))))
      (install-single-worker-getconf! fake-bin)
      (spit fake-clojure fake-runner)
      (spit fake-cp observing-cp)
      (is (.setExecutable fake-clojure true false))
      (is (.setExecutable fake-cp true false))
      (let [builder
            (doto
             (ProcessBuilder.
              ^java.util.List
              [(str (io/file checkout "bin" "test"))
               "--paths" "bin/test" "--" "seon.fs-test"])
              (as-orchestrator)
              (.directory checkout)
              (.redirectErrorStream true))
            environment (.environment builder)
            _ (.put environment "SEON_TEST_RUN_PARENT"
                    (.getCanonicalPath run-parent))
            _ (.put environment "SEON_FAKE_CACHE_DIGEST" fake-cache-digest)
            _ (.put environment "SEON_FAKE_CACHE_PATH"
                    (.getCanonicalPath cache-path))
            _ (.put environment "SEON_FAKE_CLAIM_OBSERVATION"
                    (.getCanonicalPath claim-observation))
            _ (.put environment "PATH"
                    (str (.getCanonicalPath fake-bin)
                         java.io.File/pathSeparator
                         (System/getenv "PATH")))
            launched (.start builder)
            _ (reset! process launched)
            output-future (future (slurp (.getInputStream launched)))
            completed?
            (.waitFor launched
                      (* 3 test-support/event-backstop-seconds)
                      TimeUnit/SECONDS)
            _ (when-not completed? (stop-process-tree! launched))
            output (deref output-future
                          (* 1000 test-support/event-backstop-seconds)
                          ::output-backstop)]
        (is completed? (pr-str output))
        (is (string? output) (pr-str output))
        (is (zero? (.exitValue launched)) output)
        (is (= (set (map #(.getCanonicalPath ^java.io.File %)
                         unclaimed-roots))
               (set (map #(.getCanonicalPath ^java.io.File %)
                         (.listFiles run-parent))))
            "fresh roots without claims remain outside sweep policy")
        (let [claim (slurp claim-observation)]
          (is (str/starts-with? claim "pid=") claim)
          (is (str/includes? claim "process-start=") claim)
          (is (str/includes? claim "suite-start=") claim)))
      (finally
        (when-let [launched @process]
          (stop-process-tree! launched))
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest ^{:seon.test/fixture-observation "Independent launcher processes require separate persistent result stores and locks to record both gate tallies."} concurrent-bin-test-invocations-both-reach-their-tallies
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-concurrent-gates"
                 (str (random-uuid)))
        fake-bin (io/file fixture-root "bin")
        run-parent (io/file fixture-root "runs")
        old-at (- (System/currentTimeMillis) (* 2 24 60 60 1000))
        processes (atom [])
        result-roots (mapv #(io/file fixture-root (str "result-" %)) (range 2))]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      (install-single-worker-getconf! fake-bin)
      (dotimes [root-ordinal 4]
        (let [root (io/file run-parent (str "run.stale-" root-ordinal))]
          (.mkdirs root)
          (spit (io/file root "test-run.txt") "pid=999999999\n")
          (dotimes [directory-ordinal 100]
            (let [directory (io/file root (str directory-ordinal))]
              (.mkdirs directory)
              (spit (io/file directory "evidence") "retained")))
          (is (.setLastModified root old-at))))
      (doseq [result-root result-roots]
        (test-support/populate-published-operator-root! (str result-root)))
      (let [start!
            (fn [result-root]
              (let [builder
                    (doto
                     (ProcessBuilder.
                      ^java.util.List
                      [(str (io/file project-root "bin" "test"))
                       "--result-cluster" "evidence"
                       "--result-root" (str result-root) "seon.fs-test"])
                      (as-orchestrator)
                      (.directory project-root)
                      (.redirectErrorStream true))
                    environment (.environment builder)]
                (.put environment "SEON_TEST_WORKERS" "1")
                (.put environment "SEON_TEST_RUN_PARENT"
                      (.getCanonicalPath run-parent))
                (.put environment "PATH"
                      (str (.getCanonicalPath fake-bin)
                           java.io.File/pathSeparator
                           (System/getenv "PATH")))
                (.start builder)))
            launched (mapv start! result-roots)
            _ (reset! processes launched)
            outputs (mapv #(future (slurp (.getInputStream ^Process %)))
                          launched)
            completions
            (mapv #(future
                     (.waitFor ^Process %
                               (* 12 test-support/event-backstop-seconds)
                               TimeUnit/SECONDS))
                  launched)
            completed
            (mapv #(deref %
                          (* 13 1000 test-support/event-backstop-seconds)
                          false)
                  completions)]
        (doseq [[process complete?] (map vector launched completed)]
          (when-not complete?
            (stop-process-tree! process)))
        (let [captured
              (mapv #(deref %
                            (* 1000 test-support/event-backstop-seconds)
                            ::output-backstop)
                    outputs)]
          (is (every? true? completed) (pr-str captured))
          (doseq [[process output] (map vector launched captured)]
            (is (string? output) (pr-str output))
            (is (zero? (.exitValue ^Process process)) output)
            (is (and (str/includes? output "\nRan ")
                     (str/includes? output " tests containing "))
                output)
            (is (str/includes? output "0 failures, 0 errors.") output))))
      (finally
        (run! stop-process-tree! @processes)
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

;;; ---------------------------------------------------------------------------
;;; The armed world is DERIVED, and its derivation refuses absence
;;; ---------------------------------------------------------------------------

(defn- program-root-fixture!
  "One throwaway first-party source root holding exactly `files`."
  [files]
  (let [root (doto (io/file project-root "tmp" "test-runner-program"
                            (str (random-uuid)))
               .mkdirs)]
    (doseq [[file-name content] files]
      (spit (io/file root file-name) content))
    root))

(deftest the-armed-program-derivation-refuses-absence-instead-of-answering-empty
  ;; CLASS: a check that reads ABSENCE OF SIGNAL as health. The worker refuses
  ;; when it armed nothing, but the INPUT to that refusal answered `[]` in
  ;; silence whenever its relative root did not resolve — and the worker's own
  ;; test vars kept the instrumented count positive, so the gate stayed green
  ;; about a question it never asked. Every way the derivation can come up
  ;; empty is now a typed refusal naming what was missing.
  (testing "an unresolvable program source root is a typed refusal"
    (with-redefs-fn {#'runner/program-source-root
                     "no-such-first-party-source-root"}
      (fn []
        (let [refusal (test-support/refusal-data
                       #(#'runner/declared-program-namespaces))]
          (is (= ::runner/program-source-root-unresolved
                 (:seon.error/kind refusal))
              "and it names the root and the working directory, rather than
               answering the empty set the caller cannot distinguish from a
               program with no namespaces")
          (is (contains? refusal ::runner/working-directory))))))
  (testing "a source file declaring no namespace is a typed refusal"
    (let [root (program-root-fixture!
                {"declares.clj" "(ns seon.probe.declares)\n"
                 "silent.clj" "(+ 1 2)\n"})]
      (try
        (with-redefs-fn {#'runner/program-source-root (.getCanonicalPath root)}
          (fn []
            (let [refusal (test-support/refusal-data
                           #(#'runner/declared-program-namespaces))]
              (is (= ::runner/program-source-declares-no-namespace
                     (:seon.error/kind refusal)))
              (is (str/ends-with? (::runner/source-file refusal)
                                  "silent.clj")
                  "naming the exact file that would have dropped out of the
                   armed set in silence"))))
        (finally
          (test-support/delete-recursively! (.getCanonicalPath root))))))
  (testing "a root declaring no namespaces at all is a typed refusal"
    (let [root (program-root-fixture! {"README.md" "not source\n"})]
      (try
        (with-redefs-fn {#'runner/program-source-root (.getCanonicalPath root)}
          (fn []
            (is (= ::runner/program-declares-no-namespaces
                   (:seon.error/kind
                    (test-support/refusal-data
                     #(#'runner/declared-program-namespaces)))))))
        (finally
          (test-support/delete-recursively! (.getCanonicalPath root))))))
  (testing "the real root derives the program, so the refusals are not vacuous"
    (let [program (#'runner/declared-program-namespaces)]
      (is (pos? (count program)))
      (is (every? symbol? program))
      (is (contains? (set program) 'seon.artifact)))))

(deftest arming-refuses-when-a-program-contract-carries-no-wrapper
  ;; CLASS: the same one. A floor of ZERO instrumented vars was satisfied by
  ;; the worker's own test namespaces, so a worker that armed none of the
  ;; PROGRAM still passed. The question is set coverage against the set a
  ;; booted cluster arms, and both sides are derived the same way.
  (let [armed (atom nil)
        arm!
        (fn [armable instrumented]
          (with-redefs-fn
            {#'instrument/apply!
             (fn [_] {:seon.instrument/registered 2
                      :seon.instrument/instrumented (count instrumented)})
             #'instrument/armable (fn [_] armable)
             #'instrument/instrumented (fn [] instrumented)}
            (fn []
              (test-support/refusal-data
               #(reset! armed
                        (#'runner/arm-contracts!
                         {::runner/decisions {:seon.config/on-core-error :panic}
                          ::runner/caps {}
                          ::runner/program '[seon.db]}
                         {:seon.schema.projection/forms {}}
                         "pool-1"
                         '[seon.db-test]))))))]
    (testing "a program contract with no wrapper refuses, naming the contract"
      (let [refusal (arm! #{#'seon.db/pull #'seon.db/q} #{#'seon.db/q})]
        (is (= ::runner/instrumentation-unavailable
               (first (keep #{::runner/instrumentation-unavailable}
                            (keys refusal))))
            "the refusal is the typed instrumentation-unavailable shape")
        (is (= ["seon.db/pull"]
               (::runner/unarmed-program-contracts refusal))
            "and it names the exact declared program contract a cluster arms
             and this worker did not")
        (is (= 2 (::runner/armable-count refusal)))))
    (testing "complete coverage arms without refusing"
      (let [applied (arm! #{#'seon.db/q} #{#'seon.db/q})]
        (is (= test-support/committed applied))
        (is (= 1 (:seon.instrument/instrumented @armed)))))
    (testing "a worker carrying MORE than the program is not a refusal"
      ;; the worker also loads test namespaces; only the program side matters
      (is (= test-support/committed
             (arm! #{#'seon.db/q} #{#'seon.db/q #'runner/run-var!}))))))

;;; ---------------------------------------------------------------------------
;;; A pooled worker measures what a task leaves behind
;;; ---------------------------------------------------------------------------

(deftest a-task-that-changes-worker-global-state-is-named-as-the-leaker
  ;; CLASS: `parallel-only` — a red that appears only under the whole gate,
  ;; because an EARLIER task in the same worker left process-global state
  ;; behind. The verdict used to name the victim and nothing else, so the
  ;; reader was sent to the wrong owner. AGENTS §5.7: own nothing global.
  ;; Nothing DECLARES which state is shared, so the seam that admits the work
  ;; derives it either side of the task and reports the difference.
  (testing "drift is derived per member, added and removed both"
    (let [before {::runner/snapshot-instrumented '#{seon.db/q seon.db/pull}
                  ::runner/snapshot-registered '#{seon.db/q}
                  ::runner/snapshot-live-clusters #{}}
          after {::runner/snapshot-instrumented '#{seon.db/q}
                 ::runner/snapshot-registered '#{seon.db/q}
                 ::runner/snapshot-live-clusters #{"scratch"}}
          drift (#'runner/ambient-drift before after)]
      (is (= ["seon.db/pull"]
             (get-in drift [::runner/snapshot-instrumented
                            ::runner/drift-removed]))
          "a task that stripped a wrapper is named by the wrapper it stripped")
      (is (= ["scratch"]
             (get-in drift [::runner/snapshot-live-clusters
                            ::runner/drift-added]))
          "and a task that left a cluster running is named by the cluster")
      (is (not (contains? drift ::runner/snapshot-registered))
          "an unchanged member contributes nothing: only drift is reported")))
  (testing "only the LEAKING direction of each member is reported"
    ;; a test calling `instrument/apply!` collects every loaded namespace's
    ;; contracts, including its own; reporting those 47 accreted rows as a
    ;; defect buries the one line that matters. A registration that
    ;; DISAPPEARED is still a real loss and is still reported.
    (let [added (#'runner/ambient-drift
                 {::runner/snapshot-registered '#{seon.db/q}}
                 {::runner/snapshot-registered '#{seon.db/q probe/added}})
          removed (#'runner/ambient-drift
                   {::runner/snapshot-registered '#{seon.db/q probe/lost}}
                   {::runner/snapshot-registered '#{seon.db/q}})]
      (is (empty? added)
          "accreted malli registrations are ordinary, not a leak")
      (is (= ["probe/lost"]
             (get-in removed [::runner/snapshot-registered
                              ::runner/drift-removed]))
          "a LOST registration is still reported, and named")))
  (testing "an unchanged world drifts not at all"
    (let [snapshot (#'runner/ambient-snapshot)]
      (is (empty? (#'runner/ambient-drift snapshot snapshot)))
      (is (pos? (count (::runner/snapshot-instrumented snapshot)))
          "and the snapshot is genuinely measuring an armed worker, so the
           emptiness above is not the absence of a subject")))
  (testing "a red task carries the earlier tasks that changed the world"
    (let [worker (start-injected-worker! "write-failure-attributed"
                                         "import sys; sys.exit(23)\n")
          journal (::runner/worker-journal
                   (assoc worker ::runner/worker-journal
                          (atom [{::runner/task-symbols ["seon.leaker/strips"]
                                  ::runner/task-ambient-drift
                                  {::runner/snapshot-instrumented
                                   {::runner/drift-removed ["seon.db/pull"]}}}])))
          worker (assoc worker ::runner/worker-journal journal)]
      (try
        (.get (.onExit (.toHandle ^Process (::runner/worker-process worker)))
              test-support/event-backstop-seconds TimeUnit/SECONDS)
        (let [result (execute-injected-task!
                      worker (exchange-task "write-failure-attributed"))]
          (is (= "write-failure-attributed" (::runner/executed-by result))
              "the red names the worker that produced it")
          (is (= [["seon.leaker/strips"]]
                 (mapv ::runner/task-symbols
                       (::runner/prior-ambient-drift result)))
              "and it carries its suspects, so the verdict names a leaker
               rather than only a victim"))
        (finally
          (stop-injected-worker! worker))))))

(deftest a-dead-workers-task-is-never-classified-parallel-only
  ;; CLASS: the re-arm defect. A worker that died mid-task took every
  ;; namespace it held down with it, and each was reported `confirmation
  ;; parallel-only` against its own owner — a day of someone else's
  ;; diagnosis. The pool "result" was manufactured by the exchange, so
  ;; classifying it by whether it passes in isolation attributes a dead
  ;; worker to whichever tests it happened to hold.
  (let [green {::runner/task-summary {::runner/fail-count 0
                                      ::runner/error-count 0}}
        red {::runner/task-summary {::runner/fail-count 1
                                    ::runner/error-count 0}}
        dead {::runner/worker-exchange-result
              {:seon.error/kind ::runner/worker-exited}}]
    (is (= :worker-exchange
           (#'runner/parallel-failure-classification dead green))
        "green in isolation does NOT make a dead worker's task parallel-only")
    (is (= :worker-exchange
           (#'runner/parallel-failure-classification dead red)))
    (is (= :parallel-only (#'runner/parallel-failure-classification {} green))
        "an ordinary pool red that is green alone is still parallel-only")
    (is (= :reproducible (#'runner/parallel-failure-classification {} red))))
  (testing "and the final tally names every non-test verdict"
    (let [tally
          (with-out-str
            (#'runner/print-final-tally!
             {::runner/test-count 4 ::runner/pass-count 0
              ::runner/fail-count 0 ::runner/error-count 4}
             [{::runner/task-ordinal 1
               ::runner/task-symbols ["seon.a/one"]
               ::runner/worker-exchange-result
               {:seon.error/kind ::runner/worker-exited
                ::runner/worker-id "pool-1"
                ::runner/worker-exit 1
                ::runner/worker-error-log "/tmp/pool-1.log"}}
              {::runner/task-ordinal 2
               ::runner/task-symbols ["seon.b/two"]
               ::runner/worker-pool-exhausted true}
              ;; a RETIRED worker never published an exit code or a log
              {::runner/task-ordinal 5
               ::runner/task-symbols ["seon.d/four"]
               ::runner/worker-exchange-result
               {:seon.error/kind ::runner/worker-retired
                ::runner/worker-id "serial"}}
              {::runner/task-ordinal 3
               ::runner/task-symbols ["seon.c/three"]
               ::runner/executed-by "pool-2"
               ::runner/parallel-failure :parallel-only
               ::runner/parallel-only-suspects [["seon.leaker/strips"]]}
              {::runner/task-ordinal 4
               ::runner/task-symbols ["seon.leaker/strips"]
               ::runner/task-ambient-drift
               {::runner/snapshot-instrumented
                {::runner/drift-removed ["seon.db/pull"]
                 ::runner/drift-removed-count 1}}}]))]
      (is (str/includes? tally "Worker exchange failures"))
      (is (str/includes? tally "seon.a/one"))
      (is (str/includes? tally "/tmp/pool-1.log"))
      (is (not (str/includes? tally "exit= "))
          "absent is no key in the tally too: a retired worker published no
           exit code and opened no log, and `exit= log=` claims two facts the
           runner does not have")
      (is (str/includes? tally "Unlaunchable tasks"))
      (is (str/includes? tally "seon.b/two"))
      (is (str/includes? tally "Parallel-only tasks"))
      (is (str/includes? tally "seon.leaker/strips")
          "the parallel-only line names its suspected leaker")
      (is (str/includes? tally "changed worker-global state"))
      (is (str/includes? tally "removed 1: seon.db/pull")
          "and the drift line NAMES what changed: \"3 wrappers removed\" sends
           the reader nowhere, \"seon.db/pull removed\" is the fix"))))

(deftest a-confirmation-loads-the-pool-workers-world
  ;; CLASS: a verdict that does not mean what it says. The confirmation used
  ;; to initialize its worker with ONE namespace, so a test whose subject
  ;; depends on what is LOADED — the program graph, the acquired SCI ctx's
  ;; bindings, which capability namespaces resolve — answered a different
  ;; question there than in the pool. `parallel-only` then meant "green in a
  ;; smaller world", which is no evidence about scheduling at all.
  (let [initialized (atom nil)
        parent (doto (io/file project-root "tmp" (str "confirm-world-" (random-uuid))) .mkdirs)
        namespaces '[seon.a-test seon.b-test seon.c-test]
        task-result {::runner/task-id "confirm-world"
                     ::runner/task-summary {::runner/fail-count 1}
                     ::runner/task-ordinal 1
                     ::runner/task-namespace "seon.a-test"
                     ::runner/task-symbols ["seon.a-test/one"]}]
    (with-redefs-fn
      {#'runner/start-worker! (fn [worker-id _ _] {::runner/worker-id worker-id})
       #'runner/initialize-worker! (fn [worker _namespaces]
                                     (reset! initialized _namespaces)
                                     worker)
       #'runner/stop-worker! (fn [_] nil)
       #'runner/execute-worker-task!
       (fn [_ _ _] {::runner/task-summary {::runner/fail-count 0
                                           ::runner/error-count 0}})}
      (fn []
        (let [previous (System/getProperty "seon.test.worker-parent")
              confirmed
              (try
                (System/setProperty "seon.test.worker-parent" (str parent))
                (#'runner/confirm-parallel-failure!
                 namespaces
                 (atom {::runner/description "confirmation world"
                        ::runner/at-nanos (System/nanoTime)
                        ::runner/at (java.time.Instant/now)})
                 task-result)
                (finally
                  (if previous
                    (System/setProperty "seon.test.worker-parent" previous)
                    (System/clearProperty "seon.test.worker-parent"))
                  (test-support/delete-recursively! parent)))]
          (is (= namespaces @initialized)
              "the confirmation worker loads exactly the namespaces the pool
               worker loaded, so the only remaining difference is that the
               task runs alone")
          (is (= :parallel-only (::runner/parallel-failure confirmed))))))))

(deftest ^{:seon.test/platform "Publication and workers consume the selected snapshot."}
  selected-paths-overlay-head-for-preparation-and-every-worker
  (let [root (doto (io/file project-root "tmp" (str "runner-paths-" (random-uuid))) .mkdirs)
        script (io/file root "probe.sh")
        log (io/file root "output.txt")
        child (atom nil)]
    (try
      (let [[status output]
            (fixture-command!
             root ["bash" "-c" (str "set -euo pipefail\n"
             "origin=$1\n"
             "fixture=$2\n"
             "mkdir -p \"$fixture/bin\" \"$fixture/src/seon\" \"$fixture/test\" \"$fixture/.agents/skills\" \"$fixture/.claude\" \"$fixture/.clj-kondo\" \"$fixture/tmp/fake-bin\"\n"
             "cd \"$fixture\"\n"
             "cp -R \"$origin/bin/.\" bin/\n"
             launcher-source-copy
             "printf '{:paths [\"src\"]}\\n' > bb.edn\n"
             "printf 'tmp/\\ntarget/\\n' > .gitignore\n"
             "printf 'base\\n' > src/owned.txt\n"
             "printf 'base\\n' > src/foreign.txt\n"
             "printf 'base\\n' > src/deleted.txt\n"
             "touch test/fixture_test.clj .agents/skills/fixture .clj-kondo/fixture\n"
             "ln -s .agents/skills seon-skills\n"
             "ln -s ../.agents/skills .claude/skills\n"
             "ln -s \"$origin/reference-code\" reference-code\n"
             "git init -q\n"
             "git add -- bin src test bb.edn .gitignore .agents .claude .clj-kondo seon-skills reference-code\n"
             "git -c user.name=\"$(git -C \"$origin\" config user.name)\" -c user.email=\"$(git -C \"$origin\" config user.email)\" commit -qm baseline\n")
                   "fixture-setup" (.getPath project-root)
                   (.getPath (io/file root "checkout"))])]
        (is (zero? status) output))
      (publish-fixture-head! (io/file root "checkout"))
      (spit script (str "set -euo pipefail\norigin=$1\nfixture=$2\ncd \"$fixture\"\n"
             "printf 'owned\\n' > src/owned.txt\n"
             "printf 'foreign\\n' > src/foreign.txt\n"
             "printf 'added\\n' > src/added.txt\n"
             "rm src/deleted.txt\n"
             "cat > tmp/fake-bin/clojure <<'SH'\n"
             "#!/usr/bin/env bash\n"
             "set -euo pipefail\n"
             "test \"$(cat src/owned.txt)\" = owned\n"
             "test \"$(cat src/foreign.txt)\" = \"${SEON_EXPECT_FOREIGN:-base}\"\n"
             "test \"$(cat src/added.txt)\" = added\n"
             "test ! -e src/deleted.txt\n"
             fake-dev-cache-prologue
             "for argument in \"$@\"; do\n"
             "  if [ \"$argument\" = --prepare-base ]; then mkdir -p \"${!#}/data/store\"; echo '{}' > \"${!#}/manifest.edn\"; exit 0; fi\n"
             "done\n"
             "for worker in workers/*; do\n"
             "  test \"$(cat \"$worker/src/owned.txt\")\" = owned\n"
             "  test \"$(cat \"$worker/src/foreign.txt\")\" = \"${SEON_EXPECT_FOREIGN:-base}\"\n"
             "  test \"$(cat \"$worker/src/added.txt\")\" = added\n"
             "  test ! -e \"$worker/src/deleted.txt\"\n"
             "done\n"
             "echo SNAPSHOT_VERIFIED\n"
             "SH\n"
             "printf '#!/usr/bin/env bash\\necho 2\\n' > tmp/fake-bin/getconf\n"
             "chmod +x tmp/fake-bin/*\n"
             "export PATH=\"$fixture/tmp/fake-bin:$PATH\"\n"
             "export SEON_FAKE_CACHE_DIGEST=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
             "export SEON_FAKE_CACHE_PATH=\"$fixture/tmp/cache/$SEON_FAKE_CACHE_DIGEST\"\n"
             "mkdir -p \"$SEON_FAKE_CACHE_PATH\"\n"
             "bin/test --paths src/owned.txt src/added.txt src/deleted.txt -- seon.fixture-test\n"
             "export SEON_EXPECT_FOREIGN=foreign\n"
             "bin/test seon.fixture-test > tmp/default.log 2>&1\n"
             "case \"$(cat tmp/default.log)\" in *src/foreign.txt*) ;; *) cat tmp/default.log; exit 1 ;; esac\n"
             "echo DEFAULT_SNAPSHOT_VERIFIED\n"))
      (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                 ["/bin/bash" (.getPath script)
                                  (.getPath project-root) (.getPath (io/file root "checkout"))])
                             (as-orchestrator)
                             (.redirectErrorStream true)
                             (.redirectOutput log)))]
        (reset! child process)
        (is (.waitFor process test-support/event-backstop-seconds TimeUnit/SECONDS)
            "the snapshot launcher must terminate within the test bound")
        (when-not (.isAlive process)
          (let [output (slurp log)]
            (is (zero? (.exitValue process)) output)
            (is (str/includes? output "SNAPSHOT_VERIFIED") output)
            (is (str/includes? output "DEFAULT_SNAPSHOT_VERIFIED") output)
            (is (str/includes? output "src/owned.txt") output)
            (is (str/includes? output "src/added.txt") output)
            (is (str/includes? output "src/deleted.txt") output)
            (is (not (str/includes? output "src/foreign.txt")) output))))
      (finally
        (when-let [process @child] (stop-process-tree! process))
        (test-support/delete-recursively! root)))))


(deftest codex-lanes-refuse-cold-gates-before-acquiring-resources
  (let [root (doto (io/file project-root "tmp" (str "lane-gate-" (random-uuid))) .mkdirs)
        script (io/file root "probe.sh")
        log (io/file root "output.txt")
        child (atom nil)]
    (try
      ;; The real launchers and their environment inheritance are the subject.
      ;; Only the paid Codex executable is replaced with a child that invokes
      ;; the real gate; no Clojure, database, or contract harness is imitated.
      (spit script
            (str "set -euo pipefail\n"
                 "origin=$1\nfixture=$2\n"
                 "unset LANE_SID\n"
                 "mkdir -p \"$fixture/bin\" \"$fixture/.agents/skills\" \"$fixture/.claude\" \"$fixture/tmp/commands\"\n"
                 "cd \"$fixture\"\n"
                 "cp \"$origin/bin/codex-agent\" \"$origin/bin/test\" bin/\n"
                 "ln -s .agents/skills seon-skills\nln -s ../.agents/skills .claude/skills\n"
                 "cat > tmp/commands/codex <<'SH'\n"
                 "#!/usr/bin/env bash\nset -euo pipefail\n"
                 "test \"$SEON_CODEX_LANE\" = lane-fixture\n"
                 "test -n \"$SEON_OPERATOR_EPHEMERAL_OWNER_PID\"\n"
                 "case \" $* \" in *' --dangerously-bypass-hook-trust '*) ;; *) exit 1 ;; esac\n"
                 "if [ \"${2:-}\" = resume ]; then\n"
                 "  case \" $* \" in *\" $SEON_FIXTURE_SID \"*) ;; *) exit 1 ;; esac\n"
                 "fi\n"
                 "cat > tmp/received-prompt\n"
                 "grep -F 'Lanes never run bin/test gates' tmp/received-prompt\n"
                 "printf 'session id: %s\\n' \"$SEON_FIXTURE_SID\"\n"
                 "printf 'session id: %s\\n' \"$SEON_FIXTURE_QUOTED_SID\"\n"
                 "refused() {\n"
                 "  local status=0\n"
                 "  bin/test \"$@\" > tmp/refusal 2>&1 || status=$?\n"
                 "  test \"$status\" = 64\n"
                 "  grep -F 'refusing gate from Codex lane lane-fixture; use bin/test-fast --paths' tmp/refusal\n"
                 "  test ! -e tmp/test-runs\ntest ! -e tmp/test-slots\n"
                 "}\n"
                 "refused\nrefused --all\nrefused --full\nrefused --platform\n"
                 "refused --paths bin/test -- seon.db-test\n"
                 "SEON_TEST_FULL=1 refused\nSEON_TEST_ORCHESTRATOR=1 refused\n"
                 ;; Reaching fast-mode validation proves the shared snapshot
                 ;; arm is admitted; the next regression runs that arm armed.
                 "status=0\nbin/test --fast > tmp/fast 2>&1 || status=$?\n"
                 "test \"$status\" = 64\n"
                 "grep -F -- '--fast requires --paths FILE... -- NAMESPACE...' tmp/fast\n"
                 "echo LANE_IDENTITY_VERIFIED\nSH\n"
                 "chmod +x tmp/commands/codex\nexport PATH=\"$fixture/tmp/commands:$PATH\"\n"
                 "export SEON_FIXTURE_SID=$(uuidgen | tr '[:upper:]' '[:lower:]')\n"
                 "export SEON_FIXTURE_QUOTED_SID=$(uuidgen | tr '[:upper:]' '[:lower:]')\n"
                 "mkdir -p tmp/orchestrator\n"
                 "printf 'session id: %s\\n' \"$SEON_FIXTURE_QUOTED_SID\" > tmp/orchestrator/lane-fixture-stdout.log\n"
                 "bin/codex-agent run lane-fixture 'Verify lane admission.'\n"
                 "test \"$(cat tmp/orchestrator/lanes/lane-fixture/sid)\" = \"$SEON_FIXTURE_SID\"\n"
                 "test ! -d tmp/orchestrator/lanes/lane-fixture/active\n"
                 "bin/codex-agent resume lane-fixture 'Verify resumed lane admission.'\n"
                 "test \"$(cat tmp/orchestrator/lanes/lane-fixture/sid)\" = \"$SEON_FIXTURE_SID\"\n"
                 "rm tmp/orchestrator/lanes/lane-fixture/sid\n"
                 "bin/codex-agent resume lane-fixture 'Backfill pre-record launch.'\n"
                 "test \"$(cat tmp/orchestrator/lanes/lane-fixture/sid)\" = \"$SEON_FIXTURE_SID\"\n"
                 "printf 'invalid\\n' > tmp/orchestrator/lanes/lane-fixture/sid\n"
                 "status=0\nbin/codex-agent resume lane-fixture 'Invalid record.' > tmp/invalid 2>&1 || status=$?\n"
                 "test \"$status\" = 65\ngrep -F 'no valid session identity' tmp/invalid\n"
                 "rm tmp/orchestrator/lanes/lane-fixture/sid tmp/orchestrator/lane-fixture-stdout.log\n"
                 "status=0\nbin/codex-agent resume lane-fixture 'No record or log.' > tmp/missing 2>&1 || status=$?\n"
                 "test \"$status\" = 65\ngrep -F 'no valid session identity' tmp/missing\n"))
      (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                                 ["/bin/bash" (.getPath script)
                                                  (.getPath project-root)
                                                  (.getPath (io/file root "checkout"))])
                             (.redirectErrorStream true)
                             (.redirectOutput log)))]
        (reset! child process)
        (is (.waitFor process test-support/event-backstop-seconds TimeUnit/SECONDS)
            "both lane launch modes must settle within the event bound")
        (when-not (.isAlive process)
          (let [output (slurp log)]
            (is (zero? (.exitValue process)) output)
            (is (= 3 (count (filter #{"LANE_IDENTITY_VERIFIED"}
                                    (str/split-lines output)))) output))))
      (finally
        (when-let [process @child] (stop-process-tree! process))
        (test-support/delete-recursively! root)))))

(deftest fast-selected-paths-exclude-a-broken-foreign-file
  (let [root (doto (io/file project-root "tmp" (str "fast-paths-" (random-uuid))) .mkdirs)
        script (io/file root "probe.sh")
        log (io/file root "output.txt")
        child (atom nil)]
    (try
      (let [[status output]
            (fixture-command!
             root ["bash" "-c" (str "set -euo pipefail\n"
                 "origin=$1\nfixture=$2\nmkdir -p \"$fixture\"\n"
                 "git -C \"$origin\" archive HEAD | tar -x -C \"$fixture\"\n"
                 "cd \"$fixture\"\n"
                 "rmdir reference-code/* 2>/dev/null || true\n"
                 "rm -rf reference-code\n"
                 "ln -s \"$origin/reference-code\" reference-code\n"
                 "for path in bin src resources test; do cp -R \"$origin/$path/.\" \"$path/\"; done\n"
                 "git init -q\n"
                 "git add -f -- bin src test resources config deps.edn bb.edn .agents .claude seon-skills .gitignore .clj-kondo script dev_cache.clj reference-code\n"
                 "git -c user.name=\"$(git -C \"$origin\" config user.name)\" -c user.email=\"$(git -C \"$origin\" config user.email)\" commit -qm baseline\n")
                   "fixture-setup" (.getPath project-root)
                   (.getPath (io/file root "checkout"))])]
        (is (zero? status) output))
      (publish-fixture-head! (io/file root "checkout"))
      (spit script (str "set -euo pipefail\norigin=$1\nfixture=$2\ncd \"$fixture\"\n"
                 "printf '(' > src/seon/repl.clj\n"
                 "cat > test/seon/fast_paths_fixture_test.clj <<'CLJ'\n"
                 "(ns seon.fast-paths-fixture-test (:require [clojure.test :refer [deftest is]] [seon.instrument :as instrument]))\n"
                 "(deftest selected-working-bytes (is (= :selected (identity :selected))) (is (seq (instrument/instrumented))))\n"
                 "CLJ\n"
                 "SEON_CODEX_LANE=fast-fixture SEON_TEST_RUN_PARENT=\"$fixture/tmp/test-runs\" bin/test-fast --paths test/seon/fast_paths_fixture_test.clj -- seon.fast-paths-fixture-test\n"
                 "test \"$(cat src/seon/repl.clj)\" = '('\n"
                 "test -z \"$(find tmp/test-runs -name 'run.*' -type d -print)\"\n"))
      (let [process (.start
                     (doto (ProcessBuilder. ^java.util.List
                                            ["/bin/bash" (.getPath script)
                                             (.getPath project-root)
                                             (.getPath (io/file root "checkout"))])
                       (.redirectErrorStream true)
                       (.redirectOutput log)))]
        (reset! child process)
        (is (.waitFor process (* 12 test-support/event-backstop-seconds)
                      TimeUnit/SECONDS)
            "the real armed snapshot JVM must finish within the subprocess bound")
        (when-not (.isAlive process)
          (let [output (slurp log)]
            (is (zero? (.exitValue process)) output)
            (is (str/includes? output "CONTRACTS ARMED") output)
            (is (str/includes? output "Ran 1 tests containing 2 assertions.") output)
            (is (str/includes? output "0 failures, 0 errors.") output))))
      (finally
        (when-let [process @child] (stop-process-tree! process))
        (test-support/delete-recursively! root)))))

(deftest recording-refusal-notice-names-the-cluster-cause
  ;; The class: a check that reports the absence of a readable reply as a kind
  ;; and a fixed sentence. The gate line must carry the raiser's own data.
  (let [operator-failure
        (ex-info
         (str "The cluster threw during the prepl operation: "
              "clojure.lang.ExceptionInfo: record-results! refused completion")
         {:seon.error/kind :seon.fresh-operator/prepl-exception
          :seon.fresh-operator/cause "record-results! refused completion"
          :seon.fresh-operator/exception-data
          {:seon.error/kind :seon.instrument/contract-violated}
          :seon.fresh-operator/form "(try (require 'seon.cluster.source)"
          :seon.fresh-operator/events [{:tag :ret :exception true}]})
        failure (#'runner/recording-failure
                 (fn [] (throw operator-failure)))
        notice (#'runner/recording-failure-notice "persistent results" failure)]
    (testing "the refusal value keeps the raiser's kind and data"
      (is (= :seon.fresh-operator/prepl-exception (:seon.error/kind failure)))
      (is (= "record-results! refused completion"
             (:seon.fresh-operator/cause (:seon.error/data failure))))
      (is (= {:seon.error/kind :seon.instrument/contract-violated}
             (:seon.fresh-operator/exception-data
              (:seon.error/data failure)))))
    (testing "the printed gate line names the cluster's cause"
      (is (str/includes? notice "persistent results NOT recorded:") notice)
      (is (str/includes? notice ":seon.fresh-operator/prepl-exception") notice)
      (is (str/includes? notice "record-results! refused completion") notice)
      (is (str/includes? notice ":seon.instrument/contract-violated") notice)
      (is (not (str/includes? notice ":seon.fresh-operator/events"))
          "the raw prepl events stay out of the one-line notice"))
    (testing "a refusal with no data still prints kind and message"
      (let [bare (#'runner/recording-failure
                  (fn [] {:seon.error/kind ::probe
                          :seon.error/message "bare"}))]
        (is (= (str "bin/test: persistent results NOT recorded: "
                    ":seon.test-runner-test/probe bare")
               (#'runner/recording-failure-notice "persistent results" bare)))))))

(defn- synthetic-results
  "A gate-sized captured-result vector: one green row per synthetic test."
  [test-count]
  (mapv (fn [ordinal]
          {:seon.test/sym (str "seon.staged-completion-fixture.ns"
                               (quot ordinal 50) "-test/case-" ordinal)
           :seon.test/pass-count 1
           :seon.test/fail-count 0
           :seon.test/error-count 0})
        (range test-count)))

(deftest gate-completions-travel-as-a-file-not-as-code
  ;; The class this kills: DATA TRAVELLING AS CODE. persistent-results-form
  ;; inlined the entire completion as a literal inside the form it sent over
  ;; the prepl, so the cluster compiled ONE method whose bytecode grew with
  ;; the result count and passed the JVM's 64 KB ceiling at gate size —
  ;; "Method code too large!", reported only as a recording refusal. The
  ;; completion is now staged as EDN and the sent form carries just its path,
  ;; so the form is O(1) in the number of results.
  (test-support/with-database
    (fn [connection]
      (let [results (synthetic-results 2000)
            run-result {:seon.test.run/id "staged-completion-regression"
                        :seon.test.run/at at
                        :seon.test.runner/results results
                        :seon.test/run-basis-t (db/basis-t @connection)
                        :seon.test/run-at at}
            file (#'runner/stage-completion! run-result)]
        (try
          (let [form (#'runner/persistent-results-form (str file))
                staged (#'runner/staged-completion file)]
            (is (> (count (pr-str run-result)) 65536)
                "the completion itself exceeds the JVM method-code ceiling")
            (is (< (count form) 1024)
                (str "the sent form must stay O(1); it was " (count form)
                     " bytes"))
            (is (not (str/includes? form "case-1999"))
                "no result travels inside the sent form")
            (is (str/includes? form (str file))
                "the sent form names the staged completion's absolute path")
            (is (= results (:seon.test.runner/results staged))
                "the staged file reads back as the exact completion")
            (is (= at (:seon.test/run-at staged))
                "instants survive the EDN round trip")
            (is (= 2000
                   (count (runner/commit-results!
                           connection
                           (assoc (select-keys staged
                                               [:seon.test.runner/results
                                                :seon.test/run-basis-t
                                                :seon.test/run-at])
                                  :seon.test.run/provenance
                                  (runner/provenance @connection)))))
                "every staged result commits as a test row"))
          (finally
            (io/delete-file file true)))))))

(deftest a-missing-or-unreadable-staged-completion-is-named
  ;; A check that reads absence of signal as health would commit an empty
  ;; completion here — retracting every recorded result. Absence is typed.
  (let [missing (#'runner/staged-completion "/nonexistent/gate-completion.edn")]
    (is (= :seon.test.runner/staged-completion-unreadable
           (:seon.error/kind missing)))
    (is (str/includes? (:seon.error/message missing)
                       "/nonexistent/gate-completion.edn"))
    (is (= "/nonexistent/gate-completion.edn"
           (:seon.test.runner/completion-path missing))))
  (let [file (#'runner/stage-completion! {:seon.test.run/id "unreadable-probe"})]
    (try
      (spit file "{:seon.test.runner/results [")
      (let [truncated (#'runner/staged-completion file)]
        (is (= :seon.test.runner/staged-completion-unreadable
               (:seon.error/kind truncated)))
        (is (str/includes? (:seon.error/message truncated) "did not read as EDN")
            (:seon.error/message truncated)))
      (spit file "[1 2 3]")
      (let [not-a-map (#'runner/staged-completion file)]
        (is (= :seon.test.runner/staged-completion-unreadable
               (:seon.error/kind not-a-map)))
        (is (str/includes? (:seon.error/message not-a-map)
                           "did not read as a completion map")
            (:seon.error/message not-a-map)))
      (finally
        (io/delete-file file true)))))
