---
type: research
status: completed
tags: [research, database, flow, agent, web]
---

# Runtime state atom audit — 2026-07-13

## TL;DR

The active system does not need a giant replacement state atom. It needs one
owner for each genuinely live resource and no atom that competes with the
database for facts.

Keep these process-local artifacts, with explicit start/stop ownership:

- the pod's one database attachment;
- the self-host compiler state and worker compiler state;
- one fiber-local runtime scope;
- the one agent ticker;
- HTTP servers, sockets, in-flight requests, child processes, and the Gemini
  client; and
- the bounded `result/<id>` and recent-test-detail caches.

The most important current defects are correctness defects:

- `seon.eval/!next-budget-ms` is a process-global one-shot value. Concurrent
  agents can consume each other's timeout override.
- Equivalent web feeds share a render but not dependency authority. The first
  socket's dependency atom controls a grouped render, so closing that socket can
  leave the remaining tab with stale dependencies.
- The route handler is a database-derived cache with no active database
  invalidation. A route transaction does not become live until an explicit
  rebuild or reload.
- The database feed adapter has no stop/reattach transition, retains a whole
  database value, and identifies progress with a bare `t`. Its `started?`
  sentinel can strand a replacement connection after reset.
- Malli schemas are still progressively authored into a process-global atom.
  The database is not yet restoring one complete candidate registry that is
  validated before one atomic swap.
- Config, error persistence, embedding, writer initialization, and several test
  surfaces use mutable callback registries to break namespace cycles. Those
  registries make load order part of correctness.

Delete rather than migrate the known dead state: the heartbeat, the second REPL
database, the legacy `/sse` registry, `/data/sse` and its private listener, the
filtered-database handle registry, the process-run liveness set, and the writer's
agent-to-database routing atom. No current test data requires compatibility.

The audit found no Clojure `delay` in the active CLJS pod or permanent writer.
The only source `delay` is in the paused JVM relay
(`src/seon/db/relay.clj:107`) and belongs on the archive side of the boundary.

## Scope and method

This audit covers:

- the active CLJS pod in `src/seon/**/*.cljs` and `src/my/**/*.cljs`;
- shared CLJC loaded by that pod or the writer, principally `seon.schema` and
  `seon.db.id`; and
- the permanent JVM writer closure in `src/seon/server/*.clj` plus
  `src/seon/embed.clj`.

It excludes the paused JVM application, experiments, and dev-only JVM flow
system. For example, `src/seon/db/schema.clj:21-42` contains a second persisted
schema registry, but only paused JVM namespaces require it. It should disappear
with that archived application rather than be reconciled with the active
runtime.

The inventory was built from all top-level `defonce`, `atom`, `volatile!`,
`delay`, mutable registry, and cache declarations in scope, followed through
their reads and writes, and then checked against the target architecture. Local
mutable holders are grouped separately because a per-call parser buffer is not
durable runtime authority.

Classification used below:

| Classification | Meaning |
| --- | --- |
| required process-local artifact | an opaque handle or genuinely in-flight operation that cannot be a datom |
| bounded cache | a discardable derivation with a demonstrated bound |
| derivable database smell | facts or a projection duplicated outside the database |
| duplicate routing state | a second map/sentinel deciding where work or updates go |
| dead | an unused, legacy, test-only-in-production, or superseded path |

## Datahike facts that constrain the design

Datahike already owns the mutable cell around each immutable database value and
the listener registry attached to that connection. A new connection gets a
single `wrapped-atom` whose metadata contains one listener atom
(`reference-code/datahike/src/datahike/connector.cljc:95`). Seon should not add
another boolean that guesses whether a listener exists.

`datahike.core/listen!` is key-idempotent: installing the same key replaces the
old callback, and `unlisten!` removes that key
(`reference-code/datahike/src/datahike/core.cljc:206-224`). Therefore
`!installed?` and `!data-listener-installed?` are both weaker copies of state
Datahike already owns. They also become wrong when the connection is replaced.

The normal Datahike writer invokes the connection's listeners after a
successful transaction report is available
(`reference-code/datahike/src/datahike/writer.cljc:335-358`). The transaction
layer now exposes only effective datoms in that public report
(`reference-code/datahike/src/datahike/db/transaction.cljc:538-544`). The remote
feed adapter should preserve that one semantic event boundary rather than grow
a second event registry.

