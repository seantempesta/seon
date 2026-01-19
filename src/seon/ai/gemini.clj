(ns seon.ai.gemini
  "Native Clojure client for the Gemini API.

   Provides synchronous HTTP access to Google's Gemini language models with
   support for text generation, Google Search grounding, and Python code
   execution.

   This namespace follows the provider pattern established in seon.ai.claude:
   - Common schemas (prompt, tokens) reference seon.ai base schemas
   - Gemini-specific schemas (model, thinking-level, grounding) are local

   Public functions use map-based APIs with namespaced keys:
   - Input:  {::prompt \"...\" ::model \"...\"}
   - Output: {::text \"...\" ::usage {...}}

   Quick start:

     (require '[seon.ai.gemini :as gemini])

     ;; Simple question (uses GEMINI_API_KEY env var)
     (gemini/ask {::gemini/prompt \"What is the capital of France?\"})

     ;; With explicit options
     (gemini/ask {::gemini/prompt \"Explain XTDB\"
                  ::gemini/model \"gemini-3-pro-preview\"})

     ;; With Google Search grounding
     (gemini/search {::gemini/prompt \"Latest Clojure 1.12 features\"})

     ;; With Python code execution
     (gemini/calculate {::gemini/prompt \"What is the 100th Fibonacci number?\"})

   Schema verification:

     (require '[malli.instrument :as mi])
     (mi/collect! {:ns 'seon.ai.gemini})
     (mi/check {:filters [(mi/-filter-ns 'seon.ai.gemini)]})"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [hato.client :as http]
   [malli.core :as m]
   [seon.ai :as ai]
   [seon.schema :as schema]
   [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Register Gemini domain schemas in the global registry.
;; Using `::` auto-namespaces to `:seon.ai.gemini/*` keywords.
;; Each registration is a separate form for easy LLM editing.
;;
;; Base schemas from seon.ai that can be referenced:
;;   ::ai/prompt        - Initial prompt (string, min 1)
;;   ::ai/input-tokens  - Input token count (int, min 0)
;;   ::ai/output-tokens - Output token count (int, min 0)
;;   ::ai/cost-usd      - Cost in USD (double, min 0.0)
;;
;; Gemini-specific schemas are registered with ::gemini/ namespace.

;; ---- Gemini-specific primitives ----

(schema/register! ::api-key
                  [:string {:min 1
                            :description "Non-empty Gemini API authentication key"}])

;; Note: ::prompt is a local alias that mirrors ::ai/prompt.
;; We keep it as ::gemini/prompt for API consistency - callers use
;; (gemini/ask {::gemini/prompt "..."}) not {::ai/prompt "..."}.
;; The schema is identical to ::ai/prompt.
(schema/register! ::prompt
                  [:string {:min 1
                            :description "Non-empty text prompt for generation"}])

(schema/register! ::model
                  [:enum {:description "Gemini model identifier"}
                   "gemini-3-flash-preview"
                   "gemini-3-pro-preview"])

(schema/register! ::timeout
                  [:int {:min 1000 :max 600000
                         :description "Request timeout in milliseconds (1-600 seconds)"}])

;; ---- Gemini-specific: thinking configuration ----
(schema/register! ::thinking-level
                  [:enum {:description "Thinking depth: low=fast, high=thorough"}
                   "low" "medium" "high"])

;; ---- Gemini-specific: tool configuration ----
(schema/register! ::tool
                  [:map {:description "Gemini tool configuration"}
                   [:google_search {:optional true} :map]
                   [:code_execution {:optional true} :map]])

;; Internal options (non-namespaced, for private helpers)
(schema/register! ::internal-options
                  [:map
                   [:model {:optional true} ::model]
                   [:timeout {:optional true} ::timeout]
                   [:tools {:optional true} [:vector ::tool]]
                   [:thinking-level {:optional true} ::thinking-level]
                   [:system-instruction {:optional true} :string]])

;; ---- Request schemas for public API (namespaced keys) ----
(schema/register! ::system-instruction
                  [:string {:description "System instruction for context"}])

(schema/register! ::generate-request
                  [:map
                   [::api-key ::api-key]
                   [::prompt ::prompt]
                   [::model {:optional true} ::model]
                   [::timeout {:optional true} ::timeout]
                   [::tools {:optional true} [:vector ::tool]]
                   [::thinking-level {:optional true} ::thinking-level]
                   [::system-instruction {:optional true} ::system-instruction]])

(schema/register! ::ask-request
                  [:map
                   [::prompt ::prompt]
                   [::model {:optional true} ::model]
                   [::timeout {:optional true} ::timeout]
                   [::thinking-level {:optional true} ::thinking-level]
                   [::system-instruction {:optional true} ::system-instruction]
                   [::api-key {:optional true} ::api-key]])

(schema/register! ::search-request
                  [:map
                   [::prompt ::prompt]
                   [::model {:optional true} ::model]
                   [::timeout {:optional true} ::timeout]
                   [::thinking-level {:optional true} ::thinking-level]
                   [::api-key {:optional true} ::api-key]])

(schema/register! ::calculate-request
                  [:map
                   [::prompt ::prompt]
                   [::model {:optional true} ::model]
                   [::timeout {:optional true} ::timeout]
                   [::thinking-level {:optional true} ::thinking-level]
                   [::api-key {:optional true} ::api-key]])

;; Code Review Schema (plain text advisory)
(schema/register! ::conventions
                  [:string {:description "Project conventions (cached in system instruction)"}])

(schema/register! ::code-review-request
                  [:map
                   [::prompt ::prompt]
                   [::code :string]
                   [::conventions {:optional true} ::conventions]
                   [::context {:optional true} :string]
                   [::model {:optional true} ::model]
                   [::timeout {:optional true} ::timeout]
                   [::api-key {:optional true} ::api-key]])

;; ---- Token tracking ----
;; Note: These use Gemini API naming conventions (promptTokenCount, etc.)
;; The base schemas ::ai/input-tokens and ::ai/output-tokens are for
;; normalized/aggregated tracking. This schema is for raw Gemini API responses.
(schema/register! ::tokens
                  [:map {:description "Token usage counts from Gemini API"}
                   [:prompt {:optional true} :int]
                   [:response {:optional true} :int]
                   [:cached {:optional true} :int]])

;; Code review response with full context for training data
(schema/register! ::code-review-response
                  [:map {:description "Full code review result with context for training"}
                   [::text :string]
                   [::success :boolean]
                   [::system-instruction {:optional true} :string]
                   [::user-prompt {:optional true} :string]
                   [::code {:optional true} :string]
                   [::tokens {:optional true} ::tokens]
                   [::error {:optional true} :string]])

;; ---- Response components ----
;; Note: ::ai/error in seon.ai uses {::ai/message ::ai/code ::ai/data} structure.
;; Gemini's error structure is different (HTTP status, exception), so we keep
;; a Gemini-specific error schema. If persisting to XTDB, convert to ::ai/error.
(schema/register! ::text
                  [:string {:description "Generated text response"}])

(schema/register! ::status
                  [:int {:description "HTTP status code"}])

(schema/register! ::message
                  [:string {:description "Error or status message"}])

(schema/register! ::exception
                  [:string {:description "Exception message"}])

(schema/register! ::error
                  [:map {:description "Gemini API error details"}
                   [::status {:optional true} ::status]
                   [::message {:optional true} ::message]
                   [::exception {:optional true} ::exception]])

;; ---- Gemini-specific: Google Search grounding ----
;; These schemas capture grounding metadata from web search-enabled requests.
(schema/register! ::grounding-chunk
                  [:map {:description "Single grounding source from web search"}
                   [:web {:optional true}
                    [:map
                     [:uri {:optional true} :string]
                     [:title {:optional true} :string]]]])

(schema/register! ::grounding-metadata
                  [:map {:description "Web search grounding info (Gemini-specific)"}
                   [:webSearchQueries {:optional true} [:vector :string]]
                   [:groundingChunks {:optional true} [:vector ::grounding-chunk]]])

;; ---- Gemini-specific: code execution ----
(schema/register! ::code-result
                  [:map {:description "Python code execution result (Gemini-specific)"}
                   [:outcome {:optional true} :string]
                   [:output {:optional true} :string]])

;; ---- Gemini-specific: usage metadata ----
;; Uses Gemini API naming (camelCase). For normalized tracking, use ::ai/input-tokens etc.
(schema/register! ::usage
                  [:map {:description "Token usage from Gemini API response"}
                   [:promptTokenCount {:optional true} :int]
                   [:candidatesTokenCount {:optional true} :int]
                   [:totalTokenCount {:optional true} :int]])

;; ---- Unified response schema ----
(schema/register! ::response
                  [:map {:description "Unified Gemini API response"}
                   [::text ::text]
                   [::error {:optional true} ::error]
                   [::grounding-metadata {:optional true} [:maybe ::grounding-metadata]]
                   [::code-results {:optional true} [:maybe [:vector ::code-result]]]
                   [::usage {:optional true} [:maybe ::usage]]])

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:const base-url
  "Gemini API base URL for generateContent requests."
  "https://generativelanguage.googleapis.com/v1beta")

(def ^:const default-timeout-ms
  "Default HTTP request timeout in milliseconds."
  60000)

(def ^:const default-model
  "Default Gemini model for text generation."
  "gemini-3-flash-preview")

;;; ---------------------------------------------------------------------------
;;; Dynamic Configuration
;;; ---------------------------------------------------------------------------

(def ^:dynamic *api-key*
  "Dynamic var for the default API key.

   Bind this in tests or REPL sessions to avoid passing the key explicitly.
   Falls back to GEMINI_API_KEY environment variable if nil."
  nil)

;;; ---------------------------------------------------------------------------
;;; HTTP Client (Private)
;;; ---------------------------------------------------------------------------

(defn- build-url
  "Construct the Gemini API URL for a model and method."
  [model method]
  (format "%s/models/%s:%s" base-url model method))

(defn- build-request-body
  "Build the JSON request body from prompt and options.

   Constructs the Gemini API request format with optional tools,
   thinking configuration, system instructions, and structured output.

   For structured output (JSON mode), pass :response-schema with a JSON Schema map."
  [prompt {:keys [tools thinking-level system-instruction response-schema]}]
  (cond-> {:contents [{:parts [{:text prompt}]}]}
    tools
    (assoc :tools tools)

    thinking-level
    (assoc-in [:generationConfig :thinkingConfig :thinkingLevel] thinking-level)

    response-schema
    (-> (assoc-in [:generationConfig :responseMimeType] "application/json")
        (assoc-in [:generationConfig :responseSchema] response-schema))

    system-instruction
    (assoc :systemInstruction {:parts [{:text system-instruction}]})))

(defn- parse-api-response
  "Parse Gemini API JSON response into unified format with namespaced keys."
  [parsed]
  (let [candidate (-> parsed :candidates first)
        parts     (-> candidate :content :parts)]
    {::text               (->> parts
                               (filter :text)
                               (map :text)
                               (str/join "\n"))
     ::grounding-metadata (:groundingMetadata candidate)
     ::code-results       (->> parts
                               (filter :codeExecutionResult)
                               (mapv (fn [p]
                                       (select-keys (:codeExecutionResult p)
                                                    [:outcome :output]))))
     ::usage              (:usageMetadata parsed)}))

(defn- make-error-response
  "Create a standardized error response map with namespaced keys."
  ([status message]
   {::text  ""
    ::error {::status status ::message message}})
  ([exception-message]
   {::text  ""
    ::error {::exception exception-message}}))

(defn- resolve-api-key
  "Resolve API key from explicit value, dynamic var, or environment.

   Priority:
   1. Explicitly passed key
   2. *api-key* dynamic var
   3. GEMINI_API_KEY environment variable"
  [explicit-key]
  (or explicit-key
      *api-key*
      (System/getenv "GEMINI_API_KEY")))

;;; ---------------------------------------------------------------------------
;;; Private Implementation
;;; ---------------------------------------------------------------------------

(defn- generate*
  "Internal: Execute Gemini API request with positional args."
  [api-key prompt {:keys [model timeout tools thinking-level system-instruction response-schema]}]
  (let [model   (or model default-model)
        timeout (or timeout default-timeout-ms)
        url     (build-url model "generateContent")
        body    (build-request-body prompt {:tools tools
                                            :thinking-level thinking-level
                                            :system-instruction system-instruction
                                            :response-schema response-schema})]
    (try
      (let [response (http/post url
                                {:headers {"Content-Type"  "application/json"
                                           "x-goog-api-key" api-key}
                                 :body    (json/generate-string body)
                                 :timeout timeout
                                 :as      :text})
            status   (:status response)]
        (if (= 200 status)
          (-> (:body response)
              (json/parse-string true)
              parse-api-response)
          (do
            (log/error "Gemini API error" {:status status
                                           :body   (:body response)})
            (make-error-response status (:body response)))))
      (catch Exception e
        (log/error e "Gemini API request failed" {:model model})
        (make-error-response (ex-message e))))))

(defn- resolve-api-key!
  "Resolve and validate API key, throwing if unavailable."
  [explicit-key]
  (let [key (resolve-api-key explicit-key)]
    (when-not key
      (throw (ex-info "No Gemini API key available. Set GEMINI_API_KEY env var, bind *api-key*, or pass ::api-key in request."
                      {:reason :missing-api-key})))
    key))

;;; ---------------------------------------------------------------------------
;;; Public API (map in, map out with namespaced keys)
;;; ---------------------------------------------------------------------------

(defn generate
  "Generate content using the Gemini API.

   Takes a request map with namespaced keys, returns a response map
   with namespaced keys.

   Request keys:
     ::api-key            - Required. Gemini API key
     ::prompt             - Required. Text prompt to send
     ::model              - Optional. Model name (default: gemini-3-flash-preview)
     ::timeout            - Optional. Request timeout in ms (default: 60000)
     ::tools              - Optional. Vector of tool configs
     ::thinking-level     - Optional. \"low\", \"medium\", or \"high\"
     ::system-instruction - Optional. System prompt for context

   Response keys:
     ::text               - Generated text response
     ::error              - Error info if request failed
     ::grounding-metadata - Search grounding info (if tools include google_search)
     ::code-results       - Code execution results (if tools include code_execution)
     ::usage              - Token usage metadata

   Example:
     (generate {::api-key \"...\" ::prompt \"Hello\" ::model \"gemini-3-pro-preview\"})"
  {:malli/schema [:=> [:cat ::generate-request] ::response]}
  [{::keys [api-key prompt model timeout tools thinking-level system-instruction]}]
  (generate* api-key prompt {:model model
                             :timeout timeout
                             :tools tools
                             :thinking-level thinking-level
                             :system-instruction system-instruction}))

(defn ask
  "Ask Gemini a question. Simplest interface for text generation.

   API key is resolved automatically from (in priority order):
   1. ::api-key in request
   2. *api-key* dynamic var
   3. GEMINI_API_KEY environment variable

   Request keys:
     ::prompt             - Required. Question or instruction
     ::model              - Optional. Model name
     ::timeout            - Optional. Request timeout in ms
     ::thinking-level     - Optional. \"low\", \"medium\", or \"high\"
     ::system-instruction - Optional. System prompt for context
     ::api-key            - Optional. Explicit API key

   Example:
     (ask {::prompt \"What is the capital of France?\"})
     (ask {::prompt \"Explain XTDB\" ::model \"gemini-3-pro-preview\"})"
  {:malli/schema [:=> [:cat ::ask-request] ::response]}
  [{::keys [prompt model timeout thinking-level system-instruction api-key]}]
  (let [key (resolve-api-key! api-key)]
    (generate* key prompt {:model model
                           :timeout timeout
                           :thinking-level thinking-level
                           :system-instruction system-instruction})))

(defn search
  "Search the web using Gemini with Google Search grounding.

   Returns grounded responses with source citations.
   API key is resolved automatically (see `ask` for priority).

   Request keys:
     ::prompt         - Required. Search query or question
     ::model          - Optional. Model name
     ::timeout        - Optional. Request timeout in ms
     ::thinking-level - Optional. \"low\", \"medium\", or \"high\"
     ::api-key        - Optional. Explicit API key

   Response includes ::grounding-metadata with search sources.

   Example:
     (search {::prompt \"Latest Clojure 1.12 features\"})"
  {:malli/schema [:=> [:cat ::search-request] ::response]}
  [{::keys [prompt model timeout thinking-level api-key]}]
  (let [key (resolve-api-key! api-key)]
    (generate* key prompt {:model model
                           :timeout timeout
                           :thinking-level thinking-level
                           :tools [{:google_search {}}]})))

(defn calculate
  "Perform calculation using Gemini's Python code execution.

   Gemini will write and execute Python code to compute the result.
   API key is resolved automatically (see `ask` for priority).

   Request keys:
     ::prompt         - Required. Calculation or coding task
     ::model          - Optional. Model name
     ::timeout        - Optional. Request timeout in ms
     ::thinking-level - Optional. \"low\", \"medium\", or \"high\"
     ::api-key        - Optional. Explicit API key

   Response includes ::code-results with execution output.

   Example:
     (calculate {::prompt \"What is the 100th Fibonacci number?\"})"
  {:malli/schema [:=> [:cat ::calculate-request] ::response]}
  [{::keys [prompt model timeout thinking-level api-key]}]
  (let [key (resolve-api-key! api-key)]
    (generate* key prompt {:model model
                           :timeout timeout
                           :thinking-level thinking-level
                           :tools [{:code_execution {}}]})))

(defn review-code
  "Plain text code review. Returns structured map with full context for training data.

   For optimal caching, pass static content (like project conventions) in
   ::conventions - this goes into the system instruction which Gemini caches.
   Variable content (code, test results) goes in the user prompt.

   Request keys:
     ::prompt      - Required. Description of what to review
     ::code        - Required. The code to review
     ::conventions - Optional. Project conventions (cached in system instruction)
     ::context     - Optional. Additional context (test results, new functions, etc.)
     ::model       - Optional. Model name
     ::timeout     - Optional. Request timeout in ms
     ::api-key     - Optional. Explicit API key

   Returns: Map with full context for training data:
     ::text               - The review text (or error message if failed)
     ::success            - true if review completed successfully
     ::system-instruction - The full system instruction sent to Gemini
     ::user-prompt        - The full user prompt sent to Gemini
     ::code               - The code that was reviewed (separate for easy extraction)
     ::tokens             - Token counts {:prompt N :response N :cached N}
     ::error              - Error message if failed (optional)

   Example:
     (review-code {::prompt \"Review this new function\"
                   ::code \"(defn foo [x] (+ x 1))\"
                   ::conventions (slurp \"CONVENTIONS.md\")})
     ;; => {::text \"The code looks good...\"
     ;;     ::success true
     ;;     ::system-instruction \"You are a code reviewer...\"
     ;;     ::user-prompt \"Review this new function...\"
     ;;     ::code \"(defn foo [x] (+ x 1))\"
     ;;     ::tokens {:prompt 1500 :response 800 :cached 500}}"
  [{::keys [prompt code conventions context model timeout api-key]}]
  (let [key (resolve-api-key! api-key)
        ;; System instruction: static content that Gemini can cache
        ;; Put conventions here so repeated reviews hit the cache
        system-instruction (str "You are a code reviewer for Clojure code. "
                                "Be concise. Point out real issues, not style preferences.\n\n"
                                (when conventions
                                  (str "=== PROJECT CONVENTIONS ===\n" conventions "\n\n"))
                                "Format: Start with a brief summary, then list any concerns.")
        ;; User prompt: variable content (code, test results, etc.)
        user-prompt (str prompt "\n\n"
                         (when context (str context "\n\n"))
                         "Code:\n```clojure\n" code "\n```")
        result (generate* key user-prompt
                          {:model (or model default-model)
                           :timeout (or timeout default-timeout-ms)
                           :system-instruction system-instruction})
        ;; Extract token counts
        usage (::usage result)
        tokens (when usage
                 {:prompt (:promptTokenCount usage)
                  :response (:candidatesTokenCount usage)
                  :cached (:cachedContentTokenCount usage 0)})]
    ;; Log token usage for cost visibility
    (when tokens
      (log/debug "Code review tokens" tokens))
    ;; Return structured map with full context for training data
    (if (::error result)
      (let [err (::error result)
            status (::status err)
            msg (or (::message err) (::exception err) "Unknown error")
            error-text (str "[Review failed] "
                            (when status (str "HTTP " status ": "))
                            (if (> (count msg) 200)
                              (str (subs msg 0 200) "...")
                              msg))]
        {::text error-text
         ::success false
         ::system-instruction system-instruction
         ::user-prompt user-prompt
         ::code code
         ::tokens tokens
         ::error error-text})
      {::text (or (::text result) "")
       ::success true
       ::system-instruction system-instruction
       ::user-prompt user-prompt
       ::code code
       ::tokens tokens})))

(comment
  ;; REPL exploration

  ;; View registered schemas - both base and Gemini-specific
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.ai")        ; Base schemas
  (schema/schemas-in-namespace "seon.ai.gemini") ; Gemini-specific

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate ::prompt)
  (mg/generate ::ask-request)
  (mg/generate ::response)

  ;; Validate request/response
  (m/validate ::ask-request {::prompt "Hello"})
  (m/validate ::response {::text "World"})

  ;; Collect and verify function schemas
  (require '[malli.instrument :as mi])
  (mi/collect! {:ns 'seon.ai.gemini})
  (keys (get (m/function-schemas) 'seon.ai.gemini))

  ;; View function schema
  (get-in (m/function-schemas) ['seon.ai.gemini 'ask])

  ;; Manual API test (uses GEMINI_API_KEY env var)
  (ask {::prompt "What is 2+2?"})

  ;; With explicit model
  (ask {::prompt "Explain XTDB" ::model "gemini-3-pro-preview"})

  ;; Web search with grounding
  (search {::prompt "Latest Clojure 1.12 features"})

  ;; Code execution
  (calculate {::prompt "What is the 100th Fibonacci number?"})

  nil)
