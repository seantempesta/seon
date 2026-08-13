---
type: issue
status: open
severity: friction
tags: [issue, operator, test, wave/parallel-stress-triage]
---

# Classify parallel-only test failures by their shared resource

## Problem

The first complete nine-worker `bin/test --full` stress run after shared-base
preparation produced eleven tests that failed in the parallel pool and passed
when the runner immediately reran each test alone. The tests own isolated
operator roots where applicable, so root identity alone does not explain the
failures. Moving them into the serial remainder would hide either a production
concurrency defect or an undeclared test resource and is not an admissible fix.

Two agent generative properties later joined this class:
`seon.cluster.agent-test/n-agent-parallel-turns-property` and
`seon.cluster.agent-test/wake-routing-conservation-property`. They failed with
different fixed-seed shrinks under load and passed direct and isolated reruns.

## Evidence

The frozen-tree run at `b9c0f63fd2c7f2dc36f61098c69688eee1604234` is
recorded in
[`parallel-runner-measurement-2026-08-10.md`](../../prds/sci-execution-runtime/research/parallel-runner-measurement-2026-08-10.md).
The runner's isolated confirmations classified these tests as parallel-only:

- `seon.cluster.boot-test/incremental-source-refresh-preserves-agreement-across-real-edits`;
- `seon.concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster`;
- `seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store`;
- `seon.dev.fresh-operator-test/forced-reset-clears-an-exact-dead-process-record`;
- `seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle`;
- `seon.dev.fresh-operator-test/isolated-cached-boot-reports-refusal-then-reaches-readiness`;
- `seon.dev.fresh-operator-test/live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`;
- `seon.dev.fresh-operator-test/populated-stopped-cluster-reopens-after-full-operator-restart`;
- `seon.dev.fresh-operator-test/source-less-root-reset-republishes-and-reforks-default`;
- `seon.sci.defs-test/agent defs-survives-kill-9-and-explicit-clear`; and
- `seon.sci.eval-test/generated-sources-compose-fork-guard-and-admission`.

The boot failure exposed one concrete shared-resource symptom: clj-kondo
threw `Clj-kondo cache is locked by other thread or process` during
`seon.fn/build-artifact`. Other failures include load-sensitive process
lifecycle assertions and generated-property counterexamples. Those are
observations, not yet one asserted cause.

The cache-lock class is now fixed at its owner. `bin/test` copied each
worker's source tree but symlinked the top-level `.clj-kondo` directory, so all
nine processes wrote the same cache. A losing worker could poison the delayed
canonical database base on its first population attempt; later database tests
in that worker then failed immediately. Worker checkouts now copy `.clj-kondo`
copy-on-write, and
`seon.test-runner-test/worker-checkouts-own-the-writable-clj-kondo-cache`
proves the directory is private rather than a symlink. The remaining rows in
this issue still require classification after the repaired complete-tier run.

The same run had six failures that reproduced alone. They are deliberately
excluded from this issue because isolated confirmation falsified parallelism
as their cause.

The 2026-08-11 default-tier rerun at `e75b2a553` exposed the operational cost
of leaving this class open. One worker returned a run of database-dependent
pool failures in 0--4 ms; the same two-namespace sequence then passed 33 tests
and 200 assertions in one worker. Before that falsifier completed, the runner
had spent more than 14 minutes serially starting a fresh approximately
12-second JVM for each clean confirmation. `seon.test.runner` now retains one
clean JVM per failed task but starts those confirmations with bounded
parallelism. This removes diagnosis serialization; it does not classify away
or hide any row in this issue.

The SCI generated-source row is now classified as test-local load timing, not
shared state or a seed collision. Nine concurrent JVMs running the fixed seed
all failed its first trial independently. A narrower probe showed the ordinary
empty-string value being cut as `:seon.sci.eval/time-limit` after 423--559 ms
while the malformed `let` classified as `:seon.sci.eval/evaluation-failed`
after 251--456 ms. The property had given both finite inputs a 300 ms
evaluation time limit and then required the first to succeed and the second to
fail, so CPU oversubscription could legally turn either expected disposition
into a time-limit disposition. The property now uses the shipped
`:seon.config.eval/time-limit-ms`; its separate 10-second future deref remains
the harness backstop for a genuine hang. The pattern to check in the other ten
rows is a subsecond internal wall-clock bound used as an expected-result
classifier: capture the actual disposition and duration under concurrent JVMs
before attributing the failure to shared process state.

That class is fixed by this issue's SCI regression. The other ten historical
rows remain open for independent resource classification.

The two agent properties are classified and fixed as the same test-local
clock class, not as lost routing. The retained explicit namespace run at
`82196a61b2ef0211c341491a820493315ecdc938` took 25m51s and failed both; the
retained worker log carried no core fault. Neither `src/seon/cluster/agent.clj`
nor `src/seon/cluster/wake.clj` changed after the last documented green agent
suite. Both supplied routing shrinks passed directly with every named verdict
true.

The decisive probes held one correctly routed provider call beyond each
property's local clock. The routing trial returned only `:settled? false`
after its approximately five-second polling window; arming, trigger routing,
and exactly-once answers were already or subsequently true, and orderly
teardown observed the held turn complete. The parallel-turn trial threw only
the shared 20-second test-event backstop, then orderly teardown observed the
same held turn complete. Thus the specific interleaving was listener delivery
→ armer derivation and route publication → mailbox prime → active turn held in
the provider stub → test clock expiration → terminal transaction during
orderly teardown. No wake was lost and no agent remained unarmed.

