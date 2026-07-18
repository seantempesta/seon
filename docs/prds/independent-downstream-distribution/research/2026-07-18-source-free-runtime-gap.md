---
type: research
status: completed
tags: [research, component, cljs, flow, pod]
---

# Source-free production runtime gap

## Decision

The shortest honest path to a production Seon without a Shadow watcher is a
relocatable release directory, not a copy of the current development runtime
root and not yet a Bun standalone executable.

The release directory contains one optimized, devtools-free pod entry, one
optimized execution-child entry, the self-host bootstrap, the bounded program
source file, web assets, the writer uberjar, the selected production npm
closure, and a release-built patched Bun executable. One compatibility
manifest names those members by relative path and binds their digests and
versions. The existing operator reads that manifest and projects exactly
writer plus pod. Development continues to project watcher plus writer plus pod
from the same process graph.

This is smaller and safer than introducing another supervisor, and it removes
the roughly 2 GiB development-only Shadow JVM from an installed Seon. It does
not by itself reduce the already measured fixed writer plus pod footprint;
that remains a separate memory owner. Bun `--compile --bytecode` is a promising
subsequent packaging and startup optimization, but it changes executable
identity and filesystem assumptions used by execution-child admission. It
should be measured after the relocatable directory passes semantic parity, not
made a prerequisite for eliminating Shadow.

## Dependency ledger

Audit head: Seon `474a32092469b3313434f84abe9c27931c83eab9` on 2026-07-18.

| Dependency or mechanism | Selected identity | Grounded source and Seon use |
|---|---|---|
| Database protocol | `seon.db.protocol/current-version` 10 | `src/seon/db/protocol.cljc`; the writer and both Bun entries must bind this same value |
| Development artifact manifest | `seon.dev.artifact/current-version` 9 | `script/seon/dev/artifact.clj`; useful admission machinery, but its paths and Shadow development closure are not a release contract |
| Writer | Clojure 1.12.0, Datahike `4c55791be1fb8bb8d9332f21c576f5c20b85b760`, Konserve `b5c99bc02a7175652a610324215288b78551801f`, Proximum `9846d3e79e1aee48474bc876d3d563d7137209c6` | `deps.edn`, `build.clj`, `reference-code/datahike`, `reference-code/konserve`, and `reference-code/proximum`; `target/seon-database-server-standalone.jar` is already the independent writer artifact |
| ClojureScript | selected Maven 1.12.145 | `deps.edn`; the existing `reference-code/clojurescript` checkout is 1.12.41 at `946d75f3483c0c8e784e6668bff2c71a25619a77`, so exact selected compiler source is still absent |
| Shadow CLJS | `4e72595f57618f5c43388ad13d5136cd3bede566` | `reference-code/shadow-cljs`; `shadow.build.node/flush-unoptimized` explains the development entry plus cache-owned runtime tree, while a release build emits the optimized entry |
| superv.async | `3e6ed755f83634c9e9bbb58707f9446420d32ce9` | exact `reference-code/superv.async` and `deps.edn` override |
| partial-cps | `1e119b03ea908ad925b98f9ba0a26371c65441e3` | exact `reference-code/partial-cps` and `deps.edn` override |
| tools.build | Maven 0.10.5 | selected by `deps.edn` and used by `build.clj`; exact source is not mirrored under `reference-code/` |
| Bun shipped by the current manifest | release 1.3.14, revision `0d9b296af33f2b851fcbf4df3e9ec89751734ba4` | `tmp/seon-operator/artifact.edn`; this is not the patched runtime |
| Patched Bun | `d8ecf098572e2b8265b23e40c04efb4067e516cc`, reporting 1.4.0-debug in the locally built debug executable | exact `reference-code/bun`; contains the live child resource-usage addition. A release binary from this revision is still required for the package |
| Bun packaging | `bun build --compile`, `--bytecode`, embedded files, and `Bun.isStandaloneExecutable` | `reference-code/bun/docs/bundler/executables.mdx` and `bytecode.mdx`; compiled executables embed Bun and can move parse work to build time, but bytecode is tied to the Bun/JSC version |
| npm closure | `bun.lock` plus `package.json` | current package has runtime and development dependencies, but no release command derives and validates the production-only closure; `package.json` still declares ISC while the repository `LICENSE` is AGPL-3.0 |

