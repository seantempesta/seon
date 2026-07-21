---
type: research
status: completed
tags: [research, prd, flow, web, agent]
---

# Bun production runtime integration audit — 2026-07-15

## Decision

Treat Bun as a candidate **complete JavaScript runtime replacement** for both
development execution and packaged production. Shadow remains the CLJS compiler
on the JVM; “complete” means that Bun runs the development pod, CLJS test
artifacts, worker artifacts, and packaged pod. Use a short Node/Bun differential
period to establish parity and measure the decision, then remove Node runtime
drift if Bun wins. The release compatibility manifest and immutable launch
descriptor must name the selected engine and exact executable identity; a Bun
process is never an ambient `bun main.js` shortcut.

The first implementation is deliberately small: generalize every maintained
JavaScript execution door from a hard-coded `node` executable to the one closed
runtime selection, bind it into artifact/process identity, and reuse every
existing lifecycle and readiness mechanism. Do not change the CLJS build
target, add a second launcher, or introduce Bun-native application code in this
step. If Bun graduates, the selection collapses to Bun as the default and Node
remains only a bounded rollback until one release checkpoint closes.

Bun-native APIs are a second, evidence-gated decision. `Bun.serve`,
`Bun.spawn`, `Bun.file`, `Bun.connect`, and `bun:jsc` can remove compatibility
layers or expose better diagnostics, but a native substitution graduates only
when it strengthens the existing Seon owner, preserves its contracts, and
shows a material whole-pod gain. Measurements now make the HTTP/static/SSE host
adapter and Unix-socket client the two promising native candidates. Native
process spawning is not a performance project. Self-host eval should stay on
the compatible `node:vm` contract until a real profile identifies it as a cost.

This audit does not claim that Bun is faster for Seon. It defines the
integration architecture and the evidence required to find out without
confounding compiler, artifact, database, or application changes.

## Scope and exact source ledger

The audited Bun checkout is `reference-code/bun` at
`be77b652884b16a103cfaa4af3c1102f72f2dcd3`, dated 2026-07-14. Its `LATEST`
file identifies 1.3.14. The installed `/Users/sean/.bun/bin/bun` is also
1.3.14, revision `0d9b296af`; it is not the audited checkout SHA, so executable
and source identities remain distinct in the evidence. Bun is
mostly Rust around JavaScriptCore and native subsystems, with C++ JSC bindings;
moving from Node therefore changes V8/libuv semantics to JSC/Bun semantics. It
is not merely a new executable for the same VM.

The comparison runtime was Node 26.4.0. Seon uses ClojureScript 1.12.145 and
Shadow 3.4.10. The exact Shadow release source is available in
`reference-code/shadow-cljs`; the self-host compiler reference is
`reference-code/clojurescript`.

The source snapshot was inspected on branch
`codex/runtime-reliability-refactor` while other agents had unrelated edits in
the shared checkout. The follow-through added only the existing pod owner's
explicit `SEON_JS_RUNTIME` executable seam; all observed artifacts remain
provisional unless their digest is stated.

## The recommended product shape

### Development during differential proof

The compiler remains:

1. Shadow compiles `:target :node-script` CommonJS.
2. Node and Bun alternately run the live pod, Shadow build-notify client, nREPL
   bridge, and focused CLJS tests from the same artifacts.
3. The one watcher continues to own canonical `client` and `test` artifacts.
4. Hot reload continues to repair the current process in place.

This keeps compilation and artifact publication unchanged while proving the
actual development runtime. Bun does not replace the JVM compiler or own the
canonical test artifact; it executes the output and participates in Shadow's
existing reload protocol.

### Development after Bun graduation

If Bun passes reload, nREPL/MCP, tests, eval, packages, signals, and performance,
the ordinary development pod and test executor switch to Bun too. This is
preferable to permanent Node-development/Bun-production drift. The Shadow JVM
still dominates compilation and remains one managed process; Bun replaces only
the JavaScript executor.

### Packaged production

The independent-distribution unit produces one devtools-free CommonJS runtime
package containing the client bundle, bootstrap cache, program-source corpus,
static assets, npm closure, and compatibility manifest. The package is executed
in two characterization modes:

- Node reference mode: the compatibility oracle and rollback runtime.
- Bun candidate mode: the intended lower-overhead production engine.

Both modes consume the same bytes, launch descriptor, database fixture, and
operator lifecycle. A Bun result is invalid if it came from a Bun-rebundled,
transpiled, or standalone-compiled artifact while Node ran the Shadow output.
Those are later packaging experiments, not an engine comparison.

