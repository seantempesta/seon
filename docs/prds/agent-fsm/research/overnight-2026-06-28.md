---
type: research
status: active
tags: [research, agent, index]
---

# Overnight autonomous curation — running report (2026-06-28 → morning)

**North star:** every loop, make the context a little better at generating
eval-batches that achieve real tasks. Measure first (the gym's hidden answers are
the only honest judge); each change lifts the whole battery or reverts — accretive
only, no churn, no overfit, no cheating; learn from the live system (drive a real
agent, reproduce in the REPL, read the source), fold back only the signal.

This file is the morning read: what landed (with live-proof), what's measured, what
needs your decision, and the Core-routed queue.

## Decisions awaiting you (read first)

1. **`:kind` Category B — scope.** The recurrence engine (surface A) is purged + gone.
   B = value-classification "kind" that classifies transient VALUES, not entities:
   `:seon.error/kind`, `:seon.warn/kind`, `:seon.render.value/kind`, the
   render/transcript event classes, `:seon.gym.predicate/kind`, `:seon.plan.entry/kind`.
   **Purge the WORD everywhere (rename → `class`/`shape`, a real multi-file refactor of
   load-bearing systems) vs stop at entity-kinds (this pass).** Per-item recs in
   `kind-purge-2026-06-28.md` §4. My read: B is consistency/taste, not correctness —
   the recurrence driver was only A. I did NOT touch B (no load-bearing refactor without you).
2. **`/data` URL param** `?kind=` → `?ns=` (part of the A purge) — flag in case any
   bookmark/automation depends on the old param.

## Landings (live-proven, committed)

| # | What | Proof | Commit |
|---|---|---|---|
| #64 | `:kind` recurrence engine purged — store-inventory/inventory/dashboard/`/data`/render-dispatch now speak attribute-presence, not "kind" | live read-back: `inventory-has-kind? false`, `dashboard-has-kinds? false`, `/data` renders + `has-kind? false`; suite 756/0 | `44bab907` |
| #65 | `bin/gym-scorecard` fitness function (SHA-keyed battery+axes line) | baseline `0ae13072`; immediately caught 2 search_test fails | `49676103` |
| #61 | `:live-tile` static teaching trimmed (~1,300→336 tok/turn, moved to skill); `ui-live-tiles` refreshed (my.tile/my.data, staleness killed); `data-oriented-clojure` deduped | measured trim; **scorecard battery HELD** (no pass/error regression) | `fe83c76c` |

| #68 | new gym scenario `plan-resume-across-restart` (`:planning`) — untested facet: planning continuity across interruption; structural predicates + judge-resumed-not-replanned | battery auto-discovered 19→20; FREE-measured | committed |
| #62 | transcript design pushed to **CACHE-AWARE + MEASURED** (config A −83%, 1,673 frozen→cached) — design done, ROUTED to Core | measured on real root (146 ev, 21,843→3,718) | `9eb13722` |

**Gate note (shared-tree, Core active overnight):** the suite is noisy from Core's UNCOMMITTED WIP across multiple lanes — `loop-test` (4, `loop.cljs`/`schedule.cljs`), `search_test` (2, active `search.cljs`), `index_core_test` (3). NONE are mine — my agents' own files are green and the **scorecard battery holds**, which is the honest gate (the suite-wide green is Core's to restore for their files). The **canvas verification drive is blocked** until Core commits `loop.cljs` clean (a reset would pull the broken agent-loop into the pod) — Monitor watching.

## In flight

- **`bin/gym-scorecard`** (the fitness function) — battery + axes → one SHA-keyed line,
  so every later change is judged accretive-or-revert against the whole battery, cheaply.

## Core landings — MEASURE their effect when the scorecard is up

- ✅ **`8f2f8c50` fabrication (C) — `eval.cljs` pending-Promise self-heal LANDED** (the
  `result/<id>` nil-on-first-ref trap). Expect: eval-error-rate ↓, honesty failures ↓.
  Consequence: the `clojurescript` skill's "pending Promise dropped on timeout" note is now
  STALE (the behavior is fixed) → update (Core content lane).
- 🔄 **`844ec448` #42 — namespaces now renders the PUBLIC API of an agent's `:require`d deps.**
  Measure the namespaces-block token delta + whether agents still discover the my.* toolkit
  (the render-prominence finding: don't let signature-trim hide the utility nses).
- 🔄 **`a24c2fbe` — store-inventory now carries an agent/run/turn/eval JOIN MAP** (built on the
  purged attr-groups shape). Changes the inventory block again → measure token cost vs usefulness.
- 🧪 **`53550b0e` perf(search) concise grouped grep (5.8x token cut)** = the likely cause of the 2
  search_test failures the scorecard caught (format change vs pinned assertions) — diagnosis agent on it.

## Core-routed queue (their lane; verified findings waiting)

- **#62 transcript 3-tier eviction (~14.3k tok/turn — the #1 lever)** + the fabrication cite-card.
- **#42 explicit-listing config** (skills load-all/explicit + `:namespaces` `:always`/`:signature`/current-ns).
- **#63 fabrication fixes** — `eval.cljs:2624` pending-Promise self-heal (REPL-verified) + `ctx.cljs:925` same-response guidance.
- system-text↔repl dedup; catalog essay→trigger-clause; `:seon.items/*`/`:seon.result/*` proper home; my.ui unqualified refer.

## Consolidation (no-kinds taught in ~5 places)

Authoritative home = `seon-skills/datahike/SKILL.md` (now carries the REPL-proven
enumeration example). `data-oriented-clojure` (U) / `data-modeling` (Core) /
datahike-primer / CLAUDE.md → one-liner + cross-link. U part folded into #61.
