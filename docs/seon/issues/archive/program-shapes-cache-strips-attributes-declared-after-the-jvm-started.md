---
type: issue
status: resolved
severity: friction
created: 2026-09-17
tags: [program-graph, schema, adoption, dev-jvm, cache]
---

# The program-shapes cache strips attributes declared after the JVM started

## Problem

`seon.program/shapes` (`src/seon/program.cljc:~120`, the derivation that
replaced the hand-maintained mirror in `8795db4ac`) caches the authored
declarations in a process-level `defonce` and caches only a success.
`seon.fn/artifact` filters every indexed row through
`program/canonical-row`, so a schema attribute declared AFTER a JVM
started is silently stripped from that JVM's indexed rows: the runner lane
declared `:seon.test/long-ms`, the analyzer lifted it, and `canonical-row`
dropped it — the regression read that as "the indexer does not lift it".
Gate workers are fresh JVMs and unaffected; the development JVM's adoption
of any new attribute is not, and every in-process proof of a new attribute
on `default` is misled until a restart.

The same shape (a delay/defonce holding a snapshot the authority later
re-decides) appeared four times today: the fixture base, the effect
`probe-ctx`, the shapes resolver's first version, and now the shapes cache
itself.

## Fix shape

Adoption is the event: when `init --dev` reconciles schema declarations it
must refresh the shapes derivation (or the derivation reads the
declaration population the adoption handed it — §2.1, values carry their
world — instead of a process global). Regression: declaring a new
attribute and adopting it in place makes the next `artifact` carry it,
without a restart.

Related: `program-shapes-mirror-the-schema-row-maps-by-hand` (resolved),
`in-process-test-runs-poison-the-shared-fixture-base`.

## Resolved — 2026-09-17

`seon.program/shapes` no longer answers from a process-lifetime snapshot. The
authored-resources fallback caches under `seon.schema.edn/declaration-stamp`
(the sorted `[name, length, last-modified]` of the schema resources), so a
declaration edit is a cache MISS BY CONSTRUCTION; and the indexer no longer
asks a global at all — `seon.fn/build-artifact` and `build-manifest` take an
optional `:seon.schema.projection/forms`, `index!` resolves ONE population per
operation, and `artifact`, `normalized-index-row` and `reconcile-tx-in` carry
it per row.

Live on `default` (pid 88182, never restarted): after adoption
`6aaaa62d-c08e-571f-ace2-ca01f86d1e27`, an artifact of
`test/seon/cluster/boot_test.clj` carries `:seon.test/long-ms 600000`.
Regressions: `seon.program-test/a-declaration-added-after-the-first-call-is-a-cache-miss`
and `seon.fn-test/an-attribute-declared-after-this-jvm-started-is-indexed-without-a-restart`.
Landing note:
[program-shapes-follow-adoption-2026-09-17](../../prds/steward-platform/research/program-shapes-follow-adoption-2026-09-17.md).