The resulting rule is:

> One database attachment owns one Datahike listener bus. Agent wakes, router
> invalidation, and visible subscriptions register stable keys on that bus.
> Runtime caches may hang off normalized subscriptions, but durable facts and
> routing remain database projections.

## Active CLJS runtime inventory

### Database, schema, config, and error state

| State | Classification | Finding and required disposition |
| --- | --- | --- |
| `seon.db/*conn*` (`src/seon/db.cljs:322-337`) | required process-local artifact | Keep as the pod's sole attachment pointer. A connection is an opaque Datahike resource. Give it an explicit attach/detach transition and do not mirror it elsewhere. |
| `seon.client/!agent-conn` (`src/seon/client.cljs:349`, `2173-2179`, `2367-2377`) | duplicate routing state | Delete. It mirrors `db/*conn*` solely to make startup idempotent and can diverge during rehost/reset. Ask the attachment owner whether it is live. |
| `seon.repl/!conn` (`src/seon/repl.cljs:93`, `118-132`) | dead | Delete from the production runtime. It creates an unrelated in-memory database for a historical dev-init path. Tests that need a database should own an explicit fixture. |
| `seon.schema/*schemas`, `seon-registry`, registration sentinels (`src/seon/schema.cljc:27-160`) | derivable database smell | A Malli registry is a necessary runtime projection, but these load-time mutations currently author it progressively. Build a complete candidate from canonical database program/schema facts, validate every reference, then atomically replace the runtime registry. Keep only the active validated generation and the unavoidable Malli global link. |
| Malli stomp-guard watch (`src/seon/schema.cljc:39-82`) | required process-local artifact, temporarily | It repairs self-hosted `malli.core` loads that overwrite Malli's global registry. Retain only while that library load behavior exists, and own installation/removal in compiler lifecycle. It must point to the complete validated candidate, not a progressively mutated map. |
| `seon.schema/!tee-fn` (`src/seon/schema.cljc:202`) | duplicate routing state | Remove the callback injection by fixing the namespace boundary. Registration produces data; the eval recorder or explicit program publisher owns persistence. |
| `seon.schema/!last-tee` (`src/seon/schema.cljc:204-208`) | duplicate routing state | Delete. “Most recent Promise” is a process-global observation side channel and is ambiguous under concurrent evals. Return or await the publication result in the eval-local operation. |
| `seon.config/!db-config-view` (`src/seon/config.cljs:403-422`) | duplicate routing state | Delete the injected reader. `config-view` should accept/use the resolved snapshot or call the one database projection API through an acyclic dependency. |
| `seon.db/!config-view-cache` (`src/seon/db.cljs:1817-1847`) | bounded cache, unsafe key | It is only one slot, but it retains a whole database value and zero-argument config reads can cross a turn's frozen snapshot. Thread one resolved config projection per snapshot, or key a measured cache by full branch-qualified database coordinate. |
| `seon.render/!schema-cache` (`src/seon/render.cljs:293-324`) | bounded cache, obsolete source | It prevents repeated expensive schema queries, but retains a whole database value by identity and derives a catalog from persisted schema-decomposition rows that the target removes. Derive the render catalog once per validated Malli generation and cache by generation/coordinate, never by database object. |
| `seon.error/!db-hooks` (`src/seon/error.cljs:264-284`) | duplicate routing state | Replace the injected transaction/basis callbacks with one runtime error sink owned by database attachment lifecycle. Do not make namespace load order decide whether errors persist. |
| `seon.error/!pending` (`src/seon/error.cljs:286-306`, `328-345`, `523-526`) | required process-local artifact, bounded but incomplete | A pre-database fault buffer is legitimate and capped at 32. It currently flushes only when a later `record!` succeeds, so an early boot error can remain forever if no later error occurs. Attaching the database must atomically drain it; failed drains must requeue without loss or reordering. |
| DB transaction, agent, and read-capture ALS instances (`src/seon/db/internal.cljs:54-69`) | required process-local artifact | Fiber-local scope is correct for concurrent agents. Consolidate with eval/error scopes into one namespaced runtime-scope map so nesting composes and there is one propagation mechanism. |
| Error scope ALS (`src/seon/error.cljs:308-326`) | required process-local artifact | Keep the semantics, fold the instance into the unified runtime scope. |

