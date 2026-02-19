# Limits and Defaults Convention

**Date:** 2026-01-29
**Author:** Agent d52e (Research)
**Status:** Proposed

---

## Summary

This document establishes conventions for handling numeric limits, defaults, and constraints in Malli schemas and function parameters. The goal is to avoid arbitrary "magic numbers" that cause bugs or confusion.

---

## Findings

### 1. Claude CLI `--max-turns` Behavior

**Key Finding:** Despite documentation claiming "no default limit", Claude CLI v2.1.x
**does have an undocumented ~100 turn limit** when the flag is omitted.

From the official documentation (INCORRECT):
- `--max-turns` - Limit the number of agentic turns (print mode only). **No limit by default.**

**Reality:** In practice, agents stop at exactly 100-101 turns when `--max-turns` is not passed.
We observed this repeatedly with multiple agents getting "stuck" at turn 101.

**Fix:** Pass a high value (10000) to effectively disable the limit:

```clojure
;; src/seon/ai/claude.clj
;; Override undocumented 100 turn default - real limits are API tokens and cost budgets
effective-max-turns (or max-turns 10000)
```

**TODO:** File bug report with Anthropic about undocumented default behavior.

### 2. Categories of Limits Found in Codebase

| Category | Example | Source | Appropriate? |
|----------|---------|--------|--------------|
| **External system constraints** | nREPL port 7889-7999 | Our allocation | ✅ Yes - documents our reservation |
| **Domain-specific bounds** | IV rank 0.0-1.0 | Mathematical definition | ✅ Yes - actual constraint |
| **API rate limits** | delay-ms 100 | ThetaData docs | ✅ Yes - external requirement |
| **Batch sizes** | 1000 docs per batch | Performance tuning | ⚠️ Document rationale |
| **Display truncation** | max-length 500 | UX decision | ⚠️ Make configurable |
| **Arbitrary caps** | max-turns 999999 | Workaround | ❌ Remove |
| **Exploration limits** | max-turns 100 | Research/testing | ⚠️ Lower for exploration only |

### 3. Current Arbitrary Limits in Codebase

#### Workaround (documented)

1. **`max-turns 10000`** - `src/seon/ai/claude.clj`
   - Context: Claude CLI has undocumented 100 turn default
   - Solution: Pass 10000 to effectively disable the limit
   - Real limits: Anthropic API token limits and cost budgets
   - TODO: Remove once Anthropic fixes or documents the behavior

2. **`scrollTop = 9999999`** - `src/seon/web/sse.clj:62`
   - Problem: Magic number for "scroll to bottom"
   - Fix: Use `scrollHeight` instead of arbitrary large number

3. **`max-turns {:max 100}`** - `src/seon/claude/exploration.clj:60`
   - Problem: Arbitrary cap in exploration schema
   - Context: This is research code, low cap is intentional for safety
   - Fix: Document why exploration has a low cap

#### Acceptable (well-documented external constraints)

1. **nREPL ports 7889-7999** - Documented allocation for agent sessions
2. **Ticker max 10 chars** - Realistic ticker symbol bound
3. **IV rank 0.0-1.0** - Mathematical definition
4. **API delay 100ms** - ThetaData rate limit baseline

---

## Proposed Convention

### Rule 1: Don't Set Arbitrary Defaults for "No Limit" Scenarios

**Bad:**
```clojure
;; Arbitrary large number to mean "unlimited"
::sdk/max-turns (or max-turns 999999)
```

**Good:**
```clojure
;; Don't pass the flag at all when unlimited
(when max-turns
  ["--max-turns" (str max-turns)])
```

### Rule 2: Document the Source of Every Limit

When adding a `:max` or `:min` constraint to a schema, document where the limit comes from:

```clojure
;; GOOD - documents source
(schema/register! ::nrepl-port
  [:int {:min 7889 :max 7999
         :description "nREPL port for agent sessions (reserved range)"}])

(schema/register! ::iv-rank
  [:double {:min 0.0 :max 1.0
            :description "IV percentile rank (mathematical: 0-100%)"}])

;; BAD - arbitrary, undocumented
(schema/register! ::timeout
  [:int {:min 1000 :max 300000}])  ; Why 300000? Why 1000?
```

### Rule 3: External vs Internal Limits

**External limits** (from APIs, specs, protocols):
- Document the source in the schema description
- These are hard requirements - violating them causes failures

**Internal limits** (our decisions for safety/UX):
- Document the rationale in comments
- Make them configurable when reasonable
- Review periodically - may no longer apply

### Rule 4: Use Infinity-Safe Patterns

When a parameter can legitimately be "unlimited":

```clojure
;; Schema: Optional with no max
(schema/register! ::max-turns
  [:int {:min 1
         :description "Max turns (optional - no limit if not set)"}])

;; Function: Don't default to arbitrary large number
(defn launch-agent!
  [{::keys [max-turns] :as opts}]
  ;; Only pass to CLI if explicitly set
  (cond-> base-args
    max-turns (into ["--max-turns" (str max-turns)])))
```

### Rule 5: Batch Sizes and Performance Tuning

Batch sizes are internal limits for performance. Document:
- Why this number (memory, API limits, latency)
- How to override if needed

```clojure
(def ^:const xtdb-batch-size
  "Number of documents per XTDB transaction.
   Tuned for memory usage vs transaction overhead.
   Increase for bulk loads, decrease for memory-constrained envs."
  1000)
```

---

## Malli Schema Guidance

### When to Add `:max` Constraint

✅ **Add `:max` when:**
- External API enforces the limit
- Mathematical/domain constraint exists (percentages, ratios)
- Protocol spec defines the bound
- Memory/performance safety (with documentation)

❌ **Don't add `:max` when:**
- "Just to be safe" with no clear reason
- To prevent hypothetical abuse (use rate limiting instead)
- The underlying system has no limit

### When to Add `:min` Constraint

✅ **Add `:min` when:**
- Zero/negative values are invalid (e.g., counts, durations)
- Empty values cause failures (min 1 for required strings)
- Domain logic requires it

### Pattern: Optional Unbounded Parameter

```clojure
;; Schema allows any positive int
(schema/register! ::max-results
  [:int {:min 1
         :description "Max results to return. Omit for all results."}])

;; Function handles omission
(defn search [{::keys [max-results]}]
  (cond-> (base-query)
    max-results (add-limit max-results)))
```

---

## Action Items

1. **Fix max-turns in launch-agent!** - Remove `999999` default
2. **Fix scrollTop magic number** - Use proper scrollHeight
3. **Audit exploration.clj** - Document why max 100 is intentional
4. **Update CONVENTIONS.md** - Add limits section
5. **Add to PR checklist** - "Are new limits documented?"

---

## References

- Claude Code CLI reference: https://code.claude.com/docs/en/cli-reference.md
- ClaudeLog FAQ on max-turns: https://claudelog.com/faqs/what-is-max-turns-in-claude-code/
