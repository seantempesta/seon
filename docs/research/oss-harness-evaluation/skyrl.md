# SkyRL (Berkeley NovaSky)

**Summary.** Full-stack open-source RL library for LLMs — `skyrl-train` (the trainer with GRPO/RLOO/PPO), `skyrl-gym` (Gymnasium-API tool-use environments), `skyrl-agent` (multi-turn long-horizon agent layer), and `skyrl-tx` (Tinker-API backend). Currently mid-reorganization into a unified `skyrl/` package.

**Repo:** [NovaSky-AI/SkyRL](https://github.com/NovaSky-AI/SkyRL)
**License:** Apache-2.0
**Last commit:** 2026-05-07 (active daily)
**Language:** Python (uv-managed, GPU-heavy deps)

## Architecture

Four pieces:

- **`skyrl-gym`** — Gymnasium-style environments. Built-in domains: math, coding, search, SQL, terminal-use (via Harbor integration). Each env exposes `step(action) -> (obs, reward, done, info)`. Tool-use is the first-class abstraction.
- **`skyrl-agent`** — Long-horizon agent loop with multi-turn rollouts; the paper (arxiv 2511.16108, Nov 2025) is the canonical "how to train multi-turn tool-use agents" reference. Optimized for SWE-bench / terminal tasks.
- **`skyrl-train`** — The actual trainer. GRPO + RLOO + PPO + on-policy distillation. Async rollouts with in-flight weight updates.
- **`skyrl-tx`** — Implements the Tinker API (the de-facto 2026 RL-trainer interface) on local GPUs. Lets you use any Tinker-API-compatible training script.

Tool format: standard OpenAI tool-calling JSON.

Trajectory capture: full per-step observation + action + reward sequences, written for RL training consumption directly.

## the agent-specific fit

1. **Curriculum / scenario-driven training:** Possible via dataset ordering; no first-class curriculum scheduler, but you can chain training runs with progressively harder splits.
2. **Pluggable tool-call format:** OpenAI-format; works with Qwen 3.6.
3. **Multi-agent / multi-role:** Limited. `skyrl-agent` is single-agent-per-rollout. Persona-reactor would have to be modeled as part of the env (`step()` returns the persona's response). Workable but awkward — the persona becomes a fixture not a peer.
4. **Trajectory capture:** Excellent — designed for RL training, full token-in-token-out sequences.
5. **Pluggable scoring:** Reward functions are first-class but the abstraction is "score one rollout, return float." LLM-judge is doable as a reward func that calls another model. **Less ergonomic than verifiers' rubric-with-multiple-funcs+weights.**
6. **License + maintenance:** Apache-2.0, daily commits, Berkeley-backed, well-funded with industry compute partners (Anyscale, NVIDIA, AMD, AWS, Modal).
7. **Python-first:** Yes, but heavier deps (vLLM, Ray, GPU-resident).

## What we'd need to change/add for the agent Phase 0

- Wrap the the agent primitive set as a `skyrl-gym` env. Persona-reactor becomes part of `step()`.
- Implement scoring as RL reward funcs.
- Build a curriculum scheduler (gymnasium doesn't have one).
- The whole stack assumes you're training with vLLM + Ray + GPUs. **Phase 0 doesn't want to train yet.** SkyRL with no training is a heavy gymnasium-API-runtime; the `--inference-only` path exists but it's not the primary use case.

## Verdict

**Pass for Phase 0; revisit when training begins.** SkyRL is the right tool for the *training* phase — when the agent has 50–200 personas producing trajectories and we want to do real GRPO/RLOO on top-decile rollouts, SkyRL (or PRIME-RL via verifiers) is where we land.

For Phase 0, SkyRL forces a Gymnasium API on a problem (multi-turn dialog with persona) where Gymnasium API is awkward. Verifiers' OpenAI-message-list trajectory model is a better fit for "agent + persona-reactor talking" than `(obs, action)` tuples.

**Sequencing:** Phase 0 = verifiers. Phase 1 (training) = verifiers + PRIME-RL OR re-host envs into skyrl-gym for skyrl-train. Verifiers explicitly integrates with PRIME-RL (Prime Intellect's trainer); choosing verifiers does not lock us out of the SkyRL stack.

The 2026-04-17 verifiers update added OpenEnv integration which is the same Gymnasium-API surface SkyRL uses, so the bridge between the two ecosystems is increasingly well-paved.
