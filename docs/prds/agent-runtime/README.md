---
type: reference
status: active
tags: [reference, prd, index]
---

# agent-runtime — index

The Seon agent runtime: substrate (eval, render, datahike, capabilities), agent loop (sessions, turns, ctx composition), and cross-platform delivery (Tauri shell, LAN/Tailscale access, mobile). Renamed from `webassembly-agents` on 2026-05-23 when scope expanded past the WASM proof of concept to cover the full runtime — WASM containment is one phase, not the whole story. Branch: `feature/agent-runtime`.

Two parallel tracks, multiple versions per track.

```text
Platform track        MVP track
══════════════        ════════
Phase 1 ✅ shipped    v1  📝 spec draft (see v1.md) — implementation NOT started
Phase 2 ✅ shipped    v2  📝 stub (see v2.md) — deferred from v1
Phase 3 🚧 active     v3  📝 stub (see v3.md) — deferred from v2
Phase 4–10 designed

```

"Shipped" means code in `src/seon/` (Platform track). "Spec
draft" / "stub" means design only — no code yet (MVP track v1+).
The V0 CLJS pod that runs deepseek today predates the v1 spec.

## Where to start

| If you're… | Read |
|---|---|
| Picking up the MVP spec cold | **v1.md** end-to-end (~900 lines, minimum-viable shape) |
| Wondering what was deferred from v1 and why | **v2.md** (full design preserved for blob storage, program graph, tests, warnings tile, budget enforcement, etc.) |
| Working on WASM containment | **platform.md** |
| Wondering what shipped recently | **STATUS.md** |
| Wondering why we model the schema this way | **research/*-2026-05-22.md** (four files: datahike audit, V0 survey, Gemini critique, scope-cut analysis) |
| Looking for old spec content | **archive/agent-repl-mvp-pre-2026-05-22.md** (preserved for historical reference; do not edit) |

## Layout

```text
docs/prds/agent-runtime/
├── README.md                        ← you are here
├── STATUS.md                        ← what's shipped, what's next, cross-track touchpoints
├── platform.md                      ← Platform track (WASM-Tauri containment)
├── v1.md                            ← MVP track v1 — single source of truth for the agent REPL
├── v2.md                            ← MVP track v2 — one-liners for what defers to v2
├── v3.md                            ← MVP track v3 — one-liners for what defers to v3
├── research/
│   ├── v0-state-2026-05-20.md
│   ├── v0-implementation-state-2026-05-22.md
│   ├── datahike-capabilities-2026-05-22.md
│   ├── gemini-graph-modeling-2026-05-22.md
│   ├── wasm-spike-2026-05-20.md
│   └── m2-findings-2026-05-21.md
└── archive/
    └── agent-repl-mvp-pre-2026-05-22.md   ← prior monolithic spec; superseded by v1.md

```

## Version dependency graph

```text
v1 (ship target — agent runs against deepseek, session-survives-restart, playback)
│
├─ ships: causality graph (agent/session/turn/message/eval),
│         tx-meta causality bundle via *tx-context*,
│         5 default sections (system/messages/current-ns/recent-evals/prompt),
│         composable :seon.ctx layout (composer dispatch),
│         inline :seon.turn/prompt-text for playback,
│         eval-replay resume, run-turn! + run-agentic-loop!
│
├─ defers (full designs in v2.md, NOT lost):
│    program graph (:seon.fn/:seon.schema/:seon.ns/:seon.test) + detect-and-tee
│    test entity + auto-run + agent helpers
│    warnings tile + 3 predicates (untested-fn, failing-test, slow-eval)
│    :seon.blob content-addressed archival (replaces inline prompt-text)
│    :malli/schema gate + accretive-schema rule
│    forget! / forget-ns! curation verbs
│    :seon.message/important? + mark-important!
│    :db/noHistory per-attr opt-outs
│    bootstrap.edn emission (D10)
│    context token budgeting + footer
│
├─ requires (Platform): Phase 3 in progress (WASM containment for production)
│  └─ blocks: D13 (dynvar survival across WASM boundary — v1 SPIKE)
│
└─ enables: v2

v2 (all the polish v1 deliberately deferred)
│
├─ requires: v1 shipped (clean break — new DB, no migration tooling)
├─ ships: every "defers" item above, plus
│         D8 reference-graph attrs (:seon.fn/refs, etc.) — requires analyzer walk
│         seon.render namespace + per-entity Malli-specificity dispatch
│         per-section HTML composer — section fns grow :seon.render/hiccup
│           in their return map alongside v1's :seon.render/text;
│           no new :seon.ctx slot; v1 HTML stays whole-tile per agent
│         related-ns-section (depends on D8)
│         Tufte profiling + perf-section
│         resume-via-program-graph (faster than v1's eval-replay)
│         D6 explicit remove-spec / remove-fn / remove-test verbs
│
└─ enables: v3

v3 (cross-agent + cross-pod)
│
├─ requires: v2 shipped (clean break ok)
├─ adds: cross-agent collaboration, multi-agent shared DB protocols,
│        D1 older-DB-on-newer-runtime upgrade,
│        D12 blob GC / retention,
│        cross-pod blob sharing (S3-backed seon.blob/*config*),
│        replay UI

```

**v1 → v2 → v3 transitions are clean breaks.** New schemas, fresh
database if needed. No backfill or migration tooling — that lets us
iterate fast in early versions.

## Decisions locked in for v1 (2026-05-22)

These come from the research files + Sean's directives during the v1
design pass. Listed here so future agents don't re-litigate them.

- **Minimum-viable v1.** Defer everything not on the critical path
  for "agent runs against deepseek, survives restart, plays back."
  Full designs for deferred features live in [v2.md](v2.md).
- **Three-tier storage rule** (full restoration in v2): DB datoms =
  renderer projections; blobs = persistent full content for
  playback/observer; globalThis stash = volatile per-session live
  values. V1 stores `:seon.turn/prompt-text` inline as a string
  string (compromises the principle by design); v2 swaps in the
  blob subsystem.
- **Component refs all the way down** for the causality chain
  (agent → session → turn → message/eval, plus agent → ctx).
- **Forward-ref components, not backref helpers** as the primary
  discovery mechanic. One nested pull on the agent walks everything.
- **Auto tx-meta causality bundle** via `seon.db/*tx-context*`
  dynvar. Every tx in an eval scope carries
  `{agent-id, session-id, turn-id, eval-id, origin}` without manual
  plumbing.
- **`:keep-history? true`** on the agent conn (precondition asserted
  at boot).
- **Eval-replay resume.** Walk successful `:seon.eval` entries in tx
  order, re-eval each. No program-graph entities in v1; the eval
  log IS the program. (V2 swaps in program-graph-based replay.)
- **5 default sections** (composable, not hardcoded): system,
  messages, current-ns, recent-evals, prompt. `:seon.ctx` entities
  carry layout; composer reads them and resolves symbols.
- **`current-ns-section` from eval-source filter.** No analyzer
  state walk, no program-graph entities. Filter `:seon.eval/source`
  by `:seon.eval/ns`, dedupe by symbol name (last-write-wins).
- **Drop `seon.agent/my-*` helper prefix.** Bare names
  (`seon.agent/messages`, `evals`, `current-turn`, `root-pull`,
  `reset-ctx!`, etc.). Current agent supplied by `seon.agent/*id*`
  dynvar bound by `run-turn!`.
- **No `seon.render` namespace in v1.** Rendering is functions in
  `seon.agent`. `seon.render` is reserved for v2's
  per-entity-specificity dispatch system.
- **No `seon.blob` namespace in v1.** Full subsystem ships in v2.
- **Namespace count: 6 in v1, 7 in v2.** `seon.db`, `seon.schema`,
  `seon.fs`, `seon.eval`, `seon.agent`, `seon.ai.deepseek` (v1) +
  `seon.blob` (v2). Earlier drafts split ID generation into
  `seon.id`; collapsed into `seon.agent` 2026-05-22 — a 5-line
  generator + a single schema does not earn its own namespace.
  Keep it focused.

## Cross-track coordination

See [STATUS.md](STATUS.md) §"Cross-track touchpoints" for what the
MVP track needs from the Platform track and vice versa, plus the
multi-pod concurrency rules.
