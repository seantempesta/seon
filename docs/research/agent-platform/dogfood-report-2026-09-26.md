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
| 1 **[B]** | A branch forked before an adoption refuses **every** form after it, even `(doc help)`, with `install-row!` "Host-bound declaration … must change through the loaded source files". Default adopts several times an hour, so no agent branch survives a cycle. There is no rebase. | Acquisition treats every difference from the *loaded* program as the branch's override. The branch's changes are its diff from its fork point; the base moving means the branch is behind, not that it overrides anything. | [a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it](../../seon/issues/a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it.md) |
| 2 **[B]** | A docstring-only redefinition of `my.issue/status` gated on **1,495** tests (the subject has no callers), interpreted in SCI. 1,482 failed with `Unable to resolve symbol: support/with-database`, `testing`, `io/file`, `tc/quick-check`, … The install was refused and the report was 40 KB. On the next archive the same `defn` ran past MCP's 30 s bound. | Saturated reach used as behavioral reach, and unchanged file test bodies interpreted in the agent's SCI world instead of run as compiled Vars. | [a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused](../../seon/issues/a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused.md); evidence on [call-graph-fidelity](../../seon/issues/call-graph-fidelity-selection-awaits-adopted-proof.md) |
| 3 **[B]** | From a branch agent, `(my.test/check {})` runs 23 members; all 22 file tests error with "This write names branch :agent-…, which is not the writing cluster's branch :dogfood-repl-opus-5". | Custody is an ambient binding that the test member inherits from the calling agent; it should be injected per member world. | [a-branch-agents-test-run-inherits-its-custody-so-every-fixture-write-is-refused](../../seon/issues/a-branch-agents-test-run-inherits-its-custody-so-every-fixture-write-is-refused.md) |
| 4 **[B]** | `(seon.db/q '[… [?f :seon.fn/sym ?s] [(str ?s) ?t] [(re-find #"merge" ?t)]])` cannot record its read ("EDN-backed attribute `:seon.db/read-request` has an invalid logical value"). The run never settles, MCP times out, and every later run is `agent-already-running`. The agent is wedged until retire. Reproduced on two branches. | Read evidence stores a copy of the query form, which holds a host `Pattern`, instead of the query's source or digest. A recording failure leaves the run open instead of settling it as failed. | [a-query-with-a-regex-literal-cannot-record-its-read-and-leaves-the-run-open-forever](../../seon/issues/a-query-with-a-regex-literal-cannot-record-its-read-and-leaves-the-run-open-forever.md) |
| 5 | A branch agent's `(my.message/send {:my.message/to "root" …})` answers a message value, but the message exists only on the branch. Root never sees it, so the agent's only path to propose a merge is silent. | Messages are treated as branch data; delivery to a recipient on another branch is not modeled. | [a-message-sent-from-a-branch-agent-never-reaches-the-cluster](../../seon/issues/a-message-sent-from-a-branch-agent-never-reaches-the-cluster.md) |
| 6 | A REPL `deftest` runs in a world with **no agent rows**. `(my.plan/current)`, the docstring example, refuses arity 0 inside a test, and an explicit `root` is "not an existing agent". Only refusal paths are testable. One-test `my.test/check` takes 2.0–3.1 s for about 130 ms of member time. | The member world is a prepared commit that does not contain the agent it runs as. | [an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented](../../seon/issues/an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented.md) |
| 7 | `(keys m)`, `(sort …)`, `(vals m)`, `(seq m)`, `(rseq v)`, `(seq "ab")` and arrays print as `#object[clojure.lang.ArraySeq]` and similar. `(map inc [1 2])` prints `(…?)`. | The floor admits values by an exact-class allowlist and treats a finished finite lazy seq as pending. It should classify by interface and realize up to its bound. | [the-floor-prints-ordinary-seqs-as-objects-and-finite-lazy-results-as-pending](../../seon/issues/the-floor-prints-ordinary-seqs-as-objects-and-finite-lazy-results-as-pending.md) |
| 8 | A plain map containing `:seon.turn/id 12` was rendered by the turn renderer. It refused, and the **evaluation** was reported as an exception (7 KB, the value printed twice). | Renderer selection by key presence, not by schema validity, and a render refusal treated as an evaluation failure. | [a-plain-value-with-an-entity-key-renders-…](../../seon/issues/a-plain-value-with-an-entity-key-renders-through-that-entitys-renderer-and-fails-the-evaluation.md) |
| 9 | On a stale branch, an error rendered through `seon.error/render-ai`, whose refusal rendered through it again, about 8 levels and 25 KB. The real cause appears only in the branch-error notice. Reproduced independently by design-repl-entry-renders (D16). | The diagnostic path re-enters the failing selector; a result's AI print should be the structural floor. | [a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound](../../seon/issues/a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound.md) |
| 10 | MCP SCI replies drop `println` output. It is recorded as `:seon.cluster.eval/output` but never shown, which is why `clojure.repl/source` "prints nothing". stderr is merged into stdout. | The MCP bridge keeps its own receipt projection, a second printer, instead of rendering the evaluation entity. | [mcp-branch-replies-drop-the-evaluations-printed-output](../../seon/issues/mcp-branch-replies-drop-the-evaluations-printed-output.md) |
| 11 | `(ns x (:require [clojure.string :as str]))` followed by a use of `str/` in the same run refuses the whole run as "no analysis of str … fill the cache", records it as a core fault, and shows `null` for every later form. | Batch analysis before evaluation; the REPL reads and evaluates one form at a time. | evidence on [an-alias-required-by-an-earlier-form-of-a-batch…](../../seon/issues/an-alias-required-by-an-earlier-form-of-a-batch-refuses-later-forms-as-unanalyzed.md) |
| 12 | `(do (in-ns 'my.issue) (defn …))` is refused by the definition authority. Top-level `in-ns` then `defn` in one run works. | Analysis attributes the `do` to the starting namespace. | evidence on [dynamic-in-ns-cannot-persist-definition-namespace](../../seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md) |
| 13 **[B]** | An agent cannot install **any function that reads its own data**. The auto-check calls it with a generated, empty, schema-less database. `seon.db/q` then refuses ("uninstalled attribute"), and that refusal's `:seon.error/offending` holds a `datalog.parser.type.Placeholder` host object, so even the honest `[:or [:vector …] :seon.db/error-result]` contract is refused. `actual: [:seon.issue/id "0"]` and similar are the same class. | Supplied inputs are generated instead of supplied by declared schema name, and read refusals store the parser's internal value instead of the authored clause. | evidence on [auto-check-reports-the-function-symbol-as-the-actual-value](../../seon/issues/auto-check-reports-the-function-symbol-as-the-actual-value.md) |
| 14 | Error output diverges from `clojure.main` for every ordinary mistake. `(/ 1 0)`, `(inc "a")` and `(throw (ex-info "boom" {:a 1}))` show as "`seon.sci.kernel/failure-value` refused … got nil nil. Fix: <message>", with no class and no `ex-data`. An arity refusal prints its arglists as `(nil)`. `ClassCastException` cannot be named in `catch`. `*ns*` has no name. Side-by-side table in the issue. | The agent's own exception is framed as a platform refusal instead of printed as a value; the shown sentence is rebuilt from a different key than the producer filled; SCI's class allowlist omits common `java.lang` exceptions. | [agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake](../../seon/issues/agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake.md) |
| 15 | A run in which one form's program change is refused (`ns-unmap` of a called function) shows that refusal as **every** form's result, including `doc` and `breaks`, the refactoring data the refusal exists for. It is stored as a core fault and carries a writer stack trace. | A run settles as one transaction, so a refused effect erases the other forms' shown values; declared agent refusals are recorded as faults. | [a-refused-run-settlement-replaces-every-forms-shown-result](../../seon/issues/a-refused-run-settlement-replaces-every-forms-shown-result.md) |
| 16 | `breaks` proposes `(my.program/launch! …)`, which does not exist. `ns-unmap!`'s documented call refuses with "got nil nil" instead of naming the caller. Its `doc` example sits in `:body` and mentions "seam B". | Plan forms are written by hand and never resolved at the producer. | [breaks-plans-name-a-launch-function-that-does-not-exist](../../seon/issues/breaks-plans-name-a-launch-function-that-does-not-exist.md) |
| 17 | `seon.schema/register!` works only as a top-level form. Inside `let` it refuses "Schema declarations require an isolated registration delta". The first run of three `register!` calls took 1,786 ms. | The registration delta is opened by syntactic recognition of a top-level form instead of being an effect of every evaluation. | [schema-registration-works-only-as-a-top-level-form](../../seon/issues/schema-registration-works-only-as-a-top-level-form.md) |

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

### Cycle 1 change (unmerged): coverage for `my.plan/current`

```clojure
(in-ns 'my.plan-test)
(clojure.test/deftest current-refuses-an-unknown-agent-by-name
  (let [refusal (my.plan/current {:seon.db/db (seon.db/db) :seon.agent/id "an-agent-that-does-not-exist"})]
    (clojure.test/is (= "an-agent-that-does-not-exist" (:my.plan/missing-agent-id refusal)))
    (clojure.test/is (= :seon.agent/id (:my.plan/refused-member refusal)))))
```

It passed on branch `dogfood-repl-opus-5` (`my.test/check`, run `7ef033023a77`, 2 assertions, 2.3 s end to
end). The natural test, `(is (= {} (my.plan/current)))`, is untestable today (#6).
