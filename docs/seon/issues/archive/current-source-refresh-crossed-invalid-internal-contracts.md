---
type: issue
status: resolved
severity: blocker
tags: [issue, source, schema, instrumentation]
---

# Keep current-source refresh inside its public contracts

## Problem

The complete current-source path worked without instrumentation but crossed
three invalid internal values once the live operator instrumented public
contracts: the one-argument refresh delegated `nil` to a vector argument, the
Datahike schema projection received a set where it requires a sequential
collection, and ordinary qualified predicate symbols were rejected instead of
being resolved as Malli resolves them.

## Evidence

Successive live edit-hook and `init --force` attempts failed at
`seon.fn/many-or-component-attributes`, `seon.schema/canonical-definition`,
and finally `seon.cluster/refresh-source!` with an invalid `nil` second
argument. Each failure appeared only after the previous contract crossing was
removed.

## Owner

The one static program-graph build and publication path:
`seon.fn`, `seon.schema`, and `seon.cluster/refresh-source!`.

## Acceptance

- Complete and changed-path publication run inside the instrumented operator.
- Core predicate symbols remain canonical data and compile successfully.
- The one-argument refresh represents complete publication with an empty
  changed-path vector.

## Resolution

The projection now passes a deterministic sorted sequence, registered custom
predicates still win and only `clojure.core` predicate symbols resolve without
registration, and complete refresh delegates with `[]`. Focused schema/function/boot tests
are green, the edit hook advanced `current-src`, and live `init default
--force` completed.
