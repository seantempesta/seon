---
type: prd
status: planned
tags: [prd, component, cljs, flow]
---

# Independent downstream distribution roadmap

## Outcome

A third-party repository pins one Seon release, builds its own ClojureScript
extensions and config/brand overlay, and runs the packaged writer and pod
without reading the Seon source checkout. ACME is the clean acceptance fixture.

## Current state

The writer uberjar is a real independent artifact, and the source-checkout
harness proves useful source, preload, config, brand, cluster, and MCP seams.
The complete consumer remains checkout-dependent: its operator, Shadow build,
CLJS runtime, public SDK, bootstrap/source corpus, static assets, npm closure,
base config, and dependency overrides all come from the Seon repository.

The completed evidence and exact source map are
[[research/independent-acme-distribution-audit-2026-07-14]]. The verified defect
is [[../../seon/issues/downstream-runtime-package-is-not-self-contained]].
Implementation waits for the runtime/database contracts named by the
high-level [[../runtime-reliability/roadmap]] ledger; research and manifest
specification may proceed independently.

## Ordered work

1. Freeze a versioned compatibility manifest for source, database protocol,
   config/SDK ABI, Java/Node requirements, artifact members and digests,
   maintained fork identities, npm lock, and license/SBOM metadata.
2. Publish immutable maintained dependencies and the public CLJS source/macros
   required by a downstream build SDK.
3. Produce a relocatable, devtools-free Node runtime with self-host bootstrap,
   bounded program-source corpus, static assets, and production npm closure.
4. Make the one process graph project watcher + writer + pod for development
   and writer + pod for packaged operation.
5. Replace the closed default/ACME flavor map with one validated downstream
   descriptor for source/preload, dependencies, config, brand, package, and
   cluster defaults.
6. Add one source-repository release command that tests and assembles the
   writer, runtime, SDK, operator, compatibility manifest, hashes, SBOM/notices,
   and license/source metadata.
7. Prove a clean ACME checkout can build, start, customize, MCP-evaluate,
   restart, upgrade safely, and read back while Seon source is inaccessible.

## Graduation

- No package member or manifest contains an absolute producer path.
- Production starts no Shadow server, compiler watcher, or mutable dependency
  resolver and never needs Clojure CLI.
- Default and packaged ACME run simultaneously and remain explicitly
  MCP-addressable with identical custom dependency identities.
- Consumer source, dependencies, config, routes, renderers, and CSS work only
  through documented inputs.
- Repeating producer and consumer builds from the same source/locks is content
  reproducible, and incompatible artifact sets reject before database mutation.
- License, notice/SBOM, source revision, and artifact hashes ship together.
