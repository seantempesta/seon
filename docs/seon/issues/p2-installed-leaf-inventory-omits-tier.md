---
type: issue
status: active
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
