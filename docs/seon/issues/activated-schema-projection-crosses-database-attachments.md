---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, schema]
---

# Scope the activated schema projection to one database attachment

## Problem

The process-global activated Malli program projection can survive replacement
of the ambient Datahike connection. A later eval against a fresh database then
combines that database's declarations with schema state activated for an older
connection and records `:malli.core/invalid-schema` as a core fault. This makes
database attachment replacement and branch-local validation unsafe even when
the new connection itself is valid.

## Evidence

The complete changed-test artifact repeatedly fails
`seon.agent.turn-capture-test/current-ns-persists-across-turns` only after prior
test namespaces have run in the same Node process. Turn two successfully
records `(defn tmv ...)` in `:probe.tc.move`, proving the derived namespace
cursor is correct, then records `SEON-CORE-FAULT :malli.core/invalid-schema`
before `(tmv 2)` can produce an eval row. The retained logs are
`tmp/test-cljs-20260715-001900-70617.log` and
`tmp/test-changed/changed-pod-1784092522967-f4c12814-3e38-4895-9bcd-6c81c870fc35.log`.
Both show the same fault immediately between the second turn's open and done
events. The test passes in isolation, so its fresh in-memory database is not
independently malformed.

This contradicts the runtime-reliability roadmap's retained claim that the
process-global projection contamination was fully repaired. It also blocks an
honest proof that non-autonomous runtime stop/start can replace one database
attachment without inheriting another attachment's activated schema state.

## Owner

The one schema declaration collector and activated immutable program
projection across `seon.schema`, `seon.instrument`, runtime publication, and
database connection release/adoption. Connection replacement must either
activate the complete projection derived for the new database or retain no
projection; it must never reuse a projection owned by a different database
coordinate.

## Acceptance

- A focused contaminant-first test activates an incompatible projection on
  connection A, replaces the ambient connection with fresh connection B, and
  successfully defines and calls a function on B without a schema fault.
- Stop/start and failed-start cleanup remove or replace every projection and
  wrapper owned by the released attachment; no second registry or connection
  identity atom is introduced.
- The original `current-ns-persists-across-turns` test passes both alone and in
  its complete-suite order, and its two expected eval rows name
  `:probe.tc.move`.
- The complete CLJS gate contains no `:malli.core/invalid-schema` core fault
  attributable to attachment or test-order contamination.
