---
title: "Card AE-1 seon.agent/record: the one read of an agent"
type: reference
status: active
created: 2026-10-08
---

# AE-1 `seon.agent/record`: the one read of an agent

Index: [README-agent-entity](README-agent-entity.md). Lane: Sonnet (REPL-first on its own branch).

## What becomes true
`(seon.agent/record database agent-id)` answers the agent's record as one value, whichever branch `database` is:
identity, branch, namespace, settings overrides and held assignments read at the task home seen from `database`;
nothing else yet (AE-5, AE-7 add the branch-side members). An unknown agent answers `:seon.agent/no-such-agent`.

## Functions
- NEW `seon.agent/home` `[database]` → `[:maybe :seon.db/connection]`: `seon.task/home` moved (same body);
  `seon.task/home` and `home-connection` call it until AE-3 converts their callers.
- NEW `seon.agent/record` `[database agent-id]` → `[:or :seon.agent/record :seon.error/value]`: one `db/pull` at
  `(or (some-> (home database) db/db) database)` with a selector derived from `:seon.agent/agent` (its members, the
  settings component, and today's assignments via `:seon.task/_agent` with `:seon.task/assigned`
  `:seon.task/revision` `:seon.task/branch`).
- CHANGE `resources/seon/schemas/seon.agent.edn`: `:seon.agent/record` (open map: `:seon.agent/id`,
  `:seon.agent/branch`, optional namespace, settings, assignments).

## Proof
`my.agent-test/a-branch-agent-reads-its-record-at-the-home` (new, through `seon.mcp/call`): root writes an
assignment and settings at the home for an agent whose branch forked earlier; `eval_clj` as that agent of
`(seon.agent/record (seon.db/db) "<id>")` answers both. Red today: no such function.

## Cost
One pull, O(attributes + held); measure on default (expect about 1 ms).

## Dependencies
None. Commit: `bin/lane commit agent-entity -m "AE-1: seon.agent/record" --test my.agent-test src/seon/agent.clj src/seon/task.clj resources/seon/schemas/seon.agent.edn test/my/agent_test.clj`
