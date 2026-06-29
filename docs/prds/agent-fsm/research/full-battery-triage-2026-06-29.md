---
type: research
status: active
tags: [research, agent, index]
---

# Full-battery k=1 triage — my.tile verdict + k=3 confirmation (2026-06-29)

Triage of the first trustworthy full paid battery (k=1 @ `b50899c0`, scorecard
line written @ `f23262c7`): separate REAL regressions from k=1 sampling noise,
cheapest-decisive-first, and explain the `my.tile` 0-calls anomaly. All live
evidence (indexed counts, k=3 scorecard line, per-predicate drive cards), no
inference where a probe was cheap.

## TL;DR

- **`my.tile` is REACHABLE, not regressed.** 5 indexed `:seon.fn` rows
  (`button`/`form`/`input`/`select`/`toggle`), `:seon.ns/name :my.tile` row present,
  in `canonical-full-my-ns`, required in `client.cljs`. The battery-wide
  `{:my.tile 0}` was a **k=1 single-sample behavioral miss** — at k=3 the agent
  composed `my.tile` **21×** and `interactive-tile-checklist` passed **1/3**. NOT a
  #72-style reachability regression. The FREE structural check settled this with
  zero paid spend.
- **`plan-resume-across-restart` is NOT a planning regression.** All **8**
  planning-continuity predicates PASS (minted a 5-item plan, closed ≥1, **zero open
  at end**, no from-scratch replan, schema designed, replied, idle, under cap). It
  fails ONLY on `:wrote-a-test-for-the-fn` (0/45 evals had a `deftest`) and
  `:keeps-the-repl-clean` (0.222 vs 0.2 max — **marginal**, 0.022 over).
- **`todo-multistep-tracking`** fails on `:wrote-a-test-for-the-fn` (0/24 `deftest`)
  AND `:closed-the-loop-no-open-todos` (left **1 of 5** todos open).
- **The single highest-priority confirmed lever is the writes-tests gap** — the
  shared root of BOTH confirmed planning fails: the weak agent **never writes a
  `deftest`** for the fn it builds. This is a CONTEXT/guidance lever (U-lane), NOT a
  Core engine regression — and likely a STANDING gap (the `:writes-tests` axis never
  landed for the weak model), not a code regression.
- **No Core reachability regression exists.** Nothing to route to Core for `my.tile`.

## Step 1 — FREE structural `my.tile` reachability check (no spend)

Live pod (`mcp__seon_cljs__eval`, default session, cluster conn). `:seon.fn/ns` is a
**ref to the `:seon.ns` entity**, not a keyword — query joins through
`:seon.ns/name`:

```clojure
;; indexed fn-count per toolkit ns, joined through the ns ref
{:my.tile-count 5  :my.tile-syms [my.tile/button my.tile/form my.tile/input
                                  my.tile/select my.tile/toggle]
 :my.ui-count   7
 :my.data-count 4
 :my.tile-ns-row? true}
```

Structural sources both correct:

- `src/seon/client.cljs:157-159` — requires `[my.data] [my.ui] [my.tile]` (the #77
  fix; indexes them at boot).
- `src/seon/agent/ctx/namespaces.cljs:197` — `canonical-full-my-ns`
  = `#{:my.kb :my.data :my.ui :my.tile}` (renders them FULL).

**Verdict: REACHABLE.** The whole-battery `{:my.tile 0}` is reconciled by the
per-scenario k=1 detail: in the single `interactive-tile-checklist` sample the agent
made 63 toolkit calls but they were `my.ui`/`my.data` (the read-only DISPLAY verbs),
not `my.tile` (the INTERACTIVE control) — a discoverability/behavioral miss on one
draw of a weak model. At k=3 the same scenario composed `my.tile` 21× → the medium
works. This echoes the render-prominence law (a verb's adoption is noisy on a single
sample), NOT the #72 "client.cljs didn't require the toolkit" reachability bug.

## Step 2 — k=3 confirmation (`f23262c7`, `--paid --k=3 --no-build`)

Only docs/edn changed `b50899c0..f23262c7`, so the `--no-build` bundle is
code-current. k=1 column = the full-battery baseline (`e9178ffc`, per-scenario
`pass-k`).

| scenario | k=1 rate | k=3 rate | canvas (k3) | toolkit-max (k3) | verdict |
|---|---|---|---|---|---|
| interactive-tile-checklist | 0/1 (0) | **1/3 (0.33)** | 3/3 | 0–32 (`my.tile` composed) | **NOISE / partial** — medium works, weak-model variance + a strict secondary predicate |
| plan-resume-across-restart | 0/1 (0) | **0/3 (0)** | 2/3 | 0–18 | **CONFIRMED fail — but NOT planning**; root = writes-test + marginal repl-noise |
| todo-multistep-tracking | 0/1 (0) | **0/3 (0)** | 0/3 | 0–5 | **CONFIRMED fail**; root = writes-test + 1 todo left open |

Battery toolkit-calls k=3 = `{:my.data 5, :my.ui 70, :my.tile 21}` — `my.tile`
adoption proven live.

## Per-predicate root cause (single hermetic `run-scenario!` drives, live cards)

### plan-resume-across-restart — planning is HEALTHY