The exact-source policy is not fully satisfied: selected ClojureScript,
Clojure, and tools.build source remain absent or mismatched. That blocks a
final release freeze, but it does not change the runtime seam identified by
the current source.

## What is already built

### The database authority is independently executable

`build.clj/writer-uber` produces the 29 MiB
`target/seon-database-server-standalone.jar`. It uses the one `:writer` basis,
the stable Java entry point, and the same `seon.db.server` implementation as
development. The current normalized writer digest is
`124d663e30982910b99eddba241756ca4ea9812ca47747b332e7d01a9e355e97`.
The operator already launches it with the selected Java, G1, and one measured
512 MiB maximum heap.

### The immutable runtime members mostly exist

Artifact manifest version 9 already binds:

- a 16 MiB self-host bootstrap;
- the 3.1 MiB `program-sources.edn` file loaded and digest-checked by
  `seon.client`;
- the execution entry and its imported JavaScript closure;
- web CSS identity;
- exact Bun executable bytes, version, and revision; and
- the writer, maintained dependencies, and application digest.

`seon.platform/artifact-path`, `SEON_RUNTIME_ROOT`,
`SEON_PROGRAM_SOURCE_PATH`, and `SEON_PROGRAM_SOURCE_DIGEST` are already the
right runtime seams. The pod loads program source as data rather than scanning
the checkout. The web host already resolves static roots through the admitted
runtime root. The process owner already verifies the Bun executable and passes
the exact execution artifact through the launch descriptor.

### Packaged manifest reading was started

`seon.dev.artifact/build!` already distinguishes a source checkout from a
package. With `:seon.dev.config/source-checkout? false`, it reads an existing
manifest and performs no build. This is the correct direction: production must
verify immutable shipped bytes and never invoke Shadow or mutable dependency
resolution.

### Bun provides an optional stronger terminal artifact

The vendored Bun source supports bundling a Bun-targeted entry, compiling it
into a standalone executable, embedding ordinary assets, embedding source
maps, and embedding version-specific bytecode. Its own production guidance
says compiled executables avoid runtime file resolution, parsing, and
transpilation costs and may reduce startup memory. This can eventually fold
the patched Bun binary and a bundled Seon entry into one file per pod/child
entry.

That is not the first cut because Seon currently hashes the execution file
named by `process.argv[1]`, the parent supplies that digest in child startup,
and bootstrap/program-source/web assets are deliberately ordinary package
members. `Bun.isStandaloneExecutable` and embedded-file APIs provide a clean
future adaptation, but parity must establish whether the gain is material.

## Exact current blockers

### The client is a Shadow development loader

The current 61 KiB `out/client/main.js` sets `SHADOW_IMPORT_PATH` to
`.shadow-cljs/builds/client/dev/out/cljs-runtime` and includes an absolute
fallback to this producer checkout. The runtime root does not copy that client
closure. Removing the watcher while keeping these bytes only works as long as
the producer's mutable Shadow cache remains accessible; that is not a package.

The execution entry has the same development shape, although artifact version
9 at least copies its complete `dev/out/cljs-runtime` closure into the runtime
root. A production package should build both entries through the same isolated
release operation so neither has a cache-owned import tree.

### The runtime root is not self-contained

`publish-runtime-root!` copies bootstrap, execution bytes, execution runtime,
and program source, then creates absolute symlinks for `src`, `test`, and
`resources`. The current admitted root therefore still reaches into the
producer checkout. Production needs no raw `src` or `test` tree because the
bounded program source is already emitted. It does need real copied web assets
and any explicitly selected non-code resources.

### The process graph always includes the watcher

`seon.dev.process/target-processes` is always `[watcher writer pod]`.
`specs` always creates a watcher spec. A locally owned pod depends on both
watcher and writer, and an externally owned pod requires watcher and writer as
external dependencies. Readiness and status consequently require a Shadow
process even when `source-checkout?` is false.

`seon.dev.cli/reconcile-development!` also always calls
`prepare-watcher!` while building and always admits the resulting watcher.
Although `artifact/build!` can read a packaged manifest, the surrounding
operator never projects packaged operation.

### Package paths and identity are not relocatable

