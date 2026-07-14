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
- `seon.platform` and `seon.client` read bootstrap, source corpus, and web
  assets from checkout-shaped paths at runtime.
- Root `LICENSE` is AGPL-3.0 while `package.json` declares ISC.

Full evidence and the proposed artifact boundary are in
[[../../prds/runtime-reliability/research/independent-acme-distribution-audit-2026-07-14]].

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
- Packaged operation starts writer + pod without Clojure CLI, Shadow, a watcher,
  absolute producer paths, or mutable dependency selection.
- The release binds exact maintained dependency identities and rejects a mixed
  writer/pod/SDK/config protocol set before database mutation.
- The no-source proof exercises consumer source, dependencies, config, routes,
  renderers, brand CSS, MCP CLJ/CLJS evaluation, restart, and database readback.
