---
type: report
status: running (revised in place; most severe first)
created: 2026-09-26
scope: standing dogfood lane dogfood-repl-opus — being a Seon agent through MCP only (branch → eval_clj SCI → test → merge → write-back → retire)
---
# Dogfood report: the agent's REPL, from the agent's side

**Method.** I work only through MCP. The `branch` tool gives me my own branch, and `eval_clj`
runs in SCI mode on that branch as the hosted agent `mcp/default` in `user`. I do no file edits and
run no `bin/test-check`. Every finding below comes from the exact forms and shown text recorded
in its issue. Default moved through archives fbd0897df → 33ea69298 → a1b01c92a → f0efd9cc8
(fresh store) → 10d6fdff5 during cycle 1, which is about seven JVM replacements in 20 minutes.

**Milestone blockers** are marked **[B]**. Known in-progress defects are routed around and not
re-filed; they are listed at the end.

## Ranked findings

| # | Finding | Model that is wrong | Issue |
|---|---|---|---|
| 20 **[B]** (cycle 10) | A namespace whose caller's name sorts before its callee's (`top-words` calls `word-frequencies`) loses the caller: it evaluates, then the next acquisition drops its Var while its row stays, and every test world refuses its test ("no executable test Var"). The same body named `zz-top-words`, or the namespace renamed `a-…`/`b-…`/`c-…`/`d-…`, works. | Acquisition installs a namespace's rows in symbol-name order (`sort-by first`, `sci/eval.clj:2832`) and analyzes each body as it goes; a refused row becomes a missing Var, and `my.test/check` answers green with that change untested. | [a-namespaces-functions-install-in-name-order…](../../seon/issues/a-namespaces-functions-install-in-name-order-so-a-caller-named-before-its-callee-is-dropped.md) |
| 21 **[B]** (cycle 10) | After one refused run (`ns-unmap!` of a test already removed earlier in the run), **every** later run, even `(+ 1 1)`, answers "This execution is unavailable … release and reacquire the context." No entrance does that; only retire. | A sticky "unavailable" flag in the context's env instead of re-acquiring from the unchanged branch head at the next run. | [a-refused-run-leaves-the-agents-context-unavailable…](../../seon/issues/a-refused-run-leaves-the-agents-context-unavailable-with-no-entrance-to-reacquire-it.md) |
| 22 **[B]** (cycle 10) | `(ns x (:require [clojure.string :as str]))` plus a `defn` using `str/` plus a caller, in one run, never settles: turn open, no error recorded, no thread working, `agent-already-running` until retire (40/45/120 s timeouts on two archives). | Same class as #4 part (b): a run that dies after recording its sources is never closed with its cause. | evidence on [an-alias-required-by-an-earlier-form-of-a-batch…](../../seon/issues/an-alias-required-by-an-earlier-form-of-a-batch-refuses-later-forms-as-unanalyzed.md) |
| 23 (cycle 10) | `clojure.test` does not work at the agent REPL: a failing `deftest` called directly prints nothing, `(run-tests)` finds 0 tests, `(run-tests 'dogfood.wc2)` says "No namespace: dogfood.wc2 found", and `(my.test/run)` ignores `*ns*`. The only way to run a test is a 2–3.6 s `my.test/check`. | The agent's `clojure.test` is the host's, walking JVM namespaces and reporting to the host. | [clojure-test-in-the-agent-repl-reports-nowhere…](../../seon/issues/clojure-test-in-the-agent-repl-reports-nowhere-and-cannot-run-an-agents-namespace.md) |
| 1 **[B]** | A branch forked before an adoption refuses **every** form after it, even `(doc help)`, with `install-row!` "Host-bound declaration … must change through the loaded source files". Default adopts several times an hour, so no agent branch survives a cycle. There is no rebase. | Acquisition treats every difference from the *loaded* program as the branch's override. The branch's changes are its diff from its fork point; the base moving means the branch is behind, not that it overrides anything. | [a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it](../../seon/issues/a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it.md) |
| 2 **[B]** | A docstring-only redefinition of `my.issue/status` gated on **1,495** tests (the subject has no callers), interpreted in SCI. 1,482 failed with `Unable to resolve symbol: support/with-database`, `testing`, `io/file`, `tc/quick-check`, … The install was refused and the report was 40 KB. On the next archive the same `defn` ran past MCP's 30 s bound. | Saturated reach used as behavioral reach, and unchanged file test bodies interpreted in the agent's SCI world instead of run as compiled Vars. | [a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused](../../seon/issues/a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused.md); evidence on [call-graph-fidelity](../../seon/issues/call-graph-fidelity-selection-awaits-adopted-proof.md) |
| 3 **[B]** | **Not reproduced in cycle 10**: `my.test/check` from a branch agent passed 4/4 with fixture-free tests. From a branch agent, `(my.test/check {})` runs 23 members; all 22 file tests error with "This write names branch :agent-…, which is not the writing cluster's branch :dogfood-repl-opus-5". | Custody is an ambient binding that the test member inherits from the calling agent; it should be injected per member world. | [a-branch-agents-test-run-inherits-its-custody-so-every-fixture-write-is-refused](../../seon/issues/a-branch-agents-test-run-inherits-its-custody-so-every-fixture-write-is-refused.md) |
| 4 **[B]** | **Fixed** (b96d6e684, verified cycle 6 and 10). `(seon.db/q '[… [?f :seon.fn/sym ?s] [(str ?s) ?t] [(re-find #"merge" ?t)]])` cannot record its read ("EDN-backed attribute `:seon.db/read-request` has an invalid logical value"). The run never settles, MCP times out, and every later run is `agent-already-running`. The agent is wedged until retire. Reproduced on two branches. | Read evidence stores a copy of the query form, which holds a host `Pattern`, instead of the query's source or digest. A recording failure leaves the run open instead of settling it as failed. | [a-query-with-a-regex-literal-cannot-record-its-read-and-leaves-the-run-open-forever](../../seon/issues/a-query-with-a-regex-literal-cannot-record-its-read-and-leaves-the-run-open-forever.md) |
| 5 | **Fixed** (verified cycle 6; cycle 10 `propose-merge!` reached root as message `2c0df2d3ea52`). A branch agent's `(my.message/send {:my.message/to "root" …})` answers a message value, but the message exists only on the branch. Root never sees it, so the agent's only path to propose a merge is silent. | Messages are treated as branch data; delivery to a recipient on another branch is not modeled. | [a-message-sent-from-a-branch-agent-never-reaches-the-cluster](../../seon/issues/a-message-sent-from-a-branch-agent-never-reaches-the-cluster.md) |
| 6 | A REPL `deftest` runs in a world with **no agent rows**. `(my.plan/current)`, the docstring example, refuses arity 0 inside a test, and an explicit `root` is "not an existing agent". Only refusal paths are testable. One-test `my.test/check` takes 2.0–3.1 s for about 130 ms of member time. | The member world is a prepared commit that does not contain the agent it runs as. | [an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented](../../seon/issues/an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented.md) |
| 7 | **Fixed** (verified cycle 10 on 6ccf12a5a and 5bed888d0). `(keys m)`, `(sort …)`, `(vals m)`, `(seq m)`, `(rseq v)`, `(seq "ab")` and arrays print as `#object[clojure.lang.ArraySeq]` and similar. `(map inc [1 2])` prints `(…?)`. | The floor admits values by an exact-class allowlist and treats a finished finite lazy seq as pending. It should classify by interface and realize up to its bound. | [the-floor-prints-ordinary-seqs-as-objects-and-finite-lazy-results-as-pending](../../seon/issues/the-floor-prints-ordinary-seqs-as-objects-and-finite-lazy-results-as-pending.md) |
| 8 | **Still open** (cycle 10: `(select-keys issue [:seon.issue/id :seon.issue/status])` printed "Issue cd6356a6245c: still open."). A plain map containing `:seon.turn/id 12` was rendered by the turn renderer. It refused, and the **evaluation** was reported as an exception (7 KB, the value printed twice). | Renderer selection by key presence, not by schema validity, and a render refusal treated as an evaluation failure. | [a-plain-value-with-an-entity-key-renders-…](../../seon/issues/a-plain-value-with-an-entity-key-renders-through-that-entitys-renderer-and-fails-the-evaluation.md) |
| 9 | On a stale branch, an error rendered through `seon.error/render-ai`, whose refusal rendered through it again, about 8 levels and 25 KB. The real cause appears only in the branch-error notice. Reproduced independently by design-repl-entry-renders (D16). | The diagnostic path re-enters the failing selector; a result's AI print should be the structural floor. | [a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound](../../seon/issues/a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound.md) |
| 10 | **Fixed** (b66e779bc; cycle 10 replies show `*out*`, e.g. `clojure.repl/source` prints "Source not found"). MCP SCI replies drop `println` output. It is recorded as `:seon.cluster.eval/output` but never shown, which is why `clojure.repl/source` "prints nothing". stderr is merged into stdout. | The MCP bridge keeps its own receipt projection, a second printer, instead of rendering the evaluation entity. | [mcp-branch-replies-drop-the-evaluations-printed-output](../../seon/issues/mcp-branch-replies-drop-the-evaluations-printed-output.md) |
| 11 | **Refusal fixed; a worse hang replaced it** (cycle 10: a `defn` using the new alias in the same run never settles; see the cycle-10 section). `(ns x (:require [clojure.string :as str]))` followed by a use of `str/` in the same run refuses the whole run as "no analysis of str … fill the cache", records it as a core fault, and shows `null` for every later form. | Batch analysis before evaluation; the REPL reads and evaluates one form at a time. | evidence on [an-alias-required-by-an-earlier-form-of-a-batch…](../../seon/issues/an-alias-required-by-an-earlier-form-of-a-batch-refuses-later-forms-as-unanalyzed.md) |
| 12 | **Still open** (cycle 10). `(do (in-ns 'my.issue) (defn …))` is refused by the definition authority. Top-level `in-ns` then `defn` in one run works. | Analysis attributes the `do` to the starting namespace. | evidence on [dynamic-in-ns-cannot-persist-definition-namespace](../../seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md) |
| 13 **[B]** | **Fixed** (cycle 10, 5bed888d0: `e-issue-count` over `seon.db/q` installs and answers `445`). An agent cannot install **any function that reads its own data**. The auto-check calls it with a generated, empty, schema-less database. `seon.db/q` then refuses ("uninstalled attribute"), and that refusal's `:seon.error/offending` holds a `datalog.parser.type.Placeholder` host object, so even the honest `[:or [:vector …] :seon.db/error-result]` contract is refused. `actual: [:seon.issue/id "0"]` and similar are the same class. | Supplied inputs are generated instead of supplied by declared schema name, and read refusals store the parser's internal value instead of the authored clause. | evidence on [auto-check-reports-the-function-symbol-as-the-actual-value](../../seon/issues/auto-check-reports-the-function-symbol-as-the-actual-value.md) |
| 14 | **Mostly fixed** (cycle 10, 5bed888d0: class, message, location and `catch` now match; still open: arglists `(nil)`, `Math/abs`, the trailing "Contract finding" line). Error output diverges from `clojure.main` for every ordinary mistake. `(/ 1 0)`, `(inc "a")` and `(throw (ex-info "boom" {:a 1}))` show as "`seon.sci.kernel/failure-value` refused … got nil nil. Fix: <message>", with no class and no `ex-data`. An arity refusal prints its arglists as `(nil)`. `ClassCastException` cannot be named in `catch`. `*ns*` has no name. Side-by-side table in the issue. | The agent's own exception is framed as a platform refusal instead of printed as a value; the shown sentence is rebuilt from a different key than the producer filled; SCI's class allowlist omits common `java.lang` exceptions. | [agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake](../../seon/issues/agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake.md) |
| 15 | **Still open, and now fatal** (cycle 10: the refused run leaves the agent unavailable for every later run; new blocker in the cycle-10 section). A run in which one form's program change is refused (`ns-unmap` of a called function) shows that refusal as **every** form's result, including `doc` and `breaks`, the refactoring data the refusal exists for. It is stored as a core fault and carries a writer stack trace. | A run settles as one transaction, so a refused effect erases the other forms' shown values; declared agent refusals are recorded as faults. | [a-refused-run-settlement-replaces-every-forms-shown-result](../../seon/issues/a-refused-run-settlement-replaces-every-forms-shown-result.md) |
| 16 | **Fixed** (5d933754f; cycle 10 `breaks` names the caller and plans an issue with the gating tests). `breaks` proposes `(my.program/launch! …)`, which does not exist. `ns-unmap!`'s documented call refuses with "got nil nil" instead of naming the caller. Its `doc` example sits in `:body` and mentions "seam B". | Plan forms are written by hand and never resolved at the producer. | [breaks-plans-name-a-launch-function-that-does-not-exist](../../seon/issues/breaks-plans-name-a-launch-function-that-does-not-exist.md) |
| 17 | **Still open** (cycle 10). `seon.schema/register!` works only as a top-level form. Inside `let` it refuses "Schema declarations require an isolated registration delta". The first run of three `register!` calls took 1,786 ms. | The registration delta is opened by syntactic recognition of a top-level form instead of being an effect of every evaluation. | [schema-registration-works-only-as-a-top-level-form](../../seon/issues/schema-registration-works-only-as-a-top-level-form.md) |
| 18 | **Still open** (cycle 10). `doc`'s `:in` prints every map entry as `[<schema form> <schema form>]`: request keys vanish, and supplied members (`:seon.db/connection`, `:seon.agent/id`) are not removed. `my.issue/add!` (empty `:example`) cannot be called from its `doc`. This is design D9, not filed before. | A hand-written `postwalk-replace` substitutes every keyword, including entry keys, instead of walking Malli's own structure (`m/walk`, `m/entries`). Cause verified at `sci/eval.clj:2234-2263`. | [doc-replaces-every-schema-keyword-so-request-keys-disappear](../../seon/issues/doc-replaces-every-schema-keyword-so-request-keys-disappear.md) |
| 19 | **Fixed** (5bed888d0, verified cycle 10). `(letfn [(f [] :ok)] (f))` fails with "No implementation of method: :getName of protocol HasName … for class: nil". `defrecord` with an inline protocol method fails with "SciUnbound cannot be cast to Associative". Plain SCI in the same JVM runs both. Records print as `#object[sci.impl.records.SciRecord]`, and `Math/abs` is unresolved. | Seon's evaluation drives SCI analysis without SCI's namespace bindings (hypothesis, unverified); the floor allowlist; `:classes` lacks `java.lang.Math`. | [letfn-and-defrecord-with-an-inline-protocol-fail-in-the-agent-repl](../../seon/issues/letfn-and-defrecord-with-an-inline-protocol-fail-in-the-agent-repl.md) |

