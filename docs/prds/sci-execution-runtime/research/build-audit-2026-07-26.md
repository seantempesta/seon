---
type: research
status: active
tags: [research, runtime, build]
---

# Build audit, 2026-07-26 — DEV source versus one PUBLISH

## Verdict

The owner-rules axis is simpler than the current build graph:

- **DEV is not a build.** It prepares dependencies, starts the cluster JVM from
  the source classpath, and reloads source through its REPL. It consumes an
  explicitly selected PUBLISH's initialization pages/database identity, but it
  neither builds nor freshness-checks that PUBLISH on `up`.
- **PUBLISH is the one runtime build.** It produces the cluster JVM AOT jar,
  the AppCDS archive trained against that final jar and its exact launch
  options, the JVM program index, mandatory initialization pages, static
  assets, and one digest manifest. Deploy/apply and reset consume this exact
  result; the template database is the deploy-side cache keyed by its digest.
- **Tests run from source.** A writer test may compute a source-current fixture
  in its own process, but it must not force the full PUBLISH graph.
- **The pod build family dies with the pod.** `:client`, `:acme-client`,
  `:bench-client`, `:bootstrap`, the already-removed `:test`, its immutable
  test-bundle publisher, and the already-orphaned self-host worker bundles have
  no target runtime job. The JVM indexer already owns the only surviving part
  of the old `:client` hook except the analyzed Bun inventory, and that inventory
  has no consumer once Bun stops being an execution tier.

This is the split ruled at
`docs/prds/sci-execution-runtime/plan/README.md:294-319`. The State B entry
point independently says that the cluster boot process runs from source in DEV
and loads pages from the selected publish artifact/template database
(`docs/prds/sci-execution-runtime/plan/README.md:446-454`).

The two most important present-tense defects are:

1. DEV normally launches every JVM-family process from the published AOT jar
   and AppCDS archive, not source. The source branch is only a mismatch
   fallback (`script/seon/dev/process.clj:464-490`). The jar contains the source
   snapshot captured when it was built (`build.clj:190-218`), and the live
   classpath contains only that jar (`script/seon/dev/process.clj:471-480`).
   This structurally explains the owner-observed failure today: REPL reload
   reloads the jar's old source, not the edited checkout.
2. `bin/test-writer` executes Clojure from source, but first calls a helper
   which builds/requires the AOT+AppCDS pair, runs the JVM indexer, and tries to
   freeze the **full** client/bootstrap/CSS publication manifest
   (`bin/test-writer:17-48`;
   `script/seon/dev/artifact.clj:1230-1259,1421-1440`). A retained run spent
   51.684 s on AOT, 7.898 s on AppCDS, and 18.619 s indexing, then refused the
   stale publication before running a test
   (`tmp/plan-evidence/jvm-indexer/bin-test-writer-2026-07-26-final.log:1-21`).

## Scope and method

This audit covers every first-party runtime, package, test, and deployment
artifact defined or still consumed by `build.clj`, `shadow-cljs.edn`,
`package.json`, `script/seon/dev/`, `bin/test-*`, `bin/acme`, and
`docker/`. It also records old on-disk worker artifacts whose current consumers
still name them even though no current build definition exists. ML model
training outputs under the preserved diffusion experiments are not Seon
runtime builds and are outside this axis.

Source was read at `4ac6ea9debf3` on
`codex/runtime-reliability-refactor`, later than the requested
`a64a21304`. Times below come only from retained files in
`logs/operator/`, `logs/acme/`, and `tmp/plan-evidence/`; “not retained” means
exactly that and is not an estimate.

Classification:

- **DEV** — source-classpath lifecycle or test work; never a deployable
  artifact.
- **PUBLISH** — a stage or result of the one deliberate deployable build.
- **POD** — historical Bun/self-host output that disappears.
- **PACKAGING** — an optional container/SDK wrapper which, if retained, must
  consume PUBLISH rather than compile a second runtime.
- **DEPLOY** — database state derived from an exact PUBLISH; not a build.

## Inventory

### JVM and publication core

