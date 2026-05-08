# Inspect AI (UK AI Security Institute)

**Summary.** Production-grade evaluation framework with a `Dataset → Solver → Scorer` pipeline, first-class multi-agent support (handoff, as_tool, react), and an "Agent Bridge" that lets you wire any external Python agent into Inspect's logging/scoring. Eval-first; training is a downstream-of-trajectories concern.

**Repo:** [UKGovernmentBEIS/inspect_ai](https://github.com/UKGovernmentBEIS/inspect_ai)
**License:** MIT
**Last commit:** 2026-05-07 (active daily)
**Language:** Python
**Backers:** UK AISI (state-funded, deep regulatory alignment).

## Architecture

The unit of work is a `Task` = `(Dataset, Solver, Scorer)`. Dataset rows are `Sample`s (input + target + metadata). Solvers transform the agent's state turn-by-turn. Scorers reduce the final state to a numeric score plus a categorical answer.

Agent layer (`src/inspect_ai/agent/`) is rich:

- **`react()` agent** — the canonical ReAct loop with built-in `submit()` tool, retries, message-trimming, compaction strategies, message-filter middleware. Production-quality.
- **`as_solver`, `as_tool`, `handoff`** — promotion utilities that turn an agent into either a solver in a chain, a callable tool another agent can invoke, or a handoff-target in a multi-agent dialog. **Multi-agent is first-class.**
- **`bridge/`** — wraps an external agent (e.g., an OpenAI-API-compatible runtime) so it logs into Inspect's transcript and gets scored normally. Includes Anthropic-API-compatible bridge and Google-API bridge. Means you can put a Letta agent (or any production runtime) under Inspect's eval harness without rewriting it.

Scorers (`src/inspect_ai/scorer/`) cover:
- Decidable: `_match.py`, `_choice.py`, `_classification.py`, `_answer.py`, `_math.py`
- LLM-judge: `_model.py` — model-graded scorer with custom prompt/template
- Composition: `_metrics/` — accuracy, mean, std, custom reduce functions
- `score()` is callable mid-trajectory inside an agent if you want intermediate gating.

Trajectory capture is via `event/` + `log/` — every model call, tool call, scoring event is logged structurally to JSONL. The `viewer/` subcommand renders an interactive trajectory browser. **The trajectories are SFT-ready** but the project doesn't ship a "convert log to training data" CLI; you write that yourself (~50 lines).

Tool format: Inspect's `Tool` abstraction wraps either OpenAI-style JSON tools or Anthropic-style; provider-specific bridges in `_bridge/` translate. Qwen-trained format works via the OpenAI provider path.

## the agent-specific fit

1. **Curriculum / scenario-driven training:** Datasets are the natural curriculum carrier — order them, gate progression on `Scorer` results, control via `epoch` and `attempts`. **No first-class curriculum-builder**, but Inspect calls eval suites as `Tasks` you can chain in Python.
2. **Pluggable tool-call format:** Yes — provider-agnostic via bridges; OpenAI-format default for Qwen.
3. **Multi-agent / multi-role:** Strongest fit in the field. `handoff()` between persona-reactor and the orchestrator agent, `as_tool()` to make the cultural grader callable, `bridge/` to plug in any external runtime. **This is what Inspect was designed for** post-`ControlArena`.
4. **Trajectory capture:** Excellent for eval. For SFT/DPO use-as-training-data, you write a small adapter from Inspect's eval-log format to your training format. Not a deal-breaker.
5. **Pluggable scoring:** Excellent — decidable + model-judge + composition all built-in, scorer can be invoked mid-trajectory.
6. **License + maintenance:** MIT, daily commits, AISI-backed (long-term sustainable).
7. **Python-first:** Yes.

## What we'd need to change/add for the agent Phase 0

- **A "persona-reactor agent"** — easy with `agent_with(model=different_model, prompt=persona_prompt)` and `handoff()`. ~30 lines.
- **A fact-graph state** — Inspect's `Store` (per-sample mutable state) is the natural seat. Tools mutate Store; primitives are tools. ~200 lines.
- **A cultural-native grader** — implement as a model-graded scorer with per-persona model-routing. ~80 lines.
- **Eval-log → SFT training data adapter** — ~50 lines.
- **Tree-search trajectory branching** — Inspect doesn't ship this; you'd run epochs and select top-k from logs.

## Verdict

**Strong fork-and-adapt candidate, but heavier than verifiers for the agent's specific use case.** Inspect is the right pick if the agent's primary need is *evaluation* (and eventually showing the client lead / regulators a clean trajectory log per persona), and *secondarily* training-data extraction. It's the right pick if you want enterprise-grade eval reproducibility and audit trails.

For the Phase 0 observation harness specifically: **probably overkill**. Verifiers is more directly shaped around the RL/SFT-loop need; Inspect treats training as downstream. The two also compose — you can use verifiers for the inner loop and Inspect for the eval reporting layer once V1 is real. The deep wins of Inspect (compaction, message-filter, audit log, viewer UI) start to matter at V1+ evaluation, not Phase 0 observation.

**Recommend: pass for Phase 0; revisit for V1 eval rig.**
