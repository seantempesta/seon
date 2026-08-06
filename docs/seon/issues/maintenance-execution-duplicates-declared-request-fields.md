---
type: issue
status: open
severity: friction
tags: [issue, architecture, maintenance, schema]
---

# Derive maintenance execution fields from the declared request

## Problem

Maintenance execution copies a fixed vector of config attributes before it
builds the request, even though the request's required fields are already
declared in the schema registry. A newly declared maintenance field can
validate everywhere yet disappear at execution unless this second roster is
updated by hand.

## Evidence

- `src/seon/schedule.clj:457-462` hard-codes five `maintenance-dials`.
- `src/seon/schedule.clj:468-487` applies `select-keys` with that vector while
  constructing the execution context.
- `resources/seon/schemas/seon.maintenance.request.edn:42-87` already declares
  the maintenance request and its required fields.
- The program rule is that a query over declared facts answers such rosters;
  a second vector is not another authority.

## Owner

The maintenance request contract and the schedule-to-maintenance boundary.

## Acceptance

Derive the fields transferred from effective config from the declared request
contract or another explicit program fact. Adding a declared config-backed
request field needs no edit to a parallel vector, while undeclared extras
remain harmless under open-map semantics.
