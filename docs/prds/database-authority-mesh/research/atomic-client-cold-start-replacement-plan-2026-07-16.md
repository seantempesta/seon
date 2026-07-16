---
title: Atomic client cold-start replacement plan
type: research
status: complete
tags: [research, prd, database, pod, flow, cljs]
---

# Atomic client cold-start replacement plan

## Decision

Replace the pod's local Datahike connection with one process-owned
`seon.db` session only when the complete client lifecycle is session-native:
cold open, provenance and schema admission, preconditions, seed/reconcile,
program reconstruction, recovery and resume, restore proof, web readiness,
quiescence, and stop. The cut must be atomic because the current lifecycle
passes a local connection or immutable Datahike value through every one of
those stages. Opening the session while any later stage still dereferences the
returned value merely changes the failure from startup to the next consumer.

The target needs no new transport, database replica, connection facade, cache,
or request language. Existing coordinate-pinned `query`, `pull`, `pull-many`,
`installed-schema`, `execute-many`, `transact!`, `listen!`, and
`head-coordinate` operations cover the lifecycle. The missing work is to make
the application acquisition boundaries asynchronous and their inner
transformations pure.

The first implementation cohort after writer schema admission lands is
session-native provenance plus runtime schema admission. It is dependency
ready, closes the first real cold-start boundary, and gives the remaining
cohorts a valid database on which to operate. It is not permission to switch
`open-database-connection!` early; the final switch waits for all cohorts in
this report.

## Dependency ledger

| Dependency or mechanism | Selected source | Interior fact used here | Existing Seon owner |
|---|---|---|---|
| Maintained Datahike | `reference-code/datahike` at `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` | transactions serialize per database; immutable database values support concurrent reads; the maintained transaction reducer admits schema before later facts in the same request | `seon.db.writer`, `seon.db.datahike.schema` |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | native Unix sockets support persistent multiplexed process sessions and write/drain backpressure; ordinary environment maps remain compatible with the launch descriptor | `seon.db.transport.uds` |
| shadow-cljs | `reference-code/shadow-cljs` at `4e72595f57618f5c43388ad13d5136cd3bede566` | `:node-script` describes the generated server artifact, not the executable that runs it | current pod build |
| ClojureScript | `1.12.145` | `^:async`/`await` contains Promise effects at outer acquisition boundaries while pure inner functions remain ordinary CLJS | existing async Seon functions |
| Direct database facade | current `src/seon/db.cljs` | one acquired session, explicit coordinates, concurrent in-flight requests, execute-many, transactions, interests, and physical close | `seon.db` |
| Current lifecycle | root commit `5cfceff0` | the stable client still uses the local replica; incomplete session cut `068c41a3` was reverted | `seon.client` |

Datahike source grounding is in
`reference-code/datahike/src/datahike/schema.cljc` and
`reference-code/datahike/src/datahike/db/transaction.cljc`. The writer-side
schema bridge and admission owner are
`src/seon/db/datahike/schema.clj` and `src/seon/db/writer.clj`. The Bun socket
owner is `src/seon/db/transport/uds.cljs`; Bun-native values end there. Every
client acquisition receives recursively ordinary namespaced data.

## Why the JVM is not a read gate

The process session is a lifecycle and transport owner, not a sequential work
queue. The current Bun transport correlates concurrent requests by the
existing request ID and permits 16 requests in flight on one session. Separate
Bun child processes have separate sessions. On the authority, independent
reads retain immutable Datahike values and can execute concurrently;
`execute-many` additionally launches independent members against one exact
coordinate and returns them in input order. Only commits serialize per
database, as Datahike already requires.

This gives the intended division:

- one durable Datahike writer per database orders commits;
- many Bun children query the same immutable coordinate concurrently;
- Datahike computes and caches shared query work at the authority rather than
  reconstructing indexes in every child;
- each long agent/provider call retains only ordinary input data, not a
  database value or cache lease; and
