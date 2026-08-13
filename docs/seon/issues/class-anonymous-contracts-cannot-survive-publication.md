---
type: issue
status: open
severity: friction
tags: [issue, schema, class/n6, class-kill, wave/class-kill-queue]
---

# Make every durable contract predicate identifiable

## Problem

Anonymous and inline predicate contracts can validate transiently but cannot
be recorded, resolved, restored, rendered, or diagnosed by stable identity.
The same defect has reappeared through different contract-writing surfaces.

## Evidence

Current open members carry `class/n6` and are derived with
`bin/issues-index --class class/n6`.

`seon.flow/start-graph!` supplied a fresh instance with an inline `clojure.core/ifn?` join predicate; it now references the registered `:seon.flow/join!` schema.

## Owner

Schema registration and contracted-definition admission.

## Acceptance

- A durable contract can contain only registered, qualified predicate
  identities; the constructor has no field for an anonymous function.
- Refusal names the anonymous member and the required registration action.
- Publication, cold acquisition, and rendered documentation resolve the same
  identity without source replay.
