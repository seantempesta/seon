---
type: issue
status: open
severity: blocker
created: 2026-09-19
tags: [issue, schema, database, testing]
---

# Individually valid fields admit contradictory owning values

## Problem and evidence

Entity guarantees require relationships between facts, not just a valid type
for each field. The [schema audit C](../../prds/steward-platform/research/schema-audit-c-native-2026-09-19.md)
live validator probe at basis 536871517 admitted both:

- A `:seon.schema.shape.child/row` with ID and order -1, but neither a
  schema nor literal payload.
- A `:seon.test.accretion.auto/entity` with status passed, a skip reason,
  case-count 0 and executed-count 9.

This validates declarations, not a transaction of the malformed examples.
The constructor at `src/seon/fn/schema_shape.clj:199` chooses one payload;
the schema does not enforce that choice. The two owning resources are
`resources/seon/schemas/seon.schema.shape.child.edn` and
`resources/seon/schemas/seon.test.accretion.auto.edn`.

[Audit A](../../prds/steward-platform/research/schema-audit-a-native-2026-09-19.md)
also identifies source-derived counterexamples for settings ownership
backlinks and plan current-step membership. These have not been executed;
reproduce before treating them as confirmed writer defects. Audit B's
maintenance-result and arity/binding examples belong to this same class.

## Owner and acceptance

Each domain owner declares the relational invariant; `seon.db` applies it
to complete final owning values, including component edits, retractions and
transaction functions. Keep one coordinated class assignment, with separate
file ownership for shape, plan and test-result repairs.

Canonical fixture tests must refuse neither/both child payloads, negative
positions and contradictory terminal evidence, including direct mutation
after a valid creation. Test valid empty, skipped and partially executed
failure cases so strengthening does not erase real states. Prove or refute
backlink/current-step/result-completeness candidates using the same fixture.
Do not add another writer or submission-only validator.

## Config/plan family follow-up, 2026-09-20

Wave 1d's C4 inventory finds six additional undeclared top-level Seon
properties beyond the audit's three: `:seon.db/cardinality`,
`:seon.error/class`, `:seon.error/refusal`, `:seon.issue/cites`, and
`:seon.schema.admission/exemption` / `:seon.schema.admission/reason`.
A universal property declaration checker cannot silently exempt these.
The proposed config default alias also resolves to the unstorable `:any`;
the read-only foreground probe returned storable `false` and persisted
default properties `{}`. The probe is recorded in the
[config/plan landing](../../prds/steward-platform/research/config-plan-family-1d-2026-09-20.md).
This is an ownership/design boundary, not a claim that C4 is fixed.
