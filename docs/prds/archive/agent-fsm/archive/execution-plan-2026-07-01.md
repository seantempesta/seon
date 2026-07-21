---
type: archive
status: archived
tags: [archive, agent, plan]
---

> Superseded — the current we-are-here + dependency-ordered workstreams live in
> [[../roadmap]] (W1–W7). Kept as history. (Tracks A/B/C/F/G landed or are covered
> by the workstreams; Track D self-evolving memory #85 and Track E RunPod/diffusion
> are separate projects outside the agent-fsm roadmap.)

# Execution plan — post-config-init (2026-07-01)

The dependency graph + parallelism for the current push. Critical path is short;
several tracks run concurrently. Each track lists its tasks, dependencies, gate,
and which lane/agent owns it.

## Tracks

### Track A — Config-init closeout (IN FLIGHT, agent a93d447e) — #42/#87
- **A1** home-requires fix (verifier-caught) + full unit suite — *in flight*.
- **A2** LIVE DeepSeek E2E drive — init (block entities + config datoms seeded),
  multi-turn loop, configs driving behavior (decay/escape-clipping/skills/ns),
  memory store→retrieve across turns, plan RESUME across `restart pod`. **THE gate.**
- **A3** CP-6 closeout — component notes (`seon.agent.ctx`, `seon.config`) + PRD +
  final per-dial ledger.
- **Deps:** none (finishing). Sequential A1→A2→A3. **Blocks:** B2, F1, G1.

### Track B — Harness benchmark (bridge #86 DONE ✓)
- **B1** BUILD the niah / memory-QA inspect harness — real dataset, host-side
  scorer, `pass^k` epochs, per-sample fresh `:memory` conn isolation, fiber-local
  `*conn*` for parallelism. Conflict-free (inspect-bridge-spike/ + a scratch
  dataset; no `src/seon` overlap). **Can start NOW (parallel to A).**
- **B2** RUN the baseline measurement against the FINISHED config system —
  SHA-keyed trend (re-runnable as the config evolves, incl. the C1 compact flip).
- **Deps:** bridge ✓ (B1 now); B2 needs A done (measure the finished system).
  **Blocks:** D1.

### Track C — Namespace compact flip (OWNER's namespace-display lane) — the 82% lever
- **C1** compact-everywhere-except-current-ns as the default (only current ns full,
  rest compact) + GYM-measure adoption (render-prominence guardrail — the card
  carries the API so it should hold, but PROVE it). Drops the ~726k namespaces bulk.
- **Deps:** config foundation ✓ + acme free ✓ (config-init's acme drive done).
  Owner-driven (their agent, on acme). **Parallel to everything.**

### Track D — Self-evolving memory (#85)
- **D1** Milestone-1 loop — proposer writes `store!`/`recall` genomes, two-cold-child
  fitness (store→restart→retrieve under distractors), host-side checker selects,
  QD archive. GO = beats `my.kb/remember` on held-out battery.
- **Deps:** B2 (benchmark baseline) + A (per-agent LLM for proposer-strong/children-weak).
  **Downstream.**

### Track E — RunPod deployment consult (handoff written)
- **E1** Phase-2 GPU/isolation recommendation (Firecracker-in-RunPod vs Docker;
  GPU-served weak roles; DiffusionGemma-as-proposer feasibility).
- **Deps:** none (a consult). Owner hands the transfer prompt to the RunPod agent.
  **Parallel.**

### Track F — Core follow-ons
- **F1** #88 per-agent PROVIDER selection (per-call adapter). **Dep:** A done (touches
  the LLM path the config build just wired — avoid conflict).
- **F2** #26 /debug + /data rebuild · **F3** #56 naming policy · **F4** #83 writes-tests
  lever · #71/#73/#74. Mostly independent; schedule after the critical path clears.

## Integration gate

- **G1** — combined LIVE drive on a fresh cluster AFTER A + C converge: config-init +
  compact namespaces + memory + a benchmark sample, one agent E2E. The "everything
  works together" test. **Deps:** A3 + C1.

## Parallelism (what runs when)

```
NOW (concurrent):   A (closeout, a93d447e)  |  B1 (harness build)  |  C1 (namespace flip, owner lane)  |  E1 (RunPod, owner)
after A done:       A3 ✓ → B2 (baseline)  +  F1 (per-agent provider)
after A + C:        G1 (integration drive)
after B2:           D1 (memory-evolution Milestone-1)
```

## Critical path

`A1 → A2 (live drive) → A3 → B2 (baseline) → D1 (memory-evolution)`
— with B1, C1, E1 running in parallel off to the side.

## Verification discipline (applies to every track)

Live-proof over inference; git-commit checkpoints; independent verify at each
closeout (a `seon-verifier` for claims, a live DeepSeek drive for E2E); no early
victory; simplify-converge, no dead code. Byte-parity / gym-measured where a
behavior change is intended.
