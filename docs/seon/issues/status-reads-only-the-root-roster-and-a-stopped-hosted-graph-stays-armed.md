---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, flow, roster, status, lifecycle, unobserved-exit]
---

# Status reads only the root roster, and a stopped hosted graph stays armed

## Observed (default pid 13262, HEAD 61d207c3e, 2026-09-26T16:01Z, read-only JVM probes)

- The instance roster held root (armed, graph answering ping) and six child rosters, one per hosted MCP branch.
- Child `:inv-run-stab-b`: its armed entry `mcp/inv-run-stab-b` held a graph whose `flow/ping` threw
  `java.lang.Exception: flow not running`; its started `:report-chan` and `:error-chan` were closed; its mailbox
  channel was open; `:seon.agent/released?` absent. Neither the cluster nor the branch database recorded an error
  after boot. The entry still reads as armed, so the armer's `arm!` idempotence leaves the agent on a dead graph.
- `runtime_status` / `bin/seon status` reported `:flow {:agents 1 :procs 3}`: `seon.oversight/flow-status`
  (`src/seon/oversight.clj:190`) passes only `(:seon.agent/routing instance)` to `fleet-value`, which reads that one
  roster's `:seon.agent/armed`. Hosted branch agents, their armer graphs and any dead graph among them are invisible.

## Why it is a defect

The roster is the one truth for live objects (AGENTS state law); status derived from part of it reads silence as
health. A graph that stopped without a stored fault breaks "panic visibly" twice: no fault, and no status row.

## Owner and acceptance

`seon.oversight/fleet-value` walks the roster tree (`:seon.agent/children`, including each child's `::host` armer
graph), so every armed entry and armer graph answers ping or reads unknown. The armed entry of a graph that exited
is removed by its exit (the graph's own stop transition or the panic path that stopped it), not by a status read.
One regression: host a branch agent, stop its graph, and status names it as not running.
Cause of the silent stop on `inv-run-stab-b` not established (candidate: that lane's own probes); see the
`inv-agent-run-stability-opus` landing.
