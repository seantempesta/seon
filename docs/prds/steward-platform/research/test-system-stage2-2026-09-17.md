---
type: research
status: active
created: 2026-09-17
tags: [test, database, admission, stage-2]
---

# Test recording and pre-execution admission

## Continuing resolution after the launcher release

The launcher and guardrails edits landed before this continuation touched
their files; clean status was checked first. The draft is now applied in the
main tree. The schema hook admits prospective files against source already
on disk: admitting the new source-digest declaration first, then the
predicate owner, then its referencing schemas resolved the earlier refusal.
The complete candidate population independently compiled successfully.

The isolated `stage2` cluster was stopped before returning to fast snapshots.
Its final resolution proof returned **7 passes, 0 failures, 0 errors** through
the actual worker function and `run-owned`. This is iteration evidence, not
the orchestrator's cold gate or default proof. Exact JVM REPL form:

```clojure
(let [connection (seon.operator/connection "stage2")
      database (seon.db/db connection)]
  (select-keys
   (seon.test/run #'seon.test-test/resolution-follows-admitted-source-and-acquisition
     connection
     {:seon.db/db database
      :seon.test.run/provenance (seon.test.runner/provenance database)
      :seon.test/remaining-ms 240000})
   [:seon.test/sym :seon.test/pass-count :seon.test/fail-count
    :seon.test/error-count :seon.error/kind :seon.error/message
    :seon.test/failure-message]))
```

That run took 48,875 ms against scratch source commit
`6aab8a39-72fc-5348-b845-321ced396562`. The scratch root was
`tmp/stage2-wt/tmp/stage2-root`; default was never explicitly evaluated,
started, stopped, or adopted. Edit hooks retain their configured publication
behavior; no convergence or default proof is claimed.

Main-tree fast iterations, all foreground `timeout 2400`, one at a time,
with explicit owned paths and no environment overrides:

- **Current resolution slice:** `tmp/stage2-resolution-seventh-current-fast.log`,
  **56 tests, 402 assertions, 0 failures, 0 errors**, exit 0. Namespaces:
  `seon.test-test`, `seon.test.runner-test`, `my.test-test`,
  `seon.test-reaching-test`, `seon.test-cache-test`. This proves the latest
  acquisition contract and retained-file override regression. The operator
  and shell-launcher regressions still require their separate expanded run;
  cold gate and default proof remain the orchestrator's work.
- `tmp/stage2-resolution-current-fast.log`: **53 tests, 323 assertions,
  0 failures, 9 errors**. Synthetic namespace requires were missing; the
  agent fixture had no admitted definition row.
- `tmp/stage2-resolution-second-current-fast.log`: **53 tests, 367 assertions,
  9 failures, 1 error**. Synthetic source printing dropped Var metadata;
  reacquisition erased the fixture's fork generation. The fixture now prints
  metadata and acquires the base before creating the agent fork.
- `tmp/stage2-resolution-fifth-current-fast.log`: **56 tests, 401 assertions,
  0 failures, 0 errors**, including the agent's `my.test/check` and
  `my.test/run`, admitted fileless resolution, SCI interruption and classpath
  root rebasing. Later adoption-form and acquisition-contract refinements
  require the subsequent run; this tally does not cover them.
- `tmp/stage2-resolution-sixth-current-fast.log`: expanded to operator and
  launcher namespaces; **interrupted by TERM, exit 143, no final tally**.
  Failures exposed a generated `ns-resolve` symbol incorrectly qualified by
  syntax-quote, a one-arity publication fault fixture, a keyword wildcard pull,
  an obsolete bound assertion and old launch/cache-output expectations. The
  draft fixes these; the interrupted run is not a proof. Its snapshot
  `tmp/test-runs/run.YLZTQO` was removed by the launcher's TERM trap.

