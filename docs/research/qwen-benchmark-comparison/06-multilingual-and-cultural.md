---
title: Multilingual + cultural-alignment benchmarks for Qwen3.6-35B-A3B vs Qwen3-Coder-Next-80B-A3B
source-urls:
  - https://huggingface.co/Qwen/Qwen3-Next-80B-A3B-Instruct
  - https://huggingface.co/Qwen/Qwen3-Next-80B-A3B-Thinking
  - https://huggingface.co/Qwen/Qwen3.6-35B-A3B
  - https://qwen.ai/blog?id=qwen3.6-35b-a3b (via Medium third-party extraction)
  - https://arxiv.org/html/2603.00729v1
retrieved: 2026-05-08
fetched-via: WebFetch + WebSearch
---

# Multilingual + cultural — published numbers

## Qwen3.6-35B-A3B

The Qwen3.6 official channels claim "201 languages and dialects" support with
"nuanced cultural and regional understanding." Methodologically the only
*published* multilingual benchmark from Qwen3.6 (per blog + Medium extraction)
is:

- **C-Eval (Chinese)**: 90.0
- "MMLU-ProX averaged accuracy on 29 languages" — score not in extracted content

**No published Qwen3.6-35B-A3B numbers found for:** MultiIF, MMLU-ProX (specific),
INCLUDE, PolyMATH, ArabicMMLU, OALL, AraGen, ArabCulture, ACVA. The Qwen3.6
branding emphasizes multilingual breadth without publishing the granular tables
that the precursor Qwen3-Next family does.

## Qwen3-Next-80B-A3B (precursor — closest published proxy)

### Instruct variant (verbatim from HF card)

| Benchmark | Qwen3-Next-80B-A3B-Instruct |
|-----------|------------------------------|
| MultiIF | 75.8 |
| MMLU-ProX | 76.7 |
| INCLUDE | 78.9 |
| PolyMATH | 45.9 |

### Thinking variant (verbatim from HF card)

| Benchmark | Qwen3-Next-80B-A3B-Thinking |
|-----------|------------------------------|
| MultiIF | 77.8 |
| MMLU-ProX | 78.7 |
| INCLUDE | 78.9 |
| PolyMATH | 56.3 |

## Qwen3-Coder-Next-80B-A3B

The arxiv 2603.00729 tech report does **NOT publish natural-language
multilingual benchmarks** at all. The "multilingual" axis is told via:
- **SWE-Bench Multilingual**: 62.8 (coding multilingual — fix bugs across
  9+ programming languages, not natural-language understanding)

That is the only multilingual signal published. **No ArabicMMLU, no MMLU-ProX,
no MultiIF, no INCLUDE, no PolyMATH on the Coder-Next side.**

## ArabicMMLU / OALL specifically

Neither Qwen3.6-35B-A3B nor Qwen3-Coder-Next-80B-A3B publishes ArabicMMLU,
OALL, AraGen, ArabCulture, or ACVA scores. This is a real gap given the
a sibling project project's KSA Phase-1 framing.

The Qwen team has historically not led on Arabic — the leading open-weights
Arabic model in 2026 is **ALLaM-34B** (HUMAIN/SDAIA, KSA-trained), not any
Qwen variant. See `repos/team-notes/sean/research/virtue-priming/allam-as-
guardian-2026-04-25.md` for that lineage.

## Honest read for the agent Phase 0

If the the agent persona-cell needs **Arabic-specific cultural fluency**, neither
Qwen3.6-35B-A3B nor Qwen3-Coder-Next-80B-A3B has any published advantage —
both inherit Qwen-family multilingual posture which is good but generic. The
KSA cultural fluency that a sibling project needs comes from the **virtue preamble +
cultural-LoRA layer**, NOT from the base model. Phase-0 base-model choice
does not turn on Arabic benchmarks because there's no published difference.

If the the agent persona-cell needs **general multilingual fluency** (French,
Japanese, etc.), Qwen3-Next's MultiIF 77.8 / INCLUDE 78.9 numbers are the best
proxy we have for the architecture both candidates derive from. Qwen3.6
inherits and likely improves these (its post-training is one generation newer)
but doesn't publish the granular table. Coder-Next's natural-language multi-
lingual posture is *unknown* — the paper genuinely never measures it.

**Takeaway:** the multilingual axis is a **wash on published evidence** between
the two candidates. Both inherit Qwen3-Next-class multilingual posture; neither
publishes Arabic; cultural fluency is a separate-layer problem regardless of
which base wins.