| Today | Owner / invocation | Inputs | Outputs | Invalidation | Retained time | Consumer today | Axis and target existence |
|---|---|---|---|---|---:|---|---|
| Ordinary `jar` | `build.clj:16-18,142-151`; callable as `clojure -T:build jar` | root `deps.edn` basis, `src/`, `resources/`, Git revision count | `target/seon-0.1.N.jar` plus POM staging | source/resources/basis/version | not retained | optional library/SDK consumers; not operator launch | **PACKAGING**, not a cluster-runtime build. Keep only if an actual library distribution remains; never make DEV or PUBLISH depend on it. |
| Explicit writer AOT closure | `build.clj:51-137`; namespace list `resources/seon/dev/writer-aot-namespaces.edn:1-240` | observed `seon.db.server` load closure on the pinned JDK, explicit 240-line list, `:writer` basis; core.async exclusions | `target/seon-writer-aot-classes/`, copied to `target/seon-jvm-aot-classes/` | any observed/list set difference fails; source/dependency/JDK changes rebuild | 47.270 s, 47.728 s, 51.684 s (`tmp/plan-evidence/vector-order-seon-up-2026-07-26.log:3-7`; `tmp/plan-evidence/jvm-indexer/cluster-reset-shadow-stopped-2026-07-26.log:3-7`; `tmp/plan-evidence/jvm-indexer/bin-test-writer-2026-07-26-final.log:3-7`) | `writer-uber` | **PUBLISH stage**, yes, but only over the State B cluster JVM closure. It must not run in DEV. The explicit list remains useful only as a generated/verified PUBLISH input; it must not preserve deleted namespaces. |
| `writer-uber` (name says standalone, implementation now AOT) | `build.clj:190-226`; invoked by `script/seon/dev/artifact.clj:1129-1147` and `script/seon/dev/release.clj:1058-1062` | `[:writer :host]` uber basis, AOT classes, `src/`, Java source | current implementation: `target/seon-database-server-aot.jar`; non-AOT private arm would emit `target/seon-database-server-standalone.jar` | artifact writer-input digest hashes `build.clj`, `deps.edn`, `java/`, `src/`, CLI description, writer classpath/tree, local-root Git identities, and Java identity (`script/seon/dev/artifact.clj:1013-1049`) | same AOT measurements above | DEV JVM family, release packager, standalone schema test | **PUBLISH stage**, yes, renamed to the cluster jar. The non-AOT standalone sibling and duplicate `writer-uber-aot*` entry points (`build.clj:233-241`) should not exist. DEV uses source. |
| AppCDS archive and digest-keyed pair publication | AppCDS primitive `build.clj:173-188,228-231`; pair publisher `script/seon/dev/artifact.clj:1084-1127` | final digest-keyed AOT jar path, exact Java command/runtime, fixed JVM options | `tmp/seon-jvm-artifacts/<identity>/seon-jvm-aot.{jar,jsa}` and `tmp/seon-artifact-build/writer.edn` | writer-input digest, jar semantic/file digest, JDK identity, JVM-option digest, or missing/changed bytes (`script/seon/dev/artifact.clj:1051-1082`) | 5.969 s, 6.510 s, 7.898 s (`tmp/plan-evidence/jvm-indexer/cluster-reset-shadow-stopped-2026-07-26.log:8-9`; `tmp/plan-evidence/vector-order-seon-up-2026-07-26.log:8-9`; `tmp/plan-evidence/jvm-indexer/bin-test-writer-2026-07-26-final.log:8-9`) | DEV writer, host, web-render via `-Xshare:on`; full artifact manifest | **PUBLISH stage**, yes. It must ship beside the one cluster jar, be generated at its final deployed coordinate, and never be required by DEV. |
| JVM program index + initialization pages | `script/seon/dev/program_indexer.clj:1-30,446-555`; command owner `script/seon/dev/artifact.clj:852-905` | all first-party `.clj`/`.cljc` source, production require closure, JVM test sources, loaded canonical schemas, selected config-manifest digest and page-row count | four deterministic files: `program-sources.edn`, `program-rows.edn`, `base-projection.edn`, `page-plan.edn`; page plan contains mandatory pages (`script/seon/dev/program_indexer.clj:476-512,514-528`) | selected source/test/schema closure, config digest, page-row count | 16.216 s and 18.619 s (`tmp/plan-evidence/jvm-indexer/freeze-explicit-config-pages.log:3-11`; `tmp/plan-evidence/jvm-indexer/bin-test-writer-2026-07-26-final.log:10-17`) | pod apply/startgate today; writer fixtures load program sources/rows; target cluster boot/deploy | **PUBLISH stage**, yes. This is the sole program/page producer. `seon.db.protocol` correctly fails when pages are absent (`src/seon/db/protocol.cljc:1846-1856`). The target should collapse the four historical pod-side names to the minimum index/pages projections the JVM actually consumes. |
| “Canonical source artifact” meta-build | `script/seon/dev/artifact.clj:1187-1217,1552-1597`; invoked by `script/seon/dev/cli.clj:131-195` | writer+CLJS deps, AOT pair, bootstrap, Bun install, CSS, live Shadow flush, JVM indexer, config, all source/test paths | every output in this inventory plus a manifest | a broad source-input digest covers build/config/externs/guest/java/resources/src/test and downstream source (`script/seon/dev/artifact.clj:387-418`); currentness then rehashes every output (`script/seon/dev/artifact.clj:737-776`) | component times only; no retained total | `bin/seon up`, cluster reset/apply, `bin/test-writer` indirectly | **Mixed DEV+PUBLISH defect; delete.** DEV preparation and PUBLISH become separate entry points. There is no “canonical development artifact.” |
| Artifact manifest / application digest | schema and digest `script/seon/dev/artifact.clj:47-108,613-735`; assembly `script/seon/dev/artifact.clj:1421-1550` | Bun, client, bootstrap, CSS, AOT pair, JVM artifacts, maintained-dependency identities, config | process-dir `artifact.edn` plus application digest | any broad source-input or output byte change | hashing not separately retained | process specs/readiness, cluster apply/reset, tests, runtime-root key | **Mixed defect today; PUBLISH manifest survives in reduced form.** Remove Bun, client, bootstrap, test, Shadow cache, and DEV source-input currentness. Retain exact cluster jar/archive/index/pages/assets/digests and producer identities. |
| Immutable “runtime root” | `script/seon/dev/artifact.clj:1261-1419` | bootstrap and JVM files plus analyzed Bun inventory | `tmp/seon-runtime-artifacts/<identity>/`, with copied immutable files and symlinks to `src/`, `test/`, `guest-cljs/`, `resources/` | identity is bootstrap + JVM projections + Bun inventory; each new identity creates another directory | not retained | pod environment and runtime-artifact readiness | **POD/mixed; delete.** A PUBLISH directory is already a bounded immutable root. A DEV checkout already is its own source root; digest-keyed symlink forests serve neither side. |