The classpath handoff now reads the tool owner's immutable basis artifact in
the adopting JVM, instead of embedding the entire basis in its prepl form.
`dev_cache.clj` owns tools.build resolution; `seon.test.cache/classpath`
rebases repository-relative roots while preserving ordered absolute roots.
The operator, fixture publication and cold worker consume that same value,
including alias JVM options. The old paths-only reader and worker classpath
fallback are removed. Loader construction refuses a different already-loaded
dependency cache; adding URLs cannot replace JVM classes.

Dependency ledger: tools.build's `create-basis` supplies the resolved roots
through the existing `dev_cache.clj/test-classpath!`; Clojure's
`reference-code/clojure/src/jvm/clojure/lang/RT.java:2168` supplies the current
Compiler/context loader; SCI's existing acquisition, fork and interrupt
owners remain `src/seon/sci/eval.clj` and
`reference-code/sci/src/sci/interrupt.cljc`. The worker primes only after
`initialize-contracts!`, so SCI copies armed roots. This incorporates the
pending worker-order hunk documented by `3170a0060`; the broader cold-worker
instrumentation work remains its owning lane's boundary.

These are draft defects, not foreign failures. The admitted fileless
resolution regression passed in both runs. Claim/completion and unchanged
request reuse remain outstanding. The cross-branch scheduling boundary also
needs an authority decision: a pure transaction can serialize claims within
its authority database, but cannot inspect another branch's uncommitted
claims. The three options raised are one designated authority cluster per
JVM (one writer, explicit routing), per-authority serialization only (simpler,
no cross-branch guarantee), or a JVM execution boundary in addition to writer
claims (extra mechanism). Pure transaction work does not by itself establish
the cross-branch scheduling guarantee.

## Continuation after launcher release: callback prerequisite

The preserved draft applies to HEAD `5dd6ef7cc`. Main-tree schema admission
first refused `:seon.source/progress!` as a dishonest generator. The callback
now has its actual string-to-nil function contract; details and isolated
publication evidence are in
[the resolved issue](../../../seon/issues/archive/source-progress-callback-refuses-schema-admission.md).
This prerequisite commit does not claim Stage 2 complete.

The next main-tree hook refusal named
`:seon.cluster.eval/settle-request` (`schema-unresolved-reference`). Work
continued in `tmp/stage2-wt`, with HEAD and only owned changes. The fast
overlay refused a missing published baseline; the one explicitly authorized
`bin/test --prepare-head-base` attempt was refused by the lane guard before
launch. Logs are `tmp/stage2-resolution-fast-current.log` and
`tmp/stage2-prepare-head.log`. No override was set and no cold gate ran.

The authorized scratch fallback booted `stage2` at
`tmp/stage2-wt/tmp/stage2-root`, without Juniper. Its first canonical
`seon.test/run` iteration reached the fileless interpreted body successfully
but reported **4 passes, 0 failures, 2 errors**: the draft's missing-row and
stale-acquisition diagnostics omitted the required cause field. Those draft
requests are now corrected. Default was not explicitly mutated.

## Continuation after e1de7c75d: launcher handoff held

The SCI files were released and work resumed. The next encountered hold is
`script/seon/fresh_operator.clj`, needed for the design's shared resolved
test-classpath handoff into the cluster JVM. It was clean when inspected
and the draft was prepared in an isolated worktree. A later shared-tree
check showed **42 insertions and 16 deletions belonging to another lane**.
The hook also reported unmatched delimiters at lines 2509, 2611–2627 and
2674–2776 in that shared file. These observations establish a concurrent
edit, not a diagnosis of the other lane's final change. No shared launcher
bytes or foreign session were changed. The owner's explicit “stop … at any
held file” rule applies here.

