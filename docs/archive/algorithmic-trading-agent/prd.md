> **Status: ARCHIVED** — Superseded — old XTDB-first design

> **Status: ARCHIVED** — Superseded — old XTDB-first design

# PRD: Algorithmic Trading Agent Namespace

**Status:** V1 Implementation
**Priority:** High
**Created:** 2025-12-17
**Updated:** 2025-12-21

---

## Problem Statement

We need a single, self-contained namespace where an LLM agent can:

1. **Explore market data** through well-documented trading primitives
2. **Analyze opportunities** using pre-built analysis functions
3. **Get trading recommendations** based on current market conditions

Currently, trading functionality is scattered across multiple namespaces (`seon.trading.signals`, `seon.trading.analysis`, `seon.db.queries`). The agent would need to understand Clojure namespace mechanics, require statements, and cross-namespace dependencies. This cognitive overhead is unnecessary.

**Goal:** A single namespace (`seon.trading.agent`) that provides a complete REPL-like environment for market analysis.

---

## Goals (V1)

1. **Single namespace experience** - Agent operates entirely within `seon.trading.agent`
2. **Explicit function signatures** - All functions take `ctx` + options map (no magic)
3. **Agent agency** - Agent queries data, analyzes tickers, interprets results
4. **Temporal integrity** - Sessions use frozen database snapshots (no future peeking)
5. **Training data capture** - Every interaction recorded for model training

---

## Core Concept: The ctx Atom

The agent works with a `ctx` atom that holds session state. All functions take `ctx` explicitly - no hidden global state.

```clojure
;; ctx is passed to all functions explicitly
(iv-rank ctx {:ticker "SPY"})
;; => {:seon.type/name :iv-rank
;;     :iv-rank/ticker "SPY"
;;     :iv-rank/value 0.73
;;     :iv-rank/label :elevated
;;     ...}

;; Agent can inspect session state
@ctx
;; => {:db/node #xtdb.node[...]
;;     :session/id "bako"
;;     :session/frozen-time #inst "2025-07-15T21:00:00Z"
;;     :config/default-lookback 252}

```

### Function Signature Convention

All agent-facing functions follow a consistent pattern:

```clojure
;; Pattern: (function-name ctx {:key value})
(iv-rank ctx {:ticker "SPY"})
(iv-rank ctx {:ticker "SPY" :lookback 60})
(analyze ctx {:ticker "SPY"})
(options-chain ctx {:ticker "SPY" :dte 30})

```

This is explicit, self-documenting, and matches Rails-style conventions.

### V1 Simplification

For V1, the ctx atom is **not validated**. Agents can add any namespaced key. Validation deferred to V2 if needed.

---

## Solution Design (V1)

### Namespace Structure

```
src/seon/trading/agent/
├── session.clj      ; Session creation, REPL recording, parsing
├── functions.clj    ; Agent-facing trading functions (to be implemented)
├── printers.clj     ; Pretty-printers per data type (to be implemented)
└── template.clj     ; Session template generation

```

### Agent-Facing Functions

All public functions:
- Take `ctx` as first argument
- Take a single options map as second argument
- Return a namespaced result map with `:seon.type/name`
- Pretty-print automatically via metadata

```clojure
(defn iv-rank
  "IV percentile rank - where current IV sits vs history.

  Returns 0.0-1.0:
    0.0 = IV lower than all history
    0.5 = IV at median
    1.0 = IV higher than all history

  Options:
    :ticker   - Required. Uppercase symbol, e.g., \"SPY\"
    :lookback - Optional. Days of history. Default 252 (1 year)"
  [ctx {:keys [ticker lookback] :or {lookback 252}}]
  (let [db (:db/node @ctx)
        ;; ... implementation wrapping seon.trading.signals/iv-rank
        ]
    {:seon.type/name :iv-rank
     :iv-rank/ticker ticker
     :iv-rank/value 0.73
     :iv-rank/label :elevated
     ...}))

```

### Function List (V1)

| Function | Purpose |
|----------|---------|
| `(overview ctx)` | Market overview for watched tickers |
| `(analyze ctx {:ticker "SPY"})` | Full analysis with recommendation |
| `(iv-rank ctx {:ticker "SPY"})` | IV percentile rank |
| `(skew ctx {:ticker "SPY"})` | Put-call skew index |
| `(term-structure ctx {:ticker "SPY"})` | IV term structure |
| `(options-chain ctx {:ticker "SPY" :dte 30})` | Options by DTE |

