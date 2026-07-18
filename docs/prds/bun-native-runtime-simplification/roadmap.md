---
type: prd
status: active
tags: [prd, flow, web, agent]
---

# Bun-native runtime simplification roadmap

## Current implementation state

Packages C and D are implemented and live-proven. `Bun.serve` now owns HTTP
lifecycle; reitit receives an ordinary
request map containing the WHATWG Request; handlers return `Response` values;
and Datastar uses one Bun direct `ReadableStream` controller per connection.
The Node raw-request/raw-response injection, hijack sentinel, `node:http`,
`node:zlib`, per-feed gzip objects, and EventEmitter drain/error ownership are
removed.

The focused boundary passes 47 tests / 169 assertions across router, Datastar,
reactive-call, and server namespaces. The complete cut currently nets 113 fewer
lines (582 additions, 695 deletions) including its architecture and roadmap
updates. A first hot cut correctly failed readiness on a stale one-argument
Malli schema for Bun's two-value peer check; the schema is repaired. The old
Node server cannot consume Promise-returning `Response` handlers after hot
reload, so the supervisor must complete one forced drain at this atomic host
boundary before the first Bun-native cold start. This is transition evidence,
not a retained compatibility design.

The first cold Bun-native start reached ready and served `/_seon/ready`, `/`,
`/data`, and an identity-encoded root SSE feed. The root feed delivered its
complete 114,560-byte initial Datastar event and cancellation released the
final feed. A 100-connection `/data/feed` load reached exactly 100 registered
feeds, added about 272 KiB to the already-grown pod RSS, and returned to zero
registered feeds after every client disconnected. A 5,000-request, concurrency
50 readiness run completed without failure at 2,622.67 requests/second and
0.381 ms mean server time per request. A source-clean reconciliation then
stopped the previous Bun pod, watcher, and writer cleanly and brought the exact
committed system back to ready. Real headless Chrome received and morphed the
root feed into the complete system and agent layouts, with feed registration
returning to zero and no application console error. The complete checkpoint
passes 243 operator tests / 1,393 assertions, 219 writer tests / 1,821
assertions, and 1,111 ClojureScript tests / 4,934 assertions.

Package B now has one committed `seon.subprocess` owner around `Bun.spawn`.
Its focused proof passes 10 tests / 44 assertions covering stdin EOF,
stdout/stderr drain, exact byte caps, timeout classification, TERM-to-KILL
escalation, synchronous spawn failure, split UTF-8, callback containment, IPC
identity, drain-without-capture, descendant termination, and ordinary post-exit
resource data. Autocomplete's blocking `execFileSync` Git probes, foreground
and background shell, ripgrep, and the execution host now consume that owner.
The combined consumer gate passes 76 tests / 325 assertions, and the
source-affected gate passes 238 tests / 1,122 assertions. The cut deletes all
remaining `node:child_process`, `execFile`, duplicate stream-pump, and direct
`Bun.spawn` consumer paths.

Live proof exercised nonzero foreground exit with both streams, real ripgrep,
background output and completion, and a root execution child. Killing that
execution child left the pod and writer ready; the next feed started a new
child and returned the same complete 114,560-byte event. An initial shell
timeout exposed that killing only the direct shell left its `sleep` descendant
holding stdout and delayed completion for ten seconds. The shared owner now
starts a detached Bun process group and signals that group. The identical live
probe returns in 103 ms with exit 143, retained pre-timeout output, and
`timed-out?` true; a three-second inherited-pipe regression completes in under
one second.

The outbound web capability now calls `Bun.dns.lookup` and `Bun.fetch`
directly, preserving one manual-redirect, SSRF-policy, deadline, and bounded
body mechanism. Its focused proof passes 13 tests / 45 assertions, including
mixed public/private DNS refusal and split UTF-8 delivery. `node:dns`,
`node:net`, stale host refusal branches, and the separate IP-literal path are
removed from that owner.

Package A now has one immutable Bun identity across the source artifact and
maintained launch doors. Manifest version 8 records the canonical executable,
its SHA-256, Bun version, and full 40-character source revision; all four enter
the application digest. Publication detects executable replacement, pod launch
fails closed when the published bytes changed, CSS and focused tests use the
selected executable, execution children inherit `process.execPath`, and doctor
no longer requires Node or npm. `SEON_JS_RUNTIME` is removed from every
maintained selector. The integrated operator checkpoint passes 134 tests / 524
assertions across artifact, process, changed-test, and CLI ownership.

