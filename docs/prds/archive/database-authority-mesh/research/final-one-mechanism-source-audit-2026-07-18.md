---
type: research
status: complete
tags: [database, pod, web, research]
---

# Final one-mechanism source audit

## Question and boundary

This is a read-only audit of commit `78694607faea22b2bbc7751acdb188e6d939b55a`.
It asks whether the maintained source has one database API and authority
session, one JVM Datahike writer, one Bun pod and supervised execution-child
path, one Datastar rendering/feed mechanism, and one operator; whether the
load-bearing host boundaries use Bun's native HTTP, Unix-socket, and subprocess
interfaces; and whether the removed JVM application mechanisms or forbidden
database vocabulary remain executable.

No lifecycle command or test ran. The verdicts therefore describe source
topology, not graduation behavior. Generated `pod-host/wasm-tauri/*-build/`
trees, `reference-code/`, build outputs, and historical research were excluded
from product-source searches. Tests and development tooling were inspected and
classified separately so a development REPL or a dependency's own term is not
mistaken for a production runtime.

## Dependency ledger

| Dependency | Selected source | Interface inspected |
|---|---|---|
| Bun | `reference-code/bun` at `d8ecf098572e2b8265b23e40c04efb4067e516cc` | `Bun.serve`, `Bun.connect`, `Bun.spawn`, Node-compatibility modules |
| Datahike | `reference-code/datahike` at `4c55791be1fb8bb8d9332f21c576f5c20b85b760` | connections, database values, committed reports, indexes |
| Shadow-CLJS | `reference-code/shadow-cljs` at `4e72595f57618f5c43388ad13d5136cd3bede566` | `:node-script`/`:node-test` artifact targets and development nREPL |
| Datastar | `reference-code/datastar` at `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | whole-element SSE morph protocol |
| Babashka | `reference-code/babashka` at `0fb349c414e717800be775ba9cb77c95a9eb700d` | operator subprocess boundary |

First-party owners inspected were `src/seon/db.cljs`, `src/seon/db/server.clj`,
`src/seon/db/writer.clj`, `src/seon/db/registry.clj`,
`src/seon/db/transport/uds.{clj,cljs}`, `src/seon/client.cljs`,
`src/seon/subprocess.cljs`, `src/seon/execution{,/host,/runtime}.cljs`,
`src/seon/render.cljs`, `src/seon/web/{serve,router,datastar,debug}.cljs`,
`script/seon/dev/{cli,process,cluster,branch}.clj`, `bin/seon`, `bin/acme`,
`deps.edn`, `shadow-cljs.edn`, and their focused tests.

## Verdict summary

| Requirement | Verdict | Source evidence |
|---|---|---|
| One application database API | Pass | `src/seon/db.cljs:1` is the public asynchronous API; its only process session is `!session` at line 170. Application CLJS consumers require `seon.db`; Datahike calls are confined to the JVM authority and authority-owned embedding implementation. |
| One authority session per Bun process | Pass | `src/seon/db.cljs:170` owns one `!session`; `open-session!` replaces/reuses that owner and its database-value cache rather than building a second client. Every execution child is a separate Bun process and therefore correctly owns its own one session. |
| One JVM Datahike writer path | Pass | `src/seon/db/server.clj:331` composes one `seon.db.writer/start!`; `src/seon/db/writer.clj:3741` owns request semantics; `seon.db.registry` owns the multiple isolated database connections inside that JVM. No second application JVM or embedded CLJS Datahike connection was found. |
| One Bun pod | Pass | `script/seon/dev/process.clj:25-29` enumerates watcher, writer, and pod only; the pod spec is singular. Sibling clusters may share owners, but each selected cluster has one pod rather than a broker plus compatibility pod. |
| One supervised execution-child path | Pass | `src/seon/execution/host.cljs:71` owns one host state; `spawn-child!` at line 337 delegates only to `seon.subprocess/start!`; `ensure-child!` at line 421 and `invoke-once!` at line 478 are the one retained child admission path; `src/seon/execution/runtime.cljs` is the compiled child composition root. |
| One renderer | Pass | `src/seon/render.cljs` is the one generic guarded value/entity render owner. `seon.execution.runtime/render-agent-view!` composes the complete agent projection. Handler `render-ai`/`render-html` functions and `seon.ui.*` are components consumed by that owner, not competing page engines. |
| One Datastar feed mechanism | Pass | `src/seon/web/datastar.cljs:82-92` defines one socket/subscription registry; equivalent views normalize by `:seon.web.feed/key`; `open-view-feed!` at line 1260 is reused by agent, debug, and data views. `view_unit.cljs` contains presentation identity only. No second SSE server, transaction broadcast bus, or stored render cache was found. |
| One operator | Pass | `bin/seon:6` dispatches to `seon.dev.cli`; `script/seon/dev/cli.clj:1115-1134` owns `up`, `down`, `restart`, and `status`; `bin/acme` is configuration around the same operator. `cluster.clj` and `branch.clj` are operation modules invoked by that owner, not supervisors. |
| Bun-native HTTP | Pass | `src/seon/web/serve.cljs:1619` calls `Bun.serve`; `router/handler` is explicitly the single request entry point at `src/seon/web/router.cljs:461`. No Ring adapter, http-kit, Jetty, or Node HTTP server was found. |
| Bun-native client UDS | Pass | `src/seon/db/transport/uds.cljs:16-17` calls `Bun.connect`; native socket callbacks and `.write` backpressure remain inside the session closure. The JVM peer correctly uses Java NIO in `uds.clj`; this is not a second Bun transport. |
| Bun-native spawn | Pass | `src/seon/subprocess.cljs:8-11` calls `Bun.spawn`; it alone owns stream draining, limits, process-group signaling, resource sampling, and IPC. The execution supervisor consumes that ordinary control map. |
| Embedded/local replica removed | Pass with stale text | No `seon.db.replica` namespace or runtime consumer was found. The only source/config match is the obsolete comment `deps.edn:123` saying “CLJS replica”; it describes the current client/server boundary incorrectly. Other “replicated” matches are ordinary English in tests. |
| Integrant/JVM app removed | Pass | No Integrant require, `ig/` call, component graph, or former JVM application namespace was found in maintained source. The JVM namespaces are database authority, restore administration, and embedding work only. |
| JVM web/render removed | Pass | The only HTTP server is `Bun.serve`; the only maintained page/feed owners are CLJS. JVM `seon.db.server` exposes database UDS and optional development REPL, not pages or renders. |
| Runtime nREPL removed | Pass, development-only remains | `script/seon/dev/mcp.clj`, Shadow's `nrepl.port`, and `shadow-cljs.edn :nrepl` are development feedback. The immutable production application does not use them. The writer's optional REPL belongs to development diagnostics and is not the removed JVM application nREPL path. |
| Old core.async application topology removed | Pass architecturally; strict zero-use fails | The CLJS pod has no core.async. The JVM authority still imports core.async in `db/executor.clj:3-4` and `db/writer.clj:9-10`, using only `promise-chan`, `take!`, and `put!` at executor/writer completion boundaries. This is not the former Integrant/core.async application topology, but a literal “no core.async anywhere” requirement is not true. |
| No duplicate cache/feed/renderer | Pass | `seon.db` caches only the latest ordinary database value; the writer owns Datahike indexed values and exact-read joining; Datastar owns one bounded full-event/subscription cache; the router owns one discardable route projection cache. These cache different results at their natural owners and do not form parallel database or render paths. |
| Forbidden database vocabulary removed | Fail for stale names/text, not runtime data | No database “coordinate”, “point”, or “attachment” map remains. Several development test names still use “coordinates” for process/artifact facts, and `deps.edn:123` says replica. NIO's `SelectionKey.attachment` is the Java API name and must not be renamed. Dependency coordinates in artifact code and cursor coordinates in repair code are legitimate non-database meanings. |

## Exact topology evidence

### Database API and authority

`src/seon/db.cljs` declares public schemas and operations, holds one private
`!session`, and exchanges ordinary database values. The session caches current
database values by database name; it does not retain a Datahike connection or
index. The authority is the only place that imports Datahike for runtime
semantics:

- `seon.db.server` creates the dependency map and starts `seon.db.writer`;
- `seon.db.writer` owns query, pull, entity, index, history, transaction,
  interest, and embedding-request interpretation;
- `seon.db.registry` owns process-local Datahike connections for independently
  selected databases; and
- `seon.db.executor` owns bounded shared admission and fair per-database work.

`src/seon/embed.clj` and `src/seon/embed/preflight.clj` also import Datahike.
They execute inside the same JVM authority and receive authority database
values/connections; they do not expose a second pod API or session. This is an
intentional exception to the root-level shorthand “outside `src/seon/db/`,
never call Datahike,” and the localized runtime-boundary text already names
`embed.clj` as authority code. The cleanest eventual structure would inject
only pure embedding projections/index functions into `seon.db.writer`, as it
mostly does now, and move the remaining database initialization/preflight
effects behind `seon.db.server` if the literal folder rule is to become exact.
That is organizational cleanup, not evidence of a second authority.

### Bun process and transport seams

The three load-bearing Bun seams are direct:

```text
browser -> Bun.serve -> seon.web.router/handler
pod or child -> Bun.connect -> JVM Java-NIO Unix socket server
execution host -> seon.subprocess/start! -> Bun.spawn -> execution runtime

