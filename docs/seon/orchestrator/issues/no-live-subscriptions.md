---
type: issue
status: open
severity: architectural
---
# No Mechanism for Live Cross-Namespace Subscriptions

## Problem
Namespaces can query the code graph on demand (pull), but there is no way to say "notify me when this query result changes." Every consumer must poll or rely on manual refresh. This blocks reactive cross-namespace UIs.

## Where
- `src/seon/graph/query.clj` — pull-only API, no subscription mechanism

## Acceptance Criteria
- Namespaces can subscribe to graph queries and receive updates when results change
- Subscription mechanism is flow-native (not ad-hoc watches)
- Namespace views that display cross-namespace data update automatically
- No polling required for live updates
- Tests verify subscription delivery

## Related
- [[components/code-graph]]
- [[components/flow-topology]]
