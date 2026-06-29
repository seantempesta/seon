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

## In flight

- **`bin/gym-scorecard`** (the fitness function) — battery + axes → one SHA-keyed line,
  so every later change is judged accretive-or-revert against the whole battery, cheaply.

## Core-routed queue (their lane; verified findings waiting)

- **#62 transcript 3-tier eviction (~14.3k tok/turn — the #1 lever)** + the fabrication cite-card.
- **#42 explicit-listing config** (skills load-all/explicit + `:namespaces` `:always`/`:signature`/current-ns).
- **#63 fabrication fixes** — `eval.cljs:2624` pending-Promise self-heal (REPL-verified) + `ctx.cljs:925` same-response guidance.
- system-text↔repl dedup; catalog essay→trigger-clause; `:seon.items/*`/`:seon.result/*` proper home; my.ui unqualified refer.

## Consolidation (no-kinds taught in ~5 places)

Authoritative home = `seon-skills/datahike/SKILL.md` (now carries the REPL-proven
enumeration example). `data-oriented-clojure` (U) / `data-modeling` (Core) /
datahike-primer / CLAUDE.md → one-liner + cross-link. U part folded into #61.
