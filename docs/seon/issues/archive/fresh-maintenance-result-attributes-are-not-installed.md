---
type: issue
status: resolved
severity: blocker
tags: [issue, maintenance, schema, boot]
---

# Install maintenance result attributes on a fresh cluster

## Problem

A newly published and forcibly reforked default cluster reached READY, then
its first scheduled maintenance settlement was refused because
`:seon.operator.log/path` was absent from Datahike's installed schema. The
global `:seon.operator.log/result` schema row was present and referenced that
declared attribute, but population did not install the attribute.

## Root cause

`seon.schema/canonical-database-attributes` already computes the production
population from declared persisted map shapes. The maintenance result entity
instead carried a hand-maintained optional roster of operation attributes.
That roster omitted log and collection results, and collection's public
`:seon.operator.collect/branches` vector contains nested maps that cannot be a
Datahike attribute value.

Commit `00bf9feea` makes the operation result maps the population authority:
log and footprint declare their persisted maps, projected reap and cleanup
components declare theirs, and collection declares one persistence producer
that projects branch maps to component facts containing
`:seon.store/branch` and `:seon.source/commit-id`. The maintenance result
entity now declares only its identity; no central operation roster remains.

## Proof

A new isolated operator root published source digest
`38b702735d7aaf0bf6bea00a1c4b5464fb89146b82186e700f08965ec89bc07d`,
forked `default` from commit `6a74dade-12c7-5d57-bc28-6936a85cdaf6`, and
booted READY. Its installed schema contained
`:seon.operator.log/path`, `:seon.operator.collect/reclaimed-bytes`, and
`:seon.maintenance.result/collect-branches`.

With the scratch graph quiesced and an isolated clean claim population, the
five seeded tasks fired through `seon.schedule/fire-due!`. All five receipts
had `completed-at` plus a result, with no error or interruption. The compact
receipt stored three branch components. Run and message counts remained
unchanged (`2` and `3` before and after), proving the pass was turn-free.

The class regression transacts real log and collection result entities through
the production database fixture and queries the persisted scalar and nested
component facts. The focused integration gate passed 41 tests and 229
assertions with zero failures or errors. `bin/test seon.dev.markdown-test`
passed 26 tests and 350 assertions.

## Acceptance

- A fresh cluster installs every attribute declared by each persisted
  maintenance result shape.
- Collection branch maps persist as queryable component facts without an EDN
  codec or a hand-maintained population roster.
- The five seeded maintenance tasks settle green receipts turn-free.
