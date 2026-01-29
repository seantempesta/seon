# Seon Code Conventions

## Why These Conventions Matter

These aren't arbitrary style rules. They're the foundation for **AI agents to write reliable software**.

When every function has:
- **Namespaced keys** → Agents can query "what accepts `:seon.trading/position`?" instead of guessing
- **Malli schemas** → Contracts are machine-readable. Property tests validate automatically.
- **Map-in/map-out** → Extensible APIs. Adding a field doesn't break callers.
- **Registered schemas** → A queryable database of all data shapes in the system

The result: agents can discover, compose, and validate code without hallucinating interfaces.

---

## Malli Schema Patterns

All public APIs use Malli schemas for contract specification. This enables:
- Automatic validation via `malli.dev/start!`
- Generative testing via `mi/check`
- Self-documenting APIs for agents
- **Function discovery** - "What functions return `::trading/signal`?" is a database query

### Understanding `::` Keyword Syntax

The `::` creates **auto-resolved namespaced keywords**:

```clojure
;; INSIDE seon.ai.gemini namespace (library code):
::prompt              ;; => :seon.ai.gemini/prompt
::api-key             ;; => :seon.ai.gemini/api-key

;; OUTSIDE (user code with alias):
(ns my-app.core
  (:require [seon.ai.gemini :as gemini]))

::prompt              ;; => :my-app.core/prompt  (WRONG namespace!)
::gemini/prompt       ;; => :seon.ai.gemini/prompt (correct)
```

This means docstrings showing user-facing examples use `::alias/key` syntax.

### Schema Registration

Register schemas using `schema/register!` with `::` auto-namespaced keywords:

```clojure
(ns seon.trading.signals
  (:require [seon.schema :as schema]))

;; Each registration is a separate form for easy editing
(schema/register! ::ticker
                  [:string {:min 1 :max 10
                            :description "Stock ticker symbol"}])

(schema/register! ::signal-type
                  [:enum :buy :sell :hold])

(schema/register! ::confidence
                  [:double {:min 0.0 :max 1.0}])
```

### Request/Response Schema Pattern

Define separate schemas for requests and responses with namespaced keys:

```clojure
;; Request schema - all required/optional fields
(schema/register! ::analyze-request
                  [:map
                   [::ticker ::ticker]                              ; required
                   [::model {:optional true} ::model]               ; optional
                   [::timeout {:optional true} ::timeout]])

;; Response schema - namespaced keys for outputs
(schema/register! ::analyze-response
                  [:map
                   [::signal-type ::signal-type]
                   [::confidence ::confidence]
                   [::reasoning {:optional true} :string]])
```

### Public Function Pattern

Public functions are **map in, map out** with:
- Single map argument using `::keys` destructuring
- Namespaced keys in return value
- `:malli/schema` metadata referencing request/response schemas

```clojure
(defn analyze
  "Analyze a ticker and return a trading signal.

   Request keys:
     ::ticker  - Required. Stock ticker symbol
     ::model   - Optional. Model to use
     ::timeout - Optional. Request timeout in ms

   Response keys:
     ::signal-type - The trading signal (:buy, :sell, :hold)
     ::confidence  - Confidence score (0.0-1.0)
     ::reasoning   - Optional explanation

   Example:
     (analyze {::ticker \"AAPL\"})
     (analyze {::ticker \"AAPL\" ::model \"advanced\"})"
  {:malli/schema [:=> [:cat ::analyze-request] ::analyze-response]}
  [{::keys [ticker model timeout]}]
  (let [model (or model default-model)]
    (analyze* ticker model timeout)))
```

### Private Helper Pattern

Private functions use positional args for internal convenience:

```clojure
(defn- analyze* [ticker model timeout]
  ;; Implementation with positional args
  {::signal-type :hold
   ::confidence 0.5
   ::reasoning "Neutral momentum"})
```

### Handling Optional Values

Use `or` for defaults since destructuring `:or` doesn't work when key is present with nil value:

```clojure
;; WRONG - :or doesn't apply when {:model nil} is passed
(defn- impl [{:keys [model] :or {model default-model}}]
  ...)

;; CORRECT - explicit or handles nil values
(defn- impl [{:keys [model]}]
  (let [model (or model default-model)]
    ...))
```

### Complete Example (from gemini.clj)

