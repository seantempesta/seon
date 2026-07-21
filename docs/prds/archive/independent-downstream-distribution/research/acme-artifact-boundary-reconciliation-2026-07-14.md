---
type: research
status: completed
tags: [research, orchestrator, cljs, flow]
---

# ACME artifact-boundary reconciliation — 2026-07-14

## Decision

ACME is a sound simultaneous **source-checkout development target**, but it is
not yet an independent downstream distribution. The current checkout proves
separate databases, processes, Shadow builds, client outputs, config, source,
branding, and cluster-qualified MCP routing. It also now proves one canonical
writer uberjar identity across default and ACME. A copied ACME repository still
cannot build or operate without the Seon checkout because every remaining
compiler, runtime, operator, source-corpus, asset, dependency-override, and MCP
coordinate is producer-owned and unpublished.

The implementation should proceed in parallel where artifacts have independent
owners, but integration remains ordered:

1. freeze compatibility and dependency identities;
2. build the SDK/dependency, runtime, and operator/descriptor lanes in
   parallel;
3. assemble one release only after those manifests agree;
4. prove a clean no-source downstream development build;
5. prove the devtools-free packaged runtime separately; and
6. run upgrade/restart/read-back only after lifecycle recovery contracts
   graduate.

This report reconciles the earlier
[[independent-acme-distribution-audit-2026-07-14]] with current head
`bf8cf3b5`. It did not modify runtime source or live processes.

## Dependency ledger

The release contract must name these exact inputs rather than inherit them from
the producer checkout.

| Dependency or mechanism | Selected identity | Grounded source and current use |
|---|---|---|
| Clojure | `1.12.0` application pin | root `deps.edn`; writer and build bases |
| Clojure CLI | host `1.12.5.1654` | `clojure -Sdescribe`; source is not mirrored under `reference-code/` yet |
| ClojureScript | `1.12.145` | root `:cljs`; `reference-code/clojurescript` |
| Shadow CLJS | `3.4.10`, source commit `d3c04691…` | root `:cljs`; the exact release commit remains in `reference-code/shadow-cljs` history |
| tools.build | `0.10.5`, commit `2a21b7ac…` | root `:build`; exact tag inspected in an ignored audit checkout because `reference-code/tools.build` is absent |
| Babashka | host `1.12.212`, commit `12872c10…` | `bin/seon`, `bin/mcp-server-cljs`, `bb.edn`; exact core source is not mirrored under `reference-code/` |
| Datahike | `6f90b339…` | root `:writer` and `:cljs`; exact `reference-code/datahike` head |
| Konserve | `df6818d4…`, resource version `0.9.356-seon.1` | root `:writer` and `:cljs`; exact `reference-code/konserve` head |
| superv.async | `3e6ed755…` | root `:cljs` override; exact source inspected in an ignored audit checkout, not yet mirrored |
| partial-cps | `1e119b03…` | root `:cljs` override; exact source inspected in an ignored audit checkout, not yet mirrored |
| Node / npm | host Node `26.4.0`, npm `11.17.0` | root `package-lock.json`; runtime requirements are not yet release metadata |
| Java | JDK `26.0.1` selected by the operator | `seon.dev.config/select-java-home`; writer launch flags live in `seon.dev.process/specs` |
| Operator | `seon.dev.*` through `bin/seon` | `script/seon/dev/`; `reference-code/babashka-process` at `16a84e0a…` is useful but is not an exact declaration of Babashka's bundled process version |

The missing maintained mirrors are a planning gate, not a reason to guess.
Before implementation, add exact first-party sources for Clojure CLI,
tools.build, Babashka, superv.async, and partial-cps under `reference-code/`, or
record a deliberate alternative source authority in this PRD.

## What source inspection establishes

### tools.build makes the writer boundary real

At `0.10.5`, `create-basis` resolves project plus aliases into one runtime
basis; `compile-clj` compiles against that basis; and `uber` pulls every
transitive jar from the supplied basis. `build.clj/writer-uber` correctly uses
the root `:writer` basis for AOT and uber assembly. That is why the 48 MB writer
jar can run without a Seon source checkout and why its maintained dependency
identity belongs in the release manifest rather than in downstream `deps.edn`.

The ordinary `jar` target is not the downstream SDK. It writes a POM from the
broad root basis and copies source/resources, but no release command installs
or publishes it, no consumer ABI is selected, and Git dependency overrides
cannot become authoritative transitive Maven coordinates merely because they
appear in the producer's basis.

### Shadow compile output is not a relocatable package