### Compiler, eval, agent, and result state

| State | Classification | Finding and required disposition |
| --- | --- | --- |
| `seon.repl/!compile-state` + `!init-version` (`src/seon/repl.cljs:84-116`) | required process-local artifact, torn pair | The self-host analyzer is opaque runtime state. Replace the two cells with one state machine containing generation, completed state, and in-flight initialization. The current check-then-await permits duplicate initializations and the two resets can expose a mismatched pair. |
| Worker `!state`, `!warnings`, `!core-names`, and request `chain` (`src/seon/worker_eval.cljs:113-114`, `506-517`, `716-727`) | required process-local artifact plus cache | The isolated worker correctly serializes evals. Keep one worker state; make the warning bucket invocation-local and key/clear `core-names` with compiler generation. The Promise chain is a legitimate per-server queue. |
| `seon.client/!state` heartbeat/reload map (`src/seon/client.cljs:247-317`) | dead | Delete the heartbeat and its interval id. HTTP/feed/ticker resources already keep Node alive, and architecture explicitly has no heartbeat service. Reload count/start time belong in logs or supervisor status, not runtime authority. |
| `seon.agent.loop/!loop-input` (`src/seon/agent/loop.cljs:108-122`, `517-549`, `621-667`) | duplicate routing state | It repeats agent-id to compiler/provider closure routing beside the database roster and listener registry. Replace it with an explicit pod service containing the one compiler and provider resolver; `drive-run!` takes an agent id and derives agent config from the database. If a future runtime truly has per-agent opaque handles, those belong to one lifecycle-owned host entry, not a parallel roster. |
| `seon.agent.loop/!ticker` (`src/seon/agent/loop.cljs:670-715`) | required process-local artifact | Keep. It is the one wall-clock resource for schedules and overdue/stale runs. Start, replace, and clear it through pod lifecycle; no other heartbeat/reaper interval should exist. |
| `seon.agent.run/!runs-this-process` (`src/seon/agent/run.cljs:163-180`, `358`) | derivable database smell | Delete. It treats every result in a current-process run as live even after result-cache eviction. Exact result-var membership is runtime cache state; restart/recovery is represented by database recovery/run facts. |
| `seon.eval/!timeout-ms` (`src/seon/eval.cljs:80-102`) | derivable database smell | The timeout is a config dial and should be resolved from the frozen config snapshot or passed explicitly. An agent-mutable process global lets one agent change every other agent's behavior. |
| `seon.eval/!next-budget-ms` (`src/seon/eval.cljs:104-136`, `1628-1660`) | duplicate routing state, P0 bug | Delete immediately. Two overlapping agent fibers can steal or clear each other's one-shot budget. `budget` must return an explicit value/promise wrapper carrying its budget, or place the budget in the same eval-local ALS scope as the form. |
| Timeout sentinel (`src/seon/eval.cljs:189`) | required immutable artifact | Keep as a private identity sentinel; it is not mutable state. |
| Eval warning, print, and record-boundary ALS instances (`src/seon/eval.cljs:264-352`) | required process-local artifact | Their fiber-local semantics are correct. Fold their namespaced values into the unified runtime scope. Keep dispatcher/original-print installation as compiler-generation lifecycle, not three free-standing atoms. |
| Warning/print dispatcher version and original print cells (`src/seon/eval.cljs:272`, `351-352`) | required dev integration, fragmented | Preserve the behavior but make it part of the compiler/runtime generation transition. Production startup should install exactly once; hot reload replaces exactly once. |
| `seon.eval/!result-var-ids` plus `globalThis.result` and analyzer vars (`src/seon/eval.cljs:1012-1024`, `1283-1437`) | bounded cache, split ownership | This is the sanctioned `result/<id>` cache. Encapsulate roster, JS value, and analyzer definition behind one cache API so insert/evict cannot partially succeed. Membership, not run age, determines liveness. Prove the cap after late Promise settlement and make per-agent fairness explicit while one process hosts many agents. |
| `seon.client/!indexed-test-vars` (`src/seon/client.cljs:1087-1092`) | dead in production | Test population belongs to the test build's manifest/indexing step. Do not load a production mutable test registry. |
| `seon.client/!extra-core-vars` (`src/seon/client.cljs:1094-1107`) | duplicate routing state | A downstream preload mutating a Seon atom is a second program-discovery path and leaks consumer integration into core. Use the canonical database program graph/source manifest and one indexing boundary. |
| `seon.client/!orig-shadow-node-eval` (`src/seon/client.cljs:2477-2493`) | required dev integration | Keep only in the development build. It is a one-time patch of Shadow's eval conduit, not durable state. Give it an explicit uninstall/restore transition if the pod supports in-process teardown. |
| Runtime-id `!hosted` + `!cluster` (`src/seon/dev/runtime_id.cljc:32-33`) | duplicate routing state | The cluster is immutable launch configuration and hosted agent ids are a database query. Keep the pure selection grammar; derive the advertisement rather than mutating a second roster. Static infrastructure ids can be constants. |
| ID generator objects (`src/seon/db/id.cljc:1097-1103`) | required process-local artifact | Keep as immutable package adapters behind the one allocator. They are not identity authority; the atomic database allocation transaction is. |
| `seon.test.runner/!run-stash` (`src/seon/test/runner.cljs:612-637`) | bounded cache | The cap of 32 and restart volatility are honest. Keep only in the test/tooling build; production should not pay for full test detail support. |

