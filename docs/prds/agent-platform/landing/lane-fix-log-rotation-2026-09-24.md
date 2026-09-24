---
type: landing
status: landed
lane: fix-log-rotation
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/execution-2026-09-24.md (Logging row)
---

# Lane fix-log-rotation — landing (2026-09-24)

`seon.maintenance/rotate-logs!` copied `seon.log` and then truncated it. Lines appended
between the copy and the truncate were lost. It now runs the copy and the truncate while
holding the monitors of the writer's own streams, `System/out` and `System/err`.

## Not done / limits (read first)

- **Not installed on default.** Pid 90471 runs archive `ce7384682` with hook publication
  off, so this edit cannot reach it. **RESET NEEDED** (or `start --head`) to install it.
  No `bin/test-check` request was run: on default it would test the archive's old code.
- The proof below ran plain `clojure.test` in a `git archive` snapshot of HEAD `0cb5fec13`
  plus this diff. `reference-code` was symlinked to the checkout, and the checkout's
  `core.async` submodule differs from HEAD's gitlink (`+fb21fc0`). This run was not a
  `seon.test/run` member, so contracts were not armed.
- **Guarantee boundary.** The fix is lossless only for bytes written through this JVM's
  `System/out` and `System/err` PrintStreams. Those carry Clojure's root `*out*` and
  `*err*`, timbre's println appender and slf4j-simple. Bytes the JVM writes natively to
  fd 1/2 (VM warnings, SIGQUIT thread dumps) do not take the monitor. They can still be
  lost in the window, which is now about 5 ms.
- The guarantee holds only when rotation runs inside the JVM that writes the log. That is
  how it runs today: the root schedule fires it in-process (`schedule.clj:52`, log-dir
  from the running instance at `:561`).
- `.clj-kondo/config.edn:54` warns on every `locking` use. A Flow proc or atom cannot
  stand in here: the lock that must be held is the JDK PrintStream's own. Other src
  owners already use `locking` (`cluster.clj:964`, `profile.clj:187`).

## Evidence

- **Writer, verified.** `lsof` on the live log shows fd 1w and 2w of pid 90471 on one
  inode. `script/seon/operator.clj:531-534` starts that JVM with `redirectErrorStream`
  and `Redirect/appendTo log`. In pid 90471, `System/out` and `System/err` are both exactly
  `java.io.PrintStream` and are two separate objects. In JDK 26.0.1 (`src.zip`
  `java/io/PrintStream.java:403,424,504,...`) every write and flush runs in
  `synchronized (this)`.
- **Reproduced in pid 90471 on a scratch file under `tmp/`** (never `seon.log`). One
  append thread wrote while the unchanged `seon.maintenance/rotate-logs!` rotated 20
  times (retained 30). 48,293 lines were written and 47,712 were present: **581 lost**.
- **Fix probe in a scratch namespace**, removed afterwards. With the same logic under
  both monitors and two writers holding `System/out` and `System/err` respectively:
  97,689 written, 97,689 present, all distinct.
- **Regression:** `seon.maintenance-test/rotation-keeps-every-line-written-during-it`.
  Two writers append numbered lines, each under one stream's monitor, while
  `rotate-logs!` rotates 20 times. It asserts that every number from 1 to N appears
  exactly once across `seon.log` and `seon.log.1`–`.30`.
  - Snapshot with the fix: this test and `live-log-inode-is-bounded-and-archived` pass,
    6/6 assertions, 210 ms.
  - The same snapshot with HEAD's `maintenance.clj`: the new test **fails** on its
    equality assertion.

## Numbers

- 20 rotations with 5 ms sleeps between them: 216.8 ms before, 215.0 ms after. There is
  no regression.
- One rotation of a 30 MB log, measured in pid 90471: **4.9 ms**. This is how long
  writers block. The live log is 30.6 MB.
- Net lines: src +16/−10 (the added lines are mostly the docstring and comment), test
  +44/−0.

## Files

- `src/seon/maintenance.clj`: `rotate-logs!` only
- `test/seon/maintenance_test.clj`: the new deftest plus the `clojure.string` require
- this note
