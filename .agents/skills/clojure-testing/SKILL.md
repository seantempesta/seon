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
| Exceeding the declared test duration fails | **Enforced on reported completion:** `duration-failures`, `src/seon/test/runner.clj:360`, emits an assertion failure at `:end-test-var`. A nonblank `:seon.test/long` reason AND positive `:seon.test/long-ms` are required to raise the ordinary bound. | Measure the operation, put the reason beside the number, and distinguish cold/warm work. The runner cannot validate the measurement's honesty. This elapsed-body check is not a hard kill or a complete accounting of namespace fixtures/child cleanup. |
| An in-process request cannot wait forever | **Bounded observation:** `bounded-result`, `src/seon/test.clj:143`, uses `:seon.test/remaining-ms` and records unsuccessful completion. | Observation expiry does not prove the body thread exited. The current implementation deliberately lets resource scopes finish. Do not overlap another mutation or clean resources until actual exit is observed; retain isolation for uninterruptible work. |
| A refused fixture write stops setup | **Enforced when using `transacted!`:** `test/seon/test_support.clj:309` checks the real writer report and throws if no successful `:db-after` exists. `apply-config!` and `seed-cluster!` also check their production writer results (`:1039`, `:1058`). | Use these helpers. Calling `db/transact!` and discarding its returned refusal bypasses this setup check. Assert the subject-specific populated state too. |
| Tests enter with production contracts | **Enforced by initialization:** `seon.test.arm/arm-contracts!`, `src/seon/test/arm.clj:163`, loads the derived program and verifies actual wrapper coverage against armable Vars. | A later reload removes wrappers. `seon.test/run` is not an automatic arming operation; after reload, use the installed arming owner with the correct carried projection and positively verify relevant entering wrappers. Never hand-pick a smaller fake program. |
| Tests leave instrumentation intact | **Partially enforced:** `run-vars!` compares entering/exiting global state and adds errors (`src/seon/test/runner.clj:655`); `ambient-drift` checks wrapper membership, registrations, live clusters and SCI sizes (`:1617`). Worker `reassert-contracts!` repairs a reduced wrapper count (`:1866`). | A before/after set cannot prove entry was already correct, same-cardinality changes are not the re-arm count's proof, and unchanged SCI size is not unchanged contents. Restore deliberate mutations with the canonical scope. Automatic repair does not excuse leakage. |
| No hand-written production fixture maps | **Author rule, partially supported by validation:** the real writer checks schema validity. `program-fn-row` reads actual indexed artifacts or analyzes supplied source (`test/seon/test_support.clj:1026`). | There is no general detector for a map's hand-written origin or semantic fidelity. Use canonical declaration/config/cluster helpers. A schema-valid invented row may still model the wrong world. Synthetic data is legitimate only when the subject needs it. |
| No assertionless green | **Enforced:** `assertionless-failure`, `src/seon/test/runner.clj:343`, rejects executed tests with zero assertion evidence. `assert-check!`, `test/seon/test_support.clj:880`, requires a true property result and positive trial count. | One vacuous assertion can still pass. Prove meaningful inputs, subject presence and coverage. A reused recorded green is different from executing an assertionless test. |

## Canonical fixtures and custody

`seon.test-support/with-database` (`test/seon/test_support.clj:982`) calls the body
with a real connection on an isolated branch of the published fixture base. It does
not index source for each ordinary test. `with-branched-database` (`:946`) carries
the projection, owns the connection and retires the branch before releasing its lease.
Base acquisition and the branch's timed work are separate measurements; do not
replace a reusable base merely to repeat a test.

Use `program-fn-row` for program declarations (actual source symbol, or database +
symbol + synthetic source), `apply-config!` for a complete config overlay, and
`seed-cluster!` for the cluster/config path. `apply-config!` replaces the whole overlay;
it is not an incremental map merge (`test/seon/test_support.clj:1039`). Fixture
transactions go through `transacted!`. Use `:seon.test-support/extra-schema` only
for genuinely synthetic declarations. Never hand-roster the production schema.

Pass projection, environment, declared proc inputs and fixed render profiles as
production does. Use real SCI evaluation for SCI semantics; calling the same JVM
function does not prove context isolation or interpreted caller behavior. Stop an
agent graph before retracting facts it may still settle.

A branch isolates its datoms, connection and history. It does not isolate store-wide
blob keys, GC, filesystem locks, loaded JVM Vars/classes or shared SCI objects.
Store-global subjects use the existing `:seon.test-support/fresh-store?` route
(`test/seon/test_support.clj:921`); file/process subjects use the canonical published
file/root helpers with their required `:seon.test/fixture-observation` explaining why
a branch is insufficient (`:108`, `:145`). Do not fabricate a manifest or bypass
fixture admission to get a green. B4 owns replacing these costly fixture paths.

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

`await-event!` (`test/seon/test_support.clj:738`) waits on the required event under a
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
(`test/seon/test_support.clj:1116`) for separate release functions. Setup and cleanup
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
`preserving-instrumentation-state` (`test/seon/test_support.clj:1073`); it restores
through `seon.instrument/restore!` even after exceptions and leaves definitions replaced
by reload alone. Restoring an old callable over new protocols/classes is not safe.
Use the separate `preserving-schema-registry` (`:1089`) when that is the subject.
Do not weaken contracts, fake SCI, or restore an obsolete schema to make a test pass.

## Execute and report the right proof

During a cut use the installed focused authority, not a suite per commit:

- `seon.test/run` / `run-owned` (`src/seon/test.clj:541`, `:576`) admit and record
  requests with explicit program/result custody. `run-owned` supplies the agent's
  body custody; JVM callers must supply it. Read the complete returned evidence.
  Result-recording connection alone does not supply body custody (`:409`).
- `bin/test-fast --paths <owned files> -- <namespaces>` remains the shared-tree
  iteration launcher. Without `--paths`, it uses the working tree. Snapshot claims
  must match the command actually run. Missing published base is an orchestrator
  preparation problem, not permission to invent a fixture or worktree.
- The orchestrator owns affected integration and `bin/test --platform` at the cut
  checkpoint. Lanes never run cold gates, `--all` or `--full`. No full suite after
  each edit. Existing destructive admission stays in force; do not relabel a test
  or lie about its root to bypass it (`src/seon/test.clj:409`).

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