Package F's obsolete Node/WASI taxonomy and never-implemented WASI filesystem
branches are also deleted. The focused platform/filesystem/log proof passes 46
tests / 176 assertions and removes 99 net lines. Bun remains the sole concrete
runtime while Node-named Shadow targets and Bun-supported `node:*` compatibility
modules remain dependency vocabulary, not runtime choices.

The earliest unsettled contract is now Package A's live artifact publication
and cold launch under its recorded Bun identity, followed by Bun-only
production packaging. The Docker audit is durable at
[[research/bun-only-production-package-audit-2026-07-18]]: replacing the runtime
is low risk, but an honest no-source image remains blocked because startup still
seeds program facts from packaged `src/` and `test/`. The next implementation
boundary is to publish and restart the exact manifest, then separate that
program-source artifact contract from the Docker runtime cut rather than hiding
source in the image.

Manifest version 8 is live-proven on the default cluster with Bun 1.3.14,
revision `0d9b296af33f2b851fcbf4df3e9ec89751734ba4`, and the recorded executable
SHA-256. The first build also proved that ordinary `bun run` may honor a package
binary's Node shebang; CSS now uses `bun run --bun`, and the identical build
passes with Node absent from `PATH`. `bun.lock` is now the one frozen JavaScript
dependency authority; artifact digests, changed-test widening, test reuse, edit
hooks, and source identity all consume it, while `package-lock.json` and npm
installation guidance are removed.
The first complete `bun.lock` publication then rebuilt manifest version 8 and
cleanly stopped and restarted watcher, writer, and pod. All three returned ready
under application digest
`c34e56e8ce646eced5d4afa2020119133d5933c82f957d0239e1ff80385ada94`;
HTTP admission was available and the CSS build emitted no Node fallback.

The first private-memory attribution changes the interpretation of the earlier
RSS numbers without dismissing the real cost. A fresh Bun process uses about
7.4 MB private physical memory. The pod measured 353–374 MB private physical
against 0.9–1.4 GB RSS, and a loaded execution child measured about 219 MB
private physical against 503 MB RSS; the remainder is largely clean or
reclaimable mappings. Datastar feeds, database indexes, and Bun's base runtime
are falsified as dominant owners. The pod cost is primarily the retained
compiled ClojureScript/JSC object graph; the execution artifact additionally
loads self-host bootstrap analyzer state.

The isolated evaluator audit at
[[research/cljs-evaluator-footprint-audit-2026-07-18]] now reproduces that child
cost outside Seon. Official self-hosted ClojureScript with Seon's bootstrap
caches uses 205–206 MB private physical memory, within 14 MB of the measured
Seon child. SCI 0.15.56 at release commit
`9e9c78f4f358ede939b94352ff4edc03b0186c7a` uses 112–114 MB for the simple
parity workload and 117 MB for persistent namespaces, host calls, schema
registration, protocols, records, metadata discovery, and async evaluation.
The initially high-impact candidate was one SCI context per execution child,
with an expected saving around 90 MB private memory. A bounded parity probe then
falsified the required drop-in seam: SCI interns a new var before a failed
definition finishes, while Seon's official-analyzer path removes phantom
definitions after failure. Matching the existing contract therefore begins
with explicit rollback and expands into replacements for analyzer-backed
namespace, warning, test, instrumentation, and introspection semantics. That is
a new compatibility layer, not a dependency swap. Seon retains official
`cljs.js` for agent-authored evaluation and exact ClojureScript semantics.
Commit `436596c6` then removed the redundant renderer cage and the SCI
dependency: production authored rendering was already inside the same
disposable execution child, so the second evaluator added semantics and memory
without adding containment. Build-time indexing still publishes compiled
program facts and removes startup filesystem work independently.
Revisit the evaluator only if measured active-child capacity becomes a real
graduation blocker; do not retain a hybrid evaluator or fork the official
compiler.

The same audit found an interrupted `bun out/test/test.js` orphan that had
survived for over an hour: 3.61 GB RSS, 690 MB current private physical, and
2.7 GB peak private physical. The test runner now owns explicit Bun/output PIDs
and one FIFO, while the Shadow preload makes Bun monitor its owning shell and a
generous overall deadline. A complete run passes 1,123 tests / 4,981 assertions;
real INT cleanup leaves no Bun, output processor, FIFO, or lock; and a missing
owner makes the test process exit 143. This closes the largest observed test
memory/CPU spike mechanism before any compiler-topology experiment.

