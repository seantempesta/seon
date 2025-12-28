(ns seon.ai.gemini
  "Native Clojure client for the Gemini API.

   Provides synchronous HTTP access to Google's Gemini language models with
   support for text generation, Google Search grounding, and Python code
   execution.

   All public functions include :malli/schema metadata for contract
   specification, enabling generative testing via malli.instrument/check
   and runtime validation via malli.instrument/instrument!.

   Quick start:

     (require '[seon.ai.gemini :as gemini])

     ;; Set API key (or use GEMINI_API_KEY env var)
     (binding [gemini/*api-key* \"your-key\"]
       (gemini/ask \"What is the capital of France?\" {}))

     ;; With Google Search grounding
     (gemini/search \"Latest Clojure 1.12 features\" {})

     ;; With Python code execution
     (gemini/calculate \"What is the 100th Fibonacci number?\" {})

   Schema verification:

     (require '[malli.instrument :as mi])
     (mi/collect! {:ns 'seon.ai.gemini})
     (mi/check {:filters [(mi/-filter-ns 'seon.ai.gemini)]})"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [hato.client :as http]
   [malli.core :as m]
   [malli.registry :as mr]
   [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Register all Gemini domain schemas in the global mutable registry.
;; These schemas are available project-wide as :gemini/* keywords.

(mr/set-default-registry!
 (mr/composite-registry
  (m/default-schemas)
  {;; Primitive types
   :gemini/api-key
   [:string {:min 1
             :description "Non-empty Gemini API authentication key"}]

   :gemini/prompt
   [:string {:min 1
             :description "Non-empty text prompt for generation"}]

   :gemini/model
   [:enum
    "gemini-2.5-flash"
    "gemini-2.5-pro"
    "gemini-3-flash"
    "gemini-3-pro-preview"]

   :gemini/timeout
   [:int {:min 1000 :max 600000
          :description "Request timeout in milliseconds (1-600 seconds)"}]

   :gemini/thinking-level
   [:enum "low" "medium" "high"]

     ;; Tool configuration
   :gemini/tool
   [:map
    [:google_search {:optional true} :map]
    [:code_execution {:optional true} :map]]

     ;; Request options
   :gemini/options
   [:map
    [:model {:optional true} :gemini/model]
    [:timeout {:optional true} :gemini/timeout]
    [:tools {:optional true} [:vector :gemini/tool]]
    [:thinking-level {:optional true} :gemini/thinking-level]
    [:system-instruction {:optional true} :string]
    [:api-key {:optional true} :gemini/api-key]]

     ;; Response components
   :gemini/grounding-chunk
   [:map
    [:web {:optional true}
     [:map
      [:uri {:optional true} :string]
      [:title {:optional true} :string]]]]

   :gemini/grounding-metadata
   [:map
    [:webSearchQueries {:optional true} [:vector :string]]
    [:groundingChunks {:optional true} [:vector :gemini/grounding-chunk]]]

   :gemini/code-result
   [:map
    [:outcome {:optional true} :string]
    [:output {:optional true} :string]]

   :gemini/usage
   [:map
    [:promptTokenCount {:optional true} :int]
    [:candidatesTokenCount {:optional true} :int]
    [:totalTokenCount {:optional true} :int]]

   :gemini/error
   [:map
    [:status {:optional true} :int]
    [:message {:optional true} :string]
    [:exception {:optional true} :string]]

     ;; Unified response
   :gemini/response
   [:map
    [:text :string]
    [:error {:optional true} :gemini/error]
    [:grounding-metadata {:optional true} [:maybe :gemini/grounding-metadata]]
    [:code-results {:optional true} [:maybe [:vector :gemini/code-result]]]
    [:usage-metadata {:optional true} [:maybe :gemini/usage]]]}))

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
  "gemini-2.5-flash")

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
   thinking configuration, and system instructions."
  [prompt {:keys [tools thinking-level system-instruction]}]
  (cond-> {:contents [{:parts [{:text prompt}]}]}
    tools
    (assoc :tools tools)

    thinking-level
    (assoc-in [:generationConfig :thinkingConfig :thinkingLevel] thinking-level)

    system-instruction
    (assoc :systemInstruction {:parts [{:text system-instruction}]})))

(defn- parse-api-response
  "Parse Gemini API JSON response into unified format.

   Extracts text content, grounding metadata, code execution results,
   and token usage from the raw API response."
  [parsed]
  (let [candidate (-> parsed :candidates first)
        parts     (-> candidate :content :parts)]
    {:text              (->> parts
                             (filter :text)
                             (map :text)
                             (str/join "\n"))
     :grounding-metadata (:groundingMetadata candidate)
     :code-results       (->> parts
                              (filter :codeExecutionResult)
                              (mapv (fn [p]
                                      (select-keys (:codeExecutionResult p)
                                                   [:outcome :output]))))
     :usage-metadata     (:usageMetadata parsed)}))

(defn- make-error-response
  "Create a standardized error response map."
  ([status message]
   {:text  ""
    :error {:status status :message message}})
  ([exception-message]
   {:text  ""
    :error {:exception exception-message}}))

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
;;; Core API
;;; ---------------------------------------------------------------------------

(defn generate
  "Generate content using the Gemini API.

   Makes a synchronous HTTP request to the Gemini generateContent endpoint
   and returns a unified response map.

   Arguments:
     api-key - Gemini API key (non-empty string)
     prompt  - Text prompt to send (non-empty string)
     opts    - Options map:
               :model             - Model name (default: gemini-2.5-flash)
               :timeout           - Request timeout in ms (default: 60000)
               :tools             - Vector of tool configs for search/code
               :thinking-level    - \"low\", \"medium\", or \"high\"
               :system-instruction - System prompt for context

   Returns:
     Success: {:text \"response text\"
               :grounding-metadata {...}  ; if google_search enabled
               :code-results [...]        ; if code_execution enabled
               :usage-metadata {...}}     ; token counts

     Error:   {:text \"\"
               :error {:status 401 :message \"...\"}}"
  {:malli/schema [:=> [:cat :gemini/api-key :gemini/prompt :gemini/options]
                  :gemini/response]}
  [api-key prompt opts]
  (let [{:keys [model timeout]
         :or   {model   default-model
                timeout default-timeout-ms}} opts
        url  (build-url model "generateContent")
        body (build-request-body prompt opts)]
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

(defn generate-with-search
  "Generate content with Google Search grounding.

   Enables the google_search tool to provide web-grounded responses
   with source citations. Useful for current events, recent documentation,
   or factual queries requiring authoritative sources.

   Arguments:
     api-key - Gemini API key
     prompt  - Text prompt (often a question about current events)
     opts    - Options map (same as generate, :tools is preset)

   Returns:
     {:text \"response with citations\"
      :grounding-metadata {:webSearchQueries [...]
                           :groundingChunks [{:web {:uri ... :title ...}}]}
      ...}

   Example:
     (generate-with-search api-key \"Latest XTDB v2 release notes\" {})"
  {:malli/schema [:=> [:cat :gemini/api-key :gemini/prompt :gemini/options]
                  :gemini/response]}
  [api-key prompt opts]
  (generate api-key prompt (assoc opts :tools [{:google_search {}}])))

(defn generate-with-code
  "Generate content with Python code execution.

   Enables the code_execution tool, allowing Gemini to write and execute
   Python code to compute results. Useful for calculations, data analysis,
   or any task requiring programmatic computation.

   Arguments:
     api-key - Gemini API key
     prompt  - Prompt that may require calculation
     opts    - Options map (same as generate, :tools is preset)

   Returns:
     {:text \"explanation\"
      :code-results [{:outcome \"OUTCOME_OK\" :output \"...\"}]
      ...}

   Example:
     (generate-with-code api-key \"Calculate factorial of 100\" {})"
  {:malli/schema [:=> [:cat :gemini/api-key :gemini/prompt :gemini/options]
                  :gemini/response]}
  [api-key prompt opts]
  (generate api-key prompt (assoc opts :tools [{:code_execution {}}])))

;;; ---------------------------------------------------------------------------
;;; Convenience Functions
;;; ---------------------------------------------------------------------------

(defn- require-api-key!
  "Resolve and validate API key, throwing if unavailable."
  [opts]
  (let [key (resolve-api-key (:api-key opts))]
    (when-not key
      (throw (ex-info "No Gemini API key available. Set GEMINI_API_KEY env var, bind *api-key*, or pass :api-key in opts."
                      {:reason :missing-api-key})))
    key))

(defn ask
  "Ask Gemini a question using the simplest possible interface.

   Resolves the API key automatically from (in priority order):
   1. :api-key option
   2. *api-key* dynamic var
   3. GEMINI_API_KEY environment variable

   Arguments:
     prompt - Question or instruction (non-empty string)
     opts   - Options map (same as generate, plus optional :api-key)

   Returns:
     Response map (see generate for format)

   Throws:
     ExceptionInfo with :reason :missing-api-key if no key available

   Examples:
     (ask \"What is the capital of France?\" {})
     (ask \"Explain XTDB\" {:model \"gemini-3-pro-preview\"})"
  {:malli/schema [:=> [:cat :gemini/prompt :gemini/options]
                  :gemini/response]}
  [prompt opts]
  (let [key (require-api-key! opts)]
    (generate key prompt (dissoc opts :api-key))))

(defn search
  "Search the web using Gemini with Google Search grounding.

   Combines the convenience of ask with generate-with-search.
   Returns grounded responses with source citations.

   Arguments:
     query - Search query or question (non-empty string)
     opts  - Options map (same as ask)

   Returns:
     {:text \"answer with citations\"
      :grounding-metadata {:webSearchQueries [...]
                           :groundingChunks [...]}}

   Example:
     (search \"Latest Clojure 1.12 features\" {})"
  {:malli/schema [:=> [:cat :gemini/prompt :gemini/options]
                  :gemini/response]}
  [query opts]
  (let [key (require-api-key! opts)]
    (generate-with-search key query (dissoc opts :api-key))))

(defn calculate
  "Perform calculation using Gemini's Python code execution.

   Combines the convenience of ask with generate-with-code.
   Gemini will write and execute Python code to compute the result.

   Arguments:
     prompt - Calculation or coding task (non-empty string)
     opts   - Options map (same as ask)

   Returns:
     {:text \"explanation\"
      :code-results [{:outcome \"OUTCOME_OK\" :output \"result\"}]}

   Example:
     (calculate \"What is the 100th Fibonacci number?\" {})"
  {:malli/schema [:=> [:cat :gemini/prompt :gemini/options]
                  :gemini/response]}
  [prompt opts]
  (let [key (require-api-key! opts)]
    (generate-with-code key prompt (dissoc opts :api-key))))

;;; ---------------------------------------------------------------------------
;;; Schema Introspection
;;; ---------------------------------------------------------------------------

(def gemini-schema-keys
  "Set of all registered Gemini schema keywords."
  #{:gemini/api-key
    :gemini/prompt
    :gemini/model
    :gemini/timeout
    :gemini/thinking-level
    :gemini/tool
    :gemini/options
    :gemini/grounding-chunk
    :gemini/grounding-metadata
    :gemini/code-result
    :gemini/usage
    :gemini/error
    :gemini/response})

(defn schema
  "Retrieve a registered Gemini schema by keyword.

   Arguments:
     k - Schema keyword (e.g., :gemini/response)

   Returns:
     The Malli schema, or nil if not found

   Example:
     (schema :gemini/options)
     ;; => [:map [:model {:optional true} :gemini/model] ...]"
  [k]
  (when (gemini-schema-keys k)
    (m/schema k)))

(comment
  ;; REPL exploration

  ;; View registered schemas
  gemini-schema-keys
  (schema :gemini/response)
  (m/form (schema :gemini/options))

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate :gemini/prompt)
  (mg/generate :gemini/options)
  (mg/generate :gemini/response)

  ;; Validate data
  (m/validate :gemini/api-key "test-key")
  (m/validate :gemini/model "gemini-2.5-flash")
  (m/validate :gemini/options {:model "gemini-2.5-pro" :timeout 30000})

  ;; Collect and verify function schemas
  (require '[malli.instrument :as mi])
  (mi/collect! {:ns 'seon.ai.gemini})
  (keys (get (m/function-schemas) 'seon.ai.gemini))

  ;; View function schema
  (get-in (m/function-schemas) ['seon.ai.gemini 'generate])

  ;; Run generative tests (note: HTTP functions will fail without mocking)
  ;; (mi/check {:filters [(mi/-filter-ns 'seon.ai.gemini)]})

  ;; Manual API test
  (generate (System/getenv "GEMINI_API_KEY")
            "Say hello in three languages"
            {:model "gemini-2.5-flash"})

  ;; Using convenience functions
  (binding [*api-key* (System/getenv "GEMINI_API_KEY")]
    (ask "What is 2+2?" {}))

  nil)