### Rendering, web, and reactive state

| State | Classification | Finding and required disposition |
| --- | --- | --- |
| SCI `!deadline` + `!input` process-global volatiles (`src/seon/render/sci.cljs:113-138`) | duplicate routing state | Make both invocation-local values closed over by the fresh SCI context. “Synchronous today” is not a composability contract; nested or future concurrent rendering would cross-contaminate them. |
| SCI `_warmup` (`src/seon/render/sci.cljs:142-154`) | required process-local artifact | Keep as a one-time JIT warmup if profiling still proves first-use benefit. It must not become cache authority. |
| SCI `!bounding-warned` (`src/seon/render/sci.cljs:174-205`) | unbounded cache | Replace the unbounded mark-seen set with a bounded log throttle. The visible error is derived from the current failing render and must heal without acknowledgement state. |
| SCI `!source-fallback-noted` (`src/seon/render/sci.cljs:333-352`) | dead after no-migration reset | Delete with the legacy source-parsing fallback. Current test data may reset; structural require facts are the one path. |
| SCI `!recovering` (`src/seon/render/sci.cljs:612-640`) | required process-local artifact | Keep as an in-flight dedup only, with guaranteed removal in `finally`. It is operation state, not a durable “recovered” flag. |
| Canonical Datastar `!feeds` (`src/seon/web/datastar.cljs:206-215`) | required process-local artifact, wrong unit | Open sockets are real resources. Replace per-socket view authority with a normalized subscription/unit registry containing one plan/dependency/cache and N socket consumers. Keep socket identity and backpressure per consumer. |
| Per-feed pending event + draining atoms (`src/seon/web/datastar.cljs:366-397`, `690-710`) | required process-local artifact, bounded | One pending event is a valid backpressure bound, but latest partial patches do not always dominate an earlier membership/full patch. Queue semantic unit invalidations or promote to a full patch when dominance is uncertain. |
| Per-agent-feed dependency atom (`src/seon/web/datastar.cljs:1048-1065`) | duplicate routing state, P0 bug | Move dependency authority to the normalized subscription shared by equivalent tabs. Today grouped rendering selects the first socket's closure (`406-434`), so closing it can reactivate an older dependency set. |
| `!pending-change` + `!broadcast-timer` (`src/seon/web/datastar.cljs:437-465`) | required process-local artifact, incomplete bound | Keep one coalescer state, not two independently reset cells. Preserve earliest `db-before`, latest `db-after`, effective datoms/attrs, first enqueue time, and timer. Add a maximum wait; the current trailing debounce can starve under continuous structural transactions. |
| `!installed?` (`src/seon/web/datastar.cljs:468-499`) | duplicate routing state | Delete. Datahike's stable listener key already makes install idempotent. Lifecycle should install when the first normalized subscription opens and unlisten when the last closes. |
| Historical feed's captured `frozen` DB (`src/seon/web/datastar.cljs:1033-1045`) | cache retaining database object | Store a validated full coordinate and resolve `as-of` through the database attachment/cache. One open tab should not pin a whole database object as its identity. |
| Debug `!snapshot` (`src/seon/web/debug.cljs:1018-1035`) | duplicate routing state | It is a per-feed copy of the current prompt/block projection used by unit producers. Move snapshot ownership under the canonical debug subscription/unit and apply the normal prompt/unit bounds there. Closed debug owns no subscription and does no work. |
| `/data` `!data-connections`, listener flag, and pending flag (`src/seon/web/debug.cljs:46-61`, `957-1011`) | dead | Delete with `/data/sse`. Port `/data` to the same lazy unit/subscription/feed lifecycle as every other view. |
| Router `!ring-handler` (`src/seon/web/router.cljs:66-84`, `299-320`) | derivable database smell | A compiled reitit handler is a legitimate cache, but it must be keyed/invalidation-driven by the route projection and full coordinate. Register one stable route-attribute listener on the canonical bus. Current source explicitly waits for a future caller that does not exist. |
| Router `!same-origin-pred` + `!router-config` (`src/seon/web/router.cljs:73-84`, `322-340`) | duplicate routing state | Remove callback/config injection by fixing ownership. Middleware and static/core route declarations should resolve from immutable launch config plus route facts. Do not store leaf handler functions in an untyped mutable map. |
| HTTP `!server` (`src/seon/web/serve.cljs:60-65`) | required process-local artifact | Keep as the lifecycle-owned server handle. Stop must close all active responses, wait for close, and clear the handle. |
| Legacy `!sse-connections` + `/sse` (`src/seon/web/serve.cljs:67-78`, `145-166`) | dead | No publisher uses this registry. Delete the route, handler, and reset paths; Datastar's feed registry is the one live channel. |

