---
type: issue
status: open
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