The bounded Docker runtime cut now removes the Node archive, executable tree,
PATH, npm install, package lock, readiness probe, and pod launcher. Both Linux
architectures use pinned Bun 1.3.14 assets (the x64 baseline build on amd64),
frozen `bun install`, forced-Bun CSS, `Bun.connect` writer readiness, and an
absolute `bun --use-system-ca` pod launch. Shell syntax, diff checks, and a real
isolated Unix-socket acceptance probe pass. The first Linux arm64 image now
builds from the maintained local Datahike source, contains no Node executable,
reports Bun 1.3.14 revision `0d9b296af`, and weighs 310,579,775 bytes. Its first
clean runtime proof reached writer readiness in eight seconds, then correctly
failed closed because the production entrypoint had not supplied the packaged
execution build ID, output, and digest. That admission gap is recorded at
[[../../seon/issues/container-launch-omits-execution-artifact]]; it must be
closed through the existing immutable launch descriptor, not a fallback.
Cross-architecture image builds and container browser/recovery proof remain
required; the image still packages `src` and `test` honestly until the
program-source artifact replaces filesystem source acquisition. The exact
existing-seam design and falsifiers are durable at
[[research/no-source-program-artifact-audit-2026-07-18]].
Multi-cluster, no-source packaging, recovery/soak, and final percentile and
resource measurements remain graduation gates.

## Outcome

Seon runs its JavaScript system on Bun through three small native host
capabilities—subprocesses, HTTP/feeds, and framed Unix sockets—while preserving
one router, one database protocol, one render/feed derivation mechanism, and one
operator. Node compatibility machinery, duplicate wrappers, per-feed compressor
state, and obsolete package/test/runtime branches are removed. The result is
lower latency and CPU under concurrency, fewer fixed per-feed/per-cluster costs,
and a smaller conceptual surface for maintainers and agents.

The migration is successful only when the final source has fewer mechanisms,
imports, mutable lifecycle states, and production dependencies than the current
source. Compatibility parity is a pre-cut planning oracle and a final behavioral
check, not a sequence of intermediate production states.

## Exclusive cutover assumption

Implementation assumes the owner pauses every other source-editing and lifecycle
lane and gives this unit exclusive control of the checkout, operator, canonical
Shadow outputs, default cluster, and Bun proof clusters. The implementation is
one coordinated removal window. It does not keep the application runnable after
each internal edit and does not run the full suite between work packages.

Before the freeze, complete the source/dependency ledger, baseline evidence,
contract fixtures, deletion manifest, and rollback commit. During the freeze,
edit all owners to the final architecture, delete the compatibility tail, build
once, repair compile/structural failures, then run one ordered integrated
graduation matrix. No other lane restarts, rebuilds, formats, stages, or edits a
build input until the checkpoint is released.

## Dependency ledger

- Bun source: `reference-code/bun` at
  `be77b652884b16a103cfaa4af3c1102f72f2dcd3`; installed Bun 1.3.14 revision
  `0d9b296af` remains a distinct executable identity.
- Shadow CLJS: selected fork
  `4e72595f57618f5c43388ad13d5136cd3bede566`; its CommonJS `:node-script`
  artifact already runs, connects, and hot reloads under Bun.
- Existing evidence:
  [[../local-performance-graduation/research/bun-production-runtime-integration-audit-2026-07-15]],
  [[../local-performance-graduation/research/bun-http-native-adapter-measurements-2026-07-15]],
  and [[research/removal-first-integration-audit-2026-07-15]].
- First-party owners: `script/seon/dev/process.clj`,
  `src/seon/agent/shell/internal.cljs`,
  `src/seon/agent/search/internal.cljs`, `src/seon/web/serve.cljs`,
  `src/seon/web/datastar.cljs`, and `src/seon/db/transport/uds.cljs`.
- Predecessors: immutable runtime identity and no-source packaging from
  `independent-downstream-distribution`; final budgets and multi-cluster soak
  remain owned by `local-performance-graduation`.

## Settled decisions

1. Bun becomes the sole supported JavaScript runtime after the retained rollback
   checkpoint. Development and production do not intentionally diverge.
