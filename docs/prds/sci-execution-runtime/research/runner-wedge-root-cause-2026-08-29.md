---
type: research
status: complete
tags: [research, test, runner]
---

# Bulk-tier runner wedge root cause (2026-08-29)

## Verdict

The two retained runs did **not** wedge on a dead, unlaunchable, or idle
worker. In both runs `pool-8` was alive and still executing
`seon.render-simplification-test/distance-spends-only-real-ref-hops-and-caps-win`
inside the third `walk/neighborhood` call at
`test/seon/render_simplification_test.clj:303-329`; the coordinator's only
dumped worker-RPC thread was consequently waiting for that task's result.
Nine other worker JVMs were idle in the command loop. The issue's earlier
claim that every worker was idle is falsified by the retained worker dumps.

The runner defect is the unbounded RPC seam. Once a command is written,
`worker-rpc!` performs a bare pipe read and has no terminal event except a
reply or pipe EOF (`src/seon/test/runner.clj:1171-1182`). It does not race the
reply against `Process.onExit`, does not check the `PrintWriter`'s suppressed
write error, and does not enforce a declared task bound. The process-state
check occurs only *after* `readLine` returns EOF. An alive task that never
returns therefore parks its pool future forever; a worker death whose pipe is
kept open can do the same. The suite-wide silence watchdog eventually makes
the defect loud, but only after losing the per-task terminal outcome.

This is a complete root cause for the coordinator wedge. It does not diagnose
why `walk/neighborhood` remained active for more than the suite silence bound;
that is a separate test-or-production performance investigation outside this
research's owned paths.

## Authorities and dependency ledger

This diagnosis applies the paired requirements in `AGENTS.md:229-262` and
`AGENTS.md:522-574`: waits are event-driven **and** bounded, an unavailable
observation is typed rather than silent, and the test tally must account for
unconfirmed work. The relevant dependency seam is Java `Process`: the child
stdout `InputStream`, the child stdin `OutputStream`, `Process.isAlive`, and
`Process.onExit`. The first-party owners are:

- worker protocol and command loop: `src/seon/test/runner.clj:800-856`;
- launch and RPC: `src/seon/test/runner.clj:1115-1195`;
- pool dispatch and future collection: `src/seon/test/runner.clj:1276-1331`;
- confirmation classification: `src/seon/test/runner.clj:1333-1440`;
- tier ordering and suite watchdog: `src/seon/test/runner.clj:1499-1612`.

Both retained roots contain byte-identical copies of the current
`src/seon/test/runner.clj`, so these line numbers apply to the preserved runs.

## Exact sequence

1. `run-coordinator!` completed the platform tier and entered the bulk tier at
   `src/seon/test/runner.clj:1603-1612`.
2. `run-task-pool!` queued resolved tasks, submitted one serial drain future
   per pool worker, and later collected those futures with unbounded `.get`
   calls at `src/seon/test/runner.clj:1294-1329`.
3. The `pool-8` drain selected
   `seon.render-simplification-test/distance-spends-only-real-ref-hops-and-caps-win`.
   `execute-worker-task!` announced `BEGIN` only to coordinator stdout, then
   called `worker-rpc!` at `src/seon/test/runner.clj:1276-1286`. No dispatch
   journal is persisted: `announce!` only updates an in-memory atom and prints
   (`src/seon/test/runner.clj:213-222`).
4. `worker-rpc!` printed the `:run` command and flushed it, without checking
   `PrintWriter.checkError`, then entered `read-worker-protocol!`
   (`src/seon/test/runner.clj:805-808,1171-1174`).
5. Worker `pool-8` received the command and entered `run-task!` before it could
   publish `:task-complete` (`src/seon/test/runner.clj:828-834`). At each
   watchdog snapshot its main thread was active in the third
   `walk/neighborhood` call at
   `test/seon/render_simplification_test.clj:303-329`, beneath Datahike pull.
6. The coordinator's worker future remained in
   `BufferedReader.readLine` at `src/seon/test/runner.clj:1104-1113`; the
   coordinator main thread remained in `.get` at
   `src/seon/test/runner.clj:1327`. Neither call has a bound.
7. After 300 seconds without an `announce!`, the suite liveness backstop
   dumped coordinator and descendants and halted with exit 124
   (`src/seon/test/runner.clj:401-417` and the retained logs cited below).

