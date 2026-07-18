---
type: issue
status: open
severity: blocker
tags: [issue, component, cljs, flow]
---

# Make the downstream runtime package self-contained

## Problem

ACME now proves that a third party can build and run a customized source-free
Seon production package without access to the producer checkout. The remaining
blocker is completing the public distribution contract around that working
runtime: a general downstream descriptor in place of the closed default/ACME
development flavor map.

## Evidence

- The production package contains a standalone JVM writer, compiled Bun pod,
  compiled Bun execution child, self-host bootstrap, bounded program source,
  assets, production npm closure, packaged operator, configuration include
  graph, and license. It starts no Shadow watcher and needs no Clojure CLI.
- `bin/seon release <runtime> --sdk <sdk>` emits the runtime and separate build
  SDK. The SDK comes from committed source, carries exact source/Datahike/Bun/
  Babashka revisions, and does not copy the producer's mutable dependency tree.
- Two pristine SDK extractions built complete ACME packages while the host
  denied all reads beneath the producer checkout. Their entire package trees
  are byte-identical with application digest `8d5877b9…` and identical release
  manifests (`3db8fe1a…`).
- The production ACME package has rendered custom surfaces and CSS, run ACME
  functions in a real Bun execution child, restarted, read committed results
  back, and remained recursively immutable.
- SDK revision `245e96f5…` includes the existing MCP launcher and project
  registrations. Under producer-read denial it built and started a clean
  development cluster, then returned `:sdk-writer/42` through `eval_clj` and
  `:sdk-pod/42` through cluster-qualified `eval_cljs` before a clean shutdown.
- Release application `ce1f0284…` ships manifest-bound source revisions,
  Bun/Datahike/Babashka license texts, third-party notices, and a CycloneDX 1.6
  inventory of 36 npm and 81 JVM components. A second complete build is
  byte-identical; both manifests have SHA-256 `21af6e45…` and both SBOMs have
  SHA-256 `66dac763…`.
- Development artifact manifest version 4 still contains absolute
  client/cache paths and omits release/protocol/SDK/runtime/license
  compatibility data. It now binds the exact six maintained public-Git
  dependency coordinates and normalized writer digest into application
  identity, while retaining version 2/3 read compatibility. Proximum is now
  public at `9846d3e79e1aee48474bc876d3d563d7137209c6`; cold version-4 root
  publication proof is active.
- Default and ACME previously published different bootstrap digests while
  replacing the same live `out/bootstrap` path. The development operator now
  publishes content-addressed manifest-bound runtime roots; coordinated live
  proof and the independent packaged form remain in
  [[shared-bootstrap-output-mutates-running-artifact]].
- `seon.platform` and `seon.client` read bootstrap, source corpus, and web
  assets from checkout-shaped paths at runtime.
- Development MCP remains intentionally backed by the SDK's Shadow nREPL and
  writer `io-prepl`. The production process boundary deliberately ships
  neither.
- Root `LICENSE` and `package.json` both declare AGPL-3.0-only.
- Selected ClojureScript is `1.12.145`, but the current
  `reference-code/clojurescript` mirror identifies itself as `1.12.41`.
  Exact source is also absent for Clojure, tools.build, superv.async, and
  partial-cps, so the compatibility contract is not grounded in every selected
  implementation.
- Proximum `9846d3e79e1aee48474bc876d3d563d7137209c6` is the public upstream-
  `v0.1.26` descendant with guarded force and `:deps/prep-lib` compilation of
  checked-in Java.
- Datahike `9ada755087228e10cfb179fa5779ce227a6ed220` is public, supports cold
  Java preparation, declares `src-secondary`, and selects idempotent Konserve
  `b5c99bc02a7175652a610324215288b78551801f`. Root local-path removal and cold
  writer/manifest proof remain in progress.

Full evidence and the proposed artifact boundary are in
[[../../prds/independent-downstream-distribution/research/independent-acme-distribution-audit-2026-07-14]].
Current-head reconciliation and parallel implementation order are in
[[../../prds/independent-downstream-distribution/research/acme-artifact-boundary-reconciliation-2026-07-14]].
The refreshed source ledger and next offline executable slice are in
[[../../prds/independent-downstream-distribution/research/no-source-package-inventory-slice-2026-07-15]].
The exact Proximum/Datahike public-Git cutover, upstream divergence, cold-prep
contract, and source-absent writer/SDK proof are in
[[../../prds/independent-downstream-distribution/research/proximum-datahike-publication-path-2026-07-15]].

## Owner

The release artifact and operator boundary: `build.clj`,
`script/seon/dev/artifact.clj`, `script/seon/dev/config.clj`,
`script/seon/dev/process.clj`, the CLJS production build, and ACME as the
no-source acceptance fixture.

## Acceptance

- One Seon source-repository command emits a versioned writer uberjar, CLJS
  runtime/SDK package, compatibility manifest, hashes, SBOM/notices, and
  license/source metadata.
- A clean ACME checkout pins that release and builds/runs/customizes it while
  the Seon source checkout is inaccessible.
- The no-source development SDK supports cluster-qualified CLJ and CLJS MCP
  evaluation from the downstream root. Production does not inherit development
  REPL servers accidentally.
- Packaged operation starts writer + pod without Clojure CLI, Shadow, a watcher,
  absolute producer paths, or mutable dependency selection.
- The release binds exact maintained dependency identities and rejects a mixed
  writer/pod/SDK/config protocol set before database mutation.
- The tested Proximum and Datahike commits are reachable by public HTTPS Git
  SHA; a disposable-cache producer build prepares both, exposes Datahike's
  secondary adapter without `reference-code/`, and resolves no local root or
  unpublished Maven coordinate.
- Proximum preparation compiles checked-in generated Java, while release CI
  regenerates it separately and requires a clean diff. Two cold builds produce
  the same normalized writer digest.
- The no-source proof exercises consumer source, dependencies, config, routes,
  renderers, brand CSS, MCP CLJ/CLJS evaluation, restart, and database readback.
- Every selected dependency has exact mirrored source, and one pure package
  inventory test rejects absolute or escaping paths, symlinks, missing or
  changed members, undeclared production npm dependencies, license mismatch,
  and mixed writer/runtime/SDK compatibility identities.
