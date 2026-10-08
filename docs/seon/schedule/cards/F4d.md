---
title: "Card F4d: A restarted proc runs what was left"
type: reference
status: active
created: 2026-10-08
---

# F4d: A restarted proc runs what was left

Depends on F4c. Group B. Model: Opus.

## Files

- `src/seon/turn.clj`
- `test/seon/cluster/turn_test.clj`

(`src/seon/cluster/agent.clj` is not needed: proc start is the `step` 0- and 1-arities in `turn.clj:5877`.)

## Decided

Nothing is stored. At the first step after the proc starts (the proc state has no `:seon.turn.loop/queue` key and no
`:seon.turn.loop/started` key), the step derives the queue from stored evidence instead of waiting for a change:
the held task's proofs (`task-proofs`, F4a) plus the tests reaching the branch's change (`seon.test/changes` →
`seon.test/reaching`, `test.clj:1091`), minus those `seon.test/verified?` (`test.clj:1573`) on the branch. It runs
them as F4c's queue and sets `:seon.turn.loop/started true` in the proc state. A program equal to its base derives
an empty queue.

## Who runs what the bound left (R-6, decided 2026-10-09)

A test the step's bound cut keeps running to its actual exit on its own thread; until then a later request holds it
(pending) and never starts it twice. Once it exits, its recorded failure ("did not exit within …") is answered from
the record by every later request at the same reach digest (`seon.test/select` reuses red as green): it runs again only
when what it reaches changes, or when a request asks `:seon.test/rerun?`. A test the bound never started has no record
and stays pending: the agent's next eval request selects it again (its branch's change still reaches it), and while the
agent waits nothing runs it; this card's proc start is the other place it runs. Pending is not a wake.

## Proof

`seon.cluster.turn-test/a-restarted-proc-runs-pending-tests`: a pass changes code with the dial at 1 ms so a reaching
test stays pending; stop and start the agent's graph the way the existing restart tests do
(`test/seon/cluster/program_restart_test.clj`: reuse its helper, do not copy it); send one non-changing pass
(`eval_clj` `(+ 1 2)`); assert the pending test now has a result on the branch.

`--test seon.cluster.turn-test --test seon.cluster.program-restart-test`.

## Cost

One derivation per proc start: proportional to the task's proofs and the reaching tests of the branch's change.

## Commit

`bin/lane commit f4d -m "F4d: a restarted agent proc runs the tests left unfinished" --test seon.cluster.turn-test --test seon.cluster.program-restart-test src/seon/turn.clj test/seon/cluster/turn_test.clj`
