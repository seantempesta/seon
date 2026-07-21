---
title: System recovery and graduation plan
type: research
status: active
tags: [research, prd, database, cljs, agent, flow]
---

# System recovery and graduation plan

## Outcome

Seon runs completely on one database-authority architecture:

- one JVM service owns Datahike connections, indexes, query computation, query
  reuse, listeners, and one write order per database;
- Bun hosts the web UI and isolated agent children;
- each child starts from its compiled CLJS package and applies current
  database-authored namespaces as whole compile units;
- domain data remains entities, attributes, and refs;
- `seon.db` is the only application database API;
- no Bun process opens or reconstructs a Datahike database; and
- no replica, transaction replay, compatibility runtime, or second renderer is
  reachable in production.

Graduation means the application starts, performs real agent and web work,
survives child failure, passes every maintained test gate, and then meets
measured latency, CPU, and memory budgets. Correctness and simplicity precede
performance tuning.

## Planning rules

1. Make it run correctly, then measure, then make the measured slow path fast.
2. Prefer one short owning function and ordinary namespaced data over adapters,
   compatibility arities, state machines, or new vocabulary.
3. Move a complete behavior at a time. Compiler warnings and stale tests are an
   inventory, not an instruction to patch individual call sites independently.
4. Delete a superseded mechanism in the same unit that replaces its last real
   consumer. Git is the compatibility archive.
5. When a unit becomes substantially harder than its behavior suggests, stop
   and perform an adversarial pass. Look first for a false ownership boundary,
   duplicate state, an unnecessary hop, or a stale assumption from the local
   Datahike era.
6. Do not optimize artifact size, codec bytes, warning counts, or isolated
   helper calls unless they block correctness or a measured graduation budget.
7. There is one implementation of each behavior. Never introduce a `v2`,
   `new`, `remote`, `legacy`, or compatibility function/namespace beside the
   existing owner. When two current systems overlap, name the surviving owner,
   migrate its complete behavior, and delete the other system.

## Data model invariant

The remote database cut does not convert domain entities into maps.

- Runs, turns, evaluations, attempts, messages, plans, blocks, schemas, and
  functions remain entities connected by refs.
- Transaction data continues to use entity maps, lookup refs, and Datahike
  transaction forms.
- A `:seon.db/db` value is a small immutable description of the database value
  used for one computation. It is request-scoped execution context, not the
  database contents and not a replacement for domain refs.
- A render captures one database value so all of its authority queries observe
  the same immutable state. The render itself remains derived and unstored.
- Durable reproduction evidence records the smallest native identity that
  proves the historical state. Do not persist a complete database-value map by
  default merely because it crossed the protocol.

## Existing mechanisms to keep

The replacement is already partly built. Strengthen these owners instead of
creating another path:

| Behavior | Existing owner | Keep because |
|---|---|---|
| persistent database session and cached latest database value | `seon.db` | omitted-database calls avoid a preliminary head request while explicit values remain immutable |
| concurrent authority requests and grouped reads | `seon.db.protocol`, `seon.db.writer` | the JVM resolves real Datahike values and executes independent reads concurrently |
| compiled package indexing | `seon.client/index-core!`, `index-schemas` | derives one canonical desired package population |
| exact package reconciliation | `acquire-core-program!`, `compile-core-program-tx`, `commit-core-program!` | computes complete add/change/retract data and creates no transaction when converged |
| database-authored namespace assembly | `seon.eval/namespace-source`, `authored-sources` | creates one deduplicated compile section per namespace instead of replaying forms |
| dependency loading | `seon.eval/load-authored-program!` and `cljs.js` | the compiler already owns require traversal, cycles, and load-once behavior |
| isolated execution and replacement | `seon.execution`, `seon.execution.host` | one child owns one compiler; a changed program digest uses fresh-child plus one retry |
| child supervision | existing process host and operator | a failed child cannot corrupt or terminate the database authority or another child |

## Current proven state

- The authority protocol, writer, persistent session, remote reads and writes,
  and cached latest ordinary database value have focused proof.
- Execution protocol version 3 carries the same ordinary database value through
  invocation and grouped program acquisition.
- Program visibility is database-wide rather than filtered by transaction
  author. Publication remains one accepted database transaction.
- Focused program loading proves one namespace section per namespace and no
  per-form replay.
- Schema registration now collects declarations without resolving forward refs
  against partial namespace load order. Complete projection validation remains
  the admission gate; the focused gate passes 4 tests and 17 assertions.
- The complete CLJS application is intentionally not green. The obsolete
  config-test cache, ambient connection, and second local Datahike fixture are
  deleted. The focused config artifact now compiles 307 files with 34 migration
  warnings and passes 22 tests/94 assertions. Those warnings identify the
  remaining derive, schedule, agent, render, plan, typeahead, home, and warning
  consumers; they are not permission to restore local connections.
