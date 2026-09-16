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

**Initial gate request: not ready.** After the chosen implementation passes the
required in-process tests before and after adoption, the orchestrator should
run one path-limited affected-namespace gate and then the platform gate,
serially. There is no production slice to submit to a gate now.

`git diff --check` passed. A live read verified all three saved MCP request
forms are readable. Markdown hook feedback reported
12 issues and displayed stale dependency-pin citations in the existing
agents-md-audit note; the remaining feedback was elided, so no complete
cause attribution is made. Initial Clojure-script packaging lint was
removed by storing exact replayable MCP request data in EDN instead.

## Resumed decision and slice 1 — P5

The owner chose sequencing, not narrowing: P5 first, then P6 with immutable
snapshots and historical assignment/creator authority. This supersedes the
initial stop and options above. P6 remains required after this slice.

The kill at `seon.plan` is: an issue-backed step completes only when every
required test has current verified run facts; completion and issue resolution
are asserted in the same transaction. All four turn settlement sites run the
unresolved assigned issues' tests before submitting settlement. One configured
evaluation deadline covers the set. Timeout and unavailable executable tests
record red evidence through the existing test result writer. Host test Vars
retain their fixture context; SCI test Vars run under the worker context's
interrupt arm, inside the test runner's bounded thread. No transaction function
executes tests. Existing issue queries are interpreted by this one owner.

Exact in-process regression invocation throughout this slice:
`(seon.test/run #'seon.issue-settlement-test/issue-settlement-runs-tests-and-derives-completion (seon.operator/connection "default"))`.
The canonical fixture admits its function and test through the real SCI path,
then exercises system turns for red, throwing, over-bound and green definitions.
All 19 assertions passed before the file edit (03:04:40Z, 10048 ms,
run entity 68519), after the initial source edit (03:10:47Z, 11111 ms,
70779), before the host-Var correction edit (03:14:44Z, 9097 ms, 70906),
and after that edit (03:15:32Z, 8965 ms, 70936). The latter recorded reach
digest was `4a4647140df2171c35a7688620d6e9df60d7dc18d4931814815a762d9ae78e85`.
Forms were evaluated and called in the development JVM before editing.

The requested baseline retry of `seon.issue-test/issue-worker-creation-is-atomic`
again exceeded its 20000 ms bound: 22058 ms wall time, result 0/0/1,
run entity 67488 at 02:54:02Z. The in-process canonical fixture continued as
directed; no test JVM was launched.

Live proof used issue 43695, worker `12254041a057`, and its actual test
`seon.test-reaching-test/agent-admitted-tests-reach-their-tested-function`.
The old step was already completed at transaction 536871475, and source
adoption had removed the issue assignment. The probe restored that assignment
and added verification step 70816 (`issue-settlement-live-proof`), preserving
the old completed step. Run 70850 was red and left the new step open. After
the host-Var correction, run 70922 (`8068393c78dc`, basis 536871821) recorded
7/0/0; settlement transaction **536871824** asserted both the step's
completed-tx and the issue's resolved-tx. This proves the live settlement
owner using hot-reloaded definitions, not a refork.

Exact live boundary: a full system turn on this preexisting worker refuses
with `:seon.turn/generated-read-depends-on-turns` before reaching settlement.
The live probe therefore called `run-issue-tests!` and submitted the existing
`settle-call` directly; the canonical regression proves the system-turn path.
Publication 69bed57b-af8e-493d-94a5-b5be95d1492e exceeded its declared operator
bound (exit 124), so full publication convergence is not claimed from these
hot-Var runs. The pending source publication must be verified before final
integration. Default has never been stopped, reforked or restarted.

Gate request for slice 1: affected namespaces `seon.issue-settlement-test`,
`seon.plan-test`, `seon.turn-test`, with paths `src/seon/plan.clj`,
`src/seon/turn.clj`, `test/seon/issue_settlement_test.clj`, followed serially
by `--platform` at the orchestrator. No lane test JVM was launched.

## Slice 2 — P6

The writer guarantee is: after an issue has ever been assigned, every expanded
transaction preserves a nonempty test set, assignment and original creator
authority, and only that creator may retract test members or change assignment.
The declared `:seon.db/append-only-after` and `:seon.db/retraction-authority`
properties select the rule from the handed schema projection. Database admission
wraps encoded transaction data with a writer-side snapshot and a final guard.
Snapshots contain realized maps and sets, never the mutable transaction database.
Assignment history activates retention permanently; the first creator assertion
owns the exception. Removing the creator or substituting another is refused.
The final check sees nested calls, attribute retractions and entity retractions.
Identity erasure of a still-referenced test is also refused.

