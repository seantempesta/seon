---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, schema]
---

# Agent tools may silently accept unknown request keys

## Problem

Some `my.*` request schemas may accept misspelled or obsolete keys, allowing an
agent call to appear valid while ignoring part of its intent.

## Evidence

The archived dual-path audit's C62 row found this class of failure. `my.plan`
now uses a schema-derived unknown-key guard, but the other public `my.*`
request schemas have not been checked against that owner.

## Owner

The shared agent-tool request validation boundary in `seon.schema` and the
request schemas that use it.

## Acceptance

A mechanical audit identifies every open map request schema, behavioral probes
reject unknown keys through one schema-derived mechanism, and no namespace
maintains a parallel hand-written key list.

## Progress — 2026-07-20

Commit `d883bb05` closes every request map in the non-canvas public tool
namespaces. The mechanical registry audit covers 25 map request schemas across
`my.blob`, `my.data`, `my.kb`, `my.ns`, and `my.ui`: the 19 public request
schemas changed by that commit plus six already-closed blob restore/operator
requests. Every schema rejects an extra key as Malli `:malli.core/extra-key`;
the accepted keys remain the schema entries, with no parallel key list.

Focused proof passes 55 tests and 289 assertions across the five owning tool
test namespaces plus the completeness test. The default live cluster was not
running, so no live agent-call evidence is claimed.

## Resolution

Commit `94e38e15` closes the ten `my.canvas` request maps (`view`, `show`,
`canvas`, `state`, `save`, `button`, `input`, `select`, `toggle`, and `form`)
after the G11 lifecycle work settled. The same registry-derived completeness
test now covers 35 map request schemas across all six audited namespaces and
proves every extra key is classified as Malli `:malli.core/extra-key`.

The combined canvas and request-schema gate passes 9 tests and 104 assertions.
`my.plan` retains its existing schema-derived directive guard; no new runtime
key guard or hand-maintained accepted-key list was introduced. The live client
was drained and not ready, so no restart or live proof was claimed.
