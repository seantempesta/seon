---
type: issue
status: superseded
severity: friction
tags: [issue, render, database, class/n7, wave/render-connection-model]
---

# Derive render-walk connections without a function hand list

## Problem

The render walk derives ordinary forward and reverse refs from entity data and
the installed schema, then adds exceptional relationships through a private
vector of functions. Every new non-datom relationship requires editing this
second registry.

This finding is **in flight (schema-edn-consolidation lane)**.

## Evidence

- `src/seon/render/walk.clj:194-210` derives forward refs from attributes.
- `src/seon/render/walk.clj:212-268` derives reverse refs from installed ref
  attributes.
- `src/seon/render/walk.clj:270-319` implements two exceptional queries and
  registers them in `derived-edge-functions`.
- `src/seon/render/walk.clj:321-325` executes that private vector as another
  connection-discovery path.

## Owner

The database model for trigger and asked-for-run relationships, then the one
schema/data-derived `seon.render.walk/refs` mechanism.

## Acceptance

The two relationships are represented by attributes/connections at their
transaction owners or discovered from a declaration already present in the
database. Adding another relationship does not require editing a private
function roster.

## N7 closure — 2026-09-15

`bc3dfe3fd` deleted `derived-edge-functions`. HEAD now derives declared concerns through schema properties (`src/seon/render/walk.clj`, `declared-acquisition`). No replacement roster was introduced. Residual proof: current derived-relationship coverage belongs to `seon.render.root-pull-test` and the walk tests; this closure does not claim a new live relationship test. The removed private-registry mechanism is no longer a repair target.

Owner decision explicitly permits closing dissolved members with the residual
proof named. See the N7 landing note for the bounded verification record.
