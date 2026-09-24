---
type: landing
status: committed, not installed; root verification pending (load rule)
lane: review-f4-panic-stops-graph
created: 2026-09-24
reports:
  - docs/research/agent-platform/review-landings-2026-09-24.md (F4)
---

# Lane review-f4-panic-stops-graph (2026-09-24)

What exists: `var-process` wraps every proc step and records its throw through
`seon.fault/fault!`, which throws under `:panic` and on unstored faults in both modes.
Flow owns stopping (`flow/stop`, core.async `dc35f3e` `flow/impl.clj:174-183`). Approach:
the wrapper stops the proc's own graph through Flow, from the proc's thread, without
joining it.

## Broken or unproven, first

- **Not run.** Under the load rule this lane ran no test request and did not load
  `seon.flow` into default. The only evidence is lint and pure core.async probes.
- **Behaviour change for callers outside this lane.** The default `:seon.config/on-core-error`
  is `:panic` (verified: `(:seon.config/on-core-error seon.config/defaults)` returns `:panic`).
  Under it, every graph started by `start-graph!` now stops at its first proc panic. This
  covers agent graphs, the cluster graph (armer and render) and the fault committer. Any test
  outside `flow_test` that expected a panicking proc to keep processing now goes red. Those
  tests need `:record`, or their assertions change to expect the stop.
  `flow_test/a-proc-throw-during-work-…` now sets `:record`.
- **Status and page.** The stopped graph is not yet shown as failed in status or on the page,
  beyond the stored fault and the pings that stop answering. This lane does not own that
  surface.
- **Graphs built with bare `create-flow`** (tests only) have no graph reference. In those, only
  the panicked proc stops.
- **Probe leak.** One mistaken probe (`inject` of 50 messages into a paused proc) timed out.
  It may have left a paused throwaway graph, plus a parked virtual thread, in default's
  namespace `tmp.review-f4-probe`. The session answered normally afterwards.

## Change

- `start-graph!`: each proc's `:args` gets `::graph-ref`, a promise channel. It is filled
  with the graph before `flow/start`.
- `var-process`: the step closure is built per start, over the proc's own `::flow/control`
  tap and `::graph-ref`. `::graph-ref` is removed before the init arity runs, so it never
  enters state or ping.
- `proc-failed!`: answers `true` on a panic (it prints as before) and `false` when the fault
  is recorded.
- On a panic in a transform, or in a pause or resume transition,
  `stop-panicked-graph!` does two things:
  1. It `put!`s Flow's stop command onto the proc's own control tap. The loop reads control
     before any input (`impl.clj:287-295`), so no queued input runs.
  2. It calls `flow/stop` on the graph, which sends the command and closes the channels. It
     does not join.
- Stop acknowledgement: a panic inside the `::flow/stop` transition does not stop the graph
  again. The proc is already exiting.
- Kept as before: synchronous durable recording, the cause on the throwable and the bounded
  print.

## Evidence (default JVM, throwaway namespace `tmp.review-f4-probe`, pure core.async)

- Own-tap stop plus `flow/stop`, with inputs `[0 1 2 3]` queued while paused, and input 1
  triggering the stop: the proc saw `[::flow/resume 0 1 ::flow/stop]`. Took 310 ms, including
  a 300 ms sleep.
- `flow/stop` alone showed the same sequence in one run. But it reaches the tap only through
  the mult's go block, so it is racy. The own-tap put makes the ordering deterministic.
- `clj-kondo --lint src/seon/flow.clj` shows the same warnings as HEAD. A `promise` was
  replaced by a promise channel to satisfy the discouraged-var rule.
- Net lines: `src/seon/flow.clj` +53 −22, `test/seon/flow_test.clj` +48 −2.
- Timing: nothing on the hot path. There is one `update-vals` per graph start and one `poll!`
  per panic. No before/after numbers were taken (no run allowed).

## Root runs

```
bin/test-check default --test seon.flow-test/a-panicking-transform-stops-its-graph-and-a-recorded-one-continues
bin/test-check default --test seon.flow-test/an-unstored-panic-prints-bounded-text-and-keeps-the-whole-cause
bin/test-check default --test seon.flow-test/a-proc-throw-during-work-is-one-occurrence-never-dropped
bin/test-check default --test seon.flow-test/a-throwing-stop-transition-is-one-stored-fault-and-a-completed-join
bin/test-check default --ns seon.flow-test
bin/test-check default --ns seon.cluster.agent-test   # panicking agent procs now stop their graph
bin/test-check default --ns seon.turn-loop-test
```

Adoption: `src/seon/flow.clj` must be reloaded, together with its dependents that construct
graphs. **RESET NEEDED** only if adoption cannot re-arm running graphs. Graphs that are
already running keep their old launchers until they are rebuilt.
