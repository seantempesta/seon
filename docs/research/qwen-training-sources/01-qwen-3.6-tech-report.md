---
title: Qwen3 Technical Report and Official Blog Post
source-url: https://qwenlm.github.io/blog/qwen3/ ; https://arxiv.org/abs/2505.09388
retrieved: 2026-05-07
fetched-via: WebFetch
---

# Qwen3 Tech Report — Post-Training Pipeline

Note on naming: the community sometimes calls "Qwen 3.5" / "Qwen 3.6" what
Alibaba officially names "Qwen3" (released April–May 2025) plus the "Qwen3-Coder"
and follow-on "Qwen3 Thinking 2507/2509" updates through 2025. The canonical
arxiv tech report is **arXiv:2505.09388** ("Qwen3 Technical Report",
Qwen Team, 2025). The blog (qwenlm.github.io/blog/qwen3) is the public summary.

## Four-stage post-training pipeline (Qwen3, blog excerpt)

Direct quotes from the official Qwen3 blog:

1. **Long Chain-of-Thought (CoT) Cold Start** —
   "fine-tuned the models using diverse long CoT data, covering various tasks
   and domains such as mathematics, coding, logical reasoning, and STEM
   problems."

2. **Reasoning-Based Reinforcement Learning** —
   "scaling up computational resources for RL, utilizing rule-based rewards to
   enhance the model's exploration and exploitation capabilities."

3. **Thinking Mode Fusion** — fine-tuning on
   "a combination of long CoT data and commonly used instruction-tuning data"
   so the same checkpoint supports `enable_thinking=True/False`.

4. **General RL** —
   "applied RL across more than 20 general-domain tasks to further strengthen
   the model's general capabilities."

## What the blog does NOT disclose (verified absences)

- Specific SFT corpus token / example counts.
- DPO / RLAIF specifics (whether they use DPO, GRPO, PPO).
- Per-stage compute or reward-model design.
- Per-stage rollout counts.

## Arxiv 2505.09388 abstract content

Abstract emphasizes:
- Thinking mode (complex multi-step reasoning) and non-thinking mode (rapid
  context-driven responses) in one checkpoint.
- "Thinking budget mechanism, allowing users to allocate computational
  resources adaptively during inference."

Detailed numbers for SFT/RL/sims are in the full PDF — abstract page does not
expose them. (See `06-simulation-claim-source.md` for the explicit
agent-rollout numbers, which appear in the *Qwen3-Coder* blog, not the base
Qwen3 report.)

## Relation to "Qwen 3.5 / 3.6" community usage

When Sean's note says "Qwen 3.5/3.6", the closest official artifacts are:
- **Qwen3** base (Apr 2025) — base + thinking-mode unification.
- **Qwen3-Coder** (Jul 2025) — agentic coding spec, large-scale parallel envs
  for RL.
- **Qwen3-Thinking-2507 / 2509** updates — successive RL refresh checkpoints.

The "hundreds of thousands of simulations" framing maps cleanly onto
Qwen3-Coder's "20,000 independent environments in parallel" RL setup
(see file 06).

## Links

- Blog: https://qwenlm.github.io/blog/qwen3/
- Tech report (arxiv): https://arxiv.org/abs/2505.09388
- Citation: `@misc{qwen3technicalreport, year={2025}, eprint={2505.09388}}`
