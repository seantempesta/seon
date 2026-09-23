---
type: issue
status: open
severity: friction
tags: [issue, performance, memory, schema]
---

# Projection replacement generations retain replaced compiled schemas

## Problem

A projection derived by replacement (`seon.schema/projection-with-declarations`,
reached from `build-projection` with a supplied projection,
`projection-with-schema`, `projection-without-schema`,
`projection-with-function-contract` and `load-projection` with a base) keeps
every unaffected compiled schema as Malli's own object from the previous
registry. A Malli ref resolves through the registry it was compiled in
(`malli.core` `-ref-schema` keeps its `options` `:registry`), and
`malli.registry/lazy-registry` (`reference-code/malli/src/malli/registry.cljc:81-95`)
keeps every schema it compiled in its `cache*` atom. So each generation's lazy
registry stays reachable from its retained schemas, and with it the compiled
objects of declarations that later generations replaced. Nothing reachable
resolves a replaced key through the old registry (every referrer of a changed
key is in the recompiled closure), but the objects are held strongly.

## Evidence

Probe JVM, 2026-09-23, lane incremental-declaration
(`tmp/lane-incdecl/mem2.clj`), default's population (3,350 schemas, 1,925
contracts), successive `projection-with-schema` replacements that add one
property to each of 200 distinct keys, used heap after three `System/gc`:

| | after 100 | after 200 |
|---|---|---|
| parent `6e3fe5cce` schema.clj | +17.3 MB | +38.5 MB |
| `1625fb9bc` | +17.6 MB | +38.8 MB |

About 190 KB per replacement generation, linear, identical before and after
the incremental-derivation commit: the growth is a property of retaining
Malli objects across generations, which `projection-with-schema` already did.
A whole build of the same population is 16.3 MB.

The incremental commit makes this reachable from the committed-declaration
path once `seon.db`'s projection memo hands `load-projection` a base: every
committed declaration change would add one generation until the next whole
build (a boot).

## Owner

`seon.schema/projection-registry` (the generation's registry construction).

## Acceptance

- A chain of N replacement derivations retains memory bounded by the current
  population, not by N; one regression measures a chain against a whole build.
- The derivation still equals the whole build
  (`seon.schema-test/an-incremental-projection-equals-its-full-build`).
