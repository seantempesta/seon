---
title: "DIG: Agent world contents and write paths"
date: 2026-10-11
type: dig
status: in-progress
---

# DIG: Agent world contents and write paths

## 1. Functions that write to main / home / another agent's branch

| Function | Writes what | Obtains connection | Call path | Writer | Status |
|---|---|---|---|---|---|
| `seon.cluster.message/send!` | Message facts | Reads agent row `:seon.agent/branch`, calls `db/branch-connection` | `my.message/send`, `seon.turn/end-task!` (end message) | Agent, Root | Ambient: obtains branch from agent row, not passed |
| `seon.turn/end-task-call` | `:seon.agent/admission-closed-tx`, calls `send-call` | Bundled in transaction as db.fn/call | `seon.turn/end-task!` calls via `db/transact!` | Root | Ambient: calls send-call internally |
| `seon.cluster.message/send-call` | Message rows (transaction input) | Bundled in transaction as db.fn/call | `seon.turn/end-task-call`, `end-summary-tx` | Root | Ambient: no connection param, reads from database arg |
| `seon.task/written` | Task assignments, task end records | `task/main-connection` → `task/main` or provided connection | `seon.task/adopt-tasks-tx`, `seon.task/assign!` | Root | Ambient: wraps with `db/call-with-custody` |
| `seon.context/end-summary-tx` | Agent outcome facts | `task/main` from custody | `seon.turn/end-task!` | Agent | Ambient: reads from database arg, reaches via task/main |
| `seon.cluster.host/home-call` | Agent namespace entity, branch facts | `db/call` with target connection | `seon.cluster.agent/start-agent!` | Root | Transaction-level: inside a transacting connection |

## 2. Readers of agent facts / agent row on different branches

| Reader | What it reads | Where it reads | Why | Instance |
|---|---|---|---|---|
| `seon.turn/closed-on?` | `:seon.agent/admission-closed-tx` | Calls `task/main` to get home database | Checks if task is already recorded as ended on main | `seon.turn/end-task-call` guard |
| `seon.turn/end-message` | Agent row via `task/agent-task` | Reads from main (if available) or custody db | Constructs end message with full task data | `seon.turn/end-task!` |
| `seon.turn/task-end` | Held task (agent + task link) | Reads from connection (custody) | Decides if task ended this turn | `seon.turn/end-task!` |
| `seon.cluster.message/send!` | Agent row `:seon.agent/branch` | Reads from custody database | Determines recipient's home branch | Message send flow |
| `seon.task/agent-task` | Task entity and agent row | Reads from provided db (custody or main) | Retrieves task state for assignment or end | Multiple calls in turn and task |
| `seon.task/assignment-read` | Task, agent, proof rows | Reads from main (before write) | Verifies task state before assignment | `seon.task/assign!` |

## 3. World members and supplied connections

**seon.batch/world-members** (at arming and call-time):
- `:seon.db/connection` — calling evaluation's connection (custody for agents)
- `:seon.db/writer` — cluster's writer ident
- `:seon.agent/id` — current agent id
- `:seon.task/id` — agent's held task id
- `:seon.render/profile` — agent's render profile
- `:seon.batch/sink` — call record sink
- `:seon.sci.eval/ctx` — SCI context
- `:seon.store/store` — cluster store
- `:seon.agent/routing` — branch operations
- `:seon.agent/context-source` — task reference for context reacquisition

**Connection supply** (`seon.call-preparation/supply`):
- Optional `:seon.db/db` supplied from world connection at call-time (elide for current value, pass for consistent)
- World `:seon.db/connection` is agent's custody (branch connection); no database value held in world
- No world member supplies a connection to main; functions reach it via `task/main` or `:seon.agent/branch` lookup

## 4. History and prior attempts

**Custody and world design (commits a74fd3031, ff0492e60, c070c594a)**:
- **One world constructor** `seon.turn.world/world` for agents and test members
- **Custody is default world**: Agent evaluations run in their branch SCI context with custody connection
- **Issues found**:
  - e71e60a47: Omitted database supply read from test member's own branch, not custody; fixed to read `db/*conn*` (fixture's custody inside `db/call-with-custody`)
  - 4347f6cce: Test member body inherited requester's world; fixed to keep only call sink

**Ambient service patterns** (ruled against in AGENTS.md §Data, "Custody is default world"):
- Message send reaches recipient's home via `:seon.agent/branch` lookup (ambient)
- Task writes reach main via `task/main-connection` (ambient)
- No explicit world member for "main connection" or "root's home"

## 5. Datahike and library support for multiple connections

**Datahike facts** (`reference-code/datahike`):
- One evaluator (JVM), many immutable branches (pointers to commit trees)
- `d/branch!` creates a pointer; `d/branch-connection` returns a connection to it
- One writer thread serializes transactions; `db/call-with-custody` uses `db/*conn*` binding
- Commit identity (tx-id, basis-t) stable across all connections

**Supply mechanism** (`seon.call-preparation/supply-from-world`):
- `:seon.db/db` special: read at call-time from `db/*conn*` (if in custody) else world connection
- All other world members read from `:seon.batch/*world*` (thread-local binding)
- No de-duplication: each call reads independently

## 6. Open questions and contradictions

1. **Ambient vs explicit**: Functions obtain "home" connections without receiving them (read from agent row, process registry). AGENTS.md §3 says "pass the world explicitly"; is reading `:seon.agent/branch` and calling `db/branch-connection` within that rule?
2. **Message send layering**: `send!` (public tool) reads recipient's branch from agent row; `send-call` (transaction input) is called from `end-task-call` (a db.fn/call, not a regular function). Is `send-call` receiving an implicit connection via the transaction's executor?
3. **Task home vs agent home**: Tasks live on main; agents live on branches. `task/main-connection` reaches main. Does the task home (main) need its own entry in the world?
4. **World member for main**: Should there be a `:seon.task/main-connection` world member (like `:seon.db/connection` for custody) so functions can request it explicitly?
5. **db/call-with-custody scope**: `db/*conn*` binding is thread-local; how does it interact with virtual thread hops in Flow graphs?
