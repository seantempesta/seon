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
       {::hook/event {:hook_event_name \"PostToolUse\"
                      :tool_name \"Edit\"
                      :tool_input {:file_path \"/path/to/file.clj\"}}
        ::hook/config {:repair {:enabled true}
                       :tests {:unit {:enabled true}}
                       :review {:enabled true :interval-seconds 60}}})"
  (:require [clj-reload.core :as reload]
            [clojure.string :as str]
            [seon.dev.codebase :as codebase]
            [seon.dev.compliance :as compliance]
            [seon.dev.context :as context]
            [seon.dev.lint :as lint]
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
                   "Edit" "Write" "TodoWrite"])

(schema/register! ::tool-input
                  [:map {:description "Tool-specific input parameters"}
                   [:file_path {:optional true} :string]
                   [:filePath {:optional true} :string]
                   [:todos {:optional true}
                    [:vector [:map
                              [:content :string]
                              [:status [:enum "pending" "in_progress" "completed"]]
                              [:activeForm :string]]]]])

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

(schema/register! ::compliance-config
                  [:map {:description "Convention compliance configuration"}
                   [:enabled {:optional true} :boolean]
                   [:block {:optional true} :boolean]])

(schema/register! ::lint-config
                  [:map {:description "PreToolUse lint validation configuration"}
                   [:enabled {:optional true} :boolean]])

(schema/register! ::feedback-config
                  [:map {:description "Feedback formatting configuration"}
                   [:dense {:optional true} :boolean]
                   [:max-length {:optional true} [:int {:min 1}]]])

(schema/register! ::config
                  [:map {:description "Full hook configuration"}
                   [:lint {:optional true} ::lint-config]
                   [:repair {:optional true} ::repair-config]
                   [:tests {:optional true}
                    [:map
                     [:unit {:optional true} ::unit-test-config]
                     [:generative {:optional true} ::gen-test-config]]]
                   [:review {:optional true} ::review-config]
                   [:compliance {:optional true} ::compliance-config]
                   [:feedback {:optional true} ::feedback-config]])

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
  {:lint {:enabled true}  ; PreToolUse syntax validation
   :repair {:enabled true
            :cljfmt true}
   :reload {:enabled true}
   :tests {:unit {:enabled true
                  :block-on-fail true
                  :timeout-seconds 30}
           :generative {:enabled true
                        :num-tests 10
                        :block-on-fail true}}
   :review {:enabled true
            :interval-seconds 60
            :max-code-length 12000
            :max-output-length 500}
   :compliance {:enabled true
                :block false}
   :feedback {:dense true
              :max-length 1000}})

(defn- merge-config
  "Deep merge user config with defaults."
  [user-config]
  (let [lint (merge (:lint default-config) (:lint user-config))
        repair (merge (:repair default-config) (:repair user-config))
        reload (merge (:reload default-config) (:reload user-config))
        tests {:unit (merge (get-in default-config [:tests :unit])
                            (get-in user-config [:tests :unit]))
               :generative (merge (get-in default-config [:tests :generative])
                                  (get-in user-config [:tests :generative]))}
        review (merge (:review default-config) (:review user-config))
        compliance (merge (:compliance default-config) (:compliance user-config))
        feedback (merge (:feedback default-config) (:feedback user-config))]
    {:lint lint
     :repair repair
     :reload reload
     :tests tests
     :review review
     :compliance compliance
     :feedback feedback}))

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- get-file-path
  "Extract file path from tool input, checking multiple possible keys."
  [tool-input]
  (or (:file_path tool-input)
      (:filePath tool-input)))

(defn- seon-source-file?
  "Check if file is a Seon source or test file (in src/seon/ or test/seon/)."
  [file-path]
  (and file-path
       (codebase/clojure-file? {::codebase/file-path file-path})
       (or (str/includes? file-path "src/seon/")
           (str/includes? file-path "test/seon/"))))

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

