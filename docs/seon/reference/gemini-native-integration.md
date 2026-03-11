---
type: reference
status: active
tags: [reference, agent]
---
# Native Gemini API Integration for Seon

Research document for implementing native Gemini API integration in Clojure for Seon.

## Executive Summary

This document outlines how to integrate the Gemini API natively into Seon using Clojure's HTTP client libraries. The native approach offers several advantages over the existing MCP server approach: tighter REPL integration, no Node.js dependency, simpler deployment, and the ability to leverage Clojure's async capabilities.

**Recommendation**: Implement native Clojure integration. The REST API is straightforward, Seon already uses `hato` for HTTP, and the REPL-centric use cases benefit from direct integration.

---

## 1. HTTP Client Options for Clojure

### Current Pattern in Seon

Seon already uses `hato` for HTTP requests (see `seon.polymarket.api`):

```clojure
(:require [hato.client :as http]
          [cheshire.core :as json])

(http/get url
          {:query-params params
           :timeout default-timeout-ms
           :as :text
           :http-client {:redirect-policy :normal}})

```

### Recommendation: Use Hato

**Hato** is already a dependency (`hato/hato {:mvn/version "1.0.0"}`) and works well for:

- Simple synchronous requests
- JSON body handling
- Timeout configuration
- Connection pooling

For streaming SSE responses, we can use `hato` with `{:as :stream}` and process the input stream manually.

### Alternative: HTTP-Kit Client

HTTP-Kit (already a dependency for the server) has a client that supports async callbacks, which could be useful for non-blocking REPL operations:

```clojure
(require '[org.httpkit.client :as http-kit])

(http-kit/post url
  {:headers {"Content-Type" "application/json"
             "x-goog-api-key" api-key}
   :body (json/generate-string request-body)}
  (fn [{:keys [status body error]}]
    ;; Async callback
    ))

```

---

## 2. Gemini REST API Reference

### Base URL

```
https://generativelanguage.googleapis.com/v1beta/models/{model}:{method}

```

### Authentication

All requests require the `x-goog-api-key` header:

```bash
-H "x-goog-api-key: $GEMINI_API_KEY"

```

### Available Models (December 2025)

- `gemini-3-flash` - Best value, fast, cheap ($0.50/1M input, $3/1M output)
- `gemini-3-pro-preview` - Most capable, more expensive
- `gemini-2.5-flash` - Previous generation, stable
- `gemini-2.5-pro` - Previous generation pro

### Basic Generation Request

```bash
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash:generateContent" \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "contents": [{"parts": [{"text": "Explain quantum computing"}]}]
  }'

```

### Streaming Generation (SSE)

```bash
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash:streamGenerateContent?alt=sse" \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  --no-buffer \
  -d '{
    "contents": [{"parts": [{"text": "Write a story"}]}]
  }'

```

**Important**: The `?alt=sse` query parameter enables SSE streaming. Each chunk arrives as:

```
data: {"candidates": [{"content": {"parts": [{"text": "..."}]}}]}

```

### Google Search Grounding

```bash
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash:generateContent" \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "contents": [{"parts": [{"text": "What are the latest XTDB v2 features?"}]}],
    "tools": [{"google_search": {}}]
  }'

```

Response includes `groundingMetadata`:

```json
{
  "candidates": [...],
  "groundingMetadata": {
    "webSearchQueries": ["XTDB v2 features 2025"],
    "groundingChunks": [
      {"web": {"uri": "https://...", "title": "..."}}
    ]
  }
}

```

### Code Execution

```bash
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash:generateContent" \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "contents": [{"parts": [{"text": "Calculate the first 50 prime numbers"}]}],
    "tools": [{"code_execution": {}}]
  }'

```

Response includes executable code and results:

```json
{
  "candidates": [{
    "content": {
      "parts": [
        {"text": "Here's how to calculate..."},
        {"executableCode": {"language": "python", "code": "..."}},
        {"codeExecutionResult": {"outcome": "OUTCOME_OK", "output": "..."}}
      ]
    }
  }]
}

```

### Multi-Tool Requests

Combine multiple tools in a single request:

```json
{
  "contents": [{"parts": [{"text": "Search for Python 3.12 release date and calculate days since"}]}],
  "tools": [
    {"google_search": {}},
    {"code_execution": {}}
  ]
}

```

