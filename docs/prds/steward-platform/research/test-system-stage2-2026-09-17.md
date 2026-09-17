---
type: research
status: active
created: 2026-09-17
tags: [test, database, admission, stage-2]
---

# Test recording and pre-execution admission

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