```clojure
(ns seon.ai.gemini
  (:require [malli.core :as m]
            [seon.schema :as schema]))

;;; Schema Registration

(schema/register! ::api-key
                  [:string {:min 1
                            :description "Gemini API key"}])

(schema/register! ::prompt
                  [:string {:min 1
                            :description "Text prompt"}])

(schema/register! ::model
                  [:enum "gemini-3-flash-preview" "gemini-3-pro-preview"])

(schema/register! ::text
                  [:string {:description "Generated text response"}])

(schema/register! ::ask-request
                  [:map
                   [::prompt ::prompt]
                   [::model {:optional true} ::model]
                   [::api-key {:optional true} ::api-key]])

(schema/register! ::response
                  [:map
                   [::text ::text]
                   [::error {:optional true} [:map
                                               [::status {:optional true} :int]
                                               [::message {:optional true} :string]]]])

;;; Private Implementation

(defn- generate* [api-key prompt {:keys [model timeout]}]
  (let [model (or model "gemini-3-flash-preview")]
    ;; HTTP call, returns map with ::text, ::error, etc.
    {::text "Response here"}))

;;; Public API

(defn ask
  "Ask Gemini a question.

   Request keys:
     ::prompt  - Required. Question or instruction
     ::model   - Optional. Model name
     ::api-key - Optional. Explicit API key

   Example:
     (ask {::prompt \"What is 2+2?\"})
     ;; From outside namespace:
     (gemini/ask {::gemini/prompt \"What is 2+2?\"})"
  {:malli/schema [:=> [:cat ::ask-request] ::response]}
  [{::keys [prompt model api-key]}]
  (let [key (resolve-api-key! api-key)]
    (generate* key prompt {:model model})))
```

### Development Setup

Enable automatic validation during development:

```clojure
;; In user.clj or dev startup
(require '[malli.dev :as dev])
(require '[malli.instrument :as mi])

;; Collect all function schemas
(mi/collect!)

;; Start instrumentation (validates inputs/outputs)
(dev/start!)
```

### Testing Strategy

Tests serve two purposes:
1. **Example tests** - Document intended usage, show how functions compose
2. **Generative tests** - Find edge cases, validate schema contracts

**Always write both.** Generative tests alone don't show real-world usage patterns.

#### Example Tests (Documentation + Integration)

Show the intended workflow. These serve as executable documentation:

```clojure
(deftest session-lifecycle-test
  (testing "Complete workflow: start -> messages -> end -> retrieve"
    ;; Start session
    (let [{::ai/keys [session-id]} (ai/start-session!
                                     {::ai/node *test-node*
                                      ::ai/namespace 'seon.trading
                                      ::ai/prompt "Analyze AAPL"})]
      ;; Add conversation
      (ai/add-message! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/role "user"
                        ::ai/content "What's the trend?"})
      (ai/add-message! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/role "assistant"
                        ::ai/content "AAPL shows bullish momentum."
                        ::ai/output-tokens 15})

      ;; End with stats
      (ai/end-session! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/status :completed
                        ::ai/cost-usd 0.002})

      ;; Verify round-trip
      (let [messages (ai/get-messages {::ai/node *test-node*
                                       ::ai/session-id session-id})]
        (is (= 2 (count messages)))
        (is (= ["user" "assistant"] (mapv ::ai/role messages)))))))
```

#### Generative Tests (Contract Validation)

Find edge cases the schema allows but you didn't think of:

```clojure
(deftest analyze-generative-test
  (testing "analyze handles all valid inputs"
    (doseq [request (mg/sample ::signals/analyze-request {:size 20})]
      (let [response (signals/analyze request)]
        (is (m/validate ::signals/analyze-response response))))))
```

#### Edge Case Tests

Document tricky scenarios explicitly:

```clojure
(deftest edge-cases-test
  (testing "get-session returns nil for non-existent session"
    (is (nil? (ai/get-session {::ai/node *test-node*
                               ::ai/session-id "ses-nonexistent"}))))

  (testing "handles structured content (maps, not just strings)"
    (let [content {:type "tool_result" :tool-use-id "abc" :data {...}}]
      ;; ... test that map content round-trips correctly
      )))
```

### Schema Introspection

Use the global registry for schema introspection:

```clojure
(require '[seon.schema :as schema])

;; Get all schemas for a namespace
(schema/schemas-in-namespace "seon.ai.gemini")

;; Check if a schema is registered
(schema/registered? ::gemini/prompt)

;; Get function schemas after collection
(require '[malli.instrument :as mi])
(mi/collect! {:ns 'seon.ai.gemini})
(keys (get (m/function-schemas) 'seon.ai.gemini))
```

## Schema Composition Across Namespaces

Provider namespaces extend base namespaces by referencing their schemas. This is the XTDB/Datomic pattern: entities are bags of namespaced attributes.

### Base + Provider Pattern