The issue writer's `guard-call` validates additive domain requests. Creation
records `:seon.issue/created-by`; source reconciliation preserves worker facts
and tests in `replacement-tx`. The superseded preservation branch in `adopt-tx`
is removed. Missing physical attributes during initial schema installation do
not activate a rule before its owning schema exists; the same transaction's
final snapshot observes any installed rule and new assignment.

Exact P6 regression invocation:
`(seon.test/run #'seon.issue-settlement-test/started-issue-tests-retain-historical-authority (seon.operator/connection "default"))`.
It uses the canonical database population, its current packaged declarations,
and two tests admitted through real SCI. It verifies direct member/attribute/
entity retraction, test identity erasure, nested calls, assignment removal,
creator substitution, named refusal evidence, creator-authorized removal,
nonempty retention, two-transaction unassign then worker retract, source
reconciliation, creator recording, and additive domain requests.

Recorded in-process iterations (pass/fail/error, elapsed MCP milliseconds):

| Run entity | Result | ms | Observation |
|---|---|---:|---|
| 71513 | 1/21/0 | 2283 | Synthetic identity-only test rows refused by admission; replaced with SCI admission. |
| 71662 | 4/18/0 | 2368 | Fixture lacked the assumed source test identities. |
| 71670 | 4/18/0 | 4516 | Publication replaced the unpersisted transaction definition. |
| 71689 | 22/0/0 | 6012 | Writer guard re-evaluated and verified before edits. |
| 71849 | 5/24/0 | 4441 | MCP lost the session before accepting the guard re-evaluation. |
| 71860 | 29/0/0 | 5748 | Re-evaluated guard and issue owner, before issue/schema edits. |
| 71872 | 33/0/0 | 5641 | Complete regression before test-file edit. |
| 71877 | 33/0/0 | 8027 | After test-file edit. |
| 71888 | 33/0/0 | 9891 | Cold-schema correction before source edit. |
| 72856 | 33/0/0 | 6306 | Removing the redundant adoption branch, before source edit. |
| 72862 | 33/0/0 | 6048 | After adoption-branch source edit. |

P5 also remained 19/0/0 after P6's edits: run 71878 at 03:31:41Z,
10901 ms. P6 run 72862 at 03:36:39Z recorded digest
`b7017b1565312c55a87b48d66eec98e2bbe5fd35601da58d522dbf21fc23081f`.
Every changed function was evaluated and exercised in-process before its edit.
These are hot-definition evidence until the source adoption convergence below
is verified; they are not an isolated platform gate.

Live P6 probe: a nested transaction from worker 55159 attempted to retract test
45221 from the issue-family issue 43695. With the candidate canonical projection,
the writer returned `:seon.db/retention-refused`, named the issue and the full
test symbol, and left the basis unchanged. Elapsed transaction time was
84.6435 ms. A realized snapshot over default's 144 test-bearing/historically
assigned issues (2 historically assigned) took 41.533833 ms. This implementation
scans that declared population twice per guarded transaction; no performance
claim beyond this measured population is made.

Publication first exposed an actual cold-schema defect in the new guard:
`:seon.issue/agent` was queried before installation. The corrected snapshot
checks installed schema before reading that population. Subsequent publication
2648ac28-7423-42f7-9336-f10d43a8d721 exceeded the operator bound (exit 124).
The explicit lane publication shell exited after reporting source changes
during adoption. No lane-owned shell remains from that command.

MCP repeatedly reported session loss with restart wording; PID 7595 and the
live basis remained readable. This is the existing
[MCP session diagnostic issue](../../../seon/issues/mcp-session-loss-claims-unobserved-restart.md),
not evidence that this lane restarted default.

P6 gate request: `src/seon/db.clj`, `src/seon/issue.clj`,
`resources/seon/schemas/seon.db.edn`, `resources/seon/schemas/seon.issue.edn`,
and `test/seon/issue_settlement_test.clj`; affected namespaces
`seon.issue-settlement-test`, `seon.issue-test`, `seon.db-test`,
`seon.cluster.source-test`. Run the path-limited gate and then `--platform`
serially at the orchestrator, including the P5 paths in integration.
