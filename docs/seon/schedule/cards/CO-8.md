---
title: "Card CO-8: Identity-only schemas compile to class checks"
type: reference
status: active
created: 2026-10-08
---

# CO-8 Identity-only schemas compile to class checks

Pattern P2 (Opus I2/F3). Lane: Sonnet.

## What exists
Six schemas declare `:seon.schema/identity-only true` (`seon.db.edn` ×2, `seon.env.edn`, `seon.sci.eval.edn`,
`seon.schema.edn`); `seon.schema/identity-only-descriptors-in` already finds them. They validate through a named
predicate (0.15 µs through the armed wrapper).

## Change
Compose each identity-only schema in the projection registry as Malli's stock `m/-simple-schema` with an
`instance?`/`satisfies?` predicate on its declared host class. Same accepted set; no fork change.

## Proof
`seon.schema-test/identity-only-schemas-are-class-checks` (new table): for each identity-only key, validating a
member and a non-member answers as the predicate does, and `(:seon.profile/calls m)` is 0.

## Cost
Expected ~0.008 µs per check. Parent 0.15 µs.

## Commit
`bin/lane commit <lane> -m "CO-8: identity-only schemas compile to class checks" --test seon.schema-test src/seon/schema.clj test/seon/schema_test.clj`

## Dependencies
CO-7 (same file).
