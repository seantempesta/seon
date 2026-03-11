---
type: research
status: abandoned
tags: [research, archive, trading, agent]
---

# Critical Review: Algorithmic Trading Agent Design

**Reviewer:** Claude Opus 4.5 (Senior Architect)
**Date:** 2025-12-19
**Status:** Review Complete

---

## Executive Summary

The research is thorough and well-documented, but there are significant tensions between the research recommendations and the PRD's original vision. The biggest risk is **over-engineering V1** - we're designing infrastructure for training data capture and multi-ticker strategies before proving the core concept works.

**Recommendation:** Simplify aggressively. Build the minimal viable ctx-based agent experience first, then add recording and complex DSL features.

---

## 1. Conflicts & Tensions

### 1.1 PRD vs Research: The `defn!` Macro

**Conflict:** The PRD proposes a `defn!` macro with spec enforcement:

```clojure
(defmacro defn!
  "Define a public trading function..."
  [name docstring spec-map & body]
  ...)

```

But the REPL recording research recommends **explicit `rec!` recording**:

```clojure
(rec! ctx "Checking IV" (iv-rank ctx {:ticker "SPY"}))

```

These are philosophically opposed:
- `defn!` = automatic instrumentation (magic)
- `rec!` = explicit recording (no magic)

The research correctly identifies that auto-capture is noisy and loses context, but the PRD still contains the `defn!` macro approach.

**Recommendation:** Remove `defn!` macro from PRD. Use plain `defn` for primitives, explicit `rec!` for recording. The agent can choose what to record.

### 1.2 Two Normalization Approaches Recommended

The normalization research recommends:
1. **Primary:** Percentile rank (0-1 scale)
2. **Secondary:** Z-score (for mean-reversion)

But the existing `seon.trading.signals/iv-rank` already returns percentile rank. The research doesn't clearly address:
- Should we add z-score variants of existing functions?
- Or should normalization be a separate layer on top?

**Recommendation:** Don't create parallel z-score functions. Keep percentile rank as the only agent-facing normalization. Z-score can be added later if agents actually need it. Right now it's speculative.

### 1.3 Strategy DSL vs Existing Analysis

The PRD envisions agents defining strategies as data:

```clojure
{:strategy/entry-conditions
 [[:metric/iv-rank :> 0.80]
  [:metric/skew-index :> 0.04]]}

```

But `seon.trading.analysis` already has hardcoded recommendation logic:

```clojure
(and (= iv-rank-label :high)
     (#{:normal :elevated} skew-label))
;; => :short-vol

```

These are two different philosophies:
- **PRD:** Agent defines arbitrary strategies
- **Existing code:** System provides curated recommendations

**Recommendation:** Clarify scope. For V1, the agent should use the existing recommendation engine (`analyze-ticker`), not define custom strategies. Strategy DSL is a V2 feature.

### 1.4 ctx Atom Validation vs Scratchpad Extension

The PRD says:

```clojure
;; Atom rejects unknown keys
(swap! ctx assoc :random/key 123)
;; => Error: Unknown key :random/key

```

But also:

```clojure
;; Agent can extend the spec
(extend-ctx! ::my-custom-metric [:maybe :double])

```

This is contradictory. If agents can extend the schema at runtime, the validation is only catching typos, not enforcing structure.

**Recommendation:** Pick one:
- **Option A (Recommended):** Open map with optional validation. Agent can add any namespaced key. Validation only on known keys.
- **Option B:** Closed map, no extension. Simpler but less flexible.

Don't pretend we have strict validation while allowing runtime extension.

---

## 2. Gaps & Missing Pieces

### 2.1 No Design for Backtest Execution Loop

The research covers:
- How to represent strategies (DSL)
- How to record sessions (REPL capture)
- How to normalize data (percentile rank)

But **nobody designed the backtest execution loop**:
- How does the backtester step through time?
- How are positions tracked and P&L calculated?
- How do we handle fills (instant? slippage?)?
- What's the output format of a backtest?

This is a significant gap. The `backtest!` function in the PRD is hand-waved:

```clojure
(backtest!
  {:start-date #inst "2024-01-01"
   :end-date #inst "2024-12-31"
   ...})

```

**Action Required:** Research needed on backtest execution before Phase 4 implementation.

### 2.2 No Position/Trade Management

The strategy DSL defines entry/exit conditions, but:
- How are positions tracked in ctx?
- How do we represent open positions?
- How do we handle partial fills or multiple entries?
- How do we track P&L?

The existing code has no position management.

**Action Required:** Design position tracking schema before implementing strategies.

### 2.3 Metric Availability During Backtest

The research mentions metrics like `:metric/gamma-rent`, but:
- `gamma-rent` requires a spot price
- In backtest, where does spot price come from?
- Are all metrics available for all historical dates?
- What happens when a metric can't be computed?

**Action Required:** Define metric availability and error handling strategy.

### 2.4 Database Has Data But lookback Is Ignored

The existing code has a critical issue flagged in the code itself:

```clojure
;; lookback - Lookback period in days (CURRENTLY IGNORED - queries all history)
;;            TODO: Implement temporal filtering using XTDB v2 system-time ranges

```

