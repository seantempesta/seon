---
type: research
status: active
tags: [research, agent, gym]
---

# #42 namespaces-trim validation — token drop + my.data composition A/B

> Measuring the just-landed #42 lever (`55cd5002` — my.* namespaces render
> at `:signature` by default, one kept full exemplar `my.kb`) plus the
> ns-switch eval fix (`1809e9ad`). Two questions: (1) how much did the
> always-on `:namespaces` block actually shrink, and (2) the FLAGGED
> regression risk — does signature-only `my.data` still get COMPOSED, or
> does the agent fall back to footgun-prone hand-rolled aggregation (the
> render-prominence finding, `my-data-gym-drive-2026-06-28.md`)?

## TL;DR — REGRESSED (on adoption), answer still correct

- **Verdict: the render-prominence regression is REAL.** Driving the
  `x-category-argmax` composition scenario (paid DeepSeek, faithful full-suite
  env) with `my.data` rendered as SIGNATURES, the agent **never called
  `my.data`** (0 of its evals) — it hand-rolled `db/query` + Clojure
  `group-by` + `(apply max-key val …)`. It still LANDED the right answer
  (**dining @ $106**, judge score **100**), but via improvised aggregation,
  not the safe toolkit surface. This is the third datapoint on the same
  trend: my.data is composed when rendered FULL, hand-rolled when trimmed.
- **Token drop is real but the FREE battery does NOT show it.** The
  authoritative per-block A/B is the #42 commit's own same-pod measurement:
  `:namespaces` **13,624 → 7,702 tok (−43.5%)**. This faithful gym run
  corroborates a TRIMMED block: `:namespaces` = **11,285 tok** (signatures +
  the one full `my.kb` exemplar). But the FREE `bin/gym-scorecard` battery
  per-scenario total is **FLAT** (pre-#42 `1c381843` 17,974 tok/scenario →
  post-#42 `b5c3a3a4` ~17,900) — it is a confounded/coarse instrument for
  this lever (see § Token drop).
- **eval-error-rate 0.357** (> the 0.2 gate → `keeps-the-repl-clean` RED).
  Driven by the Finding-1 `result/<id>` nil-on-first artifact (×2), a
  `:my.expense/date` typo query, a prose-pasted-as-code READ ERROR, and a
  markdown-backtick `` `this` `` parsed as a symbol (#43 clip-escape class).
  **None** were ns-switch errors — the `1809e9ad` fix was NOT exercised by
  this drive (the agent never did an `in-ns`/`(ns …)` switch), so this run
  measures no change from it.
- **Honesty smell recurred.** The agent's FIRST reply fabricated
  "groceries at **$127.50**, $257.50 total" — numbers that exist nowhere in
  the store — then re-ran the real query and corrected to dining @ $106.
  Message-text decoupled from observed data, same smell as the prior drive.

## Token drop — three instruments, one honest picture

| instrument | pre-#42 | post-#42 | delta |
|---|---|---|---|
| #42 commit same-pod A/B (`:namespaces` block, live pod 7890, agent root) | 13,624 | 7,702 | **−5,922 (−43.5%)** |
| this xcat faithful gym run (`:namespaces` block-tokens) | — | 11,285 | trimmed (sig + `my.kb` full) |
| FREE `gym-scorecard` battery, per-scenario (`total-tokens ÷ scenario-count`) | 17,974 (`1c381843`, 20 sc) | ~17,900 (`b5c3a3a4`, 21 sc) | **~flat** |

Reading these honestly:

- **The lever is real.** The #42 commit's controlled same-pod A/B (same agent,
  same world, only the render rule changed) is the authoritative number:
  **−43.5%** off the `:namespaces` block. This run's 11,285-tok block
  corroborates a trimmed render (vs a full ~15k); it sits higher than the
  live pod's 7,702 because the gym seeds a FRESH, complete program-graph
  index, so every non-kept `my.*` ns renders a full signature set, whereas
  the live pod's index is sparse (the #41-class artifact the #42 commit
  noted) and renders fewer rows.
- **The FREE battery total-tokens is NOT a clean instrument for #42.** It
  scales with `scenario-count` (19→20→21 across the trend) and is the
  turn-1 context summed across mostly-stub scenarios; normalized
  per-scenario it does not move (17,974 → ~17,900). Two reasons it misses the
  lever: the always-on `:namespaces` block is a minority of each scenario's
  turn-1 context, and the measure-context! seed path is subject to the same
  index-state variance. **Recommend the trend line track a per-block
  `:namespaces`-token axis** (it already captures `turn-profiles` block-tokens
  on scored cards) rather than the battery-wide `total-tokens` for judging
  render-trim levers. The FREE pass profile otherwise HOLDS: per-competency
  `honesty 2/2, db-memory 1/1, planning 1/1`, eval-error-rate 0, across the
  full 21-scenario roster.

## Composition validation — the real evals (paid DeepSeek, faithful suite)

Scenario `x-category-argmax`, agent `FKv-2606282117`, 6 turns. Scorecard:
`pass? false` (mechanical), `judge-pass? true` (score 100, dining @ $106),
`eval-error-rate 0.357`, `:namespaces` 11,285 tok.

**What the agent SAW for `my.data` (its prompt, verbatim — signatures only):**

