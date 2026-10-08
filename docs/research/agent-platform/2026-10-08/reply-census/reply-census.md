---
title: Reply census, what an agent sees and whether the display budget is right-sized
type: research
status: complete
date: 2026-10-08
lane: reply-census
---

# Reply census: what an agent sees, and whether the display budget is right-sized

Lane `reply-census`, agent `reply-census`, default at `32ca5f2e5` (fresh restart), MCP `eval_clj` (sci), 2026-10-08.
38 forms and one `runtime_status` call, read as an agent reads them. Tokens are `:seon.eval/shown-tokens` /
`:seon.eval/value-tokens` as recorded for each evaluation (`seon.ai.tokens/estimate` of the shown value, queried back
with `seon.db/q`); a `~` marks a figure estimated by characters because the row was not recorded when queried. The
reply around a value (`:seon.repl` keys plus the `:seon.turn.loop/tests` tail) adds about 35 tokens, and each
`:findings` entry about 80.

Classes: (a) general printer rule (`seon.print`, `seon.render.value`), (b) specific render returns too much or too
little, (c) producer returns garbage. "new" rows are tasks filed in this commit (task name in the last column).

## Findings first

1. **The default budget is not the binding limit; the child cap is.** 28 of 38 replies show 150 tokens or fewer.
   Only 3 reached 1,400-1,500 (long string 1,498, function entity 1,459, 2,000-entry map 1,388). The 400-element
   vector shows 311 of 1,500 because `:seon.render.profile/max-children` (32) cuts it with 1,189 tokens unspent; the
   map spends its 1,388 tokens on 32 fat entries. Two dials decide what a collection shows and only one is a token
   budget. New: `seon.print/a-child-cap-starves-small-elements`.
2. **The ruled override does not exist.** `(binding [seon.print/*budget* 4000] ...)` answers `Unable to resolve var`.
   PR-11 is ready in the ledger but not installed, so no reply today can use 2,000. New:
   `seon.print/the-display-budget-override-is-not-installed`.
3. **Most waste is in specific renders, not the printer.** Tokens an agent did not need: `current-state` 558 of which
   about 450 are world arguments (connection ids, caps, SCI ctx, profile); `callers` 1,143 shown of 2,630 with the
   callee's `:declared-arities` repeated on every call site and the caller set listed twice; the function entity
   pull 1,459 with the source as 13 strings beside the docstring and 11 identical "unrun or incomplete" strings.
