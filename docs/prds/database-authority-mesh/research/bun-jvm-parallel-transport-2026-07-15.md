---
type: research
status: completed
tags: [research, prd, database, flow, agent]
---

# Bun/JVM parallel transport research — 2026-07-15

## Scope and non-decision

This report investigates database-authority tasks 6, 10, and 11: how isolated
Bun agent processes could reach one JVM/Datahike authority, which transport
topologies preserve genuine parallel execution, and where bounded fair
scheduling belongs. It does not select the final topology or protocol. The
decision briefs at the end intentionally preserve choices for Sean.

No Seon lifecycle, database, source, or test process ran. The only executable
probes were isolated Bun processes and a temporary native Unix socket under
`tmp/`, removed by the probe.

## Dependency ledger

- Seon source: `918cfe12eb0fc30643e26d28b5925d5c80692ad6`.
- Bun source: `be77b652884b16a103cfaa4af3c1102f72f2dcd3`;
  executable Bun 1.3.14, macOS arm64.
- Datahike is the maintained source in `reference-code/datahike`; its exact
  repository checkout is part of Seon's root repository rather than a Git
  submodule.
- JVM probe environment: OpenJDK 26.0.1; Clojure CLI 1.12.5.1654.
- Current semantic owner: `src/seon/db/protocol.cljc`.
- Current CLJS transport: `src/seon/db/transport/uds.cljs:1-203`.
- Current JVM transport: `src/seon/db/transport/uds.clj:55-254`.
- Current authority dispatch: `src/seon/db/writer.clj:986-1083` and
  `src/seon/db/writer.clj:1107-1209`.
- Bun native socket contract:
  `reference-code/bun/packages/bun-types/bun.d.ts:5779-5945` and
  `reference-code/bun/packages/bun-types/bun.d.ts:6278-6506`.
- Bun child contract:
  `reference-code/bun/packages/bun-types/bun.d.ts:6770-7020` and
  `reference-code/bun/packages/bun-types/bun.d.ts:7191-7408`.
- Bun child implementation:
  `reference-code/bun/src/runtime/api/bun/js_bun_spawn_bindings.rs:329-1731`
  and `reference-code/bun/src/runtime/api/bun/subprocess.rs:367-1445`.
- Datahike internal seams:
  `reference-code/datahike/src/datahike/query.cljc:2358-2620`,
  `reference-code/datahike/src/datahike/connections.cljc:1-121`, and
  `reference-code/datahike/src/datahike/core.cljc:130-151`.

## Shortest falsifiers

### Separate children overlap and fail independently

Command, shortened only by line wrapping:

```bash
bun -e 'const start=performance.now();
  const ps=Array.from({length:4},(_,i)=>Bun.spawn(
    [process.execPath,"-e",`const end=Date.now()+500;
     while(Date.now()<end){}; console.log(${i})`],
    {stdout:"pipe",stderr:"pipe"}));
  await Promise.all(ps.map(p=>p.exited));
  console.log({elapsed_ms:performance.now()-start})'
```

Result: four 500 ms CPU-bound children completed in **515.05 ms wall time**,
not approximately 2,000 ms. Each had a distinct PID and exit code 0. This proves
overlap on this machine; it does not promise fourfold throughput on modest
hardware or under a contended JVM.

A sibling-crash probe spawned one child that called `process.abort()` and one
that exited normally after 400 ms. The failed child returned exit 134 and
`SIGABRT`; the sibling returned 0 about 396 ms later and the parent remained
alive. A child failure therefore does not inherently terminate its Bun parent
or siblings. Parent policy still determines whether to restart, terminate a
cluster, or escalate a core fault.

`Subprocess.resourceUsage()` returned `{}` in this probe despite the declaration
of CPU, RSS, and context-switch fields at `bun.d.ts:7105-7180`. It must not be a
required observability source until platform/version tests establish when its
fields are populated. JVM/OS metrics remain necessary.

### Native sockets expose real backpressure

An isolated native `Bun.listen`/`Bun.connect` echo probe attempted to pipeline
8,000 fixed eight-byte frames. `socket.write` returned zero and the probe failed
with `client backpressure`. This directly confirms the declaration at
`bun.d.ts:5785-5812`: writes can be partial when the buffer is full and the
`drain` callback is part of correctness, not an optional optimization.

At 1,000 pipelined eight-byte echo requests, where every write completed in
full, ten-run medians were:

| Persistent native sockets | Median for 1,000 echoes |
|---:|---:|
| 1 | 0.473 ms |
| 4 | 0.403 ms |
| 8 | 0.453 ms |

