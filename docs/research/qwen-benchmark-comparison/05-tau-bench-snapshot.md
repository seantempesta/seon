---
title: tau-bench / TAU2 / TAU3 leaderboard snapshot
source-urls:
  - https://llm-stats.com/benchmarks/tau-bench-retail
  - https://llm-stats.com/benchmarks/tau-bench-airline
  - https://huggingface.co/Qwen/Qwen3-Next-80B-A3B-Thinking
  - https://medium.com/data-science-in-your-pocket/qwen3-6-35b-a3b-the-tiny-active-open-model-that-thinks-codes-and-agents-like-a-much-bigger-one-486d535e372e
retrieved: 2026-05-08
fetched-via: WebFetch
---

# tau-bench (multi-turn customer-agent simulation)

## TAU-bench Retail — llm-stats leaderboard (verbatim, 2026-05-08)

| Rank | Model | Score |
|------|-------|-------|
| 1 | Claude Sonnet 4.5 | 0.862 |
| 2 | Claude Opus 4.1 | 0.824 |
| 3 | Claude Opus 4 | 0.814 |
| 4 | Claude 3.7 Sonnet | 0.812 |
| 5 | Claude Sonnet 4 | 0.805 |
| 6 | GLM-4.5 | 0.797 |
| 7 | GLM-4.5-Air | 0.779 |
| 8 | **Qwen3-Coder 480B A35B Instruct** | **0.775** |
| 9 | o4-mini | 0.718 |
| 10 | o1 | 0.708 |
| 11 | **Qwen3-Next-80B-A3B-Thinking** | **0.696** |
| 12 | Claude 3.5 Sonnet | 0.692 |
| 13 | GPT-4.5 | 0.684 |
| 14 | GPT-4.1 | 0.680 |
| 15 | MiniMax M1 40K | 0.678 |
| 16 | GPT OSS 120B | 0.678 |
| 17 | Qwen3-235B-A22B-Thinking-2507 | 0.678 |
| 19 | **Qwen3-Next-80B-A3B-Instruct** | **0.609** |
| 20 | GPT-4o | 0.603 |

Neither Qwen3.6-35B-A3B nor Qwen3-Coder-Next-80B-A3B is on this leaderboard.

## TAU-bench Airline — llm-stats leaderboard (verbatim, 2026-05-08)

| Rank | Model | Score |
|------|-------|-------|
| 1 | Claude Sonnet 4.5 | 0.700 |
| 2 | MiniMax M1 80K | 0.620 |
| 3 | GLM-4.5-Air | 0.608 |
| 4 | GLM-4.5 | 0.604 |
| 5 | **Qwen3-Coder 480B A35B Instruct** | **0.600** |
| 5 | Claude Sonnet 4 | 0.600 |
| 5 | MiniMax M1 40K | 0.600 |
| 8 | Claude Opus 4 | 0.596 |
| 9 | Claude 3.7 Sonnet | 0.584 |
| 10 | Claude Opus 4.1 | 0.560 |
| 11 | GPT-4.5 | 0.500 |
| 11 | o1 | 0.500 |
| 13 | GPT-4.1 | 0.494 |
| 14 | o4-mini | 0.492 |
| 15 | **Qwen3-Next-80B-A3B-Thinking** | **0.490** |
| 16 | Qwen3-235B-A22B-Thinking-2507 | 0.460 |
| 18 | **Qwen3-Next-80B-A3B-Instruct** | **0.440** |

## What's published for the two candidate models

### Qwen3.6-35B-A3B (April 2026 release)

From the qwen.ai/blog?id=qwen3.6-35b-a3b (cited via Medium third-party
extraction, 2026-04, methodology: "TAU3-Bench evaluation uses the official
user model (gpt-5.2, low reasoning effort) + default BM25 retrieval"):

| Benchmark | Qwen3.6-35B-A3B |
|-----------|-----------------|
| TAU3-Bench | **67.2** |
| VITA-Bench | 35.6 |
| DeepPlanning | 25.9 |
| Tool Decathlon | 26.9 |
| MCPMark | **37.0** |
| MCP-Atlas | **62.8** |
| WideSearch | 60.1 |

This is the **richest agentic-benchmark surface** any 35B-A3B-class model has
published. Note especially:
- **TAU3-Bench 67.2** is the v3 of tau-bench (broader coverage, fixed errata
  per taubench.com/blog/tau3-task-fixes); not directly comparable to the v1/v2
  numbers in the llm-stats leaderboards above.
- **MCPMark 37.0 / MCP-Atlas 62.8** — these are the MCP-style tool-use benchmarks
  the agent's agent will most resemble. Qwen3.6 is the only Qwen model with published
  MCPMark.

### Qwen3-Coder-Next-80B-A3B (Feb 2026 release)

The arxiv 2603.00729 paper does **not publish tau-bench** numbers at all.
The Coder-Next paper's agentic story is told via SWE-Bench scaffolds + Terminal-
Bench, not customer-agent simulation. tau-bench is about customer-service
multi-turn conversation; the Coder-Next eval suite is multi-turn coding.

The closest proxy from the Qwen3-Coder family:
- **Qwen3-Coder 480B A35B Instruct** — TAU-bench Retail 0.775, Airline 0.600.

The closest proxy from the **80B-A3B architecture**:
- **Qwen3-Next-80B-A3B-Thinking** — TAU-bench Retail 0.696, Airline 0.490.

If we interpolate (80B-A3B base + Coder specialization), Qwen3-Coder-Next on
tau-bench is plausibly **between 0.696 and 0.775 on Retail**, with no published
ground truth. **It almost certainly does NOT beat Qwen3.6's 67.2 on TAU3-Bench**
because Qwen3-Coder-Next is optimized for code-tool loops, not customer-agent
conversation — which is precisely the failure mode tau-bench probes.

## Implication for the agent Phase 0

the agent's Phase-0 information-agent task is **closer to tau-bench / MCPMark than
to SWE-Bench**. The user is the persona; the agent observes, asks questions,
calls tools, returns answers. That's customer-service multi-turn shape, not
PR-fixing shape.

On this axis, Qwen3.6-35B-A3B has **published numbers in the right benchmark
family** (TAU3-Bench, MCPMark, MCP-Atlas). Qwen3-Coder-Next has **no published
numbers in this benchmark family at all**, and its closest precursor
(Qwen3-Next, general MoE) scores roughly the same as Qwen3.6 on TAU2-Retail
(67.8 vs 67.2 in different versions — close enough to call a tie).

Translation: on the agent's primary axis, Qwen3.6 has the receipts. Qwen3-Coder-Next
has receipts for a different problem (code-agent loops).
