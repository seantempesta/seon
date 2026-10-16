---
title: "Bench baseline v1: the metric, the task set, and the one live attempt it can grade today"
type: evidence
status: partial (the live task-set baseline is blocked on a merge decision, section 4)
created: 2026-10-16
---

# Bench baseline v1 (lane bench, 2026-10-16)

The harness is `seon.competency` in `test/seon/competency.clj`; the task set is `test/seon/competency/bench-v1.edn`;
the proofs are in `seon.competency-test`; the harness's own proofs are in `seon.competency-grade-test`.

## 1. The metric (one run = one attempt of one chain of one task)

`seon.competency/launch!` writes each run with `seon.task/chain!` (the variant is the chain's `:seon.chain/config`:
provider, model, call cap and any context dial), waits for the attempt's `:seon.task.attempt/end` on its chain
branch, and grades it there with `seon.competency/grade`. Root's db<->flow does everything in between; the launcher
makes no hand act. Every column comes from the attempt's own facts on its kept branch, not from the branch's later
state:

| Column | Source |
|---|---|
| end, green | `:seon.task.attempt/end` (`:done` means the done rule passed: proofs green, nothing else red, no contract gap); the proofs' counts from `:seon.agent/end-green` / `end-red` / `end-pending` |
| model calls; calls to green | the agent's call rows with a model attempt; a green run's calls (it ends at its first green) |
| score | calls to green, a non-green run scored one past its cap (`seon.competency/score`): one number per run, lower is better |
| forms by class | every model evaluation, classed by `seon.competency/classify` (read, re-read, probe, progress, wait, navigation, search, repeat, unresolved, contract refusal, error) |
| wasted calls | calls holding no read, probe, progress or wait form |
| errors | agent: evaluations with an error; platform: model attempts with an error |
| tokens | prompt, cached and completion tokens summed over the calls' model attempts |
| wall ms | first call opened to last call closed |
| eval ms, alloc MB | `:seon.eval/duration-ms` and `:seon.eval/allocated-bytes` summed over the model evaluations |

`seon.competency/table` folds runs per task and variant (green count, score median and range, calls-to-green median
and range, means, wall median, summed classes). `seon.competency/compare-tables` applies the E0 noise rule between
two tables (two HEADs or two variants): a cell is `:signal` only when green counts differ by 3 or more or the score
ranges do not overlap, and then `:better` or `:worse`; everything else is `:noise`.

## 2. Task set v1

Seven tasks, each creating a namespace main does not hold, so every proof is red on main. Each oracle was evaluated
on a disposable agent branch on default (agents `bench-oracle2`, `bench-lane`) and gave the values its proof asserts;
t1's proof ran green there through `my.test/check` (run `e82e349c810a`). The deftests of t6, t8 and t9 could not be
run on an agent branch (section 5, finding 3), so their assertions were checked as expressions.

| id | split | what |
|---|---|---|
| t1-area | train | contracted function |
| t2-qty | train | registered schema, then a function contracted over it |
| t6-word-count | train | string function with a contract refusal |
| t8-digit-sum | train | function plus the agent's own passing deftest |
| t4-alias | held-out | two namespaces, an aliased require |
| t7-index-by-id | held-out | collection function with a map contract |
| t9-initials | held-out | string function |

