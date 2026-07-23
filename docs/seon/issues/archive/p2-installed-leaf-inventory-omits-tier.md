---
type: issue
status: resolved
tags: [issue, runtime, architecture]
---

# Installed leaf inventory omits its tier

## Evidence

`seon.capability/installed-leaf-inventory` accepted a tier but discarded it.
The landed P5 claimant keyed `:seon.execution/tier-inventories` from the
missing `:seon.execution.inventory/tier` field, producing a `nil` tier and
preventing exact JVM placement.

## Acceptance

- The canonical installer enumerator returns its tier in the closed inventory.
- The JVM host retains `:jvm`, and the planner selects it for an installed
  compiled terminal.
- The inventory digest remains derived solely from installed binding
  descriptors; adding the tier field does not create another enumerator.

## Resolution

Commit `a332ecb5f` retains the tier on the canonical installer inventory,
projects the JVM claimant inventory from that one registry enumerator, and
passes the inventory to planner acquisition. The dual-tier planner regression
places real compiled-only terminals exactly on both JVM and Bun inventories.
