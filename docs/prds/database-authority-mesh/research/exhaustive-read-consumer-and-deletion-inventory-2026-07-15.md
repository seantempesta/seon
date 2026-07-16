---
type: research
status: active
tags: [research, prd, database, flow, agent]
---

# Exhaustive read-consumer and deletion inventory — 2026-07-15

## Decision this audit supports

Unit 7 migrates every Bun-side database read to one coordinate-pinned authority
session. Unit 9 then deletes the local Datahike replica, full transaction feed,
replay and Node socket paths atomically. There is no remaining UI-replica
option: [[roadmap]] requires direct authority reads for agents and the web UI,
and its deletion exit requires that Bun retain no Datahike connection or index.

This report extends [[remote-read-consumer-classification-2026-07-15]] with an
exhaustive namespace/line inventory, exact Datahike semantics, Datastar
implications, migration batches, and deletion reachability. Line numbers name
the inspected revision `7319ad18`; function/namespace ownership is durable.

## Dependency ledger

| Dependency or mechanism | Selected source | Relevant seam |
|---|---|---|
| Seon database API | `src/seon/db.cljs` | sole application interface; query, pull, entity, temporal, index, observation and listener paths |
| Datahike | `reference-code/datahike` at `092f5b05` | immutable database values, Datalog, pull-many, lazy Entity, temporal wrappers, exact-value cache and single-flight |
| Datahike Entity | `reference-code/datahike/src/datahike/impl/entity.cljc:17-218` | retains its database value and mutable local attribute cache; cannot cross the protocol |
| Datahike pull | `reference-code/datahike/src/datahike/pull_api.cljc:339-362` | `pull-many` already executes one parsed pattern over many eids with shared resource limits |
| Datahike query | `reference-code/datahike/src/datahike/query.cljc:120-149,4177-4253` | ordinary result, cache/resource evidence, request-id cancellation and identical-query single-flight |
| Datahike temporal values | `reference-code/datahike/src/datahike/api/impl.cljc:145-187` | `since`, `as-of`, and `history` return wrappers over one immutable origin db; history is idempotent |
| Datahike listeners | `reference-code/datahike/src/datahike/core.cljc:199-215` and `connector.cljc` | keyed connection-local listeners; current Bun listeners exist only because Bun owns a replica |
| Datastar shared units | `src/seon/web/view_unit.cljs`, `src/seon/web/datastar.cljs` | one rendered unit shared by consumers, exact read replay, changed-attribute routing and SSE delivery |
| Current replica | `src/seon/db/replica.cljs` | local Datahike connection, remote writer, response/feed correlation, replay, reconnect and native listener synthesis |
| Current transport | `src/seon/db/transport/uds.{clj,cljs}` | request-per-socket RPC plus JVM full-transaction publisher; both superseded by persistent native sessions |
| Launch/runtime | `src/seon/launch.cljc`, `src/seon/client.cljs`, `script/seon/dev/config.clj` | currently advertises request and publish sockets and gates runtime on replica/feed health |

## Datahike laws that constrain migration

1. A database is the immutable value to share within one authority operation.
   Every member of `execute-many` must resolve one attachment and coordinate
   once, then receive that same host-local value. Re-resolving head per member
   breaks current functions that deliberately thread one `db` argument.
2. `Entity` is not a map. It retains the database value plus volatile touched
   and attribute caches; attribute lookup can perform index reads and produce
   more lazy Entity values for refs. Remote `entity-lazy` is therefore invalid.
   Replace it with existence/scalar queries or bounded pull data. `entity`
   already touches into a map, but its callers should normally use a precise
   pull rather than serialize every attribute.
3. Datahike already owns bulk pull. Current query-then-`map pull` sites should
   use a Datalog pull expression or `pull-many`; Seon should expose that
   existing operation instead of issuing N protocol members.
4. Query results over one exact committed identity already share completed
   cache entries and identical in-flight computation. `execute-many` should not
   add a second result cache or duplicate suppression layer.
5. Temporal wrappers remain host-local. The wire names `history`, `as-of`, or
   `since` as member data against the outer coordinate; it never serializes a
   temporal database value for a later request.
6. Keyed Datahike listeners are connection-local callbacks. Once Bun loses the
   connection, the replacement is a selective authority interest delivering
   committed coordinate plus bounded changed-attribute/datom evidence. It is
   not a recreation of the global full-transaction broadcast.