#### JVM publication defects proven during the audit

The source declares one option identity for build and launch
(`build.clj:26-39`; `script/seon/dev/artifact.clj:1000-1011`), yet retained
writer and host launches report that `jdk.module.addmods` and
`jdk.module.enable.native.access` were absent at dump time
(`logs/operator/writer/bf0cdf05-57e1-41f8-b6e5-945f52ee8a1f.log:1-6`;
`logs/operator/host/642240df-e6ee-4f70-9f66-eefd1958beda.log:1-6`). The
process continues, but optimized module handling is disabled. PUBLISH cannot
graduate until `-Xshare:on` starts the exact packaged cluster command with no
CDS/AOT mismatch line and a positive archive-use observation.

The source-free packager has a separate hard mismatch. It invokes
`writer-uber`, whose only current output is
`target/seon-database-server-aot.jar` (`build.clj:197-226`), then normalizes,
hashes, and packages
`target/seon-database-server-standalone.jar`
(`script/seon/dev/release.clj:1058-1062,1086-1097`). A stale file currently
exists at that path, so a local release can silently package old bytes. The
target one-PUBLISH build has one jar name and passes its returned path directly
to all later stages.

### Shadow, CSS, and pod-family artifacts

| Today | Owner / invocation | Inputs | Outputs | Invalidation | Retained time | Consumer today | Axis and target existence |
|---|---|---|---|---|---:|---|---|
| Shadow `:client` | `shadow-cljs.edn:57-81`; watcher selection `script/seon/dev/process.clj:380-419` | `:cljs` classpath, `seon.client/-main`, npm closure, `seon.demo`, source/config; post-`2ef6f0bbd` hook only reads Shadow analyzer state | `out/client/main.js`, dev runtime chunks, `out/client/program-inventory.edn` | Shadow dependency graph and config; artifact layer additionally invalidates it on unrelated JVM/test/config inputs (`script/seon/dev/artifact.clj:531-546,737-776`) | current retained initial flush 6.52 s (`logs/operator/watcher/04ee63bf-f32e-4a31-b024-4cbfae4462e1.log:1-7`) | Bun pod, MCP CLJS REPL, analyzed Bun placement inventory | **POD; delete.** Its old sources/rows/pages hook is already gone; the remaining inventory hook is exactly `script/seon/dev/program_artifact.clj:100-158`. No Bun execution tier remains to consume it. |
| Shadow `:acme-client` | `shadow-cljs.edn:83-112`; flavor `acme/artifact.edn:1-6`; wrapper `bin/acme:75-100` | `:client` mirror plus `acme/` source/preload and own Shadow cache | `out-acme/client/main.js`, chunks, inventory, `artifact-acme.edn` | common inputs plus ACME source/config/preload | 4.98 s on the last retained pre-current build, which also watched now-retired `:acme-execution` (`logs/acme/watcher/a6f78311-1333-415d-814d-e85cb90188ef.log:1-13`) | ACME Bun pod | **POD; delete.** Downstream source joins DEV's source classpath and the same parameterized PUBLISH; it does not get a second runtime build kind. |
| Shadow `:bench-client` | `shadow-cljs.edn:114-137` | `:client` mirror | `out-bench/client/main.js`, inventory, historical `.sha256` | same source graph | not retained | no live owner: `ensure_bench_bundle` now deliberately refuses (`src-inspect-ai/src/seon_inspect/cluster.py:474-479`; proof `src-inspect-ai/tests/test_cluster.py:283-287`) | **POD; delete now.** It is already stranded configuration. A benchmark leases an exact PUBLISH/template database instead. |
| Shadow `:authority-density-client` | `shadow-cljs.edn:139-150` | `seon.db.authority-density-child/-main` and selected CLJS graph | `out/authority-density/client.js` | its source/dependency graph | not retained | one JVM-authority real-process proof | **POD test helper; delete with the pod.** Replace its invariant at the surviving cluster-JVM boundary, not with another Bun runtime. |
| Shadow `:bootstrap` + repair script | `shadow-cljs.edn:152-195`; built by `script/seon/dev/artifact.clj:1204-1208` and release at `script/seon/dev/release.clj:1063-1066` | self-host cljs.js entries/macros and analyzer libraries | `out/bootstrap/` transit/JS cache, then normalized macro metadata | entry/compiler/source changes | wrapper 4.248 s and 4.610 s; Shadow itself 1.15 s and 1.23 s (`tmp/plan-evidence/jvm-indexer/cluster-reset-shadow-stopped-2026-07-26.log:12-67`; `tmp/plan-evidence/vector-order-seon-up-2026-07-26.log:12-67`) | pod self-host eval, orphaned worker eval | **POD/self-host; delete.** SCI on the cluster JVM is the execution owner. |
| Shadow `:test` | definition removed by `96ce8cdfc`; runner remains `bin/test-cljs:215-290,349-413`; watcher still has an optional `:test` arm at `script/seon/dev/process.clj:380-384` though default config disables it | formerly all `-test$` CLJS namespaces, node preload, execution manifest | formerly `out/test/test.js` and immutable test bundle | runner fingerprint covers `src`, `test`, config, build files, locks, selections, downstream inputs (`bin/test-cljs:258-290`) | retained runner fails after 6 s because no `:test` build exists (`tmp/test-cljs-latest.log:1-5`; `tmp/test-cljs-latest.report.edn:1-16`) | none successfully; `bin/test-cljs` is stale | **Already deleted POD test build; finish deletion.** Remove runner/watch/config/test-artifact residue and preserve only invariants rewritten on JVM recurring gates. |
| Immutable CLJS test artifact repository | `script/seon/dev/test_artifact.clj:1-14,154-208,210-269` | Shadow flush output, runtime chunks, analyzed program sources | `out/test/artifacts/{objects,bundles,current.edn}`, retaining 16 bundles | `:test` flush graph and program source digest | not retained | changed-test and `bin/test-cljs` paths only | **Orphaned POD artifact; delete.** No current Shadow build calls its flush hook. |
| `:worker-validator` / `:worker-oracle-eval` | localized authority still claims them at `src/seon/diffusion/AGENTS.md:17-20`, but `shadow-cljs.edn:42-197` contains no definitions; consumers still name `out/worker-oracle-eval/main.js` at `src-inspect-ai/src/seon_inspect/oracle_scorers.py:49,125` and `src-diffusion/src/seon_diffusion/config.py:27` | historical self-host/diffusion CLJS source and bootstrap | stale on-disk `out/worker-*/main.js` only | no current invalidation/build exists | not retained | diffusion oracle/gym consumers | **Already-orphaned self-host artifacts; delete/replace downstream.** They cannot be part of the runtime PUBLISH. The localized authority is stale and must be reconciled in the deletion wave. |
| Tailwind CSS build/watch | scripts `package.json:10-15`; source build `script/seon/dev/artifact.clj:1209-1217`; release `script/seon/dev/release.clj:1071-1076` | `resources/public/css/input.css`, scanned source classes, Tailwind packages/lock, optional brand CSS at package assembly | `resources/public/css/output.css` | input/scanned sources/package lock/brand selection | 171 ms and 187 ms (`tmp/plan-evidence/jvm-indexer/cluster-reset-shadow-stopped-2026-07-26.log:68-78`; `tmp/plan-evidence/vector-order-seon-up-2026-07-26.log:68-78`) | web-render and pod static routes; release package | **DEV transform + PUBLISH stage**, yes. DEV may watch an unversioned local output; PUBLISH runs it once and digests/embeds the result. It must not make JVM source launch depend on a full artifact manifest. |