Once Bun graduates, development and packaged releases select Bun by default
while Node remains an explicitly supported diagnostic fallback for the same
compatibility artifact through one checkpoint. Bun is the test executor, not
the test artifact owner; Shadow remains compilation and dependency authority.

### Why this split is preferable

The temporary split makes engine drift visible: every gate runs twice against
identical inputs. Collapsing to Bun after graduation then removes that drift and
lets Bun-native application paths run continuously in development. It avoids
the false choice between never using native features and rewriting the pod
before Bun has passed the current contracts.

## Current host boundary

### Shadow already supports the Bun premise

The selected Shadow 3.4.10 source is more favorable than the initial Seon
comments imply. Its README explicitly lists “Node.js or Bun” as a requirement.
That wording entered in commit `5206a3893`, which added Bun package-manager
support, and it is present in the exact selected release commit
`d3c04691952aa9ea33f7287ffe9a2b3109c1e510`.

The upstream maintainer's answer in
[shadow-cljs discussion 1147](https://github.com/thheller/shadow-cljs/discussions/1147)
is also direct: if Bun is truly drop-in, no Shadow change should be necessary
and outputs such as `:node-script` and `:esm` are expected to work. Source
inspection supports that expectation for Seon's build. The current
[shadow-cljs README](https://github.com/thheller/shadow-cljs) also lists Node.js
or Bun as supported requirements.

- `shadow.build.targets.node-script` emits JavaScript and defines
  `cljs.core/*target*` as `"nodejs"`; Bun intentionally supplies that host
  surface, so a new `:bun-script` target would only duplicate the target.
- `shadow.cljs.devtools.client.node` uses CommonJS, npm `ws`, synchronous
  `SHADOW_IMPORT`, and `SHADOW_NODE_EVAL`. Bun runs these constructs and the
  installed `ws` package loaded successfully.
- the generated `node-repl*` path already accepts a `node-command` option whose
  default is `"node"`; callers can pass `"bun"` without a compiler patch.
- `:node-test` delegates compilation to `:node-script`. Its optional upstream
  autorun helper hard-codes `node`, but Seon does not use that helper: the one
  repository test runner executes the published artifact itself.

The only Shadow-source patch worth considering is diagnostic polish: the
development client currently advertises `{:host :node}` and formats
`process.version`, which makes a Bun runtime appear to be Node-compatible rather
than recording Bun/JSC explicitly. That does not block execution. Prefer a
small upstreamable feature-detection change after live reload proof; do not
fork the compiler target for a label.

Therefore the primary patch is in Seon, not Shadow:

- `script/seon/dev/process.clj` derives the pod executable;
- `bin/test-cljs` and `script/seon/dev/changed_test.clj` derive the test
  executor;
- worker/oracle launch commands derive the same executor;
- `package.json` scripts and `doctor` reflect Bun as the selected JavaScript
  runtime while retaining the Java/JVM/Shadow prerequisites.

All derive from one manifest/launch runtime coordinate. There is no maintained
set of independent `NODE_CMD` environment switches.

### Build and launch

`shadow-cljs.edn` intentionally emits CommonJS `:node-script` builds. The
self-host bootstrap loader and transitive libraries use `require("fs")`; the
repository previously rejected an ESM migration because it required loader and
`__dirname` glue. Bun's CommonJS loader supports this shape, so changing Shadow
targets is unnecessary and would confound the runtime experiment.

The one incorrect rigidity is in `script/seon/dev/process.clj`: the pod process
argv is literally `["node" client-output]`. `package.json`, `bin/test-cljs`,
and the changed-test runner also spell Node directly, while `doctor` requires
Node and npm. These call sites do not justify a new launcher. They show that
runtime executable identity is missing from the existing artifact/launch
contract.

The correct owner is the immutable launch descriptor plus release
compatibility manifest. They should carry a closed engine value, resolved
executable path, version, binary digest where distributable, and compatible
application digest. The existing process identity already hashes argv,
environment, artifact identity, and ownership, so changing the descriptor can
reuse readiness, restart, stale-process detection, and shutdown.

### Runtime semantics Seon actually depends on

The pod is not a generic HTTP script. Its load-bearing host contracts are:

- CommonJS module loading and Shadow's Node bootstrap client;
- self-hosted `cljs.js`, global eval, source maps, and native async functions;
- `AsyncLocalStorage` propagation for database transaction context, agent/error
  scope, warnings, and printed output;
- `vm.runInThisContext` timeouts for the worker eval oracle;
- framed Transit over Unix-domain sockets with `Buffer` operations;
- Node request/response streaming plus gzip `Z_SYNC_FLUSH` SSE;
- filesystem, path, crypto, OS, DNS, readline, WebSocket, and process signals;
- bounded `execFile` and long-lived `spawn` jobs;
- the installed npm SDK, DOM, id, ripgrep, and optional native/WASM closure;
- deterministic shutdown and no orphaned children or sockets.

Compatibility is therefore behavioral. The presence of a module name or a
successful `require` is only the first probe.

## Source findings by integration boundary

### CommonJS, Shadow, and self-host eval

Bun can execute the current CommonJS Shadow artifact. A read-only smoke ran the
same `out/test/test.js` digest under Node and Bun; both completed 62 tests and
396 assertions with zero failures. Bun also loaded every declared runtime npm
package in the current installation.

That is meaningful but incomplete. Shadow development code assumes its Node
client, WebSocket connection, build-notify behavior, nREPL global evaluation,
and process reload semantics. None needs to move for the proposed production
split. The packaged bundle must remove devtools, so the Bun production proof is
about bootstrap initialization, agent eval, source maps, promises, and durable
resume—not hot reload.

`cljs.js` emits JavaScript for the host engine. Most semantics are portable,
but engine differences matter around stack shapes, promise scheduling, global
definitions, `instanceof`, memory retention, and timeout/preemption. Seon's eval
path already auto-awaits native promises and bounds retained results. It should
remain the one owner; no Bun eval API or second transpiler should be introduced.

Recommendation: preserve the Shadow CJS artifact and current eval mechanism.
Make Bun pass the existing eval matrix before considering Bun bundling or
standalone compilation. `bun build --compile` is a packaging experiment only
after dynamic requires, bootstrap cache paths, npm/native assets, source maps,
runtime-root relocation, and child executable lookup are proven in the normal
Bun runtime.

### Async context

Bun's `src/js/node/async_hooks.ts` implements `AsyncLocalStorage` and a partial
`AsyncResource`, while most of the broader `async_hooks` API is stubbed. Seon
uses `AsyncLocalStorage`, not hook enumeration, so the narrow API match is
plausible. The implementation stores async-context frames and has native/event
loop integration; Bun's N-API and socket sources also explicitly preserve
async context around callbacks.

Simple and adversarial preliminary probes passed: a value survived an awaited
promise, and 100 concurrent timer-delayed scopes retained their distinct
stores. This does not close Seon's contract because nested agent, transaction,
warning, print, rejection, socket, and SDK callback scopes must all remain
isolated together.

Recommendation: retain `AsyncLocalStorage` as the single cross-engine
mechanism. Do not replace it with Bun-specific globals or manually threaded
context. Add an engine-parameterized behavioral suite using Seon's real
owners. If a Bun bug appears, report or fix the compatibility layer rather than
adding a second Seon context system.

### HTTP, static files, and routing

The current server uses `node:http`, converts incoming Node request/response
objects into the one Ring-shaped route map, and lets socket-owning handlers
reach the raw objects. Static assets are synchronously read from disk. This is
simple and compatible, but it pays Bun's Node compatibility adapter when run
under Bun.

Bun's Node HTTP server is itself implemented over `Bun.serve` in
`src/js/node/_http_server.ts`; using `node:http` under Bun therefore reaches the
same native uWebSockets-based server through an adapter that constructs Node
`IncomingMessage` and `ServerResponse` behavior. A direct unrestricted probe
confirmed both `Bun.serve({port: 0})` and Bun's `node:http.listen(0)` select an
ephemeral loopback port correctly. The earlier port-zero failure was caused by
the command sandbox and affected Node too; it is not a Bun defect.

Direct `Bun.serve` can remove the Node request/response adapter, use standard
`Request`/`Response`, return `Bun.file` for static content, and expose native
server lifecycle. That is the highest-upside native substitution because the
current compatibility adapter already terminates at one clear owner:
`seon.web.serve`/`seon.web.router`.

It is not an immediate change. The route layer currently permits handlers to
take ownership of raw Node sockets for SSE, incremental writes, close/error
events, and gzip piping. A direct Fetch API host must first express the same
stream cancellation, backpressure, readiness, status/header, request-body,
same-origin, peer-address, and hot-reload contracts. Converting every handler
to Fetch ad hoc would create two web mechanisms.

Recommendation: start Bun production through `node:http`. Profile the Node
adapter cost separately. Only if it is material, redesign the one host adapter
so route handlers consume a host-neutral request map and return either a finite
response or the existing owned streaming response description. Then make
`Bun.serve` the packaged host implementation and retain Node only for the
development adapter. The routing, render, feed registry, and transition logic
remain shared.

### Gzip Datastar SSE

The existing feed owns `zlib.createGzip`, pipes it into the Node response,
writes a frame, calls `Z_SYNC_FLUSH`, observes drain, retains at most one newest
pending event, and closes through the response lifetime. This is not merely
compression; it is the backpressure and cancellation contract.

Bun's Node zlib source implements `createGzip` and flush flags including
`Z_SYNC_FLUSH`, so the compatibility path has a credible implementation. The
native web path can instead return a streaming `Response`, but compression must
not buffer the event stream or erase per-frame flush timing. Automatic HTTP
compression is unsuitable unless it proves first-frame flush, bounded pending
bytes, drain/cancel propagation, and wire compatibility.

Recommendation: keep Node zlib for the first Bun production candidate. A
future native host adapter may use a `ReadableStream` whose producer is still
the one feed registry and latest-wins queue. Test uncompressed native SSE as a
separate arm: on loopback, removing gzip may reduce CPU, latency, buffers, and
complexity while increasing bytes. Choose with measured browser and wire data,
not an assumption that compression is always beneficial. If compressed SSE
remains necessary, retain explicit flush ownership rather than relying on
opaque automatic compression.

### Database Unix sockets

`seon.db.transport.uds` owns a small, explicit protocol: four-byte big-endian
length plus Transit JSON, a 16 MiB frame cap, one-shot request deadlines, and a
persistent ordered publisher connection. `node:net` and `Buffer` are transport
primitives; replay, recovery, and database semantics live elsewhere.

Bun provides both a substantial `node:net` implementation and native
`Bun.connect`/`Bun.listen` socket APIs backed by its runtime socket machinery.
The native API could reduce EventEmitter and Buffer churn and expose tighter
binary callbacks. It would also replace a well-bounded mechanism at the most
correctness-sensitive boundary, while the JVM writer remains unchanged.

Recommendation: do not rewrite the database transport first. Run the current
`node:net` implementation under Bun and measure allocation, throughput, tail
latency, reconnect, and retained RSS. Consider `Bun.connect` only if profiles
show the compatibility socket layer materially contributes. If selected, it
must replace only the transport internals beneath the same encode/decode,
deadline, replay, reconnect, and error-value contracts; it must not invent a
second protocol or Bun-specific writer.

### Child processes and ripgrep

Seon's search, shell, autocomplete, and worker tools use `execFile`, `spawn`,
timeouts, `maxBuffer`, signals, cwd/env gates, streaming stdout/stderr, and
background job lifecycle. Bun implements Node `child_process` on top of
`Bun.spawn`, but its source still documents gaps around extra file descriptors
and IPC. Seon's current uses do not require Node child IPC, yet timeout,
termination, buffer overflow, spawn error, and process-tree behavior are
load-bearing.

Direct `Bun.spawn` offers a cleaner promise/stream API and may reduce wrapper
overhead. Spawn overhead is unlikely to dominate long-running commands, while
semantic drift could strand children. The worker validator is a better native
candidate because its cold spawn is explicitly performance-sensitive and it
uses a narrow stdin/stdout line protocol.

Recommendation: retain `child_process` for general agent shell/search until
the full lifecycle suite passes under Bun. Independently benchmark the worker
oracle/validator with `Bun.spawn`; if meaningful, strengthen the existing
worker-process owner rather than creating a Bun worker service. Never replace
external ripgrep with a different Bun search implementation—the existing
capability, paging, and binary boundary are already the one mechanism.

### Files and static assets

Seon uses synchronous Node filesystem operations for startup/config artifacts,
logs, capability-gated agent files, blob publication, bootstrap loading, and
small static assets. `Bun.file` is a lazy Blob view and `Bun.write` provides an
optimized publication path. They are particularly attractive for finite HTTP
responses because `Bun.serve` can send files without first materializing a Node
Buffer in application code.

They are not a universal replacement. Agent filesystem functions own path
authorization, error envelopes, atomicity, paging, and platform behavior; blob
publication owns content addressing and durability. Swapping primitives must
not weaken those contracts or add host checks throughout domain code.

Recommendation: use `Bun.file` first only inside a future Bun HTTP host adapter
for immutable released assets. Evaluate `Bun.write` at the blob/log publication
owner only with fsync, rename, permissions, error, and crash evidence. Keep the
rest on Node-compatible filesystem APIs until profiling names a real cost.

### Memory and diagnostics

Node's final-performance plan uses `process.memoryUsage`, V8 heap statistics,
event-loop delay/utilization, RSS, and external/ArrayBuffer counters. Bun
supports `process.memoryUsage` and exposes JSC-specific detail through
`bun:jsc`, including `heapStats`; the observed result includes heap size and
capacity, object/protected-object counts, extra memory, mimalloc allocation,
RSS/commit, pages, and reserved regions. `Bun.gc(true)` and heap snapshots are
available for diagnostics.

The metrics are not numerically interchangeable with V8. JSC heap capacity,
mimalloc reservation, Node external memory, and V8 old-space answer different
questions. Final evidence must compare process RSS, CPU, readiness, latency,
and retained-floor slopes across engines, then retain engine-native heap detail
without pretending the fields align.

Recommendation: extend the planned on-demand operator measurement envelope
with an engine tag and engine-owned detail map. The portable layer records RSS,
CPU, uptime, event-loop responsiveness, and workload outcomes. The Node detail
uses V8 APIs; the Bun detail uses `bun:jsc`. Forced GC is diagnostic only and
never part of acceptance.

## Many agents and many clusters on modest hardware

### What `Bun.serve` can and cannot fix

`Bun.serve` should be treated as a serious scaling candidate, not cosmetic API
modernization. Bun's native server uses uWebSockets, retains transport-aware
stream sinks, forwards drain notifications, handles cancellation, and avoids
constructing Node `IncomingMessage`/`ServerResponse` compatibility objects.
With many open agent feeds, these differences can reduce per-connection
allocation, callback/EventEmitter overhead, copying, and slow-client pressure.
`Bun.file` also removes synchronous whole-file reads from the event loop.

It does not make ClojureScript rendering, self-host compilation, or agent turns
multithreaded. A database commit that invalidates many expensive render units
still runs application work on one JavaScript thread. The correct scaling
experiment therefore attributes:

- database observation and candidate selection;
- render/SCI work and serialized element bytes;
- gzip CPU and sync-flush time;
- compatibility request/response allocation;
- native stream/write/drain time;
- browser morph work.

The source reveals one important native-SSE constraint. Bun's Web
`CompressionStream("gzip")` does not expose per-event sync flush. A direct
1.3.14 probe wrote an SSE event but produced only the ten-byte gzip header until
the compressor closed. It would make Datastar appear wedged. The first native
server arms are therefore:

1. `Bun.serve` with explicit `node:zlib` `Z_SYNC_FLUSH` bridged into a Web
   `ReadableStream`;
2. `Bun.serve` with uncompressed SSE.

The second arm is not a compromise. Seon is loopback-first; removing per-feed
compressor state and CPU may increase agent capacity even if wire bytes grow.
Measure representative whole-element morphs. Also set `idleTimeout: 0` during
parity: Bun's ten-second default is shorter than Seon's 15-second heartbeat.

The target web architecture is one runtime-neutral immutable request/response
boundary plus one explicit streaming-body capability. The Bun adapter captures
kernel peer identity through `server.requestIP`, returns `Bun.file` bodies, and
owns Web-stream cancellation/backpressure. Reitit routes, security policy,
render units, feed registry, coalescer, latest-wins pending slot, and heartbeat
remain one shared mechanism. Once Bun wins, delete the raw Node adapter rather
than preserving two front doors.

### Do not map one agent to one Worker

Bun Workers are real OS threads but each creates a distinct JSC VM, mimalloc
arena, environment, globals, and heap. Messages use structured clone. A Worker
cannot share Seon's live `cljs.js` atom, analyzer maps, functions, Datahike
value/connection, instrumentation, or result bindings. `SharedArrayBuffer`
shares bytes, not those objects.

One Worker per agent would therefore multiply the most expensive runtime state,
lose process crash/OOM isolation, and require RPC replicas of canonical compiler
and database mechanisms. Bun's source also records detached-thread and nested
worker teardown gaps. This is the wrong default for modest hardware.

Keep one coordinator pod per active cluster. It owns the replica, agent FSM,
canonical compiler state, ALS context, HTTP/SSE, and lifecycle. Different agents
already overlap network/model/database waits on the event loop. Preserve one
mutating turn per agent and one canonical compiler mutation lane.

### Use bounded execution cells for genuine CPU work

The justified parallel boundary is a small disposable cell pool for pure,
bounded CPU work that already has an immutable request/result envelope:

- standalone self-host compile/eval validation;
- speculative repair candidates;
- parser/scorer work over byte payloads;
- test/oracle jobs whose entire state is disposable.

Start with zero or one warm cell on modest hardware and admit two only after
throughput/PSS evidence. A Bun Worker minimizes dispatch latency but shares the
pod crash boundary; a long-lived subprocess provides hard crash/OOM containment
and matches the existing JSON-line worker oracle. Both duplicate a JSC/compiler
heap, so a process-per-agent design is rejected.

Cells receive content-addressed bootstrap/source bytes and return plain
envelopes. No connection, DB value, closure, analyzer atom, or Promise crosses
the boundary. A hot reload changes the generation digest, drains old cells, and
warms replacements lazily. Transfer large UTF-8 buffers rather than cloning rich
maps. This strengthens the one eval owner instead of creating another agent
runtime.

### Test-time spikes: reuse before parallelizing

Bun cannot remove the Shadow JVM compilation peak. The highest-value test
change is to stop launching avoidable compiler JVMs:

- the managed watcher already publishes a content-addressed immutable full test
  artifact;
- changed-test already runs all affected selectors in one fresh process;
- ordinary focused runs should reuse that artifact whenever its digest and
  namespace coverage are current;
- queued selectors should coalesce into one executor process;
- execution may leave the mutable build lock only when pinned to an immutable
  digest.

After that, A/B Bun versus Node as the executor. Do not use `bun test`; Shadow's
runner and completion truth remain authoritative. Do not shard into Workers by
default: tests intentionally use process-global database connections, config,
registries, and environment, and Bun's Node `worker_threads.resourceLimits` is
currently empty. If a long suite merits parallelism, use a small number of
independent processes over the same immutable artifact and aggregate each
process's complete terminal summary. A checkout-wide weighted budget should
normally admit one heavy CLJS executor, perhaps two on measured larger hosts.

### Cluster density: share fixed costs, not mutable pod state

The strongest density opportunity may exceed the Node-to-Bun saving. The
desired topology is:

```text
development: 1 watcher per artifact flavor
packaged:    0 watchers
runtime:     1 immutable artifact closure
             1 writer service per trusted database family
             1 Bun pod per active cluster
             0 pods for dormant clusters
```

The writer protocol and implementation already route operations by database
name and retain a multi-database registry. Branch pods already demonstrate
reuse of an external watcher and writer. Generalizing this into an explicit
database-family writer can remove duplicate JVMs—the largest per-cluster fixed
cost—while keeping each database and pod isolated. It requires its own lifecycle
unit because writer ownership, fairness, embeddings, failure radius, and stop
semantics must no longer imply one runtime cluster.

Do not put multiple clusters inside one Bun process. Seon's single `db/*conn*`,
compiler state, instrumentation, result slots, hosted agents, HTTP/feed
registry, config, logging, and admission are process-scoped by design. One
runaway or native failure would affect every colocated cluster. One pod per
active cluster remains the correct isolation boundary.

Dormant clusters should retain database/blob facts with no pod. Start Bun pods
on demand and evict only genuinely idle pods through the existing quiesce/stop
transition, final-coordinate proof, and later resume. Do not keep a warm pool of
unattached pods: boot claims descriptor, paths, config, blob view, compiler, and
database identity, while an idle initialized heap consumes most of the same
memory.

Benchmark Bun's `--smol` as a distinct modest-hardware arm. It asks JSC to use
less memory and collect more often; it is not a hard cap and may increase eval
or render latency. Compare ordinary Bun and `--smol` at cold boot, idle, five
agents, self-host compile, feed/render load, and grown state.

### Resource-aware admission

Parallelism without admission turns speedups into swap and tail-latency spikes.
Extend the existing PID/start-time-fenced operator status with bounded,
on-demand RSS/physical-footprint and CPU evidence. Before a cold pod or heavy
test starts, reserve measured p95 boot peak plus OS/writer headroom, not only
idle RSS. Queue cold starts, coalesce test selectors, favor interactive work,
and cap simultaneous background turns separately from resident idle pods.

The operator needs no monitoring daemon or telemetry database. It samples at
admission/status checkpoints and returns a typed queued/refused result. Hard
limits belong outside Bun: cgroup v2/container memory, CPU and PID envelopes on
Linux; external admission/priority on macOS. `Bun.gc(true)` is diagnostic, not a
production scheduling policy.

The density evidence should report:

```text
safe active pods = floor(
  (host memory - OS/operator headroom - watcher - shared writer)
  / measured p95 pod footprint)
```

and separately require that current use plus the runtime's p95 cold-start peak
fits before every admission. Measure 1, 2, 4, and 8 clusters with unique
database/blob/process/log/HTTP coordinates, one pod failure, shared-writer
failure, cold-start storms, idle eviction, and grown multi-agent workloads.

## Integration stages

### Stage 0 — freeze the experiment

- Record source status, application and bootstrap digests, npm lock, config,
  complete database coordinate, host state, Node version, Bun SHA/version and
  binary identity.
- Build one devtools-free CommonJS runtime artifact through the canonical
  Shadow/release owner.
- Prohibit source, artifact, database, browser, and concurrent-load changes
  during paired samples.

### Stage 1 — one runtime selection in the existing operator

- Add a closed runtime-engine field to the release compatibility manifest and
  immutable launch descriptor.
- Resolve the executable once; include its path/version/digest and application
  digest in process identity and status.
- Derive pod argv through the existing process-spec owner.
- Make `doctor` validate the selected engine instead of globally requiring Bun
  or removing Node/npm development requirements.
- Preserve one process graph, readiness door, logs, restart, drain, shutdown,
  and stale-owner handling.

There is no `bin/seon-bun`, ambient `SEON_RUNTIME`, second process registry, or
forked launch path.

### Stage 2 — Node-compatible Bun graduation

Run the same artifact under both engines. Bun must pass:

- full packaged CLJS correctness and focused self-host eval suites;
- concurrent/nested async-context isolation;
- UDS fragmentation, multiple frames, deadline, replay, reconnect, and
  read-your-own-write;
- finite routes, static files, request bodies, same-origin and peer gates;
- gzip SSE first flush, transaction-to-frame, drain/latest-wins, cancellation,
  reconnect, and shutdown;
- child timeout, signal, max-buffer, spawn error, background output paging, and
  subtree cleanup;
- provider SDK stream/abort behavior and every shipped npm/native dependency;
- cold start, restart, crash recovery, source-map diagnostics, and exact
  database-coordinate read-back.

Only then compare whole-pod performance. The preferred decision threshold is
at least 20% lower steady grown-database pod RSS, or a statistically convincing
15% reduction with no retained-floor slope, while CPU and p95 latency regress
no more than 10% and every existing absolute budget remains green.

### Stage 3 — profile-guided native substitutions

Profile the graduated Bun compatibility runtime. Consider native changes in
this order:

1. `Bun.serve` plus `Bun.file` at the one web host adapter.
2. Uncompressed versus explicitly flushed compressed streaming SSE.
3. `bun:jsc` on-demand diagnostics.
4. `Bun.spawn` for the narrow persistent worker process.
5. `Bun.connect` beneath the existing database transport, only if socket
   profiles justify the risk.
6. `Bun.write` at proven publication hot spots.

Each substitution is a separate before/after result on the same workload. It
deletes or bypasses the superseded compatibility layer in its selected
production host; it does not duplicate routing, feed, process, database, or
filesystem policy.

### Stage 4 — optional packaging experiments

Only after normal Bun execution graduates, test Bun standalone compilation or
bundling. Admission requires dynamic CommonJS imports, runtime-root relocation,
bootstrap/source assets, native executables, source maps, licenses/SBOM, and
downstream no-source installation to remain reproducible. Faster startup or a
single binary does not compensate for an opaque or non-reproducible artifact.

## Rejected approaches

### Replace Node everywhere immediately

This couples runtime migration to Shadow development, tests, hot reload, MCP,
packaging, and production. It makes failures harder to localize and offers no
additional production benefit over the split design.

### Rewrite every Node API to its Bun-native equivalent

This optimizes module names rather than measured costs. It creates Bun-only
semantics throughout domain namespaces and makes Node development incapable of
testing production behavior. Native APIs belong at narrow host owners.

### Maintain independent Node and Bun application implementations

Two routers, feeds, transports, or shell implementations violate Seon's one-
mechanism rule and will drift. Cross-engine variation is limited to the
executable and, later, a small host adapter beneath shared contracts.

### Change Shadow to ESM as part of the migration

The current build is intentionally CommonJS because the bootstrap and
dependencies require it cleanly. Bun supports CommonJS. ESM would be an
independent artifact migration with no demonstrated bearing on Bun's runtime
benefit.

### Credit microbenchmarks as the decision

A preliminary worker one-shot was slower under Bun than Node on one sample,
while Bun's native startup is often fast in other programs. Neither result
predicts Seon's long-lived pod. Only paired readiness, steady memory, workload,
feed, database, and soak distributions decide.

## Evidence obtained

- The Bun checkout and installed binary agree on release 1.3.14 but not the
  exact revision, as recorded above.
- The same focused Shadow test artifact passed under Node and Bun: 7 tests, 44
  assertions, zero failures.
- An isolated Shadow watch fixture connected from Bun, accepted a source edit,
  compiled the changed namespace in 0.02 seconds, and ran both reload hooks.
- A real isolated Seon Bun pod connected to Shadow and its database replica,
  replayed startup, instrumented 878 functions, resumed agents, listened on a
  dynamic HTTP port, and opened a Datastar SSE feed. Another task then started
  an overlapping Shadow owner; the supervisor drained this proof pod, so this
  is readiness evidence rather than soak evidence.
- All currently declared runtime npm packages loaded under Bun CommonJS.
- Bun `AsyncLocalStorage` survived awaited and 100-way concurrent timer scopes.
- Bun and Node `vm.runInThisContext` both interrupted an infinite loop near the
  requested 50 ms timeout and reported `ERR_SCRIPT_EXECUTION_TIMEOUT`.
- Direct `Bun.serve` exceeded Bun's `node:http` adapter by 24% on sequential
  request throughput and 62% at bounded concurrency.
- At 100 idle feeds, plain direct `Bun.serve` used 43.09 MiB RSS versus 78.03
  MiB for direct Bun with one gzip stream per feed and 87.19 MiB for Bun's
  `node:http` adapter with one gzip stream per feed.
- Plain direct Web streams exposed cancellation and backpressure while using
  0.06 CPU-seconds under a paused client; both per-feed gzip variants consumed
  about 0.9 CPU-seconds and buffered the writes.
- For 100 events across 100 feeds, per-feed stateful gzip used 269.4 ms process
  CPU; one shared self-contained gzip member per event used 0.99 ms and shared
  uncompressed encoding used 0.01 ms. Browser and proxy proof is still required
  before selecting concatenated gzip members.
- Native Bun Unix sockets delivered about 3 times the compact-frame throughput,
  68% less CPU, and half the short-run RSS growth of Bun's `node:net` adapter.
  Under deliberate fragmentation the throughput win narrowed to about 10%,
  while CPU and allocation growth remained lower.
- `Bun.spawn` and Bun's `node:child_process` adapter were effectively tied;
  native spawn is justified only by a better control interface, not speed.
- On the persistent self-host worker oracle, Bun was about 13% faster in wall
  time but used about 68% more CPU and 37% more peak RSS than Node. This is an
  acceptable trade candidate, not a rejection: the whole-pod concurrency and
  latency matrix decides.
- `bun:jsc.heapStats()` returned JSC, protected-object, extra-memory, mimalloc,
  RSS/commit, page, and reservation detail.

These probes establish runtime feasibility and material native HTTP/UDS wins.
They do not yet establish sustained whole-pod resource graduation.

## Required report package for a decision

Retain one content-addressed evidence package under the local-performance
schema with:

- exact Node and Bun executable identities;
- identical application/bootstrap/npm/config/database hashes;
- raw test summaries and complete logs for both engines;
- process-cold and restart-warm distributions;
- idle, loaded, peak, and natural-idle RSS/CPU/engine-heap samples;
- HTTP and feed latency/bytes/backpressure samples;
- eval, async-context, UDS, child-process, and shutdown outcomes;
- grown-database and simultaneous-cluster results;
- a multi-cycle retained-floor test and long soak;
- every failure, retry, and noisy sample retained rather than discarded.

## Architecture consequences

No target architecture document should say Bun is the runtime today. If Stage
1 and Stage 2 graduate, update the architecture to describe the pod as a
packaged JavaScript runtime with a manifest-selected engine: Node for the
development control plane and Bun as the default packaged engine. Node-specific
request/response vocabulary should then be confined to the development host
adapter; agent, database, rendering, and feed contracts remain engine-neutral.

The independent-distribution compatibility manifest owns the supported runtime
engine and exact requirement. The local-performance PRD owns comparative proof
and final default selection. The runtime-reliability program ledger continues
to order both behind units 1–8; this research does not pull final performance
graduation forward.

## Next implementation boundary

After the predecessor lifecycle and distribution contracts are settled, the
first Bun implementation unit is:

1. add the closed runtime-engine identity to the release manifest and immutable
   launch descriptor;
2. derive the existing pod process argv and doctor checks from it;
3. package one devtools-free CommonJS artifact;
4. run the compatibility matrix under Node and Bun without native API changes;
5. retain the paired evidence and decide whether Bun enters the full
   performance matrix.

The first native follow-up, if profiling justifies one, is a design spike at
the single web host adapter for `Bun.serve`/`Bun.file` and streaming SSE. The
database transport and eval mechanism remain unchanged until evidence names
them as bottlenecks.