The small differences are noise-scale and do **not** select a topology. The
probe is useful because it falsifies the claim that socket count alone will
materially determine query latency, while the larger probe demonstrates that
an honest benchmark needs a bounded send queue and drain resumption.

## What the current transport actually permits

The existing CLJS `rpc` opens a new Node-adapter socket per request, writes one
frame, accepts one reply, and closes (`uds.cljs:69-143`). The publish connection
is persistent but is a separate broadcast-shaped path (`uds.cljs:145-203`).
Receive handling repeatedly concatenates the accumulated prefix.

The JVM request server creates one platform thread per accepted connection and
executes one request at a time on that connection (`uds.clj:161-232`). Multiple
connections can enter the writer concurrently, but there is no admission bound,
fair queue, cancellation owner, request correlation, or out-of-order response
demultiplexing. The only shared lock in transport guards connection lifecycle.

`writer/handle-request` already resolves a database by the request's explicit
name (`writer.clj:986-996`, `1107-1160`). The service boots one initial database
but its registry can route more. This is a useful semantic start, not yet a
multi-database scheduler.

Consequences:

- Merely keeping today's request socket open saves connect/close work but stays
  strictly sequential per child.
- True multiplexing requires a request identity on every request and response,
  one reader that demultiplexes replies, synchronized/buffered writes, and an
  explicit out-of-order response law.
- Transport cancellation by closing a socket cancels the entire session. One
  request needs a semantic cancellation operation tied to its request identity.
- A timeout in the Bun child can stop waiting without stopping JVM work. The
  authority must acknowledge whether work was cancelled before admission,
  cooperatively cancelled while running, or completed despite abandonment.

## Topology options

### One persistent UDS connection per agent child

Each OS child owns one native `Bun.connect` socket directly to the authority.

Benefits:

- clean process-to-session ownership and disconnect cleanup;
- no Bun-supervisor proxy hop, extra serialization, or copied response;
- kernel backpressure and a socket failure are scoped to one child;
- one child can be cancelled, killed, or restarted without disturbing siblings;
- simple accounting of admitted work, bytes, and demand per agent process.

Costs and risks:

- one file descriptor and decoder/send queue per agent plus JVM session state;
- strict request/response would head-of-line block an agent's own concurrent
  queries unless the session protocol multiplexes or the child uses a small
  connection pool;
- thousands of children require bounded JVM admission; today's platform-thread
  per connection is not an acceptable implicit bound.

This topology is operationally reversible: a later cluster broker can consume
the same data protocol if request identity and session-independent semantics are
specified first.

### One multiplexed UDS connection per cluster

Separate OS children cannot independently own one byte stream without another
coordination mechanism. In practice the Bun cluster supervisor owns the socket
and brokers every child's requests and responses, or it passes a shared file
descriptor and implements cross-process framing arbitration. Bun exposes extra
`socket-fd` stdio ownership (`bun.d.ts:6781-6791`, `7218-7228`), but that does not
make unsynchronized multi-process writes safe.

Benefits:

- fewer authority sessions, file descriptors, decoders, and authentication
  handshakes;
- one place for cluster-wide admission and shared encoded results;
- database session can survive an individual child restart if desired.

Costs and risks:

- every database operation takes a child-to-supervisor hop before UDS;
- supervisor becomes a data-plane bottleneck and a cluster-wide failure domain;
- one full socket send buffer can head-of-line block unrelated agents;
- broker needs its own correlation, cancellation, fairness, queue limits, crash
  cleanup, and child-demand accounting;
- a supervisor crash disconnects all children at once.

This option should win only if measured per-session JVM/FD/memory cost dominates
the extra hop and larger failure domain. It must not be selected merely because
one socket sounds simpler.

### Bounded connection pool

A pool has two distinct meanings that must not be conflated:

1. a small pool owned by each child, avoiding that child's internal
   head-of-line blocking at higher FD/session cost; or
2. a cluster-supervisor pool shared through the same broker required by the
   one-socket topology.

A cluster pool can route long and short requests separately and continue after
one connection fails. It still adds the broker hop and needs fair assignment;
pool size is a tuning control rather than a semantic contract. A per-child pool
preserves isolation but can multiply connections rapidly. Both are reversible
behind a session-independent protocol.

## Child IPC is not the database protocol

`Bun.spawn` IPC uses JavaScriptCore serialization and supports structured-clone
values, but Bun's declaration says the advanced channel is compatible only with
other Bun processes (`bun.d.ts:6903-6929`). It is strong for Bun-supervisor
lifecycle/control messages and perhaps local result transfer. It cannot be the
generic Datahike authority boundary because the JVM, Rust, remote, and platform
implementations must share that boundary.

