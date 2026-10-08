---
title: "Card RT-1 An assignment is a wake"
type: reference
status: active
created: 2026-10-08
---

# RT-1 An assignment is a wake

Ledger §3.28 RT-1 (group E). Index: [README-root-tasks](README-root-tasks.md). Lane: Sonnet.

## What becomes true
Writing an assignment wakes the assigned agent: `:seon.task/agent` is declared `:seon.wake/listen true`, so
`seon.wake/wake-attributes` and `turn-opening-attributes` include it and `seon.turn/unanswered-wakes` lists a new
assignment until an answering turn covers it. A root-written assignment is an outside wake (it anchors the call cap as
a message from root does today); `seon.wake/inside-attributes` is unchanged.

## Functions
- CHANGE `resources/seon/schemas/seon.task.edn`: the `:agent` member gains `:seon.wake/listen true` (and
  `:seon.wake/opens-turn? true`, read the message member's declaration in `seon.message.edn` and copy its form).
  Publish and adopt on a branch of the live store before commit.
- No code change: `seon.wake` derives the sets from the declarations.

## Deleted
Nothing yet; RT-9, RT-12, RT-13 delete the messages this replaces.

## Proof
`seon.turn-work-test/an-assignment-is-a-wake` (new; through `seon.mcp/call` as root): assign a task with a red proof
to a fresh agent; `(seon.turn/unanswered-wakes db agent-id {})` names the assignment's entity; with CX-2 landed,
`next-agent-work` answers `:open` for it.

## Cost
One more attribute in the listened set: one `:avet` seek more per `outside-wake-t`, O(the agent's wakes).

## Dependencies
None. Commit: `bin/lane commit LANE -m "RT-1: an assignment is a wake" --test seon.turn-work-test --test seon.task-ingestion-test resources/seon/schemas/seon.task.edn test/seon/turn_work_test.clj`
