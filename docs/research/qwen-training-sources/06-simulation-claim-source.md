---
title: The "hundreds of thousands of simulations" claim — primary source
source-url: https://qwenlm.github.io/blog/qwen3-coder/
retrieved: 2026-05-07
fetched-via: WebFetch
---

# Source for the "hundreds of thousands of simulations" claim

## What was found (exact quote)

From the official Qwen3-Coder blog post (Alibaba Qwen Team, July 2025):

> **"we built a scalable system capable of running 20,000 independent
> environments in parallel, leveraging Alibaba Cloud's infrastructure."**

This is the primary public claim about agentic-RL scale from the Qwen team.
The blog frames it as **"Long-Horizon RL (Agent RL)"** and describes
multi-turn interactions with planning, tool use, feedback, and decision-making
on tasks like SWE-Bench.

## Mapping to the "hundreds of thousands of simulations" framing

Sean's note recalled the team claiming "hundreds of thousands of simulations."
There are TWO numbers in the public record that match this shape:

### A. Parallel-environment concurrency (Qwen3-Coder, July 2025)

**"20,000 independent environments in parallel"** — Alibaba Cloud
infrastructure for SWE-Bench-style RL.

### B. Verifiable-task corpus (Qwen3-Coder-Next tech report, late-2025/2026)

**"~800K verifiable software engineering tasks"** synthesized across nine
programming languages, sourced from real GitHub PRs + SWE-Smith + SWE-Flow +
SWE-Rebench + Multi-SWE-RL.
(Source: arxiv 2603.00729 / qwen.ai/blog Qwen3-Coder-Next.)

**This 800K number is the most likely match for "hundreds of thousands of
simulations".** Each "verifiable task" pairs a problem with an executable
environment + reward (unit tests). The 20K-parallel-environments figure is
the *infrastructure throughput* for working through this corpus; the 800K
*tasks* are the substrate.

### C. Production deployment (RollArt paper, late-2025)

For the **Qoder** product, Alibaba ran a hundreds-of-billions-parameter MoE
model on **>3,000 GPUs for one week** continuous agentic-RL training, using
the RollArt disaggregated infrastructure (arxiv 2512.22560). Speedup
1.35–2.05× over monolithic baselines.

**Honest framing**: the "20,000 environments in parallel" + "800K verifiable
SWE tasks" pair is the cleanly attributable Alibaba claim. A literal "hundreds
of thousands of simulations" quote was NOT found verbatim, but 800K tasks (×
many epochs of RL) reaches that scale by any reasonable arithmetic.

## Surrounding context (from the same blog)

- **Code RL**: "scaled up Code RL training on a broader set of real-world
  coding tasks" by "automatically scaling test cases of diversity coding
  tasks." Execution-driven; rule-based rewards on whether tests pass.
- **Result claim**: Qwen3-Coder achieves "state-of-the-art performance among
  open-source models on SWE-Bench Verified without test-time scaling."
- **Pre-training**: 7.5T tokens, 70% code ratio. Native 256K context, 1M with
  YaRN.

## Reward / success criterion (inferred + stated)

The blog does NOT spell out the reward model in detail. What is stated:
- Code RL uses **execution success on auto-scaled test cases** (rule-based
  reward — pass/fail).
- Long-horizon Agent RL involves "receiving feedback" inside the multi-turn
  loop. Whether that feedback is rule-based, model-judged, or trajectory-PRM
  is not disclosed in the blog.

## Environment shape (what the 20K envs likely are)

The blog describes SWE-Bench-like tasks — i.e. each environment is plausibly
a containerized repo + test runner. Alibaba does not open-source the
environment harness. Closest open analogues:
- **SWE-Gym** (open source, Princeton/UCSD) — repo+tests harness for SWE-Bench
  style training.
- **OpenHands runtime** (formerly OpenDevin) — Docker per session, browser +
  shell + editor.
- **R2E-Gym / SWE-rebench** — additional repo-execution gyms.

(See `08-oss-replication-kits.md`.)

## Consequence for Phase 0 harness

If Phase 0 wants the model to land on its training prior, the implied recipe
is:
1. **Tool-calling format**: Qwen-Agent / Hermes-style function-call template
   (see file 03 + 05).
2. **Environment shape**: containerized, multi-turn, rule-based reward where
   possible — this is how Qwen3-Coder was trained at scale.
3. **Concurrency story**: Alibaba ran 20K parallel; Phase 0 won't, but the
   shape (one container per rollout, async return) is the design pattern that
   matches.

## Unanswered (would need the full tech report or insider info)

- Total rollout count across the run.
- Reward model architecture for non-code agentic tasks.
- Trajectory length distribution.
- Curriculum (did they ramp difficulty? Mix of synthetic + real repos?).
- Cost / GPU-hours of the 20K-env training.

## Links

- Primary: https://qwenlm.github.io/blog/qwen3-coder/
- Qwen3 base report (no agent-RL numbers there): https://arxiv.org/abs/2505.09388