The then-unapplied work was retained in the resolution patch at commit
`b6562f1ce` (Git history preserves its exact bytes),
against **19251d6469f0b87e7512f6dbd022dbb1a49db9ba**. It is **incomplete and
not approved production code**. It contains the acquisition-evidence draft,
provenance-based resolution, an analyzer-written source digest, the loader
basis value and launcher handoff, a bounded SCI call into the existing
capture owner, and a canonical regression. The shared SCI edit made before
isolation was removed by reversing only this lane's exact hunk; its diff
matched the saved patch before removal. No implementation from this
continuation is landed.

The initial schema-edit hook refused the shared population with
`schema-unresolved-reference` for `:seon.cluster.eval/settle-request`.
Following the assignment, work continued in
`tmp/test-system-stage2-wt`, a HEAD worktree with linked `reference-code`,
without modifying the shared evaluation schemas. Both runs below used
HEAD-plus-owned-paths snapshots, foreground `timeout 2400`, one at a time,
and no `SEON_TEST_*` overrides. No cold gate ran.

1. `tmp/test-system-stage2-resolution-fast.log`: **52 tests, 108 assertions,
   0 failures, 43 errors**, exit 1. The draft called `seon.id/digest` with a
   string where its contract requires a sequence. This was this lane's bug;
   the draft now uses `seon.id/id source 64` at writer and reader.
2. `tmp/test-system-stage2-resolution-second-fast.log`: **5 tests,
   28 assertions, 0 failures, 1 error**, exit 1. Test JVM pid **3465**,
   **1128** armed contracts; namespace began
   `2026-09-17T05:14:12.802936Z` and ended
   `2026-09-17T05:15:48.483560Z`. The new regression stopped at the existing
   three-argument `sci.eval/fork-cluster-ctx` delegation, which passes nil to
   its four-argument contract's required projection state. Resolution and
   execution acceptance are **not proven**. The refusal is recorded in
   [the arity issue](../../../seon/issues/sci-fork-three-arity-passes-nil-to-a-required-projection-state.md).

The second exact invocation, from the worktree:

```bash
timeout 2400 bin/test-fast --paths dev_cache.clj script/seon/fresh_operator.clj resources/seon/schemas/seon.program.edn resources/seon/schemas/seon.fn.edn resources/seon/schemas/seon.test.edn src/seon/fn.clj src/seon/sci/eval.clj src/seon/test.clj src/seon/test/runner.clj test/seon/test_test.clj -- seon.test-test
```

Remaining work within item (1), before any implementation commit: repair and
rerun the regression; verify accepted-batch acquisition evidence against
deletions and nested program facts; carry the acquired context through the
agent protocol; remove the worker's independent resolver; finish shared
classpath compatibility checks and selected-Var fixture handling. The
source-digest additions are optional accretions in the draft; the reset
integrator still owns their required-definition transition. Items **(2)**
claim/completion and **(3)** recorded-result reuse have not been implemented.
No Stage 1 selection was built.

All named authorities were read end to end earlier in this lane; the Stage
2/3 design and ownership table were revisited here. No explicit default
status, evaluation, publication, adoption, start, stop or reset was issued
in this continuation. File-edit hooks queued their configured publications;
those automatic effects were not inspected or claimed as a live proof.
Both owned test processes exited. The draft is committed before removing
the disposable worktree and initial scratch patch; the orchestrator still
owns cold-gate and platform/live proof.

## Continuation after e58a27c86: held acquisition regression

`e58a27c86` was accepted. The recorder error-as-row class is now owned by
[the class issue](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md);
this continuation makes no further change there.

The owner's new stop rule is “Stop for review after (3) or at any held file,
naming it.” The concrete held file encountered at item (1) is
`test/seon/sci/eval_test.clj`: it has another lane's uncommitted changes.
`src/seon/sci/eval.clj` was clean at entry, with acquisition work landed at
`684f185f8`, and **became dirty during this read-only inspection**, confirmed
by the final `git status --short` check. Both source and regression are now
held. This is not a reservation inferred from an old assignment. Neither
file was changed by this lane; the source observations below refer to the
clean definition inspected at entry.

### Required acquisition change and regression

