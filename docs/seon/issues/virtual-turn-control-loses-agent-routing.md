---
type: issue
status: open
severity: blocker
tags: [issue, web, agent, wave/agent-context]
---

# Carry agent routing into the virtual-turn control

## Problem

The debug page's Virtual turn POST returns HTTP 500 because its execution
request has no routing atom. The agent graph already owns that atom.

## Evidence

2026-09-09 default POST `/agent/juniper/context`, `action=virtual-turn`:
HTTP 500 / 157 bytes / 0.007343 seconds. The response names
`seon.turn/virtual-turn!` invalid input at `:seon.cluster.agent/routing`.
Cluster construction kept routing on the instance but omitted it from the
view and HTTP service. `change-context` also replaced a supplied top-level
routing value with the nested cluster's absent value.

## Owner

`seon.cluster/arm-agents!`, `serve!`, and `seon.render.web/change-context`.
The bounded repair carries the existing atom through these boundaries.

Repair verified on 2026-09-09: the canonical control gate passes 80 tests /
717 assertions; platform passes 83 / 490. A newly constructed scratch HTTP
service returns 204 and closes turn `bfe6b618db09` with shown value `2` and
zero provider attempts. The issue remains open for default's existing
captured service: RESET NEEDED through the orchestrator's normal lifecycle.

## Acceptance

The actual HTTP control executes an ordinary virtual turn with zero provider
attempts. The canonical real-proc fixture exercises the same control owner,
including the three-form three-transaction count and private result handles.
Reconstruct an existing HTTP service to acquire its newly carried routing;
hot-reloading functions alone cannot add an input to a captured service map.
