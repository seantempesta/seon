---
title: "Card AE-4 Assignments are components of the agent and ref their task"
type: reference
status: active
created: 2026-10-08
---

# AE-4 Assignments are components of the agent and ref their task

Index: [README-agent-entity](README-agent-entity.md). Q-AE1 (recommended option assumed). Replaces RT-1's schema
change; amends RT-3, RT-6. RESET POINT. Lane: Opus (schema, wake routing).

## What becomes true
- The agent holds its assignments: `:seon.agent/assignments` (cardinality many, component, `:home`). Each assignment
  has `:seon.task/assignment` (identity, unchanged), `:seon.task/task` (ref to the task), `:seon.task/revision`,
  `:seon.task/branch`; `:seon.task/agent` and `:seon.task/assigned` are gone.
- Writing the edge wakes the agent: `:seon.agent/assignments` is `:seon.wake/listen :entity` (the datom's entity is
  the woken agent); `seon.cluster.wake` routes and `agent-wake-datoms` seeks entity-side declarations by `:eavt`.
- Retiring a held task is refused by the required ref until it is given back.

## Functions
- CHANGE `seon.agent.edn`, `seon.task.edn`, `seon.wake.edn`; publish and adopt on a branch of the live store.
- CHANGE `seon.task/assign!` (and RT-2's `assign-tx` if landed): write `{:db/id agent :seon.agent/assignments
  [{…}]}`; `seon.task/assignment`, the proposal writer and `seon.agent/record`'s selector read the component.
- CHANGE `seon.cluster.wake`: the entity-side route (one more `:eavt` seek per declared attribute).

## Deleted
`:seon.task/agent`, `:seon.task/assigned`, `seon.task/agent-task` remnants; RT-1's card body (now this card).

## Proof
`seon.turn-work-test/an-assignment-is-a-wake` (RT-1's proof, through `seon.mcp/call` as root): assigning a red task
to a fresh agent on its branch makes `next-agent-work` `:open`; `(seon.agent/record db id)` lists the assignment
with its task pulled. `seon.task-ingestion-test/an-agent-holds-several-assignments-oldest-first` (RT-3's) passes on
the component.

## Cost
Assignment write unchanged (one home transaction); wake routing one `:eavt` seek more per entity-side attribute.

## Dependencies
AE-3. Reset per agent-loop.md §5 (dump assignments and settings first). Commit with
`--test seon.turn-work-test --test seon.task-ingestion-test --test seon.cluster.wake-test`.