```clojure
;; seon.ai - base namespace defines generic schemas
(schema/register! ::session-id [:string {:min 1}])
(schema/register! ::role [:enum "user" "assistant" "system"])

;; seon.ai.claude - provider extends base
(ns seon.ai.claude
  (:require [seon.ai :as ai]))

;; Reference base schemas in composite schemas
(schema/register! ::message-entity
  [:map
   [:xt/id ::ai/message-id]        ; Reference base!
   [::ai/role ::ai/role]           ; Reference base!
   [::ai/content ::ai/content]     ; Reference base!
   ;; Claude-specific attributes
   [::message-type ::message-type]
   [::cache-tokens {:optional true} ::cache-tokens]])
```

### Entity-Centric Thinking (XTDB/Datomic Pattern)

**Key insight:** In XTDB, an entity is a bag of namespaced attributes, not a row in a table. A single entity can have attributes from multiple namespaces:

```clojure
;; This is ONE entity, not separate "rows" in different "tables"
{:xt/id "msg-abc123"
 ;; Base seon.ai attributes (generic to all providers)
 :seon.ai/type :message
 :seon.ai/role "assistant"
 :seon.ai/content "Hello!"
 :seon.ai/timestamp #inst "2026-01-19T..."
 ;; Claude-specific attributes (only present for Claude messages)
 :seon.ai.claude/message-type "assistant"
 :seon.ai.claude/cache-tokens 150
 :seon.ai.claude/uuid "sdk-msg-xyz"}
```

**Why this matters:**
- No schema migration needed when adding provider-specific fields
- Queries can filter by any attribute from any namespace
- Generic code works on `:seon.ai/*` attributes, provider code adds its own
- The "table name" in XTDB is just a type tag for SQL queries, not a rigid schema

### When NOT to Use `:malli/schema`

Some types cannot be generated for property testing. Omit `:malli/schema` metadata and document in docstrings instead:

```clojure
;; XTDB nodes - opaque Java objects
(defn start-session!
  "Start a new AI session.

   Request keys:
     ::node - Required. XTDB node instance (cannot be generated)
     ..."
  ;; NO :malli/schema here - node can't be generated
  [{::keys [node namespace prompt]}]
  ...)

;; Process handles, channels, atoms - runtime objects
(defn launch-agent!
  "Launch an agent. Returns handle with channels and atoms.

   Note: No :malli/schema - involves process spawning and
   runtime objects that cannot be property tested."
  [{...}]
  ...)
```

**Rule:** If a function takes XTDB nodes, spawns processes, or returns channels/atoms, skip `:malli/schema`. Document the expected types in docstrings.

## Converter Functions (Map-In Pattern)

When converting external data (like SDK messages) to internal entities, use the map-in pattern:

```clojure
(defn sdk-message->entity
  "Convert a Claude SDK message to a seon.ai message entity.

   Request keys:
     ::sdk-message   - Required. Raw SDK message map
     ::ai/session-id - Optional. Parent session to attach

   Returns entity map suitable for XTDB storage."
  [{::keys [sdk-message] ::ai/keys [session-id]}]
  (let [msg-type (:type sdk-message)
        content (extract-content sdk-message)]
    (cond-> {:xt/id (generate-id "msg")
             ::ai/type :message
             ::ai/content content}
      session-id (assoc ::ai/session-id session-id))))
```

**Why map-in?** Even converter functions benefit from the pattern:
- Extensible: Add optional context (session-id, metadata) without changing signature
- Consistent: Same API style as all other public functions
- Traceable: Can add logging/debugging keys later

**Anti-pattern:**
```clojure
;; BAD: positional args limit extensibility
(defn sdk-message->entity [sdk-message session-id]
  ...)
```

## XTDB Compatibility Notes

### Namespace as String

Clojure symbols don't round-trip through XTDB. Store namespaces as strings:

```clojure
;; Store
(db/put! node :ai_sessions
  {:xt/id session-id
   ::namespace (str namespace)})  ; 'seon.trading -> "seon.trading"

;; Query (compare as string)
(db/q node "SELECT * FROM ai_sessions WHERE seon$ai$namespace = ?"
      [(str namespace)])
```

### Timestamps: Instant vs ZonedDateTime

XTDB returns `ZonedDateTime`, not `Instant`. Handle both in tests:

```clojure
(defn temporal? [v]
  (or (instance? java.time.Instant v)
      (instance? java.time.ZonedDateTime v)))

;; In tests
(is (temporal? (::ai/timestamp entity)))  ; Not (inst? ...)
```

### Custom Generators for Complex Types