All `.cljs` is already dispositioned to deletion with zero “port to CLJC”
cases in [[pod-cut-verdict-2026-07-26]]. The roadmap describes the pod as a
pages producer, renderer, and duplicate execution engine; those jobs move to
the JVM indexer, fresh web-render, and deletion respectively
(`docs/prds/sci-execution-runtime/plan/README.md:409-423`). No Shadow target
above earns survival merely because it currently bundles a non-execution
helper.

### Tests

| Today | Owner / invocation | Inputs | Outputs | Invalidation | Retained time | Consumer today | Axis and target existence |
|---|---|---|---|---|---:|---|---|
| `bin/test-writer` source test process | `bin/test-writer:50-79,109-133`; basis `deps.edn:78-87` | `:writer:host:writer-test` source classpath, selected/discovered namespaces | clojure.test result and full-run retained log | source/dependency/test selection | actual test time not reached in cited stale-publication run | recurring JVM correctness gate | **DEV/test, yes; no artifact build.** Keep the source test process and remove its publication prerequisite. |
| `bin/test-writer` compiled-program prerequisite | `bin/test-writer:17-48`; consumers `test/seon/program_indexer_test.clj:8-15` and `test/seon/db/writer_test_support.clj:70-184` | full current artifact manifest/runtime root; verified program sources and rows | environment variables pointing tests at published files | any broad artifact staleness blocks all tests | 79.5 s of build work before refusal in the retained run cited above | indexer property test and shared database fixture | **Mixed defect; delete.** The indexer test calls the indexer directly. The shared writer fixture obtains a source-current in-process index value (or a narrowly cached test fixture) without AOT, AppCDS, Shadow, bootstrap, CSS, runtime roots, or an application digest. |
| Focused changed-test artifacts | `script/seon/dev/changed_test.clj` consumes `SEON_PROGRAM_SOURCE_PATH` at lines 539-540; `bin/test-cljs` and the deleted test publisher own the CLJS half | source graph + analyzed CLJS test graph | affected-test selection / immutable bundle manifests | source graph fingerprint | no successful current measurement | edit hook | **POD residue.** Replace with JVM program-index reverse-closure selection when the index rows are facts, as already specified at `docs/prds/sci-execution-runtime/plan/README.md:290-292`; do not restore `:test`. |

