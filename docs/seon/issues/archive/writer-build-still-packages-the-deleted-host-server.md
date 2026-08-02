---
type: issue
status: resolved
severity: blocker
tags: [issue, deletion, build, quarry]
---

# Delete the writer build that still packages the old host server

## Problem

The root build and dependency manifests still expose a canonical standalone
writer artifact whose entry namespaces exist only in `src-old/`. This is a
live build-time reader into the quarry, and an active reference page still
instructs third parties to build the artifact even though Seon's current
runtime is one fresh JVM process.

The old code is not merely present as archaeology: the build basis places it
on the classpath, AOT derives from it, and the uberjar names it as the main
runtime closure.

## Evidence

- `deps.edn:102-164` labels `:writer`, `:host`, and `:writer-test` old-system
  aliases, but `:writer` uses `:replace-paths ["src" "src-old"]` and
  `:writer-test` adds `test-old` to executable classpaths.
- `build.clj:21-24` makes `[:writer :host]` the writer artifact basis.
  `writer-aot!` loads `seon.db.server` (`:104-128`), AppCDS requires
  `seon.db.server`, `seon.host`, and `seon.web.server` (`:176-188`), and
  `writer-uber!` publishes `seon.DatabaseServerMain` (`:190-221`). Those
  namespaces and the Java main's server target survive only in the old
  system closure.
- `resources/seon/dev/writer-aot-namespaces.edn:244` still seals
  `seon.db.server` into the measured AOT roster.
- `docs/seon/reference/third-party-setup.md:18-38` calls the two-process pod
  topology the settled target and tells readers to run
  `clojure -T:build writer-uber`.
- Fresh operator references to `seon.db.server`, `seon.host`, and the old jar
  at `script/seon/fresh_operator.clj:451-471` are only legacy-process
  detection so `status`/`down` can report old processes. That defensive
  reader does not require the namespaces and is not a reason to preserve the
  build.

## Owner

The root artifact/build boundary. The one fresh `seon.cluster` JVM is the
runtime owner; `src-old/` and `test-old/` remain non-executing quarry.

## Acceptance

- Delete the old writer/host/test dependency aliases, writer AOT roster, Java
  old-server main, and writer uber/AppCDS tasks, or replace a genuinely needed
  distribution with one artifact built exclusively from fresh `src/` and the
  current cluster entrypoint.
- No root build or active instruction places `src-old/` or `test-old/` on an
  executable classpath or requires `seon.db.server`, `seon.host`, or
  `seon.web.server`.
- Third-party packaging guidance is observed against the fresh artifact and
  operator; the old two-process topology is not described as a target.
- Legacy process detection remains data-only until no deployed old process
  needs cleanup, then is deleted in its own outer-reader cut.

## Resolution

Resolved by `ca1876c1d`, `430094df2`, `52fbf0624`, `e9042e9e1`,
`80f23b0f4`, and `022520292`. The writer/host aliases, writer AOT and AppCDS
tasks, old Java main, AOT roster, and old third-party build page are gone. The
replacement uses the default dependency basis, a thin `seon.ArtifactMain`, and
the fresh `seon.cluster/start!` path. The jar embeds the build-time
initialization pages as prepared transaction data; boot does no source
analysis (`build.clj:65-100`; `src/seon/artifact.clj:45-68`). Store creation
enables root fusion (`src/seon/cluster/store.clj:156-179`), and the file store's
commits use the ordered batch path
(`reference-code/datahike/src/datahike/writing.cljc:497-528`).

The frozen build input fingerprint was identical before and after the 25.33 s
build. The standalone jar's SHA-256 was
`c5d4854ecb7e970cee0316baf21e7d8b6793ada13a1fd9212475f9c5d84aa777`.
From a copied jar with no checkout classpath, three independent empty roots
reached READY and served `<title>seon · root</title>` in 20,914.273 ms,
20,717.948 ms, and 19,693.582 ms. Their median namespace-load and
initialization-install phases were 12,068.300 ms and 7,431.300 ms. A reopen
reached READY in 13,368.212 ms with a 56.547 ms converged installation check;
a second reopen preserved exact `current-src` commit ID
`6a6f3c3a-fb89-520d-8c4a-64a98e729e99`.

The owner ruled that this cold initialization is an accepted deployment cost;
the ten-second law governs the development loop, and no AOT mechanism is
introduced. Reader-chase found the old namespace names only in the fresh
operator's data-only legacy-process detection and its recurring test. No live
build or instruction reaches `src-old` or `test-old`.
