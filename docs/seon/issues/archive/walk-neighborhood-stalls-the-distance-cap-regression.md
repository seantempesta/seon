---
type: issue
status: resolved
severity: blocker
tags: [issue, render, performance, test, wave/render-acquisition-performance]
---

# walk/neighborhood stalls the distance-cap regression

## Problem

`seon.render-simplification-test/distance-spends-only-real-ref-hops-and-caps-win`
ran past the suite's 300-second liveness horizon in BOTH 2026-08-28
bare-gate wedges (retained roots `tmp/test-runs/run.cYB3zX` and
`run.ylfash`): worker `pool-8`'s thread dumps show it inside
`seon.render.walk/neighborhood` with no return, while the coordinator
waited on the reply. Whether a genuine non-termination or the known
slow-derivation class (the one-pull restructure's case — the live
`render-ai` proof already timed out past 60 s at clip-ripout landing),
a 300 s+ walk is a defect by the fast-by-default standing order, and
this member blocks every bare gate now that the platform tier is green.

## Evidence

- [Runner-wedge root cause](../../prds/sci-execution-runtime/research/runner-wedge-root-cause-2026-08-29.md):
  pool-8 alive and executing this test in both wedges; the
  launch-failure hypothesis falsified (the `confirmation-7 unlaunchable`
  line was injected output from `test/seon/test_runner_test.clj:158-209`).
- Thread dumps under each retained root's `tmp/test-liveness/`.

## Owner

`seon.render.walk/neighborhood` and the acquisition path this test
exercises; diagnose with one REPL reproduction of the test's fixture
shape before attributing (hang vs pathological cost), then fix the
cause, not the timeout. The runner-side bound (per-task deadline +
process-exit race) is the separate wedge issue's seam and does not
close this one.

## Acceptance

The test completes in single-digit seconds on the pooled worker, and a
bare `bin/test` run passes through the bulk tier without the liveness
watchdog firing on this task.

## Resolution (2026-08-29)

Diagnosis (walk-stall lane, from the preserved wedge dumps + a fresh
probe): pathological cost, not a hang — the selector recursively
expanded all 126 ref attributes to the requested distance before the
node cap applied (distance 0/1 = 10.5/13.8 ms; distance 4 with
max-nodes 1 ≈ 50 s). Fix: acquisition depth floors at max-nodes - 1 —
an implication of the existing cap, not a tuned constant (a member at
depth d costs d+1 nodes). After: 8.5 ms acquisition, 46 ms render;
the distance-cap regression strengthened and green in two full
integration rounds; the suite passes through this test in ordinary
time. The measured numbers feed the render plan's S1 baseline.
