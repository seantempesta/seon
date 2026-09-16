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

(deftest publication-diagnostics-come-from-the-operator-result
  (let [directory (fixture-directory)
        path (io/file directory "operator.edn")
        failure {:seon.error/kind :publication-failed
                 :seon.error/message "missing schema"
                 :seon.fresh-operator/exception-data {:schema :example/input}}
        program
        (str "(require '[seon.fresh-operator :as operator]) "
             "(binding [*in* (java.io.StringReader. \"{}\") "
             "*out* (java.io.StringWriter.)] (load-file \"bin/seon-hook\")) "
             "(with-redefs [operator/init! (fn [& _] "
             "(println \"unreadable console {[\") "
             "(throw (ex-info \"missing schema\" " (pr-str failure) ")))] "
             "(binding [*out* (java.io.StringWriter.)] "
             "(try (#'operator/init-result! \".\" [\"--result-file\" "
             (pr-str (str path)) "]) (catch Exception _ nil)))) "
             "(prn (publication-result " (pr-str (str path)) "))")]
    (try
      (let [result (run-process {::command ["bb" "-e" program]
                                 ::directory repo-root})]
        (is (zero? (::exit result)) (::stderr result))
        (is (= failure (edn/read-string (::stdout result)))))
      (finally (test-support/delete-recursively! directory)))))

(def ^:private queued-editor-probe
  '(do
     (binding [*in* (java.io.StringReader. "{}")
               *out* (java.io.StringWriter.)]
       (load-file "bin/seon-hook"))
     (let [paths (mapv #(str (root-file %))
                      ["src/seon/test/arm.clj" "src/seon/test/runner.clj"
                       "test/seon/test/runner_test.clj"
                       "test/seon/dev/hook_test.clj"
                       "test/seon/dev/edit_feedback_test.clj"])
           config {:current-source {:enabled true :timeout-seconds 1}}
           publications (atom [])
           responses (atom [])
           edit (fn [path]
                  (current-source-feedback
                   {:hook_event_name "PostToolUse" :tool_name "Edit"
                    :tool_input {:file_path path}}
                   config))]
       (io/make-parents source-worker-path)
       (spit source-worker-path
             (pr-str {:seon.hook/pid (.pid (java.lang.ProcessHandle/current))}))
       (with-redefs [load-config (constantly config)
                     publish-source-paths
                     (fn [batch-paths _ id]
                       (swap! publications conj
                              {:seon.hook/publication id :seon.hook/paths batch-paths})
                       ;; Inject edit events while the first publication is
                       ;; on the stack. Feedback must return before it settles;
                       ;; no scheduling interval determines batch membership.
                       (when (= 1 (count @publications))
                         (doseq [path (conj paths (first paths))]
                           (swap! responses conj
                                  {:seon.probe/path path :seon.probe/feedback (edit path)})))
                       "refused: probe publication")]
         (let [initial (edit (first paths))]
           (run-source-worker!)
           (prn {:seon.probe/paths paths
                 :seon.probe/initial initial
                 :seon.probe/publications @publications
                 :seon.probe/responses @responses
                 :seon.probe/results (mapv #(edn/read-string (slurp %))
                                           (.listFiles source-results-path))
                 :seon.probe/pending? (.exists pending-source-path)
                 :seon.probe/worker? (.exists source-worker-path)}))))))

(deftest concurrent-editors-queue-without-waiting-for-publication
  (let [directory (fixture-directory)]
    (try
      (let [result (run-process
                    {::command ["bb" "-e" (pr-str queued-editor-probe)]
                     ::directory repo-root
                     ::environment {"SEON_HOOK_STATE_DIR" (str directory)}
                     ::deadline-ms (* 1000 test-support/event-backstop-seconds)})
            _ (is (zero? (::exit result)) (::stderr result))
            observed (edn/read-string (::stdout result))
            paths (:seon.probe/paths observed)
            publications (:seon.probe/publications observed)
            [initial successor] publications
            responses (:seon.probe/responses observed)
            results (:seon.probe/results observed)]
        (is (= 2 (count publications)) "One initial batch and exactly one successor.")
        (is (= [(first paths)] (:seon.hook/paths initial)))
        (is (= (vec (sort paths)) (:seon.hook/paths successor)))
        (is (not= (:seon.hook/publication initial) (:seon.hook/publication successor)))
        (is (str/includes? (:seon.probe/initial observed) (:seon.hook/publication initial)))
        (is (= (inc (count paths)) (count responses)))
        (doseq [{:seon.probe/keys [path feedback]} responses]
          (is (str/includes? feedback "queued for publication"))
          (is (str/includes? feedback (:seon.hook/publication successor)))
          (is (str/includes? feedback path))
          (is (str/includes? feedback "seon.cluster.source/current")))
        (is (= 2 (count results)))
        (is (= (set (map :seon.hook/publication publications))
               (set (map :seon.hook/publication results))))
        (is (every? #(= "refused: probe publication" (:seon.hook/feedback %)) results)
            "Terminal refusals remain refusals, never convergence.")
        (is (false? (:seon.probe/pending? observed)))
        (is (false? (:seon.probe/worker? observed))))
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
                 " :review "
                 (pr-str (assoc (:review (edn/read-string
                                          (slurp (io/file repo-root ".claude/seon-hook.edn"))))
                                :enabled true :interval-seconds 1 :skills []))
                 "}\n"))
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
