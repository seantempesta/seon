---
title: Qwen2.5 (the deeper-documented predecessor) — post-training notes
source-url: https://qwenlm.github.io/blog/qwen2.5/ ; https://arxiv.org/abs/2412.15115
retrieved: 2026-05-07
fetched-via: WebFetch + WebSearch
---

# Qwen2.5 — Post-Training (predecessor of Qwen3 / Qwen3.6)

Note on naming: when Sean's note refers to "Qwen 3.5", the closest official
predecessor with deeper public methodological documentation is **Qwen2.5**
(2024-09 base, 2024-12 tech report). Qwen3 (2025-04) extended this with
thinking-mode unification; Qwen3-Coder (2025-07) added the agentic-RL
infrastructure; Qwen3-Coder-Next + Qwen3.6 (late 2025 / early 2026) refined.

Despite Sean's "treat 3.5 as deeper documentation source" framing, the public
Qwen2.5 blog **does not** disclose specific SFT corpus sizes or DPO/RLHF
hyperparameters either. The Qwen2.5 tech report (arxiv 2412.15115) is more
detailed than the blog but stops short of agent-RL specifics — those came
later with Qwen3-Coder.

## What the Qwen2.5 blog quotes (verbatim)

- "we have refined our post-training methodologies. Our four key updates
  include support for long text generation of up to 8K tokens, significantly
  improved comprehension of structured data, more reliable generation of
  structured outputs, particularly in JSON format, and enhanced performance
  across diverse system prompts."
- Qwen2.5-Math: "incorporates various reasoning methods, including
  Chain-of-Thought (CoT), Program-of-Thought (PoT), and Tool-Integrated
  Reasoning (TIR)."

## What's known from the tech report (community summaries)

- Two-stage post-training: SFT → offline-DPO → online-DPO/GRPO.
- Tool-Integrated Reasoning (TIR) was a Qwen2.5-Math-specific stage where
  the model learned to invoke a Python interpreter mid-solve (precursor to
  Qwen3's native function-calling).
- No agentic-RL claims at Qwen3-Coder scale.

## Phase-0 implication

For the agent's harness, **Qwen2.5 is not where the agent-training detail lives**
— it's where the format conventions were laid down. The deeper agent-training
detail is in:
- Qwen3-Coder blog (file 06)
- Qwen3-Coder-Next tech report (arxiv 2603.00729 — see file 06)
- RollArt paper (arxiv 2512.22560 — see file 08)

## Links

- Qwen2.5 blog: https://qwenlm.github.io/blog/qwen2.5/
- Qwen2.5 tech report: https://arxiv.org/abs/2412.15115
- Qwen2.5-Coder report: https://arxiv.org/abs/2409.12186
- Qwen2.5-Math report: https://arxiv.org/abs/2409.12122
