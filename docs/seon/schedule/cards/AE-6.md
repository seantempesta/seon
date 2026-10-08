---
title: "Card AE-6 Messages to an agent are written at the home"
type: reference
status: active
created: 2026-10-08
---

# AE-6 Messages to an agent are written at the home

Index: [README-agent-entity](README-agent-entity.md). Q-AE4 (recommended option assumed). Lane: Opus (wake path).

## What becomes true
`my.message/send` writes every message at the task home, whoever the recipient and wherever the sender works;
`:seon.message/to` is `:home`. The block's unread items and `my.message/inbox` read them through the record's home
value; delivery stays recorded on the reader's call row (ids as values, agent-loop.md §3.4).

## Functions
- CHANGE `seon.cluster.message/send!`: target `seon.agent/home`'s connection, not the recipient's
  `:seon.agent/branch`.
- CHANGE `seon.cluster.message` inbox reads and `seon.turn/unanswered-wakes` to read message wakes at the home.

## Deleted
The recipient-branch routing in `send!`.

## Proof
`my.message-test/a-message-to-a-branch-agent-is-read-at-the-home` (new, through `seon.mcp/call`): root sends to an
agent on its branch; the message is on the home and not on the branch, and the agent's `(my.message/inbox)` lists it.

## Cost
One home transaction per message, as before (other connection).

## Dependencies
AE-4. Commit with `--test my.message-test --test seon.turn-work-test`.