### Deferred to V2

- Strategy DSL and validation
- Backtesting engine
- Custom metric extension (`extend-ctx!`)
- Validated ctx atom

---

## Key Components

### 1. Session Management (V1)

```clojure
;; Create a new session
(require '[seon.trading.agent.session :as sess])
(def ctx (sess/create-session db {:frozen-time #inst "2025-07-15"}))

;; Session ID is pronounceable CVCV pattern
(:session/id @ctx)  ;; => "bako"

;; Session namespace for isolation
(:session/namespace @ctx)  ;; => "seon.agent.bako"

```

### 2. REPL Recording (V1)

```clojure
;; Every interaction captured for training data
(sess/record-interaction! ctx "(iv-rank ctx {:ticker \"SPY\"})" result)

;; Export session as training example
(sess/session->training-example ctx)
;; => {:messages [{:role "system" :content "..."}
;;                {:role "user" :content "..."}
;;                {:role "assistant" :content "..."}]
;;     :metadata {...}}

```

### 3. Function Discovery (V1)

```clojure
;; Agent can discover available functions (to be implemented)
(list-functions ctx)
;; => [{:name "iv-rank"
;;      :doc "IV percentile rank..."
;;      :args [:ticker :lookback]}
;;     ...]

```

### 4. Validated ctx Atom (V2 - Deferred)

```clojure
;; V2: Add validation to catch typos and type errors
(defn create-validated-ctx [schema initial-value]
  (let [a (atom initial-value)]
    (add-watch a :validation
      (fn [_ _ _ new-val]
        (when-let [errors (m/explain schema new-val)]
          (throw (ex-info "Invalid ctx update"
                   {:errors (format-errors errors)})))))
    a))

```

### 5. Strategy Validation (V2 - Deferred)

```clojure
;; V2: Validate strategy definitions
(validate-strategy strategy-map)
;; => {:valid? true}
;; OR
;; => {:valid? false
;;     :errors [{:path [:entry-conditions 0 :value]
;;               :message "Threshold must be between 0 and 1"}]}

```

---

## Constraints

1. **Single namespace** - All functionality accessible without requires
2. **No future peeking** - Database queries locked to session timestamp
3. **Spec'd everything** - Every key, every function, every strategy
4. **Agent-friendly errors** - Clear, actionable error messages
5. **Idempotent functions** - Same ctx state → same result
6. **Build on existing infrastructure** - Use `seon.trading.signals` primitives internally

---

## Resources to Learn From

| Resource | Purpose |
|----------|---------|
| `seon.trading.signals` | Existing signal primitives to wrap |
| `seon.trading.analysis` | Strategy definitions, recommendation engine |
| `seon.db.schema` | Malli schema patterns |
| `docs/prds/sql-migration/research/spec-first-design.md` | Malli function schema research |
| `docs/prds/sql-migration/research/malli-data-flow.md` | Query + schema integration |
| `reference-code/malli/` | Malli instrumentation patterns |

---

## Success Criteria

### V1 (Analysis Only)

1. **Agent can analyze a ticker** in under 5 function calls
2. **Session captures all interactions** for training data export
3. **Errors are agent-friendly** - clear messages, not stack traces
4. **Temporal isolation works** - agent can't see future data
5. **Functions are discoverable** - agent can list available functions

### V2 (Strategy & Backtest)

1. **Agent can define a valid strategy** using the spec as guide
2. **Invalid strategies are rejected** with clear fix instructions
3. **Backtests run correctly** with no future data leakage
4. **All primitives have specs** that agents can introspect

---

## Testing Checklist

### V1

- [ ] Session creation works with pronounceable IDs
- [ ] All public functions take ctx + opts map
- [ ] Query function respects temporal bounds (`:as-of`)
- [ ] Error messages are clear and actionable
- [ ] REPL recording captures input/output pairs
- [ ] Training data export produces valid format

### V2 (Deferred)

- [ ] ctx atom rejects unknown keys
- [ ] ctx atom rejects wrong types
- [ ] Strategy validation catches all constraint violations
- [ ] Backtest produces correct results on known data

---

## Implementation Phases

### V1 Phase 1: Session Infrastructure ✓

- [x] Create `seon.trading.agent.session` namespace
- [x] Session ID generation (CVCV pattern)
- [x] REPL input parsing (thinking/code separation)
- [x] Interaction recording
- [x] Training data export

