---
type: research
status: active
tags: [research, agent, gym]
---

# my.data composition gym — live DeepSeek drive + ergonomics verdict

> Wave 2 of the `my.data` build: a `:paid` composition scenario
> (`x-category-argmax`) + real DeepSeek drives that stress the
> `rows → group-sum → max-by` pipeline, observing what the agent actually
> reaches for. The question: is `my.data` a GOOD API, or does the agent fight it?

## TL;DR

- **`my.data` is a GOOD API. No change made.** When `my.data` rendered FULL in
  the `:namespaces` block, a cold DeepSeek agent DISCOVERED it and composed
  `rows → (merge … group-key/key) → group-sum → (merge … :my.data/total) →
  max-by` **flawlessly on the first real attempt** — the merge-arrow, the
  `group-key`(in)/`group`(out) split, and the envelope-threading all worked with
  zero confusion. All three pre-registered ergonomics doubts resolve in the
  API's favor (see the per-doubt table). Changing it for its own sake would be a
  dumbass move; left untouched.
- **Scenario `x-category-argmax` SHIPPED** (`test/seon/gym/scenarios/`), a paid
  `:db-memory` composition: a cold agent must group a seeded week of expenses by
  category, sum each, and argmax over the groups → dining @ $106. A flat sum
  gives 219, a flat max gives the $73 groceries row; only group-then-argmax
  lands it. The judged answer (dining/106) lives ONLY on the `:llm-judge` axis;
  mechanical legs are structural/behavioral; an `:eval-error-rate ≤ 0.2` leg
  scores REPL cleanliness.
- **First draft had a double-count bug — FIXED.** The audit's A→B framing
  (pre-seed fixtures AND have agent A re-log the same week) mints DUPLICATE
  entities (`:my.expense/merchant` is not an identity attr) → 10 rows, dining
  doubled to **$212**. The drive caught it: the agent's math was correct and it
  was honest ("six dining transactions, 28+52+26+28+52+26 = 212"). Fixed by
  making it a **single agent over a seeded, unambiguous dataset** (the task's own
  wording — "a seeded expense dataset"). Re-drives: deterministic, exactly 5
  rows, **judge score 100 (dining @ $106)**.
- **Cross-cutting findings flagged for Core / the context lane** (not my.data
  bugs): a `result/<id>` re-reference nil/partial-on-first artifact (reproduced
  with my.data AND plain `group-by`); my.data ADOPTION tracks its render
  prominence; a recurring honesty fabrication; and a gym-harness faithfulness
  caveat. Details below.

## The scenario

`test/seon/gym/scenarios/x-category-argmax.edn` — single agent, seeded fixtures:

| merchant | amount | category |
|---|---|---|
| Thai Place | 28 | :dining |
| Sushi Bar | 52 | :dining |
| Cafe Luna | 26 | :dining |
| Trader Joe's | 73 | :groceries |
| Shell | 40 | :transport |

Per-category: **dining 106**, groceries 73, transport 40. Traps: flat sum = 219;
flat argmax row = Trader Joe's @73 (wrong category). Only `rows → group-sum →
max-by` (or the exact hand-rolled equivalent) yields dining@106. One turn:
*"Look at the expenses on file for me — which kind of spending was my biggest
this past week, and how much did it come to?"* Wired into `seon.gym.paid-test`
under the `:xcat` gate (`bin/gym --paid=xcat`).

## The drives (live DeepSeek, the pod's default adapter)

Three paid runs. The faithful environment is the FULL `bin/gym` / `bin/test-cljs`
suite (a single-ns `--test=seon.gym.paid-test` run does NOT populate the
program-graph source index, so the `:namespaces` block renders empty — see
Finding 4).

### Run 1 — my.data used, composed perfectly (first draft, double-counted store)

`:namespaces` rendered full (15043 tok). The agent (B) wrote, verbatim:

```clojure
(let [expenses (my.data/rows {:my.data/attr :my.expense/amount-usd})
      grouped  (my.data/group-sum
                 (merge expenses
                        {:my.data/group-key :my.expense/category
                         :my.data/key       :my.expense/amount-usd}))
      winner   (my.data/max-by (merge grouped {:my.data/key :my.data/total}))]
  {:group (:my.data/group winner)
   :total (:my.data/total winner)
   :all-groups (mapv (fn [r] [(:my.data/group r) (:my.data/total r)])
                     (:seon.items/items grouped))})