This means:
- The normalization research recommends configurable lookbacks
- But the implementation doesn't support lookbacks
- Percentile rank is computed over ALL history, not rolling window

**Action Required:** Implement rolling window support in `seon.trading.signals` before proceeding.

### 2.5 Missing: How Agent Receives ctx

The PRD says:

```clojure
;; System injects ctx at session start
(def ctx (atom {...}))

```

But how?
- Is this a global var in the namespace?
- Or passed to a session-start function?
- What happens with concurrent sessions?

**Action Required:** Design session lifecycle clearly.

---

## 3. Complexity Concerns

### 3.1 REPL Recording is Premature

The REPL recording research is extensive (900+ lines), designing:
- Two-level storage (limited output + full values)
- Content-addressed hashing with SHA-256
- JSONL export for training
- Annotation and tagging after-the-fact

**Reality check:** We don't have a working agent session yet. We're building training data infrastructure before proving the concept works.

**Recommendation:** For V1, use a simple approach:

```clojure
(def session-log (atom []))
(defn log-interaction! [fn-name result]
  (swap! session-log conj {:fn fn-name :result result :timestamp (now)}))

```

Add the sophisticated recording later when we actually need to train models.

### 3.2 Strategy DSL is Over-Designed for V1

The strategy DSL research includes:
- Recursive logical expressions (`[:and ... [:or ...]]`)
- Cross operators (`:crosses-above`, `:crosses-below`)
- Per-condition lookback overrides
- Multiple position sizing modes
- Multi-ticker support (V2+)

For V1, an agent should be able to:
1. Analyze a ticker using existing `analyze-ticker`
2. Accept or reject the recommendation

That's it. Custom strategy definition is a V2 feature.

**Recommendation:** Remove strategy DSL from V1 scope. Use existing analysis pipeline.

### 3.3 Validated ctx Atom Adds Minimal Value

Implementing a self-validating atom:

```clojure
(add-watch a :validation
  (fn [_ _ _ new-val]
    (when-let [errors (m/explain schema new-val)]
      (throw ...))))

```

This catches typos in key names. Is that worth the complexity?

The agent will call functions like `(iv-rank!)` that read/write specific keys. If the agent misspells a key in manual `swap!`, they'll get an error when they try to use the data anyway.

**Recommendation:** Skip validation in V1. Use convention (namespaced keys) and let errors surface naturally.

---

## 4. Decisions Needed

### D5: V1 Scope Definition

**Decision Required:** What is actually in V1?

| Feature | Proposal |
|---------|----------|
| Single namespace | Yes |
| ctx atom (unvalidated) | Yes |
| Wrapped existing primitives | Yes |
| `list-functions`, `what-do-i-need?` | Yes |
| REPL recording | No (defer to V2) |
| Strategy DSL | No (defer to V2) |
| Backtesting | No (defer to V2) |
| Custom metrics via `extend-ctx!` | No (defer to V2) |

**Recommendation:** Accept this scope. V1 is pure analysis, V2 adds strategies and backtesting.

### D6: Function Signature Style

**Decision Required:** How do primitives get db/temporal context?

**Option A: Read from ctx atom (PRD proposal)**

```clojure
(defn iv-rank! []
  (let [{::keys [ticker lookback]} @ctx
        db (:seon.db/query @ctx)]
    ...))

```

**Option B: Explicit parameters (existing code pattern)**

```clojure
(defn iv-rank [db ticker lookback opts]
  ...)

```

**Option C: Hybrid - ctx for context, explicit for inputs**

```clojure
(defn iv-rank! [ticker]
  (let [db (::db @ctx)
        as-of (::as-of @ctx)]
    ...))

```

**Recommendation:** Option C. The ticker is the agent's choice, the context is system-provided. This makes function calls more explicit and readable:

```clojure
(iv-rank! "SPY")  ; Clear what's being analyzed
;; vs
(swap! ctx assoc ::ticker "SPY")
(iv-rank!)  ; What ticker? Have to check ctx

```

### D7: Error Handling Strategy

**Decision Required:** How do we handle errors for the agent?

**Options:**
1. Throw exceptions with agent-friendly messages
2. Return error maps `{:error "..." :suggestion "..."}`
3. Write errors to ctx `{::last-error "..."}`

**Recommendation:** Option 2 for recoverable errors, Option 1 for programming errors.

```clojure
(iv-rank! "INVALID-TICKER")
;; => {:error :no-data
;;     :message "No options data for INVALID-TICKER"
;;     :suggestion "Check ticker symbol or try SPY, QQQ, AAPL"}

```

### D8: Namespace Name

**Decision Required:** `seon.algorithmic-trading` vs `seon.agent` vs `seon.trading.agent`

**Recommendation:** `seon.trading.agent` - keeps trading code together, clear purpose.

### D9: Session Lifecycle

**Decision Required:** How does a session start and end?

**Recommended Design:**

```clojure
;; Start a new session
(def ctx (start-session! {:as-of #inst "2024-06-15T16:00:00Z"}))

;; Use the session
(iv-rank! ctx "SPY")
(skew-index! ctx "SPY")
(analyze! ctx "SPY")

;; End session (optional - for cleanup/saving)
(end-session! ctx)

```