### V1 Phase 2: Agent Functions (Current)

- [ ] Implement agent-facing wrappers in `seon.trading.agent.functions`
- [ ] `overview`, `analyze`, `iv-rank`, `skew`, `term-structure`, `options-chain`
- [ ] Wire up to existing `seon.trading.signals` and `seon.trading.analysis`
- [ ] Pretty-printers per data type

### V1 Phase 3: Integration

- [ ] Update `template.clj` to use real functions
- [ ] Test with real agent sessions
- [ ] Document learnings

### V2 Phase 1: Strategies (Deferred)

- Define Strategy spec
- Implement `define-strategy!` with validation
- Implement strategy storage

### V2 Phase 2: Backtesting (Deferred)

- Implement `backtest!` function
- Generate trade log and performance metrics
- Handle multi-ticker strategies

---

## Open Questions

1. **Should ctx persist across sessions?** (Store in XTDB vs ephemeral atom)
2. **How do we handle multi-ticker analysis?** (One ctx per ticker, or nested?)
3. **Should strategies be code or data?** (Data is safer, code is more flexible)
4. **What risk limits should be required?** (Max position size, max drawdown, etc.)

---

## Current Phase: V1 Implementation

Session infrastructure is complete. Now implementing agent-facing functions.

### The "Frozen Present" Model ✓

The agent always thinks it's "today". During backtesting, "today" is some historical date.

- Agent gets database snapshot locked to time T
- Can query all historical data UP TO time T (looking backward)
- Cannot see anything AFTER time T (no future peeking)
- XTDB's `{:current-time T}` provides this naturally

### Research Decisions (Completed)

#### R1: Normalization - DECIDED ✓

**Decision:** Percentile rank (0.0-1.0) as primary normalization.

- Intuitive for agents ("73rd percentile")
- Configurable lookback via `:lookback` parameter
- Z-score deferred to V2 if needed

See `research/normalization-approaches.md` for full analysis.

#### R2: REPL Recording - DECIDED ✓

**Decision:** Simple two-level storage implemented in `session.clj`.

- `:history` - Vector of input/output pairs (truncated for context)
- `:values` - Full values by content hash
- V2 parsing: split on last `\n\n`, thinking before, code after

See `research/session-v2-notes.md` for implementation details.

```clojure
;; REPL history - what the agent saw (limited, pretty-printed)
(:history @ctx)
;; => [{:input "(iv-rank ctx {:ticker \"SPY\"})"
;;      :output "{:iv-rank/value 0.73 ...}"
;;      :val-id "v_a1b2c3d4"
;;      :thinking "Checking IV rank"}
;;     ...]

;; Full values stored separately
(:values @ctx)
;; => {"abc123" {:iv-rank 0.73 :lookback 252 :raw-data [...]}}

```

Training data use cases:
1. Replay what agent saw → `:history`
2. Get full values for any step → `(get-in @ctx [:values "v_abc123"])`
3. Export as JSONL for model training

#### R3: Strategy Representation (V2 - Deferred)

**Status:** Research complete, implementation deferred to V2.

See `research/strategy-dsl.md` for DSL design. Key decisions:
- Pure data (no code) - safer, serializable, validatable
- Conditions reference only relative metrics
- Support multi-ticker (e.g., pairs trading, sector rotation)

#### R4: Rails-Like Conventions - DECIDED ✓

**Decision:** Explicit function signatures with sensible defaults.

- All functions: `(fn ctx {:opts map})`
- No macros, no implicit state
- Clear errors with suggestions
- Functions discoverable via `list-functions`

See `research/agent-experience-design.md` for full UX design.

---

## Deliverables

### V1 (Current)

- [x] `src/seon/trading/agent/session.clj` - Session management, REPL recording
- [ ] `src/seon/trading/agent/functions.clj` - Agent-facing wrappers
- [ ] `src/seon/trading/agent/printers.clj` - Pretty-printers
- [x] `src/seon/trading/agent/template.clj` - Session template (needs update)
- [ ] `test/seon/trading/agent_test.clj` - Tests
- [x] `docs/prds/algorithmic-trading-agent/` - This PRD and research

### V2 (Deferred)

- [ ] `src/seon/trading/agent/strategy.clj` - Strategy specs and validation
- [ ] `src/seon/trading/agent/backtest.clj` - Backtesting engine
