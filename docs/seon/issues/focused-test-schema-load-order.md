---
type: issue
status: open
severity: friction
tags: [issue, schema, test, runtime]
---

# Focused tests expose recovery schema load-order coupling

## Problem

`seon.runtime.recovery` registers response collections by referencing the
identity schemas owned by agent, run, and turn namespaces without requiring
those owners. Focused Shadow test selection can load recovery before one of
those schemas and abort the entire cold bundle before any test runs.

## Evidence

On 2026-07-14 the focused
`seon.web.router-test/operator-config-route-reaches-the-injected-live-operation`
build compiled successfully, then failed while importing
`seon.runtime.recovery` because `::run-ids` referenced the not-yet-registered
`:seon.agent.run/id`. The selected test executed zero namespaces. These fields
are response values; run and turn ids already share
`seon.db.id/compact-value`, and agent ids are strings.

## Owner

The response schemas in `seon.runtime.recovery` and the shared identity value
shapes in `seon.db.id`.

## Acceptance

- Recovery response schemas compile independently of agent/run/turn namespace
  load order.
- Focused router selection reaches and passes its requested test.
- Stored identity attributes remain owned and registered by their domain
  namespaces; no duplicate identity schema is introduced.