Stage 2 requires a resolver to reject an acquired context for a different
program and to preserve acquisition refusals. At this inspected source:

- `base-ctx` (`src/seon/sci/eval.clj:1953`) returns an `::acquisition` report
  on its context, but does not retain the acquired database or program digest
  with that report.
- `acquire!` (`:1975`) replaces the existing context's environment and kernel
  snapshot; its new acquisition report is returned to the caller. The
  original context map's `::acquisition` entry is not replaced by that
  mutation. Reading that map entry alone cannot prove current acquisition.
- `kernel/cache-program!` (`src/seon/sci/kernel.clj:108`) retains function and
  namespace definitions, without the acquired test-source population. It
  cannot establish that an SCI test Var denotes the requested stored test.
- `install-evaluated-rows!` (`src/seon/sci/eval.clj:971`) installs a complete
  accepted batch, while `fork-for-turn` and `fork-cluster-ctx` copy the
  existing kernel snapshot. The evidence must follow these same operations.

The required owner change is to carry the acquired immutable database and
its acquisition refusal report with the **existing** kernel program snapshot,
updating that evidence at completion of base acquisition/reacquisition and
accepted-batch installation. Forks carry that same evidence under their
existing copy semantics. The resolver can compare the existing
`runner/program-digest` derivation over the acquired and requested databases;
it must not stamp a caller-supplied digest as evidence of acquisition or infer
freshness from a resolvable Var. Failed installation must remain a refusal,
not acquire a successful-program assertion. This is a required change
description, not an applied or verified implementation patch. The initial
unapplied hunk at `base-ctx`, against `684f185f8`, is:

```diff
              acquired (acquire-program! {:seon.sci.eval/ctx ctx
                                  :seon.db/db database
                                  :seon.schema/projection projection})]
+         (swap! (::kernel/program-snapshot ctx) assoc
+                :seon.db/db database
+                ::acquisition acquired)
          (assoc ctx ::acquisition acquired))))))
```

This hunk alone is insufficient: accepted-batch installation must advance
the evidence, refusal handling must remain visible, and the held regression
must verify both paths before a resolver relies on it. `acquire!` and fork
operations already copy that snapshot; no second acquisition-state atom is
needed.

The held acquisition regression needs the corresponding cases: acquire a
real admitted fileless test; admit a changed test/dependency into the same
canonical database while retaining the old context; verify stale resolution
refuses; reacquire through the existing owner and verify the new test body;
repeat across a fork and an accepted installation batch, including a refused
installation. Item (1)'s runner regression then proves execution through
the existing capture owner. No alternate evaluator, per-test source replay,
or caller-generated acquisition proof was introduced to bypass this boundary.

### Read-only live observation

`bin/seon status` observed default pid **66052**, alive, no orphan Seon JVMs.
MCP runtime status answered with all three listed plumbing procs reporting
`reply`. It also reported two error signatures, three errored evaluations,
one failed run and three failed tests; those counts were not diagnosed by
this bounded lane and are not an assertion of a clean platform gate.

Exact JVM REPL form, `read_only: true`, root `/Users/sean/src/seon`, cluster
`default`, session `test-system-stage2`:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:pid (.pid (java.lang.ProcessHandle/current))
   :basis-t (seon.db/basis-t database)
   :analyzed-source-digest-installed?
   (boolean (get (:schema database) :seon.program/analyzed-source-digest))})
```

Returned value, reported evaluation time **1 ms**:

```clojure
{:analyzed-source-digest-installed? false
 :basis-t 536871059
 :pid 66052}
