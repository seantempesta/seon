# OSS agent training/eval harness evaluation for the agent Phase 0

**2026-05-07.** Survey + per-project evaluation of open-source agent harnesses for the agent's Phase 0 observation harness (multi-instance Qwen + minimum primitives + hand-written scenarios + observation, no training compute yet, per [`2026-05-07-brainstorm-decisions.md`](../2026-05-07-brainstorm-decisions.md) "Phase 0" section).

Per-project deep-dives in [`oss-harness-evaluation/`](oss-harness-evaluation/). This doc is the synthesis.

## Top-level recommendation

**Phase 0 should plug into [Verifiers](https://github.com/PrimeIntellect-ai/verifiers) (Prime Intellect / willccbb).** Use as-is, write the agent-specific environments and rubrics on top.

Reasoning in one paragraph: Verifiers is the only OSS harness in 2025–2026 that ships *exactly* the abstractions the agent's brainstorm calls for — a `MultiTurnEnv` whose `env_response()` is the natural seat for a persona-reactor; a `StatefulToolEnv` whose tools mutate a shared state dict (= the agent's fact graph); a `Rubric` that composes decidable reward functions and `JudgeRubric` LLM-judges with weights (= the agent's "decidable + cultural-judge stratification"); first-class trajectory capture in token-in/token-out form designed for downstream SFT/DPO/RL; first-class GEPA integration via `VerifiersGEPAAdapter` (= the prompt-evolution path for the agent's `project()` learning thesis); active daily commits; MIT-licensed; uv-managed Python; OpenAI-format tool-calling that matches Qwen 3.6's training prior. None of the other candidates — Inspect AI, SkyRL, τ³-bench, DSPy/GEPA, Letta, Voyager — give you that combination natively, and Verifiers' integration with PRIME-RL means we're not locked out of larger-scale RL training when the agent wants to scale.

## The honest comparison

| Project | Curriculum | Tool-format | Multi-role | Trajectory | Scoring | License + maint | Python | Phase-0 verdict |
|---|---|---|---|---|---|---|---|---|
| **Verifiers** | Dataset-as-curriculum, branching v0.1.8+ | OpenAI native | reactor via `env_response`, judge via `JudgeRubric` | First-class (RL-shaped) | **Best in class** (rubric+weights+judge) | MIT, daily | Yes (uv) | **Use as-is** |
| **Inspect AI** | Dataset, no scheduler | Provider-bridged OpenAI | First-class (`handoff`/`as_tool`/`bridge`) | Excellent for eval; you write SFT export | Excellent (decidable + model-graded) | MIT, daily, AISI-backed | Yes | Pass for Phase 0; revisit for V1 eval rig |
| **SkyRL** | Not first-class | OpenAI via Gym | Single-agent-per-rollout | RL-shaped | Reward funcs (less ergonomic) | Apache-2.0, daily | Yes (heavy deps) | Pass; revisit at training scale-up |
| **τ³-bench** | Per-task list | LiteLLM/OpenAI | First-class user-sim | Excellent (sim logs + Gym export) | **Best evaluator stack** | MIT, active | Yes | Borrow concepts; strongest fork-and-adapt fallback |
| **DSPy + GEPA** | No | Adapter-wrapped | Awkward | Trace-shaped (DSPy-specific) | Single metric per program | MIT, daily | Yes | Skip standalone; use via verifiers' GEPA adapter |
| **Letta** | No | OpenAI native | Coordinator-style only | Conversation logs, not trajectories | None native | Apache-2.0 | Yes | Pass as harness; reference for V2 production runtime |
| **Voyager** | Curriculum agent | Mineflayer JS (irrelevant) | 3-role pattern (curr/action/critic) | Per-task program traces | Critic-as-judge | MIT, **dead 2023** | Yes + JS | Pass on code; borrow 3-role pattern |
| AgentVerse | — | — | Yes | — | — | Apache-2.0, stale 2024 | Yes | Pass |
| Cognee/Zep/Mem0 | n/a (memory products, not harnesses) | | | | | | | Pass; covered in memory-arch doc |
| OpenHands | Coding-shaped | Yes | Yes | Yes (with SkyRL) | Yes | MIT, daily | Yes + TS | Pass — wrong domain |
| NeMo Agent Toolkit | Orchestration, not training | | | | | Apache-2.0, active | Yes | Pass for Phase 0; revisit at productionization |

## Phase-0 plan with Verifiers (concrete)

The mapping from the agent's brainstorm to Verifiers code:

| the agent concept | Verifiers seat | Lines of work |
|---|---|---|
| the orchestrator agent | Default OpenAI client driving the rollout | 0 (built-in) |
| Persona-reactor | `MultiTurnEnv.env_response()` calling a different model | ~50 (subclass) |
| Cultural-native grader | Subclass of `JudgeRubric` with per-persona model routing | ~100 |
| Fact graph state | `StatefulToolEnv` tools (`assert/retract/query/note/embed/nearest/project`) over a state dict | ~200 + storage |
| Hand-written scenarios | Verifiers `Dataset` rows | trivial loader |
| Decidable goals | Plain reward functions in a `Rubric` | per-scenario |
| Trajectory capture | Built-in token-in/token-out trajectory tracking | 0 |
| Tree-search / MCTS branching | v0.1.8 truncated/branching rollouts as building blocks; thin wrapper for the agent's branch policy | ~200 |
| `project()` prompt evolution | `VerifiersGEPAAdapter` | 0 (point at the agent env, run GEPA) |

