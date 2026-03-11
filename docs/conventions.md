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

Instrumentation is **automatic**. The Integrant component `:seon.dev/instrumentation` collects all `:malli/schema` metadata and instruments every public function on startup. It uses a custom reporter (`agent-reporter`) that throws `ExceptionInfo` with rich, structured error messages. Agent JVMs self-instrument via `agent_runner.clj`.

You don't need to call `mi/collect!`, `dev/start!`, or `mi/instrument!` manually. Just add `:malli/schema` to your function, reload, and it's instrumented.

### Testing Strategy

Tests serve two purposes:

1. **Example tests** - Document intended usage, show how functions compose
2. **Generative tests** - Find edge cases, validate schema contracts

**Always write both.** Generative tests alone don't show real-world usage patterns.

See `/clojure-testing` skill for full fixture patterns, generators, and debugging techniques.

#### Example Tests (Documentation + Integration)

Show the intended workflow. These serve as executable documentation:

```clojure
(deftest session-lifecycle-test
  (testing "Complete workflow: start -> messages -> end -> retrieve"
    ;; with-test-datalevin binds db/*direct-mode* and *conn-manager*
    (tu/with-test-datalevin
      (fn []
        ;; Start session
        (let [{::ai/keys [session-id]} (ai/start-session!
                                         {::ai/namespace 'seon.trading
                                          ::ai/prompt "Analyze AAPL"})]
          ;; Add conversation
          (ai/add-message! {::ai/session-id session-id
                            ::ai/role "user"
                            ::ai/content "What's the trend?"})
          (ai/add-message! {::ai/session-id session-id
                            ::ai/role "assistant"
                            ::ai/content "AAPL shows bullish momentum."
                            ::ai/output-tokens 15})

          ;; End with stats
          (ai/end-session! {::ai/session-id session-id
                            ::ai/status :completed
                            ::ai/cost-usd 0.002})

          ;; Verify round-trip
          (let [messages (ai/get-messages {::ai/session-id session-id})]
            (is (= 2 (count messages)))
            (is (= ["user" "assistant"] (mapv ::ai/role messages)))))))))

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
    (is (nil? (ai/get-session {::ai/session-id "ses-nonexistent"}))))

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

;; See which functions are instrumented (collected automatically on startup)
(require '[malli.core :as m])
(keys (get (m/function-schemas) 'seon.ai.gemini))

```

## Database Access

All database access goes through `seon.db`. No direct `datalevin.core` usage outside `src/seon/db/` infrastructure.

See `/datalevin` skill for the full API reference, schema registration patterns, and querying guide.

Key rule: `db/transact!` and `db/query` take a db-name keyword (`:seon.runtime`, `:seon.ai`, or namespace keywords like `:seon.trading`). All attributes must be registered with `schema/register!` before transacting.

---

## Schema Composition Across Namespaces

Provider namespaces extend base namespaces by referencing their schemas. This is the EAV pattern: entities are bags of namespaced attributes.

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
   [:seon/id ::ai/message-id]           ; Identity attribute
   [::ai/role ::ai/role]                ; Reference base!
   [::ai/content ::ai/content]          ; Reference base!
   ;; Claude-specific attributes
   [::message-type ::message-type]
   [::cache-tokens {:optional true} ::cache-tokens]])

