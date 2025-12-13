# Oracle Research Findings

**Date:** 2025-11-29
**Status:** Phase 3 Research - Initial Signal Analysis Complete

---

## Key Discovery: IV Rank Extremes Are Predictable

We analyzed 78 extreme IV rank signals across 5 symbols over 6 months and found:

### Win Rates by Signal Type

| Signal | Threshold | Win Rate | Best Symbols |
|--------|-----------|----------|--------------|
| **Long-Vol** | IV Rank ≤ 15% | **84.2%** | AAPL (100%), NVDA (80%) |
| **Short-Vol** | IV Rank ≥ 85% | 74.6% | MSFT (100%), AAPL (87%) |
| **Overall** | Either extreme | **76.9%** | MSFT, AAPL, SPY |

### Symbol Reliability

| Symbol | Win Rate | Notes |
|--------|----------|-------|
| MSFT | **100%** (8/8) | Best performer, all short-vol |
| AAPL | **90.5%** (19/21) | Reliable both directions |
| SPY | **88.9%** (8/9) | Index = predictable |
| GOOGL | 76.5% (13/17) | Good but mixed |
| NVDA | 52.2% (12/23) | Too volatile - avoid for training |

### Key Insight

**Low IV rank (long-vol) is MORE predictable than high IV rank (short-vol).**

This makes sense: IV tends to have a floor (can't go much below realized vol) but no ceiling (fear spikes are unbounded). When IV is at historical lows, mean reversion is almost guaranteed.

---

## Golden Examples for Training

### Short-Vol Examples (sell premium when IV expensive)

| Date | Symbol | IV Rank | IV Change | Quality |
|------|--------|---------|-----------|---------|
| 2025-10-29 | MSFT | 98.9% | -33.3% | ★★★ Clean |
| 2025-11-25 | GOOGL | 97.8% | -28.9% | ★★★ Gradual |
| 2025-10-10 | SPY | 97.8% | -20.0% | ★★★ Clean |
| 2025-11-20 | AAPL | 98.9% | -27.0% | ★★☆ |
| 2025-11-21 | MSFT | 96.7% | -19.0% | ★★★ Clean |

### Long-Vol Examples (buy premium when IV cheap)

| Date | Symbol | IV Rank | IV Change | Quality |
|------|--------|---------|-----------|---------|
| 2025-11-03 | NVDA | 8.9% | +42.8% | ★★★ Best |
| 2025-10-20 | AAPL | 3.3% | +25.0% | ★★★ Cleanest |
| 2025-10-21 | AAPL | 8.9% | +27.8% | ★★★ Clean |
| 2025-10-14 | NVDA | 12.2% | +43.0% | ★★☆ Volatile |
| 2025-10-30 | SPY | 10.0% | +15.0% | ★★★ Clean |

### Failure Examples (for negative training)

| Date | Symbol | IV Rank | What Happened | Lesson |
|------|--------|---------|---------------|--------|
| 2025-10-16 | NVDA | 90% | IV stayed high | Earnings proximity |
| 2025-10-13 | GOOGL | 13% | IV dropped further | Check term structure |
| 2025-10-09 | NVDA | 91% | IV spiked higher | News risk |

---

## Reasoning Trace Generation Strategy

### Can We Template Without LLM?

**Yes, 80%+ of traces can be templated.**

The reasoning follows predictable patterns:

```
1. SCAN     → Run analyze-multiple, find extreme signal
2. VERIFY   → Check supporting indicators (term structure, skew)
3. CATALYST → Look for events that justify/contradict
4. CONCLUDE → Make recommendation with reasoning
```

For each golden example, we know:
- T0 date, ticker, signal type
- Which queries to run
- What the results were
- The correct outcome

**Template Structure:**

```clojure
;; {date} {time} ET - {session_type}
;; Let me scan the watchlist for opportunities...
(analyze-multiple node {tickers} {:as-of #inst "{date}"})
;; => {ticker} shows {signal_type} signal: IV Rank {iv_rank}

;; {curiosity_phrase} Let me dig deeper...
({verification_fn} node "{ticker}" {:as-of #inst "{date}"})
;; => {result}

;; {interpretation}
;; CONCLUSION: {reasoning}
;; RECOMMENDATION: {strategy}
```

**Where LLM Adds Value:**
- Varying the "curiosity phrases" for diversity
- Generating natural commentary between queries
- Handling edge cases that don't fit templates
- Creating variations (different query orders, depths)

### Hybrid Approach (Recommended)

1. **Clojure generates the structure** - Runs actual queries at T0, captures results
2. **Templates provide 80% of traces** - Fill in slots with real data
3. **LLM adds variety** - Rephrase templates for diversity (batch operation)
4. **LLM handles edge cases** - Complex reasoning, unusual patterns

This is **10-100x more efficient** than using LLM for every trace from scratch.

---

## Data Requirements for 10k Examples

Current data: 78 extreme signals over 6 months, 5 symbols

To reach 10k examples:

| Approach | Examples | Notes |
|----------|----------|-------|
| Current golden examples | 78 | Raw signals |
| + Multiple expirations per signal | ~400 | 5 expiries each |
| + Query variations (order, depth) | ~2,000 | 5 variations |
| + Phrasing variations | ~10,000 | 5 phrasings |
| + More symbols (QQQ, AMD, etc.) | ~20,000+ | 5 more symbols |

**Conclusion:** 10k is achievable with current data + variations.

---

## Next Steps

1. **Build trace generator** - Clojure fn that takes golden example → reasoning trace
2. **Create template library** - 5-10 templates for each strategy type
3. **Validate traces** - Ensure queries actually return expected results at T0
4. **Add LLM variety** - Batch rephrase for diversity
5. **Generate 10k examples** - Combine all approaches

---

## Research Questions (From Vision Doc)

These techniques from "Training AI for Financial Trading.md" need investigation:

### Hindsight Experience Replay (HER)
- **Status:** Partially implemented (Oracle identifies good trades)
- **Gap:** Need to label failed trades as "success for different goal"
- **Research:** How to relabel borderline cases?

### Oracle Policy Distillation (OPD)
- **Status:** Not started
- **Gap:** Need loss function: L = λ₁L_policy + λ₂L_value + λ₃L_distill + λ₄L_aux
- **Research:** What's the right λ balance?

### Symbolic Regression (DEAP)
- **Status:** Not started
- **Gap:** Need to extract formulas from successful traces
- **Research:** Can we use DEAP with Clojure s-expressions?

### Chain-of-Thought Training
- **Status:** Template approach identified
- **Gap:** Need to validate templates produce learnable traces
- **Research:** What's minimum trace length for learning?
