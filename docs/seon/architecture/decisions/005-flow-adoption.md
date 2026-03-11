---
type: decision
status: implemented
date: 2026-01-15
tags: [decision, architecture, database, flow]
---

# ADR 005: Selective Adoption of core.async.flow

## Context

Rich Hickey released `core.async.flow` (January 2025) -- a library for building concurrent data processing pipelines with explicit topology, step function separation, and introspection. We evaluated whether it should become the foundational architecture for Seon's agent infrastructure.

## Decision

**Selective adoption, not wholesale replacement.** Use Flow for internal orchestration where its strengths apply. Keep current architecture for agent lifecycle.

## Rationale

### The Fundamental Mismatch

Flow was designed for concurrent data processing pipelines (Clojure threads processing messages). Seon's agents are long-running external processes (Claude CLI subprocesses) with opaque state (Claude's context window).

| Flow Assumption | Seon Reality |
|-----------------|--------------|
| Processes are Clojure threads | Agents are external subprocesses |
| State returns from step functions | State lives in Claude's context window |
| Channels for all I/O | stdout/stdin for Claude communication |
| Microsecond message processing | Minutes/hours of agent execution |

### Where Flow Excels (adopted)

- **Internal message routing** -- explicit topology visible as data
- **Status aggregation** -- decoupled UI updates, ping for debugging
- **SSE streaming** -- cleaner shutdown, better error handling
- **Database write coordination** -- serialized access through flow step-fns
- **Request-reply pattern** -- promise-based topology/request! for cross-boundary calls

### Where Flow Doesn't Apply (kept current approach)

- **Agent lifecycle** -- Integrant for resource management (nREPL, Datalevin per agent)
- **External process management** -- Direct subprocess management for Claude CLI
- **Persistence** -- Datalevin for all data storage
- **Schema discovery** -- Malli registry (Flow has no contracts concept)

## Consequences

**Benefits:**

- Explicit topology -- process connections visible as data, not hidden in code
- Centralized error handling via `:error-chan`
- Introspection via `ping` for debugging live state
- Hot reload of step functions

**Costs:**

- Learning curve for step function 4-arity protocol (describe/init/transition/transform)
- Alpha status -- "Names and other details are in flux"
- Debugging complexity requires flow-monitor and extensive logging

## Implementation

Flow is used for:

- `seon.db.datalevin.writer` -- serialized DB writes
- `seon.db.datalevin.reader` -- serialized DB reads
- `seon.flow.topology` -- unified request-reply backbone
- `seon.web.sse.flow` -- SSE broadcasting

Flow patterns (explicit topology, step function separation) inform design even where the library isn't used directly.