- Run lifecycle reads and writes now use one ordinary database value, targeted
  CAS, native reports, and direct errors. The public snapshot helper, duplicate
  derive run readers, mutable construction, and embedded local-Datahike run
  test path are deleted; focused proof passes 8 tests/33 assertions.
- Ambiguous transaction delivery now retains and redelivers one exact request
  through the existing UDS pending owner and durable writer receipt. Explicit
  owner close stops recovery; focused UDS, facade, and writer proof passes 32
  tests/156 assertions. Listener-interest restoration remains a distinct
  unsettled owner.
- No current live-cluster proof supports a claim that the refactor is complete.

## Ordered implementation plan

### 1. Complete database initialization before admission

Goal: one initialization behavior creates or reconciles everything required by
the package before agents, schedules, or the web UI can run.

1. Keep physical database creation and connection publication in the JVM
   registry.
2. Supply one canonical compiled package snapshot from the Bun package.
3. Reuse the existing exact reconciliation functions to derive the transaction
   for namespaces, functions, schemas, native persisted attributes, and required
   initial facts.
4. Validate the complete Malli projection before committing behavior.
5. Commit one exact delta behind the acquired database value. An absent managed
   population naturally yields the full transaction; a partial population
   yields a delta; convergence yields no transaction. Do not add a separate
   empty-database program path.
6. Publish the connection/runtime only after the committed population can be
   reread and validated.
7. Delete unconditional schema and seed transactions once the one reconciler
   owns their desired facts.

Exit:

- fresh initialization installs every required native attribute before initial
  facts;
- a converged reopen writes nothing;
- an interrupted or invalid snapshot publishes no partially admitted runtime;
- forward schema references are independent of namespace load order; and
- runtime-authored program facts are not overwritten by package reconciliation.

### 2. Finish the isolated child runtime

Goal: every agent computation runs in its supervised child and needs no parent
compiler or local database.

1. A child initializes one `cljs.js` compiler state from the compiled package.
2. It activates the complete schema and function-contract projection acquired
   at the invocation database value.
3. It loads only the invoked database-authored namespace and its transitive
   requires, each as one current namespace section. Compiled namespaces remain
   supplied by the package.
4. It executes eval, tests, authored functions, and authored renders through the
   same execution protocol.
5. An unchanged digest reuses the compiler and already-loaded namespaces. A
   changed digest replaces the child on the next invocation and retries once.
6. Deadline, cancellation, result bounds, crash recording, and restart remain
   parent-owned supervision; agent code never owns its process lifetime.
7. Delete the pod-global compiler, global program replay, and any parent eval
   fallback after the child proof is green.

Exit:

- one child retains a definition across calls;
- two children can define the same symbol independently;
- two children perform CPU work concurrently;
- killing one child leaves the parent, JVM, web host, and other child alive;
- the replacement child reconstructs the accepted program from the database;
- no form history is replayed; and
- no Bun process contains a local Datahike connection or index.

### 3. Migrate complete application behaviors

Goal: restore the application by moving coherent acquisition and mutation
boundaries, while preserving the existing entity/ref model.

Move in this dependency order:

1. startup, schema/program admission, recovery, and initial agent creation;
2. run, turn, loop, message, schedule, and lifecycle transitions;
3. prompt/context acquisition and agent-authored eval/test execution;
4. root and agent rendering, canvas, routes, Datastar feeds, and calls;
5. debug, history, autocomplete, warnings, embeddings, and database browsing;
6. `my.*` toolkit functions used by real agents.

For each behavior:

- capture one current database value at the outer asynchronous boundary;
- acquire the required ordinary rows with one grouped request where the reads
  are known together;
- keep the inner derivation pure and synchronous over ordinary data;
- submit mutations through `seon.db/transact!` with a targeted CAS or explicit
  database-value fence only when the domain operation needs it;
- preserve the current public function signature where its semantics still fit;
- delete synchronous local-connection arities and fallbacks in the same cut;
  and
- prove the entity facts, refs, returned value, and user-visible result.

Each unit's plan names any duplicate owners it encounters and the exact deletion
that leaves one mechanism. A temporarily compiling compatibility layer is not an
implementation milestone.

Do not mechanically replace every `db/*conn*` occurrence. Some owners should
become one coarse authority read; some belong in the child; some helpers and
tests are obsolete and should disappear.

### 4. Restore the maintained test system

Goal: tests exercise the one production architecture rather than constructing
the deleted one.

1. Keep pure transformation tests local and synchronous.
2. Use the existing CLJS authority test session for application database
   behavior; do not restore `open-agent-conn!` or root `db/*conn*` fixtures.