4. **The printer's general rules mostly work and its gaps are already ledgered.** Lazy infinite seq and the 400-vector
   cut print `(drop 32 result/e..)`, runnable `clojure.core`. Set and map cuts call `seon.print/value-at`, which is
   not `clojure.core` (30 such cuts cost about 810 of the map's 1,388 tokens). The string cut prints an unclosed
   quote and a character count: `"…+10738 (subs result/.. 4752)}`. Markers read `…N`, `…?`. An exception prints
   `#error {:via [{:type .. :message ..}]}` and a `try`/`catch` of it loses its data and trace.
5. **`:out` is not counted in the shown tokens.** `(clojure.repl/source seon.ai.tokens/estimate)` records 0 shown
   tokens for about 130 tokens of printed source (PR-07).
6. **Every reply ends with a tests block even when nothing ran**: on a fresh agent `{:seon.test/passed? false
   :seon.turn.loop/red []}` after `(+ 1 2)` (about 30 tokens, 38 of 38 replies carried it; it flips to `passed?
   true` once a test exists). The same defect is tasked in `my.test/test-output-and-an-empty-reaching-run-reach-the-reply`.
7. **Cost numbers outside the sub-second rule** (agent-visible as `:large-allocation` findings): `ns-unmap!` 833 ms /
   3.3 GB, `my.test/check` 778 ms / 2.6 GB (green, one test), `seon.health/report` 701 ms / 1.2 GB, a one-entity
   attribute listing 883 ms / 2.0 GB. All have tasks (below).
8. **Health says new evaluations refuse under memory pressure while evaluations run** (`runtime_status` top fault,
   14 occurrences, stale or falsified observation; heap-used 1.4-1.8 GB of 4 GB). Not a printer matter; seen in
   `runtime_status` and `seon.health/report` alike.
9. **Law versus installed:** an uncontracted `defn` is admitted with no warning (law: a growing warning beside the
   value); `my.program/ns-unmap!` takes `[context subject]` (CLAUDE.md teaches the Clojure shape). New:
   `seon.fn/an-uncontracted-defn-is-admitted-without-its-warning`.

## The table

| # | Form | Tokens shown / full | Problems | Class | Ledger row or task |
|---|------|--------------------|----------|-------|--------------------|
| 1 | `(+ 1 2)` | 0 / 0 | tests tail `passed? false`, `red []`, nothing ran | c | `my.test/test-output-and-an-empty-reaching-run-reach-the-reply` |
| 2 | `(seon.context/current-state)` | 558 / 558 | world arguments (connection, db, caps, SCI ctx, profile) beside a value of `turns-left`; internal keys | b | `seon.context/current-state-prints-the-world-not-the-block`, CS-02 |
| 3 | `(doc seon.db/pull)` | 250 / 250 | empty `:body ""`, `:example ""` keys; `:in` and `:out` repeat the same union three times | b | `seon.sci.eval/doc-prints-contracts-expanded-with-keys-garbled`, H-01 |
| 4 | `(clojure.repl/source clojure.core/some-fn)` (also `map`, `clojure.string/join`, `malli.core/validator`) | 0 / 0 | "Source not found" for every library var; agent needed the source | c | new `seon.sci.eval/source-of-a-library-var-answers-source-not-found` |
| 5 | `(doc clojure.core/map)` | 148 / 148 | empty `:example ""`; otherwise fine | b | S1-06 |
| 6 | `(clojure.repl/source seon.ai.tokens/estimate)` | 0 / 0 (about 130 in `:out`) | source printed to `:out`, not in the budget, value `nil` | a | PR-07, `seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl` |
| 7 | `(my.program/diff)` | 56 / 56 | 280 ms, 275 MB for a one-def diff; `:kind :function` for a `def` | c | `my.program/an-agent-cannot-see-or-choose-its-change` |
| 8 | `(my.program/callers 'sym)` (wrong shape) | ~330 | refusal prints `:example` with `#object[datahike.db.DB 0x..]` and a garbage subject `tV`; no `:seon.error/type` | b | `seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema`, E-SRENDERS |
| 9 | `(my.message/inbox)` | 0 / 0 | none | - | - |
| 10 | `(my.program/callers {..})` | 1,143 / 2,630 | `:call-sites` repeats the callee arities per site and the caller list twice; caller set cut shows `(get-in result [:seon.program/callers])` which returns the whole set; 8 elisions | b, a | new `my.program/callers-lists-each-call-site-with-the-callees-arities`; PR-02 |
| 11 | `(my.test/check ..)` nothing reaches | 179 / 179 | `executed-count 0`, `results []`, no verdict; `:unreached` lists the agent's own scratch `user/census-rows` | c | `my.test/a-check-no-test-reaches-reads-as-a-failure` |
| 12 | `(in-ns ..)`, `(defn ..)`, `(deftest ..)` | 6, 14, 15 | none (Var render is minimal: symbol only) | b | F9 (right) |
| 13 | `(deftest ^{:seon/tasks []} ..)` | ~170 | refusal explanation `{:seon/tasks ["invalid type"]}` names no shape and no fix | c | new `seon.fn/an-empty-tasks-annotation-is-refused-without-its-shape` |
| 14 | `(my.test/check ..)` red | 119 / 119 + 1 finding | good data render; `expected` and `actual` are printed strings; 336 ms, 486 MB | b, c | `seon.test.runner` task on printed `actual`, `an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step` |
| 15 | `(my.program/ns-unmap! 'my.census 'x)` (Clojure shape) | ~280 | refusal prints `:example` of three `#object[..]`; teaches nothing | b | same as row 8; new row 9 above |
| 16 | `(my.program/ns-unmap! 'my.census/x)` | 285 | five boilerplate `:unknown` gap messages (about 130 tokens) per reply; 833 ms, 3.3 GB | b, c | `my.program/ns-unmap-and-remove-ns-cost-quarter-thousand-tokens-and-most-of-a-second` |
| 17 | `(my.test/check ..)` green | 63 / 63 + 1 finding | small and right; 778 ms, 2.6 GB for one test | c | `an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step` |
| 18 | `(seon.db/pull db [:seon.fn/sym :seon.fn/doc] ref)` | 87 / 87 | none | - | - |
| 19 | `(frobnicate 1)` | ~70 | none; `:in-namespace {my.census [double-it]}` lists the namespace's Vars (useful); no `:seon.error/type` | b | E-SRENDERS |
| 20 | `(double-it 1 2)` arity | ~150 | message, `:problems`, `:arglists`, `:declared-arities` say the same thing four ways | b | E-SRENDERS |
| 21 | `(double-it "x")` | ~110 | none | - | - |
| 22 | defn returning a string for `:int`, called | ~100 | none (clear, names the fix) | - | - |
| 23 | 2,000-entry map | 1,388 / 78,391; 30 elisions | 32 children cut on count; each cut `…1 (seon.print/value-at ..)` not `clojure.core`; bare `…1968 result/e..` | a | PR-02, PR-29; `seon.print/elision-forms-call-a-seon-helper`, `a-member-cut-costs-more-than-it-hides` |
| 24 | 600-line string | 1,498 / 4,853 | cut `"…+10738 (subs result/.. 4752)}` unclosed quote, counts characters not lines; large-value finding repeats generic prose | a | PR-03, PR-29 |
| 25 | `(map #(* % %) (range))` | 47 / 47 | right: `…? (drop 32 result/e..)`, runnable; marker `…?` | a | PR-08, PR-29 |
| 26 | `(vec (repeat 400 {..}))` | 311 / 3,531 | cut at 32 with 1,189 tokens unspent; 32 identical lines | a | new `seon.print/a-child-cap-starves-small-elements` |
| 27 | `(try (/ 1 0) (catch Exception e e))` | 25 / 25 | `#error {:via [..]}` no cause, no data, no trace; not readable back | a | PR-04 |
| 28 | `(throw (ex-info ..))` | ~70 | good: message, chain with `:data`; `[my.census 1 1]` frame | - | PR-04 (ok) |
| 29 | `(seon.health/report ..)` | 1,027 / 1,027 + 2 findings | `:large-value` fires with nothing cut; 701 ms, 1.2 GB; same facts as `runtime_status` | b | `seon.bootstrap/help-is-flagged-as-a-large-value` (same class), MS-12 |
| 30 | `runtime_status` | ~1,350 | five fault clues each about 100 tokens; overlaps row 29; memory-pressure fault stale | b | MS-12 |
| 31 | `(seon.db/pull db '[*] [:seon.fn/sym ..])` | 1,459 / 1,755; 6 elisions | source as 13 strings beside the docstring; 11 identical "unrun or incomplete" strings; related lists cut with `…1` | b | new `seon.render.ns/a-function-entity-render-repeats-an-unrun-state-per-test`; PR-13 |
| 32 | `#'my.census/double-it` | 14 / 14 | right (symbol only) | b | F9 |
| 33 | `(clojure.repl/source my.census/double-it)` | 0 / 0 (about 40 in `:out`) | as row 6 | a | PR-07 |
| 34 | `(defn no-contract [x] x)` | 14 | no warning for an uncontracted definition | c | new `seon.fn/an-uncontracted-defn-is-admitted-without-its-warning` |
| 35 | `(dir seon.ai.tokens)` | ~480 | ten maps; `:arglists ()` on constants; fine | b | S1-07a |
| 36 | `(binding [seon.print/*budget* 4000] ..)` | ~140 | var unresolved; error carries host frames `clojure.core/let 4527 1` | c | new `seon.print/the-display-budget-override-is-not-installed` (PR-11) |
| 37 | `seon.db/q` with an unbound `not` | 126 | raw Datahike exception as `:chain`, no `:seon.error/type` | c | `seon.db/q-lets-a-dependency-throw-escape` |
| 38 | `(seon.db/q '[:find ?a :in $ ?e :where [?e ?a _]] ..)` | ~100 + finding | 883 ms, 2.0 GB to list one entity's attributes | c | `seon.db/an-entity-only-pattern-scans-past-the-query-work-bound` |

Observed, not yet tasked: a `(seon.db/pull db '[*] <eval entity>)` once printed as an REPL transcript string
(`user=> (seon.db/q ..)`) rather than the pulled map; the producer of that render was not found in the time box.
Reproduce with the maximum `:seon.eval/shown-tokens` entity id.

## Budget: what fits

Distribution of shown value tokens over the 38 forms: 0-150: 27; 150-600: 5; 1,000-1,500: 6; over 1,500: 0 (the
printer stops there). Full value sizes of the capped ones: 1,755 / 2,630 / 3,531 / 4,853 / 78,391.

- **1,500 is sufficient for these replies.** The three at the cap are all noise or bulk the agent should query, not read.
- **2,000 per evaluation (ruled) would change two replies**: the function entity (1,755, whole) and `callers`
  (2,630, still cut). Counting the tail (about 35) and findings (80 each) inside the 2,000, the usable value is about
  1,850. Fixing the producers in findings 3 and 4 shrinks `callers` to roughly 600-1,300, the entity to about 700,
  `current-state` to about 120: more gain than raising the number.
- **The override is needed by** reads of genuinely large text or sets: `source` or `doc` of a hub function,
  `callers` of a hub, a whole `(dir ns)` of a large namespace, a file section. It is not needed by the map, vector,
  string or lazy-seq probes, which an agent narrows with the elision form or a query. Recommendation: keep the 1,500 profile
  budget for ordinary replies, install PR-11 (so 2,000-4,000 is one binding away), and make the child count follow
  the budget (the new `a-child-cap` row) so a budget change actually changes what small-element collections show.

## PR-30: a feedback loop for the budget (proposed row)

The owner wants agents to tune the budget. Smallest loop, from what exists:

1. **Record, per reply, what was shown and what was cut.** Already stored per evaluation: `:seon.eval/shown-tokens`,
   `:seon.eval/value-tokens`, `:seon.eval/elisions`, `:seon.eval/value-class`, `:seon.eval/renderer`
   (`src/seon/sci/eval.clj` where `shown-tokens` is set). Add `:seon.eval/cut-paths`: the paths of the cut nodes the
   printer already produces (bounded by `:elisions`), declared on the eval schema.
2. **Record a followed cut on the later form.** Ledger row D1 is the seam: `:seon.cluster.eval/result-reads` lists which
   result handles a form names (recorded at settlement, `src/seon/turn/batch.clj`). A later evaluation of the same
   agent reading handle `eN` is one of: an elision form (head in `drop`, `nth`, `get-in`, `subs`, `take`, path inside
   `eN`'s cut paths), a re-read of the whole handle, or a use of `seon.print/*budget*` (PR-11 records
   `:seon.eval/budget-override`). Class is derived from the form's data, no text matching beyond the read symbols.
3. **Report per value class, as data.** `seon.health/budget-report` (a pure function over eval facts, a health unit
   beside `:profile`), grouped by `:seon.eval/renderer` or value class: `{:n :elided-count :followed-cut-rate :override-rate
   :shown-p50 :shown-p90 :recommended-budget}`. `recommended-budget` is the 90th percentile of `:value-tokens` among
   replies whose cut the agent followed, clamped to the model ceiling, and unchanged when the followed-cut rate is under a threshold.
   It is advice; the dial stays a configuration fact an owner applies.

Files: `src/seon/turn/batch.clj` (D1 recording), `src/seon/sci/eval.clj` (cut paths), `resources/seon/schemas/seon.eval.edn`
and `seon.cluster.eval.edn`, `src/seon/health.clj` (report), `test/seon/cluster/turn_test.clj`. Proof:
`seon.cluster.turn-test/a-form-naming-a-handle-records-its-result-read` (D1), then a new
`seon.cluster.turn-test/an-elision-form-on-a-cut-result-counts-as-a-followed-cut` (evaluate `(vec (range 100))`, then
`(drop 32 result/eN)`; assert the second evaluation's result-reads name the first and the report counts one followed cut-in
for that class) and `seon.health-test/the-budget-report-recommends-from-the-followed-cut-rate` on fixture rows. Task:
`seon.turn.batch/the-display-budget-has-no-feedback` (new).

**This census as first data.** Of 11 replies with elisions I followed none, because the probes were synthetic; of the
rest, where I needed more: `callers` (I wanted every caller, got 14 of 25 and a list of call sites), `source` of a
library var (got nothing), `runtime_status` (needed one top fault, got five); where I needed less:
`current-state` (about 80% world arguments), `doc`, the tests tail, the five `:unknown` gap messages after
`ns-unmap!`. The loop sees only "needed more" (an elision form run on the cut); "needed less" shows as tokens shown on replies that were
never read again, which the report can show per class (`shown-p50` against followed-cut rate zero) but cannot tell from a reply read
and used in full. That limit is real and the report should say it.

Dependency on other rows: PR-02 and PR-29 make the elision forms runnable and readable, which is what lets a followed cut be
recognised; PR-11 supplies the override signal; D1 supplies the reads.
