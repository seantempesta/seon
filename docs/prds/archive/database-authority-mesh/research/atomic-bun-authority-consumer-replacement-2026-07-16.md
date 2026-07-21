---
type: research
status: complete
tags: [research, prd, database, flow, web, agent]
---

# Atomic Bun authority consumer replacement — 2026-07-16

## Result

The optimal seam is not a remote imitation of a Datahike connection. It is the
existing `seon.db` application boundary backed by one persistent native Bun
session and coarse, coordinate-pinned database operations executed by the JVM
authority. Datahike database values, entities, temporal wrappers, indexes,
listeners, and query caches remain inside that authority. Bun receives only
ordinary namespaced data and owns agents, rendering, Datastar delivery, and
process-local coordination.

This cut can remove the local CLJS Datahike replica, full transaction feed,
replay, publisher socket, Node HTTP and zlib adapters, and their lifecycle
state. It also improves control: each agent child has its own direct database
interest; the Bun web host has one database interest shared across all browser
feeds; and every read, cancellation, response, and change is attributable to
one session, request ID, database, and coordinate.

Two contracts were settled before the deletion commit:

1. Core rendering executes one async coordinate-pinned coarse read plan before
   pure rendering. Open-ended agent-authored rendering executes and awaits in
   the owning isolated Bun child, never through a synchronous compatibility
   shim in the web host.
2. Generated-ID candidate generation and all 11 pure builders remain in Bun.
   The existing ordinary candidate manifest crosses the wire; the JVM retains
   policy validation, collision detection, serialized commit, durable receipt,
   and generated-entity recovery. Ambiguous delivery resends identical bytes
   and the same request ID; only a real collision rebuilds with new candidates
   and a new request ID. No declarative transaction language is added.

These are semantic prerequisites, not reasons to preserve the replica. Once
they are closed, the migration should replace the old reachability atomically:
there is no production mode with both a local replica and direct authority
reads.

## Dependency ledger