### Thinking Mode (Gemini 3)

```json
{
  "contents": [{"parts": [{"text": "Complex reasoning problem"}]}],
  "generationConfig": {
    "thinkingConfig": {
      "thinkingLevel": "low"  // "low", "medium", "high"
    }
  }
}

```

---

## 3. Proposed Namespace Structure

```
src/seon/
  ai/
    gemini/
      core.clj        ; Public API, main entry point
      client.clj      ; HTTP client, request/response handling
      schema.clj      ; Malli schemas for requests/responses
      tools.clj       ; Search, code execution, URL context
      streaming.clj   ; SSE streaming support
      cache.clj       ; Response caching (optional)
    repl.clj          ; REPL helper functions

```

### Why `seon.ai.gemini` not `seon.domains.gemini`?

Gemini is not a "domain" in Seon's architecture (like trading, health, finance). It's a cross-cutting AI utility that could be used by any domain. Placing it under `seon.ai` keeps it separate from domain-specific code.

---

## 4. Core Implementation

### `seon.ai.gemini.client`

```clojure
(ns seon.ai.gemini.client
  "Low-level HTTP client for Gemini API.

  Provides synchronous and async request handling for the Gemini REST API.
  All functions are stateless - API key is passed explicitly."
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]))

(def ^:const base-url
  "Gemini API base URL."
  "https://generativelanguage.googleapis.com/v1beta")

(def ^:const default-timeout-ms
  "Default request timeout."
  60000)

(def ^:private default-model
  "Default model for requests."
  "gemini-3-flash")

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

(defn generate
  "Generate content using Gemini API.

  Args:
    api-key - Gemini API key
    prompt - Text prompt
    opts - Optional parameters:
      :model - Model name (default: gemini-3-flash)
      :tools - Vector of tool configs [{:google_search {}} ...]
      :thinking-level - \"low\", \"medium\", or \"high\"
      :system-instruction - System prompt
      :timeout - Request timeout in ms

  Returns:
    {:text \"response text\"
     :grounding-metadata {...}   ; if google_search enabled
     :code-results [...]}        ; if code_execution enabled

  Example:
    (generate api-key \"Explain XTDB\" {:model \"gemini-3-flash\"})"
  [api-key prompt & [{:keys [model timeout]
                       :or {model default-model
                            timeout default-timeout-ms}
                       :as opts}]]
  (let [url (make-url model "generateContent")
        body (request-body prompt opts)]
    (try
      (let [response (http/post url
                                {:headers {"Content-Type" "application/json"
                                           "x-goog-api-key" api-key}
                                 :body (json/generate-string body)
                                 :timeout timeout
                                 :as :text})
            parsed (json/parse-string (:body response) true)]
        (cond
          (not= 200 (:status response))
          (do
            (log/error "Gemini API error" {:status (:status response)
                                           :body (:body response)})
            {:error {:status (:status response)
                     :message (:body response)}})

          :else
          (let [candidate (-> parsed :candidates first)
                parts (-> candidate :content :parts)]
            {:text (->> parts
                        (filter :text)
                        (map :text)
                        (clojure.string/join "\n"))
             :grounding-metadata (-> candidate :groundingMetadata)
             :code-results (->> parts
                                (filter :codeExecutionResult)
                                (mapv #(select-keys % [:outcome :output])))
             :usage-metadata (:usageMetadata parsed)})))
      (catch Exception e
        (log/error e "Gemini API request failed" {:model model})
        {:error {:exception (.getMessage e)}}))))

(defn generate-with-search
  "Generate content with Google Search grounding.

  Convenience wrapper that enables google_search tool.

  Example:
    (generate-with-search api-key \"Latest XTDB v2 release notes\")"
  [api-key prompt & [opts]]
  (generate api-key prompt (assoc opts :tools [{:google_search {}}])))

(defn generate-with-code
  "Generate content with Python code execution.

  Convenience wrapper that enables code_execution tool.

  Example:
    (generate-with-code api-key \"Calculate factorial of 100\")"
  [api-key prompt & [opts]]
  (generate api-key prompt (assoc opts :tools [{:code_execution {}}])))

```

### `seon.ai.gemini.core`