```

The reset-group analysis attribute required by the design is therefore still
an integration prerequisite in this observed live database. That observation
does not justify an old/new schema compatibility reader or Stage 1 selection.
No production edits, tests, write evaluations, explicit publications,
adoptions, restarts, resets, or foreign-session operations were performed in
this continuation. The Markdown edit hook may queue its normal publication.
Items (1), (2), and (3) remain unimplemented beyond the earlier admission
seam; this note does not claim any additional execution acceptance.

## Follow-up: batch 116 recording refusal

The accepted first seam is `7795e54f4`. The next coherent slice corrects
the recorder's interpretation of a refused provenance read. Resolution,
claim/completion, and unchanged-green reuse remain outstanding in that order.

Raw evidence: `tmp/orchestrator/gate-results/batch-116.log:1449` records
`:seon.test.run/immutable` after 96 platform tests; the retained execution
snapshot is `tmp/test-runs/run.jHOFSg`. Recording ran through the separate
live store holder (`record-persistent-results!` → `commit-staged-completion!`
→ `source/record-results!`), so the execution snapshot does not establish
which definition of `seon.db/pull` the recording JVM had loaded.

The run ID is a fresh event: the coordinator calls zero-argument
`seon.id/id`, which hashes a fresh UUID. The compared provenance also carries
time, Git SHA, program digest, tested basis and branch. Nothing derives the
run ID from the program digest or reuses the published base's run ID. The
published `provenance.edn` contains only program digest, basis and branch.
The gate log retained neither compared value nor even the alleged colliding
ID, so it does **not** establish an actual identity collision.

The contemporaneous read defect is independently documented in
[the config-loss incident](../../../seon/issues/the-default-clusters-effective-configuration-lost-every-required-fact.md).
Raw probes `tmp/orchestrator/config-loss-probe-4.edn` and `-5.edn` show
`seon.db/pull` returning `:seon.db/invalid-read`: a sequence passed to
`append-pull-evidence!` caused `Cons cannot be cast to Associative`.
The on-disk repair has landed in `6a0f8a08a`; no database-owner edit belongs
in this slice. This explains how the recorder could produce the reported
false conflict: its truthy `previous` was allowed to be an error map.
The historical gate's exact `previous` value is unavailable; the regression
proves the failure class rather than claiming to recover that missing value.

`record-tx` now throws a refused read with its original error data, letting
the existing transaction boundary return that refusal unchanged. It only
compares real rows for immutability. A genuine conflict includes both stored
and submitted provenance and the writer's basis, so the next diagnosis has
the evidence batch 116 omitted. There is no new ID derivation or runner.

The canonical regression records two independently captured run IDs at the
same tested basis, replays the first, and records across a different
destination branch. It verifies two distinct runs, atomic refusal of changed
provenance, and preservation of a real database read refusal injected only
at the run lookup. All other reads and the transaction use the canonical
fixture and writer. The initial run/replay/conflict iteration passed
**4 tests, 24 assertions, zero failures/errors**, at snapshot HEAD
`73306eaac1d024954d9ff9bbd6b91b6ff5e9994a` plus the two source/test paths.
The next four-namespace iteration ran **52 tests, 362 assertions, two
failures, zero errors**: the two failures were this regression looking for
diagnostic evidence at the top level instead of under `:seon.error/data`.
The read-refusal assertion passed. After correcting those assertion paths,
the final four-namespace iteration passed **52 tests, 362 assertions, zero
failures, zero errors**, exit 0. Its snapshot was HEAD
`2a36c0af9aa357da0cf63e2c04895b2f264afecb` plus the two source/test paths;
1,123 contracts were armed. Slot wait was zero seconds. Test execution ran
from `2026-09-17T04:32:20.549894Z` to `04:35:40.288723Z`.

```bash
timeout 2400 bin/test-fast --paths src/seon/test/runner.clj test/seon/test_test.clj -- seon.test-test seon.test.runner-test my.test-test seon.test-reaching-test
```

Raw iteration logs: `tmp/test-system-stage2-recording-refusal-fast.log` and
`tmp/test-system-stage2-recording-refusal-final-fast.log`.
Runs were sequential, foreground commands with a 2400-second bound; neither
slot nor silence settings were overridden. No default REPL, start, stop,
reset, or explicit adoption command was issued during the owner's reset.
The configured edit hook queued publication automatically. Cold recording
and platform proof remain the orchestrator's responsibility. No foreign
session or file was operated on.

Hook lint reported the existing dependency-pin errors in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
That foreign authority was not edited; repository-wide Markdown lint is not
claimed green. Clojure lint reported existing runner shadowed/unused-var and
docstring warnings, with no blocking finding on this change.

The recorder defect and resolution are recorded in
[the issue note](../../../seon/issues/test-recording-misreports-a-refused-provenance-read-as-an-identity-collision.md).
Stopping at this coherent recorder repair for review under the assignment's
landing rule. No resolution, selection, claim/completion, or unchanged-request
implementation is included in this follow-up.

The owner goal, verbatim: "We want agents to be able to ask for tests whenever they want and for it to just ignore requests that have already been run and to just return the results if nothing has changed."

Earlier, verbatim: "specify which cluster we are running on and I'll only run the minimum amount of tests based on what has changed since the last run and I will do it as efficiently as possible knowing globally how many tests are running in different workers."

## Scope of this first seam

This is an intermediate recording/admission seam, not completed Stage 2.
`seon.test.runner/record-tx` derives admission provenance only when recreating
an absent test. It already executes through `:db.fn/call`; its namespace
query therefore observes the writer's mid-transaction database. An existing
test retains its admission source. An absent test takes its namespace's
declared source, defaulting to `:agent` when none was declared, following
`seon.error/function-identity-call` at `27f0a0242`.

`runner/admit-run` accepts an explicit selected membership and reserves the
complement of matching admitted memberships atomically. It writes selection
evidence even for zero members. This is a pure transaction function; it does
not select tests, start execution, or replace the recording owner.

Still outstanding: identity-based host/SCI resolution, shared resolved
classpath carriage, acquired-program verification, guarded SCI execution,
unchanged-green result reuse, namespace-group claims, exact-process recovery,
and immutable member completion/report recording. Stage 1 selection remains
deferred until the symbol-edge reset. The new admission entry is not yet
integrated into launchers or `check`.

Admission currently receives the caller's input digest; publisher-side input
identity verification and terminal covered-result compatibility still belong
to subsequent integration. Do not treat this unused entry as a complete
execution API.

Existing regressions exposed three fixture corrections. The empty-check
fixture now supplies no changed paths: its previous `docs/README.md` input
is classified as widening, so it was not requesting empty work. The
agent-callable fixture evaluates in its canonical namespace instead of
asking analysis to resolve an absent `user` namespace. Its test is still
authored by real SCI evaluation; writes use `transacted!` so a refused write
cannot look successful. Finally, the long-exchange fixture derives its
allowance above the configured default instead of assuming that default is
less than 900 seconds. No selection algorithm was changed.

## Authorities and dependency ledger

Read AGENTS.md sections 0–7 and these authorities end to end:

- [Test system PRD](../plan/test-system-is-the-database-prd-2026-09-17.md).
- [Stages 1–3 design](../plan/test-system-stage1-3-design-2026-09-17.md).
- [Stage 0 design](test-system-stage0-design-2026-09-17.md).
- [Stage 0 review](review-test-system-stage0-2026-09-17.md).
- [Execution model](test-execution-model-2026-09-16.md).

Dependency seams read before editing:

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1153`:
  `:db.fn/call` receives the current transaction database and returns more
  transaction data. `reference-code/datahike/src/datahike/writer.cljc:201`
  rejects a failed write without publishing a new database value.
