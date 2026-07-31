---
type: issue
status: open
severity: friction
tags: [issue, schema, database, datahike]
---

# Refuse `:db.secondary/only` until a covering index exists

## Problem

The Malli-to-Datahike bridge will happily emit `:db.secondary/only` for an
attribute. That flag means the value lives **only** in a covering secondary
index — the primary indices keep just a content hash. Seon declares no
secondary index anywhere, so an attribute that used the property today would
write values that cannot be read back from the primary store.

Nothing uses it yet, which is why this is a live footgun rather than a
current data loss.

## Evidence

`src/seon/schema/datahike.cljc:228-243` reads `:db.secondary/only` from Malli
properties and sets it on the emitted attribute, guarding only that the
value type is float.

`reference-code/datahike/CHANGELOG.md:50` is explicit that such a value is
search-only and not reproducible from the primary indices;
`reference-code/datahike/src/datahike/schema.cljc:135` is the schema side.

Grep confirms `:db.secondary/type` appears **nowhere** in Seon — no
Scriptum text index, no Proximum KNN index, no Stratum columnar index is
declared — even though Proximum is a live dependency (`deps.edn:41-42`) and
is listed in the AOT namespace set.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

`seon.schema.datahike` — the bridge that emits the property.

## Acceptance

- Declaring a `:db.secondary/only` attribute without a covering secondary
  index is refused at the bridge, as a schema admission error naming the
  missing index; a test asserts the refusal.
- Or a secondary index is declared and the round trip is proven: a value
  written through a `:db.secondary/only` attribute is read back correctly.
- Whichever lands, the failing case is representable — the current state is
  that the unsafe combination is silently accepted.