The ctx is returned from `start-session!`, not a global var. This supports concurrent sessions and testing.

---

## 5. Integration Issues

### 5.1 Naming Conflicts

The PRD uses `::iv-rank` as a ctx key, but `seon.trading.signals/iv-rank` is a function.

If both are in scope:

```clojure
(ns seon.trading.agent
  (:require [seon.trading.signals :as sig]))

;; Is this the function or the key?
iv-rank  ; ambiguous in conversation

```

**Recommendation:** Use different naming:
- Functions: `iv-rank!`, `skew-index!` (with bang)
- Keys: `:result/iv-rank`, `:result/skew-index` (result prefix)

### 5.2 Existing Code Doesn't Support Rolling Windows

`seon.trading.signals/iv-rank` ignores the lookback parameter:

```clojure
;; lookback - Lookback period in days (CURRENTLY IGNORED)

```

The new namespace can't provide configurable lookbacks until this is fixed.

**Action Required:** Fix `seon.trading.signals` before wrapping in new namespace.

### 5.3 Analysis Returns Strategy Recommendations, Not Raw Signals

`seon.trading.analysis/analyze-ticker` returns:

```clojure
{:recommendation :short-vol
 :confidence :high
 :strategies [:iron-condor :short-strangle]}

```

This is a curated view, not raw data. If the agent namespace just wraps this, it's not adding much value.

**Question:** Should the agent have access to raw signals and make its own decisions? Or use the existing recommendation engine?

**Recommendation:** Start with the existing recommendation engine. Agent can request raw signals separately if needed.

---

## 6. Risk Assessment

### 6.1 Biggest Risk: Over-Engineering Before Validation

We're designing:
- Training data capture
- Strategy DSL
- Backtest infrastructure
- Validated ctx atoms

Before validating:
- Can an LLM agent actually use this effectively?
- Does the single-namespace approach help?
- What errors do agents actually make?

**Mitigation:** Build minimal V1, test with real agent sessions, then iterate.

### 6.2 Risk: Lookback Not Implemented

The entire normalization research assumes configurable lookbacks. The code doesn't support them.

**Mitigation:** Implement rolling window support as Phase 0 (prerequisite).

### 6.3 Risk: No Historical Data for Backtesting

The database has options data, but:
- What date range?
- Is it complete?
- Can we simulate positions?

**Mitigation:** Validate data availability before designing backtest.

### 6.4 Risk: XTDB v2 Temporal Queries Are Complex

The "frozen present" model relies on XTDB's `{:current-time T}`. This is well-understood, but:
- Does it work with all query patterns?
- Are there edge cases with valid-time vs system-time?

**Mitigation:** Write tests for temporal isolation before building on it.

---

## 7. Recommended V1 Implementation Plan

### Phase 0: Prerequisites (Do First)

1. Implement rolling window support in `seon.trading.signals`
2. Write tests validating temporal isolation
3. Verify data availability for key tickers

### Phase 1: Minimal Agent Namespace

1. Create `seon.trading.agent` namespace
2. Implement `start-session!` returning ctx atom
3. Wrap existing primitives with ctx-aware signatures:
   - `(iv-rank! ctx ticker)` -> percentile
   - `(skew-index! ctx ticker)` -> spread
   - `(term-slope! ctx ticker)` -> slope
4. Implement `list-functions` for discovery
5. Implement `analyze!` wrapping `seon.trading.analysis/analyze-ticker`

### Phase 2: Test with Real Agent

1. Have an LLM agent use the namespace
2. Document what works and what doesn't
3. Identify actual pain points

### Phase 3: Iterate Based on Feedback

- Add recording if training data is needed
- Add strategy DSL if custom strategies are needed
- Add backtesting if that's the bottleneck

---

## 8. Summary of Decisions

| ID | Decision | Status |
|----|----------|--------|
| D1 | Single namespace design | Accepted |
| D2 | Frozen present model | Accepted |
| D3 | Strategies as data | Accepted (defer to V2) |
| D4 | Relative metrics only | Accepted |
| D5 | V1 scope (analysis only, no strategy/backtest) | **Proposed** |
| D6 | Function signature: explicit ticker, ctx for context | **Proposed** |
| D7 | Error handling: return error maps | **Proposed** |
| D8 | Namespace: `seon.trading.agent` | **Proposed** |
| D9 | Session lifecycle: `start-session!` returns ctx | **Proposed** |

---

## 9. Action Items

### Before Implementation

- [ ] Fix rolling window in `seon.trading.signals/iv-rank`
- [ ] Write temporal isolation tests
- [ ] Validate data availability
- [ ] Accept/reject proposed decisions (D5-D9)

### For V1 Implementation

- [ ] Create minimal `seon.trading.agent` namespace
- [ ] Test with real LLM agent
- [ ] Document findings

### Defer to V2

- [ ] REPL recording infrastructure
- [ ] Strategy DSL and validation
- [ ] Backtesting engine
- [ ] Custom metric extension (`extend-ctx!`)
