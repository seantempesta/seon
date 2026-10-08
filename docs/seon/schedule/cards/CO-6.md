---
title: "Card CO-6: Toolkit namespaces follow what they read"
type: reference
status: active
created: 2026-10-08
---

# CO-6 Toolkit namespaces follow what they read

Patterns P1, P3. Lane: Sonnet.

## What exists
`seon.cluster/ensure-cluster-entity!` calls `seon.cluster.instruction/toolkit-namespaces` (a join over every
function's `:seon.fn/ns`, `:seon.fn/spec`, `:seon.fn/macro?`, `:seon.fn/private?` and `:seon.ns/context-relevant?`)
on every ensure, including the test world's: up to 0.325 GB in a two-test check window (variable: 0.059 GB in the
candidate window).

## Change
Look the value up in `seon.cache` (kind `::toolkit-namespaces`) keyed by `seon.db/content-revisions` of those five
attributes, the same keying `seon.test.runner/reach-attribute` uses; a nil revision derives.

## Proof
`seon.cluster.instruction-test/toolkit-namespaces-follow-their-attributes` (new): two calls on one value: the second
`(calls-of m 'seon.db/q)` is 0; after a write making a namespace context-relevant, the value includes it.

## Cost
Expected: O(5 attributes) on a hit. Parent ≤ 0.325 GB per check.

## Commit
`bin/lane commit <lane> -m "CO-6: toolkit namespaces follow what they read" --test seon.cluster.instruction-test src/seon/cluster/instruction.clj test/seon/cluster/instruction_test.clj`

## Dependencies
CO-0.
