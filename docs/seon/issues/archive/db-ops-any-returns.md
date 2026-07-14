---
type: issue
status: resolved
severity: cleanup
tags: [issue, database, schema]
---

# `seon.db` query/pull/entity declare `:any` returns (RESOLVED — sanctioned boundary)

## Resolution (user, 2026-06-08)

`:any` is **acceptable at third-party interface boundaries** — we don't control
what datahike (or any external library) returns, so there's no honest tighter
type. The `query`/`pull`/`entity`/`transact!` `:any` returns are this sanctioned
case and STAY. The no-`:any` rule remains the default nudge for seon-authored
data. The compliance checker flags `:any` as a non-blocking nudge (not a defect);
at a genuine boundary the warning is an accepted judgment call. Convention written
to `docs/conventions.md` ("`:any` at third-party interface boundaries"). No
opaque-return marker was added (don't over-engineer). Original analysis kept below.

---

## Original analysis (flagged as a real smell, before resolution)

## What

After T17 made `seon.dev.compliance` an accurate schema-completeness checker, it
correctly flags `seon.db/query`, `seon.db/pull`, `seon.db/entity` for declaring
`:any` RETURN types in their `:malli/schema` (`:function` form). The argument
side is fully specced (the `:catn` positional slots all reference registered
schemas / concrete base types post-T15); only the return is `:any`. This
violates the project "no `:any`" convention.

This is **pre-existing**, not a T15 regression — the old single-arity schemas
(`[:=> [:cat ::query-request] :any]`) already used `:any`; T15 chunk 1 carried
the existing return contract forward into the new positional arities.

## Why it wasn't fixed in T15 chunk 1

- These returns ARE Malli-instrumented (sync `:function` schemas), so the return
  value is validated at runtime. A naive tightening breaks the agent:
  - `pull` returns **`nil`** for an unresolved ref → a `:map` return schema would
    THROW on every missed pull. So `pull` cannot simply become `:map`.
  - `query` returns a datahike result set (`#{[v ...] ...}` — arbitrary-arity
    tuples of heterogeneous values); `entity` returns a datahike `Entity` record
    (lazy map-like). Neither is cleanly expressible without an `:any` leaf.
- `:any` is SAFE as a return (accepts anything incl. nil) — the issue is purely
  the convention violation, not a runtime bug.

## The actual decision (broader than a quick edit)

These are arguably the sanctioned-opaque-`:any` exception (cf. the wire-protocol
`:any` carve-outs). The compliance checker has **no concept of a sanctioned
opaque return**. So the fix is a design choice, to be made deliberately (NOT on
the demo critical path):

1. Give `seon.dev.compliance` a sanctioned-opaque marker (e.g. a registered
   `::opaque-return` shape, or an allowlist) so a deliberate opaque return reads
   as complete; OR
2. Register named opaque shapes — `::query-result`, `::entity-val`, and a
   nil-permitting `pull` return — that are still effectively `[:fn …]`, and point
   the ops at them. Must permit `nil` for `pull` (instrumented return).

Whichever path: it changes a return contract that ~179 internal map-in callers
also observe, so verify in the live pod before committing.

## Pointers

- Flagged by the T15 read-ops agent during chunk 1 (analysis preserved here).
- Related: T15 positional db ops (`positional-db-ops-spec-2026-06-08.md`);
  the no-`:any` rule (MEMORY / `docs/conventions.md`).
