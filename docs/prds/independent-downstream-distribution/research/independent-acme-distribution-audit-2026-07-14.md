---
type: research
status: completed
tags: [research, orchestrator, cljs, component]
---

# Independent ACME distribution audit — 2026-07-14

## Question

Can ACME, as the representative downstream product, build, run, and customize
Seon without a Seon source checkout? What must the Seon source repository
publish, and what must a downstream repository own?

The answer is **not yet**. The current source-checkout harness proves useful
composition seams and the writer uberjar is real, but there is no published,
self-contained CLJS runtime/SDK or production package. `bin/acme` still derives
its entire build and operator from the Seon checkout. This report is read-only
with respect to runtime source and live processes. The active [[../roadmap.md]]
remains the sole current-state and implementation-order authority.

## Target boundary

The default Seon repository is the producer. One release operation builds,
tests, versions, signs or hashes, and publishes two coordinated artifacts:

1. a standalone JVM database-server uberjar; and
2. a relocatable Node CLJS runtime plus downstream build SDK.

The runtime package contains the immutable pod closure, self-host bootstrap,
the exact runtime source corpus that code-as-data indexing needs, static web
assets, the base config manifest, production Node dependencies, the shared
operator, licenses/notices, and one compatibility manifest. The SDK contains
the public CLJS source/macros and build entry needed to compile consumer-owned
namespaces into that runtime. A source archive may accompany the AGPL release,
but the consumer's build and launch commands do not depend on checking it out.

A downstream repository owns only its source and dependencies, config deltas,
routes/renderers, brand data/CSS, and deployment policy. It pins one Seon
release, supplies its own Maven/Git and npm inputs through declared manifests,
and invokes the shipped build/operator commands. ACME is the acceptance fixture
for this public contract, not a hard-coded artifact flavor in production code.

The compatibility manifest binds at least:

- Seon release and source revision;
- database protocol and config-manifest schema versions;
- writer uberjar digest, required Java version, and launch flags;
- CLJS runtime, bootstrap, source-corpus, static-asset, and operator digests;
- ClojureScript, Shadow, Node, Datahike, Konserve, superv.async, partial-cps,
  npm lock, and consumer-SDK coordinates;
- supported consumer extension ABI/build format; and
- license/notice/SBOM locations.

The downstream package embeds the exact producer manifest and adds its own
source/dependency/config/brand digests. Startup rejects mixed or incompatible
producer and consumer packages; it never recompiles or chooses “latest.”

## What already works

### Standalone JVM writer

`build.clj` has one `writer-uber` target. It builds
`target/seon-database-server-standalone.jar` from the same root `:writer` basis
used by source launch. That basis owns the maintained Datahike and Konserve
pins and includes Datahike's secondary-index source. The current artifact is a
48 MB executable jar, and both default and ACME launch it through the same JVM
argv. This is the strongest existing no-source boundary.

The jar is not yet a release: its fixed output name has no embedded release
manifest beside it, and the repository has no command that assembles and
publishes the complete coordinated server + pod distribution.

### Downstream source and data seams

The source harness proves the intended customization mechanisms:

- `acme/deps.edn` is a consumer-owned Clojure dependency project;
- `SEON_EXTRA_SRC` and `SEON_EXTRA_PRELOAD` compile consumer namespaces into
  the pod and register their public surface;
- `config/acme.edn` composes the base manifest and can add context, routes, and
  home requirements without editing `src/seon`;
- `SEON_BRAND_*` supplies product data and `SEON_BRAND_CSS` loads downstream
  CSS after the shipped stylesheet;
- ACME owns a separate database, sockets, logs, process state, client output,
  Shadow cache, and dynamic endpoints; and
- the unified MCP server can address default and ACME by cluster-qualified
  runtime identity.

The version-2 development artifact manifest also proves a useful start: it
ties one flavor to writer, client, bootstrap, CSS, and application digests.

### Dependency convergence inside this checkout

The root `:writer` and `:cljs` aliases currently keep default and ACME on the
same maintained Datahike `6f90b339…`, Konserve `df6818d4…`, superv.async
`3e6ed755…`, and partial-cps `1e119b03…` revisions. This is valid evidence for
simultaneous local experiments. It is not a downstream release contract:
`acme/deps.edn` declares no Seon coordinate and inherits those versions only
because the root operator injects it into the root classpath.

## Verified blockers

### The consumer build is rooted in the Seon checkout

`bin/acme` computes `SEON_ROOT` from its location and execs that checkout's
`bin/seon`. It points `SEON_EXTRA_SRC` at `$SEON_ROOT/acme`, chooses the
checkout's hard-coded `acme-client` Shadow build, and reads the checkout's
`config/system.edn`, CSS, bootstrap, operator namespaces, and build aliases.

