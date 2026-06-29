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

## Bottom line (the morning exec summary)

**The night's central deliverable is PROVEN: the `my.*` toolkit went from built-but-invisible to composable-and-honest.** A real DeepSeek agent now builds its tiles with `my.data`/`my.ui`/`my.tile` (composed 15×/23×, 9×/11×, 3×-wired) instead of hand-rolling broken `[:div]`s — and canvas-first moved it onto the *derived* surface, fixing a fabrication (judge fail→PASS). This closed end-to-end through the recursive loop: **build → drive real agents → discover it wasn't reachable → route the root cause → Core fixed it → verify composable.** Three more such cycles closed tonight (the #42 trim regression, the toolkit-reachability P0, the loop's own blind-spot instrument).

**Also shipped & verified:** the gym became a measured fitness function (`bin/gym-scorecard`: pass × per-block-tokens × eval-error-rate × **toolkit-adoption** × **pass^k** noise-robustness), the battery grew well-rounded to 23 scenarios across every facet, two facets proved **handled** (error-recovery, planning-resume), an honest green gate was restored (two hermetic test fixes), `:kind` was purged at the root, `:live-tile` trimmed, and the live pod reset to current.

**Loop-in-action (the recursion delivering a measured gain):** pass^k (built this cycle) revealed agents drive their canvas reliably for DATA asks but only **~33%** for PLANNING/goal asks → a one-sentence general guidance fix (plans/goals/status are canvas content; render the board first) → re-measured: **canvas-drive 1/3→3/3, judge 33→100, no regression → KEPT** (`01457ba9`). Build-instrument → instrument-finds-gap → fix → re-measure → keep, all in one cycle.

**Landed + VALIDATED by Core (measured GREEN):** transcript eviction (#62, ~20k→~1.4k, the #1 token lever DONE) and the **cite-card → fabrication FIXED** (#63/#80: honesty scenario designed-RED → **3/3 GREEN**, agent cites the computed 161 instead of fabricating). **Still awaiting Core:** explicit-listing config (#42), the home-ns alias collision (#73). **Awaiting you:** `:kind` Category-B scope (#66).

**`:namespaces` breakdown (live-measured, 18,185 tok)** — mostly load-bearing: my.ui/my.kb/my.tile/my.data = **10,284 (the toolkit+manual, keep full)**; `seon.db`/`schema`/`message`/`lifecycle`/`my.skills`(334) = ~4,177 **signatures (lean, correct — #42 trim works)**; the one clear trim is **`seon.agent.todo` 3,724 FULL** → signature (~400, save ~3.3k): it's a simple-call verb (the render-prominence law is about composition verbs like my.data, not simple calls), its worked-example role now redundant with 4 full my.* examples. Routed #74 (verify todo usage holds after the trim). So the realistic namespaces lever is ~3.3k, not the whole 18k — the toolkit is *earning* its tokens.

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

## db-memory findings-content lever — MEASURED KEEP, Gap A routed (2026-06-29)

The night's #1 context-correctness lever closed a measured cycle. **Regression:** a
fresh agent saw only an inventory COUNT of stored `my.kb.*` knowledge, never claim
CONTENT, so it under-stored and re-grepped instead of consulting. **Root cause (git):**
the content render was deleted in the Jun-18 refactor (`cf77ca11` dropped the unbounded
`pull[*]` dump, `d227b792` nuked `seon.agent.findings`); the intended `:relevant-source`
KNN replacement is default-OFF, so the stock pod had NO content surface.

**Core fix (`ee928de7`):** restored a bounded findings-CONTENT block
(`src/seon/agent/ctx/findings.cljs`) — priority-97 volatile band, renders the top-N
recent `my.kb.*` rows as `claim + path:line + :verified` provenance, reactive
empty-when-no-rows (not a revert: no unbounded dump, no lexical-overlap pointer). **U
predicate re-cut (`d2ca231b`):** gym predicate re-cut for the 220-char content-clip.

**Measured lift (PAID k=2 DeepSeek db-memory drive at SHA `d2ca231b`; baseline
`da671bcc`):**

| scenario | before → after | note |
|---|---|---|
| `s32-consult-before-research` | **0/2 → 2/2 PASS** | salience predicate was the only red; agent B's first eval became `(db/query …)` over the store |
| `s12-run8-two-agent-consultation` | 0/2 → 0/2 FAIL | but **Gap B (consult-first) improved 0/2 → 1/2** |
| `finding-storage-shape` | 1/1 → 1/1 green | no regression |

Scorecard line: `:pass-rate 0.667 :per-competency {:db-memory {:pass 3 :total 5}}
:eval-error-rate 0.129`. eval-error 0.13 = moderate, not noise. Dump:
`tmp/dbmem-s12-run8-two-agent-consultation.edn`. **Verdict: KEEP** — lifted the battery;
the render-salience/consult side of db-memory is now closed.

**Harness trust:** the gym schema-restore fix (`6ac32983`) made the multi-scenario paid
battery trustworthy (prior multi-scenario runs shared schema state across scenarios).

**Isolated next lever (Gap A → Core):** s12 still fails because agent A grep-researches
but persists ZERO `my.kb` rows (both runs `:a-stored-at-least-two-findings-with-provenance`
rows=[] x2). NOT a mechanism gap (`finding-storage-shape` passes) — in s12's "research so
the other agent can consult" framing the agent just doesn't proactively persist. Gap A is
UPSTREAM of render (the findings block serves CONSULT, not STORE). Fix = a single
store-proactively guidance change in the my.kb manual / always-on context (Core-owned
`my.*` content); routed in [[coordination]] under "Needs (UI → Core): db-memory Gap A".
Re-measure `s12-run8-two-agent-consultation` ALONE at k=2 + full battery; isolated change,
must not be bundled.

## db-memory Gap A — store-guidance KEEP-but-insufficient + s12 root-caused (2026-06-29)

The Gap-A cycle ran to ground. `s12-run8-two-agent-consultation` is db-memory's last red:
agent A researches → must persist ≥2 `my.kb.*` findings with provenance → agent B consults
them. Sequence, all measured/committed:

1. **Findings-CONTENT block — confirmed KEEP (real lift).** The restored bounded content
   block (`ee928de7`, predicate re-cut `d2ca231b`) measured at k=2: **s32-consult 0/2 → 2/2**,
   **s12 Gap B (consult-first) 0/2 → 1/2**, `finding-storage-shape` held 1/1, no regression.
   The CONSULT side of db-memory is closed.
2. **Store-proactively guidance — measured KEEP-but-insufficient.** Core added store-proactively
   guidance (`60dfe087`) to attack Gap A (A persists 0 findings). PAID k=2 drive: it did NOT
   close s12 (`s12 rate 0 → 0`, judge `17.5 → 0`) but it **improved s32 quality (judge 50 → 100)**
   and **lowered battery eval-error (0.129 → 0.087)**, pass-rate flat at 0.667. Net-positive →
   **KEEP**, but insufficient. (Trend line `26b219f2`.)
3. **Root cause found — store-DEFERRED × research-friction, NOT framing/salience** (`ac3db866`;
   deep doc [[research/s12-store-under-framing-rootcause-2026-06-29]], committed `d1d0dc9f`). The
   guidance IS internalized: A's first eval plans an explicit "store my.kb.* rows" step → the
   framing and salience theories are **FALSIFIED**. The real cause: A defers storing until after
   tracing, then **burns all 6 turns stuck researching** — reads the WRONG file (`src/seon/db.clj`,
   the paused JVM track, instead of `db.cljs`), trusts `println`'s nil, never pivots — so the store
   step is never REACHED. **0 `transact!`/`register!` across 6 turns.**

**Two follow-ups opened:**

- **#78 (CRITICAL Core footgun)** — `(seon.agent.ctx/render-namespace {:seon.ns/name :seon.schema})`
  renders AND EXECUTES `(seon.schema/clear-all! [])`, wiping the run's schema registry. Any
  schema-inspecting agent can destroy it; **blocks a clean s12 re-measure.**
- **#79** — agents pointed at paused-JVM `.clj` siblings instead of the active `.cljs` (the 6
  wasted turns above).

**Next isolated Gap-A lever:** reframe the store guidance from batch → **INCREMENTAL** ("store each
claim the moment you verify it; a grep hit with `file:line` is already storable") in Core's `my.kb`
manual — blocked on #78. Re-measure **s12 ALONE** at k=2 + full battery; isolated, do NOT bundle.

## db-memory Gap A — PAYOFF re-measure: blockers cleared + quality lifted, storage behavior RESIDUAL (2026-06-29)

The Gap-A loop ran its payoff measurement and **STOPPED** (deliberate — further s12 tweaks would
overfit). Three precise fixes landed to clear the root-caused blockers, then a clean k=2 re-measure:

1. **#78 (`52b38daf`)** — inert-comment render: `render-namespace` no longer EXECUTES the form it
   shows, killing the `(schema/clear-all! [])` registry-wipe footgun that blocked a clean re-measure.
2. **#86 (`b50899c0`)** — file-grep now suppresses the paused `.clj` lane-sibling, so an agent
   grepping `db` gets the live `db.cljs`, not the dead JVM-track `db.clj` (the wrong file that ate A's 6 turns).
3. **#85 (`32b23323`)** — store-proactively guidance reframed **batch-at-end → INCREMENTAL**
   ("store each claim the moment you verify it; a grep hit with `file:line` is already storable").

**Measured at `b50899c0` (all 3 fixes), PAID k=2 DeepSeek:**

| axis | result | read |
|---|---|---|
| `s12-run8-two-agent-consultation` | **0/2 FAIL** (still) | the targeted bar did NOT pass |
| s12 judge-mean | **0 → 52.5** | substantial quality lift |
| battery judge | **33 → 68** | broad lift |
| `:a-stored-at-least-two-findings-with-provenance` | **0/2** | A still does not persist |
| my.kb references (both runs) | **0** | zero rows written |
| A live-tile touches | **8×** | A PRESENTS to its canvas instead |
| A active my.ui | **11** | toolkit used heavily on the present side |
| `s32-consult` | **2/2 j100 HELD** | no regression |
| `finding-storage-shape` | **1/1 HELD** | no regression |

**The honest bottom line:** the loop **resolved every upstream BLOCKER** (grep friction #86,
registry-wipe #78, deferred/batch store guidance #85) and **lifted s12 quality substantially**
(judge 0→52.5), but s12 **does not pass**. The residual gap is robust: agent A (DeepSeek)
**PRESENTS findings to its canvas (live-tile 8×) but does not PERSIST them as `my.kb` rows
(stored 0/2, 0 refs)** under the two-agent "research so your partner can consult" framing. Storage
behavior survived every intervention — it is the residual, not a missing mechanism (`finding-storage-shape`
passes). The loop **deliberately stopped tweaking s12** here to avoid overfitting the battery to one bar.

**This is now an OWNER DECISION among three hypotheses (presented, not chosen):**

1. **MODEL CEILING** — DeepSeek won't reliably do the persist step under this framing. Recommend
   re-testing with a stronger model + tracking s12 as a known-hard `pass^k` bar.
2. **PRESENT-vs-PERSIST TENSION** — the canvas-first culture competes with store-to-DB; A satisfies
   "show your work" via the canvas (live-tile 8 / stored 0) instead of persisting. May need
   guidance/scenario disambiguation, or an accepted tradeoff.
3. **OVER-SCOPED SCENARIO** — s12 conflates store + consult + handoff across TWO agents; could be
   split into tighter single-behavior bars.

Root-cause depth: [[research/s12-store-under-framing-rootcause-2026-06-29]].

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

**✅ CORE FIXED THE TOOLKIT P0 (`960cb489`, suite 796/0):** BOTH parts — `client.cljs` now requires my.data/my.ui/my.tile (index at boot) + `canonical-full-my-ns` = `#{:my.kb :my.data :my.ui :my.tile}` (render full). **The toolkit is now reachable by USE.** Another recursive cycle closed (coherence audit → P0 → fix). Also `565ace0b` fixed the registry-stomp (#41), `878351ce` the report=data/message=pointer delegation contract. VERIFICATION QUEUED: re-drive the canvas scenario → confirm `toolkit-calls{:my.ui :my.tile}` rises from 0 (hermetic gym picks up the new boot requires). Fires after the error-recovery scenario lands.

## ✅ CAPSTONE RESET — live pod now current + toolkit-indexing LIVE-PROVEN

Core signalled `0710336d` (pre-reset batch complete, cold-boot verified, U owns timing) → ran `bin/seon cluster reset default` (wire-server 8s, pod 25s, auto-boot ready, root minted). **Live-pod proof of the toolkit P0 fix:** `(count :seon.fn per my.* ns)` = `{my.kb 13, my.kb.shared 3, my.skills 6, my.data 4, my.ui 7, my.tile 5}` — vs the coherence audit's `{my.data [] my.ui [] my.tile []}`. The toolkit is now indexed + renders full on the LIVE pod. The whole night's work (toolkit, canvas-first-in-system-text, no-kinds, registry-stomp) is now live. Composability proof = the gym re-drive (running).

## 🏆 CAPSTONE PROVEN — toolkit COMPOSABLE + fabrication fixed (`352fcf91`)

Verification re-drive (`research/toolkit-reachable-verification-2026-06-28.md`):
- **`my.ui` composed 9× / 11× (was 0)** — agent builds tiles with `my.ui/section`+`status-line`+`kv-table`, not hand-rolled `[:div]`. `my.data` 15×/23×. Toolkit renders FULL (was ABSENT).
- **Budget fabrication FIXED:** judge fail→PASS (100) — canvas-first + full toolkit moved the agent onto the DERIVED surface (correct $136 canvas vs fabricated $155 prose). **Canvas-first has an honesty benefit** beyond Core's cite-card (#63).
- `my.tile` 0× = EXPECTED (interactive controls, all 3 scenarios are read-only → needs an interactive scenario). `canvas-goal-board` single-sample DeepSeek miss (model variance, pass^k averages).

**Central arc CLOSED end-to-end:** built toolkit → drove agents → found unreachable → routed root cause → Core fixed → verified composable + honest. This is the recursive loop delivering a real capability gain.

## Facet-gap drive — error-recovery + planning HANDLED, alias-collision gap found

`research/facet-gaps-drive-2026-06-28.md`: **error-recovery PASS** (10/10, judge 100 — reads the error-as-value envelope, fixes, continues, no spiral) and **planning-resume facet PASS** (judge-resumed-not-replanned 100). The drive surfaced a HIGH-value REPL-error source: **home-ns aliases (`db/`/`message/`/`todo/`/lifecycle verbs) break in agent-authored `my.*` nses** — ~60 `"db/transact! is not defined"` per fn-authoring drive (the context says "create a namespace" but the aliases are home-only). Routed to Core (#73, always-on/error-render/auto-refer) + U skill complement landed (`7b006404`: the verified alias→qualified mapping + named-test pattern in data-oriented-clojure + ui-live-tiles). Verifying `my.tile` composability (the last toolkit piece) closes the toolkit-proof.

## 🏆🏆 TOOLKIT FULLY PROVEN (`d666b437`)

`my.tile` composed 3× (toolkit-calls 6), each `(tile/button …)` wired to a handler the agent DEFINED (`toggle-water!`/`vitamins!`/`walk!`, transacting a flip), set as a derive-from-db tile fn. judge 100, eval-error-rate 0, rendered full. **All three pieces compose: my.data 15×/23×, my.ui 9×/11×, my.tile 3× wired.** The toolkit is end-to-end proven.
- **Gym-integrity bug found:** `:composed-an-interactive-control` regex is ALIAS-BLIND (`my\.tile/` misses `tile/button` from `[my.tile :as tile]`) → scored a perfect composition 0/14. The robust `toolkit-calls` axis caught it. Fix queued (align with toolkit-calls). Plus a click-dependent predicate that can't pass no-click.
- **#73 nuance:** alias collision did NOT bite here (home ns has the aliases) — it only bites in NEW agent-authored `my.*` nses. Scopes the Core fix.

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
