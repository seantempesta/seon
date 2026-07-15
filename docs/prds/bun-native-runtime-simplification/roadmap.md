---
type: prd
status: planned
tags: [prd, flow, web, agent]
---

# Bun-native runtime simplification roadmap

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
   remain the application seam.
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
  ├─ Bun pod: cluster A
  ├─ Bun pod: cluster B
  └─ Bun pod: cluster C
```

Each child owns its cluster's replica, agent loops, eval/compiler state, HTTP
server, feeds, and process-local caches. Dormant clusters own durable database
and artifact facts but no Bun pod. Admission starts a pod only when work or a
human session needs it; an idle policy drains it after all addressable work is
safe in the database.

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

The larger fixed cost is likely the JVM writer, not the Bun pod. A writer-family
service capable of owning several database identities could remove one JVM per
cluster while keeping one Bun child per active cluster. That topology requires a
separate database-server contract and must preserve single-writer ordering,
database identity, replay, branch, and failure isolation.

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

First replace repeated accumulated-prefix concatenation with a cursor/chunk
queue decoder and prove arbitrary fragmentation. Then use `Bun.connect`/native
socket callbacks with exact partial-write cursors and drain retries. Preserve
Transit framing, request deadlines, publish replay, close/error envelopes, and
database coordinates.

RPC and publish connections share one decoder and one exact partial-write pump.
Delete `node:net`, duplicated accumulation, and queued-write assumptions.

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
4. start one isolated Bun cluster, prove database replay, MCP, hot reload,
   subprocesses, routes, Datastar feeds, restart, and clean shutdown;
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