Use `:gen/fmap` for types that need custom generation:

```clojure
(schema/register! ::timestamp
  [:fn {:description "Event timestamp"
        :gen/fmap (fn [_] (Instant/now))
        :gen/schema :int}  ; Dummy schema for generator input
   inst?])
```

## Provider Multimethod Pattern

For extensible provider systems (like AI providers), use multimethods with keyword dispatch. This allows adding new providers without modifying existing code.

### Defining Extension Points

Define multimethods in a base namespace with sensible defaults:

```clojure
(ns seon.ai.agent
  "Provider-agnostic agent extension points.")

;; Extension points - dispatch on :provider key
(defmulti normalize-message
  "Convert provider-specific message to ::ai/message entity.
   Dispatch on :provider keyword in request map."
  :provider)

(defmulti result-message?
  "Check if message is the final result message.
   Returns boolean."
  :provider)

(defmulti parse-result
  "Extract final stats from result message.
   Returns ::parsed-result map."
  :provider)

;; Default implementations for unknown providers
(defmethod normalize-message :default
  [{:keys [provider]}]
  (throw (ex-info (str "Unknown provider: " provider
                       ". Did you require the provider namespace?")
                  {:provider provider})))
```

### Implementing Providers

Provider namespaces implement the multimethods:

```clojure
(ns seon.ai.claude
  "Claude provider implementation."
  (:require [seon.ai.agent :as agent]))

;; Implement normalize-message for :claude
(defmethod agent/normalize-message :claude
  [{:keys [message session-id]}]
  (sdk-message->entity {::sdk-message message
                        ::ai/session-id session-id}))

;; Implement result-message? for :claude
(defmethod agent/result-message? :claude
  [{:keys [message]}]
  (= "result" (:type message)))

;; Implement parse-result for :claude
(defmethod agent/parse-result :claude
  [{:keys [message]}]
  {::status (if (= "success" (:subtype message)) :completed :failed)
   ::cost-usd (:total_cost_usd message)
   ;; ... other fields
   })
```

### Usage Pattern

```clojure
;; Call multimethod with provider key in request
(agent/normalize-message {:provider :claude
                          :message sdk-msg
                          :session-id "ses-abc"})

;; Adding a new provider (e.g., Gemini) requires:
;; 1. New namespace: seon.ai.gemini.agent
;; 2. Implement: (defmethod agent/normalize-message :gemini ...)
;; 3. No changes to base namespace or existing providers
```

### Why Multimethods Over Protocols

