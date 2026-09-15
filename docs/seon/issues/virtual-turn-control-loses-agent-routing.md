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

## Re-verified at HEAD (2026-09-15)

surface: render-debug-page

OPEN, UNVERIFIABLE-WITHOUT-GATE for the final HTTP control behavior. Fix `e6832e8d8` is present at HEAD `a5f3d7565`: `src/seon/cluster.clj:2774–2783` carries routing into the view; `:2333–2345` carries it into the HTTP service; `src/seon/render/web.clj:2390–2394` preserves the supplied top-level routing. Read-only default MCP JVM probe `(let [instance (#'seon.cluster/mcp-instance "default") view (:seon.render.web/view instance)] {:instance-routing? (some? (:seon.agent/routing instance)) :view-routing? (some? (:seon.agent/routing view)) :served? (some? (:seon.render.web/served instance))})` returned all three true in 2 ms. This verifies instance/view custody, not the server closure's captured map or a completed HTTP POST. The owner's CPU correction forbids new JVMs and limits default probes to read-only, so the remaining gate is `bin/test-fast seon.turn-test` (`virtual-turns-use-the-proc-and-compaction-is-agent-scoped`, control entry at `test/seon/turn_test.clj:160`). A separately authorized disposable virtual-turn POST would verify the captured live service. No POST or new JVM was launched. The old missing-routing failure is not reproduced; blocker status remains pending that final boundary.
