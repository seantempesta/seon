---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, schema, runtime]
---

# Enforce committed schema validation in the claimant leaf

## Problem

The JVM claimant database leaf explicitly disabled Malli validation and
discarded the host's retained committed schema projection. Invalid turn phases
and zero claim epochs could therefore reach the writer instead of failing at
the claimant boundary.

## Evidence

`src/seon/agent/driver/host.clj` supplied a nil projection, a no-op projection
cache, and `schema-validation? false`, unlike the general JVM host database
context in `src/seon/host/context.clj`.

## Owner

The one portable `seon.db` leaf context backed by
`seon.host.context`'s committed projection state.

## Acceptance

- The claimant leaf reads and updates the host's retained committed projection.
- An out-of-enum turn phase and a zero claim epoch each return a flat
  `:user-input` error before the writer transport is called.