The shared writer fixture does need source-current program rows today: it
verifies program source/row members, derives schema attributes from the rows,
and compiles fixture initialization pages
(`test/seon/db/writer_test_support.clj:144-184,186-233`). That is a legitimate
test input, not authority for requiring a deployable PUBLISH. Calling the pure
indexer in the test JVM preserves the invariant and makes the 16–19 s cost
visible only to tests which need the full program graph.

### Release, container, and deployment artifacts

| Today | Owner / invocation | Inputs | Outputs | Invalidation | Retained time | Consumer today | Axis and target existence |
|---|---|---|---|---|---:|---|---|
| Source-free release package (`bin/seon release`) | CLI `script/seon/dev/cli.clj:785-824`; builder `script/seon/dev/release.clj:999-1134` | downloaded Babashka, operator jar, writer jar, bootstrap, isolated Shadow release, JVM artifacts, CSS, Bun, production node modules, configs, licenses/SBOM | relocatable package with `release.edn`; member map `script/seon/dev/release.clj:49-77` | deliberate build into a nonexistent target; all member bytes | not retained | packaged operator/runtime | **Current mixed PUBLISH+POD implementation; replace with the one PUBLISH.** Keep source-free assembly, provenance, SBOM, config, and exact JVM outputs; remove pod/bootstrap/Bun/node_modules/client inventory. |
| Isolated release pod/program build | `script/seon/dev/artifact.clj:810-830,907-990`; invoked at `script/seon/dev/release.clj:1077-1078` | separate Shadow cache, `:client` release, selected downstream source, JVM indexer config | release `pod.js`, inventory, and four JVM files | all source/config/dependency inputs | not separately retained; JVM stage 16–19 s in source runs | source-free package | **Split result:** Shadow half **POD/delete**; JVM half folds into PUBLISH. One program-index invocation, not a “release programs” sub-build. |
| Babashka operator jar | `script/seon/dev/release.clj:964-985` | staged first-party operator `.clj/.cljc`, `bb.edn` deps minus nREPL bencode | `operator.jar` | operator source/dependencies | not retained | packaged `bin/seon` launcher | **PACKAGING**, optional. If retained, it is assembled after and points at an admitted PUBLISH; it is not part of the cluster application digest unless operator/runtime compatibility requires that policy. |
| Build SDK | CLI optional arm `script/seon/dev/cli.clj:810-823`; source package implementation `script/seon/dev/release.clj:521-590` | exact build/source files and dependency metadata | separate source/build SDK with `sdk.edn` | source/build input digest | not retained | third-party builders | **PACKAGING**, outside the runtime axis. Keep only if there is a product consumer; it must invoke the same PUBLISH implementation, not recreate the old graph. |
| Docker image | `docker/Dockerfile:1-30,101-170`; entrypoint `docker/seon-entrypoint:1-20,38-53` | its own JDK/Bun downloads, deps prep, source, direct `:client`/`:bootstrap`/CSS compiles, a baked source classpath | multi-arch OCI image containing writer source classpath and Bun pod | Docker context/layer inputs and remote downloads | no current retained timing in the allowed evidence locations | historical distribution/benchmark substrate | **Second PUBLISH path plus POD; delete as a compiler.** If an image remains, its Dockerfile only copies one already-built PUBLISH and supplies an entrypoint. It must not compile a parallel runtime or bake another classpath contract. |
| Template database (current path calls it “template store”) | path/clone/publish/reset `script/seon/dev/cluster.clj:42-150`; publish after successful apply `script/seon/dev/cluster.clj:382-437` | one closed applied cluster database, exact application digest, cluster name | `tmp/seon-template-stores/<application-digest>/<cluster>/db`; CoW/reflink reset clone | new PUBLISH digest or cluster name; existing exact template is reused | clone/publish duration not retained | `cluster reset`; deploy/apply cache | **DEPLOY, yes.** It is not a build output. Rename proposal below uses “publish template database” and keeps it keyed only by the admitted PUBLISH digest. |

The template database is the part that already matches the ruled model:
successful apply closes the writer, copies the database under the exact release
digest, and reset clones that exact closed database. The remaining defect is
upstream: source `reset` still builds/current-checks the conflated artifact
before selecting the template
(`script/seon/dev/cli.clj:826-889`). Under the target, reset accepts an
already-admitted PUBLISH identity and never builds.

## What currently makes DEV consume PUBLISH

The coupling is not incidental; it is encoded at five consecutive boundaries.

1. `bin/seon up` calls the source artifact publisher whenever the broad
   manifest is not current (`script/seon/dev/cli.clj:140-195`).