Configuration still derives writer, client, execution, and manifest paths
from the assumed checkout layout. Manifest version 9 stores absolute Bun,
Shadow cache, client, execution, and runtime-root paths. The package therefore
cannot be extracted elsewhere without rewriting an identity-bearing value.

A release compatibility manifest must store only normalized relative member
paths and content digests. Configuration resolves those members against the
canonical package root after validation; resolved host paths are process input,
not release identity.

### There is no production build or inventory

There is no command that builds client and execution entries with devtools
absent in an isolated Shadow cache/output directory, copies the bounded
runtime members, selects the patched release Bun binary, derives production
npm dependencies, writes notices/SBOM/license data, and validates the result.
There is also no pure inventory test rejecting absolute paths, path escape,
symlinks, missing/changed members, mixed protocol/runtime identities, or
undeclared npm dependencies.

## Required release manifest and members

The release manifest should be a new closed compatibility contract rather than
an extension of the development watcher's mutable paths. It should contain
ordinary namespaced data in these groups:

- release version and source revision;
- database protocol version, config manifest version, and public SDK version;
- minimum Java and operating-system/architecture Bun target;
- relative paths plus SHA-256 digests for writer jar, Bun executable, pod
  entry, execution entry, bootstrap tree, program source, and web assets;
- Bun version and full revision, including the patched revision;
- exact maintained dependency identities and normalized writer digest;
- npm lock digest and exact production package inventory;
- root license, notices, SBOM, and source-offer/revision members; and
- one application digest derived from the semantic values above, never from
  producer absolute paths or publication time.

The smallest complete extracted tree is:

```text
bin/seon
lib/seon-operator.jar-or-babashka-source
runtime/bun
runtime/pod.js
runtime/execution.js
runtime/bootstrap/**
runtime/program-sources.edn
runtime/public/**
runtime/node_modules/**        # only if the optimized entries leave externals
writer/seon-database-server-standalone.jar
release.edn
release.sha256
LICENSE
NOTICE
sbom.*

```

The exact operator carrier can remain Babashka initially if the release ships
one supported Babashka requirement or executable. It must not require Clojure
CLI. A later native operator binary is independent of the process graph and is
not required to remove Shadow.

## Smallest implementation sequence

### 1. Prove the production CLJS entries in isolated outputs

Derive a production build from the existing `client` and `execution` build
definitions, with devtools and preloads selected explicitly, a package-staging
output path, and a package-staging Shadow cache. Do not add hand-copied
`client-v2` or `execution-v2` definitions. Build both with `release`, then run
the narrowest semantic probe:

- pod reaches readiness against the real writer;
- root page and gzip feed return;
- program source publishes the same required core namespaces;
- one execution child becomes ready, evaluates a definition and a later call,
  and retires normally; and
- neither entry opens a Shadow connection or reads a Shadow cache path.

This is the earliest unsettled implementation boundary. Advanced Closure
renaming, dynamic lookup, self-host bootstrap loading, Node/Bun externs, and
execution-file hashing are the risky facts; one live production-entry probe
settles all of them before operator work.

If Shadow's optimized entry cannot preserve one of those semantics, first try
the least aggressive relocatable Bun-target bundle from the same compiled
closure. Shipping the development loader or retaining Shadow is not an
acceptable fallback.

### 2. Replace runtime-root symlinks with an explicit release inventory

Keep development `publish-runtime-root!` unchanged for hot reload. Add one pure
release-package owner that copies only the production entries, bootstrap,
program source, selected public assets, writer jar, Bun, and required npm
members. Validate relative containment, reject every symlink, and hash file
bytes in deterministic order. Correct the package license metadata in the same
cut.

### 3. Make configuration resolve one validated release

When `source-checkout?` is false, load `release.edn` from the package root,
validate it before database mutation, resolve its relative members to absolute
host paths, and derive the existing launch descriptor from those paths. Do not
copy the immutable manifest into a mutable process directory and do not make
environment variables another package authority.

### 4. Project packaged operation from the one process graph

Derive target process IDs from the configuration:

- development: watcher, writer, pod;
- package owning its writer: writer, pod; and
- package using an external writer authority: pod plus the existing writer
  external dependency.