There is no launch-failure transition in this sequence. `ProcessBuilder.start`
failure is already made a typed `::worker-launch-failure` at
`src/seon/test/runner.clj:1137-1145`; the separate defect is that a process
which exits *after* `.start` but before readiness leaves the parent in the same
unbounded `read-worker-protocol!` at `src/seon/test/runner.clj:1153`. During a
task RPC, exit is inspected only after pipe EOF at
`src/seon/test/runner.clj:1174-1182`. Thus an exit-vs-pipe race is missing, but
it was not the trigger in these retained runs: the relevant worker never
exited.

## Retained evidence

### `run.cYB3zX`

- `tmp/test-runs/run.cYB3zX/test-run.txt:1-5` identifies source
  `f2b476bf8ac360fb6e617f48114248b546eda533`, coordinator PID 33422, and
  watchdog exit 124 at 22:51:38Z.
- `tmp/test-runs/run.cYB3zX/tmp/test-liveness/33422-1787957498397.log:1-5`
  records the 300-second silence and the last completed task; lines 13-23
  record all ten child JVMs alive. Lines 37-67 show coordinator main waiting
  on the pool future at `runner.clj:1327`.
- `tmp/test-runs/run.cYB3zX/tmp/test-liveness/33422-1787957497901-threads.json:993-997`
  shows the one outstanding RPC in `read-worker-protocol!` → `worker-rpc!` →
  `execute-worker-task!`.
- `tmp/test-runs/run.cYB3zX/workers/pool-8/logs/worker-stderr.log:2` maps
  `pool-8` to PID 33478. Its dump,
  `tmp/test-runs/run.cYB3zX/tmp/test-liveness/33478-1787957497901-threads.json:77-110`,
  identifies the active test and the `walk/neighborhood` call. The other nine
  child dumps each place main at the command-loop read
  (`tmp/test-runs/run.cYB3zX/tmp/test-liveness/33479-1787957497901-threads.json:30`
  is representative).

### `run.ylfash`

- `tmp/test-runs/run.ylfash/test-run.txt:1-5` identifies source
  `a1dd591921d82aaabcf60ddf8995a5e882e0f8a6`, coordinator PID 37240, and
  watchdog exit 124 at 23:15:55Z.
- `tmp/test-runs/run.ylfash/tmp/test-liveness/37240-1787958955570.log:1-5`
  records the second 300-second silence; lines 13-23 again record all ten
  child JVMs alive, and lines 37-67 again show main waiting at
  `runner.clj:1327`.
- `tmp/test-runs/run.ylfash/tmp/test-liveness/37240-1787958955153-threads.json:851-855`
  shows the same outstanding RPC read.
- `tmp/test-runs/run.ylfash/workers/pool-8/logs/worker-stderr.log:2` maps
  `pool-8` to PID 37280. Its dump,
  `tmp/test-runs/run.ylfash/tmp/test-liveness/37280-1787958955152-threads.json:77-110`,
  identifies the same active test at the same call. The other nine child
  dumps are idle at `runner.clj:815`.

The repeated stack is stronger attribution than the absent console journal:
the active worker is the only worker whose parent RPC can still legitimately
be awaiting a task-complete frame. The retained data does not preserve the
random task id because task construction assigns it in memory and the `BEGIN`
line is not journaled (`src/seon/test/runner.clj:561-587,1276-1282`). The test
symbol, worker id, and PID are nevertheless exact from the child stack and
worker log.

### The confirmation line is fixture output

Both runs contain the purported launch failure in the ordinary `pool-1` log:
`tmp/test-runs/run.cYB3zX/workers/pool-1/logs/worker-stderr.log:6` and
`tmp/test-runs/run.ylfash/workers/pool-1/logs/worker-stderr.log:7`. It is
emitted by
`unlaunchable-confirmation-worker-does-not-suppress-the-tally`, whose injected
`confirm!` function throws `::worker-launch-failure` without starting a
process (`test/seon/test_runner_test.clj:158-209`). The production
confirmation path had not begun: neither root contains a
`confirmation-launch.edn`, which the real path writes before calling
`start-worker!` (`src/seon/test/runner.clj:1333-1388`).

The `tests.edn` found in each root is a symlink to the repository's dead Kaocha
configuration, not a task manifest or journal. Its zero-live-reference status
was already measured in
`docs/prds/sci-execution-runtime/research/testing-story-2026-07-27.md:61-70`.
The only run journal is `test-run.txt`, and it records process transitions but
not task dispatches. Therefore it cannot support the issue's original
confirmation-worker attribution.

