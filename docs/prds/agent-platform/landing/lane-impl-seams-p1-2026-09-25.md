---
type: landing
status: committed, unadopted (root adopts P0, P2 and P1 together)
created: 2026-09-25
scope: lost-error seams cut P1 (per-proc fault port; graph-wide stop off the panicked proc's thread), design lost-error-seams-design-2026-09-25.md §0.1
---
# Landing: seams P1, fault port and stop

What exists: Flow's `ProcLauncher` SPI hands each proc its `:outs` and its control tap, and `var-process`
reifies that SPI.

The data flow now works like this:
- A loop-level failure goes to a per-proc `WritePort` that records it on the reporting thread.
- A panic requests the graph-wide `flow/stop` on a virtual thread.
- The panicked proc closes its own inputs with Flow's `::flow/input-filter` and keeps reading control until
  that stop arrives.

## Broken or unproved

- **Nothing was run.** No test, no namespace load, no adoption. Root runs the tests.
- **Requires P2 loaded.** `proc-failed!` passes `:seon.fault/stop-request!`, which `fault!` only honors after P2.
  With a pre-P2 `fault!` the key is ignored, and `var-process` still issues the same stop after the panic.
- **G16 is not done.** Refusing `:compute` in `var-process` would contradict the law "procs declare `:io` or
  `:compute`", so G16 stays open (design §G).
- **Size.** Source is +102/−33, mostly docstrings giving the dependency evidence; the logic is about 45 lines.
- **The error fan-out and committer still exist (R1).** They now receive nothing from `var-process` procs.

## What changed

| Path | Change |
|---|---|
| `src/seon/flow.clj` | New: `fault-port`, a `WritePort` that commits the synchronous handler under its lock and then disposes. It is substituted for `::flow/error` in the `:outs` that `var-process` forwards, so Flow's sliding error channel, which `flow/stop` also closes early, no longer carries proc faults. New: `request-graph-stop!`, which runs `flow/stop` on a virtual thread. `proc-failed!` gains an arity that passes the owner's stop request to `fault!`. `var-process` builds one idempotent `stop!` per proc and closes a panicked proc's inputs through `::flow/input-filter`. A loop-level Flow report is recorded as one fault by reference: pid, status, cid, op and count, never the proc state. New: `write-port?` predicate |
| `resources/seon/schemas/seon.flow.edn` | `:seon.flow/write-port` |
| `test/seon/flow_test.clj` | `a-panicked-proc-with-saturated-control-still-stops-its-graph` |

## Deleted

- `stop-panicked-graph!` (13 lines): the self-stop through the proc's own tap, followed by `flow/stop` on the
  panicked proc's thread. That was the deadlock.
- The `failed!` closure's `stopping?` branch (2 lines).
- The `(step control graph-ref)` plumbing (3 lines).

## Evidence

- **The deadlock, reproduced** in the read-only probe (`tmp/impl-seams-p1/probe.clj`, recorded in design §0.1).
  A proc stopped by its own tap, followed by about 20 commands: 10 command threads blocked in `>!!` and the
  stopping thread blocked on Flow's lock.
- **The fix shape** (`tmp/impl-seams-p1/p1-fix-probe.clj`, scratch namespace, stub panic, no database):
  - one proc stalled in its step with 25 `ping-proc` commands queued;
  - after release, the step throws, its inputs are closed, and `flow/stop` runs on a virtual thread;
  - all 25 commands complete and 0 stay blocked, 1.6 ms after release;
  - the graph then answers "flow not running";
  - the queued `:after` input never ran (`:seen []`).
- The six fault-port cases from the P1 probe still apply to the port as built.
- **Lint:** `src/seon/flow.clj` has the same 5 warnings as HEAD. `test/seon/flow_test.clj` is also at 19
  warnings, with none in the new test.

## Root runs (after adopting P0, P2 and P1 together)

- `bin/test-check default --ns seon.flow-test`, which includes the new saturated-control test and the existing
  `a-panicking-transform-stops-its-graph-and-a-recorded-one-continues`
- `bin/test-check default --ns seon.cluster.agent-test`, because agent graphs are `var-process` procs
- `bin/test-check default --ns seon.cluster.turn-test`
- P2's and P0's lists
