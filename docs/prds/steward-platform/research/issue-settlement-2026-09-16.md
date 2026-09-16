---
type: research
status: awaiting owner decision
created: 2026-09-16
tags: [research, database, agent, test]
---

# Issue settlement: writer design falsified before production edits

P5 and P6 are **not implemented by this lane**. The proposed P6 construction
has two independently reproduced holes. This is the assignment's pre-edit
design gate, not a stop caused by another lane's failing test or protected file.

The required guarantee is: **each settlement runs the issue's nonempty success
test set under one bound, and the writer preserves that set against worker
removal while deriving step and issue completion from current verified runs.**

## Authorities and baseline

Read end to end: AGENTS.md, docs/seon/issues/README.md, its localized
AGENTS.md, the issue-family spec, the namespace data model (including §9),
the issue-family landing note, and its linked ownership issue and the new
writer-integration issue. Read the class-mining structural-kill tables:
this specific assignment is P5/P6 of the September issue-family spec, not
August class P5 (dependency pins). It names no August class issue/member
roster; no unrelated class closure is claimed.

Also read the current roadmap entry and working edge. Loaded
data-oriented-clojure, datahike, repl, clojure-testing,
seon-flow-architecture and data-modeling.

Entry HEAD was `28038838c`, branch `steward-platform`. HEAD advanced to
`c4bfd51e8` during read-only investigation. All foreign edits were preserved.
At the final inspection issue.clj had 7 added lines, and turn.clj had
42 added / 13 removed lines belonging to other work. Neither was edited.

`bin/seon status`: default PID 7595 alive, prepl 51919, web 7994; no orphan
Seon JVMs. MCP runtime status refused the occurrence-count contract at
signature index 4. The already in-flight
[status issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md)
was preserved. Direct JVM evaluation with explicit default custody worked.

## Dependency ledger

- Datahike gitlink: `cdcb5792db8bd599487f099437265d18a31164a5`.
  `reference-code/datahike/src/datahike/db.cljc:198` converts indices to
  transients. The transaction loop at
  `reference-code/datahike/src/datahike/db/transaction.cljc:1230`
  enters that transient database; line 1153 applies transaction functions
  to the current value. Expansion is spliced before subsequent operations.
- `src/seon/db.clj:2810` preflights assertions; `transact-call` submits
  encoded data at line 2832. It has no declared retention guard.
- `src/seon/issue.clj:327` contains the interim green-after-assignment
  query; `start-tx` at line 355 authors it on the plan step.
  `add-tx` does not record a creator.
- `src/seon/plan.clj:520` derives the query deadline;
  `completion-tx` at line 557 writes step completion only.
  `settle-call` evaluates query-backed steps at line 564.
- The four turn settlement call sites were found by their Var references,
  at lines 2205, 3424, 3463 and 4643 at inspection. One is inside the pure
  evaluation-terminal-data builder; execution must remain outside its
  transaction data, not be inserted into the transaction function.
- `src/seon/test.clj:81` already runs one host or SCI test Var with a
  supplied remaining-time option; the result goes through
  `seon.test.runner/commit-results!`, then `record-tx`, the existing
  completion writer. `verified?` has the database/test arity in both
  source and live metadata and compares the current reach digest.
- `src/seon/sci/eval.clj:900` installs committed evaluated rows.
  Its candidate-test path at line 2604 demonstrates resolving a SCI
  test Var and arming the SCI execution bound. P5 must execute the
  agent's actual program; resolving only a host namespace would not prove
  a function admitted through SCI.

## Live probes and per-member verdict

The exact successful MCP requests are committed in
[issue-settlement-probes-2026-09-16.edn](issue-settlement-probes-2026-09-16.edn).
Replay each through MCP; the first two use namespace `seon.db`, mode
`jvm`, and Datahike `with`. They do not transact the speculative changes.
One initial two-transaction form had an unmatched delimiter and was rejected
before evaluation; the saved request is the corrected successful form.

At basis 536871615, the live schema had neither
`:seon.db/append-only-after` nor `:seon.issue/created-by`. The one assigned
issue was `agent-form-calls-to-core-namespaces-are-not-indexed`, entity
43695, agent `12254041a057`, test entity 45221:
`seon.test-reaching-test/agent-admitted-tests-reach-their-tested-function`.

### P6: retaining a database value does not preserve its before state

A transaction function captured its database, a later operation changed the
issue title, and the final function pulled the title from both values.
Complete result (7 ms):

```clojure
{:issue 43695
 :original-title "Historical call-edge analyses need re-evaluation"
 :observation
 {:before-title "issue-settlement speculative probe"
  :after-title "issue-settlement speculative probe"
  :same-value? false}
 :committed-title "Historical call-edge analyses need re-evaluation"}
```

Thus the proposed final guard would compare the changed indices with
themselves. This is dependency behavior, not a Datahike defect. Immutable
materialized guard inputs, or a dependency-owned final-report seam, are needed.

### P6: a before-or-after assignment check is insufficient across transactions

A second probe used materialized test sets. It speculatively removed assignment
while keeping the test, then removed tests in a second transaction. The
proposed predicate accepted both (7 ms):

```clojure
{:issue 43695
 :before {:active? true :tests #{45221}}
 :after-first {:active? false :tests #{45221}}
 :after-second {:active? false :tests #{}}
 :first-accepted? true
 :second-accepted? true
 :committed {:active? true :tests #{45221}}}
```