Authoring found two broken plans before any run: a `[:maybe :int]` return is refused at admission ("bare nilable
return"), and `malli.core/validate` on a key registered with `seon.schema/register!` answers
`:malli.core/invalid-schema` in an agent's context. Both tasks were replaced. v1 is authored by one lane, so the
held-out split is not yet sealed (self-improvement-loop.md, "Enforcing no cheating" item 2).

## 3. The one live attempt gradable today

Only one kept attempt with model calls exists on default: lane live-run's chain (`live-run.md`), graded read-only
from its branch in 70 ms.

| attempt | branch | end | calls | wasted | forms | agent err | prompt / cached / out tokens | wall | eval ms | alloc MB |
|---|---|---|---|---|---|---|---|---|---|---|
| `5548298627d3` | `:chain-68d99c8aef55` | done (merged) | 27 | 2 | 54 | 3 | 465,680 / 384,640 / 3,729 | 60.0 s | 401 | 516 |

Classes: read 16, probe 17, re-read 8, progress 3, repeat 3, navigation 2, search 2, unresolved 2, contract refusal 1.

Where its turns went (forms in call order, read with `seon.competency/classify`):
- **Calls 8 to 23 (16 of 27): adding one `:require` to an existing `ns` form.** Call 8 re-evaluated `seon.edit`'s
  whole `ns` form with a new require and failed ("Could not find namespace: seon.config.eval.result"). The agent then
  queried program facts by hand for eight calls (`seon.db/q` on `:seon.ns.alias/local`, `:seon.fn/sym`, file paths;
  now classed search) and tried `my.program/search` with a symbol (a contract refusal) before
  `(require '[seon.sci.reader :as reader])` in call 23 worked. The context never says how an agent adds a require to
  a namespace it is in.
- **Re-reads: 8 forms** of `my.program/source` for names already read (`single-form?` three times), at
  `:seon.config.context/reference-tokens` 1,500, so not only history elision.
- **Unresolved names: 2** (`my.program/declarations`, the namespace above).

## 4. The task-set baseline is blocked: a green bench run would publish into the checkout

The launcher works end to end on the real pipeline (proof `seon.competency-grade-test/a-launch-grades-each-chain-run-from-its-branch`:
two fixture-model chain runs, each `:done` in one call, graded from its branch). It was not run against default,
because every bench task's done end merges into main and publishes:

- `seon.cluster.agent/merge-chains!` merges every done terminal attempt, and `seon.merge/publish!` writes its
  declarations into `:seon.config.fs/working-root` (default's is the repository) and makes a git commit.
- A namespace main never published still gets a file: `seon.cluster.source/change-path` falls back to `new-path`.
  So run 1 of t1 would commit `comp/geo.clj` to the repository, and t2 a schema resource.
- After that merge, every later run of the same task starts with its proof green and ends with no model call.

Observed in a check JVM (whose checkout is refused as a write-back root): both fixture runs bailed to root with
"The final merge refused it, :seon.source/write-back-error", one problem task each.

A bench run is CI on a branch that is never merged. Options for root (TASK-CHAIN is locked, so each needs a ruling):

1. **Recommended, smallest:** a chain option that ends the chain at its last task's done with no merge (git: a branch
   with CI and no merge request), kept for reading, with its red deftest and the TASK-CHAIN amendment. About one
   clause in `seon.cluster.agent/chain-finishes` and one key in `:seon.task/chain-options`. Then
   `seon.competency/launch!` passes it and the baseline runs (21 runs at n = 3, about US$0.05).
2. A bench cluster: its own main (a branch of the store), its own db<->flow and a disposable working root, with the
   task names and namespaces renamed per run so merges never pre-green a later run. Larger: a second cluster runtime.
3. Run the set inside the test harness (a member branch as main, passes driven by the test). Not the live
   reconciler, and in that world the proof of t1 stayed red after the model defined `comp.geo/area`
   (section 5, finding 2).

## 5. Findings (outside this lane's files)

1. Bench runs need a no-merge chain end (section 4).
2. In a fixture member cluster (check JVM, `seon.competency-grade-test` world), `seon.competency-test/t1-area-is-contracted`
   run by the chain's test step answered "Unable to resolve symbol: comp.geo/area" after the model's `(defn area ...)`
   evaluated without error on the chain branch; the same proof is green on an agent branch on default.
3. On an agent's SCI branch, `(load-string ...)` of `seon.competency-test` cannot resolve `seon.test-support/agent-value`,
   although the error's own `:seon.error/in-namespace` lists `agent-value` in `seon.test-support`.
4. `malli.core/validate` with a key registered by `seon.schema/register!` fails in an agent's context
   (`:malli.core/invalid-schema`); no agent-facing validate-by-key verb was found.
5. Evaluation errors are stored as text only (`:seon.cluster.eval/error`), so the classifier reads message text; the
   producer should store the error's `:seon.error/type`.
6. After a refused program row (a bare nilable return), every later request on that branch reports the same
   `seon.sci.eval/acquisition-refusal` error ("Program row could not be installed"), counted per request.
7. `(load-string (slurp "test/seon/competency.clj"))` (24 KB) on an agent branch allocated 476 MB and took 371 ms.