3. Keep writer/Datahike behavior in `bin/test-writer`.
4. Keep operator and lifecycle behavior in `bin/seon test operator`.
5. Delete tests whose only subject is the removed replica, replay feed, local
   connection, Node adapter, or duplicate renderer.
6. Port behavioral assertions from stale tests when the behavior remains part
   of Seon. Do not port their obsolete setup.
7. Run focused tests while implementing. Run each complete gate once at its
   natural boundary, then the full matrix from one frozen source digest.

Required gates:

- `bin/test-cljs`;
- `bin/test-writer`;
- `bin/seon test operator`;
- protocol/transport conformance under Bun;
- focused child supervision and program reconstruction;
- browser proof for `/`, `/agent/{id}`, `/data`, calls, and live Datastar
  updates; and
- a real agent run that writes, reads, renders, restarts, and continues from
  database facts.

### 5. Delete the old architecture

After the last real consumer moves, delete together:

- pod-local Datahike constructors and connection state;
- replica and committed-transaction replay;
- full-feed publisher/correlation paths;
- pod-global compiler and replay graph;
- Node-only process, socket, and web compatibility owners;
- synchronous database facade arities;
- duplicate rendering or reactive paths; and
- production helpers retained only to support obsolete tests.

Static reachability then proves the old architecture cannot return. File size is
not the performance claim; eliminating duplicate live owners is.

### 6. Prove the running system

From one frozen source digest:

1. build the writer and Bun artifacts;
2. initialize a fresh default database;
3. reopen it and prove initialization is write-free;
4. create and run multiple agents concurrently;
5. exercise query, pull, entity, index, transaction, and listener behavior;
6. verify root, agent, data, debug, and canvas views in a real browser;
7. kill one child during work and prove bounded failure data plus successful
   reconstruction;
8. restart the pod and JVM independently and prove database continuity;
9. run multiple cluster databases through one JVM and prove independent read
   and write progress; and
10. stop through the operator and prove no owned process remains.

### 7. Measure and optimize

Only after correctness and live graduation:

- compare Node and Bun on the same final artifact where Node remains runnable;
- measure cold start, warm child invocation, query/pull/index latency, render to
  first byte, Datastar update latency, and transaction acknowledgement;
- measure idle CPU, CPU under concurrent agents, JVM heap, Bun RSS per host and
  child, retained compiler memory, allocation, queue wait, cache hits, and
  database count scaling;
- run 1, 4, 16, and 32 child density on modest hardware;
- verify identical queries over the same database value share JVM computation;
  and
- tune batching, compression, child retention, `--smol`, pools, or authority
  sharding only when the measurements identify them as limiting.

## Adversarial design gate

Pause a unit before adding more code when any of these occurs:

- the same fact is retained in the JVM and Bun;
- a new cache, replay cursor, lifecycle entity, registry, or renderer appears;
- one behavior needs more than one database value without a temporal reason;
- a helper needs compatibility branches for both local and remote databases;
- a function or namespace name acquires a version, `new`, `remote`, or `legacy`
  suffix because its existing owner was not replaced in place;
- a request performs many serial authority calls whose inputs were already
  known together;
- initialization behavior is split between database creation, pod boot, and
  child startup without one admission decision;
- an entity/ref relationship is replaced with an encoded map for transport
  convenience; or
- a focused test requires recreating deleted production infrastructure.

The adversarial pass must attempt a smaller ownership boundary or deletion
before accepting the added complexity.

## Known integration decisions

- Database/package initialization extends the existing database ensure/open
  boundary. Do not add a second initialize operation with an admission gap.
- Datastar registration must settle one current-database/resynchronization
  contract. The current listener acknowledgement and consumer expectations do
  not match; do not restore replay to bridge them.
- Eval operation capture and web `read-replayable?` belong to the deleted local
  replay architecture. Authority request evidence and persisted domain facts
  replace their useful observations; the old fields are deleted.
- The execution child owns agent-view projection and hung-renderer containment.
  Old local canvas/slot/SCI rendering is deleted after source reachability
  proves its last consumer moved; it is not adapted to the remote database.
- `seon.db.id` retains one CLJS remote allocator and one CLJ writer
  implementation. Duplicate CLJS allocator bodies are unified in place.
- `bin/test-cljs` and changed-test execution use the same selected JavaScript
  runtime as the operator. Node may remain a temporary comparison command but
  not a second production runtime path.

The complete consumer and deletion inventory is
[[remaining-authority-only-consumer-deletion-inventory-2026-07-16]].

## Immediate next boundary

Finish the complete schema and package initialization contract, then prove a
fresh child applies that accepted program over its compiled baseline. Do not
begin broad consumer migration until those two contracts are stable: every
later behavior depends on both, and neither requires a compatibility runtime.
