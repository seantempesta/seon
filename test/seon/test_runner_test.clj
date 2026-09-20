(ns seon.test-runner-test
  "Declared latest-result facts owned by the JVM test runner."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [seon.test.cache :as cache]
            [dev-cache]
            [seon.dev.dependency-digest :as dependency-digest]
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

(deftest ^{:seon.test/platform "Coordinator owns bounded worker sizing and explicit overrides."}
  coordinator-uses-the-prepared-worker-count
  (is (= 2 (#'runner/worker-count 16 "2")))
  (is (= 1 (#'runner/worker-count 16 "1")))
  (is (= 3 (#'runner/worker-count 16 nil)))
  (is (= 1 (#'runner/worker-count 1 nil)))
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



;; NOT :seon.test/platform: the fixture reaches
;; seon.test-support/populate-published-root!, which deletes and reclones a
;; store directory. The platform tier runs first on every bin/test invocation,
;; so a destructive fixture there deletes before any evidence exists
;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).


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
   "  printf '#:seon.test{:classpath-roots [\"/fixture-classpath\"], :classpath-root \".\", :jvm-options []}\\n' >\"$SEON_FAKE_CACHE_PATH/basis.edn\"\n"
   "  printf '#:seon.dev-cache{:digest \"%s\", :test-digest \"%s\", :path \"%s\", :test-classpath-file \"%s/classpath.edn\", :test-basis-file \"%s/basis.edn\"}\\n' \\\n"
   "    \"$SEON_FAKE_CACHE_DIGEST\" \"$SEON_FAKE_CACHE_DIGEST\" \"$SEON_FAKE_CACHE_PATH\" \"$SEON_FAKE_CACHE_PATH\" \"$SEON_FAKE_CACHE_PATH\"\n"
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
    (is (not (str/includes? output "Recorded"))
        "Exchange diagnostics cannot claim a recorded tally before recording.")
    (is (str/includes? output "Unconfirmed tasks — 1 task(s)")
        "the tally counts unconfirmed work rather than listing it silently")
    (is (str/includes? output " - seon.example-test/unlaunchable")
        "and names the task, so the reader is not left with a bare count")
    (is (str/includes? output "[INJECTED FIXTURE]")
        "fixture output cannot be mistaken for a production launch line")))













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
      (is (str/includes? output "recorded tally unavailable"))
      (is (= 1 (occurrences output
                            "bin/test: persistent results NOT recorded:")))
      (is (str/includes?
           output
           ":seon.test.runner/injected-persistent-recording-failure"))
      (is (not (str/includes? output "Recorded 1 executed"))
          "A recording refusal cannot print a successful recorded tally."))))

(deftest explicit-result-root-directs-bare-gate-evidence
  (is (= "/tmp/isolated-operator-root"
         (#'runner/configured-persistent-results-root
          "/checkout-root" "/tmp/isolated-operator-root")))
  (is (= "/checkout-root"
         (#'runner/configured-persistent-results-root
          "/checkout-root" nil))))

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
          (is (= :seon.test.runner/test-definition-absent (:seon.error/kind recorded)))
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



(def ^:private launcher-source-copy
  (str "cp \"$origin/src/seon/fs.clj\" src/seon/fs.clj\n"
       "mkdir -p src/seon/test\n"
       "cp -R \"$origin/src/seon/test/.\" src/seon/test/\n"))

(defn- publish-fixture-head! [checkout]
  (let [directory (io/file checkout "target/test-published-bases" fake-cache-digest)
        base (doto (io/file directory "base") .mkdirs)]
    (io/copy (io/file project-root "dev_cache.clj") (io/file checkout "dev_cache.clj"))
    (spit (io/file base "manifest.edn")
          (pr-str (functions/build-manifest
                   {:seon.fn/root (.getCanonicalPath checkout)
                    :seon.fn/roots ["src" "test"]})))
    (spit (io/file directory "ready.edn")
          (pr-str {:seon.test.cache/digest fake-cache-digest
                   :seon.test.cache/inputs (#'dev-cache/test-inputs (.getCanonicalPath checkout) fake-cache-digest)}))))

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
        (let [[status sha] (fixture-command! checkout ["git" "rev-parse" "HEAD"])]
          (is (zero? status) sha)
          (spit ready (pr-str (assoc (edn/read-string (slurp ready))
                                    :seon.test.cache/git-sha (str/trim sha)
                                    :seon.test.cache/prepared-at "2026-09-17T00:00:00Z"))))
        (let [record (slurp ready)
              executable (io/file checkout "tmp/fake-bin/clojure")]
          (io/make-parents executable)
          (spit executable (str "#!/bin/sh\necho LAUNCHED > '" (.getCanonicalPath marker) "'\n"))
          (.setExecutable executable true)
          (io/delete-file ready)
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (zero? status) output)
            (is (.exists marker) "an exact HEAD snapshot needs no overlay graph"))
          (io/delete-file marker true)
          (spit api "(ns probe.api)\n(defn value [x] x)\n(defn unchanged [x] x)\n;; dirty overlay\n")
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (= 64 status) output)
            (is (str/includes? output "orchestrator must run: bin/test --prepare-head-base") output)
            (is (not (.exists marker)) "absent baseline refuses before a JVM"))
          (spit api "(ns probe.api)\n(defn value [x] x)\n(defn unchanged [x] x)\n")
          (let [fast-source (slurp executable)]
            (io/copy (io/file checkout "target/test-published-bases" fake-cache-digest "base/manifest.edn")
                     (io/file checkout "tmp/head-manifest.edn"))
            (spit (io/file checkout "tmp/inputs.edn")
                  (pr-str (:seon.test.cache/inputs (edn/read-string record))))
            (spit executable
                  (str "#!/bin/bash\nset -euo pipefail\n"
                       "mkdir -p target/test-classpaths\n"
                       "cp \"$SEON_FAKE_CACHE_PATH/../../inputs.edn\" target/test-classpaths/" fake-cache-digest ".inputs.edn\n"
                       fake-dev-cache-prologue
                       "for argument in \"$@\"; do\n"
                       "  if [ \"$argument\" = --prepare-base ]; then\n"
                       "    mkdir -p \"${!#}/data/store\"\n"
                       "    cp \"$SEON_FAKE_CACHE_PATH/../../head-manifest.edn\" \"${!#}/manifest.edn\"\n"
                       "    echo HEAD_PREPARED\n    exit 0\n  fi\ndone\n"
                       "echo COLD_RAN\n"))
            (doseq [selection ["--paths src/probe/api.clj -- seon.fixture-test"
                               "--platform --paths src/probe/api.clj"]]
              (io/delete-file ready true)
              (let [[status output]
                    (fixture-command!
                     checkout
                     ["bash" "-c"
                      (str "mkdir -p tmp/cache/" fake-cache-digest "\n"
                           "export PATH=\"$PWD/tmp/fake-bin:$PATH\"\n"
                           "export SEON_FAKE_CACHE_PATH=\"$PWD/tmp/cache/" fake-cache-digest "\"\n"
                           "export SEON_FAKE_CACHE_DIGEST=" fake-cache-digest "\n"
                           "exec bin/test " selection)] true)]
                (is (zero? status) output)
                (is (str/includes? output "HEAD_PREPARED") output)
                (is (= (:seon.test.cache/git-sha (edn/read-string record))
                       (:seon.test.cache/git-sha (edn/read-string (slurp ready)))))
                (is (str/includes? output "COLD_RAN") output)))
            (spit executable fast-source))
          (spit ready (pr-str (assoc-in (edn/read-string record) [:seon.test.cache/inputs 0 "src/probe/api.clj"] "stale")))
          (spit (io/file checkout "src/probe/new.clj") "(ns probe.new)\n")
          (is (zero? (first (fixture-command! checkout ["git" "add" "src/probe/new.clj"]))))
          (is (zero? (first (fixture-command! checkout ["git" "commit" "-qm" "another lane source commit"]))))
          (let [older-digest (apply str (repeat 64 "b"))
                older (io/file checkout "target/test-published-bases" older-digest)
                graph (io/file older "base/manifest.edn")]
            (io/make-parents graph)
            (io/copy (io/file checkout "target/test-published-bases" fake-cache-digest "base/manifest.edn") graph)
            (spit (io/file older "ready.edn")
                  (pr-str (assoc (edn/read-string record)
                                 :seon.test.cache/digest older-digest
                                 :seon.test.cache/prepared-at "2025-09-17T00:00:00Z"))))
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (zero? status) output)
            (is (.exists marker) "a stale published graph still admits fast iteration")
            (is (str/includes? output (str "overlay graph " fake-cache-digest " age= 1 commits behind HEAD")) output))
          (io/delete-file marker true)
          (publish-fixture-head! checkout)
          (spit (io/file checkout "docs/fixture") "documentation-only commit\n")
          (is (zero? (first (fixture-command! checkout ["git" "add" "docs/fixture"]))))
          (is (zero? (first (fixture-command! checkout ["git" "commit" "-qm" "documentation only"]))))
          (let [[status output] (command ["src/probe/api.clj"])]
            (is (zero? status) output)
            (is (.exists marker) "documentation commits reuse the same published source inputs"))
          (io/delete-file marker true)
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
          (spit ready (pr-str (edn/read-string record)))
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
            (is (str/includes? output "published overlay baseline") output)
            (is (= (:seon.test.cache/inputs (edn/read-string record))
                   (:seon.test.cache/inputs (edn/read-string (slurp ready)))))
            (is (= roots-before
                   (set (map #(.getName %) (.listFiles (io/file checkout "tmp/test-runs")))))
                "successful preparation removes its disposable run root")
            (is (not (str/includes? output "UNEXPECTED_RUNNER")) output))))
      (finally (test-support/delete-recursively! root)))))























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
          {:seon.test/sym (symbol (str "seon.staged-completion-fixture.ns"
                                       (quot ordinal 50) "-test")
                                 (str "case-" ordinal))
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
            (is (= :seon.test.runner/test-definition-absent
                   (:seon.error/kind (runner/commit-results!
                           connection
                           (assoc (select-keys staged
                                               [:seon.test.runner/results
                                                :seon.test/run-basis-t
                                                :seon.test/run-at])
                                  :seon.test.run/provenance
                                  (runner/provenance @connection)))))
                "transported results cannot fabricate absent program definitions"))
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

(deftest child-process-coverage-is-explicit-long-integration
  (require 'seon.test-runner-integration-test)
  (let [tests (#'runner/test-vars-in ['seon.test-runner-integration-test])
        declarations (into {} (map (fn [v] [(#'runner/var-symbol v) (meta v)])) tests)
        selected (#'runner/test-selection
                  ['seon.test-runner-integration-test]
                  {::runner/include-long? false
                   ::runner/long-declarations declarations
                   ::runner/platform-declarations declarations
                   ::runner/selected-symbols :all})]
    (is (seq tests) "The moved coverage must exist and load.")
    (is (every? #(and (string? (:seon.test/long (meta %)))
                     (pos-int? (:seon.test/long-ms (meta %)))
                     (nil? (:seon.test/platform (meta %)))) tests))
    (is (= (count tests) (count (::runner/skipped selected))))
    (is (empty? (::runner/platform selected)))
    (is (empty? (::runner/selected selected)))))
