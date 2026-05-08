---
title: Qwen3-Coder-480B-A35B-Instruct — flagship model card (context only)
source-url: https://huggingface.co/Qwen/Qwen3-Coder-480B-A35B-Instruct/raw/main/README.md
retrieved: 2026-05-08
fetched-via: WebFetch
---

# Qwen3-Coder-480B-A35B-Instruct — for context

NOT a Phase-0 candidate (480B total / 35B active is multi-GPU; the agent/a sibling project runs
RTX 6000 96GB single-GPU).

## Specs (verbatim from card)

- Type: Causal LM
- Parameters: 480B total / 35B activated
- Layers: 62
- Attention heads (GQA): 96 Q / 8 KV
- Experts: 160 total / 8 activated
- Native context: 262,144 tokens (1M with YaRN)
- Non-thinking mode only — does **not** generate `<think></think>` blocks

## Benchmarks on the HF model card

The HF card sparsely publishes:
- SWE-Bench Pro: **38.7**
- Terminal-Bench 2.0: **23.9** (asterisk)
- EvasionBench: **78.16**

That's it. The card defers to qwen3-coder blog + GitHub for fuller numbers.

## Benchmarks per arxiv 2603.00729 Table 6 (function-level + competitive coding)

| Benchmark | Qwen3-Coder-480B-A35B |
|-----------|------------------------|
| EvalPlus | 86.66 |
| MultiPL-E | 88.00 |
| CRUXEval | 92.13 |
| LiveCodeBench (v6) | 44.93 |
| OJBench | 14.98 |
| Codeforces | 1800 |

Qwen3-Coder-Next-80B-A3B beats this on every row except a near-tie on EvalPlus.

## Why this matters for Phase 0

The flagship is **strictly worse** on competitive coding, and roughly comparable
on agentic SWE-Bench, while being **way more expensive to deploy**. The Coder-Next
80B-A3B is the design point Alibaba is pushing as the new Pareto frontier.

For the agent, this is the answer to "if we wanted the strongest published Coder
in the family, what would we give up?" — answer: ~3× memory footprint and worse
LiveCodeBench. So 480B is dominated; the choice is genuinely 35B-A3B vs 80B-A3B.

## Tool-call / agentic surface

Same Hermes/`<tool_call>` envelope as Qwen3-Coder-Next, served via
`vllm --tool-call-parser qwen3_coder`.
