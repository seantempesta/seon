(ns seon.dev.changed-test-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.dev.config :as config]
            [seon.dev.changed-test :as changed]
            [seon.dev.state :as state]))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 10) (recur))
        :else false))))

(defn- pid-handle [pid]
  (.orElse (java.lang.ProcessHandle/of pid) nil))

(defn- pid-alive? [pid]
  (boolean (some-> (pid-handle pid) .isAlive)))

(defn- force-pid! [pid]
  (when-let [handle (pid-handle pid)]
    (when (.isAlive handle) (.destroyForcibly handle))))

(defn- edn-lines [path]
  (when (fs/regular-file? path)
    (mapv edn/read-string (fs/read-all-lines path))))

(def manifest
  {:seon.dev.test.artifact/test-namespaces
   ['example.alpha-test 'example.beta-test 'unrelated-test]
   :seon.dev.test.artifact/resources
   [{:seon.dev.test.resource/path "src/example/alpha.cljs"
     :seon.dev.test.resource/namespace 'example.alpha
     :seon.dev.test.resource/cache-key ["alpha"]
     :seon.dev.test.resource/requires []}
    {:seon.dev.test.resource/path "src/example/beta.cljs"
     :seon.dev.test.resource/namespace 'example.beta
     :seon.dev.test.resource/cache-key ["beta"]
     :seon.dev.test.resource/requires ['example.alpha]}
    {:seon.dev.test.resource/path "test/example/alpha_test.cljs"
     :seon.dev.test.resource/namespace 'example.alpha-test
     :seon.dev.test.resource/cache-key ["alpha-test"]
     :seon.dev.test.resource/requires ['example.alpha]}
    {:seon.dev.test.resource/path "test/example/beta_test.cljs"
     :seon.dev.test.resource/namespace 'example.beta-test
     :seon.dev.test.resource/cache-key ["beta-test"]
     :seon.dev.test.resource/requires ['example.beta]}
    {:seon.dev.test.resource/path "test/unrelated_test.cljs"
     :seon.dev.test.resource/namespace 'unrelated-test
     :seon.dev.test.resource/cache-key ["unrelated"]
     :seon.dev.test.resource/requires []}]})

(deftest impact-follows-shadow-dependencies-reverse-transitively
  (is (= ['example.alpha-test 'example.beta-test]
         (:seon.dev.changed-test/test-namespaces
           (changed/impact manifest ["src/example/alpha.cljs"])))))

(deftest test-edit-selects-that-test
  (is (= ['example.beta-test]
         (:seon.dev.changed-test/test-namespaces
           (changed/impact manifest ["test/example/beta_test.cljs"])))))

(deftest unknown-cljs-path-widens-explicitly
  (let [result (changed/impact manifest ["src/example/new.cljs"])]
    (is (true? (:seon.dev.changed-test/full? result)))
    (is (= (set (:seon.dev.test.artifact/test-namespaces manifest))
           (set (:seon.dev.changed-test/test-namespaces result))))
    (is (= :unknown-cljs-resource
           (get-in result [:seon.dev.changed-test/widening 0
                           :seon.dev.changed-test/reason])))))

(deftest broad-input-runs-the-unfiltered-shadow-artifact
  (let [result (changed/impact manifest ["deps.edn"])]
    (is (true? (:seon.dev.changed-test/full? result))
        "a broad input must run Shadow's complete test data, including required probe namespaces")
    (is (= (set (:seon.dev.test.artifact/test-namespaces manifest))
           (set (:seon.dev.changed-test/test-namespaces result))))))

(deftest bun-test-environment-matches-the-canonical-runner
  (is (= {"SEON_CONFIG" "config/test.edn"
          "SEON_RENDER_STRICT" "1"}
         (changed/test-process-environment {})))
  (is (= {"SEON_CONFIG" "config/custom.edn"
          "SEON_RENDER_STRICT" "0"}
         (changed/test-process-environment
           {:seon.dev.config/environment
            {"SEON_CONFIG" "config/custom.edn"
             "SEON_RENDER_STRICT" "0"}}))
      "an explicit caller selection still wins, as it does in bin/test-cljs"))

(deftest full-javascript-command-does-not-filter-shadow-test-data
  (let [artifact {:seon.dev.test.artifact/path "out/test/artifact/test.js"}]
    (is (= ["/exact/bun" "root/out/test/artifact/test.js"]
           (changed/javascript-argv "/exact/bun" "root" artifact :all)))
    (is (= ["/exact/bun" "root/out/test/artifact/test.js"
            "--test=example.alpha-test"]
           (changed/javascript-argv
            "/exact/bun" "root" artifact ['example.alpha-test])))))

(deftest shared-cljc-input-uses-the-shadow-graph-when-known
  (let [shared (assoc-in manifest
                         [:seon.dev.test.artifact/resources 0
                          :seon.dev.test.resource/path]
                         "src/example/alpha.cljc")
        result (changed/impact shared ["src/example/alpha.cljc"])]
    (is (= #{'example.alpha-test 'example.beta-test}
           (set (:seon.dev.changed-test/test-namespaces result))))
    (is (empty? (:seon.dev.changed-test/widening result)))))

(deftest clj-macro-change-seeds-the-existing-shadow-graph
  (let [with-macro (update-in manifest
                              [:seon.dev.test.artifact/resources 1
                               :seon.dev.test.resource/requires]
                              conj 'example.macro)
        host-selection
        {:seon.dev.changed-test/host-namespaces #{'example.macro}
         :seon.dev.changed-test/host-graph
         {:seon.dev.changed-test/path->namespace
          {"src/example/macro.clj" 'example.macro}}}
        plan (changed/shadow-plan with-macro host-selection
                                  ["src/example/macro.clj"])
        result (changed/impact with-macro
                               ["src/example/macro.clj"]
                               (:seon.dev.changed-test/shadow-seeds plan))]
    (is (= ["src/example/macro.clj"]
           (:seon.dev.changed-test/shadow-paths plan)))
    (is (= #{'example.macro}
           (:seon.dev.changed-test/shadow-seeds plan)))
    (is (= ['example.beta-test]
           (:seon.dev.changed-test/test-namespaces result)))))

(deftest missing-shadow-graph-treats-source-clj-as-a-possible-macro
  (is (true? (changed/potential-shadow-input? "src/example/macros.clj")))
  (is (false? (changed/potential-shadow-input? "test/example/tool_test.clj"))))

(deftest maintained-reference-sources-are-not-root-runtime-inputs
  (is (false?
        (changed/root-runtime-path?
          "reference-code/datahike/src/datahike/query.cljc")))
  (is (true? (changed/root-runtime-path? "src/seon/db.cljs")))
  (is (true? (changed/root-runtime-path? "test/seon/db_test.cljs"))))

(deftest host-impact-follows-each-retained-runner-graph
  (let [host-result
        {:seon.dev.changed-test/host-status :available
         :seon.dev.changed-test/host-graph
         {:seon.dev.changed-test/path->namespace
          {"src/shared.cljc" 'example.shared
           "script/seon/dev/tool.clj" 'example.tool}
          :seon.dev.changed-test/requires
          {'example.writer-test #{'example.shared}
           'example.tool-test #{'example.tool}}
          :seon.dev.changed-test/operator-tests #{'example.tool-test}
          :seon.dev.changed-test/writer-tests #{'example.writer-test}}}]
    (is (= ['example.writer-test]
           (:seon.dev.changed-test/writer-tests
            (changed/host-impact host-result ["src/shared.cljc"]))))
    (is (= ['example.tool-test]
           (:seon.dev.changed-test/operator-tests
            (changed/host-impact host-result
                                 ["script/seon/dev/tool.clj"]))))))

(deftest missing-host-analysis-widens-only-relevant-runners
  (let [unavailable {:seon.dev.changed-test/host-status :unavailable
                     :seon.dev.changed-test/reason "missing"}]
    (is (= :all
           (:seon.dev.changed-test/operator-tests
            (changed/host-impact unavailable
                                 ["script/seon/dev/tool.clj"]))))
    (is (= :all
           (:seon.dev.changed-test/writer-tests
            (changed/host-impact unavailable
                                 ["src/seon/db/writer.clj"]))))
    (is (empty?
         (:seon.dev.changed-test/writer-tests
          (changed/host-impact unavailable
                               ["script/seon/dev/tool.clj"]))))))

(deftest failure-feedback-keeps-actionable-values-and-bounds-the-index
  (let [failure (fn [n]
                  (str "FAIL in (example-" n ") (example_test.cljs:10)\n"
                       "expected: (= " n " 1)\n"
                       "  actual: (not (= " n " 1))\n\n"))
        excerpts (changed/failure-excerpts
                   (str (failure 1) (failure 2) (failure 3)))]
    (is (= 2 (count excerpts)))
    (is (every? #(and (re-find #"expected:" %)
                      (re-find #"actual:" %))
                excerpts))))

(deftest termination-rescans-children-spawned-during-grace
  (let [root (System/getProperty "user.dir")
        directory (fs/path root "tmp" (str "changed-tree-" (random-uuid)))
        ready (fs/path directory "ready.pid")
        spawned (fs/path directory "spawned.pid")
        command
        (str "trap 'sleep 30 & echo $! > " spawned "' TERM; "
             "echo $$ > " ready "; while true; do sleep 1; done")
        _ (fs/create-dirs directory)
        process (.start (ProcessBuilder. ^java.util.List
                                         ["bash" "-c" command]))]
    (try
      (is (wait-until #(fs/regular-file? ready) 3000))
      (with-redefs [changed/termination-wait-ms 200]
        (is (true? (#'changed/terminate! process))))
      (is (wait-until #(fs/regular-file? spawned) 1000)
          "the TERM trap spawned a child after the first tree capture")
      (let [spawned-pid (parse-long (first (fs/read-all-lines spawned)))]
        (is (false? (pid-alive? spawned-pid))
            "the forced escalation included the newly spawned child"))
      (finally
        (.destroyForcibly process)
        (when (fs/regular-file? spawned)
          (force-pid! (parse-long (first (fs/read-all-lines spawned)))))
        (fs/delete-tree directory)))))

(deftest killing-changed-test-owner-unwinds-its-complete-process-tree
  (let [root (System/getProperty "user.dir")
        directory (fs/path root "tmp" (str "changed-owner-" (random-uuid)))
        child-file (fs/path directory "child.pid")
        grandchild-file (fs/path directory "grandchild.pid")
        owner-log (fs/path directory "owner.log")
        child-command
        (str "echo $$ > " child-file "; "
             "bash -c 'echo $$ > " grandchild-file "; sleep 30' & wait")
        expression
        (str "(do (require 'seon.dev.changed-test) "
             "(let [run (deref (ns-resolve 'seon.dev.changed-test "
             "'run-command!))] (run " (pr-str root) " :pod "
             (pr-str ["bash" "-c" child-command]) " {})))")
        _ (fs/create-dirs directory)
        owner
        (.start
          (doto
            (ProcessBuilder.
              ^java.util.List
              ["bb" "--config" (str (fs/path root "bb.edn"))
               "--deps-root" root "-e" expression])
            (.redirectErrorStream true)
            (.redirectOutput (.toFile owner-log))))]
    (try
      (let [ready? (wait-until #(and (fs/regular-file? child-file)
                                     (fs/regular-file? grandchild-file))
                               5000)]
        (is ready? (when (fs/regular-file? owner-log)
                     (slurp (str owner-log))))
        (when ready?
          (let [child-pid (parse-long (first (fs/read-all-lines child-file)))
                grandchild-pid
                (parse-long (first (fs/read-all-lines grandchild-file)))]
            (is (pid-alive? child-pid))
            (is (pid-alive? grandchild-pid))
            (.destroy owner)
            (is (.waitFor owner 10 java.util.concurrent.TimeUnit/SECONDS))
            (is (wait-until #(and (not (pid-alive? child-pid))
                                  (not (pid-alive? grandchild-pid)))
                            5000)
                "the owner's shutdown hook awaited every known descendant")
            (force-pid! child-pid)
            (force-pid! grandchild-pid))))
      (finally
        (.destroyForcibly owner)
        (when (fs/regular-file? child-file)
          (force-pid! (parse-long (first (fs/read-all-lines child-file)))))
        (when (fs/regular-file? grandchild-file)
          (force-pid!
            (parse-long (first (fs/read-all-lines grandchild-file)))))
        (fs/delete-tree directory)))))

(deftest hook-burst-retains-one-worker-and-one-coalesced-generation
  (let [root (System/getProperty "user.dir")
        directory (fs/path root "tmp" (str "changed-hook-" (random-uuid)))
        process-dir (fs/path directory "operator")
        worker-starts (fs/path directory "worker-starts.edn")
        running (fs/path directory "running")
        release (fs/path directory "release")
        completed (fs/path directory "completed.edn")
        latest (fs/path directory "latest.report.edn")
        hook-config (fs/path directory "hook.edn")
        configuration
        (assoc (config/load! root)
               :seon.dev.config/process-dir (str process-dir))
        worker-expression
        (str
         "(require '[babashka.fs :as fs] '[seon.dev.changed-test :as changed] "
         "'[seon.dev.config :as config] '[seon.dev.state :as state]) "
         "(let [configuration (assoc (config/load! " (pr-str root) ") "
         ":seon.dev.config/process-dir " (pr-str (str process-dir)) ") "
         "execute! (fn [request] "
         "(when-not (fs/exists? " (pr-str (str running)) ") "
         "(spit " (pr-str (str running)) " \"ready\\n\") "
         "(loop [] (when-not (fs/exists? " (pr-str (str release)) ") "
         "(Thread/sleep 10) (recur)))) "
         "(spit " (pr-str (str completed))
         " (str (pr-str request) \"\\n\") :append true) "
         "(state/write-edn! " (pr-str (str latest)) " request))] "
         "(spit " (pr-str (str worker-starts)) " \"{:worker/start true}\\n\" "
         ":append true) (changed/run-hook-worker! configuration execute!))")
        worker-command
        ["bb" "--config" (str (fs/path root "bb.edn"))
         "--deps-root" root "-e" worker-expression]
        burst-paths
        (mapv #(str "test/seon/dev/generated_hook_burst_" % ".clj")
              (range 16))]
    (fs/create-dirs directory)
    (spit (str hook-config)
          "{:seon.config/on-core-error :log\n :lint {:enabled false}\n :markdown-lint {:enabled false}\n :docstring-lint {:enabled false}\n :changed-tests {:enabled true}}\n")
    (try
      (changed/enqueue-hook! configuration
                             ["test/seon/dev/changed_test_test.clj"]
                             worker-command)
      (is (wait-until #(fs/regular-file? running) 5000)
          "the one worker claims the initial generation")
      (is (= ["test/seon/dev/changed_test_test.clj"]
             (:seon.dev.changed-test/paths
              (edn/read-string
               (slurp
                (str (fs/path process-dir "changed-test-hook"
                              "running.edn"))))))
          "the claimed generation remains recoverable across worker death")
      (let [environment
            (assoc (into {} (System/getenv))
                   "SEON_PROC_DIR" (str process-dir)
                   "SEON_HOOK_CONFIG" (str hook-config))
            publishers
            (mapv
             (fn [path]
               (process/process
                {:cmd [(str (fs/path root "bin/seon-hook"))]
                 :dir root
                 :env environment
                 :in (json/generate-string
                      {:hook_event_name "PostToolUse"
                       :tool_name "Edit"
                       :tool_input {:file_path path}})
                 :out :string
                 :err :string}))
             burst-paths)
            results (mapv deref publishers)
            pending-path
            (fs/path process-dir "changed-test-hook" "pending.edn")
            worker-path
            (fs/path process-dir "changed-test-hook" "worker.edn")
            pending (edn/read-string (slurp (str pending-path)))
            worker (edn/read-string (slurp (str worker-path)))]
        (is (every? #(zero? (:exit %)) results))
        (is (every? #(re-find #"Changed tests queued as generation"
                              (str (:out %)))
                    results)
            "every publisher exits promptly with advisory queue evidence")
        (is (= (set burst-paths)
               (set (:seon.dev.changed-test/paths pending)))
            "all newer paths occupy one pending generation")
        (is (state/process-identity-alive? worker)
            "only the published worker identity remains long-lived")
        (is (pos? (:seon.dev.process/pid worker)))
        (when (fs/which "ps")
          (let [rss (-> (process/sh
                         {:cmd ["ps" "-o" "rss=" "-p"
                                (str (:seon.dev.process/pid worker))]})
                        :out str str/trim parse-long)]
            (is (and (pos? rss) (< rss (* 256 1024)))
                "one transient worker keeps the retained RSS below 256 MiB")))
        (is (= 1 (count (edn-lines worker-starts)))
            "the process burst starts no duplicate worker"))
      (spit (str release) "release\n")
      (is (wait-until
           #(not (fs/regular-file?
                  (fs/path process-dir "changed-test-hook" "worker.edn")))
           10000)
          "the worker exits after draining its one pending generation")
      (is (not (fs/regular-file?
                (fs/path process-dir "changed-test-hook" "running.edn")))
          "a completed generation releases its crash-recovery claim")
      (let [runs (edn-lines completed)
            report (edn/read-string (slurp (str latest)))]
        (is (= 2 (count runs))
            "one running generation is followed by one coalesced generation")
        (is (= (set burst-paths)
               (set (:seon.dev.changed-test/paths (second runs)))))
        (is (= (second runs) report)
            "the final report covers the latest generation's complete union"))
      (finally
        (when-let [worker
                   (some-> (fs/path process-dir "changed-test-hook" "worker.edn")
                           (#(when (fs/regular-file? %) (slurp (str %))))
                           edn/read-string)]
          (force-pid! (:seon.dev.process/pid worker)))
        (fs/delete-tree directory)))))