2. `Bun.spawn` is the subprocess primitive. Its advantage is the stronger
   lifecycle/stream/resource interface, not measured spawn speed.
3. `Bun.serve` is the target web host. The existing router and response contract
   remain the application seam. This is a separate transport cut after the
   single renderer/feed semantics and correctness gates are settled; it never
   creates a Bun-specific renderer or feed.
4. Loopback Datastar SSE is uncompressed by default. Compression is admitted
   only for a measured remote boundary.
5. Native Bun Unix sockets are the target database transport after the decoder
   is linear under fragmentation.
6. Bun-native values remain inside internal host owners. Agent-facing functions
   and database values stay ordinary namespaced data.
7. The unused Node-to-Ring raw-response/hijack path is removed. Reitit route
   derivation stays; handlers consume one immutable request value and return one
   response value or a streaming `Response`.
8. The fictional Node/WASI platform taxonomy is removed or replaced by concrete
   capability predicates. Bun is never mislabeled as Node.

## Multi-cluster process topology

The target is one lightweight control plane supervising one Bun child pod per
active cluster. “One Bun system” means one operator/control graph, not one
JavaScript heap:

```text
cluster control plane
  ├─ shared or family-scoped JVM writer service
  ├─ Bun cluster host A
  │   ├─ web UI + agent-child supervisor
  │   ├─ Bun agent child: root
  │   └─ Bun agent children: active tasks
  └─ Bun cluster host B
      ├─ web UI + agent-child supervisor
      └─ Bun agent children: active agents
```

Each cluster host owns only that cluster's web UI, agent-child supervision, and
small host-local compiler/control state. Each active agent child owns its own
loop, eval state, and direct persistent database session. Neither process owns
a Datahike replica, copied database index, transaction feed, or database result
cache. Dormant clusters retain durable database and artifact facts but no Bun
process. Admission starts the host and only the children needed by work or a
human session; idle policy drains them after all addressable work is safe in
the database.

A child exit does not inherently terminate the parent. The control plane awaits
the child's `exited` Promise, samples its post-exit resource usage, records the
classified outcome, and applies bounded restart/quarantine policy. Parent death
is different: native `Bun.spawn().kill()` targets one PID and does not guarantee
descendant cleanup. Preserve process-group containment and parent-death cleanup
until a native group-kill proof replaces it. No unhandled child stream, IPC, or
spawn error may escape the control loop.

Separate child pods are preferred over colocating clusters in one Bun process:

- operating-system scheduling provides real parallel CPU execution;
- each cluster has an independent JSC heap and garbage-collection pause domain;
- one runaway eval, native fault, leak, or unhandled exception cannot kill every
  cluster;
- CPU, max RSS, IO, exit, and signal evidence is attributable per cluster;
- restart, upgrade, admission, and idle eviction happen independently; and
- current ambient database/compiler/runtime state does not need to become
  re-entrant merely to share a process.

One-process multi-cluster remains an experiment only if measurements show the
fixed per-pod JSC cost dominates modest hardware. Bun Workers do not make this
free: every worker owns a JavaScriptCore VM and shares the parent process failure
domain. The experiment must compare process children, workers, and colocated
logical clusters at 1/2/4/8 clusters under identical workloads. It graduates
only if it materially improves admitted-cluster density without worsening tail
latency, isolation, cleanup, or cognitive complexity.

The approved [[../database-authority-mesh/roadmap]] removes the larger fixed JVM
cost per cluster: one JVM initially hosts many database identities, with one
ordered Datahike writer per connection and parallel immutable reads. Every
active agent is a separate Bun child with a direct persistent database session;
no Bun process owns a Datahike replica or copied indexes. Bun work consumes the
settled capability and execute-many protocol and never creates a local cache,
lease, coordinate, subscription, broker, or compatibility path.

## Removal laws

- Replace one owner in place; never add `*-bun`, `*-native`, or a compatibility
  namespace.
- A cut deletes its superseded implementation and tests before graduating.
- Shared product semantics—authorization, caps, errors-as-values, protocol
  framing, routing, render sharing—stay above the host API.
- Host mechanics—streams, cancellation, signals, sockets, resource sampling—sit
  below that contract and may use Bun directly.
- Do not preserve Node behavior merely because it existed. Preserve only a
  named Seon contract with acceptance evidence.