### Transport, tools, and host resources

| State | Classification | Finding and required disposition |
| --- | --- | --- |
| Transit `!writer` + `!reader` (`src/seon/store/internal/wire_node.cljs:41-50`) | required immutable artifact, unnecessary atoms | Transit objects are reusable in single-threaded Node. Construct immutable defs/delays in the transport module; no resettable cells are needed. Rename with the database transport cutover. |
| RPC/parser local atoms (`src/seon/store/internal/wire_node.cljs:147-185`, `225-250`) | required process-local artifact | These are per-socket framing, timeout, and settled-state holders. Keep them local to the socket and guarantee timer/socket cleanup on every terminal path. |
| `seon.store.wire/!transactions` (`src/seon/store/wire.cljs:280-338`, `453-502`, `624-648`) | required process-local artifact, unbounded failure path | Own-write/feed correlation is real in-flight state. It can grow when replies resolve while the feed is disconnected because pruning depends on the feed watermark. Scope it to one attachment, add a hard bound/deadline and exact terminal states, and key it by database coordinate plus durable request id. |
| Writer instance lifecycle atom (`src/seon/store/wire.cljs:521-526`) | required process-local artifact | Keep per writer connection; it correctly tracks admission and pending requests. Attach shutdown to the parent database attachment. |
| Feed adapter `!adapter` (`src/seon/store/wire.cljs:542-565`, `764-910`) | required process-local artifact, broken lifecycle | Replace with an explicit attachment state machine that owns connection, pub socket, reconnect timer, branch-qualified cursor, and stop/reattach. Do not retain `:last-db`; do not use bare `t`; store the timer/socket handles so stop can cancel them. |
| Feed connect local atoms (`src/seon/store/wire.cljs:764-844`) | required process-local artifact | Legitimate replay/live handshake state. It can be one connection-local state transition map; cleanup must settle the drop promise and destroy the socket exactly once. |
| File grant `seon.agent.fs.internal/!config` (`src/seon/agent/fs/internal.cljs:86-120`) | required launch config, unnecessary atom | Host capability grants are immutable for one process. Parse once into the runtime component; tests inject a dependency explicitly. Do not let agent code mutate the host grant. |
| Log `!config` (`src/seon/log.cljs:245-284`) | required launch config, unnecessary atom | Resolve once from supervisor/database config before logging starts. If live rotation config is later required, make it an explicit config projection transition, not a free global setter. |
| Blob `my.blob/!dir` (`src/my/blob.cljs:121-128`) | derivable database/launch smell | The cluster database attachment determines its blob archive path. Derive it from that attachment; hermetic tests pass an attachment/path explicitly. |
| Shell `!jobs` (`src/seon/agent/shell/internal.cljs:185-211`) | required process-local artifact, partially bounded | Child handles and stream buffers are genuinely volatile. Finished records and streams are bounded; running jobs are not. Add a maximum concurrent-job cap and pod shutdown that terminates/reaps the complete process group before clearing the table. |
| Web test overrides (`src/seon/agent/web/internal.cljs:78-81`, `191-193`, `418`, `577`, `660`, `756`) | dead in production | Replace global mutable seams with explicit dependency maps/fixtures in tests. They should not be agent-visible runtime mutation points. |
| Readability `!rdeps` (`src/seon/agent/web/internal.cljs:351-365`) | bounded resource cache | A one-slot optional-module cache is acceptable. Represent loading/failure explicitly if retry behavior matters; otherwise an immutable module load is simpler. |