`acme/deps.edn` explicitly has no Seon dependency. `seon.dev.artifact` adds it
to the root build with a generated `:local/root`; `shadow-cljs.edn` defines the
consumer build inside Seon. A copied ACME directory therefore has neither the
Seon compiler classpath nor a command capable of producing its pod.

### The current CLJS artifact is a development loader, not a package

The current `out-acme/client/main.js` is only about 69 KB because it loads 1,037
generated files from an absolute/checkout-relative Shadow development runtime
directory. That directory is about 52 MB and is addressed as
`tmp/shadow/acme/builds/acme-client/dev/out/cljs-runtime`. The generated entry
also embeds the live Shadow server token, port, and development runtime id.

The artifact manifest hashes that runtime directory but records absolute paths
for client output and Shadow cache. It does not copy the closure into a
relocatable package. Shadow's own source describes Node builds as placing their
runtime files under `.shadow-cljs` while only `:output-to` is placed at the
declared output. Copying `main.js` is therefore neither complete nor portable.

A production build must emit one immutable relocatable closure (or a package
with an explicit relative module tree), disable Shadow devtools/watch
attachment, and prove self-host eval, dynamic symbol resolution, web serving,
and downstream preloads under the chosen optimization mode.

### “Packaged mode” still starts a compiler watcher

`seon.dev.config` calls a root packaged when `deps.edn` or `shadow-cljs.edn` is
absent, and `seon.dev.artifact/build!` then verifies an existing manifest
instead of compiling. But `seon.dev.process/specs` always creates three
processes and makes the pod depend on a Shadow watcher whose argv starts with
`clj -M:cljs watch …`. A real no-source package has neither the project
classpath nor the Shadow build definition, so current packaged `up` cannot
reach the otherwise-valid writer and pod processes.

Packaged operation owns only writer + pod. Development operation additionally
owns the watcher. They are two projections of one process graph, not separate
operators.

### Runtime code-as-data still expects checkout source

`seon.platform/artifact-path` deliberately routes `out/bootstrap`, `src`,
`test`, `guest-cljs/src`, and `resources/public` through
`SEON_RUNTIME_ROOT`. The boot indexer reads source forms from those roots to
publish program facts; the web host reads static files there. That is a sound
runtime requirement, but today `SEON_RUNTIME_ROOT` means a Seon checkout.

The release must instead ship a bounded, generated runtime corpus containing
exactly the compiled public source needed for program-graph/context truth. It
must not ship the platform test tree or depend on arbitrary repository layout.
The corpus digest belongs in the compatibility manifest and is verified before
boot. The self-host bootstrap (currently about 16 MB) and static assets
(currently about 1.2 MB) are likewise first-class package contents.

### Consumer dependencies are not independently resolvable

Source compilation can add consumer Clojure dependencies through
`acme/deps.edn`, and Shadow can search `SEON_EXTRA_NPM`. Those are good seams,
but only the root project invokes them. There is no published Seon Maven/SDK
coordinate, no consumer build command, and no rule for reconciling a
consumer's dependency graph against the runtime ABI.

The current Node bundle also retains runtime `require(...)` calls. The root
`package.json` and 84 MB development `node_modules` tree are not a production
dependency package. A downstream release needs an exact production npm lock or
bundled dependency closure plus a declared consumer npm merge rule. It must
detect version conflicts before compilation rather than rely on `NODE_PATH`.

The maintained Git forks present an additional publishing constraint: a Maven
POM cannot make Seon's root Git overrides transitively authoritative for a
third-party SDK consumer. Release automation must publish immutable compatible
fork artifacts (or otherwise package their CLJS sources and declare their
identity) so a consumer never silently resolves upstream Datahike/Konserve.

### Config, operator, and flavor are checkout-specific

`config/acme.edn` includes `system.edn` by a checkout-relative path and contains
a deliberately mirrored testbed context tree. The release needs a shipped base
manifest with a stable include/import contract, while a product supplies only
its delta. ACME's testbed-only autocomplete loadout must not define the generic
consumer contract.

Artifact flavors are currently a closed `default`/`acme` map in
`seon.dev.config`. A third party cannot declare its own build id, source,
preload, output, package metadata, or product identity without editing Seon.
The public form is a validated downstream descriptor consumed by the same
operator/build code; ACME becomes one checked-in descriptor fixture.

The operator itself is Babashka source loaded from `script/`, with dependencies
from the root `bb.edn`. No operator executable/archive or minimal runtime
prerequisite manifest is published. A package should preserve the semantic
`up`, `down`, `restart`, `status`, `logs`, and scoped-reset commands without
requiring Clojure CLI or Shadow in production.

### Version, provenance, and licensing are incomplete

The development manifest records content digests but no Seon release, Git
revision, protocol/config/SDK ABI, Java/Node requirements, dependency lock,
or license inventory. `build.clj` computes a Git-count version for the ordinary
jar but the fixed writer uberjar name does not use it. `package.json` says ISC
while the repository root license is AGPL-3.0, an unresolved distribution
metadata conflict.