```

`node:fs`, `node:path`, `node:crypto`, `node:zlib`, `node:vm`, and
`node:async_hooks` imports are Bun's compatibility modules used inside a Bun
process. They do not select the Node executable, Node HTTP server,
`child_process`, or Node socket API. Shadow's artifact target is named
`:node-script`/`:node-test` because that is Shadow's supported self-host/server
artifact format; `package.json:14` executes the artifact with `bun`.

The remaining Node-related contradictions are documentation/dependency hygiene:

- `src/my/skills.cljs:130` incorrectly says “the pod is Node”;
- `test/seon/eval/memory_safety_test.cljs:7` describes an old Node-pod incident;
- `deps.edn:123` calls the current boundary a CLJS replica; and
- `package.json:39` declares `node-sqlite3-wasm`, while no maintained source
  reference was found. Its necessity must be checked against the final npm
  closure before removal; a name containing “node” alone does not prove a Node
  runtime dependency.

### Renderer and feed ownership

There is one complete-view route:

```text
Datahike transaction report
  -> seon.db interest
  -> seon.web.datastar subscription affected?
  -> seon.execution.runtime/render-agent-view!
  -> seon.render component conversions
  -> one serialized Datastar whole-element morph
  -> every equivalent socket

```

The Datastar registry contains both socket consumers and normalized
subscriptions so shared answers render and serialize once. Its coalescer and
listener-update atoms are invocation/lifecycle coordination within this one
feed, not alternative feeds. The route cache holds only a compiled reitit
projection. Neither duplicates database values, Datahike indexes, or rendered
application truth.

## False-positive classification

The broad searches deliberately found terms that must remain:

- `script/seon/dev/artifact.clj` uses **dependency coordinate**, the standard
  Clojure dependency term, unrelated to database values.
- `src/seon/repair.cljc` uses cursor/screen coordinate in ordinary parsing UI
  language, not a database selector.
- `src/seon/db/transport/uds.clj:933,941` calls Java NIO
  `SelectionKey.attachment`; `attachment` is the dependency's exact method
  name and the value is a socket session, not a Seon database map.
- `src/seon/repl/internal.cljc:986` uses “attach” for comment narration; it is
  not a database term.
- `node` throughout render and plan code means an immutable tree node, not the
  Node.js runtime.
- `node_modules` is npm's directory name and remains correct for Bun packages.
- Datahike imports in writer/registry/program/id/branch/backend and authority
  tests are expected because those files implement or prove the authority.
- nREPL in the Shadow MCP adapter is the maintained development REPL, not a
  production application server.

## Contradictions and smallest next fixes

### 1. Stale forbidden terminology is the earliest source-only contradiction

The executable topology satisfies the one-mechanism requirements, but the
source tree does not yet satisfy the promised terminology cleanup. Make one
small documentation/test-name cut:

- change `deps.edn:123` from “CLJS replica” to “Bun database client”;
- change `src/my/skills.cljs:130` from Node to Bun;
- rename `process-specs-require-published-execution-coordinates` to use
  “artifact fields” or the exact `execution-build-id`/`execution-output` terms;
- rename the CLI tests at `test/seon/dev/cli_test.clj:232,528` to describe
  reconciliation/restart ordering rather than “coordinates”; and
- retain dependency coordinates, NIO attachment, and cursor coordinates.

This fix changes no behavior and removes the only unequivocal mismatch between
the intended vocabulary and maintained first-party prose/tests.

### 2. Decide whether “remove core.async” means topology or dependency

The old application topology is gone, but four authority completion sites
still use core.async. If the final graduation gate literally forbids any
first-party core.async use, replace those promise channels with one JVM-native
completion value (`CompletableFuture` or an existing Datahike callback value),
then remove the direct `org.clojure/core.async` dependency only if the selected
Datahike/Superv.async closure does not still require it. Do not perform this cut
merely for vocabulary: it changes cancellation and completion semantics and
requires focused executor/writer proofs. If the requirement is the originally
stated removal of the old Integrant/core.async **topology**, record that scope
explicitly and leave the four bounded interop sites.

### 3. Remove only proven-unused package entries

`node-sqlite3-wasm` has no maintained source reference. Confirm whether Shadow
or the final release closure resolves it indirectly; if not, remove it and
rebuild the immutable package manifest/SBOM. `cljs-node-io` and Shadow's
`:node-script` naming cannot be classified as unused from a text search because
they participate in self-host compilation and artifact production.

## Reproducible searches

The decisive read-only searches were:

```bash
rg -n 'Bun\.(serve|connect|spawn)|js-invoke js/Bun' \
  src/seon/web src/seon/db/transport src/seon/subprocess.cljs