Keeping control IPC and database UDS separate also prevents a wedged database
request from hiding child exit/disconnect evidence. Bun documents that IPC
disconnect and process exit callbacks may arrive in either order
(`bun.d.ts:6861-6887`), so the child owner must reconcile both into one
idempotent lifecycle result.

## Framing, encoding, copies, and backpressure

Keep the semantic protocol independent of encoding. The present four-byte
big-endian length plus Transit JSON is adequate for compatibility experiments,
but its costs are visible:

- CLJS creates a Transit string, UTF-8 `Buffer`, four-byte header, then another
  concatenated frame (`uds.cljs:51-63`);
- fragmented receives repeatedly `Buffer.concat` the retained prefix;
- JVM creates a `ByteArrayOutputStream`, copies to a byte array, and wraps or
  copies again for a frame (`uds.clj:55-108`);
- each client separately parses an identical result even if Datahike computed it
  once.

The native transport experiment must first use a cursor/chunk queue, retain the
exact unwritten suffix after a partial write, resume only from `drain`, and
bound queued bytes per session and database. Otherwise a native-vs-adapter or
topology comparison measures bugs and quadratic copies.

Candidate measurements, not decisions:

- Transit JSON versus another conformance-preserving encoding for small query,
  large pull, datom page, and error values;
- encoded-result sharing for identical completed requests at one exact database
  value;
- decode once in a Bun broker versus the extra broker hop and retained object;
- compression only beyond a measured remote threshold, never for local UDS;
- maximum frame, maximum result, chunk/page, and per-session queued-byte bounds.

Large results should be pages/chunks with explicit continuation and cancellation,
not one frame near the current 16 MiB maximum. Socket flow control bounds bytes;
authority admission separately bounds computations.

## A closer Datahike seam remains open

Seon owns this Datahike fork, so the authority need not be an adapter over only
today's `writer/handle-request`. A purpose-built Datahike remote/session
capability could live at its existing internal seams:

- immutable `DB` values and exact query execution;
- the shared completed-result cache and transaction propagation in
  `datahike.query`;
- connection acquisition/final release in `datahike.connections`; and
- transaction completion in `datahike.core`.

That capability would own generic operations such as acquire database, resolve
immutable database value, query/pull/index access, release value, transact,
listen, cancel, and release session. It would not own Seon attachment policy,
agent authorization, cluster supervision, or application error prose.

Possible advantage: single-flight work, snapshot lifetime, cache evidence, and
release can be implemented once beside the values they govern instead of being
reconstructed in a Seon dispatcher. Possible disadvantage: transport concerns
could contaminate Datahike and make its local API harder to reason about. The
research test is whether a small data-only capability protocol can be invoked
in-process and remotely with no socket types in Datahike. If not, keep the
generic session owner immediately outside Datahike.

## Fair JVM multi-database execution

The current platform-thread-per-connection server is concurrent but neither
bounded nor fair. A single client can occupy its thread indefinitely; many
connections create many threads; and there is no explicit per-database share.
Moving those threads to virtual threads would reduce parked-thread cost, but it
would not bound CPU, query memory, output bytes, or starvation.

The research scheduler should separate four concerns:

1. **Admission:** global and per-database semaphores bound admitted query work,
   bytes, and expensive projections.
2. **Ordering:** each physical database has one ordered write lane. Datahike's
   connection/writer remains the semantic serializer; no transport queue may
   reorder accepted writes silently.
3. **Read execution:** immutable database values permit concurrent reads. A
   bounded shared executor avoids one fixed pool per database, while
   per-database queues or weighted permits prevent one cluster taking every
   slot.
4. **Delivery:** completed responses enter bounded per-session queues. Slow
   consumers stop receiving admission or lose replaceable notifications; they
   never block Datahike transaction publication.

Candidate schedulers to benchmark with 1/2/4/8 databases:

- one bounded executor plus fair per-database permits;
- deficit/weighted round-robin database queues feeding one bounded executor;
- virtual thread per admitted task behind the same global/per-database permits;
- separate small CPU and blocking-I/O executors only if profiling proves the
  distinction useful.

Do not use one executor per database by default: its fixed threads and queues
make dormant clusters cost memory and make global capacity harder to enforce.
Do not use unbounded virtual threads as the capacity policy.

