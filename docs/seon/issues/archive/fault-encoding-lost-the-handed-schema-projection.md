---
type: issue
status: resolved
severity: blocker
tags: [issue, flow, database, schema, observability]
---

# Supply the handed schema projection throughout fault encoding

## Problem

A core instrumentation fault could be normalized but not committed. The
transaction encoder passed its database-derived schema projection to explicit
bridge calls without supplying the same declaration forms to registered
one-argument predicates invoked underneath. On the fault-committer Flow thread,
which has no ambient projection, instrumentation therefore rejected a valid
`:boolean` schema form before Datahike could record the fault.

## Evidence

The retained live log printed Malli's positional humanization:
`[nil [{:value :boolean ...}]]`. The `nil` is not the rejected form; it means
argument zero, the explicit projection, had no error. Argument one was the
valid `:boolean` declaration for `:seon.error/capped?`.

The chain was `seon.flow/fault-committer-step` → private
`seon.cluster/commit-fault!` → `seon.db/transact!` →
`seon.schema.datahike/encode-transaction-in` →
`edn-encoded-attr-in?` → instrumented `resolve-datahike-form-in`.
`seon.schema/malli-form?` then had no dynamically supplied declaration
population and correctly refused unhanded resolution. A direct probe returned
false for `(schema/malli-form? :boolean)` without supplied forms and true for
the same predicate inside `schema/call-with-forms`.

## Owner

The transaction-encoding boundary in
`seon.schema.datahike/encode-transaction-in`.

## Acceptance

- Encoding an entire transaction supplies the explicit projection's forms to
  every registered predicate below the bridge.
- An instrumentation-shaped core fault commits from a thread with no ambient
  projection.
- The durable fact retains its instrumentation function, expected schema, and
  boolean capped marker.

## Resolved 2026-08-12

Commit `305be0b29` scopes the complete encode operation with
`schema/call-with-forms`. Its regression instruments the exact bridge, invokes
the real fault committer without an ambient projection, and reads back the
durable fault. Four focused owner tests passed with 83 assertions and zero
failures/errors, including the pre-existing once-per-transaction and EDN
round-trip invariants.