- Clojure `reference-code/clojure/src/clj/clojure/test.clj:710` (`test-var`)
  and `:728` (`test-vars`): event/count capture and
  namespace once/each fixtures remain the existing runner's responsibility.
- SCI `reference-code/sci/src/sci/core.cljc:330` and `:345`: init and fork;
  no new evaluator or acquisition mechanism was introduced.
- First-party precedent: `src/seon/error.clj`,
  `function-identity-call` at `27f0a0242`; recorder `record-tx` and
  `commit-results!` remain the writer route.

Applied skills: data-oriented-clojure, data-modeling, datahike,
clojure-testing, and repl.

## Verification

The requested four namespaces passed on HEAD `846d75e9c` plus the selected
paths: **51 tests, 346 assertions, zero failures, zero errors**. The final
admission-only follow-up, after unlimited scope pulls and ignoring unrelated
request keys, passed **3 tests, 15 assertions, zero failures, zero errors**.
The schema's last descriptions do not change validation semantics.

Exact commands from the checkout:

```bash
SEON_TEST_SLOTS=3 SEON_TEST_SILENCE_SECONDS=1800 bin/test-fast --paths src/seon/test/runner.clj resources/seon/schemas/seon.test.run.edn resources/seon/schemas/seon.test.member.edn test/seon/test_test.clj test/seon/test_runner_test.clj test/seon/test/runner_test.clj test/seon/test_reaching_test.clj -- seon.test-test seon.test.runner-test my.test-test seon.test-reaching-test
SEON_TEST_SLOTS=3 bin/test-fast --paths src/seon/test/runner.clj resources/seon/schemas/seon.test.run.edn resources/seon/schemas/seon.test.member.edn test/seon/test_test.clj -- seon.test-test
```