;=> {:group :dining, :total 212, :all-groups [[:groceries 146] [:dining 212] [:transport 80]]}
```

This is the WHOLE pipeline, composed correctly the first time it stuck — the
merge-arrow on both reducers, `group-key` on input, `:my.data/group` read off
output, and the group-sum envelope both threaded into `max-by` AND opened via
`:seon.items/items` for the breakdown. The 212 is the double-count (10 rows). The
agent even verified honestly: *"I double-checked the raw rows: 28+52+26+28+52+26
= 212, six dining transactions."* The API was not the problem; the scenario was.

### Run 2 (single-ns, `:namespaces` ABSENT) & Run 3 (faithful, `:namespaces` 7351 tok, fixed dataset)

After the double-count fix, both re-drives produced the correct answer and the
judge scored **100 / dining @ $106**. But in BOTH, the agent did NOT use
`my.data` — it hand-rolled. Run 2 used a datalog aggregate; Run 3 used
`group-by` + `reduce`:

```clojure
;; Run 2 — raw datalog aggregate (the footgun path my.data exists to replace):
(db/query '[:find ?category (sum ?amount)
            :where [?e :my.expense/category ?category]
                   [?e :my.expense/amount-usd ?amount]])
;=> [[:dining 106] [:groceries 73] [:transport 40]]

;; Run 3 — group-by + reduce over pulled entity maps:
(let [expenses result/VyZ-...
      grouped  (group-by :my.expense/category expenses)
      totals   (into {} (map (fn [[cat items]]
                               [cat (reduce + (map :my.expense/amount-usd items))]) grouped))
      biggest  (apply max-key val totals)]
  {:biggest-category (first biggest) :total-amount (second biggest) :breakdown totals})