The pod depends on the writer, never on a watcher, in packaged operation.
Status omits Shadow endpoints when no watcher is selected. Up, down, restart,
logs, recovery, reset, ownership, containment, and readiness continue through
the existing functions over the selected process map. Rename
`reconcile-development!` to describe the now-general application transition
rather than adding a parallel package reconciler.

### 5. Add one release command and source-free acceptance fixture

The producer command performs tests, builds the writer and two production
entries, builds the patched Bun release binary, copies the closure, writes the
compatibility manifest/hash/SBOM/notices/license members, and runs the pure
inventory validator. Then extract it into a directory outside the Seon tree
and run the existing operator there with the producer checkout unreadable.

ACME consumes that release only after the core package is green. Its separate
development SDK/compiler work can follow without delaying the production
writer-plus-pod memory measurement.

### 6. Measure Bun standalone compilation only after parity

Build `pod.js` and `execution.js` with the packaged Bun using
`--compile --minify --sourcemap --bytecode`. Adapt execution identity using
`Bun.isStandaloneExecutable` only if required. Compare:

- cold start and readiness;
- physical/private footprint after collection and after one agent;
- execution-child startup and peak footprint;
- file count and installed bytes; and
- error stack/source-map quality.

Select standalone executables only when they preserve exact behavior and show
a significant startup, memory, or operational win. Bytecode and executable
digests must remain bound to the exact Bun revision.

## Runnable source-free falsifier

The production cut is not complete until the following shape can run from an
extracted release directory. `bin/seon package` is the intended producer
command name here; implementation may choose the existing CLI's most idiomatic
subcommand, but there must be one command rather than a handwritten assembly
script.

```bash
bin/seon package --output tmp/source-free-seon

cd tmp/source-free-seon
SEON_PROC_DIR="$PWD/state/processes" \
SEON_LOG_DIR="$PWD/state/logs" \
SEON_CLUSTER_DIR="$PWD/state/cluster" \
./bin/seon up

./bin/seon status --edn
curl --fail --silent http://127.0.0.1:7890/ready
curl --compressed --fail --silent --max-time 2 \
  http://127.0.0.1:7890/agent/root/feed

```

The status value must contain exactly live, ready writer and pod processes,
with no watcher or Shadow endpoint. Process arguments and open files must
contain no producer checkout path, `clj`, `shadow.cljs.devtools`, or Shadow
cache path. On macOS, measure the two workload PIDs rather than their
containment owners:

```bash
ps -o pid=,rss=,command= -p WRITER_PID,POD_PID
footprint -p WRITER_PID --swapped
footprint -p POD_PID --swapped

```

The release must then drive one real agent, observe a separate execution child,
wait for ordinary idle retirement, restart writer plus pod, and read the same
database facts back. Repeat after making the producer checkout unreadable or
moving it away. Any Shadow JVM, absolute producer read, missing web asset,
failed dynamic eval, changed database, or watcher-shaped status falsifies the
package.

## Performance implications

- The immediate installed-runtime win is deterministic: the development
  Shadow watcher measured about 2.2 GiB and disappears entirely. It was never
  part of the intended production budget.
- The current fixed production-relevant writer plus pod was about 864 MiB after
  collection before the writer heap cap work; the 512 MiB heap cap reduces
  startup pressure but does not make packaging itself a fixed-memory cure.
- Optimized entries eliminate the mutable 50--70 MiB Shadow development
  runtime trees and their runtime file-resolution work. The effect on JSC
  retained memory must be measured; package byte size is not a proxy.
- A release directory permits shared mapped Bun and JavaScript pages across
  execution children. Separate processes still allocate their own JSC heaps,
  which is the correct isolation cost.
- Bun compiled executables may improve cold start and reduce parsing memory,
  but producing separate large pod and execution binaries may duplicate
  installed bytes and mapped runtime images. Measure them against one shared
  patched Bun plus two optimized JavaScript entries before selecting them.

## Exact next implementation boundary

Build the existing `client` and `execution` programs as isolated,
devtools-free release entries and launch them against the current standalone
writer without a Shadow server. The proof is pod readiness, root feed, one
persistent two-step CLJS eval in a separate child, normal child retirement, and
zero reads from `.shadow-cljs` or the producer source tree.

Only after that proof should the process graph drop the watcher for packaged
configuration. Otherwise operator work would merely make an unpackageable
development loader easier to start.