The first command waited 111 seconds for a slot; the final admission command
waited 146 seconds. The former armed 1,119 contracts. Logs are
`tmp/test-system-stage2-four-fast.log` and
`tmp/test-system-stage2-admission-final-fast.log`.

The broader five-namespace run at `ec350ece0` plus the selected paths
completed all three requested recorder regressions without failure:
`result-recording-is-total-under-concurrent-test-retraction`,
`the-agent-fork-callable-returns-the-committed-projection`, and
`gate-completions-travel-as-a-file-not-as-code` (2,000 results).
That run's aggregate was **98 tests, 637 assertions, six failures and two
errors**. The six failures were the empty-input and fixed-bound fixture
assumptions corrected and verified by the four-namespace run above. The two
remaining errors are the separately recorded
[publication issue-indexing refusal](../../../seon/issues/publication-issue-indexing-refuses-in-test-runner-fixtures.md).
This is not a claim that the broader five-namespace suite is green.

The broad run used the four-namespace command's silence override and added
`seon.test-runner-test`; its snapshot also included the subsequently removed
one-line `src/seon/test.clj` experiment. The experiment was not the empty
fixture's cause and is not part of this landing. Evidence is in
`tmp/test-system-stage2-final-fast.log`.

### Earlier iterations and bounds

The first fast invocation was terminated during the orchestrator's session
restart after waiting 264 seconds for a slot. It produced no verdict.
The replacement invocation uses three slots and HEAD plus only selected
paths, with `seon.test-test`, `seon.test.runner-test`, `my.test-test`,
`seon.test-reaching-test`, and `seon.test-runner-test` (the three recorder
regressions named in the issue live in the last namespace).

No cold gate or platform proof was run by this lane; those belong to the
orchestrator. No claim of full Stage 2 acceptance is made.

The isolated worktree at `0b900a2b0` excludes the foreign runner hunks and
links its slot directory to the checkout's three-slot pool. Its first run
acquired a slot after 378 seconds and armed 1,112 contracts. All three new
`seon.test-test` regressions completed without failure. It exposed the two
existing regressions described above; their corrected files passed the
subsequent run. Deliberately failing nested fixtures also print FAIL lines;
those lines are not independently interpreted as the suite verdict.

