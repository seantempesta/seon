---
type: issue
status: open
severity: architectural
milestone: M1
tags: [issue, flow]
---
# No Mechanism for Namespaces to Broadcast Signals

## Problem

When a namespace's state changes, only atom watches (internal to ctx) know about it. There is no general-purpose signal or event that other namespaces or system components can subscribe to. This blocks cross-namespace coordination and system-wide event monitoring.

## Where

- State changes are trapped inside `src/seon/ctx.clj` atom watches

## Acceptance Criteria

- Namespaces can broadcast state-change signals
- Other namespaces and system components can subscribe to these signals
- Signal mechanism is flow-native
- Cross-namespace coordination patterns are possible without polling
- Tests verify signal delivery and subscription

## Related

- [[components/context]]
- [[components/flow-topology]]