## Permanent JVM writer inventory

| State | Classification | Finding and required disposition |
| --- | --- | --- |
| Writer database `!registry` (`src/seon/server/registry.clj:203-207`) | required process-local artifact | Keep as `seon.db.registry`'s lifecycle-owned database-name/branch to live-connection map. It is resource identity, not durable domain truth. Open/release must be atomic and idempotent. |
| Writer `!agents` (`src/seon/server/registry.clj:209-215`, `569-609`) | duplicate routing state | Delete. Agent identity and database membership are database facts; wire requests already carry database identity. Do not maintain a second agent roster in the writer. |
| `!on-ensure-db-hooks` (`src/seon/server/registry.clj:247-280`) | duplicate routing state | Replace load-order registration with one explicit connection initializer assembled by writer boot. The fixed writer closure does not need a plugin registry for broadcast/schema/embedding setup. |
| Registry snapshot/restore seam (`src/seon/server/registry.clj:611-625`) | dangerous test-only mutation | Replace with isolated registry components/fixtures. Tests should not snapshot and mutate the live process registry and hook vector. |
| Broadcast `socket-subscribers` (`src/seon/server/broadcast.clj:18-58`) | required process-local artifact | Keep open subscriber handles. Add a public close-all/stop lifecycle used when the pub server stops; current code only closes peers after a failed broadcast. |
| Writer `state` (`src/seon/server/wire.clj:34`, `1150-1199`) | required process-local artifact, incomplete lifecycle | Keep one lifecycle component for request server, pub server, REPL server, and ambient database attachment. Add an explicit stop that closes each resource and releases the registry entry. Do not duplicate ambient connection/name fields already owned by the registry. |
| `!tx-augmenter` (`src/seon/server/wire.clj:225-262`) | duplicate routing state | Delete the mutable callback seam. Compose the writer transaction pipeline explicitly at boot. If embeddings are derived asynchronous work, enqueue from committed facts rather than silently changing primary transaction semantics. |
| `filtered-dbs` + `filter-counter` (`src/seon/server/wire.clj:264-278`, `1028-1036`) | dead | Delete the remote handle API. It retains database objects indefinitely unless a caller releases each handle and is not used by the active pod path. Use coordinate/as-of requests. |
| Embeddable `!embeddables` (`src/seon/embed.clj:464-505`, `545-587`) | duplicate routing state | Trigger attributes and compose symbols are canonical program/schema facts or immutable writer pipeline data. A load-time map of function objects is a second authority and makes backfill depend on require order. |
| Gemini `!client` (`src/seon/embed.clj:603-618`) | required process-local artifact | Keep one lazily created heavyweight client, but own and close it in writer lifecycle. Cache construction failure/key state deliberately rather than retrying accidentally on every call. |
| `embed-call-count` (`src/seon/embed.clj:620-626`) | dead production state | It exists to prove a test. Use an injected fake client or real metrics boundary; do not ship a mutable test assertion counter. |
| Shared `seon.schema` globals | derivable database smell | The writer and pod must project the same canonical schema facts through the same candidate-builder/validator. Do not leave JVM load order as a second schema authority. |
| Human-readable ID generator (`src/seon/db/id.cljc:1097-1103`) | required process-local artifact | Keep the package object behind the single atomic allocator. |

## Invocation-local holders and caches

These mutable cells do not survive an operation and are not database authority:

- plan/transcript collectors (`src/my/plan/internal.cljs:107` and
  `src/seon/agent/ctx/transcript.cljs:191`);
