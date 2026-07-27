---
type: issue
status: open
severity: blocker
tags: [issue, schema, database, architecture, runtime]
---

# Derive core schema admission without an identity allowlist

## Problem

`seon.schema/admission-from-asserting-transaction` classifies a committed
schema or contract as core-authored by membership in a literal set of three
process identity keywords.

Transaction provenance is the correct input, but process-name membership is a
hand-maintained trust classification. A new legitimate core producer is
silently treated as agent-authored until this file is edited, while any path
that stamps one listed identity receives core exceptions by name.

## Evidence

- `src/seon/schema.cljc:261-264` defines
  `core-process-identities` as `boot`, `config`, and `core`.
- `src/seon/schema.cljc:266-300` derives `:core` solely with
  `(contains? core-process-identities process-id)`.
- The default branch fails closed as agent-authored, which is safe for an
  unknown producer but does not remove the classification drift: adding or
  renaming a core transaction source requires synchronizing this list.
- The previously resolved provenance issue established that admission must be
  derived from the asserting transaction and must not use symbol/namespace
  authority. The fresh implementation now has transaction provenance but
  still turns one of its names into a literal trust list.

This violates the house rule that trust/privacy/placement classifications are
computed from facts, provenance, or the artifact inventory, never from a
literal name list.

## Owner

The transaction-provenance model and `seon.schema` projection admission. Add
or derive the authoritative provenance fact at the producer boundary; do not
replace the list with a `:kind` field or a second registry.

## Acceptance

- Core versus agent admission follows an explicit durable provenance or
  artifact fact, not membership in process identity keywords.
- A newly admitted core producer requires no edit to a classification list.
- An agent-authored transaction cannot receive core schema exceptions by
  selecting or replaying a trusted-looking process name.
- Missing/unrecognized provenance remains fail-closed as agent-authored.
- Fresh recurring tests cover boot, config, runtime core, agent, REPL, missing,
  and newly added producer provenance.
