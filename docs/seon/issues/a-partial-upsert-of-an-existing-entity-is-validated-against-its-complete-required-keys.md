---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [write-admission, schema, seon.db, owner-decision]
---

# A partial upsert of an existing entity is validated against its complete required keys

## Problem

`seon.db/transact!`'s write admission (`write-map-error`, since `20d30a0bd`
stopped validating partial maps against every schema) validates any map
keyed by an identity attribute against that attribute's ENTIRE entity
schema. A caller that upserts one attribute of an entity that already
exists — a turn's reply size, a schedule's cadence expression, a cluster's
one dial — is refused for required keys the entity already carries.

Three sightings on 2026-09-17 once fixtures stopped ignoring refusals:
`seon.turn-test/recovery-preserves-terminal-receipts-exactly` (one
attribute on an open turn, refused for `:seon.turn/agent` then
`opened-tx`), `seon.maintenance-schema-test`'s "later cadence transaction
remains authoritative" row (refused for `:seon.schedule/zone-id`), and the
config overlay class the fixture sweep routed through `apply!`. The
workaround in every case was a `[:db/add …]` datom, which bypasses map
validation entirely — so the rule pushes writers toward the LESS validated
form.

## Decision for the owner (options, recommendation first)

1. **Validate a partial upsert against the entity's required keys as they
   will stand after the transaction** — inside the transaction function,
   merge the existing entity's attributes (from the mid-transaction
   database) with the map before validating; a new entity still needs
   every required key. Guarantee: an update never fails for a key the
   entity already has; a creation is still complete. Cost: one pull per
   upserted identity at write time (an AVET lookup). Recommended.
2. Keep whole-map validation and make the datom form the documented way
   to update one attribute. Guarantee: unchanged mechanism. Cost: the
   validated path is the one nobody uses for updates.
3. A declared "update" form (`{:seon.db/update true …}`) that validates
   only the supplied keys' shapes. Cost: a second write grammar.

## F2 research — 2026-09-17

[Write-admission research](../../prds/steward-platform/research/write-admission-2026-09-17.md)
reproduced the map refusal against a live schedule with its required zone,
and observed no admission error for the equivalent incomplete raw add or
transaction-function output. The dependency writer rejects a thrown
transaction function before commit, but a per-map merge alone does not cover
later datoms, cardinality-many additions, resolved tempids, nested function
output or retained program identities. The note recommends a final-report
validation seam in Datahike and records three options for the required
cross-owner design decision. No production fix has landed; status stays open.

The existing in-process refusal regression passed 6/0/0 on default, run
80770, with an explicitly unavailable reach digest. This is not the requested
F2 atomicity/parity regression or a cold gate. The note carries exact evidence
and the remaining acceptance work.

## Related investigations

`write-admission-validated-partial-maps-against-every-schema`,
`fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour`.
