---
type: decision
status: abandoned
tags: [decision, archive, trading, agent]
---

# Decisions Log: Algorithmic Trading Agent

**Last Updated:** 2025-12-21

This document records key architectural and design decisions made during the research and implementation of the algorithmic trading agent namespace.

---

## Quick Reference

| ID | Decision | Status |
|----|----------|--------|
| D1 | Single Namespace Design | Accepted (updated to `seon.trading.agent`) |
| D2 | Frozen Present Model | Accepted |
| D3 | Strategies as Data | Accepted (V2 - deferred) |
| D4 | Relative Metrics Only | Accepted |
| D5 | Function Signature Convention | Accepted |
| D6 | Session ID Format | Accepted |
| D7 | Agent Response Parsing | Accepted |
| D8 | `defn!` Macro | Rejected |
| D9 | ctx Validation | Deferred to V2 |
| D10 | Normalization Approach | Accepted (percentile rank) |

---

## Decisions

### D1: Single Namespace Design

**Date:** 2025-12-17
**Updated:** 2025-12-21
**Status:** Accepted

**Context:** The agent needs to analyze market data and get trading recommendations. Current trading functionality is scattered across multiple namespaces.

**Decision:** Create `seon.trading.agent` namespace structure that provides everything the agent needs.

**Rationale:**
- Reduces cognitive overhead for the agent
- No need to understand Clojure namespace mechanics
- Single point of entry for all trading functionality
- Keeps trading code together under `seon.trading.*`

**Structure:**

```
src/seon/trading/agent/
├── session.clj      ; Session management, REPL recording
├── functions.clj    ; Agent-facing wrappers
├── printers.clj     ; Pretty-printers per data type
└── template.clj     ; Session template generation

```

**Note:** Original proposal was `seon.algorithmic-trading`, updated to `seon.trading.agent` for consistency.

---

### D2: "Frozen Present" Model for Backtesting

**Date:** 2025-12-17
**Status:** Accepted

**Context:** During backtesting, the agent must not be able to see future data (no lookahead bias).

**Decision:** Use XTDB's `{:current-time T}` to lock the database to a specific point in time. The agent always thinks it's "today" - during backtesting, "today" is the historical date.

**Rationale:**
- XTDB provides this naturally
- No special code needed to prevent future peeking
- Same query interface works for live and backtest

**Alternatives Considered:**
- Filtering queries to exclude future dates - error-prone
- Separate backtest database - unnecessary complexity

**Consequences:**
- All queries automatically respect the frozen time
- Agent code works identically in live vs backtest mode

---

### D3: Strategies as Data, Not Code

**Date:** 2025-12-17
**Updated:** 2025-12-21
**Status:** Accepted (V2 - Implementation Deferred)

**Context:** How should trading strategies be represented?

**Decision:** Strategies are pure data (Clojure maps), not code. However, implementation is deferred to V2.