```

### Entity-Centric Thinking (EAV Pattern)

**Key insight:** In Datalevin's EAV model, an entity is a bag of namespaced attributes, not a row in a table. A single entity can have attributes from multiple namespaces:

```clojure
;; This is ONE entity, not separate "rows" in different "tables"
{:seon/id "msg-abc123"
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

### When NOT to Use `:malli/schema`

Some types cannot be generated for property testing. Omit `:malli/schema` metadata and document in docstrings instead:

```clojure
;; Connection managers, atoms, channels - opaque runtime objects
(defn start-session!
  "Start a new AI session.

   Request keys:
     ::namespace - Optional. Clojure namespace context
     ..."
  ;; NO :malli/schema here - runtime objects can't be generated
  [{::keys [namespace prompt]}]
  ...)

;; Process handles, channels, atoms - runtime objects
(defn launch-agent!
  "Launch an agent. Returns handle with channels and atoms.

   Note: No :malli/schema - involves process spawning and
   runtime objects that cannot be property tested."
  [{...}]
  ...)

```

**Rule:** If a function takes connection managers, spawns processes, or returns channels/atoms, skip `:malli/schema`. Document the expected types in docstrings.

### Test Code Exemptions

Test namespaces (`*_test.clj`) are exempt from most conventions:

- **No `:malli/schema`** on `deftest` or test helper functions
- **No map-in/map-out** — test helpers can use positional args for brevity
- **No namespace docstrings** — tests are self-documenting via test names
- **Non-namespaced keys are fine** in test data literals (e.g. `{:name "test"}`)

Conventions that **do** apply in tests:

- **Namespaced keys when calling production functions** — match the real API
- **Both example and generative tests** — see Testing Strategy below
- **Meaningful test names** — `session-lifecycle-test` not `test1`

## Converter Functions (Map-In Pattern)

When converting external data (like SDK messages) to internal entities, use the map-in pattern:

```clojure
(defn sdk-message->entity
  "Convert a Claude SDK message to a seon.ai message entity.

   Request keys:
     ::sdk-message   - Required. Raw SDK message map
     ::ai/session-id - Optional. Parent session to attach

   Returns entity map with :seon/id and namespaced attributes."
  [{::keys [sdk-message] ::ai/keys [session-id]}]
  (let [msg-type (:type sdk-message)
        content (extract-content sdk-message)]
    (cond-> {:seon/id (generate-id "msg")
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

## Namespace Docstrings

Every namespace should have a comprehensive docstring written by its steward agent. The docstring is a living assessment covering purpose, architecture position, consumer analysis, convention compliance, issues, and recommendations. See `docs/agent-playbooks/namespace-stewardship.md` for the full format and process.

## SSE Patterns

See `/datastar-web-ui` skill for SSE patterns (direct response vs background push, buffer design, refresh triggers, handler hot reload).

## Reload Lifecycle Hooks for `defonce` State

Seon is a runtime system where agents live-code and update namespaces. `defonce` atoms survive `user/reload` (clj-reload) but may hold stale references — old closures, dead channels, orphaned threads. Every `defonce` with mutable runtime state **must** have lifecycle hooks.

### How It Works

clj-reload calls two per-namespace hooks (0-arg functions) if they exist:

| Hook | When | Use For |
|------|------|---------|
| `before-ns-unload` | Before ns is removed | Stop go-loops, drain promises, cancel schedulers, remove watches |
| `after-ns-reload` | After ns is reloaded | Re-populate from Integrant system, restart background processes |

### Pattern

```clojure
(defonce my-state (atom nil))

(defn before-ns-unload
  "Cleanup before clj-reload unloads this namespace."
  []
  (when-let [s @my-state]
    (stop! s)
    (reset! my-state nil)))

(defn after-ns-reload
  "Re-init after clj-reload reloads this namespace."
  []
  (when (nil? @my-state)
    (reset! my-state (init-from-integrant-system))))

```

### Rules

1. **Any `defonce` holding runtime state must have hooks.** Caches, registries, go-loops, channels, promises, schedulers — if it's mutable and not purely config, add hooks.
2. **`before-ns-unload` must be idempotent.** It may be called when state is already nil.
3. **`after-ns-reload` should re-derive from Integrant.** The system map is the source of truth: `@(requiring-resolve 'integrant.repl.state/system)`.
4. **Don't add hooks for `defonce` holding immutable config.** Pure values, schema definitions, etc. are fine without hooks.
5. **Exceptions in `before-ns-unload` are swallowed** by clj-reload. Keep it simple — don't do anything that can fail.

### Existing Examples

- **SSE handlers**: `seon.web.handlers/after-ns-reload` — recreates handler objects
- **Route caches**: `seon.ns.routes/after-ns-reload` — clears stale handler cache
- **Promise drains**: `seon.flow.topology/before-ns-unload` — delivers timeout to pending promises
- **Flow re-wiring**: `seon.runtime/after-ns-reload` — re-registers flow handles from Integrant

---

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

Add `:max` when:

- External API/protocol enforces the limit
- Mathematical/domain constraint exists (percentages, ratios)
- Memory/performance safety requires it (document why)

Don't add `:max` when:

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
