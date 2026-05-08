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
The cleanly sourced number is **20,000 parallel environments**. A single
training run across many policy updates ⇒ rollouts/simulations easily reach
hundreds of thousands or millions; the blog does not state a total rollout
count, only the parallel concurrency.

**Honest framing**: the "20,000 environments in parallel" is the only
quantitative agent-RL infrastructure number Alibaba has published. Any larger
"hundreds of thousands" total is downstream arithmetic, not a directly quoted
figure. **Primary-source for a literal "hundreds of thousands of simulations"
quote: NOT FOUND.** The closest cleanly attributable Alibaba claim is the
20K-parallel-envs sentence above.

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
