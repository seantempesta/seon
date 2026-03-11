# Seon Architecture Research for Primer Domain

## Key Findings

### 1. Malli/Specs Patterns

**Registry-based schema system** at `src/seon/db/schema.clj`:

```clojure
;; Central registry atom (dynamically updated)
(def registry
  (atom
   (merge
    (m/default-schemas)
    {:option/type OptionType
     :option/quote OptionQuote
     :option/greeks Greeks
     ...})))

;; Schemas include generators for meaningful test data
(def gen-ticker
  [:string {:min 1 :max 5
            :gen/elements ["AAPL" "MSFT" "GOOGL" ...]}])

```

**Key Patterns:**
- Schema-first design - entities defined before code
- Generators built-in for property-based testing
- Registry is an atom - extensible at runtime
- Namespaced keywords everywhere

### 2. Domain Organization

**Structure:** `src/seon/trading/`

```
├── core.clj          # Public API + capabilities()
├── signals.clj       # Trading signal calculations
├── analysis.clj      # High-level analysis
├── validation.clj    # Data validation
├── ingest.clj        # Data pipeline
└── *_test.clj        # Colocated tests

```

**The `capabilities` pattern:**

```clojure
(defn capabilities []
  {:domain :trading
   :description "Options trading analysis"
   :signals [:iv-rank :skew-index ...]
   :temporal-support true})

```

**Domain requirements:**
1. `db` parameter first - no globals
2. `capabilities` function - discoverable API
3. Schema colocated or in `schema.clj`

### 3. State & Rendering (Hyperlith Pattern)

**Core pattern:** `view = f(state)` - render full view, not deltas

```clojure
;; 1. STATE ATOM
(defonce job-state (atom {:current nil :history []}))

;; 2. AUTO-REFRESH ON STATE CHANGE (Key!)
(add-watch job-state :sse-auto-refresh
  (fn [_ _ old new]
    (when (not= old new)
      (sse/refresh-all!))))

;; 3. SSE RENDER HANDLER
(def dashboard-sse
  (sse/render-handler
   (fn [_request]
     (html/dashboard-content @job-state))))

```

**Key points:**
- Atoms watch + auto-refresh
- Hash-based change detection (only sends if changed)
- Never block in render functions

### 4. XTDB Integration

```clojure
;; Use xt/template for dynamic values
(node/query node
  (xt/template
   (from :option-greeks [{:asset/ticker ~ticker} ...])))

;; Temporal queries (as-of)
(node/query node query-form {:current-time some-instant})

```

**Key points:**
- Deterministic IDs for idempotent operations
- Temporal by default
- `node/query` wrapper, not raw `xt/q`

### 5. Registry & Dispatch

**Domain registry:**

```clojure
(defonce domains (atom {}))

(defn register-domain! [domain-id db-node metadata]
  (swap! domains assoc domain-id {:db db-node :metadata metadata}))

(defn domain-db [domain-id]
  (get-in @domains [domain-id :db]))

```

**Integrant for lifecycle:**

```clojure
(defmethod ig/init-key :seon/xtdb-node [_ config]
  (start-node config))

```

---

## Implications for Primer

1. **Schema-first** - Define `:primer/scene`, `:primer/session`, etc.
2. **Central state atom** - `session-state` with watch for auto-refresh
3. **Capabilities function** - Discoverable API for agents
4. **db parameter** - Pass XTDB node, no globals
5. **Temporal storage** - Sessions/scenes stored with history
