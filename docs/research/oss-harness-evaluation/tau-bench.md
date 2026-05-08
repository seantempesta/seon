# tau-bench / tau2-bench / tau3-bench (Sierra Research)

**Summary.** A simulation framework where an LLM-driven user converses with an LLM-driven agent in a domain (airline / retail / banking / telecom) with tools and a policy; success measured by tool-call action correctness + per-trajectory verification. The original v1 repo is frozen; **τ³-bench (in `tau2-bench` repo) is the live successor.**

**Repos:**
- [sierra-research/tau-bench](https://github.com/sierra-research/tau-bench) — original (last commit 2026-03-18, marked outdated)
- [sierra-research/tau2-bench](https://github.com/sierra-research/tau2-bench) — τ²/τ³, **the live one** (last commit 2026-05-05)

**License:** MIT (both)
**Language:** Python (3.12+ required for tau2)

## Architecture

The relevant pieces (looking at `tau2-bench`):

- **Domains** (`src/tau2/domains/{airline,retail,telecom,banking_knowledge}`) — each domain is a policy doc + tool list + task fixture set + optional user-tools.
- **User simulator** (the persona-reactor analog) — pluggable via `--user-llm`. Strategies include `llm` (model-driven dialog) and `react` (model with explicit thinking). **This is exactly the persona-reactor seat the agent wants** and it's first-class.
- **Orchestrator** (`src/tau2/orchestrator/`) — half-duplex (turn-based) and full-duplex (voice) modes. Coordinates user-sim ↔ agent ↔ tools.
- **Evaluator** (`src/tau2/evaluator/`) — multiple evaluator modules: `evaluator_action.py` (decidable action correctness), `evaluator_communicate.py` (NL-assertion checks), `evaluator_nl_assertions.py`, `review_llm_judge.py` (LLM-judge), `auth_classifier.py`, `hallucination_reviewer.py`. **Very rich — the most thoughtful evaluator stack in this survey.**
- **Trajectory capture** — full simulations saved to `data/simulations/`, browsable via `tau2 view`.

τ³-bench specifically adds: knowledge-retrieval domain (`banking_knowledge`) with configurable RAG, voice full-duplex eval, 75+ task fixes from the SABER paper, plus a **`gymnasium` extra** (`uv sync --extra gym`) — i.e., the env can be exposed as a Gym env for RL training.

Tool format: agent tools are LiteLLM-driven function-calling, OpenAI-format. Compatible with Qwen 3.6.

## the agent-specific fit

1. **Curriculum / scenario-driven training:** Tasks are listed and runnable individually. Curriculum = ordered task list. **No automatic curriculum scheduler**, but `--task-ids` and per-task evaluation make scripted curriculum trivial.
2. **Pluggable tool-call format:** Yes (LiteLLM, OpenAI-format).
3. **Multi-agent / multi-role:** Yes, but specifically "user-sim + agent" — fits the agent's reactor + agent split. The cultural-judge-grader role isn't built in but the evaluator pipeline gives you the seat.
4. **Trajectory capture:** Excellent — full simulation logs designed for analysis, gymnasium-API export available.
5. **Pluggable scoring:** Strongest evaluator stack in the survey — multi-axis scoring out of the box. **the agent's "decidable + cultural-judge stratification" idea is essentially what the τ³ evaluator pipeline already does** (action correctness + NL-judge composition).
6. **License + maintenance:** MIT, active (May 2026), Sierra-maintained.
7. **Python-first:** Yes.

## What we'd need to change/add for the agent Phase 0

- **Define an "the agent-info-agent" domain** — a directory `src/tau2/domains/aria_personal/` with a policy doc, tool list (the agent's primitives), task fixtures, optional user-tools. **This is the central labor.** Modeled after `airline/` or `retail/` — those are well-documented and small (a few hundred lines each).
- **Persona corpus → user-sim configs.** Each persona (KSA, French, Egyptian-American) becomes a user-sim variant with culturally-specific system prompt and a different model lineage (Falcon-Ar, Mistral, etc.).
- **Cultural-native grader** — add a custom evaluator module under `src/tau2/evaluator/` that routes per-persona to the right grader model.
- Tree-search / branching is **not** native — same gap as everywhere else.

## Verdict

**Strong borrow-concepts candidate; possibly fork-and-adapt for V1.** The architecture is *exactly* the shape the agent needs: agent + simulated user + tool-use + multi-axis evaluation, all production-grade. The orchestrator + evaluator pipeline is more thoughtful than anything else in the survey for this use case.

Two reasons not to pick it as the agent Phase 0's primary harness:

1. **Domain commitment.** τ³-bench is shaped around customer-service domains (airline/retail/banking). the agent's "personal-info agent" domain doesn't exist; we'd have to build it from scratch within their structure. That's not bad — it's exactly how new τ³ domains get added — but it's a bigger initial investment than verifiers (where you write a Python class and you're done).
2. **Training-loop fit.** τ³ is eval-shaped; training is via the gymnasium export, not native. Verifiers' RL/SFT integration is more direct.

**Recommend: borrow the evaluator-pipeline architecture; pass on adopting τ³ as the agent's primary harness.** When we write the agent's evaluator module(s) for verifiers, copy τ³'s multi-evaluator composition pattern — `evaluator_action` (decidable) + `review_llm_judge` (cultural) + `hallucination_reviewer` (the agent's "did the agent fabricate facts" check) is the right taxonomy.

If verifiers turns out wrong for some reason, **τ³-bench is the strongest fork-and-adapt fallback.**