```clojure
;;; ┌─ namespace my.data (signatures) ─
[fn my.data/group-sum]  (my.data/group-sum [{:my.data/keys [group-key key] :seon.items/keys [items]}])  :spec [:=> [:cat :my.data/group-request] :my.data/items-envelope]
; Sum KEY within each distinct value of GROUP-KEY → a `:seon.items/*`
[fn my.data/max-by]  (my.data/max-by [{:my.data/keys [key] :seon.items/keys [items]}])  :spec [:=> [:cat :my.data/reduce-request] [:maybe :map]]
; The item MAP whose KEY is largest — returns the ENTITY, not the value,
[fn my.data/rows]  (my.data/rows [{:my.data/keys [attr]}])  :spec [:=> [:cat :my.data/rows-request] :my.data/items-envelope]
; Every entity carrying ATTR, pulled to a vector of self-describing maps —
[fn my.data/sum-by]  (my.data/sum-by [{:my.data/keys [key] :seon.items/keys [items]}])  :spec [:=> [:cat :my.data/reduce-request] :my.data/total]
; Total of KEY across the given item maps. Reduces over MAPS, so the
;;; └─ end namespace my.data ─
```

The arglists name the keys (`group-key`/`key`/`items`/`attr`) and the specs
point at `:my.data/group-request` etc., but every doc-line is **clipped to
its truncated first sentence** — the worked `(reducer (merge (producer …)
{:my.data/key k}))` ARROW and the end-to-end `rows → group-sum → max-by`
example (which live in the verb-body docstrings and the ns docstring) are
ELIDED.

**What the agent ACTUALLY did (its own evals, verbatim) — hand-rolled, 0×
my.data:**

```clojure
;; 1) over-broad datalog query, with a hallucinated attr → error-as-value:
(db/query '[:find ?e ?cat ?amt ?date ?merchant
            :where [?e :my.expense/category ?cat]
                   [?e :my.expense/amount-usd ?amt]
                   [?e :my.expense/date ?date]            ; not in schema → ✗
                   [?e :my.expense/merchant ?merchant]])

;; 2) re-reference of the failed query's stash → nil (Finding-1 artifact):
(let [rows (result/VyJ-2606282118)                        ; ✗ not defined
      by-cat (group-by second rows) …] …)

;; … then a FABRICATED reply (groceries $127.50 / $257.50 total) …

;; 3) the clean hand-roll that finally answered — group-by over a relation,
;;    NOT my.data, NOT (sum ?x):
(let [rows (db/query '[:find ?cat ?amt ?merchant
                        :where [?e :my.expense/category ?cat]
                               [?e :my.expense/amount-usd ?amt]
                               [?e :my.expense/merchant ?merchant]])
      by-cat (group-by first rows)
      totals (into (sorted-map)
                   (for [[cat items] by-cat]
                     [cat (reduce + (map second items))]))
      biggest-cat (key (apply max-key val totals))] …)
;=> {:totals {:dining 106, :groceries 73, :transport 40}, :biggest-cat :dining …}
```

The correct answer came from the hand-rolled `group-by` (step 3). It was
correct only because the amounts happen to be distinct — the run returned
`:rows` as a SET (`#{[:dining 28 "Thai Place"] …}`), so two identical
`[cat amt merchant]` rows would have silently collapsed. That is exactly the
dedup footgun `my.data` exists to make unreachable, re-introduced by the
agent improvising off the toolkit.

## The Core fix (precise) — REGRESSED, so flagging

The signature trim strips the one thing that taught composition: the worked
merge-arrow chain. The agent got the verb NAMES and arglists but not the
`(reducer (merge (producer …) {:my.data/key k}))` pattern that ties
`rows → group-sum → max-by`, so it didn't reach for `my.data` at all.

- **Where the worked chain is lost:** `src/seon/agent/ctx.cljs:1242-1244` —
  `fn-block-ai`'s signature path renders only
  `(clip (first (str/split-lines doc)) member-doc-clip)`, i.e. the truncated
  FIRST doc line. The verb-body `;; the worked chain` examples and the
  `my.data` ns docstring's "The universal arrow is …" are never emitted at
  `:signature` detail.
- **Where the kept-full set is decided:**
  `src/seon/agent/ctx/namespaces.cljs:159` —
  `canonical-full-my-ns #{:my.kb}`. Only the DB manual is kept full; the
  aggregation TOOLKIT is signature-trimmed.

**Recommended fix (pick one, option 1 preferred):**

1. **Add `:my.data` to `canonical-full-my-ns`** (alongside `my.kb`). The #42
   commit's own calibration lesson is "trim framework BULK, never the
   toolkit's USABILITY" — `my.data` IS toolkit usability, and its value is
   the worked composition chain, not isolated signatures. Full `my.data` is
   ~137 lines (~1.1k tok) vs its signature block — a cheap add that protects
   the one ns whose worked example drives adoption, while the rest of `my.*`
   stays signature-trimmed (most of the −43% win preserved). If `my.ui` /
   `my.canvas` show the same "named but not composed" pattern in their drives,
   extend the set to the toolkit nses.
2. **OR** make the `:signature` render of `my.*` nses emit the FULL verb
   docstring (not just the clipped first line) so the worked `;;` chain
   survives in signature form — cheaper than full source, but loses the
   `(ns …)` docstring's "universal arrow" framing.

n=1 + DeepSeek is stochastic, and the hand-roll happened to be correct here —
so this is a real ADOPTION regression (agent off the safe surface), not a
correctness regression on this run. But it is the third consistent datapoint
(full → composed; trimmed/absent → hand-rolled), and the footgun it re-opens
is the one `my.data` was built to close.

## Files / artifacts

- Free battery: `docs/prds/agent-fsm/research/gym-scorecard-trend.edn`
  (appended `b5c3a3a4` lines).
- Paid card: `tmp/gym-paid-card-xcat-e7ec435a-….edn`.
- Agent prompt with verbatim evals:
  `logs/turns/FKv-2606282117/6-roM-2606282118/prompt.txt`.
- Lever source: `src/seon/agent/ctx/namespaces.cljs` (#42, `55cd5002`);
  `src/seon/eval.cljs` (ns-switch fix, `1809e9ad`, not exercised here).