```clojure
(ns seon.ai.gemini.core
  "High-level Gemini API interface.

  Manages API configuration and provides convenient access patterns.
  Can be initialized with Integrant or used standalone."
  (:require [seon.ai.gemini.client :as client]
            [aero.core :as aero]
            [clojure.java.io :as io]))

;; Dynamic var for default API key - can be bound or set
(def ^:dynamic *api-key* nil)

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

  Example:
    (ask \"What is the capital of France?\")
    (ask \"Explain XTDB\" {:model \"gemini-3-pro-preview\"})"
  [prompt & [{:keys [api-key] :as opts}]]
  (let [key (get-api-key api-key)]
    (when-not key
      (throw (ex-info "No Gemini API key available" {})))
    (client/generate key prompt (dissoc opts :api-key))))

(defn search
  "Search the web using Gemini with Google Search grounding.

  Returns response with sources.

  Example:
    (search \"Latest Clojure 1.12 features\")
    ;; => {:text \"Clojure 1.12 was released...\"
    ;;     :grounding-metadata {:groundingChunks [...]}}"
  [query & [opts]]
  (let [key (get-api-key (:api-key opts))]
    (when-not key
      (throw (ex-info "No Gemini API key available" {})))
    (client/generate-with-search key query (dissoc opts :api-key))))

(defn calculate
  "Perform calculation using Gemini's Python code execution.

  Example:
    (calculate \"What is the 100th Fibonacci number?\")
    ;; => {:text \"...\" :code-results [{:outcome \"OUTCOME_OK\" :output \"...\"}]}"
  [prompt & [opts]]
  (let [key (get-api-key (:api-key opts))]
    (when-not key
      (throw (ex-info "No Gemini API key available" {})))
    (client/generate-with-code key prompt (dissoc opts :api-key))))

```

### `seon.ai.repl`

```clojure
(ns seon.ai.repl
  "REPL helper functions for AI assistance.

  Provides convenience functions for common REPL tasks:
  - Error explanation
  - Code introspection
  - Documentation lookup
  - Web search for current information

  These functions are designed to be used interactively in the REPL
  and return formatted, human-readable output."
  (:require [seon.ai.gemini.core :as gemini]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Error Explanation
;; =============================================================================

(defn explain-error
  "Explain a Clojure error using Gemini.

  Takes an exception (usually *e in the REPL) and asks Gemini
  to explain what went wrong and how to fix it.

  Example:
    (try (/ 1 0) (catch Exception e (explain-error e)))
    (explain-error *e)  ; After an error in REPL"
  ([e] (explain-error e {}))
  ([e {:keys [context] :as opts}]
   (let [error-class (.getName (class e))
         error-msg (.getMessage e)
         stack-trace (with-out-str
                       (binding [*err* *out*]
                         (.printStackTrace e)))
         ;; Limit stack trace to first 20 lines
         truncated-trace (->> (str/split-lines stack-trace)
                              (take 20)
                              (str/join "\n"))
         prompt (str "I encountered this Clojure error:\n\n"
                     "Exception: " error-class "\n"
                     "Message: " error-msg "\n\n"
                     "Stack trace (truncated):\n" truncated-trace "\n\n"
                     (when context
                       (str "Context: " context "\n\n"))
                     "Please:\n"
                     "1. Explain what this error means\n"
                     "2. Identify the likely cause\n"
                     "3. Suggest how to fix it\n"
                     "Be concise and Clojure-specific.")]
     (-> (gemini/ask prompt {:model "gemini-3-flash"})
         :text))))

(defmacro try-explain
  "Execute code and explain any errors that occur.

  Example:
    (try-explain (/ 1 0))"
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (println "Error occurred:")
       (println (.getMessage e#))
       (println "\nGemini explanation:")
       (println (explain-error e#)))))

;; =============================================================================
;; Code Introspection
;; =============================================================================

(defn analyze-code
  "Analyze Clojure code for improvements, bugs, or style issues.

  Example:
    (analyze-code \"(defn foo [x] (if x (do (println x) x) nil))\")
    (analyze-code (slurp \"src/seon/db/node.clj\") {:focus :performance})"
  ([code] (analyze-code code {}))
  ([code {:keys [focus] :or {focus :general}}]
   (let [focus-instruction (case focus
                             :performance "Focus on performance improvements"
                             :bugs "Focus on potential bugs and edge cases"
                             :style "Focus on Clojure idioms and style"
                             :security "Focus on security concerns"
                             "Provide a general code review")
         prompt (str "Review this Clojure code:\n\n```clojure\n"
                     code
                     "\n```\n\n"
                     focus-instruction ". "
                     "Be concise and specific. "
                     "If the code is good, say so briefly.")]
     (-> (gemini/ask prompt {:model "gemini-3-flash"})
         :text))))

