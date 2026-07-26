---
type: issue
status: resolved
severity: friction
tags: [issue, database, test]
---

# Project fractional numeric map keys before Transit

## Problem

`seon.db.protocol/ordinary-wire-value?` accepted fractional numeric map keys,
but Transit JSON encoded key `0.5` with its integer tag and decoded it as `0`.
The codec therefore silently changed a value that the ordinary-wire predicate
claimed was round-trip safe.

## Evidence

A live CLJS probe produced encoded text `["^ ","~i0.5",0]`, decoded value
`{0 0}`, and `false` for equality with `{0.5 0}` while
`ordinary-wire-value?` returned true.

Commit `1fbbc7b8e` excludes fractional numeric keys from already-ordinary maps.
The one existing wire projector degrades such a key to bounded text before
Transit. The focused regression passes and the deterministic codec-totality
property has zero failures in both final full runs.

## Owner

`seon.db.protocol/ordinary-wire-value?` and
`seon.db.protocol/wire-projection` jointly own the one total codec boundary.

## Acceptance

`{0.5 0}` is marked degraded, projects to an eager ordinary value, and the
projected value is equal after the production UDS Transit encode/decode
round-trip.
