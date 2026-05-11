# Harness impact on benchmark scores — evidence review

**Date:** 2026-05-11
**Purpose:** Stress-test Sean's claim for the agent's pitch — *"same model + better harness = much better results"* — against the public 2024-2026 literature.

---

## TL;DR

The "harness matters a lot" claim is **well-supported** but **not unbounded**: scaffold changes routinely move scores by 10-30 percentage points on the same model across SWE-bench, GAIA, and MLE-bench, and Anthropic, METR, and HuggingFace have all published numbers to that effect. The strongest single citation is **METR's own elicitation methodology** — they explicitly pick a scaffold per model (ReAct / Triframe / Claude Code / Codex / Vivaria / Inspect) and have shown statistically significant score differences between scaffolds on the *same* model (GPT-4o, o3) on the same task suite. The cleanest demo is **Anthropic's minimal bash+edit scaffold gaining Claude 3.5 Sonnet ~10 points on SWE-bench Verified (39% → 49%) over the previous SOTA scaffold**, and **HuggingFace's CodeAgent beating the prior multi-agent SOTA on GAIA by ~4 points (40% → 44.2%) using the same class of GPT-4o**. The headline pushback: **AgentArch (Sept 2025) found model choice explains ~28% of success-rate variance vs. ~0.6% for agent architecture across enterprise tasks** — i.e. when you average across many configurations, model still dominates, and harness gains can be brittle to task distribution. Net: defensible deck claim, but Sean should frame it as *"harness can close or open a ~10-30 point gap on the same model on the same benchmark"* — not *"harness > model."*

---

## Specific examples with numbers

### 1. Anthropic — minimal scaffold on Claude 3.5 Sonnet (SWE-bench Verified)