- addressed interests wake only the owners whose declared attributes changed.

Multiple clusters remain independent databases and therefore independent
commit domains. A single JVM process may host those database authorities
without forcing their writes through one cross-cluster queue. Process failure
is also contained: a Bun child losing its session releases that child's
acquisitions and interests; it does not terminate the JVM or another child.

## Exact current call graph

### Process entry and cold start

`-main` calls `start-runtime!`, which synchronously claims the retained
`starting` phase and awaits `start-runtime-impl!`.

The unattached branch currently performs this ordered graph:

1. `open-database-connection!`
   1. reads `replica/process-launch-descriptor`;
   2. `replica/ping!`;
   3. `replica/ensure-database!`;
   4. local `d/connect` over the shared Konserve store;
   5. when autonomous, `db/ensure-provenance!` with the local connection;
   6. `install-runtime-schema!`, which submits raw Datahike declarations;
   7. `replica/attach!` to the transaction feed.
2. Install the connection at the root of `db/*conn*`.
3. `validate-restore-attachment!` dereferences it for the head coordinate and
   installed completion schema.
4. `db/assert-preconditions!` inspects local history and provenance schema.
5. Start `repl/ensure-bootstrap!`; compilation overlaps database bootstrap.
6. On ordinary autonomous cold start, `boot-seed!`:
   1. `core-program-tx` reads one local database value and runs the complete
      compiled-program diff through direct `d/q` calls;
   2. transact `seed-core!`;
   3. transact the program diff;
   4. optionally `apply-config!`, which invokes `state/reconcile!` against the
      local connection.
7. `recovery/recover!` reads and repairs incomplete durable agent work.
8. Read root-home existence; call `agent/create!` and
   `agent/ensure-initial-agent!`; derive created IDs.
9. Await compiler bootstrap; derive resumable and all agent IDs from `@conn`.
10. Open program publication, then `replay-program-graph!`:
    1. read schema forms and function contracts;
    2. activate the pure schema projection;
    3. read authored namespaces and stored require edges;
    4. reconstruct namespace sources from the same database value;
    5. topologically order and evaluate them.
11. Prepare or publish the reconstructed program and instrumentation.
12. For autonomous start, `agent/resume!` each resumable ID.
13. For restore, record completion and validate it against `@conn`.
14. Start `web.serve`; restore uses its readiness-only path.
15. Synchronize provider/brand data and install the agent ticker.
16. Publish the `running` phase.

### Attached refresh and development rehost

The attached branch reattaches the replica feed, checks replica catch-up for
restore, validates retained completion against `@conn`, derives resumable IDs,
and starts the web surface. `rehost-agent-runtimes!` separately dereferences
`db/*conn*`, derives resumable IDs, and resumes each process-local runtime.
`runtime-advertisement` also synchronously derives resumable IDs from the root
connection.

### Quiescence and stop

`quiesce-runtime!` closes executable admission and calls
`drain-runtime-owners!` while leaving HTTP alive for its result. Direct
`stop-runtime!` stops HTTP first and invokes the same drain without a returned
coordinate.

The drain is:

1. uninstall ticker and database wake triggers;
2. repeatedly run `agent-run/quiescence-work @conn`;
3. close idle current runs, re-read current/running work, and wait for running
   turns to reach terminal durable states;
4. read terminal turn entities from `@conn`;
5. unhost every process-local agent runtime;
6. detach the replica feed;
7. optionally read the final head coordinate;
8. detach program admission and its active schema projection;
9. release the local Datahike connection;
10. clear `db/*conn*` and retained lifecycle state.

## Replacement map

