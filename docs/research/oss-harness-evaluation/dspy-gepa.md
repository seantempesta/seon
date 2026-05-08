# DSPy + GEPA (Stanford NLP / GEPA team)

**Summary.** DSPy is a framework for "programming, not prompting" LMs — declarative compositional modules with a separate optimizer layer. GEPA (Generalized Evolution of Policy Agents, Jul 2025 paper) is one of those optimizers: reflective prompt evolution that uses natural-language-feedback gradients (TextGrad-style) on Pareto-selected trajectories. Both are *not* a harness; they're the optimizer-side of training, complementary to one.

**Repos:**
- [stanfordnlp/dspy](https://github.com/stanfordnlp/dspy) — DSPy framework
- GEPA ships as `dspy.teleprompt.gepa` (in-tree) and as a standalone library [gepa-ai/gepa](https://github.com/gepa-ai/gepa)

**License:** MIT (DSPy)
**Last commit:** 2026-05-07 (DSPy daily)
**Language:** Python

## Architecture

DSPy programs are compositions of `Module`s; each Module has a `Signature` (typed input/output schema) and a `predictor` (the LM call). You write a `forward()` method composing them. Then you pass the program to a `Teleprompter` (optimizer) along with a `metric` function and a training set; the optimizer mutates prompts/few-shots/weights to maximize the metric.

Optimizers (`dspy/teleprompt/`):
- **GEPA** (`gepa/`) — Pareto-frontier reflective prompt evolution. Mutates system prompts based on natural-language reflections on failure trajectories. The Jul'25 GEPA paper claims this beats RL on agent tasks at fraction of the compute.
- **MIPROv2** — Bayesian-optimized prompt + few-shot search.
- **GRPO** (`grpo.py`) — yes, DSPy now has GRPO too (so you can RL-train inside DSPy).
- **bootstrap_finetune.py / bootstrap_trace.py** — supervised fine-tune on bootstrapped trajectories.
- **simba.py / random_search.py / signature_opt.py / copro_optimizer.py** — many more.

Tool format: DSPy ships its own `dspy.Tool` abstraction; can adapt to OpenAI-format with the right adapter.

Multi-agent: you can compose Modules but DSPy isn't multi-agent-first; persona-reactor would be a separate `dspy.LM` you call inside `forward()`.

Trajectory capture: traces are first-class (used by `bootstrap_trace`) but they're DSPy-shaped traces, not raw token sequences.

## the agent-specific fit

1. **Curriculum:** Not native. You'd manage curriculum stages outside DSPy.
2. **Pluggable tool-call format:** Possible via adapter; not native OpenAI tool-calling.
3. **Multi-agent:** Workable but awkward — DSPy is single-agent-program-first.
4. **Trajectory capture for SFT:** Yes via `bootstrap_trace` / `bootstrap_finetune`. RL trajectories via `grpo.py`.
5. **Pluggable scoring:** Metrics are first-class but the abstraction is one metric per program. LLM-judge metrics are common practice but not the same multi-axis rubric verifiers gives you.
6. **License + maintenance:** MIT, Stanford-backed, very active.
7. **Python-first:** Yes.

## What we'd need to change/add for the agent Phase 0

DSPy doesn't replace a harness; it replaces the **prompt-engineering cycle** for the agent's prompt(s). For the agent specifically:

- The agent's `project()`-prompt-evolution path = use DSPy + GEPA. the agent writes the `project()` module as a DSPy `Predict` with a Signature; runs episodes; GEPA evolves the prompt.
- The persona-reactor's prompts could similarly be DSPy-managed, but probably overkill — they're authored once per persona.

## Verdict

**Borrow concepts; not a harness for the agent.** DSPy + GEPA is the *optimizer* layer that sits on top of whatever harness the agent picks. Verifiers already integrates GEPA directly (no DSPy needed); that's the cleanest path.

If the agent specifically wants to stay in DSPy's ecosystem (declarative agent program + automatic prompt-evolution + standardized signatures), DSPy + GEPA is the call. But the harness piece — multi-turn dialog with persona-reactor and tool-use — is not where DSPy is strongest. Verifiers does that natively *and* exposes GEPA via the Verifiers-GEPA adapter.

**Recommend: skip standalone DSPy adoption. Use the GEPA-via-verifiers integration to get the agent's `project()`-prompt evolution.**

The substance under Nous Hermes' "agent evolves its own prompts" pitch is GEPA. the agent gets the same capability via verifiers without taking on DSPy's signature/module abstraction tax.