7. An authority result must be recursively ordinary namespaced data. Database,
   connection, Entity, Datom implementation, function, derefable, Future,
   Throwable and host stream values fail materialization before encoding.

## Whole-tree classification

The executable production search found no `pull-many` consumer today. It found
four temporal/history cohorts, three keyed-listener owners, two bounded index
consumers, and 60-plus namespaces using query/pull/entity/schema reads. The
right migration unit is the coarse computation, not each leaf call.

### Context, render and Datastar — one outer read plan

| Owner | Current reads | Migration |
|---|---|---|
| `seon.agent.ctx` and `ctx/*` | agent/config entities, namespace/function/schema/test graph, transcript, subagents, menu, canvas, warnings, history | one asynchronous context plan at a coordinate; consolidate lookup probes and bulk pulls; pure render functions consume returned data |
| `seon.render`, `render.surface`, `render.system`, `render.canvas`, `render.chat`, `render.sci` | renderer selection, agent/process/message/run graph, fleet N+1 reads, canvas/user data, history | one surface/system plan; fleet relation or measured named projection; no Promise below render walker |
| `seon.web.datastar` and `seon.web.view-unit` | post-transaction pull, listener, captured-read replay | one database-scoped authority interest plus coordinate-pinned grouped reads; retain shared unit identity/output once, replace local replay with returned dependency evidence |
| `seon.web.router`, `web.brand`, `web.reactive.call`, `web.debug` | routes/schema, brand, capability gates, debug presence | grouped route/config projection cached by coordinate; small request-specific queries remain async members |
| `seon.web.serve` | clear sets, model attempts, eval evidence, task window, historical views | request-window/evidence groups at explicit coordinates; collapse per-attempt/per-eval pulls; mutations carry expected coordinate |

Datastar must not subscribe once per browser or agent. Its current
`view-unit` owner already shares one derived unit and serialized element among
all consumer feeds. Preserve that ownership in the Bun UI host: one selective
database interest supplies committed coordinate and changed attributes; the UI
host asks once for every candidate shared unit, then fans one serialized morph
to its interested browser sessions. The authority computes shared database
reads once; Bun computes and serializes the CLJS render once. Browser count must
not multiply queries, renders, or authority interests.

Current `capture-reads`/`read-observation-changed?` re-executes captured local
reads against the new replica value. After the cut, exact replay in Bun would
be a second query scheduler and extra round trips. The closer seam is for an
`execute-many` result to include bounded query dependency/cache evidence under
the same member result. Changed-attribute routing selects candidates; a
coordinate-keyed plan is rerun once only when relevant. Equal returned data or
equal serialized output still suppresses the morph. Delete database-value
normalization and local semantic replay after this behavior is proven.

### Agent execution and coordination

| Owner | Current reads | Migration |
|---|---|---|
| `seon.agent.turn` | head coordinate and completed turn pull | pre-provider context coordinate and post-commit evidence coordinate are deliberately separate; pull result remains ordinary data |
| `seon.agent.loop`, `run`, `runtime`, `lifecycle`, `schedule` | agent/run/message state, stale/overdue scans, wake listener | one run-control plan per decision; committed interest wakes the child; writes retain request-id and expected-coordinate/CAS fences |
| `seon.agent.message` and `.internal` | bounded recent index scans, pulls, sender/recipient graph | one message-window projection using index page plus pull-many/query; no per-peer entity calls |
| `seon.derive` | agent/run/message relations for state transition | one derive input plan; keep `derive-state` pure over ordinary returned data |
| `seon.agent.debug`, `.testrun`, `.web.internal` | historical turn/error/test/evidence reads | grouped bounded evidence; name a heavy authority projection only if measured query/wire cost warrants it |
| `seon.agent.search.internal` | code graph literal queries | share the code-corpus read owner with eval/context rather than a second scan |

Transaction callbacks must not issue a request for every datom. The authority
interest carries enough indexed change evidence to decide which durable agent
or shared unit is relevant; the chosen child/UI owner then submits one
addressable grouped read. A slow or crashed child never blocks the writer or a
sibling interest.

### Eval, compiler, autocomplete and boot

