---
type: issue
status: open
severity: friction
tags: [issue, schema, contract, class-kill]
---

# Make every durable contract predicate identifiable

## Problem

Anonymous and inline predicate contracts can validate transiently but cannot
be recorded, resolved, restored, rendered, or diagnosed by stable identity.
The same defect has reappeared through different contract-writing surfaces.

## Evidence

Four open issues recur on 2026-08-02, 2026-08-07, 2026-08-08, and
2026-08-11:
[[a-search-contract-predicate-cannot-be-made-durable]],
[[an-inline-fn-predicate-in-src-refuses-every-corpus-projection]], and
[[anonymous-runtime-contracts-have-recurred]], plus the concurrently filed
[[unregistered-ifn-malli-schema-breaks-source-publication]]. The last note is
the same construction at schema identity rather than predicate identity: a
contract admits a name no registry can resolve and fails only in a later
whole-tree projection.

`seon.flow/start-graph!` supplied a fresh instance with an inline `clojure.core/ifn?` join predicate; it now references the registered `:seon.flow/join!` schema.

## Owner

Schema registration and contracted-definition admission.

## Acceptance

- A durable contract can contain only registered, qualified predicate
  identities; the constructor has no field for an anonymous function.
- Refusal names the anonymous member and the required registration action.
- Publication, cold acquisition, and rendered documentation resolve the same
  identity without source replay.
