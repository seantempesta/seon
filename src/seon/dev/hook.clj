(ns seon.dev.hook
  "Main orchestrator for the development feedback hook.

   This is the single entry point called by the thin Babashka hook script.
   It coordinates all the other dev namespaces:
   - seon.dev.context - Edit/review event tracking
   - seon.dev.codebase - File introspection, namespace mapping
   - seon.dev.verify - Test orchestration
   - seon.dev.repair - Delimiter repair
   - seon.dev.review - AI code review

   The hook processes Claude Code hook events (PreToolUse/PostToolUse) and
   returns structured responses for feedback.

   Example usage:
     (require '[seon.dev.hook :as hook])

     ;; Process a hook event
     (hook/process-hook-event!
       (user/xtdb-node)
       {::hook/event {:hook_event_name \"PostToolUse\"
                      :tool_name \"Edit\"
                      :tool_input {:file_path \"/path/to/file.clj\"}}
        ::hook/config {:repair {:enabled true}
                       :tests {:unit {:enabled true}}
                       :review {:enabled true :interval-seconds 60}}})"
  (:require [clojure.string :as str]
            [seon.dev.codebase :as codebase]
            [seon.dev.context :as context]
            [seon.dev.repair :as repair]
            [seon.dev.review :as review]
            [seon.dev.verify :as verify]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

;; Hook event from Claude Code
(schema/register! ::hook-event-name
                  [:enum {:description "The type of hook event"}
                   "PreToolUse" "PostToolUse"])

(schema/register! ::tool-name
                  [:enum {:description "The tool that triggered the event"}
                   "Edit" "Write"])

(schema/register! ::tool-input
                  [:map {:description "Tool-specific input parameters"}
                   [:file_path {:optional true} :string]
                   [:filePath {:optional true} :string]])

(schema/register! ::session-id
                  [:string {:description "Claude Code session identifier"}])

(schema/register! ::hook-event
                  [:map {:description "Claude Code hook event payload"}
                   [:hook_event_name ::hook-event-name]
                   [:tool_name ::tool-name]
                   [:tool_input {:optional true} ::tool-input]
                   [:session_id {:optional true} ::session-id]])

;; Configuration schema
(schema/register! ::repair-config
                  [:map {:description "Repair stage configuration"}
                   [:enabled {:optional true} :boolean]
                   [:cljfmt {:optional true} :boolean]])

(schema/register! ::unit-test-config
                  [:map {:description "Unit test configuration"}
                   [:enabled {:optional true} :boolean]
                   [:block-on-fail {:optional true} :boolean]
                   [:timeout-seconds {:optional true} [:int {:min 1}]]])

(schema/register! ::gen-test-config
                  [:map {:description "Generative test configuration"}
                   [:enabled {:optional true} :boolean]
                   [:num-tests {:optional true} [:int {:min 1}]]
                   [:block-on-fail {:optional true} :boolean]])

(schema/register! ::review-config
                  [:map {:description "AI review configuration"}
                   [:enabled {:optional true} :boolean]
                   [:interval-seconds {:optional true} [:int {:min 0}]]
                   [:max-code-length {:optional true} [:int {:min 1}]]
                   [:max-output-length {:optional true} [:int {:min 1}]]])

(schema/register! ::config
                  [:map {:description "Full hook configuration"}
                   [:repair {:optional true} ::repair-config]
                   [:tests {:optional true}
                    [:map
                     [:unit {:optional true} ::unit-test-config]
                     [:generative {:optional true} ::gen-test-config]]]
                   [:review {:optional true} ::review-config]])

;; Request/Response schemas
(schema/register! ::process-request
                  [:map {:description "Request to process a hook event"}
                   [::event ::hook-event]
                   [::config {:optional true} ::config]])

(schema/register! ::decision
                  [:enum {:description "Hook decision"}
                   "block"])

(schema/register! ::process-response
                  [:map {:description "Response from hook processing"}
                   [::continue {:optional true} :boolean]
                   [::decision {:optional true} ::decision]
                   [::reason {:optional true} :string]
                   [::feedback {:optional true} [:vector :string]]])

;;; ---------------------------------------------------------------------------
;;; Configuration Defaults
;;; ---------------------------------------------------------------------------

(def ^:const default-config
  "Default configuration when not provided."
  {:repair {:enabled true
            :cljfmt true}
   :tests {:unit {:enabled true
                  :block-on-fail true
                  :timeout-seconds 30}
           :generative {:enabled true
                        :num-tests 10
                        :block-on-fail true}}
   :review {:enabled true
            :interval-seconds 60
            :max-code-length 12000
            :max-output-length 500}})

(defn- merge-config
  "Deep merge user config with defaults."
  [user-config]
  (let [repair (merge (:repair default-config) (:repair user-config))
        tests {:unit (merge (get-in default-config [:tests :unit])
                            (get-in user-config [:tests :unit]))
               :generative (merge (get-in default-config [:tests :generative])
                                  (get-in user-config [:tests :generative]))}
        review (merge (:review default-config) (:review user-config))]
    {:repair repair
     :tests tests
     :review review}))

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- get-file-path
  "Extract file path from tool input, checking multiple possible keys."
  [tool-input]
  (or (:file_path tool-input)
      (:filePath tool-input)))

(defn- seon-source-file?
  "Check if file is a Seon source file (in src/seon/)."
  [file-path]
  (and file-path
       (codebase/clojure-file? file-path)
       (str/includes? file-path "src/seon/")))

(defn- success-response
  "Build a success response with optional feedback messages."
  [feedback]
  (if (seq feedback)
    {::continue true
     ::feedback (vec feedback)}
    {::continue true}))

(defn- block-response
  "Build a blocking response with reason."
  [reason]
  {::decision "block"
   ::reason reason})

;;; ---------------------------------------------------------------------------
;;; Pipeline Stages
;;; ---------------------------------------------------------------------------

(defn- stage-repair
  "Run delimiter repair on file if needed.

   Returns:
     {:success true}                     - File is valid or was repaired
     {:success false :error \"...\"}     - File has unfixable errors
     nil                                 - Repair disabled or not a Clojure file"
  [file-path config]
  (when (and (get-in config [:repair :enabled])
             (codebase/clojure-file? file-path))
    (let [read-result (codebase/read-source file-path)]
      (if-not (::codebase/success read-result)
        ;; Can't read file - skip repair
        {:success true}
        (let [content (::codebase/content read-result)]
          (if-not (repair/delimiter-error? content)
            ;; No errors - nothing to repair
            {:success true}
            ;; Try to repair
            (let [format? (get-in config [:repair :cljfmt] true)
                  result (repair/repair-and-format {::repair/content content
                                                    ::repair/format? format?})]
              (if (::repair/success result)
                ;; Repair succeeded - write back
                (do
                  (spit file-path (::repair/content result))
                  (log/info "Repaired delimiter errors in" file-path)
                  {:success true :repaired true})
                ;; Repair failed
                {:success false
                 :error "Unable to repair delimiter errors"}))))))))

(defn- stage-reload
  "Reload the namespace to check for compile errors.

   Returns:
     {:success true}                 - Namespace loaded successfully
     {:success false :error \"...\"}  - Compile error"
  [ns-sym]
  (try
    (require ns-sym :reload)
    {:success true}
    (catch Exception e
      {:success false
       :error (str "Compile error: " (.getMessage e))})))

(defn- stage-unit-tests
  "Run unit tests if enabled and test namespace exists.

   Returns test result map from verify namespace, or nil if skipped."
  [source-ns config]
  (when (get-in config [:tests :unit :enabled])
    (let [test-ns (codebase/file->test-namespace
                   (codebase/namespace->file source-ns))]
      (when (and test-ns (codebase/test-file-exists? test-ns))
        (log/debug "Running unit tests for" test-ns)
        (verify/run-unit-tests test-ns)))))

(defn- stage-gen-tests
  "Run generative tests if enabled.

   Returns gen test result map from verify namespace, or nil if skipped."
  [source-ns config]
  (when (get-in config [:tests :generative :enabled])
    (let [num-tests (get-in config [:tests :generative :num-tests] 10)]
      (log/debug "Running generative tests for" source-ns "with" num-tests "tests")
      (verify/run-gen-tests source-ns {::verify/num-tests num-tests}))))

(defn- stage-record-edit
  "Record the edit event in XTDB."
  [xtdb-node file-path ns-sym]
  (context/record-edit! xtdb-node file-path ns-sym))

(defn- stage-should-review?
  "Check if we should trigger a review based on rate limiting."
  [xtdb-node config]
  (when (get-in config [:review :enabled])
    (let [interval (get-in config [:review :interval-seconds] 60)]
      (context/should-review? xtdb-node {::context/interval-seconds interval}))))

(defn- stage-review
  "Run AI review on accumulated edits.

   Returns formatted review text or nil if skipped/failed."
  [xtdb-node config unit-result]
  (when (get-in config [:review :enabled])
    (let [summary (context/edits-summary xtdb-node)
          files (::context/files summary)]
      (when (seq files)
        (log/info "Running AI review on" (count files) "files")
        (let [result (review/review-edits
                      {::review/files files
                       ::review/test-results unit-result
                       ::review/max-output-length (get-in config [:review :max-output-length] 500)})]
          ;; Record that we completed a review
          (context/record-review! xtdb-node files)
          result)))))

;;; ---------------------------------------------------------------------------
;;; Main Entry Point
;;; ---------------------------------------------------------------------------

(defn process-hook-event!
  "Process a Claude Code hook event.

   This is the single public entry point called by the Babashka hook script.
   It orchestrates the full feedback pipeline:
   1. Repair - Fix delimiter errors if needed
   2. Reload - Check for compile errors
   3. Unit Tests - Run if test namespace exists
   4. Gen Tests - Run generative tests on schema'd functions
   5. Record - Store edit event in XTDB
   6. Review - Trigger AI review if rate limit allows

   Request keys:
     ::event  - Parsed hook JSON from Claude Code
     ::config - Configuration (merged with defaults)

   Response keys (for Claude Code JSON):
     ::continue - true to proceed with the edit
     ::decision - \"block\" to stop the edit
     ::reason   - Why the edit was blocked
     ::feedback - Vector of messages for additionalContext

   Examples:
     ;; Successful edit
     (process-hook-event! node {::event {...} ::config {...}})
     ;; => {::continue true ::feedback [\"5 tests passed\"]}

     ;; Blocked edit
     ;; => {::decision \"block\" ::reason \"Compile error: ...\"}"
  {:malli/schema [:=> [:cat :any ::process-request] ::process-response]}
  [xtdb-node {::keys [event config]}]
  (let [config (merge-config config)
        event-name (:hook_event_name event)
        tool-name (:tool_name event)
        file-path (get-file-path (:tool_input event))
        feedback (atom [])]

    (log/debug "Processing hook event" {:event event-name
                                        :tool tool-name
                                        :file file-path})

    ;; Skip non-relevant events
    (when-not (and (or (= event-name "PreToolUse")
                       (= event-name "PostToolUse"))
                   (or (= tool-name "Edit")
                       (= tool-name "Write")))
      (log/debug "Skipping non-relevant event")
      (success-response @feedback))

    ;; Skip non-Clojure files
    (if-not (codebase/clojure-file? file-path)
      (do
        (log/debug "Skipping non-Clojure file" file-path)
        (success-response @feedback))

      ;; === PreToolUse: Only repair ===
      (if (= event-name "PreToolUse")
        ;; PreToolUse - just check syntax if file exists
        (let [repair-result (stage-repair file-path config)]
          (if (and repair-result (not (:success repair-result)))
            (block-response (:error repair-result))
            (success-response @feedback)))

        ;; === PostToolUse: Full pipeline ===
        (if-not (seon-source-file? file-path)
          ;; Not a seon source file - skip full pipeline
          (do
            (log/debug "Skipping non-seon source file" file-path)
            (success-response @feedback))

          ;; Run full pipeline for seon source files
          (let [ns-sym (codebase/file->namespace file-path)]
            (if-not ns-sym
              ;; Can't determine namespace - skip
              (do
                (log/debug "Could not determine namespace for" file-path)
                (success-response @feedback))

              ;; Full pipeline
              (do
                ;; 1. Repair
                (let [repair-result (stage-repair file-path config)]
                  (when (and repair-result (not (:success repair-result)))
                    (block-response (:error repair-result))))

                ;; 2. Reload namespace
                (let [reload-result (stage-reload ns-sym)]
                  (when-not (:success reload-result)
                    (block-response (:error reload-result))))

                ;; 3. Unit tests
                (let [unit-result (stage-unit-tests ns-sym config)]
                  (when unit-result
                    (if (::verify/success unit-result)
                      (swap! feedback conj
                             (verify/format-unit-result unit-result
                                                        (codebase/file->test-namespace file-path)))
                      ;; Unit tests failed
                      (when (get-in config [:tests :unit :block-on-fail])
                        (block-response
                         (format "Unit tests failed: %d failures, %d errors in %s"
                                 (::verify/fail-count unit-result 0)
                                 (::verify/error-count unit-result 0)
                                 (codebase/file->test-namespace file-path))))))

                  ;; 4. Generative tests
                  (let [gen-result (stage-gen-tests ns-sym config)]
                    (when gen-result
                      (if (::verify/success gen-result)
                        (swap! feedback conj
                               (verify/format-gen-result gen-result ns-sym))
                        ;; Gen tests failed
                        (when (get-in config [:tests :generative :block-on-fail])
                          (let [failed-fns (str/join ", " (map #(str (::verify/fn-symbol %))
                                                               (::verify/failures gen-result)))]
                            (block-response
                             (format "Generative tests failed for: %s"
                                     (if (str/blank? failed-fns) "schema errors" failed-fns))))))))

                  ;; 5. Record edit
                  (stage-record-edit xtdb-node file-path ns-sym)

                  ;; 6. Review (if rate limit allows)
                  (when (stage-should-review? xtdb-node config)
                    (when-let [review-text (stage-review xtdb-node config unit-result)]
                      (swap! feedback conj review-text)))

                  ;; Success
                  (success-response @feedback))))))))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  (require '[seon.dev.hook :as hook])

  ;; Test event processing (requires running XTDB node)
  (def test-event
    {:hook_event_name "PostToolUse"
     :tool_name "Edit"
     :tool_input {:file_path "/Users/sean/src/seon/src/seon/core.clj"}})

  ;; Process with default config
  (process-hook-event!
   (user/xtdb-node)
   {::event test-event
    ::config {}})

  ;; Process with custom config
  (process-hook-event!
   (user/xtdb-node)
   {::event test-event
    ::config {:repair {:enabled false}
              :tests {:unit {:enabled true :block-on-fail false}
                      :generative {:enabled false}}
              :review {:enabled true :interval-seconds 0}}})

  ;; Test PreToolUse (only repair)
  (process-hook-event!
   (user/xtdb-node)
   {::event {:hook_event_name "PreToolUse"
             :tool_name "Edit"
             :tool_input {:file_path "/tmp/test.clj"}}
    ::config {}})

  nil)
