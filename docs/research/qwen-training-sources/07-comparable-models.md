---
title: Comparable models' agentic-training methodology — DeepSeek, Mistral, Llama, Anthropic
source-url: various — see inline links
retrieved: 2026-05-07
fetched-via: WebFetch + WebSearch + prior knowledge with verification
---

# Comparable agentic-training methodologies

Brief — just enough to know whether Qwen's approach is conventional.

## DeepSeek (R1 / V3 / V3.5)

- **R1** (2025-01) introduced large-scale rule-based RL with **GRPO** (Group
  Relative Policy Optimization, DeepSeek's PPO simplification — no value
  network). Rule-based rewards (math correctness, code unit-test pass).
- **V3** (2024-12) and **V3.5** (mid-2025): scale up the same recipe; V3.5
  adds explicit agentic post-training stages with tool-use RL on coding +
  search environments.
- Reward signal: rule-based primary, model-judge secondary. **Same
  philosophy as Qwen3-Coder.**
- DeepSeek does not publish parallel-environment counts. Their R1 paper
  describes a "reasoning-oriented RL" stage running for "thousands of steps"
  on math/code without enumerating env count.

## Mistral

- **Codestral-Agent** (2025): post-trained for tool use, exposes
  function-calling with their own prompt template (similar to OpenAI tool
  schema, slightly different system-prompt tags).
- **Magistral** (2025): reasoning model in DeepSeek-R1 lineage; minimal
  agentic-RL detail published. They emphasize SFT-on-traces over large-scale
  parallel-env RL.

## Llama 3.x / Llama-Agent

- Meta's Llama 3.1 / 3.3 publishes function-calling training but at lower
  scale than Qwen3-Coder. Their "tool reasoning" data is described as
  thousands-to-low-millions of multi-turn examples, mostly synthetic.
- **Llama-Agentic-System** is a reference harness, not a training pipeline.

## Anthropic (Claude Sonnet 4.6 / Opus 4.7)

- Anthropic does **not publish** post-training methodology in detail.
  Public claims emphasize Constitutional AI, RLHF, and "agent training" but
  no env-count or rollout-count figures.
- The Claude family's strong tool-use is widely-attributed to extensive
  multi-turn RLHF on tool-use trajectories, plus the SWE-Bench-style
  evaluation harness as iteration signal.

## Bottom line

Qwen3-Coder's recipe is **conventional in shape** (rule-based rewards on
verifiable tasks, GRPO/PPO, multi-turn rollouts) but **unusually
quantitatively transparent**: 20K parallel envs + 800K verifiable tasks +
3000-GPU training run are numbers no other lab publishes. DeepSeek is the
closest methodological cousin; Anthropic and Mistral are opaque-er.

For the agent Phase 0, the implication: matching Qwen's training prior is also
roughly matching DeepSeek's training prior. The two are far more
methodologically similar than either is to Claude or to Mistral.

## Links

- DeepSeek-R1: https://arxiv.org/abs/2501.12948
- DeepSeek-V3: https://arxiv.org/abs/2412.19437
- Mistral Codestral: https://mistral.ai/news/codestral/
- Llama 3.x function-calling: https://github.com/meta-llama/llama-stack
- Anthropic Claude post-training (no detailed source): https://www.anthropic.com/research