| Owner | Current reads | Migration |
|---|---|---|
| `seon.eval` | recent index pages, namespace/function/schema graph, source, entities and installed schema | fetch one coordinate-keyed code projection before synchronous compiler work; retain bounded ad hoc inspection operations |
| `seon.client` | boot schema/forms/contracts/ns sets, head coordinate, runtime entity graph | same code projection; session/capability health replaces replica/feed readiness |
| `seon.instrument` | function specs and render coordinate | consume code projection and explicit coordinate, never an ambient replica deref |
| `seon.repl.autocomplete` | corpus symbols/records, agents, turns, schema, historical export | one cancellable export projection; interactive symbol lookup remains a small async pull |
| `seon.ai.typeahead` and `ctx.typeahead-steps` | function specs, eval calls, lazy entities | consolidate into code/typeahead projection and eliminate lazy Entity probes |

The analyzer and compiler stay in Bun; only immutable database selection and
graph extraction move to the authority. This avoids Promise contagion through
CLJS compiler callbacks while deleting repeated corpus scans.

### Toolkit and agent-authored reads

`my.blob`, `my.canvas`, `my.data`, `my.kb`, `my.ns`, `my.plan`, `my.skills`,
and agent-authored `seon.db` calls remain the open-ended surface. Query, pull,
pull-many, bounded index pages, temporal query, and KNN are honestly async.
Agent top-level eval awaits a returned Promise; authored multi-read functions
use `^:async`/`await` or send one explicit `execute-many` request. No sync
compatibility shim may block Bun or secretly open a local Datahike database.

Obvious consolidations are `my.plan.internal` query-plus-pull loops,
`my.skills` existence-query then pull, `my.ns` query then pull, and `my.kb`
schema scan plus per-attribute/per-entity loops. Genericity does not preclude
batching: retain the caller's ordinary query/pull member shapes.

### Database browser, temporal and index work

- `seon.db.browser` requests installed schema plus a first bounded
  `index-datoms`/`rseek-datoms` page; later cursor pages remain independent but
  pinned to the same attachment/coordinate until the user refreshes.
- `seon.eval` and `seon.agent.message` are the only production bounded-index
  users beyond the browser. Preserve their forward/reverse and prefix semantics
  as protocol data; never return a lazy datom sequence.
- `seon.agent`, `ctx.render-fns`, `render.surface`, `runtime.recovery` and debug
  use `history`; historical web/autocomplete paths use `at-coordinate` and
  `as-of`. Compose the temporal selector inside the operation and return its
  query/pull result. Do not create a remote “historical db handle.”
- `basis-t`/`head-coordinate` becomes the resolved outer coordinate already
  returned by session attachment/`resolve-head`, not another leaf RPC.

### Heavy JVM-local work

`seon.embed.clj`, Datahike registry/writer/restore, and authority protocol
execution keep synchronous reads over explicit host-local database values.
`seon.embed.cljs` currently queries filter eids and pulls every KNN hit; move
filter resolution, native KNN and bounded pull enrichment into one KNN-class
authority operation. `seon.diffusion.retrieval` consumes that result instead of
building another graph/search path. Provider work stays async and never gates
exact reads or mutation.

## Exhaustive production call-site appendix

The following list contains executable application owners found by direct
search. Docstrings/examples, schema metadata such as `{:seon.db/entity true}`,
and `seon.db`'s own function definitions are excluded. Adjacent line numbers
often name one multiline call.

### Toolkit

- `src/my/blob.cljs`: schema 446; query 447,867; entity 869; coordinate 469,592.
- `src/my/canvas.cljs`: schema 113; pull 114,138.
- `src/my/data.cljs`: query-with-pull 74.
- `src/my/kb.cljs`: query 261,270,281,327; schema 321; pull 377,419; entity 430.
- `src/my/kb/shared.cljs`: query 79.
- `src/my/ns.cljs`: query 62; pull 69.
- `src/my/plan.cljs`: entity 445.
- `src/my/plan/internal.cljs`: entity 73,95,225,237,976,1321; query 78,189,212,248,263,273,292,302,390,813,826,879,944,968,988,1013,1168,1195,1433; pull 808,890,1171,1441; schema 940,986.
- `src/my/skills.cljs`: query 194,211,236,367; pull 371.

### Agent, context and derivation

