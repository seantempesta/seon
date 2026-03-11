---
type: decision
status: implemented
date: 2026-03-05
---

# ADR-002: Absence Over Nil

## Context

Datalevin (and all EAV databases) have no concept of a null datom -- an attribute either has a value or does not exist. Transacting `{:foo nil}` throws. Meanwhile, Malli's `[:maybe X]` validates both nil and X, creating a semantic gap at the persistence boundary. Naive nil-stripping is lossy: it turns "clear this field" into "leave unchanged."

## Decision

Model "no value" as **key absence**, never as nil. For persisted schemas: `{:optional true}` on the map entry, never `[:maybe X]`. No nil values cross the Datalevin boundary.

## Rules

1. **`{:optional true} X`** -- key may be absent. If present, value must match X. Absent key = no value.
2. **No `[:maybe X]` on persisted schemas.** Banned. Generators produce nil ~50% of the time, which crashes Datalevin.
3. **No nil values in entity maps** at the DB boundary. `db/transact!` validates via Malli before Datalevin sees the data.
4. **Retraction is explicit.** To clear a field on an existing entity, use `[:db/retract eid :attr]`. Omitting a key from a transact map means "leave unchanged."
5. **Pull output matches schema directly.** `d/pull` omits absent attributes -- no hydration layer needed. `(:foo pulled-entity)` returns nil naturally via Clojure's map lookup.

## Rationale

- **Eliminates an entire class of bugs.** No nil-stripping layers, no nil-to-retraction conversion, no hydration on read. Pull output is directly Malli-valid.
- **One representation of "no value."** Absence everywhere. No confusion about whether you're looking at app-level nil or DB-level absence.
- **Matches Datalevin's actual semantics.** Zero impedance mismatch.
- **Simplest of three options evaluated.** Option B (nil-stripping + hydration) and Option C (mixed boundary) both required two new layers and introduced "which representation am I looking at?" confusion.

## Rejected Alternatives

- **`[:maybe X]` with nil-stripping + retraction layer** -- required smart middleware to distinguish new entities (strip nils) from updates (retract). Two representations of "no value" in the system.
- **Mixed boundary (absence in DB, nil in app)** -- most flexible but most complex. Three representations, boundary functions to maintain.

## Details

- `docs/prds/schema-unification/research/nil-semantics-findings.md` -- full research with REPL verification
- [[components/schema-system]] for schema patterns
- [[components/database]] for EAV semantics