(defn suggest-tests
  "Suggest test cases for a function or namespace.

  Example:
    (suggest-tests \"(defn add [a b] (+ a b))\")"
  [code]
  (let [prompt (str "Given this Clojure code:\n\n```clojure\n"
                    code
                    "\n```\n\n"
                    "Suggest test cases using clojure.test. "
                    "Include edge cases and property-based test ideas. "
                    "Use the testing patterns from this project: "
                    "deftest, is, are, and testing macros.")]
    (-> (gemini/ask prompt {:model "gemini-3-flash"})
        :text)))

;; =============================================================================
;; Documentation & Learning
;; =============================================================================

(defn doc-search
  "Search for documentation on a topic with web grounding.

  Uses Google Search to find current documentation.

  Example:
    (doc-search \"XTDB v2 temporal queries\")
    (doc-search \"Malli schema coercion\")"
  [topic]
  (let [prompt (str "I need documentation/examples for: " topic "\n\n"
                    "Search for current documentation and provide:\n"
                    "1. A concise explanation\n"
                    "2. Key code examples\n"
                    "3. Links to official docs if available\n"
                    "Focus on practical usage.")]
    (let [result (gemini/search prompt {:model "gemini-3-flash"})]
      {:text (:text result)
       :sources (when-let [chunks (-> result :grounding-metadata :groundingChunks)]
                  (->> chunks
                       (map (fn [c] {:url (-> c :web :uri)
                                     :title (-> c :web :title)}))
                       (remove #(nil? (:url %)))))})))

(defn how-to
  "Ask how to do something in Clojure.

  Example:
    (how-to \"parse JSON in Clojure\")
    (how-to \"create a lazy sequence from a file\")"
  [task]
  (let [prompt (str "How do I " task " in Clojure?\n\n"
                    "Provide:\n"
                    "1. A concise explanation\n"
                    "2. A working code example\n"
                    "3. Any gotchas or best practices\n"
                    "Use modern Clojure (1.11+) idioms.")]
    (-> (gemini/ask prompt {:model "gemini-3-flash"})
        :text)))

;; =============================================================================
;; Project-Specific Helpers
;; =============================================================================

(defn xtdb-help
  "Get help with XTDB v2 queries.

  Uses web search to find current XTDB v2 documentation.

  Example:
    (xtdb-help \"temporal queries with valid-time\")
    (xtdb-help \"aggregate functions in XTQL\")"
  [question]
  (let [prompt (str "Help me with XTDB v2 (not v1, not SQL): " question "\n\n"
                    "Important: I'm using XTDB v2 with XTQL (not SQL). "
                    "Provide examples using the Clojure API with forms like:\n"
                    "(from :table [{:column value} other-columns])\n"
                    "(where (= col value))\n"
                    "(aggregate {:count (count id)})\n\n"
                    "Search for current XTDB v2 documentation.")]
    (let [result (gemini/search prompt {:model "gemini-3-flash"})]
      {:text (:text result)
       :sources (when-let [chunks (-> result :grounding-metadata :groundingChunks)]
                  (->> chunks
                       (map (fn [c] {:url (-> c :web :uri)
                                     :title (-> c :web :title)}))
                       (remove #(nil? (:url %)))))})))

;; =============================================================================
;; REPL Output Helpers
;; =============================================================================

(defn summarize
  "Summarize text or data for easier understanding.

  Example:
    (summarize (slurp \"long-log-file.txt\"))
    (summarize (with-out-str (pp/pprint large-data-structure)))"
  [text & [{:keys [format] :or {format :paragraph}}]]
  (let [format-instruction (case format
                             :bullets "Use bullet points"
                             :paragraph "Use a single paragraph"
                             :outline "Use an outline with headers")
        prompt (str "Summarize this concisely. " format-instruction ":\n\n"
                    text)]
    (-> (gemini/ask prompt {:model "gemini-3-flash"})
        :text)))

```

---

## 5. Configuration with Aero

Add to `resources/system.edn`:

```clojure
{;; ... existing config ...

 ;; Gemini AI Configuration (optional - can also use env var)
 :seon.ai/gemini
 {:api-key #or [#env GEMINI_API_KEY nil]
  :default-model "gemini-3-flash"
  :timeout-ms 60000}}

```

Add to `src/seon/system.clj`:

```clojure
;;; ---------------------------------------------------------------------------
;;; Gemini AI Component (Optional)
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.ai/gemini
  [_ {:keys [api-key default-model timeout-ms]}]
  (when api-key
    (log/info "Gemini AI configured" {:model default-model})
    ;; Set dynamic var for easy REPL access
    (alter-var-root #'seon.ai.gemini.core/*api-key* (constantly api-key)))
  {:api-key api-key
   :default-model default-model
   :timeout-ms timeout-ms})

(defmethod ig/halt-key! :seon.ai/gemini
  [_ _]
  (alter-var-root #'seon.ai.gemini.core/*api-key* (constantly nil)))

```

---

## 6. REPL Integration Examples

### Add to `user.clj`

```clojure
;; At the top of user.clj, add to requires:
;; [seon.ai.repl :as ai]
;; [seon.ai.gemini.core :as gemini]

;; Convenience aliases for REPL use
(def explain ai/explain-error)
(def ask gemini/ask)
(def search gemini/search)
(def doc-search ai/doc-search)
(def xtdb-help ai/xtdb-help)
(def how-to ai/how-to)

```

### Usage Examples

```clojure
;; Quick question
(ask "What's the difference between reduce and transduce in Clojure?")

;; Error explanation
(try (/ 1 0) (catch Exception e (explain e)))
;; Or after an error: (explain *e)

;; Web search for current info
(search "Clojure 1.12 new features")
;; => {:text "...", :sources [{:url "..." :title "..."}]}

;; Documentation lookup
(doc-search "Malli schema transformers")

;; XTDB-specific help
(xtdb-help "how to do a temporal range query")

;; Code analysis
(analyze-code (slurp "src/seon/db/node.clj") {:focus :performance})

;; Get coding help
(how-to "implement a retry with exponential backoff")

```

---

## 7. Streaming Support (Optional Enhancement)

For long responses, streaming provides better UX:

```clojure
(ns seon.ai.gemini.streaming
  "SSE streaming support for Gemini API."
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

(defn- parse-sse-line
  "Parse a Server-Sent Events data line."
  [line]
  (when (str/starts-with? line "data: ")
    (let [data-str (subs line 6)]
      (when-not (= data-str "[DONE]")
        (json/parse-string data-str true)))))

(defn generate-stream
  "Stream generation with callback for each chunk.

  on-chunk is called with each text chunk as it arrives.
  Returns the complete response when done.

  Example:
    (generate-stream api-key \"Write a story\"
      (fn [chunk] (print chunk) (flush)))"
  [api-key prompt on-chunk & [{:keys [model] :or {model "gemini-3-flash"}}]]
  (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/"
                 model ":streamGenerateContent?alt=sse")
        body {:contents [{:parts [{:text prompt}]}]}]
    (with-open [resp (http/post url
                                {:headers {"Content-Type" "application/json"
                                           "x-goog-api-key" api-key}
                                 :body (json/generate-string body)
                                 :as :stream})
                reader (BufferedReader.
                        (InputStreamReader. (:body resp)))]
      (let [chunks (atom [])]
        (loop []
          (when-let [line (.readLine reader)]
            (when-let [data (parse-sse-line line)]
              (when-let [text (-> data :candidates first :content :parts first :text)]
                (swap! chunks conj text)
                (on-chunk text)))
            (recur)))
        {:text (str/join @chunks)}))))

;; REPL helper for streaming
(defn ask-stream
  "Ask with streaming output to REPL.

  Example:
    (ask-stream \"Explain monads in Clojure\")"
  [prompt]
  (generate-stream
   (System/getenv "GEMINI_API_KEY")
   prompt
   (fn [chunk] (print chunk) (flush)))
  (println))

```

---

## 8. Cost Analysis

### Gemini 3 Flash Pricing (December 2025)

- **Input**: $0.50 per 1M tokens (~750,000 words)
- **Output**: $3.00 per 1M tokens
- **Google Search**: $14 per 1,000 search queries (billing starts Jan 5, 2026)

### Estimated REPL Usage Costs

| Use Case | Input Tokens | Output Tokens | Est. Cost |
|----------|--------------|---------------|-----------|
| Error explanation | ~500 | ~500 | $0.002 |
| Code review (small file) | ~2,000 | ~1,000 | $0.005 |
| Doc search (with web) | ~200 | ~1,000 | $0.017 |
| XTDB help | ~300 | ~800 | $0.003 |

**Monthly estimate for active development**: $5-20/month

### Cost Optimization Strategies

1. **Use Flash model** - 3-4x cheaper than Pro for most tasks
2. **Limit stack traces** - Truncate to first 20 lines
3. **Cache common queries** - Store doc lookups in memory
4. **Batch requests** - Combine related questions
5. **Token limits** - Set `maxOutputTokens` for bounded responses

---

## 9. Native Clojure vs MCP Comparison

| Aspect | Native Clojure | MCP Server |
|--------|----------------|------------|
| **Dependencies** | hato, cheshire (already have) | Node.js, npm, bun |
| **REPL Integration** | Direct, seamless | Via tool calls |
| **Streaming** | Full control | Limited by MCP protocol |
| **Error Handling** | Clojure exceptions | JSON-RPC errors |
| **Configuration** | Aero/system.edn | Environment variables |
| **Debugging** | Standard Clojure tools | Separate process |
| **Deployment** | Single JVM | JVM + Node.js |
| **Latency** | Single HTTP call | MCP + HTTP |
| **Customization** | Full flexibility | Limited to MCP tools |

### Recommendation: Native Clojure

For Seon's use case (REPL-driven development, tight integration), native Clojure is clearly better:

1. **Simpler deployment** - No Node.js dependency
2. **Better REPL experience** - Direct function calls, not tool invocations
3. **Full control** - Can customize request/response handling
4. **Streaming** - Can implement proper SSE streaming for REPL
5. **Error handling** - Native Clojure exceptions with full stack traces
6. **Testing** - Standard Clojure test patterns

---

## 10. Implementation Plan

### Phase 1: Core Client (1-2 hours)

1. Create `seon.ai.gemini.client` namespace
2. Implement basic `generate` function
3. Add convenience wrappers for search and code execution
4. Write basic tests

### Phase 2: REPL Helpers (1-2 hours)

1. Create `seon.ai.repl` namespace
2. Implement `explain-error`, `analyze-code`, `doc-search`
3. Add to `user.clj` for easy access
4. Test in live REPL

### Phase 3: Integrant Integration (30 min)

1. Add config to `system.edn`
2. Implement init/halt methods
3. Wire up `*api-key*` dynamic var

### Phase 4: Streaming (optional, 1 hour)

1. Implement SSE parsing
2. Add `ask-stream` for REPL
3. Consider async/future-based approach

---

## 11. Security Considerations

1. **API Key Storage**
   - Never commit API keys to git
   - Use environment variables or `.env` file (gitignored)
   - Aero's `#env` reader is secure

2. **Request Logging**
   - Don't log full prompts in production (may contain sensitive data)
   - Log request metadata (model, token count) for debugging

3. **Rate Limiting**
   - Implement client-side rate limiting if needed
   - Handle 429 responses gracefully with backoff

4. **Input Validation**
   - Validate prompt length before sending
   - Sanitize user input if used in prompts

---

## Sources

- [Gemini API Quickstart](https://ai.google.dev/gemini-api/docs/quickstart)
- [Gemini API Reference](https://ai.google.dev/api)
- [Grounding with Google Search](https://ai.google.dev/gemini-api/docs/google-search)
- [Code Execution](https://ai.google.dev/gemini-api/docs/code-execution)
- [Text Generation](https://ai.google.dev/gemini-api/docs/text-generation)
- [Gemini 3 Developer Guide](https://ai.google.dev/gemini-api/docs/gemini-3)
- [Gemini Pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini 3 Flash Announcement](https://blog.google/products/gemini/gemini-3-flash/)
- [Streaming REST Cookbook](https://github.com/google-gemini/cookbook/blob/main/quickstarts/rest/Streaming_REST.ipynb)