| Owner | Selected source | Constraint or capability |
|---|---|---|
| Seon application database API | `src/seon/db.cljs` at `bf46a160` | Preserve the `seon.db` namespace as the sole application seam; replace its local Datahike implementation with async session operations. |
| Current replica | `src/seon/db/replica.cljs` at `bf46a160` | Delete local connection, `RemoteWriter`, feed application, replay, reconnect, listener synthesis, and publish socket ownership. |
| Native Bun session | `src/seon/db/transport/uds.cljs` and Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`, `packages/bun-types/bun.d.ts:5781-5811,6305-6357,6459-6493` | `Bun.connect`, partial-write byte counts, `drain`, `connectError`, Unix sockets, one response/event demultiplexer. |
| Datahike entity | Datahike `d9765276cd8d0778f39e93046c2d59b8c2fa8ff2`, `src/datahike/impl/entity.cljc:17-218` | An entity retains a database value and mutable local attribute cache; it is not a wire value. |
| Datahike pull | `src/datahike/pull_api.cljc:328-359` | `pull-many` parses one pattern once and shares its resource budget; use it rather than N remote pulls. |
| Datahike query evidence | `src/datahike/query.cljc:124-152,2654-2670` | `q-with-evidence` already returns query result and conservative attribute dependencies; unknown dependencies become `:all`. |
| Datahike temporal values | `src/datahike/api/impl.cljc:148-194` | `as-of`, `since`, and `history` are host-local wrappers over immutable database values. |
| Datahike listener | `src/datahike/core.cljc:199-217` | Keyed callbacks remain a process-local API but are not installed beside the selected committed-report source; doing both would duplicate commit delivery. |
| Datahike index pages | `src/datahike/index_page.cljc:104-145` | Datahike owns eager bounded ordering and cursors; Seon must not reconstruct index ordering in Bun. |
| Committed-report readiness | `src/datahike/committed_report.cljc:175-278` at Datahike `d9765276` | The selected commit owns bounded `poll-batch!`, identity-fenced idempotent `requeue-ready!`, and blocking `take-ready!`; JVM selective-interest integration remains the prerequisite. |
| Datastar sharing | `src/seon/web/view_unit.cljs`, `src/seon/web/datastar.cljs` | Keep one rendered unit and serialized output shared by browser consumers; replace local read replay with authority dependency evidence and coarse reads. |
| Shadow output | shadow-cljs `4e72595f57618f5c43388ad13d5136cd3bede566`, `src/main/shadow/build/targets/node_script.clj:32-65`, `node_test.clj:15-84` | `:node-script` and `:node-test` emit CommonJS suitable for Bun. Only test autorun hard-codes a Node executable; Seon's runner controls execution. |
| Bun web host | Bun `be77b652`, `Bun.serve`, direct `ReadableStream`, `Bun.file` | Replace Node HTTP, zlib, Ring/raw-response conversion, and the hijack sentinel while retaining Datastar semantics. |

The transport mechanics are grounded in
[[research/persistent-bun-session-atomic-replacement-inventory-2026-07-16]],
the exact read surface in
[[research/exhaustive-read-consumer-and-deletion-inventory-2026-07-15]], and
the Datahike operation shapes in
[[research/remote-datahike-operation-seams-2026-07-16]]. This report resolves
their consumer ordering and atomic deletion boundary.

## The final seam

### `seon.db` remains; local Datahike does not

Application code continues to call namespaced `seon.db` functions. Their
implementation resolves the current database attachment and session, sends an
ordinary request, and returns ordinary data or `:seon/error`. Application
callers stop dereferencing `db/*conn*` and stop receiving database values.

The surviving operation families are:

- current coordinate and health;
- `query` and `query-with-evidence`;
- `pull` and `pull-many`;
- schema inspection;
- bounded index and reverse-index pages;
- coordinate-pinned `as-of`, `since`, and history operations;
- one coarse `execute-many` operation sharing an exact database value;
- `transact!`, generated-ID transaction, and cancellation; and
- `listen!` and `unlisten!` as database-interest ownership on the session.

`listen!` should accept ordinary data describing the relevant query or
attribute set. The callback remains process-local and is registered with the
session owner. No function crosses the protocol. The authority uses Datahike's
one generation-fenced committed-report source, computes committed dependency
evidence, and addresses only interested sessions.

An interest event contains the existing interest request ID, committed
coordinate, and matching ordinary datoms. The physical session already owns
the database attachment, so the event does not repeat a parallel database
identity or dependency envelope. A query interest uses the authority-derived
attribute set only for routing. It never receives a Datahike database value. A
gap or bounded-queue overflow causes one explicit resync with the interest ID
and known coordinate; it does not revive full-feed replay.

### One immutable value per coarse operation

`execute-many` resolves the database attachment and requested coordinate once,
then runs every member against that exact immutable Datahike value. This is the
shared-index and shared-query-cache seam: Datahike performs host-local index
access, completed-query caching, identical-query single-flight, `pull-many`,
and temporal wrapping once or with shared structures. Bun does not add another
cache or attempt to serialize indexes.

Members are ordinary operation data, not arbitrary remote Clojure functions.
Results preserve member order and return ordinary data. A member may depend on
an earlier ordinary result only through a small declared binding shape; if
that is not needed by audited consumers, omit it. The authority should prefer
existing Datahike pull expressions and `pull-many` over N+1 member lists.

### Parallelism without JVM gatekeeping

The JVM remains the sole writer because Datahike already serializes commits,
not because it serializes all work. Independent sessions and databases enter
the bounded authority executor concurrently. Reads against immutable database
values, pull, query, index pages, encoding, KNN, and provider work can run in
parallel. Only each database's commit order is serialized.

Each Bun agent child owns one direct session and one database interest. A
child crash closes only its session and releases its interests; it does not
take down the Bun supervisor, sibling agents, or JVM authority. The web host
owns one session and one database interest regardless of browser count. Its
existing shared render units fan one serialized result to equivalent browser
feeds.

This gives control without a broker process: the authority can account and
cancel work per session/request/database, while the Bun supervisor can restart
children and inspect their exits. Separate self-contained Bun clusters remain
useful for fault and memory isolation, but they share the same JVM authority
and do not duplicate Datahike indexes or JVM heap.

## Atomic source replacement inventory

### Database implementation owners

#### Rewrite in place

- `src/seon/db.cljs`
  - Replace direct `datahike.api`, `datahike.index`, entity, connector, and
    local connection calls with async authority operations.
  - Remove `*conn*` as an application-visible database-value owner.
  - Keep schemas and public names where their meaning remains accurate.
  - Replace `capture-reads` and `replay-reads` with query dependency evidence
    attached to the coarse operation result.
  - Replace local temporal materialization and index traversal with protocol
    members resolved at one coordinate.
- `src/seon/db/id.cljc`
  - Keep candidate generation and the pure builder in Bun, then send frozen
    transaction data plus the existing candidate manifest.
  - Preserve returned IDs/eids as ordinary data. The JVM owns validation,
    collision detection, serialized commit, receipt, and recovery; no function
    or declarative builder language crosses the wire.
- `src/seon/db/coordinate.cljc`
  - Retain coordinate schemas and pure projections in CLJS.
  - Move `resolved`, `as-of`, `since`, and history database-value construction
    to the JVM authority.
- `src/seon/client.cljs`
  - Attach one native session, apply configuration through authority calls,
    obtain program-graph data through a coarse read, and start runtime owners.
  - Delete local database creation/connection, local `d/db` and `d/q`, replica
    feed readiness, and publish-socket setup.
- `src/seon/repl.cljs`
  - Remove its private in-memory Datahike connection and route development
    reads through the selected live authority session. A REPL session may keep
    process-local evaluation state, but it must not create another database
    implementation.
- `src/seon/embed.cljs` and `src/seon/handlers/ns.cljs`
  - Remove their remaining direct Datahike requires/calls. Embedding and
    namespace computations use the same async `seon.db` authority operations;
    delayed embedding updates never gate unrelated reads or commits.
- `src/seon/launch.cljc`
  - Keep one authority socket/session selector.
  - Remove the publish socket and replica-specific launch fields.

#### Delete after reachability closes

- `src/seon/db/replica.cljs` in full.
- CLJS-only Datahike code in `src/seon/db/internal.cljs` that recognizes
  database/entity values, installs local schemas, wraps local listeners, or
  validates a replica connection.
- The CLJS `RemoteWriter` and all response/feed correlation, transaction
  application, replay paging, replay buffering, reconnect timers, synthetic
  Datahike listeners, KNN RPC forwarding, and replica status.
- CLJS production reachability to Datahike and Konserve, including
  `konserve.node-filestore`.

JVM owners such as `src/seon/db/backend.clj`, `registry.clj`, `server.clj`,
`writer.clj`, and the CLJ embedding owners retain Datahike because they run
inside the authority. The deletion test is target reachability, not a textual
ban across the repository. `src/seon/dev/runtime_id.cljc` and development MCP
owners must also stop teaching or inspecting replica state even when they do
not ship in the Bun artifact.

### Read consumers, ordered by owning computation

The migration unit is a computation, not a mechanical replacement of every
`db/query` call. Each owner should expose one async outer function that gathers
the smallest ordinary input at one coordinate, followed by pure transforms.

1. **Bounded database utilities**
   - `src/seon/db/browser.cljs`, `src/seon/embed.cljs`,
     `src/seon/agent/message.cljs`, and
     `src/seon/agent/message/internal.cljs`.
   - Establish pull-many, index-page, KNN, and bounded message-window idioms.
   - Remove local lazy entities and manual index ordering first.
2. **Agent state transitions**
   - `src/seon/agent/run.cljs`, `turn.cljs`, `lifecycle.cljs`, `schedule.cljs`,
     `runtime.cljs`, `runtime/recovery.cljs`, and `agent.cljs`.
   - Read the turn/run/agent facts in one coarse plan, then transact with the
     existing coordinate/CAS fences. Never hold a database value across an
     awaited model or tool call.
3. **Context derivation**
   - `src/seon/agent/ctx.cljs` and every namespace under
     `src/seon/agent/ctx/`.
   - Replace ambient `@db/*conn*`, lazy entities, and many small pulls/queries
     with coordinate-pinned plans per context block. Preserve omission and
     pure rendering from ordinary data.
4. **Eval and code graph**
   - `src/seon/eval.cljs`, `derive.cljs`, `instrument.cljs`,
     `repl/autocomplete.cljs`, `config.cljs`, and bootstrap code in
     `client.cljs`.
   - Return ordinary program-graph, installed-schema, and eval-history data.
     Delete the local Datahike program diff and direct `datahike.api` call in
     `src/seon/handlers/ns.cljs`.
5. **Agent-facing toolkit**
   - `src/my/blob.cljs`, `canvas.cljs`, `data.cljs`, `kb.cljs`,
     `kb/shared.cljs`, `ns.cljs`, `plan.cljs`, `plan/internal.cljs`,
     `skills.cljs`, and `ui.cljs`.
   - Public database functions become genuinely async. `^:async` callers await
     them at the outer boundary; ordinary values remain namespaced data.
6. **Web and render**
   - `src/seon/render.cljs`, every namespace under `src/seon/render/`,
     `src/seon/web/serve.cljs`, `router.cljs`, `datastar.cljs`, `view_unit.cljs`,
     `debug.cljs`, `brand.cljs`, and `reactive/call.cljs`.
   - Settle the render execution contract before deleting sync reads, then
     migrate route, view, debug, and data plans.

The exhaustive per-call inventory remains in
[[research/exhaustive-read-consumer-and-deletion-inventory-2026-07-15]]. It
records more than eighty CLJS test files and the production namespaces that
use `query`, `pull`, entity, index, temporal, listener, transaction, or ambient
connection access. This ordering prevents each call site from inventing a
remote micro-query interface.

### Current listeners become direct database interests

There are three production listener owners:

- `src/seon/agent/loop.cljs`: one interest in each agent child. An exact
  relevant committed change wakes that child; siblings are not woken.
- `src/seon/web/router.cljs`: route dependencies are registered through the
  web host's existing database interest and locally invalidate the route
  result. Do not open another wire interest per browser or route lookup.
- `src/seon/web/datastar.cljs`: one database-scoped interest feeds the shared
  view-unit graph. Changed attributes select candidate units; each candidate
  runs at most one coordinate-pinned coarse plan.

`src/seon/web/view_unit.cljs` keeps its valuable behavior: one unit per shared
view, serialized-output retention, equality suppression, and fanout. It deletes
local operation capture and replay against successive database values. The
authority's conservative query dependencies select candidates; the exact
coarse plan proves the new output.

No open browser page means no render unit and therefore no database work for
that page. N equivalent browser feeds still cause one authority plan, one
render, and one retained serialized result.

### Native Bun web host

- `src/seon/web/serve.cljs`
  - Replace `node:http` with one `Bun.serve` owner returning standard
    `Response` values and direct streams.
  - Serve static assets through `Bun.file` and keep readiness/lifecycle data in
    the existing host owner.
- `src/seon/web/router.cljs`
  - Delete Node-to-Ring request conversion, raw response mutation, and the
    hijack sentinel. Route over standard `Request` data.
- `src/seon/web/datastar.cljs`
  - Delete `node:zlib` and raw Node response writes.
  - Return a direct `ReadableStream` for SSE. Compression remains one explicit
    deployment configuration: off by default locally for debuggability and
    available at the edge or measured Bun response boundary for remote
    clients.

The database session and browser SSE are different streams with different
backpressure. The authority never broadcasts browser output; the Bun web host
retains and fans it.

### Bun build and process owners

- Keep Shadow's `:node-script` and `:node-test` target implementation and
  `cljs.core/*target*` value. Those describe CommonJS/server-JS output, not the
  executable used to run it. No shadow-cljs fork is justified.
- Change the executable default to Bun in `script/seon/dev/process.clj`, and
  route `bin/test-cljs`, `script/seon/dev/changed_test.clj`, worker launchers,
  validators, and oracles through that one selected runtime seam.
- Update `script/seon/dev/cli.clj` doctor checks and `package.json` scripts to
  require/run Bun.
- Remove publish-socket configuration, arguments, status, and tests from
  `script/seon/dev/config.clj`, `process.clj`, and their test owners.
- Make `src/seon/platform.cljs` recognize Bun deliberately. Prefer a
  server-JavaScript capability predicate for compatible `fs`, `path`,
  `crypto`, `dns`, `Buffer`, and `process` usages, plus explicit Bun-native
  branches for sockets, HTTP, files, and child processes. Do not misclassify
  Bun as Node merely because `process` exists.
- Replace `node:child_process` in shell/search/autocomplete owners with one
  `Bun.spawn` process owner when that adjacent cut is made. It provides native
  async stdio, exit observation, kill/control, and avoids proliferating spawn
  policies. It is not a prerequisite for database correctness, but it belongs
  in the same Bun-only host graduation.

Do not delete npm packages, CommonJS output, `node:*` imports that Bun supports,
`Buffer`, or `process` solely because of their names. Delete only adapters and
dependencies that have become unreachable or whose semantics Bun replaces.

## Test replacement inventory

### Delete

- `test/seon/db/replica_test.cljs` in full.
- Full-feed, replay, publisher-socket, local Datahike listener synthesis,
  `RemoteWriter`, and replica-health cases in database transport/client tests.
- Tests whose only purpose is preserving Node HTTP/zlib/Ring adapter or hijack
  behavior.

### Rewrite against the final seam

- `test/seon/client_runtime_test.cljs`: native session attachment, config-free
  reopen, coarse bootstrap read, and clean session close.
- `test/seon/db_test.cljs`: async ordinary-data API, exact coordinates,
  execute-many identity, pull-many, temporal operations, cancellation, and
  errors as values.
- `test/seon/db/restore_test.cljs`: authority-owned restore reads and writes,
  not local connection rebinding.
- `test/seon/agent_lifecycle_test.cljs`: exact per-child interests and restart
  behavior.
- `test/seon/embed_test.cljs`: async KNN and embedding updates that never gate
  unrelated reads or commits.
- `test/seon/index_core_test.cljs`: bounded authority pages and cursors, with no
  CLJS reconstruction of Datahike ordering.
- `test/seon/web/datastar_test.cljs`: shared-interest candidate selection,
  one coarse plan/render per relevant commit, equal-output suppression, and
  direct stream backpressure.
- `test/seon/web/serve_test.cljs`: `Request`/`Response`, Bun streams, `Bun.file`,
  disconnect cleanup, and configurable compression.
- `test/seon/db/transport_uds_test.cljs`: fragmented/coalesced frames, repeated
  events after listen acknowledgement, partial writes, overflow/resync,
  cancellation, late responses, and close races on the native session.

### Retain on the JVM

Retain Datahike-specific correctness and integration tests where Datahike is
actually hosted: query evidence, committed-report readiness, index pages,
schema, history, transactions, and writer integration. Extend them with
selective-interest ordering, fair batches, requeue, gap/resync, final release,
and sibling-session isolation.

CLJS projection tests should use ordinary fixture data. CLJS integration tests
should use one disposable JVM authority database/session. Remove Datahike from
the CLJS test dependency graph so the test artifact itself proves that Bun
cannot accidentally depend on a local database. The `clojure-testing` skill's
fresh local Datahike setup must be updated in the same documentation cut.

## Dependency-ordered implementation plan

### Gate 1 — Complete authority primitives

1. Retain the graduated Datahike bounded index-page and committed-report fair
   batch/requeue proofs at `d9765276`.
2. Implement JVM selective interests with direct session ownership, ordered
   coordinates, bounded queues, explicit gap/resync, and final release.
3. Retain the completed native Bun session event demultiplexing and bounded
   backpressure proof.

Exit: the JVM can address one of many sessions without a publisher, and the
Bun owner can receive interleaved responses/events without loss or unbounded
memory.

### Gate 2 — Establish the final `seon.db` contract

1. Define schemas for ordinary read operations/results, execute-many,
   interests, cancellation, and health.
2. Implement the async session client in the existing `seon.db` owner.
3. Recursively reject Datahike database/entity/datom objects, functions,
   Futures, and Promises at the protocol boundary.
4. Prove one attachment and coordinate per coarse operation.

Exit: a representative bounded consumer can run entirely through the final
API without a local connection or compatibility namespace.

### Gate 3 — Close the two risky semantic seams

1. Retain the selected Bun-side pure builders and ordinary candidate manifest;
   prove bounded collision retry and identical-request recovery.
2. Make core render/context acquisition async and rendering pure over ordinary
   data.
3. Put open-ended agent-authored render execution behind the async isolated
   child execution boundary; return ordinary render data to the web host.
4. Replace bootstrap's local program-graph query/diff with one authority plan.

Exit: no required caller depends on synchronous arbitrary local database
access, and no function needs to cross the protocol.

### Gate 4 — Migrate computations bottom-up

Migrate bounded utilities, agent transitions, context blocks, eval/program
graph, toolkit functions, then web/render owners in the order above. For every
owner, replace multiple ambient reads with one coarse outer plan, preserve pure
transformations, and delete the old read path in the same change.

Exit: source reachability from the Bun entry point contains no local Datahike
read or connection operation.

### Gate 5 — Replace listeners and the web host

1. Move agent wake to per-child interests.
2. Give the web host one shared database interest and locally dispatch route
   and view candidates.
3. Replace local view read replay with authority dependency evidence plus one
   exact coarse plan.
4. Cut to `Bun.serve`, direct SSE streams, `Bun.file`, and explicit compression
   configuration.

Exit: relevant commits address exact consumers; unrelated commits cause zero
query/render work; browser count does not multiply authority work.

### Gate 6 — Atomic deletion and Bun-only graduation

In one reachability-changing commit, delete the replica namespace and test,
Datahike/Konserve CLJS dependencies, feed/replay/publisher paths, publish socket
configuration, Node HTTP/zlib/Ring adapters, and every compatibility flag and
test. Switch operator/test/package execution to Bun. Update architecture,
localized instructions, and skills so no document teaches the removed path.

Exit: clean build, tests, package, and live cluster succeed with no Node
executable and no local Datahike code reachable from Bun.

## Focused and live proof matrix

| Boundary | Focused proof | Live proof |
|---|---|---|
| Protocol values | Recursive result scan rejects database/entity/datom objects, functions, Futures, and Promises. | Inspect representative query, pull-many, schema, index, history, and transaction envelopes from Bun. |
| Immutable read identity | `execute-many` resolves one attachment/coordinate once; all members observe it; equivalent concurrent queries share Datahike evidence. | Hold a coordinate while writes continue and show every member returns the same requested facts. |
| Selective interests | One-of-1,000 addressing; order, duplicate, gap/resync, unlisten acknowledgement, disconnect, and final release tests. | A relevant transaction wakes one exact agent child and web candidate; siblings and unrelated views remain idle. |
| Native session | Fragmented/coalesced frames, partial writes, `drain`, callback overflow, close race, cancellation, late response, and pending-count-zero tests. | Kill one child/session under load; parent, siblings, web host, and authority continue, and resources return to zero. |
| Generated IDs | Collision/retry, committed-reply loss, policy lookup, substitution, and one-commit tests. | Create agents/turns/messages/plans concurrently and show unique IDs plus no extra round-trip loop. |
| Render contract | Async acquisition followed by pure render; Promise leakage fails; equivalent feeds share one unit. | Root, agent, data, and debug pages update; one relevant commit causes one plan/render/event and equal output emits nothing. |
| Agent parallelism | Independent children issue concurrent reads while per-database writes remain ordered and CAS-fenced. | Run 1, 8, and 32 children; crash/restart one; observe concurrent progress and exact database facts. |
| Bun web host | Standard request/response, stream cancellation, slow-client bound, static file, identity/compressed configuration tests. | Long-lived gzip and identity SSE, browser reconnect, focus preservation, and bounded memory under slow/disconnected clients. |
| Build reachability | Shadow dependency/source-map inspection and `rg` prove no replica, publisher, Datahike/Konserve CLJS, `node:http`, or `node:zlib`. | Remove Node from `PATH`; build, test, package, start, browse, transact, and shut down using Bun plus one JVM authority. |
| Performance | Compare old baseline and final p50/p95/p99 latency, CPU, RSS, bytes, query count, render count, and queue depth at 1/8/32 children/feeds. | Sustained modest-hardware run with multiple clusters sharing one JVM; no unbounded queue, replay storm, or browser-count amplification. |

## Remaining risks owned by the roadmap

1. **Bun platform detection.** Treating Bun as `:node` hides native seams;
   changing the platform keyword naively can break compatible filesystem,
   logging, shell, and web branches. Central capability ownership must replace
   scattered runtime conditionals.
2. **Backpressure has two owners.** The database session and browser SSE have
   separate limits and cancellation. Combining them would let a slow browser
   retain authority work or block unrelated agent responses.
3. **No hidden test replica.** Keeping Datahike in the CLJS test artifact would
   preserve the wrong idiom and weaken reachability proof. Datahike-specific
   tests belong on the JVM; Bun integration tests use the real authority.

## Final recommendation

Proceed with the native Bun session and selective JVM authority design. Freeze
the final `seon.db` ordinary-data contract around the selected generated-ID and
render seams. Then migrate computations bottom-up and make one atomic
reachability cut that deletes the replica, full feed, publisher, local Datahike,
and Node web adapters together.

This design preserves Datahike's strengths—immutable values, indexes, query
evidence, cache reuse, pull, temporal reads, and serialized commits—while Bun
owns what it does best: many lightweight isolated children, native sockets,
direct streaming HTTP, process control, and low-overhead fanout. The result is
both simpler and more parallel: one database authority, many direct consumers,
no copied index in every cluster, no broadcast feed, and no compatibility
system left behind.
