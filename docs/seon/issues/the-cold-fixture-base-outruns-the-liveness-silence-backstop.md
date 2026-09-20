---
type: issue
status: open
severity: friction
tags: [issue, test-fixture, bounded-execution, parallel-stress-triage, load-time]
---

# A cold canonical fixture base outruns the 300 s liveness silence backstop whenever lanes share the machine

`bin/test`'s liveness backstop fires after `SEON_TEST_SILENCE_SECONDS`
(default 300, `src/seon/test/runner.clj:530`) without reporter progress, and
the cold canonical fixture base emits NO reporter progress while it builds:
the first `seon.test-support/with-database` in a worker sits in
`retrying-base` while `seon.fn/index!` derives contract facts and assigns
tempids for the whole program. With several lanes' JVMs sharing this machine,
that build exceeds 300 s and every one of those runs dies at exit 124 with a
thread dump instead of a verdict.

## Observed, 2026-09-16

Four independent `bin/test-fast` invocations fired the backstop within twenty
minutes of each other, at three different first tests, all of them the
worker's first fixture base:

| pid | diagnostic log | last progress before the dump |
|---|---|---|
| 95401 | `tmp/test-liveness/95401-1789598207079.log` | `seon.test.runner-test/executor-submissions-carry-the-callers-handed-projection` |
| 20238 | `tmp/test-liveness/20238-1789599229574.log` | `seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit` |
| 20078 | `tmp/test-liveness/20078-1789599213201.log` | `seon.search-test/index-step-contract-has-durable-generative-host-predicates` |
| 20611 | `tmp/test-liveness/20611-1789599278499.log` | `seon.program-test/typed-cross-namespace-deletion-retracts-function-and-test` |

95401 fired at 16:36, before the earliest edit in the working tree
(`resources/seon/schemas/seon.program.edn`, 16:44:35), so this is not one
lane's change. Three of the four runs belonged to different lanes. The top
frames in 20078's dump are `seon.fn/index-tempids` (`src/seon/fn.clj:2416`)
inside a `pr-str`-keyed `PersistentTreeSet` insert — every comparison prints a
form — which is where the silent minutes go.

Two lanes were already working around it: their live command lines carry
`SEON_TEST_SILENCE_SECONDS=900`. A workaround spreading by copy between lanes
is the signal that the bound is wrong, not the machine.

## Second observation, 2026-09-16 23:45 — the drive itself, not only the base

A fifth firing, pid 78404, diagnostic log
`tmp/test-runs/run.e59PbD/tmp/test-liveness/78404-1789602345875.log`, same
last progress as row 20238 above:
`BEGIN test seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit`.

Its thread dump refines what the silence is for THAT row. `main` is not in
the fixture base: it is `WAITING` on a `CountDownLatch` inside
`seon.eval.drive/await-fact!` (`src/seon/eval/drive.clj:71`, called from
`run-episode!` at `:337`), below `seon.bootstrap_drive/one-drive!`
(`src/seon/bootstrap_drive.clj:382`) and `run-drives!` (`:458`). The test had
already got past its base — the log carries the drive's own cluster line,
`seon bootstrap-drive-57c34ffd view: http://127.0.0.1:7841` — and was waiting
on the episode's terminal fact while the drive's agent loop ran.

So this issue has two silent surfaces, not one. The cold base is the surface
in the other rows; for the bootstrap drive it is the episode wait. Both are
the same defect shape — a real execution surface that publishes no progress
the backstop can see — and both are fixed the same way, by feeding the
surface's own events to the `progress` atom `start-liveness-backstop!`
watches. `seon.eval.drive/await-fact!` already takes a description string for
each wait (`"bootstrap <id>"`, `"objective <id>"`), so it already has the
event; it just does not publish it.

One further note on this row specifically: the test declares its own duration,
`^{:seon.test/long "171.859 s pool: ..."}`. That declared 171.9 s sits inside
the 300 s silence horizon with only 128 s of margin, which three concurrent
test JVM slots consume. `exchange-bound-seconds`
(`src/seon/test/runner.clj:547-555`) already derives the per-exchange bound
FROM `silence-seconds` for exactly this reason; a declared `:seon.test/long`
duration is the same kind of fact and is not consulted anywhere.

Working around it once more to get a verdict: this lane's re-run used
`SEON_TEST_SILENCE_SECONDS=1800`, which is the third distinct lane observed
carrying that variable on its command line.

## Why this is the absence-reads-as-health class

The backstop is correct to exist (AGENTS.md §2.3), but it is watching the
WRONG signal. It waits on *reporter* progress, and the fixture base is a real
execution surface that reports none — so a perfectly healthy build is
indistinguishable from a wedge, and the only distinguishing action available
to a lane is to raise the number until the red goes away.

## What would fix it rather than tune it

The base build should publish its own progress the way every other interface
publishes readiness: `seon.test-support/retrying-base` knows when it starts,
which phase `populate-database!` is in, and when it completes. Feeding those
events to the same `progress` atom `start-liveness-backstop!` watches makes
the silence bound mean what it says, and a base build that genuinely wedges
still fires — naming the phase that never arrived instead of dumping every
thread in the JVM.

The `pr-str` comparator in `seon.fn/index-tempids` is a separate cost worth
measuring once the phase is visible.

## Boundary

Observed from four liveness diagnostic logs and the process table on one
machine under four concurrent lanes. No change was made; no measurement of
the base build's cost on an idle machine was taken, so "exceeds 300 s" is
established only under concurrent load.

## Slice 0 observation — 2026-09-22

The one-JVM redesign lane's fast snapshot at `a70995402`, PID 31591,
reported `failed-loaded-definition-scope-restores-arming` beginning at
`2026-09-20T17:08:19.927835Z` and ending at `17:11:27.411151Z`: 187.483 s
including first fixture acquisition, without a reported failure. This test's
body was unchanged by slice 0. A `jcmd 31591 Thread.dump_to_file -format=json`
sample during the silence showed `main` waiting in
`seon.test-support/retrying-base` → `acquire-base!`; the
`seon-test-database-base` thread was awaiting the Datahike transaction from
`populate-database!` → `create-base`. The dump includes virtual threads.
This observation establishes fixture population during the interval, not
that the test body itself took 187 s. No backstop fired and no timeout was
raised. Reducing that cost and enforcing the owner's per-test bounds remain
outside the guard-deletion slice.

## Error-contract iteration — 2026-09-23

The class-1 fast snapshot started its first `seon.error-test` member at
`2026-09-20T20:58:00.971776Z`. A sample at JVM elapsed 185.06 s found main
in `retrying-base/acquire-base!`, and the fixture thread in
`populate-database!` waiting on the Datahike transaction. This repeats the
whole-program population observation above; no timeout override was used.
The permitted initial indexing cost must be reported separately from test
body work, rather than hidden by a per-test exception.

After fixture acquisition, existing error tests also exceeded five seconds:
`a-prepared-message-keeps-its-id-when-the-transaction-repeats` measured
8.610 s, `recurrence-counting-does-not-require-a-notification-threshold`
11.878 s, and `recurrence-identity-is-the-complete-observations-stable-evidence`
38.107 s (reporter begin/end timestamps). The new focused recording
regression took 4.964 s. Those observations do not identify the slow
transaction algorithm; they establish that the existing suite does not yet
meet the new bound. No test metadata was widened to conceal these costs.