## Seam constraint: make the wedge unconstructable

**Constraint:** there may be no direct worker pipe read. Readiness,
initialization, ordinary task execution, confirmation, and stop must all use
one total worker-exchange seam whose input carries the worker process, worker
identity, command/task identity, and declared completion bound. Before writing
the command, the seam persists or durably appends its dispatch identity; then
it checks the command write and races exactly three terminal events:

1. one complete, parseable protocol reply;
2. the exact child process's `Process.onExit` future; or
3. the declared operation deadline.

The exit registration must happen before the checked write so a child cannot
die in the observation gap. `PrintWriter.checkError` after flush converts a
suppressed broken-pipe write into the same typed terminal result. Reply wins
only with the expected worker id and task id. Exit carries the exit value and
stderr path. Deadline carries worker id, PID, task symbols, dispatch instant,
and the missing expected event. Exit or deadline retires that worker before it
can receive another command.

For confirmation tasks, the existing `unconfirmed-confirmation` classifier at
`src/seon/test/runner.clj:1352-1374` can consume the typed exchange failure and
preserve the already-complete tally. For ordinary pool tasks the same failure
must become an attributed task result included in the total tally; it must not
escape into an unbounded future. `start-worker!`'s readiness read at line 1153
must use the same seam with a readiness bound.

Racing only reply against process exit is insufficient for the preserved
failure: both `pool-8` processes were alive. The third terminal event—the
declared task bound—is required by the bounded-execution law. The 300-second
silence watchdog remains a last-resort suite falsifier, not the normal way a
single task acquires a terminal result.

The smallest regression matrix for the eventual implementation is: injected
launch refusal, exit before readiness, kill after command acceptance, checked
write failure, a live worker whose task exceeds its bound, and an ordinary
reply. Every case must yield one attributed terminal value without waiting for
the suite watchdog.

## Masking: make stale green evidence visible

The current runner already has most of the data model. A test row carries
`:seon.test/ns`, latest pass/fail/error counts, `:seon.test/run-basis-t`, and
`:seon.test/run-at` (`resources/seon/schemas/seon.test.edn:1-10,31-75`), and
`record-tx` replaces those latest result facts atomically
(`src/seon/test/runner.clj:876-915`). What is missing is a durable home for the
bare gate: `run-coordinator!` records results only when `cluster-name` is not
`"-"` (`src/seon/test/runner.clj:1620-1630`), while its only green memory is the
checkout-local file written after an entirely green run
(`src/seon/test/runner.clj:1077-1088,1649-1650`). The plan already calls that
file temporary until an in-server database home exists
(`docs/prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md:932-938,1060-1062`).

The cheapest visibility change is therefore not a new status mirror:

1. give recurring bare-gate result transactions one persistent, operator-owned
   results branch and commit each completed task's existing test-result facts;
2. have `bin/seon status` (and the test status query it calls) join the current
   program's test rows by `:seon.test/ns` and derive, per namespace, whether all
   current tests have latest green evidence, the evidence floor
   (`run-basis-t` and oldest `run-at`), and which tests are absent or red; and
3. compute `N days ago` from `now - run-at` at render time—never store age.

The honest display is, for example, `namespace X: all current tests last known
green; oldest proof basis T, N days ago`. If any current test lacks result
facts, the namespace is `unknown`, not green. A platform-red or wedged run does
not invent a new result, but it also cannot hide the old one: its age continues
to increase and status exposes the stale proof. This uses the result facts as
the asserted observations and derives namespace health; it does not store a
second namespace-green boolean.

If the product requires the stronger sentence “namespace X last ran as one
complete cohort at basis T,” the existing `:seon.test.run/id`, `/at`, and
`/git-sha` shape (`resources/seon/schemas/seon.test.run.edn:1-9`) must be
transacted as the canonical run event and related to its selected tests and
terminal outcomes. That is more machinery than the cheapest stale-evidence
status and is not required to expose the 11-day masking interval.

## Proof boundary

No tests or cluster operations were run. All conclusions come from source and
the two retained roots, which were read without modification. The evidence
proves the active task, worker liveness, and unbounded parent wait; it does not
prove whether the repeated `walk/neighborhood` execution was infinite or
merely slower than 300 seconds.
