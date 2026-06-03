---
type: reference
status: active
tags: [reference, agent, database, flow]
---

# Agent-runtime glossary (canonical names)

Single source of truth for the vocabulary shared by the **reactive** track
(agents building reactive context) and the **platform** track (clusters +
multi-DB wiring). Agreed 2026-06-03. If a doc disagrees with this table, this
table wins — update the doc.

## Canonical terms

| Term | Meaning |
| --- | --- |
| **Database** (the CLJ DB) | The JVM datahike store; the single source of truth. All facts are datoms here. **The isolation boundary between clusters is one database** (one datahike conn). |
| **Host** | The JVM process that owns datahike + the wire server (`src/seon/server/`). |
| **Guest** | The JS runtime instance an agent runs inside — Node for V2, an isolated runtime (WASM or anything that runs JS well) later. The container; the *agent* is the actor. |
| **Agent** | A CLJS actor running an LLM loop. Writes code, writes to the database, reachable the same way as any other agent. The top-level agent is what the user talks to. |
| **Subagent** | An agent launched by another agent to do work. **Functionally identical** to an agent — same capabilities, same DB access, same code-writing; can launch its own subagents. The *only* difference is the launched-by relationship. |
| **Subscription** | A standing reactive query an agent registers; it wakes the agent when matching data changes. Persisted as a datom, so it is queryable, survives restart, and drives lifecycle. Subscribing to a subagent is just subscribing to that subagent's render output. |
| **Patterns** | The e/a/v match patterns derived from a subscription's query that decide when it fires (entity/value-precise; Posh's structure). Mostly internal — derived from the query, not declared. (Replaces the misleading "read-set".) |
| **Summary** | An agent's rendered output — the `:seon.render/ai` (text) and `:seon.render/html` values written **on the agent's own entity**. Not a separate entity. What consumers read. |
| **Render function** | The pure function of the database that produces an agent's `:seon.render/ai` + `:seon.render/html`. Reuses Seon's existing render machinery. |
| **Reactive engine** | The host-side Posh engine that runs inside datahike's `d/listen!` callback and routes each commit to the subscriptions it affects. (Convex calls this a "subscription manager".) |
| **Notification** | The ephemeral wake-up signal sent to a subscriber. Carries no authoritative information — its durable counterpart is always the **transaction**. Lossy-safe: a dropped notification loses nothing, because the data is in the database. |
| **Cluster** | One database + its N agents + a task + metrics — the unit of parallel experiments. The MVP is one cluster (one shared database). Platform-track term. |

## Retired terms (do not use)

| Retired | Use instead |
| --- | --- |
| scout | subagent (or agent) |
| general | the top-level agent |
| orchestrator (the runtime, user-facing one) | agent. **Note:** "orchestrator" still means the *dev-time* Claude instance elsewhere in the repo (`ORCHESTRATOR.md`, `docs/seon/orchestrator/`) — that usage is unchanged. |
| worker | agent (or "subagent" if you mean a launched one) |
| read-set | patterns |
| session (isolation boundary) | **fully retired.** The isolation boundary is "the database" (MVP) / "a cluster's database" (multi-cluster). Pre-loads user/auth semantics we haven't designed, and collides with nREPL sessions. |

**The one surviving "session":** the **nREPL / MCP eval session** (the `mcp__seon__eval` / `mcp__seon_cljs__eval` REPL sessions). That meaning is industry-standard and unambiguous in context — it stays. No other use of the word.

## Code renames (folded into platform P1, atomic — no `foo`/`foo-v2`)

| Was | Becomes | Why |
| --- | --- | --- |
| `seon.server.session` (`session.clj`) | `seon.server.registry` | it is the `{db-name → conn}` registry of **Databases** |
| `:seon.session/<name>` (db-name keyword) | `:seon.cluster/<name>` | a Database belongs to a **Cluster**; MVP = one cluster |
| Rust `SessionRegistry` / `SEON_AGENT_SESSION` | `ClusterRegistry` / `SEON_CLUSTER` | same |

## Load-bearing relationships

- **Agents and subagents are the same kind of thing.** There is one entity type
  (agent); "manages others / talks to the user" is what an agent *does*, not a
  schema distinction. A subagent is an agent with a launched-by relationship.
- **Everything is a subscription to data.** An agent subscribes to database data;
  an agent watching a subagent is subscribing to that subagent's summary
  (`:seon.render/*`) — which is also data. One concept covers both. Lifecycle
  ("an agent stays active while ≥1 agent is subscribed to it") falls out because
  subscriptions are datoms you can query.
- **All facts are transactions; effects live at the edges.** Inbound effects
  (web, email, MCP, timers, the user) *write* what they observe; outbound effects
  (LLM calls, sending to the user) *read* an intent datom and *act*. The interior
  is pure reactive readers. Nothing crosses except through the database. See the
  invariant in [reactive-agent-topology](reactive-agent-topology.md).