| Lifecycle stage | Current local dependency | Session-native replacement | Smallest missing application seam |
|---|---|---|---|
| Open | replica ping/ensure, `d/connect`, feed attach | `db/open-session!` with the validated launch selection; its capability negotiation, ensure, and acquire response is the attachment proof | client projects the launch descriptor into the existing open-session request; no new protocol operation |
| Provenance | `ensure-provenance!` examines `@conn` and directly calls Datahike for the unattributed genesis | read installed schema plus root/process/user presence at one coordinate; submit canonical schema facts and missing genesis entities in one unattributed transaction; ensure the human in a second root/boot-attributed transaction at the first response coordinate | make `db/ensure-provenance!` session-native; retain its existing result names and idempotency |
| Runtime schema | `pod-full-schema` sends raw `:db/*` declarations through the local bridge | submit canonical `:seon.schema/key`/`:seon.schema/form` rows and generator policy data; writer schema admission prepends the needed Datahike declarations in the same transaction | replace `install-runtime-schema!` input with canonical rows; delete `pod-full-schema` from the client |
| Preconditions | local connection config and installed provenance attrs | `installed-schema` at the bootstrap coordinate plus writer/open capabilities for history | turn `assert-preconditions!` into an async ordinary-data check or fold it into the bootstrap acquisition; no protocol addition |
| Restore attachment | `@conn`, synchronous head and installed schema | compare launch expected/forced coordinates with `head-coordinate`; request completion attrs with `installed-schema` at that coordinate | make validators accept ordinary coordinate/schema maps |
| Core program diff | `d/db`, seven direct queries, entity/history traversal, require-edge pulls | one coordinate-pinned `execute-many` acquisition of stored functions, schemas, namespaces, provenance ownership, agent homes, and require edges; run the existing diff as a pure transformation; transact the result with that expected coordinate | extract `core-program-tx-data` from acquisition; no generic remote diff language |
| Seed | ambient `db/*conn*` and explicit conn arguments | ordinary remote transactions in the existing root/boot context; each dependent read uses the previous transaction response coordinate | remove connection arguments and root rebinding |
| Config reconcile | `state/reconcile!` reads a local database value | acquire current managed identities/fields at one coordinate, compile the exact reconcile transaction purely, transact with the expected coordinate | split `state/reconcile!` into async acquisition plus its existing pure desired/current diff |
| Recovery | `recovery/recover!` uses ambient synchronous reads | one coarse coordinate-pinned acquisition of incomplete work followed by existing remote repair transactions | make recovery outer function async over ordinary data; keep repair decisions pure |
| Agent birth and ID selection | local entity/query/derive helpers | coordinate-pinned query/pull operations; use transaction coordinates from births for following reads | async acquisition functions in `seon.agent`; do not pass a database handle through resume |
| Program replay | one frozen local database value consumed by schema, graph, edge, and source helpers | one `execute-many` at an explicit coordinate returns canonical schemas, contracts, namespace rows, edges, and source/member rows; pure projection activation, reconstruction, topo sort, and evaluation remain local | add `acquire-program-graph!`; change replay's input from conn to ordinary graph data |
| Restore completion | synchronous readiness over `@conn` | record transaction response coordinate, then acquire completion and admission evidence at exactly that coordinate | make `db.restore/readiness` consume ordinary completion/schema/admission evidence |
| Web readiness | replica caught-up status | acquired session + completed bootstrap + acknowledged route interest; restore also requires exact completion evidence | remove replica status from `web.serve`; use existing listen acknowledgement |
| Development rehost and advertisement | synchronous resumable-ID derivation from `db/*conn*` | async resumable-ID query; cache only the last ordinary advertisement projection if the synchronous MCP surface requires it, invalidated by its addressed interest | make rehost async; keep advertisement state process-local and derived, never a database authority |
| Quiescence scan | two calls to `agent-run/quiescence-work @conn` per loop | one `execute-many` containing the current-run and running-turn queries at one coordinate; after closes, repeat at a newly resolved head | async `agent-run/quiescence-work!` returning its coordinate and ordinary vectors |
| Terminal turn status | entity lookup per observed turn | one `pull-many` for all observed turn IDs at the final empty-work coordinate | make `settled-turns` pure over pulled maps |
| Final coordinate and stop | replica detach, local release, root conn clear | drain/unlisten all owned interests, optionally `head-coordinate`, detach program projection, then `db/close-session!` | session close is the physical inverse; no release-database administration request |

