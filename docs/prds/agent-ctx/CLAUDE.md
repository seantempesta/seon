---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# agent-ctx — compose context from functions over the db, then measure

**The chunk:** compose the agent's context from functions applied to the db,
then MEASURE whether that context + the tool surface actually let agents get
work done. The idealized system is `docs/seon/architecture/` (read
[[architecture]] + [[context]] FIRST — don't restate them). This folder is
the roadmap chunk. **Status lives in [[roadmap]] and [[context-rebuild]]
(the plan of record) — not here.** Cross-lane channel: [[coordination]].
Shared ledger: `evals/scorecard.jsonl`.

## Current state (2026-07-11)

**The context-rebuild cutover is DONE — the default cluster runs the rebuilt
minimal tree.** `system.edn` carries the graduated **v3.1 system-text** (the
`:seon.config/system-text` datom — one source; `minimal.edn` inherits it) plus
the evidenced tree: `:namespaces` + `:plan` (with its html twin — the human's
live plan tile) + `:transcript`, and root's three KEPT derived fault surfaces
(`:core-faults`, `:instrumentation-gaps`, `:orphaned-agents` — 0 tokens when
healthy). Every legacy block is OUT of the running tree; the old tree is frozen
in `config/legacy.edn` (comparison drives only, expiry-dated). Capability
milestones `repl` / `namespaces` / `plan` are GREEN (both model classes); **`db`
is next**. See [[context-rebuild]] for the milestone table, the target
block set, and the inclusion bar; [[roadmap]] for detailed status.

## The three lanes (all active as of 2026-07-11)

- **Context / tooling lane** — the runtime + FSM (`seon.agent.*`), the context
  engine (`seon.agent.ctx`, `seon.render`, `seon.eval`, `seon.instrument`), the
  `my.*` tool surface, and the context-rebuild arc itself. Owns the **default
  pod (7890)**.
- **Eval / measurement lane** — the standing inspect-ai suite over the
  `POST /agents/run` door (`src-inspect-ai/`), `pass^k` stability, the flake
  taxonomy, and the context-refinement A/Bs those numbers drive (every
  trim/skill-edit/tool-tune is an A/B against frozen samples — the ledger
  decides, not taste).
- **Diffusion-typeahead lane** (owner-directed since 2026-07-10) — menu/plan
  affordances (`seon.agent.ctx.menu`) + a local-DiffusionGemma step-loop
  provider, measured in `src-inspect-ai`. Owns **acme (7980)** as its testbed.
  See the [[coordination]] 2026-07-11 entries.

## The contract between the lanes

- **Boundary:** the eval lane does NOT touch tool/runtime/ctx-engine internals;
  the tooling lane does NOT touch the harness — it gets the numbers. When a row
  fails, the eval lane **attributes** it (context defect vs tool defect vs flake
  vs model) and hands tool defects to the tooling lane **with captured
  rendered-context evidence**.
- **Shared surface = the CONTEXT.** A change to *which* content renders (via
  `config/system.edn`, measured) is eval/context-lane; a change to *how* it
  renders (the engine, required-keys, auto-run) is tooling-lane. When in doubt,
  flag in [[coordination]] before editing another lane's file.
- **Pod ownership (owner rulings):** default 7890 = context/tooling; acme 7980 =
  typeahead lane. Separate systems — neither lane coordinates restarts/resets of
  the other's pod; keep your OWN pod on the latest build + context. Never touch
  another lane's pod, never the JVM track.

## The load-bearing finding (binds the whole chunk)

**Every check a scorer makes MUST be stated in the agent's context, or the bench
measures prompt-omission, not capability.** (DeepSeek preflight: 0/2 → ~1.0 on
the contract sentence alone.) The eval lane's numbers are only meaningful if the
context actually says what the task needs. The rebuild's corollary (the poison
principle): omission is recoverable and attributable, inclusion is neither — so
evidence attaches at INSERTION time and the safest posture is minimal (see the
inclusion bar in [[context-rebuild]]).

## Settled — do NOT re-litigate

- Chunk name = `agent-ctx`. Vocabulary maps to Clojure primitives:
  **required-keys** (not injection/ALS), **current-ns** (not "workspace"),
  **refs** link the agent entity to namespace-scribed entities; **functions**
  never "verbs"; **`:batch`/`:stream`** never "Mode A/B"; milestones named by
  the block/namespace they validate (`repl`/`namespaces`/`plan`/`db`/`warnings`/
  `canvas`/`subagents`/`soul`). A new noun = parallel-system risk.
- `docs/seon/architecture/` is the SINGLE idealized-system set; this folder is
  the roadmap chunk, not a second doc system.
- **Eval:** scorers gate CORRECTNESS (parses ∧ spec validates ∧ runs ∧ right
  answer) — idiom/style is reported data, never a gate. Established benches over
  homemade; bespoke only where no public bench exists. Flakes are classified +
  excluded from capability means. Uniform 0-scores → suspect the harness/context
  first, not a model ceiling.
- **No maintained code in PRD dirs** (`src-inspect-ai/` is the precedent — a real
  top-level package). Env never shadows config; config resolves into the
  `:seon.config` DB singleton at boot, runtime reads the db.
- **The cluster is the isolation unit** (owner-ratified): one shared DB + one
  Node pod + agents; isolation = the process boundary + the wire capability
  surface. One wire-server JVM hosts ALL clusters' dbs (the shipped registry) —
  never build a second registry. `POD_MAX_SAMPLES=1` is LOCKED.
- **Implementation = opus seon-agents against a written spec.** Iterate wording
  on Spark, gate on DeepSeek; no symptom-side hacks (root cause = wrong context
  or wrong code).

## How to run

```bash
bin/seon status                       # pods/pids/port (7890 default)
bin/seon restart pod                  # wait for "auto-boot ready" in logs/pod.log
bin/seon cluster reset default        # fresh default cluster — WIPES the store; re-seeds
src-inspect-ai/.venv/bin/pytest       # offline harness and scorer proof
# eval suite: src-inspect-ai/README.md run matrix → evals/scorecard.jsonl
# live-drive: (seon.db/with-agent "root" (fn [] (seon.agent/start! {:seon.agent/purpose "…"}))) ; then rearm-wake-triggers!
```

`SEON_CONFIG` + the provider key must be exported on every cluster create AND
restart; check `logs/pod.log` for `SEON-STUB-LLM` after a provider boots (a
configured provider with its key unset drives on the stub). `cluster reset
default` after a context/block change to re-seed the shared pod.

## Pointer index

- **Plan of record:** [[context-rebuild]] (the milestone table, target
  block set, idea inventory, inclusion bar, cutover status) · **we-are-here:**
  [[roadmap]] · **cross-lane channel:** [[coordination]]
- **Architecture (idealized system):** [[architecture]] · [[context]] ·
  [[data-model]] · [[agent-runtime]] · [[ui]] · [[observability]] · [[toolkit]]
- **Eval-lane docs:** [[eval-design]] (the spec) · [[eval-lane-plan]] (work plan
  and boundary) · harness code in `src-inspect-ai/` (do not maintain from the
  tooling lane)
- **Audits (2026-07-11):** [[research/claude-md-audit-2026-07-11]] ·
  [[research/vocabulary-audit-2026-07-11]]
- **Evidence ledger:** `evals/runs/2026-07-10-minimal-buildup/README.md`
  (per-drive milestone evidence) · `evals/scorecard.jsonl` (the shared truth)
- **Tracked open work:** `docs/seon/orchestrator/issues/dual-code-paths-registry.md`
  (the ONE list of dual-code-path / complexity-artifact rows; closes only with
  the fixing sha)