Minimum adversarial proof pairs one database issuing long cold queries with
another issuing short cached queries and transactions. Record per-database
p50/p95/p99, oldest queued age, admitted/running/queued counts, CPU, allocation,
RSS, GC pause, cache hits, bytes queued, cancellation latency, and progress over
time. Task 11 should wait for tasks 1-3 to settle cache identity and
single-flight; otherwise duplicated cold work contaminates scheduling results.

## Protocol requirements exposed by this lane

These are requirements to take into specification research, not settled field
names:

- version and capability negotiation;
- stable request identity echoed by every response/chunk;
- database identity plus exact immutable database value/coordinate semantics;
- operation, arguments, bounds, priority class, and optional deadline;
- completed, chunk, cancelled, rejected, stale, and failure results as data;
- semantic cancel and session release operations;
- explicit ordered-write acknowledgement and replay/fencing behavior;
- per-session and per-database resource evidence sufficient to diagnose queueing;
- no JVM, Datahike, Bun, socket, Future, or Promise object on the wire.

CLJ local calls may remain synchronous over an immutable database value. CLJS
authority calls are honestly asynchronous, return ordinary namespaced data after
await, and acquire one immutable database value for a coherent turn/render
rather than silently dereferencing a moving head for every leaf query.

## Decision briefs for Sean

### Decision A — first topology to prototype

**Question:** direct persistent UDS per child, cluster broker, or pool?

- **Direct per child:** strongest isolation, observability, cancellation, and
  simplest ownership; more sessions/FDs.
- **Cluster broker:** lowest session count and opportunity to decode/share once;
  adds hop, copying, head-of-line risk, and cluster-wide data-plane failure.
- **Pool:** tuning option after multiplexing evidence, not a different semantic
  protocol; ownership determines whether it preserves isolation.

**Shortest decision experiment:** 1/8/32 children, cached-small/cold-heavy/large
result mixes, direct versus broker, with deliberate slow consumer and child
crash. Compare p99, total CPU/RSS, copies, queued bytes, FD/session memory, and
blast radius. The microbenchmark here does not distinguish them.

### Decision B — multiplex within a child connection

**Question:** permit several in-flight requests or require sequential calls?

- Sequential is smaller and naturally ordered but one long query blocks every
  later call from that agent.
- Multiplexed requests require correlation, bounded queues, cancellation, and
  out-of-order responses but use one connection efficiently.

**Reversible recommendation for the experiment:** specify request identity and
out-of-order-capable responses now, then compare an in-flight limit of 1 versus
greater than 1. This preserves the option without requiring broad concurrency.

### Decision C — generic Datahike capability owner

**Question:** implement generic remote/session mechanics inside the maintained
Datahike fork or in the JVM authority immediately outside it?

- Inside can share immutable values, single-flight, cache evidence, references,
  and release at their actual owner.
- Outside keeps Datahike transport-free and Seon-specific evolution easier.

**Boundary test:** design one data-only in-process capability with no transport
or Seon policy values. If both local and remote dispatch can call it without
translation duplication, Datahike is the closer seam. If socket/session policy
leaks inward, place it outside and add only the missing cache/lifetime primitives
to Datahike.

### Decision D — JVM fairness policy

**Question:** permits over a shared executor, explicit fair queues, or virtual
threads behind admission?

No option is acceptable without global and per-database bounds. Select using the
adversarial two-database proof before 1/2/4/8 density tests. Weight/priority
policy is a product choice for Sean: strict equality maximizes experiment
isolation; configurable weights let an interactive cluster outrank background
experiments.

### Decision E — encoding investment

**Question:** retain Transit JSON first or optimize encoding with the transport?

Retaining Transit isolates topology and scheduling effects and minimizes initial
scope. A binary encoding may reduce CPU and copies for large values but expands
conformance and debugging work. First remove repeated concatenation and implement
correct drain handling; then profile encode/decode share before choosing.

## Research exits before the final PRD

The final PRD should remain open until Sean has reviewed the five decisions and
the following evidence exists:

- exact Datahike database-value identity and single-flight results from tasks
  1-3;
- measured direct-versus-broker topology with slow-client and crash injection;
- a data-only generic Datahike capability boundary prototype on paper and in an
  isolated in-process probe;
- Transit/native framing allocation profiles after correct chunk queues and
  drain handling;
- adversarial two-database fairness proof followed by 1/2/4/8 density evidence;
- explicit choice of equality versus weighted priority between clusters; and
- protocol conformance fixtures independent of Bun, JVM, and transport.

The implementation remains easy to revise if semantics, request identity,
cancellation, and resource ownership are settled before socket count, executor,
or encoding is frozen.