- read-capture buckets (`src/seon/db.cljs:475`);
- markdown/context/config reducers (`src/seon/ui/markdown.cljs:52-53`,
  `src/seon/agent/ctx.cljs:1831`, `2059-2061`, and
  `src/seon/config.cljs:1251`);
- client index/seed collectors (`src/seon/client.cljs:792-793`, `955`, and
  `2440`);
- eval warning/output/form accumulators (`src/seon/eval.cljs:1118`,
  `1429`, `1999`, `3473`, `3528`, `3571`, `3822`, `3934`, and
  `4682-4690`);
- worker request/report accumulators (`src/seon/worker_eval.cljs:716`);
- file result collectors (`src/seon/agent/fs.cljs:601-602`);
- request-body collectors (`src/seon/web/serve.cljs:183` and
  `src/seon/web/reactive/call.cljs:208`);
- the Datastar retained-id swap helper (`src/seon/web/datastar.cljs:890`) and
  writer broadcast's dead-subscriber collector
  (`src/seon/server/broadcast.clj:41`);
- bounded search/retrieval accumulators (`src/seon/agent/search/internal.cljs:330`
  and `src/seon/diffusion/retrieval.cljs:392`); and
- test runner builders/timeouts (`src/seon/test/runner.cljs:396`, `541`).

They are acceptable when they bridge callback-based APIs or collect an async
stream. Pure synchronous reducers should be changed opportunistically to
`reduce`, transients, or explicit recursion, but they are not a cold-resume
threat and should not distract from process-global authority.

## Hidden mutable state not spelled as an atom

The refactor must also account for mutable host state reached indirectly:

- a Datahike connection's `wrapped-atom` and listener atom;
- the CLJS analyzer atom nested inside compile state;
- `globalThis.result` and analyzer definitions backing `result/<id>`;
- Malli's process-global default-registry atom;
- the patched `js/SHADOW_NODE_EVAL` function;
- Node HTTP servers, sockets, timers, and child processes; and
- the Gemini client's connection pool.

Putting a Clojure map around these objects does not make them durable. Each must
have one lifecycle owner, and every owner must support stop/reset without
depending on process death.

## Prioritized simplification plan

### P0 — remove active cross-agent and stale-view bugs

1. Replace `!next-budget-ms` with eval-local explicit budget data and exercise
   two deliberately interleaved agent evals.
2. Make one normalized web subscription own render plan, observed reads,
   dependency set, and unit cache; sockets are only consumers. Fold pending
   change and timer into one bounded coalescer with earliest-before/latest-after
   semantics and a maximum wait.
3. Delete listener booleans and install stable Datahike listener keys. Add route
   projection invalidation to the same connection listener bus.
4. Give the wire feed adapter a real start/stop/reattach state machine using the
   full database coordinate. Bound own-write correlation while disconnected.

### P1 — make cold boot one validated reconstruction

5. Build the complete Malli candidate from database program/schema facts,
   validate it off to the side, atomically swap it, then instrument the complete
   program once. Incremental definitions update through the same candidate
   mechanism.
6. Make one database attachment own `db/*conn*`, config projection, error sink,
   listener bus, and transport adapter. Delete `!agent-conn`, the REPL database,
   injected config/error callbacks, and whole-database identity caches.
7. Replace `!loop-input` with explicit runtime services plus database-derived
   agent identity/config. Delete process-run liveness and derive exact result
   availability from the bounded result cache.

### P2 — delete duplicate paths and own resource shutdown

8. Delete heartbeat, legacy `/sse`, `/data/sse`, filtered-database handles,
   writer agent routing, mutable writer hook/augmenter registries, legacy SCI
   source fallback, and production test override registries.
9. Add lifecycle cleanup for HTTP/feed sockets, writer subscribers, reconnect
   timers, the one ticker, shell process groups, compiler workers, and Gemini.
10. Move test population/detail support to the test build and replace mutable
    test seams with explicit fixtures.
11. Replace unbounded warn-once sets with bounded throttles and audit every
    retained cache for a numeric bound, full coordinate/generation key, and no
    retained database object.

This order replaces mechanisms in place. It does not introduce a second
runtime component system, event bus, cache path, or compatibility namespace.

## Mechanical proofs