- `src/seon/agent.cljs`: history-query 363; entity 459,461,567.
- `src/seon/agent/ctx.cljs`: entity 382,446,475,722,2622; lazy entity 405,1566,1726; query 1094,2325; pull 1127,1567,1582,2330; schema 379,444,473,720.
- `ctx/canvas.cljs`: query 36. `ctx/menu.cljs`: lazy entity 143,227; query 351; pull loop 360; schema 142,261,262,341,378,379.
- `ctx/namespaces.cljs`: entity 267,463; lazy entity 292,366,800; pull 293,802; query 482.
- `ctx/render_fns.cljs`: query 86,197,261,274,354,369; pull 208,351; entity 256,316; history 254,370; schema 194,314; basis 438.
- `ctx/subagents.cljs`: query 54,157; entity 80,191. `ctx/transcript.cljs`: entity 153; lazy entity 652; query 600; pull 1059.
- `ctx/typeahead_steps.cljs`: query 134 plus pull loop 141; schema 133. `ctx/warnings.cljs`: query 46,54.
- `src/seon/agent/debug.cljs`: query 104,195,376,426,481; pull 224,405; historical resolution 618.
- `agent/home.cljs`: entity 82/schema 80. `agent/internal.cljs`: entity 35,52. `agent/lifecycle.cljs`: query 86/entity 166,170.
- `agent/loop.cljs`: entity 151,455,463,470,838,852,856; query 841; listeners 685-705.
- `agent/message.cljs`: reverse/forward index 100,122,147; pull 134,154; entity 240; schema 118,146.
- `agent/message/internal.cljs`: entity 37,73,74; query 44,78.
- `agent/run.cljs`: entity 181,210,358,488,518,546,551,590,591,637,664,775,803,804; query 262,277,711,764.
- `agent/runtime.cljs`: schema 65/entity 67,101. `agent/schedule.cljs`: query 308,322,351/entity 364.
- `agent/search/internal.cljs`: query 418,426,433,440. `agent/testrun.cljs`: query 203/pull 216. `agent/turn.cljs`: coordinate 656,796/pull 857. `agent/web/internal.cljs`: query 554/entity 562.
- `src/seon/derive.cljs`: entity 97,100,115,202,344,390,459; query 138,161,183,223,243,275,328,346,378; schema 377.

### Eval, AI, render and web

- `src/seon/eval.cljs`: index 187,194,214; schema 185,213,2825,2828; pull 201,221,2840; entity 1629,3450; query 278,900,969,977,2670,2822,2881,2911,2969,3771,3783,4029,4886,4890.
- `src/seon/client.cljs`: query 956,1006,1018,2721; schema 2567; entity 2681,2916; coordinate 2540,2559,3048.
- `src/seon/instrument.cljc`: basis 119/query 850,983. `seon.ai.cljs`: query 551; entity 554,592,694,803; schema 590,692,801.
- `seon.ai/typeahead.cljs`: query 496,549,787; lazy entity 545; schema 493,494,786.
- `seon.render.cljs`: query 928/pull 1088. `render/canvas.cljs`: pull 410/query 511/schema 510,570. `render/chat.cljs`: query 134. `render/sci.cljs`: query 272,339.
- `render/surface.cljs`: entity 101; history-query 110; query 140,170,224,238,255,312. `render/system.cljs`: query 77,88,107,185,234,238,241/entity 181.
- `src/seon/repl/autocomplete.cljs`: lazy entity 81,259,423; entity 155; query 223,417,516; pull 260; schema 152,523; historical coordinate 575.
- `src/seon/runtime/admission.cljs`: query 177,187. `runtime/recovery.cljs`: query 71,88,106,255,265,280,297; history 318/entity 342/schema 104.
- `src/seon/state.cljs`: entity 94,123,147,279,296/schema 256. `seon.warn.cljs`: query 107,342,351,402,593,607,619,726,786,837,875,904,936; schema 372,461.
- `web/brand.cljs`: query 108/entity 111. `web/debug.cljs`: query 61 plus historical coordinate paths 1012-1091. `web/reactive/call.cljs`: query 77,93.
- `web/router.cljs`: schema 231/query 233/listener 425,437.
- `web/datastar.cljs`: post-tx pull 785; listener 889,898; agent existence query 1654; historical resolution 1878.
- `web/serve.cljs`: query 291,301,464,879,1129,1196,1251,1259,1295; pull 889,899,1141; entity 288,1248,1249; coordinate/temporal paths 605,788,802,954,1139,1272,1276,1568.
- `seon.db.browser.cljs`: schema 187,201,519; history 458; bounded index pages 465,466.
- `seon.db.restore.cljc`: schema 163,171,254; entity 164; query 172; coordinate/transaction resolution 198-205,268-272,350.
- `seon.diffusion/retrieval.cljs`: query 422/pull 457. `seon.embed.cljs`: query 142/pull 196. `handlers/message.cljs`: pull 45.

