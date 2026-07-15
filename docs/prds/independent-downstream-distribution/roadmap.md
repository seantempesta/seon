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
The checkout-global source build now serializes default/ACME fixed outputs and
reuses one verified canonical writer, so both live targets publish writer
digest `80054020…` and resolve the same maintained dependency SHAs. That closes
writer collision and parity inside this checkout; it does not publish a
consumer contract.

The complete consumer remains checkout-dependent: its operator, Shadow build,
CLJS runtime, public SDK, bootstrap/source corpus, static assets, npm closure,
base config, and dependency overrides all come from the Seon repository.
The audit found that a later ACME build replaced the shared `out/bootstrap`
after the default manifest was published. The bounded implementation now
publishes bootstrap bytes beneath a content-addressed runtime root, records
that root in artifact manifest version 3, injects it only into the owning pod,
and makes readiness re-hash the exact manifest-bound bytes. Deterministic
sequential default/ACME tests prove the second publication cannot mutate the
first. A coordinated live rebuild was deliberately not run in this unit, so
the remaining live gate stays in
[[../../seon/issues/shared-bootstrap-output-mutates-running-artifact]].

The completed evidence and exact source map are
[[research/independent-acme-distribution-audit-2026-07-14]], grounded by the
earlier boundary audit
[[research/client-distribution-and-server-rendering-boundary-2026-07-13]]. The
current-head dependency ledger, parity evidence, MCP mode distinction, and
parallel carve-out are
[[research/acme-artifact-boundary-reconciliation-2026-07-14]]. The refreshed
exact-source ledger and smallest offline manifest/inventory slice are
[[research/no-source-package-inventory-slice-2026-07-15]]. That audit found
that selected ClojureScript `1.12.145` is not mirrored: the current mirror is
`1.12.41`. Exact source is also absent for Clojure, tools.build,
superv.async, and partial-cps. Those gaps must close before freezing a release
schema under the repository source policy. The
verified defect is
[[../../seon/issues/downstream-runtime-package-is-not-self-contained]].
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

## Dependency order and parallel execution

First mirror every exact selected dependency source named in the refreshed
inventory and settle the database protocol plus config/public-SDK ABI version
owners. Then implement the pure offline compatibility-manifest and package
inventory contract described by the July 15 audit. Its focused test is the
earliest executable slice and operates no pod, writer, watcher, or ACME
process. After that, three bounded units can proceed in parallel:

- publish the public CLJS SDK plus immutable maintained dependency coordinates;
- build the relocatable runtime, bootstrap/source/assets/npm closure; and
- project the one operator through a validated downstream descriptor for
  development and packaged operation.

Their integration is sequential: all three manifests must agree before the
producer release command; the release must exist before the clean no-source
ACME build; and restart/upgrade/rollback proof waits for the database-lifecycle
recovery contract. Research, manifest schemas, package-inventory tests, and
isolated build probes do not wait for lifecycle implementation.

MCP acceptance has two explicit modes. A no-source downstream **development**
build supports cluster-qualified CLJ and CLJS eval through its own SDK/tooling.
The immutable production package starts no Shadow server or writer `io-prepl`.
If production MCP evaluation becomes a requirement, it needs one deliberate
secured runtime protocol; development REPL servers are not silently shipped.

## Graduation

- No package member or manifest contains an absolute producer path.
- Production starts no Shadow server, compiler watcher, or mutable dependency
  resolver and never needs Clojure CLI.
- Default and packaged ACME run simultaneously and remain explicitly
  addressable with identical custom dependency identities. The clean ACME
  development build is explicitly MCP-addressable in both runtimes.
- Consumer source, dependencies, config, routes, renderers, and CSS work only
  through documented inputs.
- Repeating producer and consumer builds from the same source/locks is content
  reproducible, and incompatible artifact sets reject before database mutation.
- License, notice/SBOM, source revision, and artifact hashes ship together.
