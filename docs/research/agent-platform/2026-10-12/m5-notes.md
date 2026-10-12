---
title: "M5 notes: the derived lifecycle, and what it keeps until later phases"
type: research
status: lane m5, 2026-10-12. M5 done in two commits, 1b09d8043 (P7, the one catch) and c74ef3437 (U3, the derived lifecycle). Root's rulings of 2026-10-12 applied: the end keeps reaching main through the lifecycle's `:merge` (M3's `merge-tx`), the needs cycle is broken (M5 → M6 → M4), the `:seon.squash/copy`, `/datoms`, `/conflicts` schemas move to M6.
---

# M5 notes

## What landed

- **1b09d8043, P7.** `seon.cluster.agent/apply-plan!` has no catch-and-continue: an action's failure is thrown
  again naming the action (`:seon.flow.plan/action`, cause kept), and `db<->flow`'s one catch,
  `seon.cluster.agent/failed-pass!`, ends the assignment of a failed `:attempt`, `:start-flow`, `:merge-base` or
  `:merge` `:error` on main with the failure's message as its reason, then records the fault under db<->flow.
  On HEAD the same failure's fault record itself threw ("SEON CORE FAULT (not recorded)", because it was
  attributed to the attempt's unopened branch). `seon.task/ready` reads `:error` as terminal: without it the
  `:error` end datom (`:seon.wake/starts`) would wake db<->flow into a new failing assignment every pass.
  The pass is one function, `seon.cluster.agent/run-pass!`; the agent start loop (`agents-to-start`, M13)
  runs last, so a broken idle agent no longer stops the lifecycle and the plan.
- **c74ef3437, U3.** `seon.cluster.agent/lifecycle-facts` reads every assignment whose branch the store holds;
  the pure `seon.cluster.agent/next-actions` derives, in order: `:stop` (an ended graph, its last pass
  finishes first, so the merge takes its final head), `:merge-base` (an end to merge with no merge base: the
  G1 hint, an end that never got a base is derived, never a throw), `:merge` (head ≠ merge base, main holds
  no other end), `:close` (a non-merged end written on main with root's problem task), `:delete-branch` (head
  = merge base and nothing keeps it). Deletion is decided only after the merge, on main, because an open
  merge request and a successor's fork point reach main in the merge itself; the merge's end datom wakes the
  pass that deletes. Deleted: `land-ends!`, `unlanded-ends`, `settle-kept-branches!`, `record-error-end!`,
  `open-merge-request?`, `detached!`, `successor-waits?`, `:seon.squash/attempt`.

## Kept, and until when (the code contradicted the row; root ruled the first two)

| Kept | Why | Goes in |
|---|---|---|
| the successor-waits condition, inside `kept?` | successors still fork the predecessor's end commit read through its branch (`fork-attempt!`); deleting it before C1 deletes the fork point (G7 on every chain) | M10 |
| the open-merge-request condition, inside `kept?` | root's code merge (`seon.cluster.source/land!`) reads the source branch until M6 folds it | M6/M8 |
| `ended-problem!` and the `:close` write of a non-merged end | root's only notice of a capped, stalled or given-back assignment until `close-tx` | M12 |
| `:seon.squash/copy`, `/datoms`, `/conflicts` | `seon.cluster.squash/merge!`'s return value; M6 rewrites `merge!` | M6 |
| `seon.turn/land-ended!` with no caller | the detached auto-land of an open merge request under `:seon.config.root/land?` (false by default and on default) lost its caller with `detached!`; turn.clj is held by root-drift | M8 deletes it and the dial |

A transitional rule: an end main holds with no merge base was copied there before M3 (default holds seven such
`:sent-back`/`:capped` assignments with their branches); it is not merged again (a re-merge from the fork base
would add every `:add` count twice) and its branch is deleted when nothing keeps it. Expect the first pass after
root's restart to delete those seven branches.

## For root-drift (held files): stale references to deleted symbols

- `src/seon/turn.clj`, docstrings of `end-task-call` and `end-task!`: `(`seon.cluster.agent/land-ends!`)` →
  `(`seon.cluster.agent/next-actions`)`; "copies the end … stops the graph and settles the branch" → "stops the
  graph, squash-merges the end, refinements, children and merge request to main in one transaction and deletes
  the branch once merged".
- `resources/seon/tasks/seon.turn.edn`, task `a-held-task-opens-no-model-turn`: "After M5
  (`seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted`) and M12" → "After M12"
  (the task is retired; the backticked reference no longer resolves).

## Proofs and costs

- Red on HEAD → green: `seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` (error:
  the uncaught fault record), `seon.cluster.db-flow-test/a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted`
  (fail: the graph still ran after the pass). New table proof
  `seon.cluster.db-flow-test/each-ended-assignment-derives-its-lifecycle-from-its-facts` (pure, ten rows).
  Tasks P7 and U3 retired; their deftests stay untagged as regressions.
- Cost: an idle pass under 50 ms, asserted in the crash proof through `seon.profile/measure`. On default
  (15 assignments, 7 held), warm: the lifecycle's reads 0.7–1.9 ms, `kept?` for the 7 merged rows 1.8–3.0 ms;
  HEAD's `unlanded-ends` 0.16–0.27 ms (it skipped marked rows; the derivation reads them until deleted).
- Pre-existing reds, unchanged by M5: `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn`
  (red in a whole-namespace run, green alone; `g1-proof-bisect.md`), `seon.cluster.squash-test/a-repeated-merge-adds-nothing`
  (red on 1b09d8043), `seon.cluster.lazy-agents-test/idle-agents-wait-for-work-but-schedule-owners-need-their-timers`,
  `seon.turn-continue-test/provider-progress-has-its-own-bound` and `read-results-arrive-before-done` (red on HEAD).
