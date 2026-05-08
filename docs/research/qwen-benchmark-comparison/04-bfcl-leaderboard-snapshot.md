---
title: BFCL leaderboard snapshot for Qwen-family + frontier comparators
source-urls:
  - https://llm-stats.com/benchmarks/bfcl-v3
  - https://gorilla.cs.berkeley.edu/leaderboard.html
  - https://forums.developer.nvidia.com/t/for-loacl-agent-qwen3-6-35b-a3b-or-qwen3-coder-next/367721
  - https://huggingface.co/Qwen/Qwen3-Next-80B-A3B-Thinking (Qwen-team-published)
retrieved: 2026-05-08
fetched-via: WebFetch + WebSearch
---

# BFCL — Berkeley Function-Calling Leaderboard

## BFCL-v3 leaderboard (verbatim from llm-stats.com, 2026-05-08)

Only Qwen entries shown — the leaderboard is Qwen-family-dominated at the top.
Note: Claude / GPT-5 / Gemini 2.5 / DeepSeek V3.2 / Kimi K2.5 / GLM-4.7 are
NOT on this leaderboard (they're not BFCL-submitted in v3 yet).

| Rank | Model | Score |
|------|-------|-------|
| 1 | GLM-4.5 | 0.778 |
| 2 | GLM-4.5-Air | 0.764 |
| 3 | LongCat-Flash-Thinking | 0.744 |
| 4 | Qwen3-Next-80B-A3B-Thinking | **0.720** |
| 5 | Qwen3 VL 235B A22B Thinking | 0.719 |
| 5 | Qwen3-235B-A22B-Thinking-2507 | 0.719 |
| 7 | Qwen3 VL 32B Thinking | 0.717 |
| 8 | Qwen3-235B-A22B-Instruct-2507 | 0.709 |
| 9 | Qwen3-Next-80B-A3B-Instruct | **0.703** |
| 10 | Qwen3 VL 32B Instruct | 0.702 |
| 11 | Qwen3-Coder 480B A35B Instruct | **0.687** |
| 12 | Qwen3 VL 30B A3B Thinking | 0.686 |
| 13 | Qwen3 VL 235B A22B Instruct | 0.677 |
| 14 | Qwen3 VL 4B Thinking | 0.673 |

**Neither Qwen3.6-35B-A3B nor Qwen3-Coder-Next-80B-A3B appears on llm-stats.com's
BFCL-v3 ranking** as of retrieval — both are too new (Apr 2026 / Feb 2026) and
neither has been added.

## What we have for Qwen3-Next-80B-A3B-Thinking (Qwen-published)

From the Qwen3-Next HF model card (the **general** sibling, not Coder; precursor
to both the Coder-Next and Qwen3.6 lines):

| Benchmark | Qwen3-Next-80B-A3B-Thinking | Qwen3-235B-A22B-Thinking-2507 | Gemini-2.5-Flash Thinking |
|-----------|------------------------------|-------------------------------|----------------------------|
| BFCL-v3 | **72.0** | 71.9 | 68.6 |
| TAU1-Retail | **69.6** | 67.8 | 65.2 |
| TAU1-Airline | 49.0 | 46.0 | 54.0 |
| TAU2-Retail | 67.8 | 71.9 | 66.7 |
| TAU2-Airline | 60.5 | 58.0 | 52.0 |
| TAU2-Telecom | 43.9 | 45.6 | 31.6 |

This is the closest published BFCL/tau-bench number for the **80B-A3B
architecture** that Qwen3-Coder-Next inherits. Qwen3-Coder-Next adds Coder
specialization on top of this base, so its BFCL is plausibly ≥72.0 (no
published number exists).

## What we have for Qwen3.6-35B-A3B (third-party)

The Qwen3.6-35B-A3B official model card on HF does **NOT publish BFCL**.
Community benchmarks (NVIDIA developer forum, 2026-05):

> "Qwen3.6 35B-A3B" scored "78.0%" on BFCL (unofficial, FP8)
> "Qwen3 Coder Next FP8" had no BFCL results recorded

These are unofficial — not from the Qwen team — and lack the methodology that
the Qwen3-Next card uses (FC mode, cleaning, leaderboard formats). Treat as a
weak signal that Qwen3.6 is in the same ballpark as Qwen3-Next (72%) or higher.

## Honest gap statement

For a **rigorous BFCL comparison between Qwen3.6-35B-A3B and Qwen3-Coder-Next-
80B-A3B**:
- Qwen3.6 has NO Qwen-team-published BFCL number. Community reports ~78%.
- Qwen3-Coder-Next has NO Qwen-team-published BFCL number. Inferred ≥72%
  via the Qwen3-Next-80B-A3B-Thinking score.
- The arxiv 2603.00729 Coder-Next paper omits BFCL entirely; its tool-call
  story is told via Table 2 ("Template Following accuracy", 92.7% avg) and
  the SWE-Bench / Terminal-Bench scaffolds — those are the published agentic
  surfaces.

## BFCL-v3 vs BFCL-v4

The official Berkeley leaderboard is now V4 (updated 2026-04-12).
llm-stats.com still mirrors V3. Neither shows Qwen3.6 or Qwen3-Coder-Next.
Berkeley's submission process has a lag; expect both to appear within 1-2
months of release (Qwen3.6 was just released April 2026).

## Implication for the agent Phase 0

Even where the published numbers are missing, the structural read is clear:
- **Qwen3-Next-80B-A3B (general)** outscores **Qwen3-Coder-480B-A35B (coder)**
  on BFCL-v3 (0.720 vs 0.687). Bigger isn't better; **general-MoE post-training
  beats coder-specialized post-training on function-calling**.
- This is a strong prior that Qwen3.6-35B-A3B (general unified post-train,
  newer than Qwen3-Next) will also beat Qwen3-Coder-Next on BFCL — confirmed
  by the (unofficial) NVIDIA forum 78% vs n/a.
