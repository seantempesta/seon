---
title: "R5 notes: a base run is kept on the agent's branch"
type: research
status: lane r5 (Opus), 2026-10-12, basis HEAD 83e9c5df7
---

# R5 notes

## What changed

- `seon.test/fork-point-red`: the base's results are read at the base reach digests on the base commit and on the
  execution's branch (`seon.test.runner/result-at-reach`, which replaces the old `latest-results` plus equality
  filter); a test held at neither runs once on a child forked at the base, recorded on the execution's connection.
- `seon.test/run-request`: when the recording connection's branch differs from the execution database's branch, the
  provenance names the recording branch `:seon.test.run/branch` (destination) and the child
  `:seon.test.run/tested-branch`. `seon.test.runner/commit-results!` already keys members by reach digests computed
  on the tested database, so the kept run's members are at the base keys.
- `seon.test/admit-run`: a run with a tested branch is admitted on its destination; its program digest and basis are
  the tested branch's, so the destination-program and basis checks do not apply to it (as for a published snapshot).
- `seon.test.runner/newest-results` takes `own?`: `latest-results` reads only the branch's own runs, so a kept base
  run (red at the base key, newer than the head's run) never stands in for the branch's latest result at the head
  (`seon.context` head entries, `seon.problems`, `seon.render.var`, `seon.mcp`, `seon.turn`); `result-at-reach` reads
  every run at its key. Before R5 no admitted run had a tested branch (admit-run refused one), so this changes no
  existing read.

## Gap the hybrid did not name

The prebrief said "nothing reads the kept run" until R6. `latest-results` would have: its consumers read the newest
run of the branch, and a kept base run is newer. The `own?` read is what keeps head reads unchanged.

## Decision to confirm with root

`seon.test/change-reds` calls `fork-point-red` with main as the execution, so the merge gate's base run is now kept on
main (root recording its own observation) instead of released with the child. The prebrief asked to keep main
unrecorded until R8; the hybrid's words ("records on the execution's connection") and the owner's R8 ruling point
this way. If root wants main unrecorded until R8, `change-reds` needs its own recording connection argument.

## Not measured

The second call's cost (< 10 ms budget) is not measured through `seon.profile/measure`; the proof asserts by data
that the second call records no run. The proof's run: 6.7 s for the deftest in a throwaway JVM (two forks, two
context acquisitions, one nested run), declared long (15 s).

## What R6 needs next

- `results-at` [databases keys] generalizes `result-at-reach` (now also used twice by `fork-point-red`), with the
  evaluator compared; `fork-point-red`'s `held` becomes one `results-at` call over the commit and the branch.
- `latest-results`' `own?` stays the head read; R6's head entries should read `results-at` at the head keys and
  base results at the base keys over the agent's branch and main.
- `record-broken!` (lane s4b holds `src/seon/turn.clj`) still writes `:seon.agent/broken`; R6 deletes it and reads
  the kept run instead.