- Prefer incremental bounded streaming to full-buffer convenience APIs.
- Measure event-loop delay, tail latency, CPU per useful event, retained RSS,
  and cancellation—not only requests per second.

## One-window implementation plan

These are dependency-ordered work packages inside one exclusive cutover, not
separately released migrations. Do not preserve temporary adapters merely to
make an intermediate package runnable.

### Pre-cut: freeze identity, contracts, and deletion manifest

Bind Bun executable identity into the release/launch artifact and make every
maintained JavaScript execution door derive from it. Inventory Node imports,
Node-only npm requirements, wrapper lines, mutable atoms/listeners, and artifact
members. Retain Node only as the exact-artifact differential oracle.

The pre-cut oracle proves Bun can run the current artifact and records
source/dependency counts plus whole-pod cold/warm distributions. It does not
modify the final host design. Tag the rollback commit and record every path that
will be edited or deleted. Confirm all other lanes are paused before proceeding.

### Package A: install the final Bun runtime identity

Make Bun version and revision part of the immutable runtime/release identity.
Change the operator, tests, changed-test runner, package scripts, doctor checks,
and packaged launcher directly to Bun. Remove the ambient Node/Bun selector and
Node-default terminology in the same package. Shadow continues to emit its
working CommonJS `:node-script`; target names do not dictate the executor.

### Package B: collapse child execution onto `Bun.spawn`

Define one internal subprocess request/result contract covering argv, cwd,
environment, stdin, timeout, output caps, cancellation, signal/exit, and optional
resource usage. Implement one incremental Web-stream pump. Redirect foreground
shell, background jobs, search, and remaining synchronous Git probes where
asynchrony is valid. Domain owners retain only their authorization and result
interpretation.

Delete both `execFile` wrappers, Node callback/EventEmitter translation, and
duplicated cap/timeout/exit mechanics. Do not emulate `maxBuffer`; enforce the
Seon cap while consuming streams and kill through one explicit escalation rule.

Catch synchronous spawn failures. Use `proc.exited`, not `onExit`, as authority;
the latter may run before `Bun.spawn` returns. Latch timeout, caller cancellation,
and output-limit reasons separately. Await exit and deterministic stream drain,
then sample `resourceUsage()` and normalize both number and bigint fields.
Preserve the existing process-group containment and TERM→grace→KILL owner because
native `kill` targets one PID. Do not trust async `maxBuffer`: an installed-Bun
probe overshot a 1,000-byte setting to 532,350 bytes before termination.

### Package C: replace Node HTTP and raw-response routing with `Bun.serve`

Move only the outer server lifecycle to direct Bun request/response/Web-stream
mechanics. Translate a Web `Request` into the existing router request value;
translate its response back once. Preserve late-bound hot reload, readiness-only
admission, dynamic ports, static asset policy, and errors-as-values.

Remove raw Node request/response injection, the hijack sentinel, Node write
helpers, double-write bookkeeping, and `createServer` EventEmitter lifecycle.
Every current handler is migrated to a returned response value or streaming
`Response`. The Bun fetch callback dereferences the current router/handler on
every request so Shadow hot reload retains late binding. Set server idle timeout
to zero initially because Bun's default 10 seconds is shorter than Seon's
15-second heartbeat.

### Package D: make Datastar fanout shared and backpressured

Keep one derived view and one encoded event per equivalent update. A feed pulls
the newest available event when its Web-stream controller has capacity; stale
intermediate updates coalesce. Cancellation releases the feed immediately.
Loopback responses carry no content encoding.

Delete one gzip object, pipe, flush, drain/error listener set, and compression
buffer per feed. Test shared gzip members only as a separate remote-transport
candidate; ship them only if browsers, proxies, reconnects, and partial delivery
all stream correctly and bandwidth wins materially.

Preserve view-unit dependency tracking, equivalent-view sharing, coalescing,
latest-wins state, and feed registry ownership. Delete `node:zlib`, gzip pipes,
sync flush, Node drain/error listeners, and all documentation that calls gzip
the canonical loopback transport.

### Package E: make framed UDS linear and native

Use the one completed persistent `Bun.connect` database session with its linear
decoder, request/event demultiplexing, exact partial-write cursor, `drain`
retry, deadlines, and idempotent terminal transition. Selective interests ride
that same correlated session. Delete `node:net`, request-per-socket RPC, the
publisher socket, transaction replay/fanout, duplicated accumulation, and
queued-write assumptions.

