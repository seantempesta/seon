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

## Tooling lane — the ordered path (ratified with owner 2026-07-02)

**Interleave rule (owner call): one stability unit lands per feature unit** —
the P-stability queue burns down continuously without stalling the flywheel.
Feature order (turn-capture pulled forward — it is the eval lane's attribution
substrate):

1. **Required-key resolution** — ✅ Phase 1 landed (`a6362630`; suite green
   937/4310 after `e0f63c05`). Remaining: register `:seon.render/at`; resolve
   the `my.plan` **skip-syms** blocker.
2. **Current-ns render-fn auto-run** — query the program graph for the current
   ns's render-typed fns, run through the same wrapper → block/tile twins,
   positioned after the stable code. (design: [[research/explicit-deps-injection-2026-07-02]])
3. **Observability turn-capture** (pulled forward from "still open") —
   `:seon.agent.turn/rendered-as-of` + prompt/reply blobs + `inspect/turn` /
   `turn-diff`; gives the eval lane rendered-context evidence per row.
4. **`my.*` as namespace-scribed entities** — the agent entity refs a `my.plan`
   entity whose schema is scribed in the `my.plan` ns; scope declared by
   signature. `my.plan` the worked example.
5. **Canvas = last-updated tile** (derived default, pin to override) — the code
   for the ui.md decision.
6. **Queued tool defects** — fresh-world `my.kb` empty render; turn-6 recall
   visibility; SCI-bounding fallback on `my.plan.internal/plan-block`
   (`docs/seon/orchestrator/issues/sci-bounding-fallback-plan-block.md` —
   unresolvable `db/*conn*` alias drops the tile onto the UNBOUNDED path).

Stability queue (interleaved, one per feature unit above; owner-agreed
2026-07-02 — each fix REUSES an existing mechanism, no new ones):

1. **Provenance-at-the-boundary** — `seon.db/transact!` stamps
   `:seon.db/origin` from the ambient scope (same boundary-resolution concept
   as the unit-1 injectable registry); callers never pass it; DELETE
   `warn-on-seed-origin-forge!` (the forgery becomes impossible, killing the
   ×3 boot warning).
2. **Pub-socket feed migration** → **transact-timeout semantics**
   (`docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md`).
3. **SCI alias root-fix + fallback DELETION** — store the analyzer's requires
   on `:seon.ns/source` so SCI resolves aliases (code-as-data reuse); a fn
   that still can't run bounded renders a `:seon/error` tile
   (never-crash-always-surface) and the unbounded compiled fallback path is
   REMOVED. Absorbs `sci-bounding-fallback-plan-block.md` and part of the
   `*conn*` root.
4. **`*conn*` single-dynamic-root / fiber-local** (remainder; turn-6 recall).
5. **skip-syms → zero** (background thread, per unit): every remaining entry
   gets its root cause fixed or a STRUCTURAL rule; the name list dies.

Small fixes (no unit needed): dev hook resolves `logs/`/`tmp/` from the repo
root, not the edited file's tree (submodule litter). ✅ Skills corpora SPLIT
(owner ruling, `68d73395`): `seon-skills/` (manifest-owned) = the seon agents'
in-runtime corpus; `.claude/skills/` = Claude Code's dev corpus — real copies,
no symlinks, free to diverge by audience perspective; convergence returns when
seon agents write seon code. Follow-up (content, eval-lane-shared): rewrite
agent skills from the agent's in-runtime perspective where they still read as
repo-dev docs.

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
