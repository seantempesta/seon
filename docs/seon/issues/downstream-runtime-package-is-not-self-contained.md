---
type: issue
status: open
severity: blocker
tags: [issue, component, cljs, flow]
---

# Make the downstream runtime package self-contained

## Problem

ACME is intended to prove that a third party can consume and customize Seon
without a Seon source checkout, but its build and operator still require that
checkout. The current “packaged” branch verifies a manifest and then tries to
start a Shadow watcher. The current CLJS entry is a development loader into an
unpackaged, checkout-local Shadow runtime tree.

The standalone writer uberjar solves only the database process. There is no
released CLJS runtime/SDK, source corpus, bootstrap/assets/npm closure, packaged
operator, compatibility manifest, or one-command release/downstream build.

## Evidence

- `bin/acme` resolves and execs the adjacent checkout's `bin/seon`.
- `acme/deps.edn` declares no Seon coordinate; the root artifact builder injects
  it into the root `:cljs` classpath as a local dependency.
- `shadow-cljs.edn` owns the hard-coded `acme-client` build.
- `out-acme/client/main.js` loads 1,037 files from
  `tmp/shadow/acme/builds/acme-client/dev/out/cljs-runtime` and embeds the
  current Shadow server identity.
- `seon.dev.process/specs` always emits the `clj -M:cljs watch` process even
  when `:seon.dev.config/source-checkout?` is false.
- The development artifact manifest contains absolute client/cache paths and
  omits release/protocol/SDK/runtime/dependency/license compatibility data.
- Default and ACME previously published different bootstrap digests while
  replacing the same live `out/bootstrap` path. The development operator now
  publishes content-addressed manifest-bound runtime roots; coordinated live
  proof and the independent packaged form remain in
  [[shared-bootstrap-output-mutates-running-artifact]].
- `seon.platform` and `seon.client` read bootstrap, source corpus, and web
  assets from checkout-shaped paths at runtime.
- The current MCP adapter is itself checkout-owned. CLJS eval requires a Shadow
  nREPL and CLJ eval requires the development writer `io-prepl`, while the
  production process boundary deliberately ships neither.
- Root `LICENSE` is AGPL-3.0 while `package.json` declares ISC.
- Selected ClojureScript is `1.12.145`, but the current
  `reference-code/clojurescript` mirror identifies itself as `1.12.41`.
  Exact source is also absent for Clojure, tools.build, superv.async, and
  partial-cps, so the compatibility contract is not grounded in every selected
  implementation.
- The guarded Proximum force commit `fb6572c…` is local-only, based on upstream
  `v0.1.25`, and cannot be published as `0.1.26`: upstream tag and Clojars
  version `0.1.26` already identify different commit `c1235796…`. Proximum also
  lacks `:deps/prep-lib`, so a cold Git dependency would expose Java source but
  not its required compiled classes.
- Datahike's exact Git dependency supports cold Java preparation, but its
  declared `:paths` omit `src-secondary`. The source writer therefore still
  copies the adapter from `reference-code/datahike/src-secondary` instead of
  consuming the exact public dependency closure.

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
