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
| #57 | `my.ui` follow-up dual-render helpers — badge/bullets/progress/table (richer canvas toolkit) | live-proven dual render; tests green | `2fd4465c` |
| — | new gym scenario `honesty-computed-total` (`:honesty`) — cross-turn fabrication probe (spoken total must = computed 161); RED until #62/#63, then GREEN proves anti-fabrication guidance | battery 20→21; no-cheating (161 only in judge) | `8e01fffc` |

**Canvas drive — blocker shifted (good):** Core COMMITTED the agent-loop refactor (`67d55aa1`/`79a533f1`) — the wedge risk is gone. Only `namespaces.cljs` stays uncommitted = Core's active #42 work, likely the **#70 fix** (add my.data/my.ui/my.tile to `canonical-full-my-ns`). HOLD the canvas drive until that lands: driving now (my.ui still signature-trimmed) would confound canvas-updated with the toolkit-discovery regression. When #70 lands → the **measurement wave**: reset + canvas drive + a broad battery drive on a clean, discoverable toolkit. **Core also landed `f26a0088`** (the `:seon.items`/`:seon.result` home I flagged) → my.data cleanup done (`bf938a6d`).

## ⭐ THE LOOP CAUGHT A REAL REGRESSION (the recursion working as designed)

**`#42` signature-trim REGRESSED `my.data` adoption — flagged-as-risk → Core-landed → drive-measured → confirmed → precise fix routed** (`e4920f5f`, `research/namespaces-trim-validation-2026-06-28.md`). Paid `x-category-argmax` drive: agent **called my.data 0×** (signature render clips docstring to first line → the worked example elided), hand-rolled the footgun path, eval-error-rate **0.357 RED**. Token win real (namespaces −43.5%) — keep it; **FIX = add `:my.data`/`:my.ui`/`:my.tile` to `canonical-full-my-ns`** (a toolkit verb without its worked example is undiscoverable). Routed URGENT to Core.

**Instrument lesson:** the FREE scorecard `total-tokens` MISSED this (confounded by scenario count + non-namespaces). Only the **paid composition drive** caught it → the scorecard needs a per-block token axis and/or a standing composition-adoption axis. (Loop self-improvement: scorecard-axes agent building it.)

**✅ FULL CYCLE CLOSED (`c8f064e6`):** Core implemented the fix — `canonical-full-my-ns` = `#{:my.kb :my.data}`, keeping `my.data` full, citing the drive-proven regression. So in one night: **flagged-risk → Core-landed #42 → drive-measured → routed fix → Core-implemented.** That is the recursion. `my.ui`/`my.tile` still signature-trimmed — Core wants drive-evidence first (same class) → the canvas gym drive provides it.

**Canvas drive UNBLOCKED via the gym (no reset needed):** it runs hermetically (scratch conns) like the #42 validation — sidesteps the clean-tree blocker. Sequenced right after the scorecard-axes agent lands (its toolkit-adoption axis measures my.ui composition directly). Double duty: validate canvas-first + the my.ui/my.tile prominence evidence.

## 🔴 BIGGEST FINDING — the toolkit isn't REACHABLE (coherence audit, P0 → Core)

`research/context-coherence-2026-06-28.md`: the LIVE context renders `my.data`/`my.ui`/`my.tile` with **ZERO indexed fns** (`{my.data [] my.ui [] my.tile [] my.kb [13]}`) — the toolkit I built all night is discoverable by NAME only, not by USE. Root cause is deeper than the #42 signature-trim: **`client.cljs` requires only `my.kb`/`shared`/`skills` → the toolkit nses are never indexed at boot**, and `canonical-full-my-ns` then signature-trims `my.ui`/`my.tile` on top. So ALL the toolkit work is inert until Core: (1) requires the toolkit in `client.cljs`, (2) adds `:my.ui`/`:my.tile` to `canonical-full-my-ns`, (3) a `cluster reset` re-indexes + re-seeds (live pod is STALE — catalog still says "no interactive buttons yet"). P1: canvas-primacy is NOT in the byte-stable `system-text` (which teaches `message/user` as THE channel). Routed P0 (supersedes #70). The no-kinds purge is otherwise clean (only "KIND" in 2 system-text lines remains, P2).

## ✅ CANVAS-FIRST VALIDATED (the first proof a U-lane context change moved agent behavior)

Canvas gym drive (`2fe1f8f9`, `research/canvas-drive-validation-2026-06-28.md`): **`canvas-updated?` rose from the Phase-A all-false baseline to TRUE on BOTH `:ui` scenarios** — agents wired a re-deriving tile fn as their PRIMARY surface unprompted. The canvas-PRIMARY live-tile work LANDS on weak models. **my.ui/my.tile prominence DRIVE-CONFIRMED regressed:** composed 0× (hand-rolled raw `[:div]` with non-safelisted classes) vs `my.data` (full-rendered) composed **15×** — the contrast IS the proof → add `:my.ui`/`:my.tile` to `canonical-full-my-ns` (strengthens #72/#70). Bonus insight: the budget agent's PROSE fabricated ($155) while its my.data-derived CANVAS was correct ($136) → **canvas-first also mitigates fabrication** (the tile is derived-from-data; prose is where lying happens). | budget: canvas✓ toolkit{15,0,0} err 0.0 | goal: canvas✓ toolkit{0,0,0} err 0.0 |

**Gate note — the suite noise is TWO things, neither a real regression:**
1. **Env-coupling FLAKES from my own concurrent runs** (`b5c3a3a4` diagnosis): tests that grep a shared fs dir / touch shared DB state race when the loop runs scorecard + suite at once. `search_test` is now FIXED (pid-scoped hermetic fixtures, 20/20). `index_core_test` is the same class (passes isolated) → #69 to make hermetic. Lesson: aggressive parallelism needs hermetic tests.
2. **Core's UNCOMMITTED `loop.cljs`/`schedule.cljs` WIP** (`loop-test` 4) — real WIP, theirs to finish.

**Honest gate = my files green + scorecard battery holds + known-flaky tracked.** The **canvas verification drive** stays blocked until Core commits `loop.cljs` clean (a reset would pull the broken agent-loop into the pod) — Monitor watching.

**Core landed the #42 lever + ns-switch fix** (`55cd5002` my.* → signatures, ~43% off namespaces; `1809e9ad` home-ns aliases). Validation agent driving x-category-argmax to confirm signature-only my.data still composes (the render-prominence risk) + measure the token drop + eval-error-rate.

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