2. The publisher always prepares writer+CLJS, ensures AOT+AppCDS, builds the
   self-host bootstrap and CSS, waits for a client watcher flush, runs the JVM
   indexer, then publishes one manifest
   (`script/seon/dev/artifact.clj:1187-1217,1552-1592`).
3. The manifest requires jar, archive, client, program files, inventory,
   bootstrap, and CSS as one all-or-nothing set
   (`script/seon/dev/artifact.clj:1421-1440`).
4. Process derivation checks that jar/archive pair and chooses it whenever it
   is valid; source is only the error fallback
   (`script/seon/dev/process.clj:426-490`). Writer, host, and web-render all call
   that chooser (`script/seon/dev/process.clj:711-829`).
5. Source process and pod readiness are tied back to manifest/client/bootstrap
   digests (`script/seon/dev/process.clj:1048-1057,1086-1134`), so a normal
   source edit makes the “application” stale even when the JVM could simply
   reload it.

The same helper leaks this coupling into `bin/test-writer`. It is especially
expensive because `prepare-dependencies!` promises dependency preparation but
also builds writer publication, indexes the program, reads Bun identity, and
constructs the whole output manifest
(`script/seon/dev/artifact.clj:1230-1259`). Naming hid the side effect.

## Proposed target build set and names

These are proposals only; no rename is applied by this audit.

| Proposed name | Kind | Exact job | Proposed principal outputs |
|---|---|---|---|
| `dev` / `bin/seon up` | lifecycle, **not a build** | prepare source dependencies when needed; start one cluster JVM and web-render from source; expose REPL/io-prepl; optionally run the CSS watcher | process records, dynamic ports/readiness, unversioned local CSS |
| `publish` / `bin/seon publish <dir>` | the **one build** | from one frozen source/config/dependency/JDK basis, AOT the State B cluster closure, make the one uberjar, train AppCDS at the final jar coordinate with exact launch options, run the JVM indexer and page compiler once, build static assets once, hash and atomically admit the directory | `runtime/cluster.jar`, `runtime/cluster.jsa`, `runtime/program-index.edn`, `runtime/initialization-pages.edn`, optional `runtime/base-projection.edn`, `runtime/public/`, `publish.edn` |
| `deploy` / `bin/seon cluster apply <name> --publish <dir-or-digest>` | operation, **not a build** | admit one PUBLISH, apply its pages to a closed cluster database, verify applied identity, publish the closed template database | applied database facts, exact publish template database, applied-manifest fact/file |
| `reset` / `bin/seon cluster reset <name> --publish <dir-or-digest>` | operation, **not a build** | replace the selected cluster database from the exact publish template; fail clearly if policy requires a template and none exists | cloned database |
| `test-writer` | source test gate, **not a build** | run selected JVM tests from `:writer:host:writer-test`; compute only the in-process program fixture needed by selected tests | test result and retained log |
| `package-image` / `package-sdk` | optional packaging of an admitted PUBLISH | wrap, never recompile, the one PUBLISH for a distribution channel | OCI image and/or build SDK |

Why `publish`, not a renamed collection of `writer-aot`,
`writer-cds`, `release-programs`, `runtime-root`, and `artifact`: the ruling is
one deliberate action with one admitted result. AOT, AppCDS, indexing, pages,
CSS, digesting, and atomic assembly remain internal stages so callers cannot
select a half-publication.

Suggested output names also remove historical topology:

- `cluster.jar`, not `database-server-{aot,standalone}.jar`: State B is one
  cluster JVM, not a writer server plus host.
- `cluster.jsa`, paired by manifest with `cluster.jar`.
- `program-index.edn`, not client-side `program-{sources,rows}.edn`. The JVM
  indexer is the owner; whether source and row projections remain separate
  inside the manifest is an implementation detail.
- `initialization-pages.edn`, not `page-plan.edn`: the dependency's concrete
  value is `:seon.db/initialization-pages`.
- `publish.edn`, not development `artifact.edn`: it is immutable PUBLISH
  admission data, not source currentness.
- `tmp/seon-publish-templates/<publish-digest>/<cluster>/db`, not
  `tmp/seon-template-stores/...`: it is a closed template **database** derived
  from one PUBLISH.

## Exact DEV-from-source change specification

This is a change spec, not an implementation.

### 1. Separate selection from construction

- `bin/seon up` resolves two independent inputs: the checkout source basis and
  an explicitly selected admitted PUBLISH identity for database initialization.
- It never calls `artifact/build!`, `ensure-writer!`, `writer-uber`,
  `writer-cds`, Shadow compile/watch, or the JVM indexer.
- Dependency preparation remains a narrow operation. `:writer`/`:host`
  preparation may fetch/compile dependency-owned prep outputs, but it may not
  publish a Seon runtime.
- A missing required PUBLISH/pages selection fails with one direct remedy
  (`bin/seon publish …` then apply/reset). It never silently constructs pages
  during runtime boot; O15/O16 keep indexing compile-time-only and missing
  pages loud.

### 2. Launch the JVM from the real source classpath

