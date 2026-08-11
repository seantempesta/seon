(ns seon.test-runner-test
  "The opt-in JVM test-result fact sink."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.cluster.boot-test]
            [seon.test-runner-failure-fixture]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private at (java.util.Date. 1785283200000))
(def ^:private git-sha (apply str (repeat 40 "a")))
(def ^:private run-id "test-run-1")
(def ^:private project-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

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

(deftest boot-tests-have-no-namespace-wide-execution-shape
  (let [namespace-object (find-ns 'seon.cluster.boot-test)
        fixtures (meta namespace-object)]
    (is (empty? (::test/once-fixtures fixtures)))
    (is (seq (::test/each-fixtures fixtures)))
    (is (not (#'runner/atomic-namespace-task? namespace-object))
        "each boot test receives a private base clone and can be queued alone")))

(deftest captures-one-pass-or-fail-value-per-test
  (let [result (captured-run)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))]
    (is (= #:seon.test.runner{:test-count 4
                              :pass-count 1
                              :fail-count 1
                              :error-count 8}
           (:seon.test.runner/summary result)))
    (is (= :pass
           (:seon.test.result/outcome
            (by-symbol
             "seon.test-runner-failure-fixture/passing-example"))))
    (is (= :fail
           (:seon.test.result/outcome
            (by-symbol
             "seon.test-runner-failure-fixture/failing-example"))))
    (is (str/includes?
         (:seon.test.failure/message
          (by-symbol
           "seon.test-runner-failure-fixture/failing-example"))
         "deliberate broken-test evidence"))))

(deftest repeated-identical-errors-have-one-bounded-face
  (let [{::keys [result output]} (captured-run-with-output)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))
        repeated-message
        (:seon.test.failure/message
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
    (testing "one face and the captured fact are bounded and deduplicated"
      (is (<= (count repeated-face)
              (:seon.config.eval.result/blob-threshold
               (config/defaults))))
      (is (= 1 (occurrences repeated-message
                            "the same refusal reached the reporter again"))))))

(deftest result-facts-join-through-test-namespace-to-its-owner
  (test-support/with-database
    (fn [connection]
      (let [run-result (captured-run)]
        (test-support/seed-cluster! connection "test")
        (db/transact! connection (runner/record-tx run-result))
        (db/transact!
         connection
         (agent/creation-tx
          {:seon.cluster.agent/id "fixture-owner"
           :seon.cluster/name "test"
           :seon.ns/name 'seon.test-runner-failure-fixture}))
        (is
         (= #{["seon.test-runner-failure-fixture/failing-example"
                "fixture-owner"
                "the deliberate broken-test evidence\nexpected: (= 5 (+ 2 2))\nactual: (not (= 5 4))"
                at
                git-sha]}
            (db/q
             '[:find ?test-symbol ?agent-id ?message ?at ?git-sha
               :in $ ?selected-test
               :where
               [?result :seon.test.result/outcome :fail]
               [?result :seon.test.result/test ?test]
               [?test :seon.test/sym ?test-symbol]
               [(= ?test-symbol ?selected-test)]
               [?test :seon.test/ns ?namespace]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?agent :seon.cluster.agent/id ?agent-id]
               [?result :seon.test.result/failure ?failure]
               [?failure :seon.test.failure/message ?message]
               [?result :seon.test.result/run ?run]
               [?run :seon.test.run/at ?at]
               [?run :seon.test.run/git-sha ?git-sha]]
             @connection
             "seon.test-runner-failure-fixture/failing-example")))))))

(deftest the-effectful-sink-refuses-the-default-cluster
  (let [refusal
        (test-support/refusal-data
         #(runner/record!
           {:seon.test.runner/run-result (captured-run)
            :seon.boot/cluster-name "default"
            :seon.boot/root "tmp/test-result-default-refusal"}))]
    (is (= :seon.test.runner/default-cluster-refused
           (:seon.error/kind refusal)))))

(deftest liveness-dump-includes-virtual-threads
  (let [release (CountDownLatch. 1)
        path (volatile! nil)
        thread
        (-> (Thread/ofVirtual)
            (.name "seon-test-runner-virtual-thread-proof")
            (.start
             (reify Runnable
               (run [_]
                 (.await release)))))]
    (try
      (let [dump-path (#'runner/persist-virtual-thread-dump!)
            _ (vreset! path dump-path)
            dump (slurp dump-path)]
        (is (str/includes? dump
                           "seon-test-runner-virtual-thread-proof"))
        (is (str/includes? dump "\"virtual\": true")
            "the retained diagnostic is not the platform-only MXBean view"))
      (finally
        (.countDown release)
        (.join thread)
        (when-let [dump-path @path]
          (io/delete-file dump-path true))))))

(deftest interrupted-launcher-awaits-its-runner-before-retaining-the-root
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-interrupt"
                 (str (random-uuid)))
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        reaped-file (io/file fixture-root "child-reaped.txt")
        run-parent (io/file fixture-root "runs")
        fake-runner
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
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
        process (atom nil)]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      (spit fake-clojure fake-runner)
      (is (.setExecutable fake-clojure true false))
      (let [builder
            (doto
             (ProcessBuilder.
              ^java.util.List
              [(str (io/file project-root "bin" "test"))
               "seon.test-runner-test"])
              (.directory project-root)
              (.redirectErrorStream true))
            _ (.put (.environment builder)
                    "SEON_TEST_RUN_PARENT" (.getCanonicalPath run-parent))
            _ (.put (.environment builder)
                    "SEON_FAKE_REAPED" (.getCanonicalPath reaped-file))
            _ (.put (.environment builder)
                    "PATH"
                    (str (.getCanonicalPath fake-bin)
                         java.io.File/pathSeparator
                         (System/getenv "PATH")))
            launched (.start builder)
            _ (reset! process launched)
            ready-prefix "FAKE_RUNNER_READY "
            ready (promise)
            output
            (future
              (with-open [reader (io/reader (.getInputStream launched))]
                (loop [lines []]
                  (if-let [line (.readLine ^java.io.BufferedReader reader)]
                    (do
                      (when (str/starts-with? line ready-prefix)
                        (deliver ready line))
                      (recur (conj lines line)))
                    lines))))
            ready-line (deref ready 20000 ::readiness-backstop)
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
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))