**Phase 0 deliverable:** ~600 lines of the agent-specific code on top of verifiers, running multiple Qwen instances against ~5 hand-written scenarios on a single GCP node, with full trajectory logs Sean can read.

## Secondary candidates — keep these warm

In case verifiers has a discovered fatal flaw during Phase 0 implementation:

1. **τ³-bench (tau2-bench).** The strongest fork-and-adapt fallback. Its evaluator pipeline is more thoughtful than verifiers' rubric layer. If verifiers' rubric composition turns out limiting, τ³'s `evaluator_action + review_llm_judge + hallucination_reviewer` taxonomy is the model. The cost of switching is "rebuild the agent scenarios as a τ³ domain" — non-trivial but bounded.

2. **Inspect AI.** The strongest *eval-first* fallback. If the agent's bottleneck turns out to be eval reproducibility / audit trails (e.g., the client lead wants per-persona compliance evidence) rather than training-data extraction, Inspect's logging + viewer + bridge architecture is the right substrate. The two compose: verifiers for inner loop, Inspect for outer eval rig.

## What we still have to build on top of any harness

Even with verifiers, these remain the agent's labor:

1. **The fact-graph state model.** Datomic-style EAVT with retraction is not in any of the harnesses. We're writing it. Phase 0 can use a simple Python dict + JSON-snapshot; V1 needs SQLite + bi-temporal.
2. **The persona corpus.** ~5 hand-written personas for Phase 0; the multi-LLM fanout for V1.
3. **Cultural-grader model wiring.** Falcon-H1-Arabic / Mistral / Plamo / panel — needs concrete API/hosted/local-deploy decisions per culture.
4. **Tree-search orchestration.** Phase 0 doesn't need it (linear rollouts are fine); V1 does. Verifiers gives building blocks; the agent writes the branch-spawn / branch-prune policy.
5. **Trajectory → SFT/DPO export.** Verifiers' trajectories are already RL-shaped, but the agent's specific top-decile filter + cross-cultural-grader stratification is custom selection logic. ~150 lines.
6. **The scenario authoring tool.** Sean's M0 has him hand-authoring scenarios. M1+ may want a curriculum-agent (Voyager-pattern) that proposes next scenarios; that's its own ~200-line module.
7. **The OCR / compressed-memory investigation arm** is entirely orthogonal to harness choice; verifiers won't help and won't hurt.

## Open questions for the next pass

1. **Verifiers' `EnvGroup` + branching primitives — sufficient for MCTS-style trajectory tree?** Need to read `envs/env_group.py` and the v0.1.8 release notes more carefully before committing the M2/M3 design. *Risk: if branching is shallower than the agent's tree-search plan needs, we wrote a wrapper.*
2. **State-passing across persona-reactor turns.** Verifiers' `state` is shared between agent and env, but the agent's reactor needs *its own* state (the persona's mood, evolving frustration) separate from the agent's state. Probably a sub-key in `state`, but verify the patterns library doesn't assume single-state.
3. **Multi-rubric composition for the decidable + cultural + hallucination split.** Verifiers' `RubricGroup` exists; need to confirm weights compose the way the agent's "decidable-pass + cultural-pass" gating wants (boolean AND, not weighted sum).
4. **PRIME-RL training loop** is the natural next step after Phase 0. Read the verifiers → PRIME-RL handoff docs before Phase 1 begins.
5. **Tongyi DeepResearch's training recipe** — read their paper at Phase 1 design time. Closest published prior art on training info-agents specifically.
6. **OpenEnv integration in v0.1.10+.** This is the bridge that makes verifiers envs runnable inside other Gymnasium-based stacks (including SkyRL). Confirm the agent envs should follow OpenEnv conventions to preserve future-flexibility.
7. **Letta-style memory-block UX** as the V1 "what does the agent know about you" surface — open question Q13 in brainstorm-decisions. Worth a separate scoping pass.

## Honest pushback on the survey

The reason this synthesis recommends one harness rather than "build custom" is **not** because the harnesses are perfect fits for personal-info agents — they aren't. Most are coding-agent / web-agent / RL-game-shaped. Verifiers wins because it's deliberately the *thinnest* harness in the field — it's a small library that happens to nail the agent's specific abstractions, not a megaframework that retrofits.

The honest read: if verifiers had not existed, "build custom" would be the recommendation. Three out of seven of the agent's ranking criteria — multi-role with persona-reactor + grader, fact-graph-as-state, trajectory tree-search — have no natural seat in most of the surveyed projects. Verifiers gives us the first two cleanly and the third with a wrapper. That's not a coincidence; the willccbb / Prime Intellect lineage is RL-frame practitioners who built for training small-team agents, not product teams retrofitting eval onto production runtimes. The shape is right.

If verifiers turns out brittle in Phase 0 — async bugs, branching limits, rubric composition gotchas — the agent's fallback is to write a thin custom multi-turn loop in ~400 lines (the ToolEnv + MultiTurnEnv + Rubric abstractions are not difficult to reproduce) and lean on τ³-bench's evaluator architecture for the scoring layer. The "all the harnesses are awkward fits, build custom" verdict was the honest possibility going in; it's not the verdict because verifiers exists and is the right shape.