## Smaller findings (not yet filed; one line each)

- The `eval_clj` tool description says a branch run "evaluate[s] exactly one form"; several forms work.
- The host is created once per branch. A later call with another `session_id` or `namespace` still runs as `mcp/default` in `user`, and a `namespace` argument is refused as `starting-namespace-changed`. That run's MCP reply also carries a 5 KB `datahike.writer` `:error` stack trace as an `out` event, for an ordinary declared refusal.
- `(help)` lists "Tools: my.message, my.turn, seon.bootstrap, seon.db", derived from `user`'s requires. `my.program`, `my.test`, `my.issue`, `my.plan` and `my.agent` are not mentioned. It also teaches `:out`/`:result` and "results arrive in your NEXT turn", which the MCP entrance does not show.
- Turn ids repeat across branches for the same hosted agent (`77ec74824b5d` was the first turn on four branches), so branch-error windows are hard to correlate.
- A store rebuild answered a raw `Throwable->map` with a 5 KB trace for a declared `branch-absent` store refusal.
- The PostToolUse hook blamed my `eval_clj` for another lane's half-edited `src/seon/render.clj`. No eval wrote files.
- `sci.lang.Namespace` prints `#object[sci.lang.Namespace]` with no name. `defn` now prints `#'ns/name`, so D13 is partly fixed.
- `my.program/callers` records `call-arity 0` against `declared-arities {:min 1 :max 1}` for zero-argument `my.*` calls, which call preparation completes. The new arity gate (f81a22cd8) could misjudge them.