## Exact Unit 9 deletion inventory

### Delete outright

- `src/seon/db/replica.cljs`: all local connection construction, `RemoteWriter`,
  RYOW deref, own-write correlations, attachment generation, feed application,
  replay validation/buffering, reconnect timer and replica status.
- `test/seon/db/replica_test.cljs`: entire replica contract.
- `src/seon/db/transport/uds.cljs`: Node `net` request-per-socket RPC and
  publisher client after native persistent Bun sessions own framing.
- JVM publisher half of `src/seon/db/transport/uds.clj`: subscriber queues,
  `start-publisher!`, fanout/write pump and `close-publisher!`. Retain or replace
  only the authority's native persistent request/session server.
- Writer transaction-feed production and replay-only handlers in
  `src/seon/db/writer.clj`: `replay-transactions-page`, replay operation dispatch,
  publisher callback/event emission and feed-only protocol responses after no
  consumer remains. Durable receipts and transaction-coordinate resolution
  stay under authority operations.
- Replica observation replay in `src/seon/db.cljs`: captured database-value
  normalization, `replayable-read-operations`, `replay-read-result` and
  `read-observation-changed?` once authority dependency evidence proves shared
  Datastar invalidation. Keep only ordinary request/dependency data actually
  consumed by the new shared-unit owner.

### Rewrite surviving owners before deletion

- `src/seon/db.cljs`: remove `seon.db.replica` require; route transaction,
  coordinate, KNN and all reads through one persistent session while preserving
  the public semantic owner.
- `src/seon/client.cljs`: `open-database-connection!`, startup/refresh
  `replica/attach!`, status gates, detach and blob descriptor access become
  session attach/capabilities/health and clean session close. Program graph
  reconstruction survives but consumes remote ordinary data.
- `src/seon/web/datastar.cljs`, `web/router.cljs`, `agent/loop.cljs`: replace
  native replica listeners with one database interest and local keyed dispatch.
- `src/seon/embed.cljs` and `web/serve.cljs`: replace direct replica KNN and
  transaction-coordinate calls with their authority operations.
- `script/seon/dev/mcp.clj` and `src/seon/dev/runtime_id.cljc`: remove replica
  vocabulary but retain the one cluster/database-name derivation in its operator
  owner.

### Launch/config fields to remove or rename once sessions graduate

- `src/seon/launch.cljc`: remove `::publish-socket-path` from descriptor and
  writer-owner shapes. Replace `::request-socket-path` only if the persistent
  authority endpoint needs a differently named single field; do not retain both.
- `script/seon/dev/config.clj`: stop deriving `pub-sock` and publishing both
  request/publish paths.
- `script/seon/dev/process.clj`, `branch.clj`, `restore_state.clj` and their
  tests: remove accesses whose only purpose is the publisher/feed or replica
  attachment. Preserve the one ordinary launch descriptor and authority
  lifecycle coordinates.
- Remove replica readiness/status, feed reconnect delays/timeouts, replay page
  limits, own-write correlation limits and any environment/config toggles.

### Tests to delete versus migrate

Delete replica/feed behavior tests: `test/seon/db/replica_test.cljs`,
`test/seon/db/replay_test.clj`, and publisher/subscriber/feed cases in
`test/seon/db/transport_uds_test.clj`, `writer_integration_test.clj`,
`request_receipt_test.clj`, `generated_id_transaction_test.clj`, and
`transaction_coordinate_test.clj` after equivalent session/receipt/coordinate
proof exists. Do not delete receipt idempotency or coordinate semantics; move
those assertions to protocol/session tests.

Migrate runtime assumptions in `test/seon/client_runtime_test.cljs`,
`agent_lifecycle_test.cljs`, `embed_test.cljs`, `db/restore_test.cljs`,
`web/serve_test.cljs`, `dev/branch_test.clj`, and restore/process tests from
replica/feed readiness to session capability/attachment health.

