---
name: clojure-testing
description: "Write and run Seon behavior regressions with canonical fixtures, real SCI and armed contracts. Use for test design, fixture diagnosis, bounds, selection and interpreting recorded evidence."
---

# Test the behavior through the installed authority

[Root instructions](../../../AGENTS.md) and the
[agent-platform plan §6](../../../docs/prds/agent-platform/plan/README.md#6-implementation-proof-and-recovery)
own the cut cadence. This skill owns test construction and current harness limits.
B4 describes a replacement runner; it is not permission to assume that runner exists.

## Choose the smallest honest proof

Name the behavior and the real boundary before writing the test. Keep one regression
per failure class, with cases that exercise its meaningful variations. Assert public
outcomes independently of the implementation; do not preserve obsolete machinery by
repairing its tests. Remove those tests with the mechanism and supply replacement
behavior coverage by the end of the cut. Ordinary tests must not publish all of `src/`
to set up a small change; publication tests use a small complete fixture program.

Prove the subject exists before testing its disappearance or refusal. Assert the
specific diagnostic and unchanged relevant state, not merely a throw/nonzero exit.
A missing entity, wrong branch name or failed fixture write must make the test fail.
An empty set is not proof that the right set was queried. Use the writer's returned
identity, then positively establish the before-state and independently read the after-state.

## What the machinery enforces today

These are source-verified boundaries, not a claim that the entire platform is green.
Recheck the named owners when changing them; update this table in the same slice.

| Rule | Installed enforcement | Author responsibility / limit |
|---|---|---|
| Exceeding the declared test duration fails | **Enforced on reported completion:** `duration-failures`, `src/seon/test/runner.clj:361`, emits an assertion failure at `:end-test-var`. A nonblank `:seon.test/long` reason AND positive `:seon.test/long-ms` are required to raise the ordinary bound. | Measure the operation, put the reason beside the number, and distinguish cold/warm work. The runner cannot validate the measurement's honesty. This elapsed-body check is not a hard kill or a complete accounting of namespace fixtures/child cleanup. |
| An in-process request cannot wait forever | **Bounded observation with actual exit:** `bounded-result`, `src/seon/test.clj:106`, joins the body thread under the request remainder of `:seon.test/check-time-limit-ms`; a body still live is recorded unfinished and keeps its branch, and a watcher releases the branch only after the thread exits. `seon.test/run` (`:1393`) admits no further body after one. | SCI interrupts interpreted bodies at the per-test bound; a host call is not interruptible, so a live body can hold its branch until it returns. |
| A refused fixture write stops setup | **Enforced when using `transacted!`:** `test/seon/test_support.clj:285` checks the real writer report and throws if no successful `:db-after` exists. `apply-config!` and `seed-cluster!` also check their production writer results (`:767`, `:786`). | Use these helpers. Calling `db/transact!` and discarding its returned refusal bypasses this setup check. Assert the subject-specific populated state too. |
| Tests enter with production contracts | **Enforced by initialization:** `seon.test.arm/arm-contracts!`, `src/seon/test/arm.clj:163`, loads the derived program and verifies actual wrapper coverage against armable Vars. | A later reload removes wrappers. `seon.test/run` is not an automatic arming operation; after reload, use the installed arming owner with the correct carried projection and positively verify relevant entering wrappers. Never hand-pick a smaller fake program. |
| Tests leave instrumentation intact | **Partially enforced:** `run-vars!` compares entering/exiting global state and adds errors (`src/seon/test/runner.clj:656`); `ambient-drift` checks wrapper membership, registrations, live clusters and SCI sizes (`:1604`). Worker `reassert-contracts!` repairs a reduced wrapper count (`:1852`). | A before/after set cannot prove entry was already correct, same-cardinality changes are not the re-arm count's proof, and unchanged SCI size is not unchanged contents. Restore deliberate mutations with the canonical scope. Automatic repair does not excuse leakage. |
| No hand-written production fixture maps | **Author rule, partially supported by validation:** the real writer checks schema validity. `program-fn-row` reads actual indexed artifacts or analyzes supplied source (`test/seon/test_support.clj:754`). | There is no general detector for a map's hand-written origin or semantic fidelity. Use canonical declaration/config/cluster helpers. A schema-valid invented row may still model the wrong world. Synthetic data is legitimate only when the subject needs it. |
| No assertionless green | **Enforced:** `assertionless-failure`, `src/seon/test/runner.clj:344`, rejects executed tests with zero assertion evidence. `assert-check!`, `test/seon/test_support.clj:616`, requires a true property result and positive trial count. | One vacuous assertion can still pass. Prove meaningful inputs, subject presence and coverage. A reused recorded green is different from executing an assertionless test. |

## Canonical fixtures and custody

A test is an isolated agent for one body. `seon.test/run` (`src/seon/test.clj:1393`)
acquires each member's own branch off the request's captured commit through
`seon.cluster.agent/acquire-context!` and releases (unlinks) it through
`release-context!` after the body exits (`member-result`, `:1308`). An agent (SCI)
test's elided `seon.db` arities reach that branch; a host test body inherits no
custody, and its fixtures find the member through the SCI arm governing its thread.

`seon.test-support/with-database` (`test/seon/test_support.clj:705`) acquires a fresh
branch off the commit the executing handle was acquired at, through the same
entrance, runs the body under that branch's custody and releases it
(`with-branched-database`, `:692`). It never copies a store or indexes source. Outside
a `seon.test/run` member it refuses by name (`execution-handle`, `:332`).
`fork-cluster-ctx` (`:358`) forks the executing handle's context onto a fixture
connection. `:seon.test-support/fresh-store?` opens an empty in-memory store with the
installed attribute schema for store-global subjects (`with-fresh-database`, `:660`).
A fixture branch inherits the executing branch's data, including its cluster rows.

Use `program-fn-row` for program declarations (actual source symbol, or database +
symbol + synthetic source), `apply-config!` for a complete config overlay, and
`seed-cluster!` for the cluster/config path. `apply-config!` replaces the whole overlay;
it is not an incremental map merge (`test/seon/test_support.clj:767`). Fixture
transactions go through `transacted!`. Use `:seon.test-support/extra-schema` only
for genuinely synthetic declarations. Never hand-roster the production schema.

Pass projection, environment, declared proc inputs and fixed render profiles as
production does. Use real SCI evaluation for SCI semantics; calling the same JVM
function does not prove context isolation or interpreted caller behavior. Stop an
agent graph before retracting facts it may still settle.

A branch isolates its datoms, connection and history. It does not isolate store-wide
blob keys, GC, filesystem locks, loaded JVM Vars/classes or shared SCI objects.
Store-global subjects use the existing `:seon.test-support/fresh-store?` route
(`test/seon/test_support.clj:660`); file/process subjects use the canonical published
file/root helpers with their required `:seon.test/fixture-observation` explaining why
a branch is insufficient (`:104`, `:151`). Do not fabricate a manifest or bypass
fixture admission to get a green. Track 1.3d commit 5 owns replacing these costly file-backed paths.

For pure derivation properties, immutable Datahike `with` values are admissible
only with the same final-report validator in transaction metadata as the writer.
See `test/seon/turn_work_cost_test.clj` and the shared transaction evaluator at
`reference-code/datahike/src/datahike/db/transaction.cljc:1206`. This does not prove
durable publication, live boot, adoption or browser paint.

## Bounds, events and process tests

The ordinary duration bound is five seconds (`resources/seon/schemas/seon.test.edn:27`).
A longer bound declares both numeric `:seon.test/long-ms` and explanatory
`:seon.test/long` beside the test. Derive it from the measured operation, including
which work was timed and why any margin is needed. Store exact timings in the owning
landing evidence. No blanket multiplier or fifteen-minute escape substitutes for
measurement. Raising a bound cannot turn a prior timeout into a pass; diagnose and
rerun the actual proof. Work over ten seconds needs owner authorization already
covering that operation. Cold boot/index authorization is not permission for unrelated
slow tests or repeated full-suite runs.

`await-event!` (`test/seon/test_support.clj:474`) waits on the required event under a
declared bound and reports what failed to arrive. It installs watches before reading
current state. A future timeout requests cancellation, which is not proof of exit.
Avoid sleeps, quiescence guesses and infinite waits. A fixture hold that waits for a
cold competitor must cover that declared operation; positively assert the winner
remains paused until the competitor has returned the expected refusal.

Concurrency regressions use the same contested identity/path as production. OS lock
exclusion needs an actual second process; `.isValid` in one JVM alone proves too little.
Assert the specific losing operation, unchanged winner state, and actual child exit
with its exit code where relevant. Wait for stdout/stderr completion separately;
a diagnostic stream is not automatically an EDN result. A connection closing is not
proof of process exit. Use the retained `(pid, start-instant)` and `ProcessHandle.onExit`.
See the eight real examples in `test/seon/cluster/boot_test.clj`.

Acquire resources in nested `with-open` scopes, using `test-support/closeable`
(`test/seon/test_support.clj:844`) for separate release functions. Setup and cleanup
failures must still attempt every earlier release. Preserve the primary failure and
cleanup diagnostics. Do not delete a root while a child or database release is unknown.
Plant an external symlink sentinel in recursive-cleanup tests; verify it survives.

## Properties and global state

Generate reproducible inputs from a fixed seed. Do not mint uncontrolled IDs or read
wall time inside a property whose replay depends on that seed. Mutating trials get
isolated fixtures; pure trials may share an immutable database value. Validate generated
values and meaningful domain coverage separately from the property; generator overrides
do not prove validity (`reference-code/malli/src/malli/generator.cljc:468`).
Use `assert-check!` to preserve shrink evidence and require positive trials.

Own no incidental JVM-global state. Deliberate instrumentation changes use
`preserving-instrumentation-state` (`test/seon/test_support.clj:801`); it restores
through `seon.instrument/restore!` even after exceptions and leaves definitions replaced
by reload alone. Restoring an old callable over new protocols/classes is not safe.
Use the separate `preserving-schema-registry` (`:817`) when that is the subject.
Do not weaken contracts, fake SCI, or restore an obsolete schema to make a test pass.

## Execute and report the right proof

During a cut use the installed focused authority, not a suite per commit:

- `seon.test/run` (`src/seon/test.clj:1393`) is one request:
  `{:seon.test/execution <agent context source or execution handle>
  :seon.test/recording-connection <connection holding that branch>
  :seon.test/policy :named|:incremental|:platform|:all}` plus optional
  `:seon.test/identities`, `:seon.test/namespaces`, `:seon.test/changed`,
  `:seon.test/include-long?`, `:seon.test/check-time-limit-ms`. It selects, admits,
  runs every member on its own branch, records, and returns `:seon.test/passed?`,
  results, reuse, exclusions, pending/unfinished and per-member `:seon.test/timings`.
  `seon.test/tally` (`:1569`) renders it. On a live cluster from the MCP eval tool:
  `(let [h (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "NAME"))]
  (seon.test/tally (seon.test/run {:seon.test/execution h
  :seon.test/recording-connection (:seon.db/connection h) :seon.test/policy :named
  :seon.test/namespaces #{'my.ns-test}})))`. Agents use `(my.test/run)` and
  `(my.test/check {:seon.test/changed [...]})` (`src/my/test.clj`).
- `bin/test-check [--root PATH] [CLUSTER] --test NS/TEST | --ns NS | --changed SYM`
  sends that request over the cluster's prepl; exit 0 only when `passed?`.
- The worker launchers `bin/test`/`bin/test-fast` still exist but their workers hold
  no execution handle, so `with-database` tests refuse there (commit 5 retires them).
  Lanes never run cold gates, `--all` or `--full`. On a development root, members
  reaching a `:seon.fn/destroys` owner or declaring `:seon.test/fixture-observation`
  are excluded with their platform command (`host-exclusions`, `:1273`).

Record actual program identity, arming precondition, execution/reuse counts,
assertions, failures, errors and refusal/timeout categories. Zero executions with
valid recorded green evidence is reuse, not a fresh proof. Fixture-excluded or
unconfirmed work is not green. A standalone `clojure.test` call bypassing canonical
admission/recording is diagnostic only. Distinguish hot reload, in-place adoption,
new fork and cold boot; observe the real user-facing result separately.

Turn/context regressions follow the current
[turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
and [B2](../../../docs/prds/agent-platform/plan/lane-b2-walk-flow-fork.md): use virtual
provider replies through real procs, without paid model calls; test ordered durable
source/results, interruption without replay, private-context isolation, changed-read
refresh without replaying effects, unchanged historical shown text and loss of live
objects on restart. A target is an acceptance condition, not proof it is installed.