### Package F: remove the entire compatibility tail

Remove Node production metadata and executable checks, Node-only scripts,
differential flags, `node:child_process`, `node:http`, `node:net`, `node:zlib`,
raw Node request/response vocabulary, and dependencies made unreachable by the
native owners. Replace or delete `seon.platform`'s Node/WASI taxonomy: retain
only concrete capabilities that have real implementations. Remove the unused
WASI refusal branches if no current artifact executes them.

Convert synchronous Git probes in autocomplete to the process capability or
derive revision identity from the launch artifact so the pod thread never blocks
on `execFileSync`. Use `Bun.file` for static assets only where it deletes buffer
materialization or path glue in the final host.

Use reachability and profiles to look again for additional deletion: filesystem
wrappers replaceable by `Bun.file`, redundant buffer conversions, test artifact
copies, duplicated cluster supervisors, idle pods, and per-cluster fixed writer
cost. Findings graduate only through their true owner; this PRD stays open to
more removal without turning them into assumed scope.

### Package G: structural pass before the only integrated build

Run namespace/import/reachability scans, Markdown validation, formatting, and
static schema checks without starting lifecycle processes. Confirm the deletion
manifest is complete, no Node executable path remains, no compatibility owner
was added, and every Bun-native value is confined to its host boundary. Then
produce the final artifact once.

## Integrated graduation after the cutover

Run one ordered checkpoint against the final combined implementation:

1. compile the bootstrap, client, tests, writer, CSS, and closed package;
2. run focused contract/property tests only when the first build exposes a
   failure, repairing the final mechanism rather than restoring adapters;
3. run the complete operator, writer, and CLJS gates once they compile;
4. start one isolated Bun cluster, prove direct database reads/interests, MCP,
   hot reload, subprocesses, routes, Datastar feeds, reconnect, and clean
   shutdown;
5. run the real-browser root, agent, canvas, data, reconnect, and slow-feed
   journey;
6. run retained p50/p95/p99 HTTP/feed/process/UDS measurements, event-loop delay,
   CPU/RSS/JSC metrics, 1/100/1000 feeds, and 1/2/4/8 clusters; and
7. build and start the no-source downstream package on a host with no Node
   executable, then inspect the inventory/SBOM and soak the final system.

Any failure keeps the exclusive freeze active. Repair forward in the one final
owner, rerun the smallest falsifier, and repeat the complete checkpoint only
after all local failures are closed.

## Pre-cut parallel portfolio

Before the exclusive freeze, independent lanes may prepare:

- browser/proxy proof for uncompressed and shared-member SSE;
- arbitrary-fragmentation decoder properties and native socket fixtures;
- package/SBOM reachability and Node-removal inventory; and
- multi-cluster admission measurements, idle-pod policy, and shared-writer
  research owned by their architecture domains.

All lanes stop and hand back coherent commits before the cutover begins.

## Scorecard

Each cut records before/after:

- production JS runtime dependencies and executable requirements;
- Node compatibility imports and Bun-native imports;
- owner lines/functions/listeners/atoms and duplicated policy implementations;
- cold/warm readiness and restart latency distributions;
- p50/p95/p99 request, feed, subprocess, and DB latency;
- CPU per request/event/frame and event-loop delay;
- idle/loaded/retained RSS plus native engine/resource metrics;
- 1/100/1000 feed behavior and 1/2/4/8 active-cluster behavior; and
- cancellation, shutdown, orphan, reconnect, and backpressure correctness.

The scorecard is diagnostic, not a mandate to minimize line count at the cost of
clear contracts.

## Graduation

- Bun is the one development, test, worker, and packaged JavaScript runtime.
- `Bun.spawn`, `Bun.serve`, and native Bun sockets are confined to the three
  internal capability owners.
- Node is absent from runtime requirements, production artifacts, operator
  doctor checks, and maintained execution doors.
- Shell/search share one bounded subprocess implementation with explicit child
  lifecycle and resource evidence.
- Datastar has no per-feed compressor and bounded slow-client behavior.
- Database framing is linear under arbitrary fragmentation and exact under
  partial writes.
- The real browser, no-source consumer, restart/recovery, full tests, soak, and
  simultaneous-cluster matrix are green.
- The final deletion ledger shows every superseded mechanism and dependency was
  removed, with no compatibility namespace or second runtime path left behind.
