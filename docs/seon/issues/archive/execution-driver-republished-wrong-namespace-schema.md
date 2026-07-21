---
type: issue
status: resolved
tags: [testing, runtime, schema, issue]
severity: blocker
---

# Execution driver republished wrong namespace schema

## Evidence

At frozen source HEAD `ddbb4f06` on 2026-07-20, the required selective gate
`bin/test-writer seon.execution-process-test` failed before either real child
spawned. The integration driver's complete registered-schema transaction tried
to publish `:seon.ns/name` as a keyword identity, while the fresh memory
database had correctly installed the canonical symbol identity from the test's
initial program:

```text
:seon.db.writer/attribute :seon.ns/name
:seon.db.writer/expected-schema :db.type/keyword
:seon.db.writer/actual-schema :db.type/symbol

```

The later 11 failures and one error are cascades from the rejected schema
transaction: no child result, database value, spawn, retirement, or replacement
evidence exists. The same frozen HEAD passed the complete CLJS suite at 1,349
tests / 6,257 assertions, and the live pod reports
`(seon.schema/form-string :seon.ns/name)` as
`"[:symbol {:seon.db/identity true}]"`. The fault is therefore isolated to the
integration driver's compiled registry/load boundary rather than the canonical
runtime registration or Datahike schema.

## Expected owner and acceptance

The existing execution integration driver must load the real schema owner and
submit the complete unfiltered keyword-keyed registered population without
overwriting canonical forms. Do not filter the conflicting row or weaken the
writer guard.

Acceptance requires:

- a direct assertion that the driver's registered form for `:seon.ns/name` is
  the canonical symbol identity before the transaction;
- an equal submitted/population count with no filtered schema forms;
- `bin/test-writer seon.execution-process-test` green with two distinct real
  children, current database advancement, bounded stuck-child retirement, and
  replacement-process proof; and
- a new exact-HEAD default artifact before Stage 1.6 live/browser observations
  resume.

## Resolution

Resolved by `95d94666`. The driver now loads `seon.agent.runtime`, the missing
production schema owner in its test-only publisher graph, and fails before its
transaction unless `:seon.ns/name` is the canonical symbol identity. The
complete keyword-keyed registry population remains unfiltered and count-equal.

After rebuilding `:execution-integration-client`, the selective writer gate
passed 1 test / 27 assertions with 1,807 submitted schema rows. The proof used
two real children with distinct PIDs, advanced the database from basis
transaction 536870917 to 536870918, retired the bounded stuck child, kept the
other child responsive, and replaced the retired process with a new PID.
