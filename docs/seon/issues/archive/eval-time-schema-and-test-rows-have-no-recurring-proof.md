---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, program-graph, testing]
---

# Give eval-time schema and test rows recurring proof

## Problem

Runtime function, schema, namespace-binding, and test declarations once lacked
one canonical row lifecycle and recurring proof across terminal commit,
replacement, deletion, acquisition, and process reopen.

## Evidence

The implementation history through `995ccec92` establishes one current-source
publication and runtime declaration contract. The recurring program, eval,
turn, schema-usage, and process-restart tests cover exact rows, evaluated
schema values, replacement/removal, aliases/refers/imports, and cold
acquisition. The former open note's own final evidence reported a frozen full
gate of 606 tests / 2,680 assertions and no remaining registration-lifecycle
blocker.

The only surviving discrepancy is narrower and downstream: clj-kondo cannot
represent SCI's persisted nil-mask for a removed JVM default import. That is
tracked independently as
`negative-import-masks-escape-static-admission.md`; it does not reopen the
canonical eval-time row lifecycle.

## Owner

`seon.program`, `seon.sci.eval`, and current-source publication.

## Acceptance

Build and runtime declarations share canonical identities and exact
replacement/removal, commit only from terminal `db-after`, and reacquire after
process loss under recurring tests.

## Resolved 2026-07-30

Resolved by the registration/indexing wave culminating in `995ccec92`
(`Replace evaluated indexing with current-src publication`), after the exact
binding and registration commits recorded in that wave. The remaining static
negative-import limitation was split to its actual owner during the
2026-08-02 backlog triage.
