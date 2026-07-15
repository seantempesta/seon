---
type: issue
status: resolved
severity: friction
tags: [issue, database, web]
---

# Validate database-browser cursor boundaries before index reads

## Problem

The database-browser cursor checks its sealed request identity before reading,
but does not prove that its `last` datom is canonically encoded or belongs to
the sealed index prefix. A crafted cursor can therefore trigger a bounded index
seek that the cursor contract requires to reject before any database read.

## Evidence

`seon.db.browser/decode-cursor` validates the encoded tuple shapes only.
`decode-value` later transforms keyword payloads with
`(keyword (subs payload 1))` without requiring a leading `:` or exact
decode/re-encode equality. In `index-page`, `cursor-request-matches?` compares
the coordinate, projection, index, prefix, and direction, but never compares
the decoded `last` components with that prefix. The function then calls
`index-datoms` or `rseek-datoms`; `matching-prefix?` constrains results only
after the read.

The ordinary server-generated cursors are valid and existing paging remains
bounded. The defect is fail-before-read validation of malformed or altered
opaque input, not a reason to sign cursors, add a token registry, or create a
second paging path.

Full reconciliation is in
[[../../prds/database-browser/research/entity-reference-projection-reconciliation-2026-07-15]].

## Owner

The one cursor and bounded index projection in `seon.db.browser`.

## Acceptance

- Every encoded scalar decodes and re-encodes to exactly the same canonical
  value before database access.
- The decoded `last` datom's selected-index components begin with the sealed
  prefix and satisfy the current/history position contract.
- Noncanonical scalar payloads and prefix-inconsistent last datoms return typed
  errors with zero `index-datoms` and `rseek-datoms` calls.
- Existing forward/reverse EAVT, AEVT, AVET, retained-coordinate, and
  five-component history paging remains disjoint and bounded.
- The version-1 opaque cursor remains the sole continuation mechanism; no
  signature, server-side registry, cache, or fallback-to-first-page is added.

## Resolution

`seon.db.browser` now reconstructs and re-encodes every cursor boundary scalar,
rejects noncanonical encodings, and compares the ordered boundary prefix with
the sealed prefix before selecting the immutable projection or reading an
index. The focused projection checkpoint passed four tests and 25 assertions
with zero failures or errors, including zero-read doubles for noncanonical and
prefix-inconsistent boundaries. The retained log is
`tmp/test-cljs-20260715-092419-25639.log`.
