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

### Testing Pattern

Test through public functions with schema-generated inputs:

```clojure
(ns seon.trading.signals-test
  (:require [clojure.test :refer :all]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [malli.core :as m]
            [seon.trading.signals :as signals]))

(deftest analyze-generative-test
  (testing "analyze handles all valid inputs"
    (mi/collect! {:ns 'seon.trading.signals})
    (doseq [request (mg/sample ::signals/analyze-request {:size 20})]
      (let [response (signals/analyze request)]
        (is (m/validate ::signals/analyze-response response))))))
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