## Provenance and schema transaction order

Genesis is the one cold-start case that cannot pretend provenance already
exists. Preserve the current two-layer meaning without preserving its local
Datahike implementation:

1. At coordinate `C0`, acquire installed schema and presence of root, each
   canonical process, and the human user. `execute-many` can group these reads
   once the minimal attributes are installed; a truly empty store reports an
   empty schema, so the first request may use the schema response alone.
2. Submit one explicitly unattributed transaction containing canonical schema
   rows for the genesis attributes and any missing root/process entities. The
   writer's schema-admission reducer derives and prepends the required
   Datahike declarations before the later facts in this same request. Let the
   response coordinate be `C1`.
3. At `C1`, verify lookup targets as needed and submit the missing human entity
   in the existing root/boot transaction context. Let its response coordinate
   be `C2`.
4. Submit canonical runtime schema and generator-policy rows at `C2`; writer
   admission installs attributes actually used by the request and retains
   canonical forms for lazy first use.

Retries re-read and emit only the gap. Do not install every registered Malli
shape as a Datahike attribute: request/entity shapes are not database
attributes. Do not keep raw `:db/*` declaration acceptance as a client API;
it is temporary migration input at the writer.

## Coordinate law for the lifecycle

One logical acquisition observes one immutable coordinate. Independent reads
for that acquisition use `execute-many`; dependent reads follow a successful
transaction at its returned coordinate. A later lifecycle stage may
intentionally acquire a later head, but it names that coordinate rather than
mutating process-global "latest database" state.

This is especially important in three places:

- program replay activates schemas and reconstructs every namespace from one
  coordinate, so code and contracts cannot tear;
- an agent/provider call releases database acquisition before the slow call
  and performs post-write reads at the transaction coordinate; and
- quiescence deliberately advances coordinates between close attempts, then
  proves an empty work projection and terminal turn states together.

The shared Datahike cache is therefore naturally useful: simultaneous clients
asking equivalent queries at the same committed coordinate reuse the
authority's cache identity and single-flight work. Readers going out of scope
need no Bun-side tombstone or snapshot lease; they retain only ordinary
results, while authority cache eviction remains governed by Datahike's bounded
cache and active work.

## Implementation and deletion order

### Cohort 1: establish a valid cold database

1. Land and prove writer canonical schema admission, including same-request
   schema rows followed by facts, generated identity policy, incompatible
   installed-shape rejection, and idempotent replay.
2. Make `db/ensure-provenance!` session-native and preserve the two genesis
   transaction layers.
3. Change runtime schema installation to canonical schema rows and generator
   facts; remove raw Datahike declarations from this client path.
4. Add a real writer/UDS cold-empty-store integration test. Fake only the
   physical session in facade unit tests; do not stub the preparation owner
   whose behavior is under test.

### Cohort 2: bootstrap acquisition

1. Make preconditions and restore attachment checks async over coordinate and
   schema data.
2. Extract pure core-program and config reconcile compilers; acquire all
   current facts with coordinate-pinned execute-many calls.
3. Convert seed, recovery, root/initial birth, and ID selection to session
   operations.
4. Add cold, converged restart, partial-schema repair, and incompatible-schema
   tests.

### Cohort 3: reconstruction and runtime owners

1. Add one program-graph acquisition and pass only ordinary data into replay.
2. Convert recovery/resume and their downstream agent/database helpers.
3. Replace replica-based restore and web readiness with completion evidence
   plus acknowledged route interest.
4. Convert rehost and runtime advertisement without introducing an ambient
   connection or synchronous remote facade.

### Cohort 4: inverse lifecycle