rg -n 'datahike\.api|seon\.db\.replica|integrant|core\.async|nrepl|nREPL' \
  src script test deps.edn shadow-cljs.edn --glob '*.{clj,cljc,cljs,edn}'

rg -n '(?i)\b(coordinate|coordinates|attachment|attachments)\b' \
  src script test --glob '*.{clj,cljc,cljs}'

rg -n 'node-sqlite3-wasm|sqlite3-wasm|cljs-node-io' \
  src test shadow-cljs.edn deps.edn package.json

rg -n 'defonce.*(!session|!feeds|!server|!host)|open-view-feed!|spawn-child!' \
  src/seon

```

The broad vocabulary search produced dependency-coordinate, parser cursor,
tree-node, and Java NIO method matches; the classifications above are based on
reading each owning source rather than counting raw matches.

## Graduation judgment

The maintained executable source is one system, not two: one Bun client API
per process talks to one JVM authority deployment; one Bun pod supervises
isolated Bun execution children; one complete-view render path feeds one
normalized Datastar registry; and one Babashka operator owns lifecycle. No
embedded/local Datahike application, Node executable/server/socket/spawn path,
Integrant graph, JVM web/render path, or duplicate renderer/feed was found.

Source graduation is nevertheless **not perfectly clean**. Stale Node,
replica, and database-coordinate prose/test names contradict the maintained
vocabulary, and a literal zero-core.async interpretation is false in four JVM
authority completion sites. The terminology cut is the earliest safe fix. The
core.async choice should be made explicitly and proven behaviorally rather
than folded into cosmetic cleanup.