Retain Datastar browser-feed tests, but rewrite their database input fixture:
`test/seon/web/datastar_test.cljs` and `web/view_unit_test.cljs` should prove one
authority grouped read and one shared render for multiple browser consumers,
selective invalidation, equal-result suppression, disconnect cleanup and no
full transaction broadcast. “Feed” in those files is browser SSE, not the
obsolete database transaction feed.

Program-source “replay” (`client/replay-program-graph!` and resume tests) is not
database transaction replay. It survives as boot reconstruction from ordinary
authority query results; rename only if later vocabulary cleanup is valuable.

## Ordered migration batches

1. **Settle read result materialization.** Prove query, pull, pull-many,
   bounded-index and temporal-result members reject host values and preserve
   Datahike result shapes at one coordinate. Falsifier: nested ref returns an
   Entity/Datom/DB/Future or two members observe different coordinates.
2. **Collapse obvious N+1 work locally first.** Convert query-plus-pull loops in
   fleet, context namespaces/menu, messages, web evidence, plans and KNN
   enrichment to query pull expressions or `pull-many`. Falsifier: protocol
   request count scales with returned entity count.
3. **Migrate context and shared UI.** Await one context plan per agent turn and
   one plan per candidate shared Datastar unit. Install one selective database
   interest for the UI host. Falsifier: a second browser doubles authority
   requests/renders or a Promise reaches Hiccup/context.
4. **Migrate run/turn/message/derive.** Each decision reads one coordinate;
   subsequent writes use request-id and expected-coordinate/CAS. Falsifier: a
   concurrent write makes a stale read-before-write decision commit.
5. **Migrate code corpus, boot and autocomplete.** Fetch bounded ordinary code
   data before synchronous compiler work. Falsifier: compiler callback performs
   a leaf RPC or boot repeats equivalent corpus scans.
6. **Migrate browser/debug/temporal and agent-authored APIs.** Make open-ended
   calls honestly async and temporal selectors operation-local. Falsifier: Bun
   retains a database/temporal wrapper or blocks its event loop for sync
   compatibility.
7. **Migrate KNN/embedding and remaining consumers.** Resolve filters, native
   KNN and enrichment at the authority; provider remains independent. Falsifier:
   eid candidates or N result pulls traverse the wire.
8. **Atomic deletion cut.** Remove replica, publisher, replay, Node adapter,
   observation replay, dual socket fields and compatibility branches together.
   Falsifier: reachability from Bun still loads Datahike/Konserve/PSS, opens the
   publish socket, handles full transaction events, or constructs a local conn.

## Acceptance proof

- A static call-site check accounts for every appendix owner: each is JVM-local,
  one migrated coarse async boundary, or intentionally open-ended async API.
- Root, agent, data and debug pages; context; turn execution; arbitrary query,
  pull/pull-many, temporal read, bounded index page, transaction and KNN work
  against a pinned coordinate with no Promise or host value in visible data.
- Two same-coordinate identical queries from different children show one
  Datahike computation; unrelated database reads overlap up to the shared fair
  read-worker limit.
- Multiple browser sessions for the same unit produce one authority read plan,
  one CLJS render/serialization and independent bounded SSE delivery.
- Interest selectivity proves an unrelated transaction causes no candidate
  query; an applicable transaction reruns each shared unit at most once; equal
  data/output emits no morph.
- Child/UI disconnect, cancellation and release drop request/interest/resource
  ownership without evicting a value still used by another session.
- `rg` reachability finds no `seon.db.replica`, publish socket, transaction
  replay/fanout, RYOW correlation, Node `net` adapter, or Bun Datahike opening.
- Package and density proof show no Node requirement, no source checkout, and
  no per-child Datahike indexes/caches/listeners.

## Consequential choices for Sean

Most migration choices are now dependency-grounded, not product choices. Three
tradeoffs still deserve explicit involvement after measurement:

1. Whether fleet, warning, debug and code-corpus plans remain transparent
   `execute-many` vectors or graduate as named projections after CPU/wire reuse
   evidence. Start transparent; name only stable measured heavy work.
2. Whether a failed member makes an independent `execute-many` return all member
   outcomes or fail the outer request. Independent reads should return every
   outcome; dependent plans should be expressed as one query/projection rather
   than protocol ordering.
3. Which default result/work/byte limits agent-authored reads receive on modest
   hardware. The protocol should expose the limits and evidence; defaults need
   workload measurements rather than guesses.
