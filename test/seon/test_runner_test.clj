(ns seon.test-runner-test
  "Declared latest-result facts owned by the JVM test runner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.env :as env]
            [seon.sci.eval :as eval]
            [seon.cluster.boot-test]
            [seon.test-runner-failure-fixture]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support])
  (:import [java.io PrintWriter]
           [java.util.concurrent CountDownLatch TimeUnit]))

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
    (is (str/includes?
         output
         "Unconfirmed tasks:\n - seon.example-test/unlaunchable"))
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
            (with-redefs-fn
              {#'runner/event-backstop-seconds (constantly 1)}
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
             :seon.test/run-at at}
            test-symbol (:seon.test/sym
                         (first (:seon.test.runner/results run-result)))]
        (is (seq (runner/commit-results! connection completion))
            "first recording installs the row")
        (db/transact!
         connection
         [[:db.fn/retractEntity [:seon.test/sym test-symbol]]])
        (let [recorded (runner/commit-results! connection completion)]
          (is (not (:seon.error/kind recorded))
              "recording after the retraction still commits")
          (is (some #(= test-symbol (:seon.test/sym %)) recorded)
              "the retracted row was recreated by the writer's decision"))))))

(deftest result-facts-live-on-the-test-row-and-reruns-replace-them
  (test-support/with-database
    (fn [connection]
      (let [run-result (captured-run)
            basis-t (db/basis-t @connection)
            completion
            {:seon.test.runner/results
             (:seon.test.runner/results run-result)
             :seon.test/run-basis-t basis-t
             :seon.test/run-at at}]
        (test-support/seed-cluster! connection "test")
        (let [committed (runner/commit-results! connection completion)]
          (is (= (:seon.test.runner/results run-result)
                 (mapv #(dissoc % :seon.test/run-basis-t
                                :seon.test/run-at)
                       committed))
              "the completion value is pulled from the committed test rows"))
        (db/transact!
         connection
         (agent/creation-tx
          {:seon.cluster.agent/id "fixture-owner"
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
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?agent :seon.cluster.agent/id ?agent-id]
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
               {:seon.cluster.run.form/source source
                :seon.cluster.run.form/ns [:seon.ns/name 'user]
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps
                (config/result-caps (config/defaults))
                :seon.sci.eval/time-limit-ms 5000
                :seon.config/on-core-error :panic}))
            _ (evaluate
               "(require '[clojure.test :refer [deftest is]])")
            _ (evaluate
               "(deftest agent-fork-example (is (= 4 (+ 2 2))))")
            result
            (:seon.sci.admit/value
             (evaluate "(seon.test/run #'agent-fork-example)"))
            stored (db/pull @connection
                            [:seon.test/sym
                             :seon.test/pass-count
                             :seon.test/fail-count
                             :seon.test/error-count
                             :seon.test/run-basis-t
                             :seon.test/run-at]
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
        process (atom nil)
        output-reader (atom nil)]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      (install-single-worker-getconf! fake-bin)
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

(deftest worker-checkouts-own-the-writable-clj-kondo-cache
  (let [fixture-root
        (io/file project-root "tmp" "test-runner-worker-cache"
                 (str (random-uuid)))
        fake-bin (io/file fixture-root "bin")
        fake-clojure (io/file fake-bin "clojure")
        run-parent (io/file fixture-root "runs")
        fake-runner
        (str
         "#!/usr/bin/env bash\n"
         "set -euo pipefail\n"
         "prepare=false\n"
         "for argument in \"$@\"; do\n"
         "  if [ \"$argument\" = \"--prepare-base\" ]; then prepare=true; fi\n"
         "done\n"
         "if [ \"$prepare\" = true ]; then exit 0; fi\n"
         "test -d workers/pool-1/.clj-kondo\n"
         "test ! -L workers/pool-1/.clj-kondo\n")]
    (try
      (.mkdirs fake-bin)
      (.mkdirs run-parent)
      (install-single-worker-getconf! fake-bin)
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
                    "PATH"
                    (str (.getCanonicalPath fake-bin)
                         java.io.File/pathSeparator
                         (System/getenv "PATH")))
            launched (.start builder)
            output (slurp (.getInputStream launched))]
        (is (zero? (.waitFor launched)) output)
        (is (empty? (vec (.listFiles run-parent)))
            "a successful structural probe leaves no retained run root"))
      (finally
        (when (.exists fixture-root)
          (test-support/delete-recursively! fixture-root))))))