The creator exception adds another authority fact to preserve: a worker must
not replace the creator and then use that identity to retract tests. That
consequence is design reasoning, not a completed creator-substitution probe;
the creator attribute is not installed.

P6 remains open. The additional evidence is recorded in
[the guard-design issue](../../../seon/issues/issue-test-guard-before-value-and-activation-are-not-stable.md).
The original writer-integration issue was an in-flight untracked file owned
by issue-family and was not edited or closed.

### P5: existing run primitive present; settlement integration absent

Source inspection confirms no automatic issue-test execution at the four
settlement sites and no issue resolution fact in the completion transaction.
The live issue remains unresolved. Its required test's existing result was
5 passes / 2 failures / 0 errors; this lane did not rerun or change that
test, its program, its worker, or its issue.

The existing canonical worker-creation regression was run twice, serially,
through precisely:

```clojure
(seon.test/run
 (requiring-resolve 'seon.issue-test/issue-worker-creation-is-atomic)
 (seon.operator/connection "default"))
```

| Run time UTC | Run entity | Basis | Result | MCP elapsed |
|---|---:|---:|---|---:|
| 2026-09-16 02:46:25 | 66104 | 536871615 | 0 pass / 0 fail / 1 error | 23,257 ms |
| 2026-09-16 02:49:48 | 66126 | 536871632 | 0 pass / 0 fail / 1 error | 22,915 ms |

Both recorded the same explicit 20,000 ms completion-bound failure, not a
passing zero-test result. The second run tested whether another invocation
would complete after the first; it did not. No cause is attributed.
Run 66104 was independently pulled: id `e1091bc251cb`, branch
`cluster-default`, program digest
`88ae88f416a1fe63b2a67051a78ee781ed2a4e7ec61417f25d75bf20ead92382`.
Both results carried reach digest
`bbefd5ada6285dde633afe030aa88990a09ef6cb42014cf8be8f11a6791ba67c`.

The unexplained timeout is tracked in
[its own issue](../../../seon/issues/issue-worker-creation-in-process-test-exceeds-bound.md).

These are baseline runs, not P5/P6 regressions or proof of their implementation.

## Three priced options

Estimates are engineering effort, not measured runtime. The prior owner
authorization of the listed files is accepted; the new decision concerns
the invariant's supporting facts and transaction validation construction.

1. **Materialize guarded values and preserve their authority — recommended.**
   At the existing database writer, derive guards from schema, capture
   immutable values before expansion, and validate final values. Workers
   cannot retract assignment or replace creator authority; creators may
   remove tests while leaving a nonempty set. Unknown creators on existing
   indexed issues receive no inferred exemption. Then implement P5 using
   the existing test runner and completion seam.
   Guarantee: worker writes cannot remove the tests or disable the facts
   enforcing retention, including across separate transactions.
   Cost: approximately 6–10 hours across database, issue, plan/turn and
   canonical tests. Give up: worker-driven unassignment/creator reassignment;
   straightforward snapshotting also scans the declared guarded population
   per transaction and needs measured cost before acceptance.

2. **Expose final transaction validation at the maintained Datahike owner.**
   Validate the writer's immutable before value and final expanded transaction
   report, using affected datoms rather than rescanning guarded populations.
   Keep the same creator/assignment rule, then integrate P5.
   Guarantee: identical retention with validation driven by actual expanded
   effects and no second evaluation of transaction functions.
   Cost: approximately 1–2 engineer-days including dependency regression,
   pin update and integration. Give up: the current lane's first-party-only
   ownership and independent landing.

3. **Land P5 separately and explicitly retain P6 as open.**
   Run tests with recorded evidence before settlement and write issue
   resolution with plan completion; retain only the additive issue API.
   Guarantee: automatic completion derives from current verified runs for
   the currently declared set.
   Cost: approximately 3–5 hours plus the baseline-test diagnosis.
   Give up: the requested non-removal guarantee; this cannot close the
   writer-integration issue or complete this assignment.

The stop is required by the assignment's explicit hours-of-cross-owner-work
design gate and AGENTS.md §2.5. No skill added an approval requirement.
Choosing option 1 authorizes the expanded assignment/creator lifecycle rule;
it is not a request to re-authorize files already assigned to this lane.

## Verification boundary and gate request

No production Var was redefined and no production/schema/test file edited.
No implementation slice or class regression is claimed. No member/class
note is closed. Both P5 and P6 remain required.

No test JVM, bin/test, bin/test-fast, scratch cluster, worktree or background
shell was launched. Default was never stopped, reforked or restarted.
The only committed live mutations from this lane are the two ordinary test
results. Speculative title, assignment and test-ref changes were verified
absent from the live issue.

**Gate request: not ready.** After the chosen implementation passes the
required in-process tests before and after adoption, the orchestrator should
run one path-limited affected-namespace gate and then the platform gate,
serially. There is no production slice to submit to a gate now.

`git diff --check` passed. A live read verified all three saved MCP request
forms are readable. Markdown hook feedback reported
12 issues and displayed stale dependency-pin citations in the existing
agents-md-audit note; the remaining feedback was elided, so no complete
cause attribution is made. Initial Clojure-script packaging lint was
removed by storing exact replayable MCP request data in EDN instead.