1. Convert quiescence work to execute-many and terminal status to pull-many.
2. Ensure every web/route/wake interest has an explicit owner and is
   unlistened or physically session-owned before close.
3. Replace replica detach and local release with `db/close-session!` after
   agent/web drain; preserve retry-safe retained lifecycle evidence.

### Atomic client reachability cut

Only after Cohorts 1–4 are source coherent:

1. switch `open-database-connection!` to `db/open-session!` and return ordinary
   session/open evidence;
2. delete every `replica/*` call and import from `seon.client`;
3. delete `datahike.api` and `konserve.node-filestore` imports from the client;
4. move local isolated database fixtures out of production; delete
   `mem-db`, `open-agent-conn!`, `pod-full-schema`, connection schemas, and
   explicit `:seon.db/conn` inputs;
5. delete root mutation/dereference of `db/*conn*` and all database-value
   arities reachable from client lifecycle;
6. rewrite `client_runtime_test` around the session and real ordinary protocol
   responses; and
7. coordinate the larger replica/publisher deletion described in
   [[atomic-replica-publisher-deletion-audit-2026-07-16]].

Git is the only compatibility archive. Do not leave a mode switch, local
fallback, `foo-v2` namespace, or synchronous wrapper around Promises.

## Proof plan

### Focused behavioral proof

- A real empty writer database accepts canonical genesis schema plus root and
  process facts, then attributed human/runtime facts; restart emits no write.
- Same-request canonical schema followed by a fact using that attribute
  succeeds atomically; an incompatible installed shape returns
  `:seon.error/kind :user-input` and leaves the session usable.
- Cold start reaches web readiness with one acquired session and an
  acknowledged route interest. There is no publish socket or replica status
  in the proof.
- Program schemas, contracts, namespaces, edges, and source are acquired at
  one coordinate; replay failure remains isolated per namespace.
- Restore refuses a mismatched forced/head coordinate, missing completion
  schema, failed reconstruction, or absent interest acknowledgement.
- Quiescence observes/ closes current runs, waits for running turns, classifies
  terminal turns with one batch read, records its final coordinate, drains
  interests, and only then closes the session.
- Transport loss during bootstrap or drain yields a retryable lifecycle error
  and never silently falls back to local Datahike.

### Final reachability falsifier

The atomic client cut has not happened until this search returns no
production-reachable match in `src/seon/client.cljs`:

```bash
rg -n 'seon\.db\.replica|datahike\.|konserve\.|db/\*conn\*|@conn|:seon\.db/(conn|db)|d/(db|q|connect|create-database|transact!)|open-agent-conn!|pod-full-schema' src/seon/client.cljs

```

Then search all production CLJS for the obsolete authority shapes and account
for every result before deleting the replica:

```bash
rg -n 'seon\.db\.replica|db/\*conn\*|:seon\.db/(conn|db)|entity-lazy|at-coordinate|captured-read|datahike\.api|konserve\.node-filestore' src

```

The compiled pod dependency graph must contain no CLJS Datahike, Konserve,
replica, publisher, or connection owner. Protocol/facade tests must prove every
returned value is recursively ordinary; client runtime tests must prove open,
bootstrap, web interest, quiesce, and close ordering. The final graduation
checkpoint is the focused client/session/writer suites, complete CLJS and
writer gates, operator tests, and one live cold start, converged restart,
planned quiesce, and stop with the default Bun pod.

## Earliest dependency-ready implementation boundary

After writer schema admission lands, implement one coordinated slice:

1. session-native `seon.db/ensure-provenance!`;
2. canonical client runtime schema admission with no raw Datahike declarations;
3. async schema/precondition evidence at the returned coordinate; and
4. a real empty-store and converged-restart writer/UDS proof.

This settles the first cold-start contract. The next boundary is the pure
core-program/config diff acquisition. The client open switch remains deferred
until the reconstruction, runtime-owner, and inverse-lifecycle cohorts are all
ready for the same atomic source cut.
