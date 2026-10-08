---
title: "Card AE-2 Settings are written and read at the home"
type: reference
status: active
created: 2026-10-08
---

# AE-2 Settings are written and read at the home

Index: [README-agent-entity](README-agent-entity.md). Answers RESPEC R-9. Lane: Opus (writer check, boot-critical
reads).

## What becomes true
- `seon.agent/settings!` and `my.agent/settings!` write the agent's settings component at the home, from any branch:
  root's write from main and the agent's own write from its branch land in the same place.
- Every settings read goes through `seon.agent/record`: the turn's provider settings, the cap's dial, bootstrap's
  bound read, `seon.agent/settings`, `effective-settings` and the settings renders.
- Each `:seon.agent/*` attribute declares `:seon.agent/written-at` `:home` or `:branch`; the checked writer refuses a
  `:home` attribute in a transaction on a branch that is not the home (and `:branch` on the home for another agent's
  branch) as `:seon.agent/written-at-refusal`, naming the attribute and both branches.

## Functions
- CHANGE `seon.agent/settings!`: transact through `(or (home (db/db connection)) connection)` under that home's
  custody, as `seon.task/written` does.
- CHANGE `seon.ai/agent-overlay` → reads `(:seon.agent/settings (record db id))`; `seon.turn` (the four overlay reads
  and `max-episode-runs`), `seon.bootstrap` (the bound read) call it.
- CHANGE the writer's schema check (`seon.db` validation): the side check from the attribute's declaration.
- CHANGE `seon.agent.edn`: `:written-at` on each agent attribute.

## Deleted
The branch-local settings route; `max-episode-runs`'s private settings query.

## Proof
`my.agent-test/root-settings-reach-an-agent-on-its-branch` (new, tagged `my.agent/settings-root-writes-on-main-never-reach-an-agent-on-its-branch`;
through `seon.mcp/call`): root's `(seon.agent/settings! {:seon.config.run/max-episode-runs 5} main-conn id)` is what
the branch agent's `(my.agent/turns-left)` and `(seon.agent/settings (seon.db/db) id)` read; a raw
`[:db/add agent :seon.agent/settings …]` on the agent's branch refuses `:seon.agent/written-at-refusal`. Existing
`my.agent-test/settings-*` stay green. Also tag `my.agent/settings-bang-answers-the-overrides-before-the-write` if the
answer now reads the written value (it reads the record from `:db-after` at the home).

## Cost
One home transaction per settings write (as before, other connection); reads as AE-1.

## Dependencies
AE-1. Commit with `--test my.agent-test --test seon.turn-work-test`.