That run ended with exit 124 at the already-open
[nested-gate liveness issue](../../../seon/issues/a-test-that-drives-two-real-gates-reports-no-progress-to-the-silence-bound.md).
Last progress was `concurrent-bin-test-invocations-both-reach-their-tallies`
at `2026-09-17T03:07:25.484199Z`; the watchdog reported 300 seconds of
silence. The parent had no deadlocked thread IDs. The scratch worktree was
removed after process exit and a zero-matching-process check, unlinking its
reference-code and shared-slot symlinks before removal.

The broader candidate was HEAD `ec350ece0` plus the six selected code/schema/test
paths, with `SEON_TEST_SLOTS=3 SEON_TEST_SILENCE_SECONDS=1800`. The latter is
the existing issue's documented observation bound, not a production change
or a claim that its underlying liveness bug is fixed.

## Live observations and foreign boundaries

Default was observed as PID 33583 before edits. This lane never restarted,
reset, or changed default's configuration. The earlier read-only REPL probe
returned basis `536871217`, no member schema, and no analyzed-source-digest
key. The inspected indexed test had `:core` provenance and stored source.

After the recorder edit, the project hook queued publication
`d6a16add-83d1-4a80-ac42-7df04240d14a`. This exact JVM REPL form, in session
`test-system-stage2`, did not yield the requested evidence:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:basis-t (seon.db/basis-t database)
   :admit-run-loaded? (some? (ns-resolve 'seon.test.runner 'admit-run))
   :member-schema (get (:seon.schema.projection/forms
                       (seon.db/carried-projection database))
                      :seon.test.member/member)
   :source (seon.db/q '[:find ?commit .
                       :where [_ :seon.source/commit-id ?commit]] database)})
```

The MCP envelope reported `:seon.config/missing-effective` for `"default"`,
68 missing config keys, elapsed 3 ms, cluster-state alive. This is an
observation, not an attribution or a successful live proof.

A second read-only form used the tool's ordinary stdout event to distinguish
evaluation from result projection:

```clojure
(println
 (let [database (seon.db/db (seon.operator/connection "default"))]
   {:basis-t (seon.db/basis-t database)
    :admit-run-loaded? (some? (ns-resolve 'seon.test.runner 'admit-run))
    :member-schema? (some? (get (:seon.schema.projection/forms
                                (seon.db/carried-projection database))
                               :seon.test.member/member))
    :source (seon.db/q '[:find ?commit .
                        :where [_ :seon.source/commit-id ?commit]] database)}))
```

Exact stdout, elapsed 2 ms:

```clojure
{:basis-t 536871223, :admit-run-loaded? false, :member-schema? true, :source #uuid "6aab4025-0614-5831-adbe-d04beab988da"}
```

The returned-value projection still refused missing config. The hook result
later reported `:seon.boot/refused`, “Source changed while current-src was
being analyzed; retry.” Thus schema presence alone did not prove adoption.
The observation extends the existing
[partial hot reload issue](../../../seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md).

The later JVM check remained unchanged (4 ms):

```clojure
(println {:pid (.pid (java.lang.ProcessHandle/current))
          :admit-run-loaded? (some? (ns-resolve 'seon.test.runner 'admit-run))})
{:pid 33583, :admit-run-loaded? false}
```

Its returned-value projection again reported missing effective config.

The hook also briefly rejected shell reads because the concurrently edited
`test/seon/render/web_debug_test.clj` had an unmatched opening parenthesis.
Read-only shell access subsequently succeeded without this lane changing
that file. No foreign session was operated or messaged.

At entry, `runner.clj` already contained uncommitted `tests-reaching-rows`
and fixture-selection changes, with a corresponding change in
`test/seon/test/runner_test.clj`. Those hunks are not this lane's work;
they must not be silently included in its commit. The supplied file snapshot
includes the runner's existing bytes, so verification must name that boundary
until the other hunks land.

The fixture-selection hunks subsequently landed as `d65cc688c`; the runner
file is now available for a path-limited commit containing only this lane's
changes. The guardrail lane's neighbouring issue-note paragraph likewise
landed as `aba5d94a5`. Both are preserved in the shared checkout.
