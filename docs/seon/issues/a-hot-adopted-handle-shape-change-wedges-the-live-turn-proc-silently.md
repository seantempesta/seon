---
type: issue
status: open
severity: blocker
tags: [issue, platform, adoption, turn, flow, absence-as-health, class/p1]
created: 2026-09-15
---

# A hot-adopted change that needs new boot-time handle state wedges the live turn proc, and nothing says so

## Observed (default, 2026-09-15 04:07Z, live run 3)

A lane's uncommitted edit added `:seon.agent/context-state` to the cluster
handle built at boot (`src/seon/cluster.clj`) and made context acquisition
require it. The edit hook adopted the functions onto the running `default`,
whose handle predates the key. Juniper's next turn faulted
`:seon.agent/missing-context-state` (error `d217ae07…`; root too,
`ea7ee3f1…`), the turn proc stopped taking turns, and
`(seon.turn/next-agent-work db {:seon.agent/id "juniper"})` kept returning
`{:situation :open …}` — work pending, nothing running, 11 turns of budget
unused, the page showing "idle". A live run was invalidated silently.

## Two defects

1. **Absence read as health.** A proc whose last step faulted and whose
   derivation says work is pending is wedged; the page, the status probe
   (`runtime_status` → "unknown, Read timed out"), and the problems panel
   say nothing. Wanted: the agent's state line derives "stalled: work
   pending since HH:MM, last fault <kind>" from those two facts, and the
   problems panel lists it first.
2. **Adoption admits a change whose handle contract the live cluster cannot
   satisfy.** A function that requires a handle key the boot did not
   produce should be refused at adoption (the handle's schema is declared:
   compare the function's declared handle inputs against the live handle)
   with RESET NEEDED, instead of being loaded and failing at the next turn.

The lane rule already says RESET NEEDED for schema-incompatible changes;
handle-shape changes are the same class and need the same gate.

## Re-verified at HEAD (2026-09-15)

OPEN, UNVERIFIABLE. HEAD 7e35df213: `src/seon/cluster/agent.clj:644` still refuses `:seon.agent/missing-context-state`. The original trigger requires adopting an incompatible handle change onto an older live graph; that mutation is outside this read-only default probe. No reproduction of that transition was performed. MCP `runtime_status` with cluster `default` returned health/Flow `unknown`, `Read timed out`; a separate JVM `(+ 1 2)` returned 3 in 2 ms. This demonstrates an observation limit, not the historical cause. The generic health timeout is already owned by `default-component-probe-times-out-after-adoption.md`. Needed: a disposable older boot plus controlled incompatible adoption and pending-work observation. Retain blocker pending that proof.

surface: adoption-publication

Owner correction: UNVERIFIABLE-WITHOUT-GATE (seon.cluster.source-test, seon.turn-test). No new JVM may be launched; the required canonical regression remains pending.
