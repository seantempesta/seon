---
type: prd
status: active
tags: [prd, testing, runtime, operator]
---

# In-server test execution: one runner, three callers

## Decision

Owner-approved direction (2026-08-03): one test-run mechanism with three
callers — `bin/test` detecting a live system and dispatching the fast tier
INTO it; a human at the REPL calling the same function; a root agent
running tests as ordinary in-system work. The spawned-JVM path remains
permanently for `--full`, everything declaring `:seon.test/long`
(process/boot tests are ABOUT fresh JVMs), and the no-live-system
fallback. Grounding and measurements:
[in-server-tests-2026-08-03.md](../sci-execution-runtime/research/in-server-tests-2026-08-03.md).

The measured prize: single-namespace iteration 10.32 s → 28–51 ms
(~250×). The honest scope: the win is the fix loop, not the full gate —
the fast tier's remaining bulk is test bodies.

## Verified constraints (from the research, all probed)

- The live cluster JVM launches `-M:dev`; `test/` is NOT on its classpath,
  and a classpath cannot be extended from a prepl eval (fresh
  `DynamicClassLoader` per evaluation). The unlock is the launch line:
  `-M:dev:test`.
- Fixtures are in-server-safe: `with-database` is fully in-memory
  (branch-of-memory-base, lease-pooled); 50 consecutive in-process runs
  held heap flat at 99 MB, 24.8 ms/run, zero disk writes.
- The runner's `run!` is reusable unchanged; only `-main` owns
  `System/exit`, the fatal `Runtime.halt(124)` backstop, and recording —
  that is the seam. A halt is fatal in a live JVM and must not exist on
  the in-server path.
- The soft-reference class-eviction hazard
  ([issue](../../seon/issues/long-lived-jvm-loses-soft-referenced-dynamic-classes.md))
  means the first falsifier is the `require :reload` recovery of an
  evicted namespace — proven in an EXPENDABLE cluster, never first
  against the live default.

## Implementation order (five slices)

1. **`-M:dev:test` launch** (one line in `script/seon/fresh_operator.clj`)
   + `:seon.test/long` lifted from var metadata into an indexed fact so
   tier selection is a query, not a namespace load. (The fast-tier lane's
   demotion reasons ride the same fact.)
2. **`seon.operator/test!`** — the ninth verb: reuses `runner/run!`;
   selection by namespaces, changed-set, or tier; returns the structured
   report as a VALUE (never exits, never halts); an interruptible run
   thread with the reporter-event liveness detector and the silence clock
   as a loud last-resort returning an error value. Test namespaces
   `require :reload`ed before the run in program-graph dependency order
   (compose with the operator PRD's `reload!` design).
3. **`bin/test` dispatch** — detect a live advertisement + answering
   prepl → send the fast tier through `test!`, print the report, exit
   with its status; fall back to the spawned path when absent; `--full`
   always spawns. The changed-test selector calls the same function
   (deleting its duplicate-analysis path is in scope — the research filed
   it).
4. **Agent access** — `test!` reachable as ordinary work (it is compute
   returning a report value; running tests is not a capability — no
   receipt beyond the eval's own), results renderable via a declared
   report renderer (both faces, per the render-producers rule) and
   shaped identically to the accretion gate's report (two substrates,
   one result shape — the recorded conflation warning).
5. **Store hygiene proof** — a full fast-tier in-server run measured for
   heap, thread, and store growth; the lease pool returns to baseline;
   repeated runs stay flat.

## Falsifiers

- Evicted-class recovery: `require :reload` restores a deliberately
  evicted namespace in an expendable cluster.
- A test that wedges is interrupted; the report carries the flat error;
  the cluster survives and answers evals afterward.
- A test that throws never reaches `halt`; exit codes appear only in
  `bin/test`'s process, never the server's.
- The same namespace selection produces the same pass/fail verdicts
  in-server and spawned (spot equivalence on three namespaces).
- Reload-before-run picks up an edit made after the JVM booted.
- Heap/threads/lease-pool flat across 50 consecutive runs (re-proving
  the research probe through the real verb).

## What not to build

- no second runner or reporter — `run!` and the structured report are
  the one mechanism; the accretion gate shares the report shape only;
- no `System/exit`, `halt`, or fatal backstop on the in-server path;
- no in-server `--full` — process/boot tests keep their fresh JVMs;
- no test-code capability door — tests are compute; effects inside tests
  hit the same doors any code does;
- no classpath mutation machinery — the launch line owns the classpath.

## Graduation

`bin/test seon.blob-test` against a live system returns in under two
seconds end to end; `(seon.operator/test! ...)` from a bare REPL attach
returns the structured report; a root agent runs a namespace's tests
through an ordinary eval and reads the report in its next turn; the
spawned fallback still works with no live system; the full gate is
unchanged.
