# AgentVerse, Cognee/Zep/Mem0, AutoGen/LangGraph, OpenHands, NeMo Agent Toolkit, ScienceWorld/ALFWorld

A short consolidated review of the secondary candidates — none of these emerged as Phase-0 contenders, but they're worth the explicit pass-rationale so we don't revisit them under time pressure later.

## AgentVerse (OpenBMB)

- **Repo:** [OpenBMB/AgentVerse](https://github.com/OpenBMB/AgentVerse), Apache-2.0
- **Last commit:** 2024-09-09 (stale)
- **What it is:** Multi-agent simulation framework with two cases — task-solving (recruit experts, solve task) and simulation (Pokemon, prisoner's dilemma, etc.). The README cites Voyager-style behaviors emerging from multi-agent civilizations.
- **the agent fit:** Marginal. The "recruit experts" pattern is interesting but coding-task-focused. Active development has moved on; staleness is disqualifying for Phase 0.
- **Verdict: pass.**

## Cognee (topoteretes/cognee)

- **License:** Apache-2.0, active.
- **What it is:** "Memory control plane" — a knowledge-graph-extraction-from-unstructured-text pipeline. Takes raw docs, builds a graph + vector index, exposes retrieval.
- **the agent fit:** Re-evaluated as a *harness*: it's not a harness, it's a memory product. As a *ground-truth-graph generator* for the agent scoring (one of Gemini's suggestions), maybe — Cognee could produce the gold-standard graph of a persona corpus, and the agent's stored facts get measured against it. Worth a probe in V1; not Phase 0 critical-path.
- **Verdict: borrow concepts (ground-truth-graph extraction); pass as harness.** Already covered in `2026-05-07-memory-architecture.md`.

## Zep (getzep/zep)

- **License:** Apache-2.0, active.
- **What it is:** Temporal knowledge graphs for agent memory. Bi-temporal facts (event time + ingest time). Closer to the agent's Datomic-EAVT vision than Cognee.
- **the agent fit:** Same memory-product critique as Letta — Zep gives you a *fixed* memory API; the agent explicitly wants the agent to learn its own idiom. Useful as a ground-truth reference and a comparison-baseline. Not a harness.
- **Verdict: pass as harness.** Already covered in `2026-05-07-memory-architecture.md`.

## Mem0 (mem0ai/mem0)

- **License:** Apache-2.0, active.
- Same critique as above — production memory product, not a training harness.
- **Verdict: pass.** Already covered in `2026-05-07-memory-architecture.md`.

## AutoGen / LangGraph / MetaGPT / CrewAI

- **What they are:** Multi-agent orchestration libraries. Useful for *building* multi-agent systems; not training/eval frameworks.
- **the agent fit:** None for harness. AutoGen specifically has gone into "community maintenance" per Microsoft's reorg toward Magentic-One.
- **Verdict: pass.** Wrong category — these are the *target* of training, not the harness. AgentVerse + verifiers' multi-role patterns are sufficient for the agent's reactor/agent/grader composition.

## OpenHands (All-Hands-AI/OpenHands) + Magentic-One

- **License:** MIT, active.
- **What it is:** Production runtime for AI software engineers — coder + browser + terminal sandboxes. Integrated with SkyRL training.
- **the agent fit:** Coding-agent-shaped. The browser/terminal bias is wrong domain. OpenHands' SWE-Bench training kit is real but useless for personal-info agents.
- **Verdict: pass.** This is the canonical "wrong shape for the agent" project.

## NVIDIA NeMo Agent Toolkit

- **License:** Apache-2.0, active.
- **What it is:** Enterprise-grade orchestration + instrumentation toolkit for multi-agent workflows. Framework-agnostic connectivity (talks to LangChain, LlamaIndex, AutoGen).
- **the agent fit:** It's an *orchestration* toolkit, not a training harness. Useful at V2+ when the agent runs as a production agent inside a customer's stack and needs OpenTelemetry/observability. Not Phase 0.
- **Verdict: pass for now; revisit at productionization.**

## ScienceWorld / ALFWorld

- **Licenses:** Apache-2.0 / MIT
- **What they are:** Text-based simulators — ScienceWorld for elementary science procedures; ALFWorld for household tasks bridging natural-language → embodied execution.
- **the agent fit:** Domain-specific. The ALFWorld curriculum-shape (text-as-action over a structured world) is conceptually adjacent to the agent's primitives-over-fact-graph but the world model is wrong (kitchens / labs, not personal-info).
- **Verdict: pass.** Reference for "text-action language" style if the agent needs to invent a textual primitive grammar; otherwise irrelevant.

## ToolBench (OpenBMB) / AgentTuning (THUDM)

- **What they are:** SFT datasets + tooling for tool-use fine-tuning. ToolBench has 16k real-world APIs; AgentTuning has AgentInstruct.
- **the agent fit:** Datasets, not harnesses. The 16k-API ToolBench dataset is a useful *pretraining* corpus if the agent's base model needed broader tool-use grounding — but Qwen 3.6 already scores well on BFCL-V4 (67.3) so that pretraining already happened upstream.
- **Verdict: pass.** Wrong category for the agent's training phase.

## Tongyi DeepResearch (Alibaba-NLP)

- **License:** Apache-2.0, active.
- **What it is:** Autonomous agentic model + training recipe for long-context information-seeking and multi-step research synthesis. **This is the closest published "info-agent training recipe" to the agent's domain.**
- **the agent fit:** Worth a deeper read at V1 for prior art on info-agent training specifically — what reward shape they used, what synthetic-task generation, etc. Not a harness we adopt; a *recipe* worth comparing against. Out of scope for Phase 0 narrowly.
- **Verdict: borrow recipes; revisit at V1 training-design.**

---

**Net of this group:** none of these change the Phase-0 decision. Verifiers stays the recommendation; tau-bench/τ³ stays the strongest fork-and-adapt fallback; Tongyi DeepResearch is the most interesting **prior-art recipe** worth a deeper read once Phase 0 produces signal worth investing further training compute against.