`test/seon/cluster/agent_test.clj` now registers a routing-atom watch and a
database listener before deriving current state, then waits on those observable
events without an inner semantic clock. It also derives the evaluation limit
from the shipped config instead of imposing a fixture-only 2,000ms limit. The
held-provider regression proves pending work remains pending until terminal
database evidence instead of becoming a false property verdict.

The W1 integration gate exposed two more assertions in this same test-local
clock class. `restamp-recovery-test` observed a pending recovery after its
approximately five-second polling wall even though the retained run completed
after 26.288 seconds; `wait-closes-in-terminal-tx-test` likewise read the
database before its terminal transaction. Commit `5438b8b27` registers a
database listener before either action and derives the terminal database value
from the published event. Its focused proof ran 17 tests / 172 assertions with
no failures or errors. The two stale mutable-history assertions in the former
test were separately replaced with the exact interruption value and the
surviving run render; they are not attributed to parallel resource ownership.

This classification leaves the incremental invalidation listener design
unchanged: its one-listener, nonparking, payload-free wake law was not the
defect. Its future generative proof must likewise register interest before
derivation and await observable render completion; a tuned polling interval
must never classify a pending derivation as a lost invalidation.

## 2026-08-12 complete-tier recurrence

The integration `bin/test --all` attempt at `12d9dcee8` selected 71 platform
and 1,107 bulk test rows. The platform tier completed, and 1,176 of the 1,178
selected rows published worker `END` events. The same two agent properties did
not return:

- `seon.cluster.agent-test/n-agent-parallel-turns-property` began on `pool-5`
  at `22:17:10Z`;
- `seon.cluster.agent-test/wake-routing-conservation-property` began on
  `pool-9` at `22:17:17Z`.

After the last unrelated result at `22:22:13Z`, the coordinator reached its
300-second no-progress backstop and exited 124. Its virtual-thread-aware dump
is retained at
`tmp/test-runs/run.ZyS5O7/tmp/test-liveness/11878-1786573634108-threads.json`.
It shows `main` waiting in `run-task-pool!` on a task future and two task-pool
virtual threads blocked in `worker-rpc!` reading worker protocol responses.
The diagnostic reported no JVM deadlock and all ten workers alive. No final
assertion summary exists because those two rows never returned to aggregation.

This is a recurrence at the agent-property fixture owner, not the separately
known worker-root cleanup race: the cleanup regression itself returned after
22.913 seconds, and no `DirectoryNotEmptyException` was emitted. The next
proof must make both property tasks publish terminal task results under the
nine-worker complete tier; a focused pass or a later terminal transaction
during teardown does not close this boundary.

## 2026-08-13 render-profile attribution

The later retained worker dump at
`tmp/test-runs/run.czWGK6/tmp/test-liveness/86091-1786612994637-threads.json`
contains 66 platform and virtual threads. The test thread is parked in
`await-database-state!`, but the agent graph is not missing a wake: one turn
proc is waiting for `seon.render/acquire-context!`, while the only runnable
application virtual thread is executing
`render.web/context-pass` → `render.walk/history` → `render/render-call` →
`config/effective` → `schema/projection-from-database` → Datahike's temporal
merge. The remaining agent and armer procs are ordinarily parked on their
Flow inputs. The dump therefore identifies active render derivation, not an
executor deadlock, missing armer route, or absent terminal transaction.

The post-property commits named in the recurrence were excluded by source
inspection: `677f84f85` and `0ef66e742` only supplied the render interest
reference in other fixtures; `00232b834` and `24255dcfe` changed
materialized-branch registry/operator reads and custody proof. None changed
`routing-trial`, its awaited predicate, agent wake routing, or context
acquisition.

A same-JVM probe over identical operations measured the fixture's ordinary
unprofiled context request at 217,100 ms and the same request carrying
`render/agent-render-profile` derived from shipped defaults at 6,185 ms. Both
runs returned all four routing verdicts true. The class was fixed at the test
construction seam: `test-support/render-context-channel` supplies the
already-derived profile that these fixed-config integration fixtures know,
while the production render proc, context walk, prompt assembly, turns, and
database facts remain real.

The nine-worker changed-path reproduction selected 71 platform plus 21 bulk
tests and completed 92 tests / 510 assertions with no failures or errors.
`n-agent-parallel-turns-property` returned in 61,330 ms and
`wake-routing-conservation-property` in 76,179 ms under pool contention. This
closes the current recurrence without a serial roster, reduced worker count,
poll, or semantic clock.

## Owner

The test and production owner of each resource named during triage: clj-kondo
analysis/cache ownership, fresh-operator process lifecycle, cluster restart,
and SCI evaluation fixtures. The parallel runner remains the stress and
attribution mechanism; it does not acquire a hand-maintained serial list.

## Acceptance

- Reproduce each row under parallel load and identify whether the production
  mechanism or the test fixture owns the shared resource.
- Make that resource's ownership explicit or make the production operation
  concurrency-safe, with one regression for each distinct failure class.
- Keep the repaired tests in the derived parallel pool.
- A complete `bin/test --full` run produces no parallel-only confirmations for
  these rows, without reducing worker count or adding names to a serial list.
