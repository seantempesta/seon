(ns seon.test-runner-integration-test
  "Explicit orchestrator subprocess coverage; excluded from ordinary runner and platform selection."
  (:require [seon.test-runner-test :as fixture]
            [clojure.java.io :as io]
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

(defn- assert-one-terminal-error!
  [case-name result expected-kind]
  (is (= {::runner/test-count 1
          ::runner/pass-count 0
          ::runner/fail-count 0
          ::runner/error-count 1}
         (::runner/task-summary result))
      (str case-name " contributes one terminal task to the total tally"))
  (is (= 1 (count (::runner/task-results result))))
  (is (= (symbol "seon.exchange-test" case-name)
         (:seon.test/sym (first (::runner/task-results result)))))
  (is (= expected-kind
         (get-in result [::runner/worker-exchange-result
                         :seon.error/kind])))
  (is (= [(symbol "seon.exchange-test" case-name)]
         (get-in result [::runner/worker-exchange-result
                         ::runner/task-symbols]))))

(deftest ^{:seon.test/long "Orchestrator integration: observes real child exit while serial work awaits a bounded release event."
           :seon.test/long-ms 1800000}
  drained-pool-exits-before-serial-release
  (let [serial-entered (CountDownLatch. 1)
        release-serial (CountDownLatch. 1)
        children (atom [])
        launch (fn [worker-id]
                 (let [worker
                       ((deref (var fixture/start-injected-worker!))
                        worker-id
                        (str "import sys\n"
                             "sys.stdin.readline()\n"
                             "print('SEON_TEST_WORKER_EDN "
                             (pr-str {::runner/worker-event :stopped
                                      ::runner/worker-id worker-id
                                      ::runner/exchange-id (str worker-id "/stop")})
                             "', flush=True)\n"))]
                   (swap! children conj worker)
                   (let [process ^Process (::runner/worker-process worker)]
                     (prn {::runner/worker-id worker-id
                           ::created-pid (.pid process)
                           ::created-at (str (.orElse (.startInstant (.info (.toHandle process))) nil))}))
                   worker))
        pool (delay (launch "pool-1"))
        serial (delay (launch "serial"))
        task (fn [n] {::runner/task-id (str n)
                       ::runner/task-symbols [(symbol "seon.lifetime-fixture" (str "test-" n))]})
        executor (java.util.concurrent.Executors/newSingleThreadExecutor)]
    (try
      (with-redefs-fn
        {#'runner/execute-worker-task!
         (fn [_ worker task]
           (if (= "serial" (::runner/worker-id worker))
             (do (.countDown serial-entered)
                 (test-support/await-event! release-serial ::serial-release))
             (test-support/await-event! serial-entered ::serial-entered))
           (assoc task ::runner/task-summary
                       {::runner/test-count 1 ::runner/pass-count 1
                        ::runner/fail-count 0 ::runner/error-count 0}))}
        (fn []
          (let [completion (.submit executor ^java.util.concurrent.Callable
                                    (#'runner/on-caller-loader
                                     #(#'runner/run-task-pool! nil [pool] serial [(task 0)] [(task 1)])))]
            (try
              (test-support/await-event! serial-entered ::serial-entered)
              (let [process ^Process (::runner/worker-process (force pool))]
                (test-support/await-event! (.onExit process) ::pool-process-exit)
                (prn {::pool-exited-pid (.pid process)
                      ::exit-code (.exitValue process)
                      ::serial-release-pending? (pos? (.getCount release-serial))})
                (is (not (.isAlive process)))
                (is (not (.isDone completion)))
                (is (.isAlive ^Process (::runner/worker-process (force serial)))))
              (finally (.countDown release-serial)))
            (let [results (test-support/await-event! completion ::stage-completion)]
              (is (= {"0" 1 "1" 1} (frequencies (map ::runner/task-id results))))
              (is (every? #(not (.isAlive ^Process (::runner/worker-process %))) @children))))))
      (finally
        (.countDown release-serial)
        (.shutdownNow executor)
        (doseq [worker @children]
          ((deref (var fixture/stop-injected-worker!)) worker))))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} dependency-configuration-excludes-first-party-source
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "dependency-inputs-" (random-uuid))) .mkdirs)
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
        ((deref (var fixture/stop-process-tree!)) child)
        (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/fixture-observation "Cache reuse is verified against a real published file store and its immutable manifest across launcher invocations.", :seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} consecutive-cache-invocations-reuse-the-published-base
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "base-reuse-" (random-uuid))) .mkdirs)
        supplied (System/getProperty "seon.test.published-base")
        published (or supplied (str (io/file root "standalone/base")))]
    (try
      (when-not supplied
        ;; Direct iteration uses the same real publication fixture as boot tests.
        (test-support/populate-published-root! published)
        (spit (io/file published "manifest.edn") (pr-str @test-support/source-manifest))
        (spit (io/file root "standalone/ready.edn")
              (pr-str {:seon.test.cache/digest
                       (#'dev-cache/test-digest (str (deref (var fixture/project-root)))
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
                                ["bb" "--config" (str (io/file (deref (var fixture/project-root)) "bb.edn"))
                                 "-m" "seon.test.cache" (str root) (str (deref (var fixture/project-root)))
                                 digest (System/getProperty "java.class.path")
                                 (str (.pid (ProcessHandle/current)))])
                           (.directory (deref (var fixture/project-root)))
                           (.redirectErrorStream true)
                           (.redirectOutput log)))]
              (try
                (is (.waitFor child test-support/event-backstop-seconds TimeUnit/SECONDS))
                (when-not (.isAlive child)
                  (is (zero? (.exitValue child)) (slurp log))
                  (is (str/includes? (slurp log) "REUSE cached base") (slurp log))
                  (is (not (str/includes? (slurp log) "PUBLISH cached base")) (slurp log)))
                (finally ((deref (var fixture/stop-process-tree!)) child)))))
          (is (= ready-value (edn/read-string (slurp ready))))
          (is (= before (cache/manifest (str base))))
          (is (.isDirectory (io/file published "data/store"))))
      (finally (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} exit-before-readiness-is-one-attributed-terminal-value
  (let [worker ((deref (var fixture/start-injected-worker!)) "readiness-exit"
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
        ((deref (var fixture/stop-injected-worker!)) worker)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} kill-after-command-acceptance-is-one-attributed-task-result
  (let [accepted-name "accepted"
        worker
        ((deref (var fixture/start-injected-worker!))
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
            (future ((deref (var fixture/execute-injected-task!)) worker ((deref (var fixture/exchange-task)) "killed")))
            _ (is (= accepted-name ((deref (var fixture/await-created!)) watcher accepted-name)))
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
        ((deref (var fixture/stop-injected-worker!)) worker)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} a-worker-dying-during-re-arm-names-the-re-arm
  (let [worker
        ((deref (var fixture/start-injected-worker!))
         "re-arm-death"
         (str "import os, sys\n"
              "sys.stdin.readline()\n"
              "print('SEON_TEST_WORKER_EDN {:seon.test.runner/worker-event :re-arming :seon.test.runner/worker-id \\\"re-arm-death\\\" :seon.test.runner/exchange-id \\\"re-arm-death\\\"}', flush=True)\n"
              "os._exit(17)\n"))]
    (try
      (let [result ((deref (var fixture/execute-injected-task!)) worker ((deref (var fixture/exchange-task)) "re-arm-death"))]
        (assert-one-terminal-error! "re-arm-death" result ::runner/re-arm-failed)
        (is (= 17 (get-in result [::runner/worker-exchange-result
                                  ::runner/worker-exit])))
        (is (= :worker-exchange
               (#'runner/parallel-failure-classification result
                {::runner/task-summary {::runner/fail-count 0
                                         ::runner/error-count 0}}))))
      (finally ((deref (var fixture/stop-injected-worker!)) worker)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} checked-write-failure-is-one-attributed-task-result
  (let [worker ((deref (var fixture/start-injected-worker!)) "write-failure"
                                       "import sys; sys.exit(19)\n")
        process ^Process (::runner/worker-process worker)]
    (try
      (.get (.onExit (.toHandle process))
            test-support/event-backstop-seconds TimeUnit/SECONDS)
      (let [result ((deref (var fixture/execute-injected-task!)) worker
                                           ((deref (var fixture/exchange-task)) "write-failure"))]
        (assert-one-terminal-error! "write-failure" result
                                    ::runner/worker-write-failure))
      (finally
        ((deref (var fixture/stop-injected-worker!)) worker)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} live-worker-exceeding-its-bound-is-one-attributed-task-result
  (let [worker
        ((deref (var fixture/start-injected-worker!))
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
              #((deref (var fixture/execute-injected-task!)) worker ((deref (var fixture/exchange-task)) "bounded")))]
        (assert-one-terminal-error! "bounded" result
                                    ::runner/worker-exchange-bound)
        (is (false? (.isAlive ^Process (::runner/worker-process worker)))
            "the bounded worker is retired before another dispatch"))
      (finally
        ((deref (var fixture/stop-injected-worker!)) worker)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} ordinary-worker-reply-is-one-attributed-terminal-value
  (let [worker
        ((deref (var fixture/start-injected-worker!))
         "ordinary"
         (str
          "import sys\n"
          "sys.stdin.readline()\n"
          "print('SEON_TEST_WORKER_EDN {:seon.test.runner/worker-event :task-complete "
          ":seon.test.runner/worker-id \\\"ordinary\\\" "
          ":seon.test.runner/exchange-id \\\"ordinary\\\" "
          ":seon.test.runner/task-id \\\"ordinary\\\" "
          ":seon.test.runner/task-symbols [seon.exchange-test/ordinary] "
          ":seon.test.runner/task-summary {:seon.test.runner/test-count 1 "
          ":seon.test.runner/pass-count 1 :seon.test.runner/fail-count 0 "
          ":seon.test.runner/error-count 0} :seon.test.runner/task-results [] "
          ":seon.test.runner/task-elapsed-ms 1}', flush=True)\n"))]
    (try
      (let [result ((deref (var fixture/execute-injected-task!)) worker ((deref (var fixture/exchange-task)) "ordinary"))]
        (is (= {::runner/test-count 1
                ::runner/pass-count 1
                ::runner/fail-count 0
                ::runner/error-count 0}
               (::runner/task-summary result)))
        (is (= "ordinary" (::runner/task-id result)))
        (is (nil? (::runner/worker-exchange-result result))))
      (finally
        ((deref (var fixture/stop-injected-worker!)) worker)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} liveness-dump-includes-coordinator-and-worker-virtual-threads
  (let [release (CountDownLatch. 1)
        paths (volatile! [])
        fixture-root
        (io/file (deref (var fixture/project-root)) "tmp" "test-runner-worker-dump"
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
            _ (is (= "child-ready" ((deref (var fixture/await-created!)) watcher "child-ready")))
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

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} dependency-tool-loads-selection
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "tool-selection-" (random-uuid))) .mkdirs)]
    (try
      (let [checkout ((deref (var fixture/launcher-checkout!)) root)]
        (io/copy (io/file (deref (var fixture/project-root)) "deps.edn") (io/file checkout "deps.edn"))
        (spit (io/file checkout "dev_cache_probe.clj")
              (str "(ns dev-cache-probe)\n"
                   "(defn verify [_]\n"
                   "  (load-file \"src/seon/test/selection.clj\")\n"
                   "  (assert (seq ((resolve 'seon.test.selection/input-digests) \".\")))\n"
                   "  (println \"TOOL_SELECTION_LOADED\"))\n"))
        (let [[status output]
              ((deref (var fixture/fixture-command!)) checkout ["clojure" "-T:dev-cache" "dev-cache-probe/verify"])]
          (is (zero? status) output)
          (is (str/includes? output "TOOL_SELECTION_LOADED") output)))
      (finally (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} selected-overlays-require-a-current-graph-and-every-changed-caller
  ((deref (var fixture/overlay-admission-proof!)) (slurp (io/file (deref (var fixture/project-root)) "bin/test"))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} launcher-checkout-carries-new-cache-dependencies
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "launcher-dependencies-" (random-uuid))) .mkdirs)]
    (try
      (let [origin ((deref (var fixture/launcher-checkout!)) (io/file root "origin"))
            cache-file (io/file origin "src/seon/test/cache.clj")
            dependency (io/file origin "src/seon/test/fixture_dependency.clj")
            log (io/file root "require.log")]
        (spit dependency "(ns seon.test.fixture-dependency)\n(def loaded :new-dependency-loaded)\n")
        (spit cache-file
              (str (slurp cache-file)
                   "\n(require '[seon.test.fixture-dependency])\n"))
        (let [checkout ((deref (var fixture/launcher-checkout!)) (io/file root "consumer") origin)
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
            (finally ((deref (var fixture/stop-process-tree!)) child)))))
      (finally (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} orphaned-gates-are-announced-by-wait-and-preamble
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "orphaned-gates-" (random-uuid))) .mkdirs)
        checkout ((deref (var fixture/launcher-checkout!)) root)
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
                (finally ((deref (var fixture/stop-process-tree!)) child)))))
          (finally ((deref (var fixture/stop-process-tree!)) launcher))))
      (finally
        (when-let [handle @orphan]
          (.destroyForcibly ^ProcessHandle handle)
          (.get (.onExit ^ProcessHandle handle) test-support/event-backstop-seconds TimeUnit/SECONDS))
        (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} interrupted-launcher-awaits-its-runner-before-retaining-the-root
  (let [fixture-root
        (io/file (deref (var fixture/project-root)) "tmp" "test-runner-interrupt"
                 (str (random-uuid)))
        checkout ((deref (var fixture/launcher-checkout!)) fixture-root)
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        reaped-file (io/file fixture-root "child-reaped.txt")
        run-parent (io/file fixture-root "runs")
        cache-path (io/file fixture-root "cache" (deref (var fixture/fake-cache-digest)))
        fake-runner
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         (deref (var fixture/fake-dev-cache-prologue))
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
      ((deref (var fixture/install-single-worker-getconf!)) fake-bin)
      (spit fake-clojure fake-runner)
      (is (.setExecutable fake-clojure true false))
      (let [builder
            (doto
             (ProcessBuilder.
              ^java.util.List
              [(str (io/file checkout "bin" "test"))
               "--paths" "bin/test" "--" "seon.test-runner-test"])
              ((deref (var fixture/as-orchestrator)))
              (.directory checkout)
              (.redirectErrorStream true))
            _ (.put (.environment builder)
                    "SEON_TEST_RUN_PARENT" (.getCanonicalPath run-parent))
            _ (.put (.environment builder)
                    "SEON_FAKE_REAPED" (.getCanonicalPath reaped-file))
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_DIGEST" (deref (var fixture/fake-cache-digest)))
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

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} worker-exit-backstop-names-and-fails-a-stuck-child
  (let [fixture-root
        (io/file (deref (var fixture/project-root)) "tmp" "test-runner-stuck-child"
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

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} worker-root-cleanup-awaits-recorded-child-completion
  (let [fixture-root
        (io/file (deref (var fixture/project-root)) "tmp" "test-runner-child-completion"
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
        (is (= "child-ready" ((deref (var fixture/await-created!)) watcher "child-ready")))
        (let [cleanup-count (atom 0)
              cleanup
              (future
                (#'runner/stop-owned-process-tree!
                 (#'runner/process-tree-ownership launched))
                (.waitFor launched)
                (swap! cleanup-count inc)
                (test-support/delete-recursively! worker-root))]
          (is (= "child-stopping"
                 ((deref (var fixture/await-created!)) watcher "child-stopping")))
          (is (zero? @cleanup-count)
              "cleanup cannot run while a recorded child still owns the root")
          (is (.isDirectory worker-root))
          (with-open [release (io/writer release-fifo)]
            (.write release "x")
            (.flush release))
          (is (= "child-complete.txt"
                 ((deref (var fixture/await-created!)) watcher "child-complete.txt")))
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

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} stale-dependency-cache-is-refused-or-selected-and-recorded
  (let [fixture-root
        (io/file (deref (var fixture/project-root)) "tmp" "test-runner-worker-cache"
                 (str (random-uuid)))
        checkout ((deref (var fixture/launcher-checkout!)) fixture-root)
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        run-parent (io/file fixture-root "runs")
        cache-path (io/file fixture-root "cache" (deref (var fixture/fake-cache-digest)))
        cache-call (io/file fixture-root "cache-call.txt")
        transcript (io/file fixture-root "test-run.txt")
        fake-runner
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         (deref (var fixture/fake-dev-cache-prologue))
         "prepare=false\n"
         "for argument in \"$@\"; do\n"
         "  if [ \"$argument\" = \"--prepare-base\" ]; then prepare=true; fi\n"
         "done\n"
         "if [ \"$prepare\" = true ]; then mkdir -p \"${!#}/data/store\"; echo '{}' > \"${!#}/manifest.edn\"; exit 0; fi\n"
         "test \"$1\" = -Scp\n"
         "test \"$2\" = /fixture-classpath\n"
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
      ((deref (var fixture/install-single-worker-getconf!)) fake-bin)
      (spit fake-clojure fake-runner)
      (is (.setExecutable fake-clojure true false))
      (let [mismatched-digest (apply str (repeat 64 "b"))
            builder
            (doto
             (ProcessBuilder.
              ^java.util.List
              [(str (io/file checkout "bin" "test"))
               "--paths" "bin/test" "--" "seon.test-runner-test"])
              ((deref (var fixture/as-orchestrator)))
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
              ((deref (var fixture/as-orchestrator)))
              (.directory checkout)
              (.redirectErrorStream true))
            _ (.put (.environment builder)
                    "SEON_TEST_RUN_PARENT" (.getCanonicalPath run-parent))
            _ (.put (.environment builder)
                    "SEON_FAKE_CACHE_DIGEST" (deref (var fixture/fake-cache-digest)))
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
                           (str "dev-cache-digest=" (deref (var fixture/fake-cache-digest)))))
        (is (= 1 (count (.listFiles run-parent)))
            "only the earlier cache-refusal snapshot remains"))
      (finally
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} a-preparation-phase-that-outruns-its-declared-bound-fails-loudly
  (testing "The preparation phases had no bound: on 2026-09-17 every gate
            refused inside `dependency-cache-and-classpath` for about an hour
            while four invocations sat at `phase=snapshot`, and the wedge read
            as an ordinary slot queue. A phase that outruns its declared bound
            now fails the gate with one line naming the phase, the elapsed
            seconds, the bound, and where that phase's own output is."
    (let [fixture-root
          (io/file (deref (var fixture/project-root)) "tmp" "test-runner-phase-bound"
                   (str (random-uuid)))
          checkout ((deref (var fixture/launcher-checkout!)) fixture-root)
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
        ((deref (var fixture/install-single-worker-getconf!)) fake-bin)
        (spit fake-clojure wedged-runner)
        (is (.setExecutable fake-clojure true false))
        (let [builder
              (doto
               (ProcessBuilder.
                ^java.util.List
                [(str (io/file checkout "bin" "test"))
                 "--paths" "bin/test" "--" "seon.test-runner-test"])
                ((deref (var fixture/as-orchestrator)))
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
              ((deref (var fixture/stop-process-tree!)) launched)))
          (when (.exists fixture-root)
            (test-support/delete-recursively! fixture-root)))))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} a-fresh-run-root-is-claimed-before-population-and-sweep
  (let [fixture-root
        (io/file (deref (var fixture/project-root)) "tmp" "test-runner-root-claim"
                 (str (random-uuid)))
        checkout ((deref (var fixture/launcher-checkout!)) fixture-root)
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        fake-cp (io/file fake-bin "cp")
        run-parent (io/file fixture-root "runs")
        cache-path (io/file fixture-root "cache" (deref (var fixture/fake-cache-digest)))
        claim-observation (io/file fixture-root "claim-observation.txt")
        process (atom nil)
        unclaimed-roots
        (mapv #(io/file run-parent (str "run.unclaimed-" %)) (range 4))
        fake-runner
        (str "#!/usr/bin/env bash\n"
             "set -euo pipefail\n"
             (deref (var fixture/fake-dev-cache-prologue))
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
      ((deref (var fixture/install-single-worker-getconf!)) fake-bin)
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
              ((deref (var fixture/as-orchestrator)))
              (.directory checkout)
              (.redirectErrorStream true))
            environment (.environment builder)
            _ (.put environment "SEON_TEST_RUN_PARENT"
                    (.getCanonicalPath run-parent))
            _ (.put environment "SEON_FAKE_CACHE_DIGEST" (deref (var fixture/fake-cache-digest)))
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
            _ (when-not completed? ((deref (var fixture/stop-process-tree!)) launched))
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
          ((deref (var fixture/stop-process-tree!)) launched))
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest ^{:seon.test/long-ms 1800000, :seon.test/fixture-observation "Independent launcher processes require separate persistent result stores and locks to record both gate tallies.", :seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection."} concurrent-bin-test-invocations-both-reach-their-tallies
  (let [deadline (+ (System/nanoTime)
                    (* 1000000 (:seon.test/long-ms
                                 (meta #'concurrent-bin-test-invocations-both-reach-their-tallies))))
        remaining-ms #(max 0 (quot (- deadline (System/nanoTime)) 1000000))
        fixture-root
        (io/file (deref (var fixture/project-root)) "tmp" "test-runner-concurrent-gates"
                 (str (random-uuid)))
        fake-bin (io/file fixture-root "bin")
        run-parent (io/file fixture-root "runs")
        old-at (- (System/currentTimeMillis) (* 2 24 60 60 1000))
        processes (atom [])
        result-roots (mapv #(io/file fixture-root (str "result-" %)) (range 2))]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      ((deref (var fixture/install-single-worker-getconf!)) fake-bin)
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
                      [(str (io/file (deref (var fixture/project-root)) "bin" "test"))
                       "--result-cluster" "evidence"
                       "--result-root" (str result-root) "seon.fs-test"])
                      ((deref (var fixture/as-orchestrator)))
                      (.directory (deref (var fixture/project-root)))
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
                               (remaining-ms)
                               TimeUnit/MILLISECONDS))
                  launched)
            completed
            (mapv #(deref %
                          (remaining-ms)
                          false)
                  completions)]
        (doseq [[process complete?] (map vector launched completed)]
          (when-not complete?
            ((deref (var fixture/stop-process-tree!)) process)))
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
        (run! (deref (var fixture/stop-process-tree!)) @processes)
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} a-task-that-changes-worker-global-state-is-named-as-the-leaker
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
    (let [worker ((deref (var fixture/start-injected-worker!)) "write-failure-attributed"
                                         "import sys; sys.exit(23)\n")
          journal (::runner/worker-journal
                   (assoc worker ::runner/worker-journal
                          (atom [{::runner/task-symbols ['seon.leaker/strips]
                                  ::runner/task-ambient-drift
                                  {::runner/snapshot-instrumented
                                   {::runner/drift-removed ["seon.db/pull"]}}}])))
          worker (assoc worker ::runner/worker-journal journal)]
      (try
        (.get (.onExit (.toHandle ^Process (::runner/worker-process worker)))
              test-support/event-backstop-seconds TimeUnit/SECONDS)
        (let [result ((deref (var fixture/execute-injected-task!))
                      worker ((deref (var fixture/exchange-task)) "write-failure-attributed"))]
          (is (= "write-failure-attributed" (::runner/executed-by result))
              "the red names the worker that produced it")
          (is (= [['seon.leaker/strips]]
                 (mapv ::runner/task-symbols
                       (::runner/prior-ambient-drift result)))
              "and it carries its suspects, so the verdict names a leaker
               rather than only a victim"))
        (finally
          ((deref (var fixture/stop-injected-worker!)) worker))))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} selected-paths-overlay-head-for-preparation-and-every-worker
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "runner-paths-" (random-uuid))) .mkdirs)
        script (io/file root "probe.sh")
        log (io/file root "output.txt")
        child (atom nil)]
    (try
      (let [[status output]
            ((deref (var fixture/fixture-command!))
             root ["bash" "-c" (str "set -euo pipefail\n"
             "origin=$1\n"
             "fixture=$2\n"
             "mkdir -p \"$fixture/bin\" \"$fixture/src/seon\" \"$fixture/test\" \"$fixture/.agents/skills\" \"$fixture/.claude\" \"$fixture/.clj-kondo\" \"$fixture/tmp/fake-bin\"\n"
             "cd \"$fixture\"\n"
             "cp -R \"$origin/bin/.\" bin/\n"
             (deref (var fixture/launcher-source-copy))
             "printf '{:paths [\"src\" \"script\"]}\\n' > bb.edn\n"
             "printf 'tmp/\\ntarget/\\n' > .gitignore\n"
             "printf 'base\\n' > src/owned.txt\n"
             "printf 'base\\n' > src/deleted.txt\n"
             "printf '(ns dirty-tracked-test)\\n' > test/dirty_tracked_test.clj\n"
             "touch test/fixture_test.clj .agents/skills/fixture .clj-kondo/fixture\n"
             "ln -s .agents/skills seon-skills\n"
             "ln -s ../.agents/skills .claude/skills\n"
             "ln -s \"$origin/reference-code\" reference-code\n"
             "git init -q\n"
             "git add -- bin src script test bb.edn .gitignore .agents .claude .clj-kondo seon-skills reference-code\n"
             "git -c user.name=\"$(git -C \"$origin\" config user.name)\" -c user.email=\"$(git -C \"$origin\" config user.email)\" commit -qm baseline\n")
                   "fixture-setup" (.getPath (deref (var fixture/project-root)))
                   (.getPath (io/file root "checkout"))])]
        (is (zero? status) output))
      ((deref (var fixture/publish-fixture-head!)) (io/file root "checkout"))
      (spit script (str "set -euo pipefail\norigin=$1\nfixture=$2\ncd \"$fixture\"\n"
             "printf 'owned\\n' > src/owned.txt\n"
             "printf 'added\\n' > src/added.txt\n"
             "rm src/deleted.txt\n"
             "cat > tmp/fake-bin/clojure <<'SH'\n"
             "#!/usr/bin/env bash\n"
             "set -euo pipefail\n"
             "if [ \"${SEON_EXPECT_BARE:-0}\" = 1 ]; then\n"
             "  test \"$(cat src/owned.txt)\" = base\n"
             "  test \"$(tail -n 1 test/dirty_tracked_test.clj)\" = '(ns dirty-tracked-test)'\n"
             "  test ! -e src/added.txt\n"
             "  test \"$(cat src/deleted.txt)\" = base\n"
             "else\n"
             "  test \"$(cat src/owned.txt)\" = owned\n"
             "  test \"$(tail -n 1 test/dirty_tracked_test.clj)\" = '(ns dirty-tracked-test)'\n"
             "  test \"$(cat src/added.txt)\" = added\n"
             "  test ! -e src/deleted.txt\n"
             "fi\n"
             (deref (var fixture/fake-dev-cache-prologue))
             "for argument in \"$@\"; do\n"
             "  if [ \"$argument\" = --prepare-base ]; then mkdir -p \"${!#}/data/store\"; echo '{}' > \"${!#}/manifest.edn\"; exit 0; fi\n"
             "done\n"
             "for worker in workers/*; do\n"
             "  if [ \"${SEON_EXPECT_BARE:-0}\" = 1 ]; then\n"
             "    test \"$(cat \"$worker/src/owned.txt\")\" = base\n"
             "    test \"$(tail -n 1 \"$worker/test/dirty_tracked_test.clj\")\" = '(ns dirty-tracked-test)'\n"
             "    test ! -e \"$worker/src/added.txt\"\n"
             "    test \"$(cat \"$worker/src/deleted.txt\")\" = base\n"
             "  else\n"
             "    test \"$(cat \"$worker/src/owned.txt\")\" = owned\n"
             "    test \"$(tail -n 1 \"$worker/test/dirty_tracked_test.clj\")\" = '(ns dirty-tracked-test)'\n"
             "    test \"$(cat \"$worker/src/added.txt\")\" = added\n"
             "    test ! -e \"$worker/src/deleted.txt\"\n"
             "  fi\n"
             "done\n"
             "echo SNAPSHOT_VERIFIED\n"
             "SH\n"
             "printf '#!/usr/bin/env bash\\necho 2\\n' > tmp/fake-bin/getconf\n"
             "cat > tmp/fake-bin/tar <<'SH'\n"
             "#!/usr/bin/env bash\nset -euo pipefail\n"
             "\"$SEON_REAL_TAR\" \"$@\"\n"
             "if [ \"${SEON_CONTAMINATE_SNAPSHOT:-0}\" = 1 ]; then\n"
             "  cp \"$SEON_CONTAMINATION_SOURCE\" \"${!#}/test/dirty_tracked_test.clj\"\n"
             "fi\n"
             "SH\n"
             "export SEON_REAL_TAR=$(command -v tar)\n"
             "chmod +x tmp/fake-bin/*\n"
             "export PATH=\"$fixture/tmp/fake-bin:$PATH\"\n"
             "export SEON_FAKE_CACHE_DIGEST=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
             "export SEON_FAKE_CACHE_PATH=\"$fixture/tmp/cache/$SEON_FAKE_CACHE_DIGEST\"\n"
             "mkdir -p \"$SEON_FAKE_CACHE_PATH\"\n"
             "if ! bin/test --paths src/owned.txt src/added.txt src/deleted.txt -- seon.fixture-test > tmp/selected.log 2>&1; then tail -100 tmp/selected.log; exit 81; fi\n"
             "grep -F 'src/owned.txt' tmp/selected.log\n"
             "grep -F 'src/added.txt' tmp/selected.log\n"
             "grep -F 'src/deleted.txt' tmp/selected.log\n"
             "echo SNAPSHOT_VERIFIED\n"
             "printf '(ns dirty-tracked-test)\\n;; dirty working tree\\n' > test/dirty_tracked_test.clj\n"
             "export SEON_EXPECT_BARE=1\n"
             "if ! bin/test seon.fixture-test > tmp/default.log 2>&1; then tail -100 tmp/default.log; exit 82; fi\n"
             "grep -F 'SNAPSHOT_VERIFIED' tmp/default.log\n"
             "if grep -F 'test/dirty_tracked_test.clj' tmp/default.log; then cat tmp/default.log; exit 1; fi\n"
             "echo DEFAULT_SNAPSHOT_VERIFIED\n"
             ;; Deliberately contaminate the run root after archive extraction.
             ;; The independent ls-tree proof must refuse before fake clojure
             ;; can observe the dirty, non-named tracked test file.
             "export SEON_CONTAMINATE_SNAPSHOT=1\n"
             "export SEON_CONTAMINATION_SOURCE=$fixture/test/dirty_tracked_test.clj\n"
             "if bin/test seon.fixture-test > tmp/contaminated.log 2>&1; then cat tmp/contaminated.log; exit 92; else test \"$?\" = 64; fi\n"
             "grep -F 'non-named tracked path differs from HEAD: test/dirty_tracked_test.clj' tmp/contaminated.log\n"
             "echo CONTAMINATION_REFUSAL_VERIFIED\n"))
      (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                 ["/bin/bash" (.getPath script)
                                  (.getPath (deref (var fixture/project-root))) (.getPath (io/file root "checkout"))])
                             ((deref (var fixture/as-orchestrator)))
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
            (is (str/includes? output "CONTAMINATION_REFUSAL_VERIFIED") output)
            (is (str/includes? output "src/owned.txt") output)
            (is (str/includes? output "src/added.txt") output)
            (is (str/includes? output "src/deleted.txt") output)
            (is (str/includes? output
                               "non-named tracked path differs from HEAD: test/dirty_tracked_test.clj")
                output))))
      (finally
        (when-let [process @child] ((deref (var fixture/stop-process-tree!)) process))
        (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} codex-lanes-refuse-cold-gates-before-acquiring-resources
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "lane-gate-" (random-uuid))) .mkdirs)
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
                                                  (.getPath (deref (var fixture/project-root)))
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
        (when-let [process @child] ((deref (var fixture/stop-process-tree!)) process))
        (test-support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Orchestrator integration: owns child processes; excluded from ordinary lane runner selection.", :seon.test/long-ms 1800000} fast-selected-paths-exclude-a-broken-foreign-file
  (let [root (doto (io/file (deref (var fixture/project-root)) "tmp" (str "fast-paths-" (random-uuid))) .mkdirs)
        script (io/file root "probe.sh")
        log (io/file root "output.txt")
        child (atom nil)]
    (try
      (let [[status output]
            ((deref (var fixture/fixture-command!))
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
                   "fixture-setup" (.getPath (deref (var fixture/project-root)))
                   (.getPath (io/file root "checkout"))])]
        (is (zero? status) output))
      ((deref (var fixture/publish-fixture-head!)) (io/file root "checkout"))
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
                                             (.getPath (deref (var fixture/project-root)))
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
        (when-let [process @child] ((deref (var fixture/stop-process-tree!)) process))
        (test-support/delete-recursively! root)))))
