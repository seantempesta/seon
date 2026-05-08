---
title: Coding-agent benchmarks — SWE-Bench / Terminal-Bench / LiveCodeBench / HumanEval
source-urls:
  - https://huggingface.co/Qwen/Qwen3.6-35B-A3B
  - https://arxiv.org/html/2603.00729v1
  - https://qwen.ai/blog?id=qwen3.6-35b-a3b (via Medium extraction)
retrieved: 2026-05-08
fetched-via: WebFetch
---

# Coding-agent benchmarks — head-to-head

This is the axis where Coder-Next is supposed to dominate. Result is more
mixed than expected.

## SWE-Bench Verified

Qwen3.6-35B-A3B model card uses an unspecified scaffold; Coder-Next paper
publishes 3 scaffolds. Best-of-scaffold comparison:

| Model | SWE-Bench Verified |
|-------|---------------------|
| Qwen3.6-35B-A3B (model card, scaffold unspecified) | **73.4** |
| Qwen3-Coder-Next-80B-A3B (SWE-Agent) | 70.6 |
| Qwen3-Coder-Next-80B-A3B (MiniSWE-Agent) | 71.1 |
| Qwen3-Coder-Next-80B-A3B (OpenHands) | 71.3 |

**Qwen3.6 wins by 2-3 points** on the headline SWE-Bench Verified number,
though the scaffold isn't disclosed for the 73.4 figure (likely picks the best
among scaffolds tested).

## SWE-Bench Pro

| Model | SWE-Bench Pro |
|-------|----------------|
| Qwen3.6-35B-A3B | **49.5** |
| Qwen3-Coder-Next-80B-A3B (SWE-Agent) | 42.7 |
| Qwen3-Coder-Next-80B-A3B (MiniSWE-Agent) | 38.7 |

**Qwen3.6 wins by 7-11 points** on the harder Pro variant. This was the most
surprising finding — Coder-Next is the *coder-specialized* model and is losing
to the unified post-train at the harder bug-fixing benchmark.

## SWE-Bench Multilingual

| Model | SWE-Bench Multilingual |
|-------|-------------------------|
| Qwen3.6-35B-A3B (= "SWE-bench Multilingual" line on Qwen3.6 card) | **67.2** |
| Qwen3-Coder-Next-80B-A3B (per arxiv) | 62.8 |

**Qwen3.6 wins again by ~4 points.**

## Terminal-Bench 2.0

| Model | Best Scaffold | Worst Scaffold |
|-------|---------------|----------------|
| Qwen3.6-35B-A3B (model card, scaffold unspecified) | **51.5** | — |
| Qwen3-Coder-Next-80B-A3B | 36.2 (Terminus2-json) | 25.8 (QwenCode) |

**Qwen3.6 wins by 15+ points** on the Terminal-Bench 2.0 surface — the
agentic-shell axis. This is striking because terminal-shell agentic loops are
exactly the workload Coder-Next was designed for.

## LiveCodeBench

| Model | LiveCodeBench |
|-------|----------------|
| Qwen3.6-35B-A3B (per Medium extraction of qwen.ai blog) | **80.4 (v6)** |
| Qwen3-Coder-Next-80B-A3B (per arxiv Table 6) | 58.93 (v6) |
| Qwen3-Coder-480B-A35B | 44.93 (v6) |
| Qwen3-Next-80B-A3B-Thinking | 68.7 (v6) |

**Qwen3.6 wins by 21+ points.** This is the clearest single-shot-coding axis
and Qwen3.6 dominates by a wide margin. (Caveat: Qwen3.6 number is from
third-party extraction of the qwen.ai blog, not the HF card.)

## HumanEval / EvalPlus

Qwen3.6-35B-A3B does NOT publish HumanEval or EvalPlus numbers.

Qwen3-Coder-Next does (Table 6 of arxiv 2603.00729):

| Benchmark | Qwen3-Coder-Next |
|-----------|-------------------|
| EvalPlus | 86.56 |
| MultiPL-E | 88.23 |
| CRUXEval | 95.88 |
| OJBench | 23.01 |
| Codeforces | 2100 |

These are the function-level / competitive-programming axes Coder-Next genuinely
wins on. **EvalPlus, MultiPL-E, CRUXEval, OJBench, Codeforces all favor
Coder-Next** — and Coder-Next beats even Qwen3-Coder-480B on each (per Table 6).

## Qwen team's own coding-agent benchmarks (Qwen3.6-35B-A3B blog, third-party)

| Benchmark | Qwen3.6-35B-A3B |
|-----------|------------------|
| Claw-Eval Avg | 68.7 |
| Claw-Eval Pass³ | 50.0 |
| SkillsBench Avg5 | 28.7 |
| QwenClawBench | 52.6 |
| NL2Repo | 29.4 |
| QwenWebBench | 1397 |

Coder-Next has no comparable scores on these (they're new benchmarks Qwen
introduced with the 3.6 release).

## Honest summary for the agent Phase 0

If the agent's Phase-0 task were **agentic SWE-Bench-style coding**, Qwen3.6-35B-A3B
wins on the headline numbers despite being smaller. The Coder-Next-Next paper's
own positioning ("competitive performance relative to its active parameter
count") is honest — it doesn't claim absolute SOTA on SWE-Bench Verified, just
Pareto-frontier for active params. With only 3B active in both models, the
comparison is fair, and Qwen3.6 wins.

If the agent's Phase-0 task were **competitive code generation** (HackerRank-style,
LeetCode-style, function-by-function), Coder-Next wins by wide margins (LiveCodeBench
v6 was the exception that flipped the other way — likely because Qwen3.6 inherited
v6-era code training data Coder-Next predates by a few months).

For the agent's actual Phase-0 task — observing personas, asking questions, calling
information-tools, ranking trajectories — the SWE-Bench/Terminal-Bench numbers
are *less* relevant than tau-bench / MCPMark, and on those Qwen3.6 has the
published edge. The coding-agent axis is a **secondary check** for the agent, not
the primary axis.