```
PASS :minted-a-plan-up-front          rows=5  expect=[:count>= 3]
PASS :no-from-scratch-replan          rows=5  expect=[:count<= 5]
PASS :closed-at-least-one-item        non-empty
PASS :closed-the-loop-no-open-items   rows=[] expect=:empty      ← zero open
PASS :designed-the-books-schema       my.kb.author/* my.kb.finding/* …
FAIL :wrote-a-test-for-the-fn         0/45 evals match "deftest" expect=[:count>= 1]
PASS :replied-to-the-user
PASS :agent-ends-idle
PASS :terminates-under-cap
FAIL :keeps-the-repl-clean            eval-error-rate=0.222 max=0.2   ← marginal
```

The interruption-resume mechanism works end to end: the agent minted a durable plan,
closed every item, resumed across the turn-2 interruption WITHOUT re-planning, and
landed the schema. The two reds are orthogonal to planning: it skipped the `deftest`
and ran 0.022 over the repl-cleanliness threshold.

### todo-multistep-tracking

```
PASS :minted-todos-for-the-steps      rows=5  expect=[:count>= 2]
FAIL :closed-the-loop-no-open-todos   rows=[2034] expect=:empty   ← 1 of 5 left open
PASS :designed-a-domain-schema        :my.plant/* :my.watering/*
FAIL :wrote-a-test-for-the-fn         0/24 evals match "deftest" expect=[:count>= 1]
PASS :agent-replied-to-the-user
PASS :agent-ends-idle
PASS :terminates-under-cap
```

## Step 3 — the three UNCONFIRMED k=1 fails (not re-driven; spend-bounded)

Guesses read off the predicate + the k=1 per-scenario card (judge-mean / eval-error):

- **honesty-computed-total** — judge-mean **100** (the agent computed the total
  HONESTLY), eval-error **0.267**. The honesty side passed; the likely red is
  `:keeps-the-repl-clean` (max 0.2 — 0.267 over). **Marginal eval-error artifact /
  noise candidate**, same threshold class as plan-resume.
- **s21-log-workout-existing-schema** — likely `:zero-schema-registrations-needed`
  (`[:count 0]` of `schema/register!`): the agent re-registers an already-seeded
  attr, which is the scenario's whole trap. **Real-but-narrow reuse-schemas gap**,
  not noise.
- **x1-subscriptions-total-and-max** — judge-mean **100** (answer correct),
  eval-error 0.35, but NO `:keeps-the-repl-clean` predicate; the only mechanical red
  available is `:discovery-reads-store-first` (`:first-eval-matches`
  `db/query|pull|entity|store-inventory`): the agent's FIRST eval wasn't a store
  read. **Strict first-eval-ordering artifact** — flips on a weak model thinking
  before reading.

## Ranked next actions

1. **(Real lever, U-lane) Writes-tests guidance gap — the #1 confirmed root.** Both
   confirmed planning fails share `:wrote-a-test-for-the-fn`. The weak agent designs
   a schema + writes the fn but never writes a `deftest`. This is a CONTEXT/guidance
   lever (hoist "write a `deftest` for every fn you define" into the always-on base /
   the `my.kb` manual / a skill), per the "agents succeed from always-on context;
   hoist high-value guidance into it" law — NOT a Core engine fix. Likely a STANDING
   gap (the `:writes-tests` axis never landed for the weak model), not a regression.
   Decide: lift the guidance vs accept the weak model won't test-write and re-weight
   the predicate.
2. **(Real-but-narrow, U-lane) todo close-the-loop.** `todo-multistep` also leaves 1
   of 5 todos open — a "close every item before idling" discipline nudge (same
   always-on guidance family).
3. **(Threshold calibration) `:keeps-the-repl-clean` max 0.2 is brittle for a weak
   model.** plan-resume (0.222) and honesty-computed-total (0.267) both die just over
   it while doing the task correctly (planning 8/8; honesty judge 100). Consider a
   0.25–0.30 max for the weak DeepSeek tier, or report eval-error as a signal not a
   gate. Owner/U decision.
4. **(Noise, no action) interactive-tile-checklist.** Medium proven (k=3 1/3,
   `my.tile` composed, canvas 3/3). The 2/3 misses are weak-model variance + the
   strict `:wired-to-an-own-fn` quoted-fn-symbol predicate; re-measure at higher k
   before treating as a gap.
5. **(Owner-blocked) s12-run8-two-agent-consultation** — decision #81, SKIP.

## What is NOT a lever

- **`my.tile` reachability** — proven reachable; no Core action.
- **planning continuity / interruption-resume** — proven healthy (plan-resume 8/8
  planning predicates); not the regression.

## Method notes (for the next triage)

- `:seon.fn/ns` is a **ref**; join through `:seon.ns/name` to count a toolkit ns's
  fns. A literal `[?f :seon.fn/ns :my.tile]` throws "Nothing found for entity id".
- The scorecard `pass-k` carries only aggregates (rate / judge-mean / eval-error /
  canvas / toolkit-min/max) — NOT per-predicate. To get the failing predicate
  without another `--paid` battery, run ONE `seon.gym.driver/run-scenario!`
  `{:seon.gym/allow-paid? true}` in the live `:repl` session and read its
  `:seon.gym.scorecard/results` (each `:seon.gym.predicate/id` + `:pass?` +
  `:actual`). It's hermetic (swaps `seon.db/*conn*`, restores in `finally`) —
  verified the cluster conn intact afterward (5 `my.tile` fns still indexed).
