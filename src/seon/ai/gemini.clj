(ns seon.ai.gemini
  "Native Gemini API integration for Seon.

   This namespace demonstrates the :malli/schema metadata pattern for AI-assisted
   development. All public functions have schema metadata that can be:
   - Parsed by LLMs to understand function contracts
   - Collected via `mi/collect!` for registration
   - Verified via `mi/check` for generative testing
   - Instrumented for runtime validation

   Usage:
     (require '[seon.ai.gemini :as gemini])

     ;; Basic generation
     (gemini/generate api-key \"Explain XTDB\" {})

     ;; With Google Search grounding
     (gemini/generate-with-search api-key \"Latest Clojure 1.12 features\" {})

     ;; With code execution
     (gemini/generate-with-code api-key \"Calculate first 50 primes\" {})

   Schema verification:
     (require '[malli.instrument :as mi])
     (mi/collect! {:ns 'seon.ai.gemini})
     (mi/check {:filters [(mi/-filter-ns 'seon.ai.gemini)]})

   See: docs/research/gemini-native-integration.md for API details."
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [malli.core :as m]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Definitions
;;; ---------------------------------------------------------------------------
;;
;; Schemas are defined inline for clarity and to avoid registry conflicts.
;; The :malli/schema metadata on functions uses these definitions directly.
;; For generative testing, use the local-registry function.

(def GeminiApiKey
  "Non-empty string for API authentication."
  [:string {:min 1}])

(def GeminiPrompt
  "Non-empty string for user input."
  [:string {:min 1}])

(def GeminiModel
  "Enum of supported model names."
  [:enum
   "gemini-2.5-flash"
   "gemini-2.5-pro"
   "gemini-3-flash"
   "gemini-3-pro-preview"])

(def GeminiTimeout
  "Request timeout in milliseconds (1-600 seconds)."
  [:int {:min 1000 :max 600000}])

(def GeminiTool
  "Tool configuration (google_search or code_execution)."
  [:map
   [:google_search {:optional true} :map]
   [:code_execution {:optional true} :map]])

(def GeminiThinkingLevel
  "Thinking mode intensity."
  [:enum "low" "medium" "high"])

(def GeminiOptions
  "Request options map."
  [:map
   [:model {:optional true} GeminiModel]
   [:timeout {:optional true} GeminiTimeout]
   [:tools {:optional true} [:vector GeminiTool]]
   [:thinking-level {:optional true} GeminiThinkingLevel]
   [:system-instruction {:optional true} :string]])

(def GeminiGroundingChunk
  "Web search result source."
  [:map
   [:web {:optional true}
    [:map
     [:uri {:optional true} :string]
     [:title {:optional true} :string]]]])

(def GeminiGroundingMetadata
  "Grounding metadata from Google Search."
  [:map
   [:webSearchQueries {:optional true} [:vector :string]]
   [:groundingChunks {:optional true} [:vector GeminiGroundingChunk]]])

(def GeminiCodeResult
  "Code execution result."
  [:map
   [:outcome {:optional true} :string]
   [:output {:optional true} :string]])

(def GeminiUsage
  "Token usage metadata."
  [:map
   [:promptTokenCount {:optional true} :int]
   [:candidatesTokenCount {:optional true} :int]
   [:totalTokenCount {:optional true} :int]])

(def GeminiError
  "Error response details."
  [:map
   [:status {:optional true} :int]
   [:message {:optional true} :string]
   [:exception {:optional true} :string]])

(def GeminiResponse
  "Unified response format from Gemini API."
  [:map
   [:text :string]
   [:error {:optional true} GeminiError]
   [:grounding-metadata {:optional true} [:maybe GeminiGroundingMetadata]]
   [:code-results {:optional true} [:maybe [:vector GeminiCodeResult]]]
   [:usage-metadata {:optional true} [:maybe GeminiUsage]]])

(def gemini-schemas
  "Map of all Gemini schemas for registry use.

   Use with Malli's registry options for validation/generation:
   (m/validate :gemini/response data {:registry (gemini-registry)})"
  {:gemini/api-key GeminiApiKey
   :gemini/prompt GeminiPrompt
   :gemini/model GeminiModel
   :gemini/timeout GeminiTimeout
   :gemini/tool GeminiTool
   :gemini/thinking-level GeminiThinkingLevel
   :gemini/options GeminiOptions
   :gemini/grounding-chunk GeminiGroundingChunk
   :gemini/grounding-metadata GeminiGroundingMetadata
   :gemini/code-result GeminiCodeResult
   :gemini/usage GeminiUsage
   :gemini/error GeminiError
   :gemini/response GeminiResponse})

(defn gemini-registry
  "Create a Malli registry that includes Gemini schemas.

   Returns a composite registry combining default schemas with Gemini-specific ones.
   Use this for validation and generation of Gemini types.

   Example:
     (m/validate :gemini/response data {:registry (gemini-registry)})
     (mg/generate :gemini/options {:registry (gemini-registry)})"
  []
  (merge (m/default-schemas) gemini-schemas))

;;; ---------------------------------------------------------------------------
;;; Constants
;;; ---------------------------------------------------------------------------

(def ^:const base-url
  "Gemini API base URL."
  "https://generativelanguage.googleapis.com/v1beta")

(def ^:const default-timeout-ms
  "Default request timeout in milliseconds."
  60000)

(def ^:const default-model
  "Default model for requests."
  "gemini-2.5-flash")

;;; ---------------------------------------------------------------------------
;;; Internal Helpers
;;; ---------------------------------------------------------------------------

(defn- make-url
  "Construct API URL for model and method."
  [model method]
  (format "%s/models/%s:%s" base-url model method))

(defn- request-body
  "Build request body from prompt and options."
  [prompt {:keys [tools thinking-level system-instruction]}]
  (cond-> {:contents [{:parts [{:text prompt}]}]}
    tools (assoc :tools tools)
    thinking-level (assoc-in [:generationConfig :thinkingConfig :thinkingLevel]
                             thinking-level)
    system-instruction (assoc :systemInstruction
                              {:parts [{:text system-instruction}]})))

(defn- parse-response
  "Parse Gemini API response into unified format."
  [parsed]
  (let [candidate (-> parsed :candidates first)
        parts (-> candidate :content :parts)]
    {:text (->> parts
                (filter :text)
                (map :text)
                (clojure.string/join "\n"))
     :grounding-metadata (-> candidate :groundingMetadata)
     :code-results (->> parts
                        (filter :codeExecutionResult)
                        (mapv (fn [p]
                                (select-keys (:codeExecutionResult p)
                                             [:outcome :output]))))
     :usage-metadata (:usageMetadata parsed)}))

(defn- make-error
  "Create error response map."
  ([status message]
   {:text ""
    :error {:status status :message message}})
  ([exception-message]
   {:text ""
    :error {:exception exception-message}}))

;;; ---------------------------------------------------------------------------
;;; Core API Functions
;;; ---------------------------------------------------------------------------

(defn generate
  "Generate content using Gemini API.

   Makes a synchronous HTTP request to the Gemini generateContent endpoint.
   Returns a unified response map with :text and optional metadata.

   Arguments:
     api-key - Gemini API key (non-empty string)
     prompt  - Text prompt to send (non-empty string)
     opts    - Options map:
               :model            - Model name (default: gemini-2.5-flash)
               :timeout          - Request timeout in ms (default: 60000)
               :tools            - Vector of tool configs
               :thinking-level   - \"low\", \"medium\", or \"high\"
               :system-instruction - System prompt

   Returns:
     {:text \"response text\"
      :grounding-metadata {...}  ; if google_search enabled
      :code-results [...]        ; if code_execution enabled
      :usage-metadata {...}}     ; token counts

   On error:
     {:text \"\"
      :error {:status 401 :message \"...\"}}"
  {:malli/schema [:=> [:cat GeminiApiKey GeminiPrompt GeminiOptions]
                  GeminiResponse]}
  [api-key prompt opts]
  (let [{:keys [model timeout]
         :or {model default-model
              timeout default-timeout-ms}} opts
        url (make-url model "generateContent")
        body (request-body prompt opts)]
    (try
      (let [response (http/post url
                                {:headers {"Content-Type" "application/json"
                                           "x-goog-api-key" api-key}
                                 :body (json/generate-string body)
                                 :timeout timeout
                                 :as :text})
            status (:status response)]
        (if (not= 200 status)
          (do
            (log/error "Gemini API error" {:status status
                                           :body (:body response)})
            (make-error status (:body response)))
          (-> (:body response)
              (json/parse-string true)
              parse-response)))
      (catch Exception e
        (log/error e "Gemini API request failed" {:model model})
        (make-error (ex-message e))))))

(defn generate-with-search
  "Generate content with Google Search grounding.

   Convenience wrapper that enables the google_search tool for web-grounded
   responses. Returns sources in :grounding-metadata.

   Arguments:
     api-key - Gemini API key
     prompt  - Text prompt (often a question about current events)
     opts    - Additional options (same as generate, but :tools is preset)

   Returns:
     {:text \"response with citations\"
      :grounding-metadata {:webSearchQueries [...] :groundingChunks [...]}
      ...}

   Example:
     (generate-with-search api-key \"Latest XTDB v2 release notes\" {})"
  {:malli/schema [:=> [:cat GeminiApiKey GeminiPrompt GeminiOptions]
                  GeminiResponse]}
  [api-key prompt opts]
  (generate api-key prompt (assoc opts :tools [{:google_search {}}])))

(defn generate-with-code
  "Generate content with Python code execution.

   Convenience wrapper that enables the code_execution tool. Gemini can
   write and execute Python code to compute results.

   Arguments:
     api-key - Gemini API key
     prompt  - Prompt that may require calculation
     opts    - Additional options (same as generate, but :tools is preset)

   Returns:
     {:text \"explanation\"
      :code-results [{:outcome \"OUTCOME_OK\" :output \"...\"}]
      ...}

   Example:
     (generate-with-code api-key \"Calculate factorial of 100\" {})"
  {:malli/schema [:=> [:cat GeminiApiKey GeminiPrompt GeminiOptions]
                  GeminiResponse]}
  [api-key prompt opts]
  (generate api-key prompt (assoc opts :tools [{:code_execution {}}])))

;;; ---------------------------------------------------------------------------
;;; REPL Convenience Functions
;;; ---------------------------------------------------------------------------

(def ^:dynamic *api-key*
  "Dynamic var for default API key. Can be bound or set for REPL convenience.
   Falls back to GEMINI_API_KEY environment variable if nil."
  nil)

(defn- get-api-key
  "Get API key from explicit arg, dynamic var, or environment."
  [explicit-key]
  (or explicit-key
      *api-key*
      (System/getenv "GEMINI_API_KEY")))

(defn ask
  "Ask Gemini a question. Simplest possible interface.

   Uses API key from:
   1. Explicit :api-key option
   2. *api-key* dynamic var
   3. GEMINI_API_KEY environment variable

   Arguments:
     prompt - Question or instruction
     opts   - Optional map, may include :api-key to override default

   Returns:
     Response map (see generate for format)

   Throws:
     ExceptionInfo if no API key is available

   Example:
     (ask \"What is the capital of France?\")
     (ask \"Explain XTDB\" {:model \"gemini-3-pro-preview\"})"
  {:malli/schema [:=> [:cat GeminiPrompt GeminiOptions]
                  GeminiResponse]}
  [prompt opts]
  (let [key (get-api-key (:api-key opts))]
    (when-not key
      (throw (ex-info "No Gemini API key available. Set GEMINI_API_KEY env var or pass :api-key in opts."
                      {:reason :missing-api-key})))
    (generate key prompt (dissoc opts :api-key))))

(defn search
  "Search the web using Gemini with Google Search grounding.

   Convenience function that combines ask with generate-with-search.

   Arguments:
     query - Search query or question
     opts  - Optional map (same as ask)

   Returns:
     {:text \"answer with citations\"
      :grounding-metadata {...sources...}}

   Example:
     (search \"Latest Clojure 1.12 features\" {})"
  {:malli/schema [:=> [:cat GeminiPrompt GeminiOptions]
                  GeminiResponse]}
  [query opts]
  (let [key (get-api-key (:api-key opts))]
    (when-not key
      (throw (ex-info "No Gemini API key available. Set GEMINI_API_KEY env var or pass :api-key in opts."
                      {:reason :missing-api-key})))
    (generate-with-search key query (dissoc opts :api-key))))

(defn calculate
  "Perform calculation using Gemini's Python code execution.

   Convenience function that combines ask with generate-with-code.

   Arguments:
     prompt - Calculation or coding task
     opts   - Optional map (same as ask)

   Returns:
     {:text \"explanation\"
      :code-results [{:outcome \"OUTCOME_OK\" :output \"result\"}]}

   Example:
     (calculate \"What is the 100th Fibonacci number?\" {})"
  {:malli/schema [:=> [:cat GeminiPrompt GeminiOptions]
                  GeminiResponse]}
  [prompt opts]
  (let [key (get-api-key (:api-key opts))]
    (when-not key
      (throw (ex-info "No Gemini API key available. Set GEMINI_API_KEY env var or pass :api-key in opts."
                      {:reason :missing-api-key})))
    (generate-with-code key prompt (dissoc opts :api-key))))

;;; ---------------------------------------------------------------------------
;;; Schema Access Utilities
;;; ---------------------------------------------------------------------------

(defn registered-schemas
  "Return the map of registered Gemini schemas.

   Useful for debugging and REPL exploration.

   Example:
     (registered-schemas)
     => {:gemini/api-key [...], :gemini/response [...], ...}"
  []
  gemini-schemas)

(comment
  ;; REPL exploration

  ;; Check registered schemas
  (registered-schemas)

  ;; Test schema generation (use local registry)
  (require '[malli.generator :as mg])
  (mg/generate GeminiPrompt)
  (mg/generate GeminiOptions)
  (mg/generate GeminiResponse)

  ;; Or with registry for keyword lookup
  (mg/generate :gemini/prompt {:registry (gemini-registry)})
  (mg/generate :gemini/options {:registry (gemini-registry)})

  ;; Check function schemas are accessible via metadata
  (:malli/schema (meta #'generate))
  (:malli/schema (meta #'generate-with-search))

  ;; Collect and verify schemas
  (require '[malli.instrument :as mi])
  (mi/collect! {:ns 'seon.ai.gemini})
  (keys (get (m/function-schemas) 'seon.ai.gemini))

  ;; Run generative tests
  (mi/check {:filters [(mi/-filter-ns 'seon.ai.gemini)]
             :num-tests 10})

  ;; Manual API test (requires API key)
  (generate (System/getenv "GEMINI_API_KEY")
            "Say hello in three languages"
            {:model "gemini-2.5-flash"})

  nil)