Shadow `3.4.10`'s `shadow.build.node/flush-unoptimized` writes an entry script
whose `SHADOW_IMPORT_PATH` points relatively from `:output-to` into the
generated `cljs-runtime` tree. `build_report.clj` explicitly notes that Node
build files live under the Shadow cache while only `:output-to` lands in the
declared output directory. Current `compile` output is therefore complete only
as the pair of entry script plus flavor-specific Shadow cache.

The current measurements remain decisive:

- default entry: about 68 KB plus a 70 MB development runtime tree;
- ACME entry: about 69 KB plus a 52 MB development runtime tree;
- self-host bootstrap: about 16 MB; and
- static public resources: about 1.2 MB.

A Shadow `release` can flush an optimized Node module at `:output-to`, but that
does not by itself prove Seon's self-host bootstrap, runtime `require(...)`
closure, source indexing, dynamic lookup, or downstream preload ABI. Those are
the production-runtime lane's executable acceptance tests.

### Maintained forks are one compatibility set

The resolved root `:cljs` tree selects Datahike `6f90b339…`, Konserve
`df6818d4…`, superv.async `3e6ed755…`, and partial-cps `1e119b03…`. Datahike's
exact source declares `:deps/prep-lib` for its generated classes and uses the
same partial-cps revision under its CLJS alias. Konserve's exact source carries
the maintained Maven version resource. superv.async's selected commit lazily
starts its stale-pending watchdog, while partial-cps's selected commit owns the
core.async/CPS adapter behavior required by current CLJS.

Default and ACME resolve those identities today only because
`seon.dev.artifact/cljs-command` injects `acme/` as `:local/root` into the
root `:cljs` project. `acme/deps.edn` contains no Seon coordinate and therefore
proves no independent resolution.

## Current target parity

Read-only `status --edn` reported both targets ready:

| Evidence | Default | ACME |
|---|---|---|
| web | `127.0.0.1:7890` | `127.0.0.1:7994` |
| writer REPL | dynamic `62081` | dynamic `62135` |
| Shadow nREPL | dynamic `62075` | dynamic `62131` |
| writer digest | `80054020…` | `80054020…` |
| client build | `client` | `acme-client` |
| client digest | `e3078630…` | `24cae9a7…` |
| database | `data/clusters/default/db` | `data/clusters/acme/db` |

Commit `bf8cf3b5` closes the writer-build collision: one checkout-global lock
serializes fixed producer outputs, and a source/dependency/CLI/JDK fingerprint
admits reuse only after the canonical jar's content digest verifies. This is
good development evidence and should become a release cache primitive.

The target data remains intentionally different where a consumer owns it:
ACME supplies `acme.pod`, consumer source, config, routes/renders, dashboard,
brand CSS, process/database coordinates, and its client build. The hard-coded
`default`/`acme` flavor enum is still not a public downstream descriptor.

The unified MCP server discovers both flavor-owned Shadow port files and both
writer port files. Cluster-qualified `default/root` and `acme/root` routing is
implemented and tested. That mechanism is still checkout-owned:
`.mcp.json`, `.codex/config.toml`, `bin/mcp-server-cljs`, `bb.edn`, and
`seon.dev.mcp` all resolve through the producer root.

## Newly verified blocker: the bootstrap is mutable across live targets

The checkout-global build lock prevents concurrent corruption, but it does not
make all published inputs immutable. Both flavors still compile to the same
`out/bootstrap` directory. The current default manifest records bootstrap
digest `58401112…`; the later ACME manifest records `f0a30715…`. Files under
`out/bootstrap` were last written at `22:55:34`, after the default manifest at
`22:53:36` and immediately before the ACME manifest at `22:55:37`.

The default process remains reported ready even though
`seon.eval/init-bootstrap!` resolves `out/bootstrap` dynamically through
`seon.platform/artifact-path`. A later target build can therefore replace a
running target's manifest-bound self-host input after admission. Serialization
fixed the race but not post-publication immutability. The durable defect is
[[../../../seon/issues/shared-bootstrap-output-mutates-running-artifact]].

The release solution is not another ad hoc copy. Bootstrap/source/assets must
be immutable package members. In development, either one verified canonical
bootstrap is built once and reused byte-for-byte by all compatible flavors, or
each flavor receives a distinct manifest-bound output root. A running process
must resolve the exact member named by its admitted manifest.

## MCP has two distinct product requirements

The current roadmap wording conflates downstream development and packaged
production:

- Development MCP works because the source checkout runs Shadow nREPL and the
  writer's development `io-prepl`.
- A devtools-free package intentionally starts no Shadow server, and the
  production writer entrypoint intentionally omits its REPL port.