- Claude 3.5 Sonnet with Anthropic's minimal bash+edit harness: **49%** on SWE-bench Verified.
- Previous SOTA (different scaffold, comparable model): **45%**.
- Anthropic has separately claimed their **custom harness adds ~10 percentage points** over a generic scaffold on the same model.
- Their design philosophy: "give as much control as possible to the language model itself, and keep the scaffolding minimal" — a prompt plus two general-purpose tools.
- Source: [Raising the bar on SWE-bench Verified with Claude 3.5 Sonnet](https://www.anthropic.com/research/swe-bench-sonnet); [engineering writeup](https://www.anthropic.com/engineering/swe-bench-sonnet).

### 2. HuggingFace smolagents CodeAgent vs ToolCallingAgent (GAIA)

- Same GPT-4o, no fine-tuning:
  - CodeAgent (code-as-action): **44.2% validation, 33.3% test** on GAIA — #1 on validation, beating prior multi-agent SOTA (~40%).
  - ToolCallingAgent (JSON tool calls): consistently used **30% more steps** and reached lower performance on hard benchmarks.
- Baseline GPT-4-Turbo with no agent harness: **<7%** on GAIA.
- Source: [Our Transformers Code Agent beats the GAIA benchmark](https://huggingface.co/blog/beating-gaia); underlying claim from [Wang et al. 2024 "Executable Code Actions Elicit Better LLM Agents"](https://huggingface.co/papers/2402.01030).

### 3. Agentless (SWE-bench Lite / Verified)

- Agentless three-phase scaffold (localize → repair → validate, no agent loop): **32.00%** on SWE-bench Lite at **$0.70/instance** — beat all prior open-source agent scaffolds at the time.
- On SWE-bench Verified, Agentless **doubled the previous best open-source scaffold's score (~16% → 32%)** using the same underlying GPT-4o-class model.
- Source: [Agentless paper, arXiv:2407.01489](https://arxiv.org/abs/2407.01489); [GitHub](https://github.com/OpenAutoCoder/Agentless).

### 4. OpenAI MLE-bench — AIDE vs ResearchAgent vs CodeActAgent (same GPT-4o)

- OpenAI ran three open-source scaffolds on **identical GPT-4o**:
  - AIDE: highest medal rate.
  - ResearchAgent: lower.
  - CodeActAgent: lower.
- With o1-preview + AIDE: **16.9% bronze-medal rate**; swapping in a weaker scaffold drops this substantially. AIDE explicitly cited by METR as evidence that *"properly eliciting models can make a very large difference."*
- Source: [MLE-bench paper, arXiv:2410.07095](https://arxiv.org/abs/2410.07095); [OpenAI blog](https://openai.com/index/mle-bench/).

### 5. METR — Vivaria vs Inspect on same models

- METR ran the same task suite under two evaluation harnesses (Vivaria, Inspect) across 5 models.
- **GPT-4o and o3 scored statistically significantly higher under Vivaria than Inspect** (paired t-test).
- Across 82 task-level comparisons: 51 tasks favored Vivaria, 31 favored Inspect — driven mostly by GPT-4o/o3 scaffold sensitivity.
- METR's published time-horizon methodology explicitly **chooses a different scaffold per model** (ReAct, Triframe, Claude Code, Codex) because uniform scaffolding under-elicits.
- Source: [Time Horizon 1.1, METR Jan 2026](https://metr.org/blog/2026-1-29-time-horizon-1-1/); [Vivaria vs Inspect comparison](https://vivagent.metr.org/comparison-with-inspect/); [Measuring Time Horizon using Claude Code and Codex](https://metr.org/notes/2026-02-13-measuring-time-horizon-using-claude-code-and-codex/).

### 6. SWE-bench Pro scaffold ladder (model unspecified)

- One blog presents a four-rung scaffold comparison: minimal wrapper **38%** → context-aware **45%** → +retry logic **51%** → full harness **60%**. **22-point swing on the same model**.
- Caveat: blog doesn't name the model; weaker citation but the ladder shape is consistent with the primary literature.
- Source: [BSWEN — What Does SWE-bench Pro Reveal About Agent Scaffold Performance?](https://docs.bswen.com/blog/2026-04-20-swe-bench-pro-agent-scaffold/).

---

## What kinds of harness improvements move the needle most

Ranked by published evidence weight:

1. **Action representation (code vs JSON tool-calls)** — HuggingFace CodeAgent shows ~30% fewer steps and higher GAIA scores from emitting executable Python rather than JSON tool dicts. The underlying [CodeAct paper (Wang et al. 2024)](https://huggingface.co/papers/2402.01030) generalizes this.
2. **Minimal, model-controlled tool surface** — Anthropic's "two tools, let the model drive" beat elaborate agentic scaffolds. Repeated in METR's recent shift to Claude Code / Codex as default scaffolds.
3. **Structured non-loop pipelines for narrow domains** — Agentless's localize/repair/validate beat agent loops on SWE-bench. The lesson: when the task has structure, hard-code it instead of relying on the model's planner.
4. **Retry / self-critique loops** — SWE-bench Pro ladder attributes ~6 points to retry logic alone.
5. **Memory/context handling** — Meta-Harness reports up to **6× performance gap** on the same LLM by changing what the harness stores, retrieves, and shows the model ([arXiv:2603.28052](https://arxiv.org/html/2603.28052v1)). Treat as upper bound — likely cherry-picked tasks.
6. **Sub-agent decomposition** — mixed evidence. HuggingFace's single CodeAgent beat multi-agent Autogen on GAIA, suggesting decomposition is *not* automatically a win.

---

## Honest pushback

- **AgentArch (Sept 2025, [arXiv:2509.10769](https://arxiv.org/abs/2509.10769))** is the clearest counter: across a broad enterprise benchmark, **model choice accounted for 28.2% of success-rate variance; agent architecture accounted for 0.6%**. Model quality dominated architecture by ~85×. Even strong models (Sonnet 4, GPT-4.1) showed high coefficients of variation (27-32%) across architectures — meaning the right scaffold matters per-model, but no single architecture wins everywhere.
- **METR's own work** quietly cuts against the strong form: same task suite under Vivaria vs Inspect showed "very similar scores" overall — large effects were concentrated in specific models, not uniform.
- **Real-world transfer is worse than benchmark scores suggest.** METR's March 2026 study found **roughly half of SWE-bench-Verified-passing PRs would not be merged into main by repo maintainers** — so harness-driven benchmark gains may overstate user-visible quality gains. ([METR notes 2026-03-10](https://metr.org/notes/2026-03-10-many-swe-bench-passing-prs-would-not-be-merged-into-main/))
- **Scaling laws still win for raw capability ceiling.** Anthropic's Claude Opus 4.7 hitting 87.6% on SWE-bench Verified and Claude Mythos hitting 93.9% are model-driven gains; no harness lifts a weaker base model into that range. Harness sets the floor-to-ceiling slope; the model sets the ceiling.
- **Harness gains are fragile to task distribution.** A scaffold tuned on SWE-bench-Lite issues degrades on Verified; one tuned for GAIA Level-1 doesn't transfer cleanly to Level-3. AgentArch's CV numbers (~30%) quantify this.

---

## the agent positioning

the agent's pitch — *"a substrate tailored to the person, not the task"* — **rides on top of** the harness-matters literature rather than contradicting it. Every paper above is harness-tailored-to-the-task (SWE-bench → Agentless; GAIA → CodeAgent; MLE-bench → AIDE). the agent's claim is the orthogonal one: *the same person doing different tasks shares more substrate than the same task done by different people.* That's an empirical question the literature hasn't tested — there's no published "personalized harness beats task-tuned harness" study, because nobody runs benchmarks per-user. So the agent can honestly say: the literature establishes that harness choice routinely produces 10-30 point swings on the same model, AgentArch shows the right harness is model-specific, and the agent extends that personalization axis from model-specific to user-specific. The deck risk is overclaiming — Sean should frame the agent as *the next axis of harness adaptation* (after task-specific and model-specific), not as a rejection of model scaling.

---

## Citations (primary preferred)

**Primary papers / official blogs:**
- [Anthropic — Raising the bar on SWE-bench Verified with Claude 3.5 Sonnet](https://www.anthropic.com/research/swe-bench-sonnet)
- [Agentless: Demystifying LLM-based Software Engineering Agents (arXiv:2407.01489)](https://arxiv.org/abs/2407.01489)
- [MLE-bench: Evaluating Machine Learning Agents on ML Engineering (arXiv:2410.07095)](https://arxiv.org/abs/2410.07095) · [OpenAI blog](https://openai.com/index/mle-bench/)
- [HuggingFace — Our Transformers Code Agent beats the GAIA benchmark](https://huggingface.co/blog/beating-gaia)
- [Wang et al. — Executable Code Actions Elicit Better LLM Agents (CodeAct)](https://huggingface.co/papers/2402.01030)
- [METR — Time Horizon 1.1 (Jan 2026)](https://metr.org/blog/2026-1-29-time-horizon-1-1/)
- [METR — Measuring Time Horizon using Claude Code and Codex (Feb 2026)](https://metr.org/notes/2026-02-13-measuring-time-horizon-using-claude-code-and-codex/)
- [METR — Vivaria vs Inspect comparison](https://vivagent.metr.org/comparison-with-inspect/)
- [METR — Many SWE-bench-Passing PRs Would Not Be Merged into Main (Mar 2026)](https://metr.org/notes/2026-03-10-many-swe-bench-passing-prs-would-not-be-merged-into-main/)
- [AgentArch: A Comprehensive Benchmark to Evaluate Agent Architectures in Enterprise (arXiv:2509.10769)](https://arxiv.org/abs/2509.10769)
- [Meta-Harness: End-to-End Optimization of Model Harnesses (arXiv:2603.28052)](https://arxiv.org/html/2603.28052v1) — treat as upper-bound claim

**Leaderboards / supporting:**
- [SWE-bench official leaderboards](https://www.swebench.com/)
- [GAIA leaderboard (HuggingFace)](https://huggingface.co/spaces/gaia-benchmark/leaderboard)
- [Anthropic — Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- [BSWEN — SWE-bench Pro scaffold ladder](https://docs.bswen.com/blog/2026-04-20-swe-bench-pro-agent-scaffold/) (weaker source; useful framing only)