(defn- extract-unit-summary
  "Extract a summary from unit test result for storage.
   Converts ::verify/* keys to simple keys for context.clj schema."
  [unit-result]
  (when unit-result
    {:success (::verify/success unit-result)
     :test-count (::verify/test-count unit-result)
     :pass-count (::verify/pass-count unit-result)
     :fail-count (::verify/fail-count unit-result)
     :error-count (::verify/error-count unit-result)}))

(defn- extract-gen-summary
  "Extract a summary from generative test result for storage."
  [gen-result]
  (when gen-result
    {:success (::verify/success gen-result)
     :error (::verify/error gen-result)}))

(defn- format-dense-success
  "Format a dense success line with all passing metrics.

   Example: '[checkmark] 5 tests, 3 gen-tests, compliant (0.2s)'

   Returns nil if there's nothing to report."
  [{:keys [unit-result gen-result compliance-result elapsed-ms]}]
  (let [parts (cond-> []
                ;; Unit tests
                (and unit-result (::verify/success unit-result))
                (conj (str (::verify/test-count unit-result) " tests"))

                ;; Gen tests passed

                (and gen-result (::verify/success gen-result))
                (conj "gen-tests")

                ;; Compliance
                (and compliance-result (:compliant? compliance-result))
                (conj "compliant"))
        ;; Format timing
        timing (when elapsed-ms
                 (format "%.1fs" (/ elapsed-ms 1000.0)))]
    (when (seq parts)
      (str "\u2713 "  ; checkmark
           (str/join ", " parts)
           (when timing (str " (" timing ")"))))))

;;; ---------------------------------------------------------------------------
;;; Code Index Update (Best-Effort)
;;; ---------------------------------------------------------------------------

(defn- update-code-index!
  "Update the Datalevin code index after a file change.
   Best-effort: logs warnings on failure, never blocks the hook."
  [file-path]
  (try
    (require 'integrant.repl.state)
    (when-let [scanner (get @(resolve 'integrant.repl.state/system) :seon/code-scanner)]
      (when-let [conn (:conn scanner)]
        (require 'seon.graph.analyzer)
        (require 'seon.graph.scanner)
        (require 'seon.graph.ingest)
        (let [analyze-form (resolve 'seon.graph.analyzer/analyze-form)
              extract-entities (resolve 'seon.graph.analyzer/extract-entities)
              scan-file (resolve 'seon.graph.scanner/scan-file)
              link-fns-to-specs (resolve 'seon.graph.scanner/link-fns-to-specs)
              ingest-incremental! (resolve 'seon.graph.ingest/ingest-incremental!)
              ;; Analyze the changed file
              source (slurp file-path)
              analysis (analyze-form {:seon.graph.analyzer/source source
                                      :seon.graph.analyzer/file-path file-path})]
          (when (:seon.graph.analyzer/success analysis)
            (let [entities (extract-entities {:seon.graph.analyzer/raw-analysis
                                             (:seon.graph.analyzer/raw-analysis analysis)})
                  ;; Scan file for specs
                  specs (scan-file {:seon.graph.scanner/file-path file-path})
                  ;; Link functions to specs
                  linked-fns (link-fns-to-specs (:seon.graph.analyzer/functions entities) specs)
                  entities (assoc entities :seon.graph.analyzer/functions linked-fns)]
              (ingest-incremental! {:seon.graph.ingest/conn conn
                                    :seon.graph.ingest/entities entities
                                    :seon.graph.ingest/specs specs}))))))
    (catch Exception e
      (log/debug "Code index update failed (non-blocking)" {:error (.getMessage e)}))))

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
             (codebase/clojure-file? {::codebase/file-path file-path}))
    (let [read-result (codebase/read-source {::codebase/file-path file-path})]
      (if-not (::codebase/success read-result)
        ;; Can't read file - skip repair
        {:success true}
        (let [content (::codebase/content read-result)]
          (if-not (repair/delimiter-error? {::repair/content content})
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
  "Reload changed namespaces via clj-reload.

   Uses clj-reload for fast, dependency-aware reloading that:
   - Only reloads what actually changed
   - Preserves defonce values
   - Reloads dependents in correct order

   Returns (merged with clj-reload result):
     {:success true :unloaded [...] :loaded [...]}
     {:success false :error \"...\" :failed ns-sym :unloaded [...] :loaded [...]}
     nil - if reload is disabled"
  [_ns-sym config]
  (when (get-in config [:reload :enabled])
    (let [result (reload/reload {:throw false})]
      (if-let [ex (:exception result)]
        (assoc result
               :success false
               :error (str "Compile error in " (:failed result) ": " (ex-message ex)))
        (assoc result :success true)))))

(defn- stage-unit-tests
  "Run unit tests if enabled and test namespace exists.

   Returns test result map from verify namespace, or nil if skipped."
  [source-ns config]
  (when (get-in config [:tests :unit :enabled])
    (let [test-ns (codebase/file->test-namespace
                   {::codebase/file-path (codebase/namespace->file {::codebase/namespace source-ns})})]
      (when (and test-ns (codebase/test-file-exists? {::codebase/namespace test-ns}))
        (log/debug "Running unit tests for" test-ns)
        (verify/run-unit-tests {::verify/test-ns test-ns})))))

(defn- stage-gen-tests
  "Run generative tests if enabled.

   Returns gen test result map from verify namespace, or nil if skipped."
  [source-ns config]
  (when (get-in config [:tests :generative :enabled])
    (let [num-tests (get-in config [:tests :generative :num-tests] 10)]
      (log/debug "Running generative tests for" source-ns "with" num-tests "tests")
      (verify/run-gen-tests {::verify/namespace source-ns ::verify/num-tests num-tests}))))

(defn- stage-compliance
  "Run convention compliance checks on namespace.

   Returns:
     {:compliant? true}                       - All functions comply
     {:compliant? false :formatted \"...\"}   - Violations found
     nil                                      - Compliance disabled"
  [source-ns config]
  (when (get-in config [:compliance :enabled])
    (log/debug "Running compliance checks for" source-ns)
    (let [result (compliance/analyze-namespace {::compliance/namespace source-ns})]
      (if (::compliance/compliant? result)
        {:compliant? true
         :public-fns (::compliance/public-fns result)
         :with-schema (::compliance/with-schema result)}
        (let [formatted (compliance/format-violations
                         {::compliance/violations (::compliance/violations result)
                          ::compliance/max-length (get-in config [:feedback :max-length] 1000)})]
          {:compliant? false
           :formatted (::compliance/formatted formatted)
           :violation-count (count (::compliance/violations result))})))))

(defn- stage-record-edit
  "Record the edit event with observability data."
  [file-path ns-sym opts]
  (context/record-edit! (cond-> {::context/file-path file-path}
                          ns-sym (assoc ::context/namespace ns-sym)
                          (:content-hash opts) (assoc ::context/content-hash (:content-hash opts))
                          (:unit-test-result opts) (assoc ::context/unit-test-result (:unit-test-result opts))
                          (:gen-test-result opts) (assoc ::context/gen-test-result (:gen-test-result opts))
                          (:decision opts) (assoc ::context/decision (:decision opts))
                          (:reason opts) (assoc ::context/reason (:reason opts))
                          (:feedback opts) (assoc ::context/feedback (:feedback opts)))))

(defn- stage-should-review?
  "Check if we should trigger a review based on rate limiting."
  [config]
  (when (get-in config [:review :enabled])
    (let [interval (get-in config [:review :interval-seconds] 60)
          result (context/should-review? {::context/interval-seconds interval})]
      (::context/should-review result))))

(defn- stage-review
  "Run AI review on accumulated edits.

   Returns formatted review text or nil if skipped/failed."
  [config unit-result]
  (when (get-in config [:review :enabled])
    (let [summary (context/edits-summary {})
          files (::context/files summary)]
      (when (seq files)
        (log/info "Running AI review on" (count files) "files")
        (let [result (review/review-edits
                      {::review/files files
                       ::review/test-results unit-result
                       ::review/max-output-length (get-in config [:review :max-output-length] 500)})]
          ;; Record review with full Gemini interaction data for training
          (context/record-review! {::context/files files
                                   ::context/gemini-prompt (::review/prompt result)
                                   ::context/gemini-response (::review/response result)
                                   ::context/gemini-system-instruction (::review/gemini-system-instruction result)
                                   ::context/gemini-code (::review/gemini-code result)
                                   ::context/gemini-tokens (::review/gemini-tokens result)})
          (::review/formatted-text result))))))

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
   5. Record - Store edit event
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
     (process-hook-event! {::event {...} ::config {...}})
     ;; => {::continue true ::feedback [\"5 tests passed\"]}

     ;; Blocked edit
     ;; => {::decision \"block\" ::reason \"Compile error: ...\"}"
  {:malli/schema [:=> [:cat ::process-request] ::process-response]}
  [{::keys [event config]}]
  (let [config (merge-config config)
        event-name (:hook_event_name event)
        tool-name (:tool_name event)
        file-path (get-file-path (:tool_input event))
        feedback (atom [])]

    (log/debug "Processing hook event" {:event event-name
                                        :tool tool-name
                                        :file file-path})

    ;; Handle TodoWrite events specially - just record to Datalevin, no other processing
    (if (and (= event-name "PostToolUse") (= tool-name "TodoWrite"))
      (let [session-id (:session_id event)
            todos (get-in event [:tool_input :todos])]
        (when (and session-id todos)
          (context/record-todos! {::context/session-id session-id
                                  ::context/todos todos}))
        (success-response @feedback))

      ;; Skip non-relevant events (not Edit/Write)
      (if-not (and (or (= event-name "PreToolUse")
                       (= event-name "PostToolUse"))
                   (or (= tool-name "Edit")
                       (= tool-name "Write")))
        (do
          (log/debug "Skipping non-relevant event")
          (success-response @feedback))

        ;; Skip non-Clojure files
        (if-not (codebase/clojure-file? {::codebase/file-path file-path})
          (do
            (log/debug "Skipping non-Clojure file" file-path)
            (success-response @feedback))

      ;; === PreToolUse: Validate code before tool runs ===
          ;; Uses unified validate-for-write for consistent error messages and suggestions.
          ;; For Seon source files: full clj-kondo validation (~50-100ms) catches:
          ;;   - Syntax errors, undefined symbols, wrong arity, private var access
          ;; For other files: fast syntax check (~1ms) catches delimiter errors only
          (if (= event-name "PreToolUse")
            (if-not (get-in config [:lint :enabled])
              ;; Lint validation disabled - skip
              (success-response @feedback)
              (let [full-lint? (seon-source-file? file-path)
                    ;; Get content to validate based on tool type
                    content-to-validate
                    (case tool-name
                      "Write" (:content (:tool_input event))
                      "Edit"  (let [old-string (:old_string (:tool_input event))
                                    new-string (:new_string (:tool_input event))
                                    current (try (slurp file-path) (catch Exception _ nil))]
                                (when (and old-string new-string current)
                                  (str/replace-first current old-string new-string)))
                      nil)]
                (if content-to-validate
                  ;; Validate using unified function
                  (let [result (lint/validate-for-write
                                {::lint/content content-to-validate
                                 ::lint/file-path file-path
                                 ::lint/full-lint? full-lint?})
                        prefix (str tool-name " would create invalid Clojure")]
                    (if (::lint/valid? result)
                      (success-response @feedback)
                      (block-response (::lint/error-msg result))))
                  ;; No content to validate - allow (missing params or can't read file)
                  (success-response @feedback))))

        ;; === PostToolUse: Full pipeline ===
            (if-not (seon-source-file? file-path)
          ;; Not a seon source file - skip full pipeline
              (do
                (log/debug "Skipping non-seon source file" file-path)
                (success-response @feedback))

          ;; Run full pipeline for seon source files
              (let [ns-sym (codebase/file->namespace {::codebase/file-path file-path})]
                (if-not ns-sym
              ;; Can't determine namespace - skip
                  (do
                    (log/debug "Could not determine namespace for" file-path)
                    (success-response @feedback))

              ;; Full pipeline - run stages, short-circuit on block
              ;; Track timing for dense feedback
                  (let [start-time (System/currentTimeMillis)
                        dense? (get-in config [:feedback :dense])

                    ;; 1. Repair - block if unfixable
                        repair-result (stage-repair file-path config)
                        repair-block (when (and repair-result (not (:success repair-result)))
                                       (block-response (:error repair-result)))]
                    (or repair-block
                    ;; 2. Reload namespace - block on compile error
                        (let [reload-result (stage-reload ns-sym config)
                          ;; Add feedback about what was reloaded
                              _ (when-let [loaded (seq (:loaded reload-result))]
                                  (swap! feedback conj (str "Reloaded: " (str/join ", " (map str loaded)))))
                              ;; Best-effort code index update after successful reload
                              _ (when (and reload-result (:success reload-result))
                                  (update-code-index! file-path))
                              reload-block (when (and reload-result
                                                      (not (:success reload-result)))
                                             (block-response (:error reload-result)))]
                          (or reload-block
                          ;; 3. Compliance checks - non-blocking feedback (after reload, before tests)
                              (let [compliance-result (stage-compliance ns-sym config)
                                ;; Violations always shown (actionable fixes), regardless of dense mode
                                    _ (when (and compliance-result
                                                 (not (:compliant? compliance-result)))
                                        (swap! feedback conj (:formatted compliance-result)))
                                    compliance-should-block (and compliance-result
                                                                 (not (:compliant? compliance-result))
                                                                 (get-in config [:compliance :block]))]
                                (if compliance-should-block
                                  (block-response (:formatted compliance-result))
                              ;; 4. Unit tests - optionally block on failure
                                  (let [unit-result (stage-unit-tests ns-sym config)
                                    ;; Only add verbose feedback when not dense mode
                                        _ (when (and (not dense?)
                                                     unit-result
                                                     (::verify/success unit-result))
                                            (swap! feedback conj
                                                   (verify/format-unit-result {::verify/result unit-result
                                                                               ::verify/test-ns (codebase/file->test-namespace {::codebase/file-path file-path})})))
                                        unit-should-block (and unit-result
                                                               (not (::verify/success unit-result))
                                                               (get-in config [:tests :unit :block-on-fail]))]
                                    (if unit-should-block
                                    ;; Record blocked edit then return block response
                                      (let [reason (format "Unit tests failed: %d failures, %d errors in %s"
                                                           (::verify/fail-count unit-result 0)
                                                           (::verify/error-count unit-result 0)
                                                           (codebase/file->test-namespace {::codebase/file-path file-path}))]
                                        (stage-record-edit file-path ns-sym
                                                           {:unit-test-result (extract-unit-summary unit-result)
                                                            :decision :block
                                                            :reason reason
                                                            :feedback @feedback})
                                        (block-response reason))
                                    ;; 5. Generative tests - optionally block on failure
                                      (let [gen-result (stage-gen-tests ns-sym config)
                                          ;; Only add verbose feedback when not dense mode
                                            _ (when (and (not dense?)
                                                         gen-result
                                                         (::verify/success gen-result))
                                                (swap! feedback conj
                                                       (verify/format-gen-result {::verify/result gen-result ::verify/namespace ns-sym})))
                                            gen-should-block (and gen-result
                                                                  (not (::verify/success gen-result))
                                                                  (get-in config [:tests :generative :block-on-fail]))]
                                        (if gen-should-block
                                          ;; Record blocked edit then return block response
                                          (let [failed-fns (str/join ", " (map #(str (::verify/fn-symbol %))
                                                                               (::verify/failures gen-result)))
                                                reason (format "Generative tests failed for: %s"
                                                               (if (str/blank? failed-fns) "schema errors" failed-fns))]
                                            (stage-record-edit file-path ns-sym
                                                               {:unit-test-result (extract-unit-summary unit-result)
                                                                :gen-test-result (extract-gen-summary gen-result)
                                                                :decision :block
                                                                :reason reason
                                                                :feedback @feedback})
                                            (block-response reason))
                                          ;; 6. Record edit + 7. Review + 8. Success
                                          (let [elapsed-ms (- (System/currentTimeMillis) start-time)]
                                            (stage-record-edit file-path ns-sym
                                                               {:unit-test-result (extract-unit-summary unit-result)
                                                                :gen-test-result (extract-gen-summary gen-result)
                                                                :decision :continue
                                                                :feedback @feedback})

                                            ;; Add dense success line if dense mode and everything passed
                                            (when dense?
                                              (when-let [dense-line (format-dense-success
                                                                     {:unit-result unit-result
                                                                      :gen-result gen-result
                                                                      :compliance-result compliance-result
                                                                      :elapsed-ms elapsed-ms})]
                                                (swap! feedback conj dense-line)))

                                            ;; AI review (if enabled and rate limit allows)
                                            (when (stage-should-review? config)
                                              (when-let [review-text (stage-review config unit-result)]
                                                (swap! feedback conj review-text)))

                                            (success-response @feedback)))))))))))))))))))))

;;; ---------------------------------------------------------------------------
;;; MCP Edit Integration
;;; ---------------------------------------------------------------------------

(schema/register! ::mcp-edit-request
                  [:map {:description "Request to process an MCP edit event"}
                   [::file-path :string]
                   [::config {:optional true} ::config]])

(schema/register! ::mcp-edit-response
                  [:map {:description "Response from MCP edit processing"}
                   [::success :boolean]
                   [::feedback [:vector :string]]
                   [::blocked {:optional true} :boolean]
                   [::reason {:optional true} :string]])

(defn- run-post-edit-pipeline!
  "Run the post-edit pipeline stages. Shared by process-hook-event! and process-mcp-edit!.
   Returns {:success :feedback :blocked :reason}."
  [{::keys [file-path ns-sym config feedback start-time]}]
  (let [dense? (get-in config [:feedback :dense])
        reload-result (stage-reload ns-sym config)
        _ (when-let [loaded (seq (:loaded reload-result))]
            (swap! feedback conj (str "Reloaded: " (str/join ", " (map str loaded)))))
        ;; Best-effort code index update after successful reload
        _ (when (and reload-result (:success reload-result))
            (update-code-index! file-path))]
    (if (and reload-result (not (:success reload-result)))
      {:success false :blocked true :reason (:error reload-result) :feedback @feedback}
      (let [compliance-result (stage-compliance ns-sym config)
            _ (when (and compliance-result (not (:compliant? compliance-result)))
                (swap! feedback conj (:formatted compliance-result)))
            compliance-block? (and compliance-result (not (:compliant? compliance-result))
                                   (get-in config [:compliance :block]))]
        (if compliance-block?
          {:success false :blocked true :reason (:formatted compliance-result) :feedback @feedback}
          (let [unit-result (stage-unit-tests ns-sym config)
                test-ns (codebase/file->test-namespace {::codebase/file-path file-path})
                _ (when (and (not dense?) unit-result (::verify/success unit-result))
                    (swap! feedback conj (verify/format-unit-result
                                          {::verify/result unit-result ::verify/test-ns test-ns})))
                unit-block? (and unit-result (not (::verify/success unit-result))
                                 (get-in config [:tests :unit :block-on-fail]))]
            (if unit-block?
              (let [reason (format "Unit tests failed: %d failures, %d errors in %s"
                                   (::verify/fail-count unit-result 0)
                                   (::verify/error-count unit-result 0) test-ns)]
                (stage-record-edit file-path ns-sym
                                   {:unit-test-result (extract-unit-summary unit-result)
                                    :decision :block :reason reason :feedback @feedback})
                {:success false :blocked true :reason reason :feedback @feedback})
              (let [gen-result (stage-gen-tests ns-sym config)
                    _ (when (and (not dense?) gen-result (::verify/success gen-result))
                        (swap! feedback conj (verify/format-gen-result
                                              {::verify/result gen-result ::verify/namespace ns-sym})))
                    gen-block? (and gen-result (not (::verify/success gen-result))
                                    (get-in config [:tests :generative :block-on-fail]))]
                (if gen-block?
                  (let [failed-fns (str/join ", " (map #(str (::verify/fn-symbol %))
                                                       (::verify/failures gen-result)))
                        reason (format "Generative tests failed for: %s"
                                       (if (str/blank? failed-fns) "schema errors" failed-fns))]
                    (stage-record-edit file-path ns-sym
                                       {:unit-test-result (extract-unit-summary unit-result)
                                        :gen-test-result (extract-gen-summary gen-result)
                                        :decision :block :reason reason :feedback @feedback})
                    {:success false :blocked true :reason reason :feedback @feedback})
                  (let [elapsed-ms (- (System/currentTimeMillis) start-time)]
                    (stage-record-edit file-path ns-sym
                                       {:unit-test-result (extract-unit-summary unit-result)
                                        :gen-test-result (extract-gen-summary gen-result)
                                        :decision :continue :feedback @feedback})
                    (when dense?
                      (when-let [dense-line (format-dense-success
                                             {:unit-result unit-result :gen-result gen-result
                                              :compliance-result compliance-result :elapsed-ms elapsed-ms})]
                        (swap! feedback conj dense-line)))
                    (when (stage-should-review? config)
                      (when-let [review-text (stage-review config unit-result)]
                        (swap! feedback conj review-text)))
                    {:success true :feedback (vec @feedback)}))))))))))

(defn process-mcp-edit!
  "Process an MCP edit (clojure_replace) through the same pipeline as PostToolUse.

   This gives clojure_replace feature parity with the regular Edit hook:
   1. Reload - Reload changed namespaces via clj-reload
   2. Unit Tests - Run if test namespace exists
   3. Gen Tests - Run generative tests on schema'd functions
   4. Compliance - Check convention compliance
   5. Record - Store edit event
   6. Review - Trigger AI review if rate limit allows

   Called by bin/mcp-server after successful clojure_replace.

   Request keys:
     ::file-path - Path to the edited file
     ::config    - Configuration (merged with defaults, optional)

   Response keys:
     ::success  - true if pipeline completed without blocking issues
     ::feedback - Vector of feedback messages
     ::blocked  - true if a blocking issue occurred
     ::reason   - Reason for block (if blocked)

   Example:
     (process-mcp-edit!
       {::file-path \"/path/to/file.clj\"})
     ;; => {::success true
     ;;     ::feedback [\"✓ 5 tests, gen-tests, compliant (0.3s)\"]}"
  {:malli/schema [:=> [:cat ::mcp-edit-request] ::mcp-edit-response]}
  [{::keys [file-path config]}]
  (let [config (merge-config (or config {}))
        ns-sym (codebase/file->namespace {::codebase/file-path file-path})]
    (cond
      (not (codebase/clojure-file? {::codebase/file-path file-path}))
      {::success true ::feedback []}

      (not (seon-source-file? file-path))
      {::success true ::feedback []}

      (not ns-sym)
      {::success true ::feedback []}

      :else
      (let [feedback (atom [])
            result (run-post-edit-pipeline!
                    {::file-path file-path
                     ::ns-sym ns-sym
                     ::config config
                     ::feedback feedback
                     ::start-time (System/currentTimeMillis)})]
        (cond-> {::success (:success result)
                 ::feedback (:feedback result)}
          (:blocked result) (assoc ::blocked true)
          (:reason result) (assoc ::reason (:reason result)))))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  (require '[seon.dev.hook :as hook])

  ;; Test event processing
  (def test-event
    {:hook_event_name "PostToolUse"
     :tool_name "Edit"
     :tool_input {:file_path "/Users/sean/src/seon/src/seon/core.clj"}})

  ;; Process with default config
  (process-hook-event!
   {::event test-event
    ::config {}})

  ;; Process with custom config
  (process-hook-event!
   {::event test-event
    ::config {:repair {:enabled false}
              :tests {:unit {:enabled true :block-on-fail false}
                      :generative {:enabled false}}
              :review {:enabled true :interval-seconds 0}}})

  ;; Test PreToolUse (only repair)
  (process-hook-event!
   {::event {:hook_event_name "PreToolUse"
             :tool_name "Edit"
             :tool_input {:file_path "/tmp/test.clj"}}
    ::config {}})

  nil)