- **Keyword dispatch** - Provider is data (:claude, :gemini), not a type
- **No wrapper objects** - Just pass maps with :provider key
- **Namespace loading** - (require '[seon.ai.claude]) registers implementations
- **Extensibility** - Third parties can add providers without modifying source

## Anti-Patterns to Avoid

```clojure
;; BAD: :pre/:post - not instrumentable, no generative testing
(defn process [x]
  {:pre [(m/validate ::input x)]}
  ...)

;; BAD: positional args in public API - harder to extend
(defn process [ticker timeframe confidence]
  ...)

;; BAD: non-namespaced keys in public API - ambiguous
(defn process [{:keys [ticker timeframe]}]
  {:signal :buy})

;; BAD: using :or in destructuring for optional API values
(defn process [{:keys [model] :or {model "default"}}]
  ...)  ; doesn't work when {:model nil} is passed

;; BAD: Only generative tests, no example tests
(deftest foo-test
  (doseq [input (mg/sample ::input)]
    (is (m/validate ::output (foo input)))))
;; Missing: tests showing intended usage patterns!
```

## File Organization

Keep it simple - one file per namespace with schemas and functions together:

```
src/seon/
├── ai/
│   └── gemini.clj    ; seon.ai.gemini (schemas + API)
└── trading/
    └── signals.clj   ; seon.trading.signals
```

Don't split into core.clj, schema.clj, etc. prematurely. Tests go in `test/` mirroring the `src/` structure.

## SSE Handler Hot Reload Pattern

SSE handlers use `def` to create handler objects. By default, `clj-reload` doesn't re-evaluate `def` forms unless the actual code changes, causing stale handlers that don't pick up changes to render functions.

### The Problem

```clojure
;; BAD: This closure is captured ONCE at def time
(def my-sse-handler
  (sse/render-handler
   (fn [_request]
     (render-my-page))))  ; Changes to render-my-page won't propagate!
```

### The Solution: Var References + after-ns-reload Hook

1. **Define render function separately** - enables var indirection
2. **Pass var reference** - `#'render-fn` derefs to current binding each call
3. **Add `after-ns-reload` hook** - recreates handler objects after reload

```clojure
(ns seon.web.myhandlers
  (:require [seon.web.sse :as sse]))

;; 1. Define render function separately
(defn- my-page-sse-render
  "Render function for SSE. Defined separately for hot reload."
  [_request]
  (render-my-page))

;; 2. Pass var reference to render-handler
(def my-page-sse
  "SSE handler for my page."
  (sse/render-handler #'my-page-sse-render :poll-ms 2000))

;; 3. Add after-ns-reload hook (called automatically by clj-reload)
(defn after-ns-reload
  "Called by clj-reload after namespace reload. Recreates SSE handlers."
  []
  (alter-var-root #'my-page-sse
                  (constantly (sse/render-handler #'my-page-sse-render :poll-ms 2000))))
```

### Dynamic Handlers (per-request state)

For handlers that need per-request state (e.g., agent-id from path params), cache handlers and clear on reload:

```clojure
;; Cache handlers per-key for connection reuse
(defonce ^:private my-handlers (atom {}))

(defn- get-my-handler
  "Get or create SSE handler for a specific resource."
  [resource-id]
  (if-let [handler (get @my-handlers resource-id)]
    handler
    (let [render-fn (fn [_req] (render-content resource-id))
          handler (sse/render-handler render-fn :poll-ms 1000)]
      (swap! my-handlers assoc resource-id handler)
      handler)))

(defn my-resource-sse
  "SSE handler for resource view."
  [request]
  (let [resource-id (get-in request [:path-params :id])
        handler (get-my-handler resource-id)]
    (handler request)))

(defn after-ns-reload
  "Clear handler cache on reload."
  []
  (reset! my-handlers {}))
```

### Why This Works

1. **Var references are IFn** - `#'render-fn` derefs to current binding each call
2. **`after-ns-reload` hook** - clj-reload calls this after namespace reload
3. **Handler recreation** - new handler objects capture updated var references

### Note on HTTP Handlers

Regular HTTP handlers (Ring functions) **don't need this pattern** - they're called directly through vars. This pattern is specifically for SSE handlers because `render-handler` captures the render function at creation time.

## Numeric Limits and Defaults

Avoid arbitrary "magic numbers" that cause bugs or confusion. Every limit should have a documented source.

### Categories of Limits

| Category | Example | Guideline |
|----------|---------|-----------|
| **External constraints** | API rate limits, protocol specs | Document the source |
| **Domain bounds** | Percentages 0-100, IV rank 0-1 | Mathematical constraints - always add |
| **Internal allocations** | Port ranges, batch sizes | Document why and how to override |
| **Safety caps** | Max recursion, timeout | Document rationale, make configurable |

### Rule: Don't Set Arbitrary Defaults for "No Limit"

**Bad - arbitrary large number to mean "unlimited":**
```clojure
::max-turns (or max-turns 999999)  ; Magic number!
```

**Good - don't pass the flag when unlimited:**
```clojure
(cond-> base-args
  max-turns (into ["--max-turns" (str max-turns)]))
```

### Rule: Document Limit Sources in Schemas

```clojure
;; GOOD - source documented
(schema/register! ::nrepl-port
  [:int {:min 7889 :max 7999
         :description "nREPL port for agent sessions (reserved range)"}])

(schema/register! ::iv-rank
  [:double {:min 0.0 :max 1.0
            :description "IV percentile rank (mathematical bound)"}])

;; BAD - arbitrary, undocumented
(schema/register! ::timeout
  [:int {:min 1000 :max 300000}])  ; Why these numbers?
```

### When to Add `:max` Constraints

✅ **Add `:max` when:**
- External API/protocol enforces the limit
- Mathematical/domain constraint exists (percentages, ratios)
- Memory/performance safety requires it (document why)

❌ **Don't add `:max` when:**
- "Just to be safe" with no specific reason
- The underlying system has no limit
- To prevent hypothetical abuse (use rate limiting instead)

### Pattern: Optional Unbounded Parameters

When a parameter can legitimately be "unlimited":

```clojure
;; Schema: min only, no max
(schema/register! ::max-results
  [:int {:min 1
         :description "Max results. Omit for unlimited."}])

;; Function: handle omission gracefully
(defn search [{::keys [max-results]}]
  (cond-> (base-query)
    max-results (add-limit max-results)))
```

### Batch Sizes and Tuning Parameters

Document the rationale and how to override:

```clojure
(def ^:const xtdb-batch-size
  "Documents per XTDB transaction.
   Tuned for memory vs latency tradeoff.
   Override for bulk loads or constrained envs."
  1000)
```