## Known in-progress defects (routed around, not re-filed)

D3 (`my.test/check`/`run`: `check` now answers run results, and `run` answers `[]` for an agent
with no tests in its own namespace), D4 (same-run reach: confirmed again, the link appears on the
next run), the missing merge proposal function, in-run ungated redefinition, host retire's keyword
tx ref (retire worked on branches 1, 2 and 4), D6 preview writes, and D7 call preparation's head
snapshot.

## Cycle log

- **Cycle 1** (2026-09-26 02:45–03:05Z). Branches 1–4 went stale or were deleted by root's store
  rebuild. On branch 5 I added `my.plan-test/current-refuses-an-unknown-agent-by-name`, which is
  coverage for `my.plan/current`, a subject that had 0 reaching tests. It passes with 2
  assertions (`my.test/check`, run `7ef033023a77`). The merge proposal was blocked by the missing
  merge function (known). The fallback, a message to root, is branch-local (#5). Write-back has no
  agent entrance: no public `my.*` or `seon.*` function names write-back or merge, and the search
  query itself wedged the agent (#4). Branches 1, 2 and 4–6 are retired. Branch 3 was deleted by
  root's store rebuild. **The change was not merged**; its source is in the report, for root.

- **Cycle 2** (03:10–03:15Z, branch `dogfood-repl-opus-7`, archive 10d6fdff5, no restart). The loop
  worked end to end *inside the branch*. I defined `user/word-count`, wrote a REPL `deftest` (it reached
  on the next run), and `(my.test/run)` passed 3/3. I then redefined `word-count` with a bug; the gate
  refused it (1 test, 2 failures, 25/25 auto-check) and kept the old definition. Merge and write-back
  still have no agent entrance. The branch is retired.

  **Where the seconds go** (turn open→close from the branch's own turn rows; `my.test` figures from its
  result; profile from the agent's own `seon.profile/begin`/`explain`). MCP wall time is not visible to
  the agent.

  | Step | Time | Notes |
  |---|---|---|
  | orient: `(doc my.note/add!)` + two `add!` + `notes` | 645 ms | one `add!` is 45 ms (a single `transact!`) |
  | orient: `(dir seon.db)` | 196 ms | 34,850 characters |
  | change: `defn` + three calls | 252 ms | |
  | test: `deftest` | 138 ms | reaches on the next run (D4) |
  | test: first `(my.test/run)` | **3,909 ms** | a later identical run took 253 ms, 247 of them in `seon.db/q` ×20 inside `seon.test/select` |
  | test: `(my.test/check {})`, one test | **1,555 / 1,730 / 1,720 ms** | member 71 ms; `with-declarations` ×13,680 = 1,119 ms, `clean-request-handle` 981 ms, `reused-count 0` every time ([evidence](../../seon/issues/test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads.md)) |
  | change: gated redefinition (refused) | 240 ms | 1 test and 25 auto-checks: fast |
  | merge attempt | — | no entrance (known) |
  | retire | not measurable from the agent | succeeded |

  Also seen: an install refusal is not flagged `exception` in the MCP reply, so tooling reads it as success.
  Its failure locations read `fail AFn.java:154`, not the test's line. `(my.note/notes)` shows
  `:my.note/agent 34185`, a raw entity id. `(throw (ex-info "boom" {:a 1}))` loses its class and `ex-data`
  ([evidence](../../seon/issues/repl-parity-divergences.md)). `subvec` prints `#object` (evidence on #9).

- **Cycle 3** (03:13–03:20Z, branch `dogfood-repl-opus-8`, archive 10d6fdff5, no restart). The program
  deletion loop works when used in order: `breaks` names the caller, I redefined the caller, then
  `(my.program/ns-unmap! 'user/helper)` removed the row and the Var, and `(caller 1)` answered `3`. Error
  parity was compared against `clojure.main` 1.12.5 (#14). Turn times: first run on the new branch (two
  `defn`s, one call, `dir`) **1,102 ms**; the refused-deletion run **1,904 ms**; the successful `ns-unmap!`
  run 868 ms; everything else 74–339 ms. The branch is retired.
  The require-then-use batch failure (#11) blocks the most common Clojure idiom. Once, a `require` made
  in an earlier run whose other forms had failed was still unknown to the next run's analysis. A fresh
  `require` alone then worked, and I could not isolate the cause.

- **Cycle 4** (03:15–03:23Z, branches `dogfood-repl-opus-9` and `-10`). Agent data modeling works:
  `register!` of id, title and an entity map, then `transact!`, then `pull` round-trips. It needed
  `:seon.program/partition :seon.data`, which the refusal did not name (the member was dropped, evidence on
  #14). A data-reading function over that schema could not be installed (#13, raised to a blocker).
  Branch 9 went stale after root's restart onto 04f3a159b (blocker #1, as expected). I retired it and
  continued on 10, which is also retired. Also noticed: `malli.core` is not resolvable from agent code,
  so an agent cannot validate a value against a named schema at the REPL. `runtime_status` reports
  `missing-layers []` while SCI still refuses "Development adoption … changed loaded definitions without
  recording their agreement", so readiness does not cover adoption. Turn times: 3× `register!` 1,786 ms
  (first run on the branch); `transact!` of 2 books plus the refused `defn` 483 ms; reads 93–168 ms.

- **Cycle 5** (03:20–03:26Z, branch `dogfood-repl-opus-11`, archive 04f3a159b). The hosted agent is now named
  after its branch (`mcp/dogfood-repl-opus-11`), so the earlier "one host per branch ignores `session_id`"
  finding is moot. The `my.plan` docstring example ran exactly as written (`add!` then `complete!`) and
  answered `:my.plan/state :completed`. But `(my.plan/plan)`'s rendered `:seon.plan/update-example` then
  proposed `(my.plan/current! {:my.plan.item/id "verify-arithmetic"})` for that **completed** step, which
  refuses "an open step … is required". The render suggests an action its own function refuses. The
  refusal also carried the writer's stack trace into the reply (evidence on
  [expected-refusal-logs-raw-datom-error-twice](../../seon/issues/expected-refusal-logs-raw-datom-error-twice.md)).
  `:example` strings lose their indentation. `my.issue/add!` has no example, and its `doc` is unreadable
  (#18). The branch is retired.

- **Cycle 6** (03:22–03:27Z, branch **`dogfood-repl-opus-12`, kept alive for root's merge**, archive 04f3a159b).
  - **Verified fixed on the live default:** the regex query (#4) answers `10` and does not wedge the agent
    (b96d6e684). A branch agent's message now reaches the cluster (#5): the proposal below is entity
    34655 on the cluster branch.
  - **Milestone step reached:** I re-made the cycle-1 test on this branch, authored an issue for it with
    `(my.issue/add! …)`, written from its source because its `doc` is unreadable (#18), and ran
    `(my.test/check {:seon.test/changed [...]})`, which passed 2/2 in 2,056 ms (run `2d6b17831736`). Then
    `(my.program/propose-merge! {:seon.issue/id "7bae492de6db"})` delivered message `276dd9433eb3` to root.
  - **Open questions for the merge:**
    1. Issue `7bae492de6db` exists only on the branch. The cluster has no row for it, and the
       merge-test name says "a merge naming a task the destination lacks refuses".
    2. The message's `:seon.message/content` is a `pr-str` string of a map, not the map.
  - `seon.cluster.source/write-back!` and `prepare-merge!` have no `:example`, and their `:summary` stops
    mid-sentence at the first line break ("Write the function and test changes of `accepted`
    (`accept-merge!`'s report"). An agent cannot preview write-back, because it is root's step after
    `accept-merge!`.

- **Cycle 7** (03:27–03:30Z, branch `dogfood-repl-opus-13`, retired). `my.fs/stat`, `read` and `glob` behave
  exactly as their docs say. A glob that omits `:my.fs/root` refuses with a good example.
  Printing budgets differ by shape. `(vec (range 5000))` shows 32 members (`max-children`, about 100
  characters), while `(apply str (repeat 300000 "x"))` shows about 48,000 characters (`…+252144`), roughly
  12k tokens of `x` in the agent's context. A nested map 8 levels deep printed whole. This is consistent
  with one token budget and a separate child bound, but a lone large string costs the agent the whole
  budget. I did not file it; it is for the floor lane to judge.

- **Cycle 8** (03:30–03:33Z, branch `dogfood-repl-opus-14`, retired).
  - The `my.background` example works: a background `printf` finished in 67 ms. But `poll` answers
    `:seon.effect/request-edn` and `:seon.effect/result-edn` as **EDN strings**, so the agent must parse
    its own result (evidence on
    [capability-component-arguments-stay-in-request-edn](../../seon/issues/capability-component-arguments-stay-in-request-edn.md)).
  - `dir` lists plumbing as agent API: `my.edit/form-request-generator` ("This program row carries no
    :seon.fn/doc."), `my.fs/write-precondition-generator`, `my.fs/content-generator`,
    `my.shell/stdin-generator`, and `my.turn/usage-form` (`:seon.fn/internal? true`).
  - **Design question for root:** `my.edit/form!`, `exact!` and `lines!` let an agent edit source
    *files*, which contradicts "files are the write-back of accepted rows, not the working medium" and
    "product agents … never shell self-modification". I did not call them.

- **Cycle 9** (03:44–03:48Z, branch `dogfood-repl-opus-15`, retired). A Clojure breadth sweep: atoms,
  multimethods, protocols with `extend-protocol`, `deftype`, transducers, `format`, regex and metadata work.
  `letfn` and `defrecord` with inline protocol methods fail (#19). Default stayed on 04f3a159b for 25
  minutes, so the committed fixes are not yet verifiable live.

- **Cycle 10** (14:10–14:40Z, lane dogfood-repl-opus-2, branches `dogfood-repl-opus-2-c4`, `-c4b`, `-c4c`, `-c10`,
  `-c10b`, `-c10c`, `-c10d`, all retired). Default ran 6ccf12a5a, then root replaced it with 5bed888d0 at 14:29Z.
  - **Re-verification.** See the verdicts in the ranked table: #4, #5, #7, #10, #13, #16 and #19 are fixed; #14 is mostly
    fixed; #11's refusal became the hang #22; #8, #12, #15, #17 and #18 are still open. Cycles 4–9: the data-reading
    function (cycle 4, #13) now installs; `malli.core` is still unresolvable from agent code; `(help)` still lists "Tools:
    my.message, my.turn, seon.bootstrap, seon.db" and still teaches "results arrive in your NEXT turn"; `dir my.program`
    still lists plumbing (`native-call-refusal`, `supplied-context`); `dir my.test` shows `my.test/run`'s `:in` and `:out`
    as the error "The program row lacks a declaration … :seon.fn/spec"; a merge proposal's `:seon.message/content` is still
    a `pr-str` string. Not re-verified this cycle: the plan render (cycle 5), `my.background` (cycle 8), #1, #2, #6, #9.
  - **The realistic change** (a word-counting namespace: helper, tokenizer, frequencies, top-n; two `deftest`s; a gated bad
    redefinition; a helper deleted with `ns-unmap!`; an issue; a merge proposal) took **seven branches**. Three were lost to
    blockers: the alias hang (#22, twice) and the context left unavailable by a refused run (#21). The fourth attempt lost
    `top-words` to name-order installation (#20). It finished only after renaming the functions `a-blank?`, `b-tokens`,
    `c-frequencies` and `d-top-words`, which no Clojure developer would do. What worked well: the gate refused a bad
    redefinition in one run with a precise diff (`expected: (= {"a" 2, "b" 1} …) actual: … {"a" 3, "b" 2}`); `breaks` named
    `tokens` as the caller and planned an issue with both gating tests; `ns-unmap!` removed the helper and the Var once the
    caller no longer used it; an identical re-check reused green in 146 ms; `propose-merge!` delivered message
    `2c0df2d3ea52` to root. Its `:seon.test/changed` omits the deleted `a-blank?`, so a deletion is not part of the
    proposed change. The issue could not cite the branch's own functions or tests (`require-test-refs!` refused
    `[:seon.test/sym dogfood.wc2/c-frequencies-counts-words]` as "expected … test entities", because the issue is written
    on the cluster, which lacks them). **Not merged**, per the assignment; the branch is retired, so proposal
    `2c0df2d3ea52` names a retired branch.
  - **Turn times** on `-c10d` (5bed888d0; turn open→close from the branch's own rows):

    | Step | ms |
    |---|---|
    | `ns` + four `defn`s (first run on the branch) | **1,879** |
    | two `deftest`s | 459 |
    | `my.test/check`, 2 tests, 4 assertions | **2,536** (check itself 2,400: `clean-request-handle` 1,442, `transact!` ×4 1,417) |
    | redefine `b-tokens` (gated) | 266 |
    | `ns-unmap!` `a-blank?` | 427 |
    | `ns-publics` + call | 254 |
    | `my.issue/add!` (three attempts) | 110 / 100 / 138 |
    | `propose-merge!` | 212 |
    | `clojure.repl/apropos` ×7 | 115 |
    | `callers` + `tests-reaching` + `overrides` + `doc` | 170 |
    | failing `deftest` + direct call + `run-tests` | 785 |

    On 6ccf12a5a the same kinds of runs were far slower: three `defn`s whose auto-check found a real contract bug took
    **119.6 s**, the next three `deftest`s **156.6 s**, and a check that selected no tests 17.8 s (the JVM was
    replaced a minute later). Every `my.test/check` measured this cycle took 2.0–3.6 s.
  - **Method note.** Besides the agent REPL, I used JVM-mode `eval_clj` only for read-only diagnosis: thread dumps, and
    turn and evaluation rows of my own branches read with `seon.db/branch-connection`.
  - **Clojure parity notes.** `(ns …)` answers `nil` as at clojure.main; `defn` prints `#'ns/name`, preceded by an `out`
    advisory "No example test gates …" even when the `deftest` is the next form of the same run. `apropos` and `find-doc`
    are not referred in `user` (clojure.main refers them). `clojure.repl/source` of an agent function prints "Source not
    found", although `my.program/history` holds the exact source. An install refusal is still not flagged `exception`,
    and its failure location still reads `fail AFn.java:154`.

### Cycle 1 change (proposed in cycle 6 from `dogfood-repl-opus-12`, message `276dd9433eb3`, issue `7bae492de6db`): coverage for `my.plan/current`

```clojure
(in-ns 'my.plan-test)
(clojure.test/deftest current-refuses-an-unknown-agent-by-name
  (let [refusal (my.plan/current {:seon.db/db (seon.db/db) :seon.agent/id "an-agent-that-does-not-exist"})]
    (clojure.test/is (= "an-agent-that-does-not-exist" (:my.plan/missing-agent-id refusal)))
    (clojure.test/is (= :seon.agent/id (:my.plan/refused-member refusal)))))
```

It passed on branch `dogfood-repl-opus-5` (`my.test/check`, run `7ef033023a77`, 2 assertions, 2.3 s end to
end). The natural test, `(is (= {} (my.plan/current)))`, is untestable today (#6).

## What else agents need to write software (owner question, answered from cycle 10)

> "What else do agents need to be able to write software, if the system is composed only of functions, schemas, tests,
> and creating/removing/overwriting them?"

The create/overwrite/remove primitives are nearly enough **when they are reliable**, and in cycle 10 they were not: rows
that disappear (#20), runs that never settle (#22) and a context that cannot be recovered (#21) cost far more than any
missing verb. Fix those first. After that, each entrance below was tried from a branch agent on 5bed888d0; "missing"
means I found no callable function by `clojure.repl/apropos` and `dir`, and the named attempt failed.

**Present and working**

| Need | Entrance | Evidence |
|---|---|---|
| Who calls this? | `(my.program/callers {:seon.program/subject 'dogfood.wc2/c-frequencies})` | callers, call sites and arities |
| What breaks if I delete it? | `(my.program/breaks {:seon.program/subject …})` | named `tokens`, planned an issue with the gating tests |
| Which tests cover it? | `(my.program/tests-reaching {:seon.program/subject …})` | `:seon.program/gating` lists both tests |
| Every past definition | `(my.program/history {:seon.program/subject …})` | source and definition per transaction |
| Delete a function or namespace | `my.program/ns-unmap!`, `my.program/remove-ns!` | the helper and its Var removed |
| Check a change | `(my.test/check {:seon.test/changed [...]})` | works, but 2.0–3.6 s |
| Ask for a merge | `(my.program/propose-merge! {:seon.issue/id …})` | message delivered to root |
| Read any entity of a schema | `seon.db/q` and `seon.db/pull` | generic Datalog; no per-schema shortcut, which is fine |

**Missing, ranked by how much they block ordinary work**

1. **Run one test, or the tests of `*ns*`, and see the report.** `clojure.test` reports nowhere and cannot find agent
   namespaces; `my.test/run` ignores `*ns*` (#23). Every test run is a 2–3.6 s `my.test/check` in a fresh world. This
   is the inner loop of writing software.
2. **Diff my branch against its base.** `(my.program/overrides {})` answered `[]` with five new rows on the branch;
   `seon.db/diff` compares shown values; nothing else matched "diff". The proposal's own change list omitted a deletion,
   so neither the agent nor root sees the change as data.
3. **Recover or discard my own state from the REPL.** No agent entrance resets the context to the branch head ("release
   and reacquire the context", #21), resets the branch to its base, or retires it. `seon.cluster.host/retire!` and
   `seon.cluster.registry/retire-branch!` exist but are not the agent's custody. The MCP `branch` tool is the only exit.
4. **Validate a value against a named schema.** `malli.core/validate` and `seon.schema/validate` are unresolvable, so an
   agent cannot test its data against the schema its contract names before calling a function.
5. **Read my function's source.** `clojure.repl/source` says "Source not found"; `my.program/history` has it, but
   `source` is where a Clojure developer looks.
6. **Rename a function, updating its callers and tests.** Nothing matched "rename" except `clojure.set/rename`. Today a
   rename takes a new `defn`, one run per caller, and an `ns-unmap!`; with #20, the new name can even change whether it
   installs.
7. **Move a function between namespaces.** Missing; same manual sequence, plus requires in every caller's namespace.
8. **What does this function call?** The row has `:seon.fn/calls`, but no function reads it; `callers` has no inverse.
9. **Undo or revert to a previous definition.** Nothing matched "revert"; the way is copying the source from `history`
   and evaluating it again.
10. **Cite my branch's new functions and tests in the issue I merge under.** `my.issue/add!` refuses them, because the
    issue lives on the cluster, which does not have them yet.
11. **`apropos` and `find-doc` referred in `user`,** as at clojure.main. The qualified forms work.
