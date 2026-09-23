---
type: issue
status: resolved
severity: blocker
tags: [issue, test, agent, turn-loop, bootstrap-drive, wave/live-drive-context]
---

# `one-fake-o1-drive-grades-on-its-ending-commit` is red at HEAD: the drive episode returns `:failed`

`seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit`
fails with 4 failures and 3 errors at `steward-platform` HEAD. It is the
namespace's only real-graph test, so `seon.bootstrap-drive-test` cannot go
green, and any lane touching `src/seon/bootstrap_drive.clj` gates against a
red it did not cause.

## Evidence, 2026-09-17 00:05 and 00:13

Two `bin/test-fast` runs, one with a lane's working-tree changes to
`src/seon/bootstrap_drive.clj` overlaid and one at bare HEAD with none of
them, produce **byte-identical** failure sets — same four assertion lines,
same three errors:

| run | overlay | tests / assertions | verdict |
|---|---|---|---|
| `tf2` | seven lane files | 44 / 236 | 4 failures, 3 errors |
| `tf3` (baseline) | `src/seon/search.clj` only, identical to HEAD | 2 / 14 | 4 failures, 3 errors |

The failures, in order:

```text
bootstrap_drive_test.clj:44  expected :completed, actual (not (= :completed :failed))
bootstrap_drive_test.clj:47  expected {:p1a true :p1b true :p1c true}, actual (not (= ... {}))
bootstrap_drive_test.clj:50  expected "deepseek-v4-flash"
bootstrap_drive_test.clj:52  expected :disabled
```

The three errors are a cascade, not independent facts: with the outcome
`:failed` the report carries no transcript, so `(re-find #"\(help\)" nil)` at
`bootstrap_drive_test.clj:53-58` throws
`NullPointerException ... because "this.text" is null` out of
`java.util.regex.Matcher.getTextLength`.

The grade map is **empty**, which locates the failure precisely: grading runs
after `eval.drive/run-episode!` returns, so the episode failed before any
grading function was called.

The one substantive line in either run's log is a writer rejection during the
drive:

```text
:error datahike.writer [157 41] :datahike/write-rejected
  {:kind :seon.turn/refused, :cause "run transition refused: agent-already-running"}
```

## What is NOT established

The rejection above is the only anomaly visible in the log, and it is a
hypothesis, not the named cause. Nothing here probed the drive's cluster
while it ran, read the episode's own terminal fact, or established whether
the rejection is the failure or an ordinary loss-tolerant refusal that
happens alongside it. `remote-timeout-ms` on the request is `120000`
(`test/seon/bootstrap_drive_test.clj:41`) and the episode's waits are
`seon.eval.drive/await-fact!` calls at `src/seon/eval/drive.clj:332-337`, so
"the objective wait expired" is the other obvious candidate and is equally
unverified. Whoever takes this reads the episode's terminal entity first.

## Boundary

Two `bin/test-fast` runs on this machine under slot contention, both with
`SEON_TEST_SILENCE_SECONDS=1800` (see
[the cold fixture base outruns the liveness silence backstop](the-cold-fixture-base-outruns-the-liveness-silence-backstop.md)
— without it this test's silence trips the watchdog at exit 124 before it can
report). No cold `bin/test` run, no prepl evaluation, no live cluster
inspection. Whether this is a recent regression or long-standing was not
established: no bisection was done.

## Resolution (lane cut-l1, 2026-09-23)

`src/seon/bootstrap_drive.clj` and `test/seon/bootstrap_drive_test.clj` are deleted
(cut analysis `e6f1c0e7a` §5): the namespace had no caller outside its own test and
booted a scratch root per drive, which the one-JVM law forbids outside the platform
tier. The red test leaves with its mechanism.
