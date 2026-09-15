---
type: issue
status: resolved
severity: friction
tags: [issue, agent, database, wave/core]
---

# Plan item reads lose state and nesting

Read-only default evidence on 2026-09-14: `seon.plan/steps` reports
`juniper/add` as ready and `juniper/again` as blocked. `seon.plan/item` on the
same immutable database reports both as open. The item reader supplied empty
ready/blocked sets and no current item to its derivation; it also reset depth
and parent. `seon.plan/items` repeated that incomplete derivation.

The core-functions repair reads the owning plan using the existing ownership
rules and selects the full derived item. Ordered item reads reuse that function
and propagate missing-item errors. The canonical regression compares the full
item maps across ready, blocked, parent and nested-child cases, and checks a
selected current item through real SCI calls.

After development adoption, a read-only default probe returned in 21 ms:

```clojure
[{:my.plan.item/id "juniper/add", :my.plan/state :ready, :my.plan/depth 0}
 {:my.plan.item/id "juniper/again", :my.plan/state :blocked, :my.plan/depth 0}]
```

Gate evidence and final status are recorded in
`docs/prds/context-generation/research/core-functions-landing-2026-09-14.md`.
