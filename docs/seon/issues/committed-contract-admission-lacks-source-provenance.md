---
type: issue
status: open
severity: blocker
tags: [issue, schema, runtime, database]
---

# Committed contract admission lacks source provenance

## Problem

R31 requires strict admission for durable agent-authored contracts while
retaining documented core and opaque-boundary exceptions. The committed
projection compiler cannot make that decision from its current input:
`seon.schema/projection-from-rows` receives only `[identity form-string]`
pairs for schemas and function contracts.

Treating every committed row as agent-authored breaks the existing core
population. Treating every row as core lets a database-loaded agent contract
bypass the `register!` and durable-`defn` gates. Namespace or symbol matching
would be a forbidden authority list rather than provenance.

## Evidence

- `src/seon/schema.cljc:491-563` parses exactly two fields from every committed
  schema and function-contract row, then calls `build-projection` without an
  admission source.
- `src/seon/host/context.clj:1198-1266` acquires the JVM host's committed
  projection through identity/form queries; this file is concurrently dirty
  in another lane.
- `src/seon/runtime/admission.cljs:200-209`,
  `src/seon/execution.cljs:406-557`, and `src/seon/web/value.cljs:13-50`
  construct the same two-field projection input on the other consumers.
- `docs/prds/sci-execution-runtime/research/schema-strictness-census-2026-07-23.md`
  §2 and §4 require admission-source provenance and prohibit symbol lists,
  while also requiring that the writer never execute agent-authored
  predicates.

## Owner

The program-graph committed-projection acquisition boundary plus
`seon.schema`. Extend the one row/input contract with transaction-derived
admission provenance, then pass it through the one projection compiler. Do not
infer authorship from namespace prefixes or schema names.

## Acceptance

- Every committed schema and function-contract row reaches
  `assert-complete-contract!` with a source derived from durable transaction
  provenance.
- A database-loaded agent contract containing `:any`, an authored open
  argument map, or an agent-authored predicate is rejected before activation.
- A core-admitted guarded predicate and a documented opaque third-party slot
  remain admissible.
- The writer never resolves or executes an agent-authored predicate.
- Focused cold-reload tests prove both rejection and retained core admission
  through the real committed-row acquisition boundary.