**Rationale:**
- Safer (can't execute arbitrary code)
- Serializable (can store in XTDB)
- Validatable (Malli schemas)
- Auditable (can see exactly what the strategy does)

**V1 Scope:** Analysis only. Agent uses existing `seon.trading.analysis/analyze-ticker` for recommendations.

**V2 Scope:** Custom strategy definition and validation via DSL.

See `research/strategy-dsl.md` for DSL design.

---

### D4: Relative Metrics Only

**Date:** 2025-12-17
**Status:** Accepted

**Context:** How to prevent strategies from using absolute values that could leak future information or be regime-dependent?

**Decision:** All metrics provided to the agent are normalized/relative (percentile rank, z-score, etc.). Absolute values are never exposed.

**Rationale:**
- Prevents future-peeking (can't compare to future absolute values)
- Regime-independent (works across different market conditions)
- Forces thinking in relative terms

**Alternatives Considered:**
- Allow absolute values with warnings - too easy to misuse
- Post-hoc validation - catches errors too late

**Consequences:**
- Need to research best normalization approaches
- Some strategies may be harder to express
- More robust backtests

---

### D5: Function Signature Convention

**Date:** 2025-12-19
**Status:** Accepted

**Context:** How should agent-facing functions be called?

**Decision:** All functions take `ctx` as first argument + single options map.

**Pattern:**

```clojure
(iv-rank ctx {:ticker "SPY"})
(iv-rank ctx {:ticker "SPY" :lookback 60})
(analyze ctx {:ticker "SPY"})

```

**Rationale:**
- Explicit is better than implicit
- Self-documenting at call sites
- Rails-like convention that's easy to learn
- No hidden state or macro magic

**Rejected Alternative:** Functions that read from ctx atom implicitly (e.g., `(iv-rank!)` reads `::ticker` from `@ctx`).

---

### D6: Session ID Format

**Date:** 2025-12-20
**Status:** Accepted

**Context:** How should sessions be identified?

**Decision:** CVCV pattern (consonant-vowel-consonant-vowel) like "bako", "meli", "toxa".

**Rationale:**
- Pronounceable - easy to communicate
- Short (4 chars) - easy to type
- 9,025 combinations - enough for parallel sessions
- No confusing characters (no 0/O, 1/l)

**Implementation:** `seon.trading.agent.session/gen-session-id`

---

### D7: Agent Response Parsing

**Date:** 2025-12-21
**Status:** Accepted

**Context:** How to extract executable code from agent responses?

**Decision:** Split on last `\n\n` - everything before is thinking, after is code. Each line in code section executes separately.

**Example:**

```
I'll check the IV rank for SPY.

(iv-rank ctx {:ticker "SPY"})
@ctx

```

Parses to:
- thinking: "I'll check the IV rank for SPY."
- code: ["(iv-rank ctx {:ticker \"SPY\"})" "@ctx"]

**Rationale:**
- Dead simple rule, no edge cases
- Predictable for agents to learn
- Handles all valid Clojure expressions including `@ctx`, `*1`, symbols

**Implementation:** `seon.trading.agent.session/parse-agent-response`

See `research/session-v2-notes.md` for full details.

---

### D8: `defn!` Macro

**Date:** 2025-12-19
**Status:** Rejected

**Context:** Should we create a custom macro for defining agent functions?

**Decision:** No. Use plain `defn` with explicit `ctx` argument.

**Rationale:**
- Macro adds "magic" that conflicts with explicit-is-better principle
- Complex to implement correctly
- Not needed - plain functions work fine
- Recording can be done via wrapper or explicit call

**Original Proposal (Rejected):**

```clojure
(defn! iv-rank!
  {:reads [[::ticker :string]]
   :writes [[::iv-rank :double]]}
  []
  ...)

```

---

### D9: ctx Atom Validation

**Date:** 2025-12-19
**Status:** Deferred to V2

**Context:** Should the ctx atom validate its contents?

**Decision:** No validation in V1. Revisit in V2 if agents make frequent errors.

**Rationale:**
- Adds complexity before proving core concept works
- Typos surface naturally when agent tries to use data
- Can add later if needed

**V2 Plan:** If needed, add Malli validation via atom watch.

---

### D10: Normalization Approach

**Date:** 2025-12-18
**Status:** Accepted

**Context:** How should metrics be normalized for the agent?

**Decision:** Percentile rank (0.0-1.0) as primary normalization.

**Rationale:**
- Intuitive ("73rd percentile")
- Bounded range makes comparisons easy
- Configurable via `:lookback` parameter
- Already implemented in `seon.trading.signals/iv-rank`

**Deferred:** Z-score normalization (add in V2 if needed).

See `research/normalization-approaches.md` for analysis.

---

## Resolved Pending Decisions

The following were marked as pending but have now been decided:

| Question | Decision |
|----------|----------|
| Normalization Approach | Percentile rank (D10) |
| REPL Recording | Auto-capture via `record-interaction!` |
| Strategy Logic | Deferred to V2 (D3) |
| Multi-Ticker | Deferred to V2 |
| Lookback Configuration | Per-function via `:lookback` option |

---

## References

- `prd.md` - Full PRD with V1/V2 scope
- `research/review-and-decisions.md` - Critical review
- `research/agent-experience-design.md` - UX design
- `research/session-v2-notes.md` - V2 parsing details
