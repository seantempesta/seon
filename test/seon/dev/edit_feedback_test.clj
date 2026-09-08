(ns seon.dev.edit-feedback-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.operator.state :as operator.state]
            [seon.test-support :as test-support]))

(def ^:private repo-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(defn- run-process
  [{::keys [command directory environment input deadline-ms]}]
  (let [result
        (operator.state/run-process!
         {:seon.operator.subprocess/argv command
          :seon.operator.subprocess/directory directory
          :seon.operator.subprocess/extra-env (or environment {})
          :seon.operator.subprocess/input (or input "")
          :seon.operator.subprocess/deadline-ms (or deadline-ms 30000)})]
    {::exit (:seon.operator.subprocess/exit result)
     ::stdout (:seon.operator.subprocess/output result)
     ::stderr (:seon.operator.subprocess/error-output result)}))

(defn- fixture-directory []
  (doto (io/file repo-root "tmp" (str "edit-feedback-" (random-uuid)))
    (.mkdirs)))

(defn- delete-files! [files]
  (doseq [^java.io.File file files]
    (when (.exists file) (.delete file))))

(deftest publication-diagnostics-survive-trailing-output
  (let [failure {:cause "missing schema" :data {:schema :example/input}}
        envelope (pr-str {:seon.fresh-operator/events
                          [{:exception true :val (pr-str failure)}
                           {:tag :out :val "cleanup"}]})
        cases [{:out (str envelope "\nfinished") :err "warning"}
               {:out "progress" :err (str envelope "\nwarning")}
               {:out envelope :err ""}
               {:out "no structured failure" :err "warning"}]
        program (str "(binding [*in* (java.io.StringReader. \"{}\") "
                     "*out* (java.io.StringWriter.)] (load-file \"bin/seon-hook\")) "
                     "(prn (mapv publication-exception " (pr-str cases) "))")
        result (run-process {::command ["bb" "-e" program]
                             ::directory repo-root})]
    (is (zero? (::exit result)) (::stderr result))
    (is (= [failure failure failure nil]
           (edn/read-string (::stdout result))))))

(deftest concurrent-editors-receive-the-one-publication-covering-their-paths
  (let [directory (fixture-directory)
        config (io/file directory "hook.edn")
        state (io/file directory "state")
        paths (mapv #(str (io/file repo-root %))
                    ["src/seon/cluster.clj" "src/seon/cluster/source.clj"
                     "test/seon/cluster/source_test.clj"
                     "test/seon/cluster/boot_test.clj"
                     "test/seon/dev/edit_feedback_test.clj"])
        operator-root (str (.relativize (.toPath repo-root)
                                        (.toPath (io/file directory "operator"))))]
    (try
      (spit config
            (pr-str {:docstring-lint {:enabled false}
                     :review {:enabled false}
                     :current-source {:enabled true :root operator-root
                                      :cluster "missing" :quiet-seconds 5
                                      :timeout-seconds 20}}))
      (let [requests
            (mapv (fn [path]
                    (future
                      (run-process
                       {::command [(str (io/file repo-root "bin/seon-hook"))]
                        ::directory repo-root
                        ::environment {"SEON_HOOK_CONFIG" (str config)
                                       "SEON_HOOK_STATE_DIR" (str state)}
                        ::input (json/generate-string
                                 {:hook_event_name "PostToolUse" :tool_name "Edit"
                                  :tool_input {:file_path path}})})))
                  paths)
            responses (mapv #(test-support/await-event! % "coalesced hook result") requests)
            result-files (vec (.listFiles (io/file state "source-publications")))]
        (is (= 1 (count result-files)) "one real operator request covers five editors")
        (doseq [response responses]
          (is (zero? (::exit response)) (::stderr response)))
        (when (= 1 (count result-files))
          (let [result (edn/read-string (slurp (first result-files)))
                id (:seon.hook/publication result)
                worker (java.lang.ProcessHandle/of (:seon.hook/worker-pid result))]
            (is (= (set paths) (set (:seon.hook/paths result))))
            (is (str/includes? (:seon.hook/feedback result) "refused")
                "the real operator refuses the absent JVM; absence is never convergence")
            (doseq [[path response] (map vector paths responses)]
              (is (str/includes? (::stdout response) id))
              (is (str/includes? (::stdout response) path)))
            (when (.isPresent worker)
              (.get (.onExit (.get worker)) 5 java.util.concurrent.TimeUnit/SECONDS))
            (is (not (.exists (io/file state ".source-worker.edn")))))))
      (finally (test-support/delete-recursively! directory)))))

(deftest pre-edit-blocks-reconstructed-error-level-findings
  (let [directory (fixture-directory)
        config (io/file directory "hook.edn")
        source (io/file directory "prospective.clj")]
    (try
      (spit config
            "{:seon.config/on-core-error :log\n :changed-tests {:enabled false}}\n")
      (let [result
            (run-process
             {::command [(str (io/file repo-root "bin/seon-hook"))]
              ::directory repo-root
              ::environment {"SEON_HOOK_CONFIG" (str config)}
              ::input
              (json/generate-string
               {:hook_event_name "PreToolUse"
                :tool_name "Write"
                :tool_input {:file_path (str source)
                             :content "(ns prospective\n"}})})
            response (json/parse-string (str/trim (::stdout result)) true)]
        (is (zero? (::exit result)) (::stderr result))
        (is (= "block" (:decision response)))
        (is (str/includes? (:reason response) "[error/syntax]")))
      (finally
        (delete-files! [config directory])))))

(deftest pre-edit-exact-reconstruction-uses-structural-edit-refusals
  (let [directory (fixture-directory)
        config (io/file directory "hook.edn")
        source (io/file directory "prospective.clj")
        invoke
        (fn [old-string new-string]
          (run-process
           {::command [(str (io/file repo-root "bin/seon-hook"))]
            ::directory repo-root
            ::environment {"SEON_HOOK_CONFIG" (str config)}
            ::input
            (json/generate-string
             {:hook_event_name "PreToolUse"
              :tool_name "Edit"
              :tool_input {:file_path (str source)
                           :old_string old-string
                           :new_string new-string}})}))]
    (try
      (spit config
            (str "{:seon.config/on-core-error :log\n"
                 " :changed-tests {:enabled false}\n"
                 " :review {:enabled false}}\n"))
      (spit source "(ns prospective)\n(def value 1)\n(def other 1)\n")
      (testing "one exact occurrence is reconstructed and checked"
        (let [result (invoke "(def value 1)" "(def value")
              response (json/parse-string (str/trim (::stdout result)) true)]
          (is (zero? (::exit result)) (::stderr result))
          (is (= "block" (:decision response)))
          (is (str/includes? (:reason response) "[error/syntax]"))))
      (testing "an ambiguous exact occurrence produces no invented first edit"
        (let [result (invoke " 1)" "")
              response (json/parse-string (str/trim (::stdout result)) true)]
          (is (zero? (::exit result)) (::stderr result))
          (is (true? (:continue response)))
          (is (nil? (:decision response)))))
      (testing "a reconstruction failure carries its actual exception message"
        (.delete source)
        (let [result (invoke "(def value 1)" "(def value")
              response (json/parse-string (str/trim (::stdout result)) true)]
          (is (zero? (::exit result)) (::stderr result))
          (is (= "block" (:decision response)))
          (is (str/includes? (:reason response) "prospective.clj"))))
      (finally
        (delete-files! [source config directory])))))

(deftest ^{:seon.test/long
           "59.518 s pool: real hook subprocesses cover schema admission before edit-hook publication."}
  split-schema-edits-run-admission-before-publication
  (let [directory (fixture-directory)
        config (io/file directory "hook.edn")
        schema-file
        (io/file repo-root
                 "resources/seon/schemas/seon.admission.hook.fixture.edn")
        invoke
        (fn [event]
          (run-process
           {::command [(str (io/file repo-root "bin/seon-hook"))]
            ::deadline-ms 120000
            ::directory repo-root
            ::environment {"SEON_HOOK_CONFIG" (str config)}
            ::input (json/generate-string event)}))]
    (try
      (spit config
            (str "{:seon.config/on-core-error :log\n"
                 " :lint {:enabled false}\n"
                 " :markdown-lint {:enabled false}\n"
                 " :docstring-lint {:enabled false}\n"
                 " :current-source {:enabled false}\n"
                 " :feedback {:max-tokens 10000}\n"
                 " :changed-tests {:enabled false}\n"
                 " :review {:enabled false}}\n"))
      (testing "error findings block a reconstructed schema edit"
        (let [result
              (invoke
               {:hook_event_name "PreToolUse"
                :tool_name "Write"
                :tool_input
                {:file_path (str schema-file)
                 :content
                 "{:wrong.namespace/value [:map {:closed true}]}"}})
              response (json/parse-string (str/trim (::stdout result)) true)]
          (is (zero? (::exit result)) (::stderr result))
          (is (= "block" (:decision response)))
          (is (str/includes? (:reason response)
                             "[error/schema-misplaced-key]"))
          (is (str/includes? (:reason response)
                             "[error/schema-closed-map]"))))
      (testing "reuse similarity rides the ordinary post-edit feedback"
        (spit schema-file
              (str "{:seon.admission.hook.fixture/positive-count "
                   "[:int {:min 1}]}\n"))
        (let [result
              (invoke
               {:hook_event_name "PostToolUse"
                :tool_name "Edit"
                :tool_input {:file_path (str schema-file)}})
              response (json/parse-string (str/trim (::stdout result)) true)
              feedback
              (get-in response [:hookSpecificOutput :additionalContext])]
          (is (zero? (::exit result)) (::stderr result))
          (is (true? (:continue response)))
          (is (str/includes? feedback
                             "[warning/schema-exact-reuse]"))
          (is (str/includes? feedback "same composite shape as"))))
      (finally
        (delete-files! [schema-file config directory])))))

(deftest post-edit-reports-valid-sibling-findings-after-a-syntax-error
  (let [directory (fixture-directory)
        config (io/file directory "hook.edn")
        broken (io/file directory "broken.clj")
        valid (io/file directory "valid.clj")
        relative (fn [file]
                   (str (.relativize (.toPath repo-root) (.toPath file))))
        patch (str "*** Begin Patch\n"
                   "*** Add File: " (relative broken) "\n"
                   "*** Add File: " (relative valid) "\n"
                   "*** End Patch")]
    (try
      (spit config
            (str "{:seon.config/on-core-error :log\n"
                 " :feedback {:max-tokens 10000"
                 " :max-advisory-findings 3}\n"
                 " :docstring-lint {:enabled false}\n"
                 " :current-source {:enabled false}\n"
                 " :changed-tests {:enabled false}\n"
                 " :review {:enabled false}}\n"))
      (spit broken "(ns broken\n")
      (spit valid
            (str "(ns valid)\n"
                 (str/join "\n"
                           (map #(str "(defn f" % " [unused] :ok)")
                                (range 30)))
                 "\n"))
      (let [result (run-process
                    {::command [(str (io/file repo-root "bin/seon-hook"))]
                     ::directory (.getParentFile repo-root)
                     ::environment {"SEON_HOOK_CONFIG" (str config)}
                     ::input
                     (json/generate-string
                      {:hook_event_name "PostToolUse"
                       :tool_name "apply_patch"
                       :tool_input {:command patch}})})
            response (json/parse-string (str/trim (::stdout result)) true)
            feedback (get-in response
                             [:hookSpecificOutput :additionalContext])]
        (is (zero? (::exit result)) (::stderr result))
        (is (true? (:continue response)))
        (is (str/includes? feedback
                           (str (relative broken) ":1:1 [error/syntax]")))
        (is (str/includes? feedback
                           (str (relative valid)
                                ":2:11 [warning/unused-binding]")))
        (is (< (str/index-of feedback "[error/syntax]")
               (str/index-of feedback "[warning/unused-binding]")))
        (is (str/includes? feedback "27 advisory finding(s) omitted"))
        (is (< (count feedback) 2500)))
      (finally
        (delete-files! [broken valid config directory])))))

(deftest post-edit-makes-analyzer-failure-visible-without-gating
  (let [directory (fixture-directory)
        fake-bin (doto (io/file directory "bin") .mkdirs)
        fake-kondo (io/file fake-bin "clj-kondo")
        config (io/file directory "hook.edn")
        source (io/file directory "valid.clj")]
    (try
      (spit fake-kondo "#!/bin/sh\necho not-edn\n")
      (.setExecutable fake-kondo true)
      (spit config
            "{:seon.config/on-core-error :log\n :docstring-lint {:enabled false}\n :changed-tests {:enabled false}}\n")
      (spit source "(ns valid)\n")
      (let [result
            (run-process
             {::command [(str (io/file repo-root "bin/seon-hook"))]
              ::directory repo-root
              ::environment
              {"SEON_HOOK_CONFIG" (str config)
               "PATH" (str fake-bin java.io.File/pathSeparator
                           (System/getenv "PATH"))}
              ::input
              (json/generate-string
               {:hook_event_name "PostToolUse"
                :tool_name "Edit"
                :tool_input {:file_path (str source)}})})
            response (json/parse-string (str/trim (::stdout result)) true)
            feedback (get-in response
                             [:hookSpecificOutput :additionalContext])]
        (is (zero? (::exit result)) (::stderr result))
        (is (true? (:continue response)))
        (is (str/includes? feedback "clj-kondo analysis failed")))
      (finally
        (delete-files! [source config fake-kondo fake-bin directory])))))

(deftest post-edit-coalesces-one-optional-review-worker-and-drops-failure
  (let [directory (fixture-directory)
        fake-bin (doto (io/file directory "bin") .mkdirs)
        fake-agy (io/file fake-bin "agy")
        config (io/file directory "hook.edn")
        source (io/file directory "review.md")
        calls (io/file directory "agy-calls")
        state (io/file directory "state")
        event
        (json/generate-string
         {:hook_event_name "PostToolUse"
          :tool_name "Edit"
          :tool_input {:file_path (str source)}})
        environment
        {"SEON_HOOK_CONFIG" (str config)
         "SEON_HOOK_STATE_DIR" (str state)
         "SEON_REVIEW_TEST_COUNT" (str calls)
         "PATH" (str fake-bin java.io.File/pathSeparator
                     (System/getenv "PATH"))}
        invoke
        #(run-process
          {::command [(str (io/file repo-root "bin/seon-hook"))]
           ::directory repo-root
           ::environment environment
           ::input event})]
    (try
      (spit fake-agy
            "#!/bin/sh\nprintf x >> \"$SEON_REVIEW_TEST_COUNT\"\nexit 1\n")
      (.setExecutable fake-agy true)
      (spit config
            (str "{:seon.config/on-core-error :log\n"
                 " :lint {:enabled false}\n"
                 " :markdown-lint {:enabled false}\n"
                 " :docstring-lint {:enabled false}\n"
                 " :current-source {:enabled false}\n"
                 " :review {:enabled true :interval-seconds 1}}\n"))
      (spit source "# Review me\n")
      (let [first-result (invoke)
            worker-file (io/file state ".review-worker.edn")
            first-worker (edn/read-string (slurp worker-file))
            second-result (invoke)
            second-worker (edn/read-string (slurp worker-file))
            pid (:seon.review.worker/pid first-worker)
            handle (.get (java.lang.ProcessHandle/of (long pid)))]
        (is (zero? (::exit first-result)) (::stderr first-result))
        (is (zero? (::exit second-result)) (::stderr second-result))
        (is (= pid (:seon.review.worker/pid second-worker)))
        (is (= 1 (count (str/split-lines
                         (slurp (io/file state ".pending-review"))))))
        (.get (.onExit handle) 5 java.util.concurrent.TimeUnit/SECONDS)
        (is (= "x" (slurp calls)))
        (is (str/blank? (slurp (io/file state ".pending-review"))))
        (is (not (.exists worker-file))))
      (finally
        (delete-files!
         [(io/file state ".pending-review")
          (io/file state ".pending-review.lock")
          state calls source config fake-agy fake-bin directory])))))

(deftest changed-test-analysis-keeps-the-valid-namespace-graph
  (let [directory (fixture-directory)
        source-directory (doto (io/file directory "src") .mkdirs)
        broken (io/file source-directory "broken.clj")
        valid (io/file source-directory "valid.clj")
        expression
        (str "(do (require 'seon.dev.changed-test) "
             "(prn (select-keys (seon.dev.changed-test/analyze-host "
             (pr-str (str directory)) ") "
             "[:seon.dev.changed-test/host-status "
             ":seon.dev.changed-test/host-graph "
             ":seon.dev.changed-test/findings])))")]
    (try
      (spit broken "(ns broken\n")
      (spit valid "(ns valid)\n(def value 1)\n")
      (let [result
            (run-process
             {::command ["bb" "--config" (str (io/file repo-root "bb.edn"))
                         "--deps-root" (str repo-root) "-e" expression]
              ::directory repo-root})
            analysis (edn/read-string (str/trim (::stdout result)))]
        (is (zero? (::exit result)) (::stderr result))
        (is (= :available (:seon.dev.changed-test/host-status analysis)))
        (is (= 'valid
               (get-in analysis
                       [:seon.dev.changed-test/host-graph
                        :seon.dev.changed-test/path->namespace
                        "src/valid.clj"])))
        (is (some #(and (= :syntax (:type %))
                        (str/ends-with? (:filename %) "src/broken.clj"))
                  (:seon.dev.changed-test/findings analysis))))
      (finally
        (delete-files! [broken valid source-directory directory])))))