- In a checkout, process argv is unconditionally the existing fallback shape:
  `clojure -J-Xmx… -M:writer:host -m <main> …`
  (`script/seon/dev/process.clj:482-485`). Delete the publication probe from the
  DEV branch rather than arranging for it to mismatch.
- In the State B topology this becomes one
  `clojure -M:writer:host -m seon.cluster.boot …` process. Until that merge
  lands, writer/host/web-render source launches may remain separate, but none
  may use the AOT jar.
- `deps.edn` already declares the source basis: `:writer` replaces paths with
  `src` and owns exact dependencies/JVM options (`deps.edn:11-57`); `:host`
  composes SCI/web dependencies (`deps.edn:59-76`).
- A source edit does not change a process artifact digest or cause a rebuild.
  REPL reload changes behavior in place. A dependency/classpath/JVM-option
  edit is a process restart boundary, still not a PUBLISH build.

### 3. Make readiness runtime truth

- Transitional writer readiness remains a published REPL port file plus an
  accepting request socket; the current probe already checks exactly those
  events (`script/seon/dev/process.clj:963-1008`). State B replaces the socket
  half with the cluster JVM's database-open/readiness fact plus io-prepl
  availability.
- Host/web readiness remains an explicit ready event/endpoint, not an artifact
  hash. The current host log marker is observed at
  `script/seon/dev/process.clj:1059-1062`; the target should publish structured
  readiness instead of parsing prose.
- Remove client/bootstrap/runtime-root checks from source readiness
  (`script/seon/dev/process.clj:1086-1134`). They belong to the deleted pod.
- Database admission readiness compares the database's applied initialization
  identity to the **selected PUBLISH digest and config digest**. It does not
  compare checkout source bytes to PUBLISH bytes; DEV source is intentionally
  allowed to be newer while hot-reloading.

### 4. Keep pages on the PUBLISH/deploy side

- PUBLISH runs `seon.dev.program-indexer` once and records exact pages and
  digests.
- Deploy applies those pages and publishes the closed template database.
- DEV boot reads the selected PUBLISH's page/base data only when opening or
  validating a database that has not already recorded the matching applied
  identity. An already-applied database reopens without rebuilding or
  reapplying.
- Reset clones the template database for that PUBLISH. It never calls a source
  build first.
- Source changes that alter schema/program initialization do not silently
  mutate an existing database. The developer deliberately publishes and
  applies/resets a new PUBLISH. Pure runtime edits remain hot-reloadable.

### 5. Uncouple tests

- Replace `artifact/prepare-dependencies!` at `bin/test-writer:17-22` with
  dependency preparation only.
- Delete the `current-manifest` refusal and
  `SEON_WRITER_ARTIFACT_{MANIFEST,VERSION}` export at
  `bin/test-writer:24-48`.
- Change `seon.program-indexer-test` to call the JVM indexer directly and
  assert its returned value, rather than reading a prior file
  (`test/seon/program_indexer_test.clj:8-15`).
- Change the delayed compiled base in writer test support to call the pure
  indexer/row compiler once inside the test JVM, or a narrow test-only cache
  keyed solely by the indexer's real inputs
  (`test/seon/db/writer_test_support.clj:144-184`). It must not require AOT,
  AppCDS, Bun, CSS, Shadow, runtime roots, or a deploy application digest.
- Remove `bin/test-cljs`, `seon.dev.test-artifact`, optional watcher `:test`,
  and changed-test CLJS bundle paths when their JVM invariant replacements are
  recurring.

### 6. Make PUBLISH self-consistent

- One function returns the final jar path; AppCDS, metadata, SBOM, package
  assembly, and launch manifest consume that returned path. No hard-coded
  AOT-versus-standalone sibling names.
- The AppCDS child uses byte-for-byte the launch options in `publish.edn`.
  Admission launches the packaged command with `-Xshare:on`, fails on any
  archive warning/mismatch, and records positive use.
- The JVM indexer consumes the same frozen source/config basis as AOT. Its
  production roots derive from the cluster entry closure, not the deleted
  writer/host/web topology.
- The publish staging directory is private, all members are digested, and one
  atomic rename admits the complete directory. Intermediate stages are never
  addressable as deployable artifacts.
- Docker and downstream ACME packaging accept this directory as input. They do
  not invoke Clojure, Shadow, Tailwind, or another AOT/indexer build.

### 7. Delete the pod build graph

- Remove `:client`, `:acme-client`, `:bench-client`,
  `:authority-density-client`, and `:bootstrap` with the pod sources.
- Remove the analyzed Bun inventory hook and the Bun/Shadow/client/bootstrap
  fields from the application manifest/digest.
- Remove the immutable runtime-root symlink forest and all source readiness
  gates which require it.
- Reconcile the diffusion localized authority and downstream oracle consumers
  which still name already-absent worker build definitions.
- Keep Tailwind only as an independent DEV watch transform and an internal
  PUBLISH stage. Static browser JavaScript/CSS assets may be resources in the
  jar or manifest members; they are not a new build kind.

## Timing consequence

A cold DEV `up` currently pays approximately the following retained stages
before any JVM process can be reconciled:

| Stage | Retained observation |
|---|---:|
| dependency preparation | 1.118 s |
| writer classpath warm | 0.508 s |
| AOT jar | 47.270 s |
| AppCDS | 6.510 s |
| CLJS classpath warm | 0.522 s |
| bootstrap wrapper | 4.610 s |
| Bun install | 0.020 s |
| CSS | 0.187 s |
| initial `:client` watcher flush (separate retained launch) | 6.52 s |
| JVM indexer when publication is rebuilt | 16.216–18.619 s |

The first eight values are one retained `up`
(`tmp/plan-evidence/vector-order-seon-up-2026-07-26.log:1-78`); the watcher
value is `logs/operator/watcher/04ee63bf-f32e-4a31-b024-4cbfae4462e1.log:1-7`;
the indexer range is
`tmp/plan-evidence/jvm-indexer/freeze-explicit-config-pages.log:1-11` and
`tmp/plan-evidence/jvm-indexer/bin-test-writer-2026-07-26-final.log:1-17`.
They are not summed because they are not one identical run. The ruled split
removes AOT, AppCDS, bootstrap, Bun, Shadow client, and indexing from ordinary
DEV start. It accepts the remaining JVM source-load time rather than hiding it
behind publication work.

## Open owner-taste questions

1. **Command noun:** use the explicit new `publish` command proposed here, or
   retain user-facing `release` while renaming only internal owners? `publish`
   makes the owner-rules axis visible; `release` is conventional but currently
   names the pod-heavy package.
2. **DEV page policy:** this audit reads the ruling and N1 as “DEV source +
   explicitly selected PUBLISH pages.” Should a brand-new checkout refuse until
   one PUBLISH exists, or may it invoke an explicitly named, non-deployable
   `dev-index` preparation? The latter is convenient but creates a second build
   result and weakens the one-axis rule.
3. **Static assets:** embed `public/` in `cluster.jar` for one runtime file pair,
   or keep a digested sibling directory so CSS can be inspected/replaced by a
   packaging layer? Either is one PUBLISH if the manifest owns the bytes.
4. **Program artifact shape:** one `program-index.edn` containing sources/rows
   versus separate `program-index.edn` and `program-sources.edn`. The target
   cluster acquisition contract should decide; historical client filenames
   should not.
5. **AppCDS coordinate:** must PUBLISH be assembled at its final deployment
   path before archive training, or can the packaged layout guarantee a stable
   relative classpath accepted after relocation? The current code asserts that
   copying a trained pair changes its classpath contract
   (`script/seon/dev/artifact.clj:1107-1117`); the release builder currently
   provides no proof.
6. **Operator digest boundary:** is `operator.jar` part of the application
   digest, or separately versioned packaging around the same PUBLISH? Include
   it only if an operator/runtime mismatch can violate the admitted runtime
   contract.
7. **Container and SDK:** are both still supported distribution products? If
   yes, they are packaging consumers of PUBLISH. If no, deleting them removes
   two historical build surfaces.
8. **Downstream naming:** should ACME call the shared operation
   `bin/seon publish --source acme --config config/acme.edn`, or expose
   `bin/acme publish` as a data-only wrapper? There should be no
   `acme-client`-style build identity.
9. **Template retention:** how many old PUBLISH template databases and publish
   directories are retained, and by which explicit prune operation? Current
   digest-keyed JVM/runtime directories have no adjacent bounded-retention
   policy, while the old CLJS test artifact did (`script/seon/dev/test_artifact.clj:14,184-208`).
10. **Hot-reload identity:** when DEV source is newer than the database's
    selected PUBLISH, should status display both the applied publish digest and
    checkout revision, or only the applied digest plus a “source DEV” marker?
    They must not be collapsed into a synthetic application digest which would
    recreate the current coupling.

## Graduation evidence for the split

The refactor is complete only when all of these are observed:

- With no `target/*.jar`, no `tmp/seon-jvm-artifacts`, no Shadow output, and an
  admitted pages/template selection, `bin/seon up` starts the cluster JVM from
  `src` and reaches runtime readiness.
- Editing one loaded namespace and REPL-reloading it changes live behavior with
  zero AOT, AppCDS, index, CSS, or Shadow process in the transcript.
- `bin/test-writer <focused-namespace>` runs from a clean build-output state
  without reading or creating `artifact.edn`, a jar, a JSA, or any `out/client`
  member.
- One `bin/seon publish <dir>` produces the jar/archive/index/pages/assets and
  one manifest; a second byte-identical build has the same content digests.
- The packaged `cluster.jar` + `cluster.jsa` starts with positive AppCDS use and
  no mismatch warning.
- Cluster apply records the exact publish/config identity, publishes a closed
  template database, and reset clones it without invoking a build.
- `rg` finds no live process/config/test/package consumer of `:client`,
  `:acme-client`, `:bench-client`, `:bootstrap`, `:test`,
  `worker-oracle-eval`, `SEON_RUNTIME_ROOT`, or
  `SEON_WRITER_ARTIFACT_MANIFEST`.
- Docker/SDK, if retained, accept only an already-admitted PUBLISH and contain
  no runtime compilation command.