Therefore “packaged ACME supports CLJ and CLJS MCP eval” is not implied by the
current mechanisms. The no-source **development SDK** can and should support
both eval tools from its own checkout. The immutable production package should
exclude compiler/debug servers unless an explicit secured diagnostic contract
is designed. If production MCP eval is a product requirement, migrate both
development and package evaluation to one intentional runtime protocol rather
than smuggling Shadow and `io-prepl` into production.

## Remaining source-path coupling

- `bin/acme` computes the producer root beside itself and execs its `bin/seon`.
- `acme/deps.edn` has no Seon release coordinate.
- `shadow-cljs.edn` owns the hard-coded `acme-client` build.
- `seon.dev.config` accepts only the closed default/ACME flavor map.
- `seon.dev.process/specs` always emits watcher + writer + pod, even when
  `:source-checkout?` is false.
- the pod reads bootstrap, source corpus, and web assets from checkout-shaped
  paths beneath `SEON_RUNTIME_ROOT`;
- `config/acme.edn` includes checkout-local `system.edn` and duplicates a
  testbed-specific block tree;
- runtime Node `require(...)` calls depend on the root npm closure and
  `NODE_PATH` merge behavior;
- the MCP server and its client registrations execute from the producer root;
- `acme/gym` and `bin/acme gym-diffusion` are retired Inspect migration
  residue, not members of a generic downstream SDK; and
- `acme/README.md` still says port `7980`, while the current wrapper and live
  target use `7994`.

## Parallel implementation lanes

These three units can proceed concurrently once the compatibility-manifest
shape and dependency ledger are accepted.

### Lane A — SDK and maintained dependencies

Own the public CLJS source/macro boundary, immutable Seon SDK coordinate,
published maintained fork coordinates, consumer `deps.edn` merge rules, and a
cold-cache resolution test. It must not design runtime packaging.

Success: a clean consumer project compiles one ACME namespace against pinned
SDK/dependency artifacts with the producer checkout inaccessible, and reports
the expected four custom SHAs.

### Lane B — relocatable runtime closure

Own the production Node build, bootstrap, bounded source corpus, static assets,
production npm closure, relative package inventory, and network-denied launch
probe. It must not invent another operator.

Success: the pod starts from an extracted directory, publishes program facts
for shipped core plus consumer source, serves the web UI, evaluates ordinary
agent code, and opens no producer path or Shadow connection.

### Lane C — operator projection and downstream descriptor

Own one validated descriptor and one process graph whose development
projection is watcher + writer + pod and packaged projection is writer + pod.
Package the semantic `up`, `down`, `restart`, `status`, `logs`, and scoped reset
surface. It must not create a package-only supervisor.

Success: an arbitrary fixture name/build/source/config/brand/cluster can be
declared without editing Seon code; invalid coordinates fail before launch.

## Sequential integration gates

1. **Compatibility gate.** The three lanes emit data conforming to one versioned
   manifest schema with no absolute paths.
2. **Producer release gate.** One command tests and assembles writer, SDK,
   runtime, operator, hashes, SBOM/notices, license, and source revision into an
   atomic immutable directory.
3. **No-source development gate.** A clean ACME checkout pins that directory,
   compiles consumer source/dependencies, starts its development graph, and
   supports cluster-qualified CLJ/CLJS MCP evaluation.
4. **Packaged-runtime gate.** The extracted production package starts only
   writer + pod with network and producer checkout access denied; consumer
   config/brand/routes/renders work and no compiler/debug service starts.
5. **Lifecycle gate.** Restart/read-back, incompatibility rejection before
   database mutation, backup, upgrade, rollback, and simultaneous default/ACME
   evidence wait for database-lifecycle recovery semantics.

## Graduation evidence

- Repeat producer and consumer builds from the same source and locks yield the
  same content identities.
- Every package path is relative and every member verifies against the admitted
  manifest before database mutation.
- Default and no-source ACME run simultaneously with equal writer and custom
  dependency identities and distinct database/process/client identities.
- No-source development supports both MCP runtimes from the downstream root;
  production starts no Shadow, Clojure CLI, mutable resolver, or development
  REPL.
- Consumer-only source, Maven/Git/npm dependencies, config delta, routes,
  renderers, and CSS pass through documented public inputs.
- The program graph contains the shipped bounded Seon corpus and the consumer
  corpus, never platform tests or missing-source placeholders.
- A mixed writer/runtime/SDK/config set rejects before a transaction.
- License, notice/SBOM, source revision, maintained dependency identities, npm
  lock, and all artifact digests travel together.
