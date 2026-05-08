# Verifiers (Prime Intellect / willccbb)

**Summary.** A Python library for LLM RL/eval that treats a "task + tool-loop + reward function" as a single composable `Environment` object — multi-turn, async, OpenAI-compatible client, with pluggable rubrics that mix decidable functions and LLM-judges.

**Repo:** [PrimeIntellect-ai/verifiers](https://github.com/PrimeIntellect-ai/verifiers) (originally willccbb)
**License:** MIT
**Last commit:** 2026-05-07 (active daily)
**Language:** Python (uv-managed)
**Recent versions:** v0.1.12 (2026-04-17) — composable Task/Agent/Environment, multi-worker env server, OpenEnv integration. v0.1.10 (2026-02) added GEPA support.

## Architecture

The unit of work is `Environment`. Three relevant subclasses for the agent:

- **`MultiTurnEnv`** (`envs/multiturn_env.py`) — abstract base. You override `env_response(messages, state) -> messages` to define what the *environment* (or a persona-reactor) says back each turn. Trajectory is captured as a list of `TrajectoryStep` records inside `state["trajectory"]`. Built-in `MultiTurnMonitorRubric` already counts turns as a free metric.
- **`ToolEnv`** — adds tool-calling on top: pass a list of plain Python callables; the env auto-converts to OpenAI tool-defs, executes calls, formats results back as `ToolMessage`. Has a `ToolMonitorRubric` that auto-tracks per-tool call counts.
- **`StatefulToolEnv`** — tools that can read/write the environment's `state` dict. **This is exactly what the the agent primitive set wants** — `assert/retract/query/note/project` all need access to a shared fact graph.

Scoring is `Rubric` (`rubrics/rubric.py`). A rubric is a list of `(reward_func, weight)` pairs. Reward funcs are async, take `(prompt, completion, answer, state, **kwargs)`, return float. `JudgeRubric` (`rubrics/judge_rubric.py`) wraps an `AsyncOpenAI` client with a configurable judge model + prompt — drop-in LLM-as-judge that can co-exist with decidable funcs in the same rubric. `RubricGroup` lets you compose rubrics.

Trajectory format is well-defined (`types.py`): `Messages` list + per-turn `TrajectoryStep` with timing. Token-in/token-out tracking added in v0.1.8 specifically so trajectories are usable as RL training data downstream.

GEPA integration is built in (`gepa/`) — `VerifiersGEPAAdapter` lets you run GEPA's reflective prompt evolution against any verifiers env, using rubric scores as the fitness signal. This is a free `project()`-prompt-evolution path for the agent.

Tool format follows OpenAI tool-calling JSON (matches Qwen 3.x training prior — Qwen 3.6 BFCL-V4 67.3 was trained on this format).

## the agent-specific fit (against the seven ranking criteria)

1. **Curriculum / scenario-driven training:** Yes — environments take a dataset of inputs; you can structure the dataset as the curriculum (turn-1 episodes, then escalation, then contradiction). Scoring decides what's "passed" each turn. Tree-search-style branching (the agent's MCTS plan) isn't first-class but `EnvGroup` + truncated/branching rollouts (v0.1.8) are the building blocks.
2. **Pluggable tool-call format:** Yes — ToolEnv emits OpenAI-format tool calls; Qwen 3.6 trained for exactly this format. No retraining-against-format mismatch.
3. **Multi-agent / multi-role:** Partial. `env_response` is the natural seat for a persona-reactor (different system prompt, different model client). `JudgeRubric` is the seat for the cultural-native grader. **What's missing:** there's no first-class "spawn three roles, share a session" abstraction — but the `MultiTurnEnv` + `JudgeRubric` composition gives you the same effect with a small wrapper.
4. **Trajectory capture for SFT/DPO:** First-class — token-in-token-out trajectories, JSON-serializable state, designed for downstream RL/SFT. Top-decile + bottom-decile pair selection for DPO is straightforward.
5. **Pluggable scoring:** This is the strongest fit in the field. Decidable + LLM-judge in the same rubric, with weights, with auto-monitor metrics.
6. **License + maintenance:** MIT, Prime Intellect-backed, daily commits. Apache-2.0-friendly enough.
7. **Python-first:** Yes, uv-managed.

## What we'd need to change/add for the agent Phase 0

- **A `PersonaReactorEnv`** subclass of `MultiTurnEnv` with `env_response` calling a different model/system-prompt. ~50 lines.
- **A `FactGraphState`** struct that primitives mutate via `StatefulToolEnv` — an `assert/retract/query/note/project/embed/nearest` toolset. ~200 lines including SQLite or in-memory backing.
- **A `CulturalGraderRubric`** that routes per-persona to Falcon-Ar / Mistral / panel — subclass of `JudgeRubric` with a model-router. ~100 lines.
- **A scenario loader** that reads hand-written scenarios (the agent's M0) as the env dataset. Trivial.
- **Trajectory-tree harness** (the agent's MCTS plan) — branching is supported in v0.1.8 but you'd need a thin orchestration loop on top.

What we explicitly do *not* have to build: trajectory capture, OpenAI-compat client wiring, async loop, judge integration, prompt-evolution (GEPA already wired), multi-worker server.

## Verdict

**Use as-is for Phase 0** (the observation harness). The library is shaped exactly like the agent's described build thesis — primitives + multi-turn loop + pluggable scoring + persona-reactor seat. The only friction is that "persona-reactor as a separate role" needs a thin wrapper, and the fact-graph-as-state is something we have to build (but we'd build that anyway). Author + maintainers are RL-frame practitioners (willccbb has a strong public track record on small-team RL agents); the codebase reads as engineer-built-for-engineers, not framework-author-yak-shaving.

The clincher: **GEPA is already integrated** and the agent's "agent learns its own `project()` idiom" plan reads as a textbook GEPA optimization target. Picking verifiers means we get prompt-evolution-on-trajectories for free without writing the optimizer.
