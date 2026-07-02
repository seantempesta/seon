---
type: prd
status: active
tags: [prd, agent]
---

# agent-ctx roadmap — we are here → the target

The single **we-are-here** for this chunk. The target (idealized system) is
`docs/seon/architecture/` — present tense; THIS doc holds what's built, the
gap, and the ordered path, for BOTH lanes. Shared state + issues: [[CLAUDE]].
Cross-lane channel: [[coordination]].

## Where we are (2026-07-02)

Branching off agent-fsm's shipped capstone (see
`docs/prds/agent-fsm/roadmap.md` §"Shipped 2026-07-02"). Built and merging:
tool parity (shell/python/web/file-edit/grep/blob), `my.plan` (rename +
planning redesign), the race-timeout wedge fix, the fn-spec heal, the
consolidated architecture docs. The eval harness (`src-inspect-ai/`) is built +
pytest-green but **not yet running** a standing suite. The context-composition
work (required-key resolution) is designed + Phase-1-built (held as a patch).

## Tooling lane — the ordered path

1. **Required-key resolution** (Phase 1 built, patch in
   `scratchpad/agent-scope-carryover/`) — a fn declares `:seon.db/db`,
   `:seon.agent/id`, `:seon.render/at` as optional request keys; the eval
   boundary resolves absent ones from context. Apply the patch; register
   `:seon.render/at`; resolve the `my.plan` **skip-syms** blocker.
2. **Current-ns render-fn auto-run** — query the program graph for the current
   ns's render-typed fns, run through the same wrapper → block/tile twins,
   positioned after the stable code. (design: [[research/explicit-deps-injection-2026-07-02]])
3. **`my.*` as namespace-scribed entities** — the agent entity refs a `my.plan`
   entity whose schema is scribed in the `my.plan` ns; scope declared by
   signature. `my.plan` the worked example.
4. **Canvas = last-updated tile** (derived default, pin to override) — the code
   for the ui.md decision.
5. **Queued tool defects** — fresh-world `my.kb` empty render; turn-6 recall
   visibility (+ the `*conn*` single-root / fiber-local lever).
6. **Post-merge de-flake** — the pub-socket feed migration + the transact-timeout
   ambiguity (`docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md`).

## Eval lane — the ordered path

1. **Calibration run** — pod `/solve` concurrency ceiling, per-row latency
   medians → timeouts.
2. **Dataset freeze** — seeded three-way splits (dev/milestone/test),
   `datasets.lock`, canary GUIDs + CI grep.
3. **Tool-row generators** — shell / web-fetch (local fixtures) / file-edit;
   goal-stated, never API-coached.
4. **Planning bench re-ground** on the redesigned `my.plan` (deps/pace/expect —
   the headline capability; plan-survives-restart stays bespoke, no public bench).
5. **First dev pass** → `evals/scorecard.jsonl` + the `pass^k` regression alarm;
   then cadence.
6. **Ongoing** — per-row rendered-context audits (every trim is an A/B),
   baseline each tool the tooling lane lands, milestone runs at merges, the mvm
   case-2 sandbox tier later.

Spec: [[eval-design]] · plan: [[eval-lane-plan]] · readiness:
[[research/tool-surface-survey-2026-07-02]].

## Still open (from agent-fsm, may land here)

Root-world-at-`/`, the spawn capability gate + roles, the observability
turn-capture build (`:seon.agent.turn/rendered-as-of` + prompt/reply blobs +
`inspect/turn`), `:seon.agent/purpose` → `:my.agent/purpose`.
