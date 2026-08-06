---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, program-graph]
---

# Preserve empty composite schemas in schema-shape facts

## Problem

Complete current-source publication refused the authored
`:seon.reconcile/request` schema. Its legitimate
`:seon.reconcile/desired` child is `[:vector [:map]]`, but P12 schema-shape
normalization expanded the empty map as `[:map [nil nil]]`. The resulting
noncanonical form violated `seon.schema/canonical-definition`'s output
contract and stopped `populate-source!`.

## Cause

`seon.fn.schema-shape` destructured every schema vector as
`[schema-type a & more]` and used `(cons a more)` when `a` was not a property
map. A one-element composite such as `[:map]` has no `a`; the code therefore
fabricated a `nil` child. Map-entry expansion then interpreted that child as
one `[nil nil]` entry. The authored reconcile schema and Malli registry
expansion were valid.

## Resolution

Schema-form splitting now separates the type, optional properties, and actual
tail without manufacturing a missing child. Canonicalization, registry
expansion, and shape-row decomposition share that operation; the normalization
revision advances to `p12-v2`. The recurring
program-graph test builds a referenced reconcile request through the canonical
source/Malli join and follows its typed map-entry and child facts, proving the
nested empty map remains exactly `[:map]` with no entries.

Complete `bin/seon init` publication plus a Datalog query over `current-src`
is the live graduation proof.