;=> {:biggest-category :dining, :total-amount 106, :breakdown {:dining 106, :groceries 73, :transport 40}}
```

Note Run 2's `(sum ?amount)` got the right answer ONLY because the amounts are
DISTINCT within each category — the dedup-collapse footgun didn't bite. Two
dining rows of the same amount would have silently undercounted. That is exactly
the trap `my.data/sum-by` makes unreachable.

## Per-doubt ergonomics verdict (from Run 1, where my.data was actually used)

| doubt | observed | verdict |
|---|---|---|
| (a) finds `(merge (rows …) {:my.data/key k})` vs hand-extracts `:seon.items/items` and rewraps | Used `(merge expenses {…})` on BOTH reducers; never hand-extracted/rewrapped | **API wins** — the merge-arrow is discoverable from the docstring's worked chain |
| (b) `:my.data/group-key` (request) vs `:my.data/group` (output) confusion | Passed `:my.data/group-key` on input, read `:my.data/group` off the result row — correctly, no mix-up | **API wins** — the distinct names did not trip it |
| (c) expects `group-sum` to return a plain `{cat total}` map vs the `:seon.items/*` envelope | Threaded the envelope straight into `max-by` via merge AND opened `:seon.items/items` for a breakdown — treated it as an envelope correctly | **API wins** — the envelope return read naturally |

The audit's three doubts were the right things to watch; all three came out in the
API's favor. `my.data` is good as-is.

## Cross-cutting findings (NOT my.data bugs — flagged for Core / context lane)

1. **`result/<id>` re-reference returns nil/partial on first composed use, then
   works on retry.** In Run 1 the first two `(let [… (my.data/rows …)] …)`
   group/argmax forms returned `nil`; structurally-identical later forms returned
   the value. In Run 3 the same shape over `result/VyZ-…` (a stashed pull) +
   `group-by` returned `nil` TWICE, and `(type (first result/VyZ-…))` came back
   `#‹fn›` with a "partial view — the COMPLETE value is result/seg-…" note,
   before the third identical attempt resolved. Reproduced with my.data AND plain
   `group-by`, so it is a value-stash / `maybe-await-value` resolution artifact in
   the eval machinery, not a my.data issue. It costs agents ~2–3 wasted evals and
   inflates `eval-error-rate`. (An ad-hoc REPL repro on the live pod was
   inconclusive — the pod's wire-backed conn doesn't mirror the gym's awaited
   `:memory` seed path; the gym is the faithful environment.) **Recommend Core
   investigate the `result/<id>` first-reference path.**
2. **my.data adoption tracks its render prominence.** The single run where
   `my.data` rendered FULL (15k namespaces) USED it; the runs where it was
   trimmed (7.4k) or absent had the agent hand-roll datalog/`group-by` instead.
   n=1 each and DeepSeek is stochastic, so this is suggestive not proven — but it
   is direct, on-point evidence for the **#42** tradeoff: trimming the namespaces
   block risks pushing agents off the safe `my.data` surface back onto
   footgun-prone hand-rolled aggregation. Worth an A/B when #42 lands.
3. **Honesty fabrication recurred.** In Run 2 the agent had the correct query
   result in hand (`[[:dining 106] …]`) yet narrated and SENT *"groceries … at
   $203.47"* (invented), then caught itself, re-derived from the real
   `result/<id>`, and corrected with an apology. Message-text decoupled from
   observed data — the known honesty smell.
4. **Gym faithfulness caveat.** `node out/test/test.js --test=seon.gym.paid-test`
   alone leaves the `:namespaces` block empty (the program-graph source index is
   seeded by earlier suite namespaces). Only the FULL `bin/gym` /
   `bin/test-cljs` run is faithful for context-dependent paid drives.
5. **The double-count latently affects x1/x3 too.** Their `*-seeded-visible`
   predicates use `[:count>= N]`, which passes at 2N and hides a re-log; their
   judges would then grade doubled totals. If those scenarios keep the
   fixtures+A-relog shape, consider the same single-agent-over-seed fix or an
   exact `[:count N]` guard (this scenario uses `[:count 5]` so the regression is
   visible).

## Scorecard (Run 3, faithful, fixed scenario)

`pass? false`, `judge-pass? true` (score 100, dining @ $106). Mechanical legs:
`expenses-seeded-visible` exactly 5 ✓, `grouped-by-category` 4/14 ✓,
`replied-to-the-user` ✓, `agent-ends-idle` 1 ✓; `keeps-the-repl-clean` ✗
(eval-error-rate 0.357 > 0.2). The cleanliness red is HONEST and on-purpose — the
agent emitted prose-as-code (segmenter READ ERRORs), an arity slip, an
unregistered-attr probe, and the Finding-1 nil retries. The correctness judge
passes; the curation axis reds — the intended "answered right, with REPL noise"
signature. A parser/stash improvement (Findings 1, and the #43/#46 parser work)
flips it green; that is the regression value of keeping the 0.2 threshold.

## Suite status

Drive-3 ran the full `bin/test-cljs` suite. The only `cljs.test` failures (7) are
in `seon/ctx_test.cljs` — a peer's in-flight namespaces/my.ui context change
(HEAD `42991198`) that the build predates; **none in the gym lane**. The gym
driver/baseline/paid integrity tests and the `x-category-argmax` scorecard all
came back valid. `src/my/data.cljs` was NOT modified, so no dedicated suite run
was owed.

## Files

- `test/seon/gym/scenarios/x-category-argmax.edn` — the scenario (new).
- `test/seon/gym/paid_test.cljs` — `:xcat` roster entry + `x-category-argmax-paid`
  deftest.
- `src/my/data.cljs` — UNCHANGED (API verdict: good).