| Claim | Mechanical proof | Failure caught |
| --- | --- | --- |
| No cross-agent timeout routing | Start two eval fibers with different budgets; make the short one settle after the long one arms its budget. Assert each observes its own deadline repeatedly under randomized ordering. | global `!next-budget-ms` theft/reset |
| One compiler initialization | Invoke `ensure-bootstrap!` concurrently from many callers and count actual initializer calls; all callers receive the identical completed generation, and a failed candidate leaves the prior generation active. | check-then-await duplication and torn state/version pair |
| Atomic schema restore | Seed one invalid schema reference among valid facts; boot must reject before registry swap. Remove it, boot, and prove every expected schema resolves from one generation. | partially visible Malli registry and load-order authority |
| Exact error-buffer attach | Record a pre-connection error, attach the database, emit no later error, and query that exact error once. Force one failed flush and prove retry preserves order without duplication. | early faults stuck forever or reordered/lost |
| Listener idempotence | Inspect the connection listener map in a test fixture while repeatedly opening/closing/reloading views. Stable keys replace in place; zero subscriptions leave no UI listener. | stale boolean, duplicate listener, invalid-feed leak |
| Shared subscription authority | Open two equivalent agent feeds, perform a structural change that adds a dependency, close the first socket, change the new dependency, and prove the second receives the patch. | first-socket dependency authority bug |
| Coalescer has a hard latency bound | Commit structural changes continuously beyond the debounce interval and assert a broadcast occurs by configured maximum wait with earliest `db-before`, latest `db-after`, and unioned effective attrs/datoms. | perpetual trailing-debounce starvation |
| Route facts are live | Transact a temporary route fact, request it without calling `rebuild!`, retract it, and prove it immediately returns not-found. Assert no unrelated transaction rebuilds the router. | stale router and broad invalidation |
| Adapter reattaches cleanly | Attach connection A, stop, attach B, and use branches that share a numeric `t`. Prove replay resumes from B's full coordinate, A's socket/timer is closed, and each listener sees each commit once. | `started?` sentinel, bare-`t` collision, leaked reconnect loop |
| Own-write correlation is bounded | Disconnect pub delivery while allowing many request replies, then reconnect/replay. Assert exact listener delivery and a fixed maximum correlation-map size throughout. | unbounded `!transactions` growth and duplicate/missing wake |
| Cache keys do not pin databases | Static check all cache entries plus a heap-retention probe after many commits/as-of views; keys contain generation/full coordinate and old database values become collectible after views close. | whole-DB identity caches and historical feed pinning |
| Result cache is the liveness truth | Insert beyond cap, settle old Promises late, restart, and query old/new `result/<id>` refs. Exact cache members resolve; evicted/prior refs return the graceful-miss value regardless of run id. | `!runs-this-process` false liveness and split cache structures |
| Shutdown leaves no live resources | After `down`/reset, inspect Node active handles, JVM threads, socket files/FDs, and process groups. No ticker, reconnect timer, SSE response, subscriber channel, child process, worker, or Gemini client remains. | reset-only cleanup and reboot-dependent correctness |
| Production excludes test state | Inspect the production module/dependency graph and start a pod without test preloads. No `!indexed-test-vars`, web override atoms, embed counter, or full run stash is reachable. | test-suite/runtime tax in normal agents |
| Static state budget stays explicit | Maintain a small reviewed allowlist of process-lifetime mutable cells with owner, bound, and stop transition. Fail on new top-level `defonce`/`atom`/`volatile!` outside that inventory; do not assert context wording. | accidental new registry/cache mechanism |

The static check should classify new state rather than ban all mutation. A new
socket parser atom can be legitimate; a new agent-id-to-route map is not. The
behavioral proofs above are the authority.

## Expected end state

After this refactor, a cold process can reconstruct everything meaningful from
the database and immutable launch inputs. The process-local remainder is small
and honest:

- one database attachment and listener bus;
- one validated Malli/compiler generation;
- one fiber-local scope mechanism;
- one ticker;
- one normalized subscription registry with bounded unit caches and socket
  consumers;
- one bounded result cache; and
- lifecycle-owned external resources.

A crash may discard those resources without losing facts. Restarting recreates
them from the database, fences interrupted runs idle, and lets root derive the
recovery notice from committed facts. That is the reliability boundary; trying
to persist or replay opaque runtime effects would make recovery less honest,
not more complete.
