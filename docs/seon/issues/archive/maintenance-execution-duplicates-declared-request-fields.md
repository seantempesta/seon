---
type: issue
status: resolved
severity: friction
tags: [issue, architecture, maintenance, schema]
---

# Derive maintenance execution fields from the declared request

## Problem

Maintenance execution copied a fixed vector of config attributes before it
built the request, even though the request fields were already declared in the
schema registry. A newly declared maintenance field could validate everywhere
yet disappear at execution unless this second roster was updated by hand.

## Evidence

- `seon.schedule` hard-coded five maintenance config attributes.
- The execution context applied `select-keys` with that vector.
- `resources/seon/schemas/seon.maintenance.request.edn` already declares the
  request value and all of its keys.
- Malli's maintained `explicit-keys` operation exposes map-entry keys without
  positional form parsing (`reference-code/malli/src/malli/core.cljc:2772-2812`).

## Owner

The maintenance request contract and the schedule-to-maintenance boundary.

## Acceptance

Derive the fields transferred from effective config from the declared request
contract. Adding a declared config-backed request field needs no edit to a
parallel vector, while undeclared extras remain harmless under open-map
semantics.

## Resolution

Resolved by the audit-finding-5 commit that archives this issue. Execution now
selects the intersection of effective config and Malli's explicit keys for
`:seon.maintenance.request/value`; the five-key hand roster no longer exists.
Focused schedule and maintenance contract suites are the behavioral proof.
