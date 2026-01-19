# Seon Code Conventions

## Malli Schema Patterns

All public APIs use Malli schemas for contract specification. This enables:
- Automatic validation via `malli.dev/start!`
- Generative testing via `mi/check`
- Self-documenting APIs for agents

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

### Same Entity, Multiple Namespaces

A single XTDB entity can have attributes from multiple namespaces:

```clojure
{:xt/id "msg-abc123"
 :seon.ai/type :message           ; Generic
 :seon.ai/role "assistant"        ; Generic
 :seon.ai/content "Hello!"        ; Generic
 :seon.ai.claude/message-type "assistant"  ; Claude-specific
 :seon.ai.claude/cache-tokens 150}         ; Claude-specific
```

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
