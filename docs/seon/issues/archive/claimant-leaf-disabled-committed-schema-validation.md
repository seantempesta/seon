---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, schema, runtime]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Enforce committed schema validation in the cluster JVM leaf

## Problem

The cluster JVM database leaf explicitly disabled Malli validation and
discarded the host's retained committed schema projection. Invalid turn phases
and zero claim epochs could therefore reach the writer instead of failing at
the run-holding process boundary.

## Evidence

`src/seon/agent/driver/host.clj` supplied a nil projection, a no-op projection
cache, and `schema-validation? false`, unlike the general JVM host database
context in `src/seon/host/context.clj`.

## Owner

The one portable `seon.db` leaf context backed by
`seon.host.context`'s committed projection state.

## Acceptance

- The cluster JVM leaf reads and updates the host's retained committed projection.
- An out-of-enum turn phase and a zero claim epoch each return a flat
  `:user-input` error before the writer transport is called.

## Resolution

Commit `34f0373e8` binds the cluster JVM leaf to the host's retained committed
projection. `seon.db.claimant-validation-test` passes 2 tests / 10 assertions:
an out-of-enum turn phase and zero claim epoch both return flat
`:user-input` values before the transport, and cluster JVM invocation limits come
from acquired config facts.
