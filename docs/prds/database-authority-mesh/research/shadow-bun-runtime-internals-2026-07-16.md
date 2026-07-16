---
type: research
status: active
tags: [research, prd, cljs, flow]
---

# Shadow ClojureScript and Bun runtime internals — 2026-07-16

## Decision

Keep Shadow's `:node-script` and `:node-test` targets, but execute their emitted
artifacts with Bun. These target names describe the CommonJS and server-host
shape that Shadow generates; they do not require the Node executable. Bun
already runs that shape, including `require`, `global`, `__dirname`, `Buffer`,
`process`, `node:*` modules, `ws`, `source-map-support`, and `NODE_PATH`.

Do not fork Shadow or introduce a Bun-specific target before a measured
generated-code limitation appears. A target fork would have to reproduce
Shadow's bootstrap, test discovery, source-map, devtools WebSocket, hot reload,
and release behavior without providing Bun-native sockets or `Bun.serve`.
Those native gains live at Seon's host boundaries after the compiled
ClojureScript has started, not in Shadow's module emitter.

The smallest no-Node cut is consequently an execution-policy change:

- make Bun the default JavaScript runtime in the existing process descriptor;
- make the maintained test and changed-test runners use the same selected
  runtime;
- execute the worker validator and eval oracle with Bun where Seon owns their
  launch command; and
- update the convenience package script and operator diagnostics so they no
  longer imply that Node is required.

Keep the JVM Shadow compiler and watcher. Bun replaces the JavaScript runtime,
not the Clojure compiler.

## Dependency ledger

- Seon checkout observed at `7319ad1847d0b30d25a545402025cc1e7430d9c3`:
  `shadow-cljs.edn`, `deps.edn`, `package.json`, `bin/test-cljs`,
  `script/seon/dev/process.clj`, `script/seon/dev/changed_test.clj`, and
  `script/seon/dev/config.clj`.
- Shadow CLJS `4e72595f57618f5c43388ad13d5136cd3bede566`:
  `reference-code/shadow-cljs/src/main/shadow/build/targets/node_script.clj`,
  `targets/node_test.clj`, `shadow/build/node.clj`, `shadow/build/npm.clj`, and
  `shadow/cljs/devtools/client/node.cljs`.
- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`, locally executed as Bun
  `1.3.14`:
  `reference-code/bun/docs/index.mdx`,
  `docs/runtime/module-resolution.mdx`, `src/resolver/resolver.rs`, and
  `test/js/bun/resolve/resolve.test.ts`.
- ClojureScript `1.12.145` is the selected compiler. Shadow is selected from
  the vendored fork through the `:cljs` alias; the npm `shadow-cljs` package is
  only the CLI-side package dependency and does not define the runtime target.
- Related native boundaries are grounded separately in
  [[bun-serve-datastar-internals-2026-07-16]] and
  [[transit-bun-delivery-internals-2026-07-16]].

## What Shadow actually emits

The `:node-script` target configures Shadow's existing Node-shaped output,
sets `cljs.core/*target*` to `"nodejs"`, and chooses the same emitter for dev
and release (`node_script.clj:32-65`). Dev uses `flush-unoptimized`; release
uses `flush-optimized`. The target itself never invokes the JavaScript runtime.

The unoptimized emitter (`shadow/build/node.clj:101-200`) writes one small
entry file that:

1. derives the compiled namespace directory from `__dirname`;
2. installs globals used by Closure and Shadow;
3. optionally loads `source-map-support`;
4. defines `SHADOW_IMPORT`; and
5. synchronously `require`s the per-namespace JavaScript files.

The current `out/client/main.js` visibly has exactly that form. It begins with
`#!/usr/bin/env node`, but the shebang is ignored when the file is passed as an
argument to `bun`; it does not select Node. It then requires `path`, `vm`, and
`fs`, loads the compiled namespace graph, and calls Seon's main function.

The `:node-test` target is not a separate runtime architecture. It selects test
namespaces and a test main, then delegates emission to
`node-script/process` (`node_test.clj:15-63,86-102`). Its optional Shadow
`:autorun` helper hardcodes `node` in a `ProcessBuilder`, but Seon does not use
that helper: `bin/test-cljs` explicitly compiles and executes the artifact so it
can own selection, locking, timeout, evidence, and failure semantics. No Shadow
fork is needed to change that explicit execution.

The generated devtools client is also host-compatible rather than
Node-executable-specific. It requires the `ws` package, talks to the JVM
watcher over WebSocket, reloads changed namespace files through
`SHADOW_IMPORT`, and reports `process.version` only as descriptive host text.
Bun supplies the required module/global surface. The description may still say
`Node ...`; that is cosmetic Shadow metadata, not runtime selection.

## Direct compatibility proof

The checked-out artifact was exercised without rebuilding or changing source:

- `out/client/main.js` SHA-256
  `11fab0636eb05b818934d72818eba485a044de014c40d1a648fb49ac46b10154`;
- `out/test/test.js` SHA-256
  `c04f30e4f3f431e59ad62a7518efb3249bc72357b73835d714ebc234e059d6bc`;
- Bun `1.3.14`; Node `v26.4.0` for the comparison run.

Running `bun out/client/main.js` with an intentionally invalid launch
descriptor loaded the Shadow bootstrap and Seon namespace graph through
`seon.db.replica.js`, then failed at Seon's descriptor validation with
`SEON_LAUNCH_DESCRIPTOR is invalid.` This is a useful startup falsifier: Bun
passed CommonJS loading and reached first-party application boot. It is not a
full live-cluster or hot-reload proof.

A direct Bun probe resolved all currently critical generated-host features:

```text
{"runtime":"1.3.14","ws":"function","sourceMapSupport":"function",
 "fs":"function","nodeFs":"function","dirname":"string",
 "buffer":"function"}

```

The current Shadow test artifact also ran unchanged under Bun:

```text
bun out/test/test.js --test=seon.platform-test
Ran 2 tests containing 7 assertions.
0 failures, 0 errors.

```

The same artifact and selection were then run once under each runtime. These
are directional cold-process samples, not a benchmark distribution:

| Runtime | Wall | User CPU | Instructions | Cycles | Max RSS | Peak footprint |
|---|---:|---:|---:|---:|---:|---:|
| Bun 1.3.14 | 2.05 s | 3.12 s | 52.57 B | 14.04 B | 1,024.6 MB | 773.9 MB |
| Node 26.4.0 | 4.55 s | 5.42 s | 128.69 B | 23.66 B | 701.1 MB | 698.8 MB |

In this one sample Bun cut wall time by about 55%, user CPU by 42%, retired
instructions by 59%, and cycles by 41%. It raised maximum RSS by about 46% and
the macOS peak-footprint measure by about 11%. The result supports using Bun
for the full suite next; it does not establish steady-state pod memory or
suite-wide speed. Most of this test artifact's roughly 0.7–1.0 GB footprint is
the unusually broad compiled test graph, so runtime replacement does not remove
the need to reduce graph and fixture retention.

## Package-resolution constraints

Shadow has separate compile-time and runtime package resolution:

- `:js-package-dirs` controls the JVM compiler's npm service; and
- unresolved `require("package")` calls in a `:node-script` artifact are
  resolved by the selected JavaScript runtime.

Seon's downstream seam already exports `NODE_PATH=$SEON_EXTRA_NPM` for the
second case. Bun explicitly implements `NODE_PATH`, including the platform
delimiter and ordered multiple entries
(`docs/runtime/module-resolution.mdx:182-205`; resolver implementation at
`src/resolver/resolver.rs:2950-2967`). Therefore keep the existing environment
name and package roots. Renaming it to a Bun-specific setting would create a
parallel package-resolution mechanism.

Before graduation, prove a downstream package that exists only under
`SEON_EXTRA_NPM` compiles and loads in both dev and release. The local root
probe only proves ordinary `node_modules`, `ws`, and `source-map-support`.

Imports such as `"node:fs"`, `"node:path"`, and `"node:crypto"` are module
names, not proof that the Node executable remains. Bun's compatibility surface
supports them. Replace an import with a Bun-native API only when that removes
an adapter or improves a measured host boundary; do not mechanically rename
every module.

## Source maps and hot reload

Dev output retains Shadow's current source-map path. Each namespace file ends
in a `sourceMappingURL` and has a neighboring `.js.map`; the entry installs
`source-map-support`. Bun resolved that package in the direct probe. This is
enough to retain the mechanism, but not enough to claim mapped stack parity.

The graduation falsifier is to throw from a known `.cljs` line in a Bun pod and
in a Bun test artifact, then prove both stacks name the expected ClojureScript
file and line. Repeat after a hot reload because Shadow evaluates replacement
code through its devtools runtime rather than process startup.

Hot reload should retain the existing JVM watcher and Shadow WebSocket client.
The smallest live proof is:

1. start a normal operator-owned cluster with `SEON_JS_RUNTIME=bun`;
2. prove the pod advertises and MCP eval reaches the selected runtime;
3. edit one harmless watched return value;
4. observe one `:build-complete` notification and the new value without a pod
   restart; and
5. restore the value through a normal source edit and prove it reloads again.

This proof must precede making Bun the unconditional default. The artifact
startup and package probes make failure unlikely, but they do not exercise the
watcher's reconnect, `ws` events, runtime selection, or reload evaluation.

## Release and packaging constraints

Keep `:target :node-script` for release. Shadow's release path emits one
optimized CommonJS-shaped file and already carries Seon's Closure externs for
Node-compatible `fs` calls. Switching to `:esm` would reintroduce the known
`require`, `__dirname`, bootstrap-loader, and Closure extern work without a
measured runtime advantage.

The release gate is:

- build `client`, `bench-client`, and the downstream `acme-client` through
  their existing flavor-owned outputs;
- execute each optimized artifact with Bun from its intended runtime root;
- prove `NODE_PATH`-only downstream packages resolve;
- prove mapped errors remain actionable or explicitly ship the selected map
  policy; and
- prove the no-source artifact contains every Shadow runtime/bootstrap file it
  references and starts on a host with Bun but no Node executable.

Do not add `bun build` on top of Shadow output initially. A second bundling pass
would create another module resolver, source-map transform, artifact digest,
and debugging surface. Consider it only after the plain Shadow release is
correct and a measurement shows worthwhile cold-start or packaging gains.

## Smallest implementation cut

One runtime-selection function should own the executable name for every
first-party JavaScript artifact. The current partial seam is
`SEON_JS_RUNTIME`, read by `script/seon/dev/process.clj`; its default is still
`node`. Strengthen that one mechanism rather than adding `SEON_USE_BUN` or
per-script switches.

In one coherent cut:

1. default `SEON_JS_RUNTIME` to `bun` in the process owner and expose the
   selected executable in operator status/doctor evidence;
2. have `bin/test-cljs` use the same selected runtime instead of its literal
   `NODE_CMD=(node out/test/test.js)`;
3. have `script/seon/dev/changed_test.clj` construct the same runtime argv
   instead of `[`node` ...]`;
4. route owned worker-validator and worker-oracle launchers through the same
   value; their Shadow build targets remain unchanged;
5. change `package.json`'s convenience `client:run` command to Bun; and
6. update comments/report field names such as `node-seconds` only where they
   are user-visible schema, preserving report compatibility only if an active
   consumer requires it.

Do not modify `shadow-cljs.edn` targets for this cut. Do not change
`cljs.core/*target*` from `"nodejs"`: it describes the server/CommonJS
compilation semantics on which the generated bundle and library conditionals
currently depend. A future `platform/host` value may distinguish Bun for
capability selection, but existing code that asks for `node?` often really
means “server-side ClojureScript.” Audit those callers before changing that
semantic label.

## What becomes deletable

After the Bun runtime, native database sessions, and native web boundary are
all proven, delete:

- Node as a required executable from operator checks, documentation, package
  scripts, CI/runtime images, and no-source packaging;
- literal Node runtime selection in the CLJS test, changed-test, pod, worker
  validator, and eval-oracle launch paths;
- Node HTTP response/request adapters, the response hijack sentinel, and
  per-feed Node gzip plumbing, as detailed in
  [[bun-serve-datastar-internals-2026-07-16]];
- Node socket/EventEmitter and repeated-buffer transport adapters once the
  persistent native Bun authority session replaces them; and
- compatibility tests whose sole purpose was to keep a removed Node adapter
  alive.

Do not delete npm package metadata, `node_modules`, `node:*` import spellings,
the `:node-script`/`:node-test` targets, Shadow's JVM watcher, or the bootstrap
compiler merely because their names contain “node.” They remain useful inputs
to Bun unless a later measured native seam actually supersedes them.

## Remaining falsifiers and measurements

- Run the complete maintained CLJS suite under Bun three or more times after a
  warm compile; compare wall, CPU, maximum/steady RSS, peak footprint, failures,
  timeouts, and process-exit cleanup with Node on the identical artifact.
- Run the operator-owned Bun pod through startup, MCP eval, hot reload, clean
  shutdown, crash/restart, and several simultaneous cluster children. One child
  crash must not stop the watcher, authority, supervisor, or sibling children.
- Exercise the bootstrap self-host compiler, native `^:async`/`await`, worker
  eval timeout, `AsyncLocalStorage`, npm SDKs, SQLite/WASM, and every `node:*`
  module actually reachable in the production graph.
- Build and run dev and release artifacts from paths containing spaces and from
  the no-source runtime root; `__dirname`, relative Shadow imports, and source
  maps must remain correct.
- Prove downstream `SEON_EXTRA_NPM` ordering with two packages of the same name
  so Bun and Shadow select the intended root consistently.
- Measure pod steady-state and per-child incremental memory separately from the
  monolithic test graph. The cold test sample shows that Bun speed does not
  automatically mean lower peak memory.

The shortest current conclusion is strong but bounded: Shadow already produces
an artifact Bun can execute, and a focused real Seon test was substantially
faster under Bun. The remaining risk is application/runtime behavior under
long-lived hot reload and the broad production dependency graph—not a need for
a new Shadow target.