The release producer must establish one version authority and package the root
license, dependency notices/SBOM, and corresponding-source pointer/archive.
This audit does not give legal advice; it records that shipping an executable
without coherent license metadata is not an acceptable release gate.

## Producer outputs and consumer inputs

| Boundary | Seon producer output | Downstream input |
|---|---|---|
| Database | versioned standalone writer uberjar + digest | Java runtime, database path, explicit config |
| Pod runtime | immutable Node closure, bootstrap, source corpus, web assets, production npm closure | Node runtime and consumer package |
| Build SDK | public CLJS source/macros, compiler/build entry, dependency coordinates | consumer `deps.edn`, CLJS source/preload, npm manifest |
| Config | versioned base manifest/schema | product delta, route/block populations, policy |
| Brand | CSS tokens/assets contract | product name/tagline/theme/CSS/assets |
| Operations | packaged one-operator launcher and process manifest | cluster paths, ports, secrets, lifecycle commands |
| Compatibility | signed/hashed release manifest + SBOM/licenses | exact Seon pin and consumer overlay digest |

The writer jar alone is sufficient only for the database process. It cannot
replace the CLJS runtime/SDK because agents, eval, rendering, and the web UI
live in the Node pod.

## Ordered implementation carve-out

1. **Specify and test the release manifest.** Add release, source revision,
   protocol/config/SDK versions, runtime requirements, relative file inventory,
   all content digests, maintained fork identities, npm lock, and license/SBOM
   references. Reject absolute paths and unknown/missing package members.
2. **Publish maintained dependencies and the CLJS SDK.** Give downstream
   `deps.edn` a real immutable Seon coordinate. Prove the exact Datahike,
   Konserve, superv.async, and partial-cps implementations resolve without a
   sibling checkout or root overrides.
3. **Create the production pod artifact.** Build a relocatable, devtools-free
   Node closure plus bootstrap, bounded source corpus, static assets, and exact
   production npm dependencies. Run it from a temporary directory with network
   denied and the source checkout hidden.
4. **Make packaged process reconciliation real.** Project watcher + writer +
   pod in development and writer + pod in production from the same process
   data. Package the semantic operator so production needs neither Clojure CLI
   nor Shadow.
5. **Replace hard-coded flavors with a downstream descriptor.** Let ACME own
   its project id, source/preload, config delta, npm/deps inputs, brand assets,
   and cluster defaults while Seon owns validation and build sequencing.
6. **Add one source-repository release command.** A clean Seon checkout runs
   one command that builds/tests both versioned artifacts, assembles SDK and
   runtime archives, writes hashes/SBOM/licenses, and publishes an atomic
   release directory. Repeating it from the same source/locks is content
   reproducible.
7. **Add one no-source ACME proof.** In a clean temporary checkout containing
   only ACME plus released artifacts, run the consumer build command and normal
   lifecycle commands; create an agent, exercise consumer source/config/brand,
   run CLJ and CLJS MCP probes, restart, and read the same database back. The
   test fails if any file is opened beneath the Seon source checkout.
8. **Document upgrades.** A consumer changes one Seon pin, rebuilds, receives a
   compatibility failure before mutation when protocols differ, and has a
   documented database backup/upgrade/rollback path.

## Acceptance matrix

- Build and run with the Seon checkout renamed or inaccessible.
- No absolute producer path appears in any package file or manifest.
- Production starts no Shadow server or compiler watcher.
- Default and independently packaged ACME can run simultaneously and remain
  independently MCP-addressable.
- Both report identical pinned custom dependency identities for one release.
- Consumer-only CLJS, Clojure, npm, config, routes, renderers, and CSS compile
  or load through documented inputs.
- Agent program facts contain the shipped Seon corpus and consumer corpus,
  without platform tests or missing-source stubs.
- One-command source release and one-command downstream build are reproducible
  from clean dependency caches.
- License, notice/SBOM, source revision, and all artifact hashes travel with
  the release.

## Source map

- `build.clj` — writer uberjar target and incomplete version naming.
- `deps.edn` — writer/CLJS bases and maintained fork identities.
- `bin/acme`, `acme/deps.edn`, `config/acme.edn` — current checkout-rooted
  consumer composition.
- `script/seon/dev/artifact.clj` — source/package branch and version-2 manifest.
- `script/seon/dev/config.clj` — hard-coded flavors and source-checkout test.
- `script/seon/dev/process.clj` — unconditional watcher process.
- `shadow-cljs.edn` — checkout-owned ACME build and development Node target.
- `src/seon/platform.cljs`, `src/seon/client.cljs` — runtime artifact/source
  corpus reads.
- `src/seon/web/brand.cljs` — downstream brand data/CSS seam.
- `reference-code/shadow-cljs/src/main/shadow/cljs/build_report.clj` and
  `reference-code/shadow-cljs/src/main/shadow/build/node.clj` — Node output
  layout behavior.
