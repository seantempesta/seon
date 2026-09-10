---
type: issue
status: open
severity: cleanup
tags: [issue, docs, agent, wave/context-fixes]
---

# Update chart plan examples to the section 17 fixture

## Problem

The chart's earlier plan examples still address the old Juniper steps after
section 17 replaces the fixture with seven steps. The live-scenario lane's
ownership of this PRD is explicitly limited to the section 17 status line.

## Evidence

On 2026-09-10, the shared fixture and all executable Clojure consumers use
`juniper/read` and `juniper/define`. The active chart at
`docs/prds/context-generation/plan/agent-data-chart-prd-2026-09-09.md:115`
still prints these exact bytes:

```clojure
(seon.db/transact! [[:db/add [:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx "datomic.tx"]])
```

Line 120 likewise selects `juniper/aggregate`; lines 95–98 show the old
plan, and line 118 appends at position 6, now already occupied. The first
live-scenario fast run independently confirmed the removed lookup fails:
`Nothing found for entity id [:my.plan.item/id "juniper/query"]` in the
data-shapes consumer, which was corrected in this lane.

## Owner

The chart PRD owner, outside the live-scenario lane's status-line allowance.

## Acceptance

The chart examples use the current fixture's ids and append after its
existing positions, or explicitly identify their output as historical.
Dated research prompt captures remain immutable observations.
