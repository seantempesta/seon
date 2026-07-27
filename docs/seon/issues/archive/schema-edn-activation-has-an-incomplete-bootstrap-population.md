---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, architecture]
---

# Schema EDN activation has an incomplete bootstrap population

## Problem

The one complete-population admission gate cannot activate the fresh bootstrap
schema population.

## Evidence

The B2 schema-EDN contract requires activation to admit the complete candidate
population after every core predicate namespace has loaded. The fresh
`seon.schema` bootstrap population is not complete before any domain EDN is
added:

```clojure
(require '[seon.schema :as schema]
         '[seon.schema.edn :as schema.edn])

(schema.edn/admit {:seon.schema/forms (schema/snapshot)})

```

On 2026-07-27 this refuses `:seon.ns` because its entity schema references the
absent `:seon.ns/name` declaration. The same form also references
`:seon.ns/source`, `:seon.ns/doc`, `:seon.ns/summary`, and
`:seon.ns/require-edges`, none of which fresh `src/` registers.

This blocks the required boot sequence:

`load predicate namespaces → load! all schema EDN → activate! the population`.

Per-file activation or ignoring the pre-existing error would restore
load-order sensitivity and violate the sealed global-population contract.

## Owner

The B2 schema/bootstrap contract owner must decide whether the program-graph
entity declarations are still bootstrap shapes or domain declarations that
belong in schema EDN with their complete referenced closure.

## Acceptance

Resolved in the schema-EDN conversion commit:

- `schema.edn/admit` accepts all 46 bootstrap declarations in a fresh JVM.
- The four domain EDN resources load after predicate registration and
  `seon.cluster` activates the one complete population.
- The integrated focused gate passed 34 tests / 137 assertions.
- Full `bin/test` passed 52 tests / 212 assertions.
