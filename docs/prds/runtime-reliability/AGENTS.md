---
type: orchestrator
status: active
tags: [orchestrator, prd, database, flow, agent, web]
---

# Runtime reliability refactor — working context

## Current state

The proven CLJS pod is responsive enough to preserve, but the repository still
contains a paused JVM application, several duplicate runtime paths, broad build
closures, a large shell operator, stale UI/database vocabulary, and an
expensive test surface. Source-grounded audits have now mapped the permanent
JVM server, canonical CLJS runtime/UI, archive boundary, local and remote
database synchronization, client distribution, test authority, and exact
canvas/surface/card cutover.

This PRD is in **phase 1 of 8: plan review and clean-base freeze**. Do not begin
production implementation until the remaining owner decisions in [[roadmap]]
are answered. The active plan replaces mechanisms in place and deletes their
superseded paths; it does not create compatibility namespaces or parallel
architectures.

Foundational gains already landed and are the baseline:

- one generated-identity allocator and minimal user/process provenance;
- separate cold boot, agent birth, and agent resume transitions;
- no second complete program build or ghost-pruning pass;
- maintained Datahike/Konserve fixes for effective datoms, connections,
  branches, ordered commits, caches, and shutdown;
- durable same-request transaction recovery and bounded replay/live overlap;
- one bounded normal transcript and partly unitized/lazy web rendering;
- no homegrown gym/evaluator—Inspect AI is the sole agent/model harness; and
- PID+OS-start/process-group/lock protections in the current supervisor.

The authoritative ordered plan is [[roadmap]]. The durable provenance and
lifecycle semantics remain [[provenance-and-lifecycle-design]] until phase 4
folds the final database vocabulary into the architecture docs.

## How to run the current system

These are the current doors while the Babashka operator is still unbuilt:

```bash
bin/seon status pod
bin/seon restart pod
bin/test-cljs
curl -fsS http://127.0.0.1:7890/agents >/dev/null
```

Use the authorized default cluster/database for destructive live proof. Do not
touch ACME while its shared lane is dirty; update it only after the default
cluster passes. Use the `browser-automation` skill for Seon's own UI and verify
long-lived gzip SSE with a Node gunzip client because the browser bridge does
not reliably proxy the feed.

The target primary door, selected by the owner, is:

```bash
bin/seon up
bin/seon up --open
```

`up` starts the complete development stack, waits for real readiness, and
prints the useful URLs. Only `--open` launches a browser. There is no fake
production alias and paused/advanced process verbs are not primary UX.

## Load-bearing findings

- The permanent Java component is a small JVM database/heavy-compute server,
  not the old JVM application. The canonical agent/runtime/renderer remains
  CLJS and server agents run as Node processes beside the JVM.
- The retained writer currently reaches thirteen namespaces; deleting the
  unused second reactive system reduces that closure to twelve. Base aliases
  and the writer artifact still include misleading paused dependencies.
- `writer-uber` builds from `[:writer]` while runtime uses the maintained
  fork/SIMD composition. Artifact and local execution are not presently the
  same program.
- `bin/seon` is a 2,186-line shell program. The replacement is a small shell
  launcher over a Babashka process graph with explicit transitions,
  readiness, locks, and scoped reset.
- Active hooks still call the paused JVM nREPL on port 7888, so claimed checks
  can silently fail to run. Useful checks move to direct bounded tool doors.
- The UDS writer has the stronger transaction contract: durable request
  receipts, same-ID recovery, bounded replay, overlap deduplication, and
  read-your-own-write. Datahike/Kabel has useful immutable-root sync but needs
  listener, deadline, cancellation, reconnect, and backpressure fixes.
- Replica state should synchronize immutable Konserve nodes and expose the
  branch head last. Exact transaction replay remains for receipts, forensics,
  and genuinely durable processors—not for every projection.
- The current agent view already has a surface catalog, database-recency focus,
  and right rail. Rename and finish that mechanism; do not invent a new view.
- `seon.store.wire`, `:seon.store.wire/*`, `{store-id, ...}`,
  `seon.server.store`, `/store`, and `db/store-inventory` form a competing
  database language. Cut them over atomically to db/database/backend terms,
  with literal upstream `:store` keys confined inside the third-party adapter.
- The inventory context, `db/store-inventory`, `my.kb/inventory`, root-canvas
  “STORE” panel, header count, and `/data` browser repeat broad
  namespace/count scans. Delete the generic inventory family, but keep and port
  `/data`: it becomes the single database browser on the canonical lazy
  render-unit/feed lifecycle with bounded `seon.db.browser` projections.
- Database-browser pages use EAVT/AEVT/AVET cursors, never Datalog
  offset/limit. Add a general upstreamable Datahike `count-datoms` API over its
  existing O(log n) subtree count-slice; keep transaction bodies lazy/capped
  until a measured need justifies a Datahike-owned transaction-leading index.
- `my.kb` domain facts and provenance are independent of that inventory. A
  later refined KB may compose purpose-specific database queries without
  restoring a global default context section.
- Three skill trees drift. One canonical distributable source must generate or
  validate tool-facing views; stale live-tile/world/inspector/`my.tile`
  teaching is deleted.
- Thin server-rendered web mode is the first remote client. A full browser
  CLJS/IndexedDB replica and downstream Tauri shell follow from the same
  renderer and protocol, not from Wasmtime/WIT or a second client runtime.

## Settled — do not re-litigate

- One product vocabulary: database/db, block/render/surface/canvas/card/slot.
  No store, tile/live-tile, world, or inspector concepts in active Seon APIs.
- Database namespace ownership is fixed: `seon.db` public API,
  `seon.db.protocol` shared semantic messages, `seon.db.backend` private
  Datahike/Konserve translation, `seon.db.registry` JVM connections/lifecycle,
  and `seon.db.transport.*` delivery adapters. Protocol behavior never forks
  by transport.
- Third-party implementation names are encapsulated at their adapter boundary;
  Seon does not fork upstream merely to rename private keys.
- The old JVM application is archived through a known Git ref and deleted from
  active source/classpaths/tests. Java remains for database authority and heavy
  work.
- The JVM does not grow a parity renderer. CLJS is canonical on server and
  client.
- Persist resulting facts, not algorithm traces, dirty flags, rendered output,
  or derivable lifecycle state.
- Provenance is only resolvable `:seon.db/user` and `:seon.db/process`
  transaction metadata. It is not authorization or entity ownership.
- Config is optional for an existing database. Explicit config apply repairs
  exactly its declared subset and writes nothing when converged.
- Native Datahike schema reopens from durable data. Canonical Malli/program
  forms are database facts; runtime registries are validated projections.
- No arbitrary eval replay and no promise to reconstruct non-database effects.
- No persisted UI dependency/subscription/cache entities. Read observation and
  bounded runtime caches are derived and discardable.
- Four dormant context-display adapters—findings, inventory, jobs, and
  testrun—are deleted precisely. The generic inventory API/root panel/header
  scan are deleted with the inventory adapter. The header database link and
  `/data` route remain; `/data/sse` and its private feed registry do not.
  Useful domain/runtime facts remain.
- `my.canvas` is the one permanent agent-facing canvas/control API and injects
  current agent/database identity.
- Local UDS and remote WebSocket may both be transport adapters, but one
  versioned message model and reconnect state machine own semantics.
- Durable offline mutation is a bounded stable-ID intent outbox plus optimistic
  projection, not a browser-authoritative database or CRDT.
- Tests assert facts, transitions, envelopes, DOM identity, omission,
  idempotency, and rendered structure—not context prose.
- No migration is needed for current test data. Rename filesystem/database
  layouts directly after coordinated reset.
- Every phase commits small gains and finishes with focused plus live proof.

## Open owner decisions

These choices do not block phases 1–5, but they change phases 6–7:

1. Approve immutable Konserve root synchronization as the first remote replica
   mechanism, deferring exact datom apply unless Datahike gains and proves a
   native primitive? Recommendation: yes.
2. Must a successful authoritative transaction wait for durable GCS/S3
   acceptance, or may a local hot database acknowledge under an explicit
   nonzero cloud RPO? Recommendation: require zero acknowledged cloud data loss
   and benchmark direct GCS first.
3. Approve thin server-rendered mode first, followed by the full
   browser/IndexedDB/Tauri replica from the same CLJS source? Recommendation:
   yes.

The owner has already selected the complete-development-stack semantics for
`bin/seon up`, including readiness/URL output and opt-in `--open`.

## Ordered next steps

1. Review/freeze the Git archive ref, database/UI vocabulary, deletion matrix,
   and coordinated clean-base evidence.
2. Isolate and prove the permanent JVM database/heavy-compute server, including
   the atomic store→database namespace/schema/path cutover.
3. Replace the operator with Babashka, archive/delete the paused JVM app and
   rejected prototypes, and split fast CLJS/writer/runtime/browser test doors.
4. Finish exact config/schema/program/database lifecycle reconstruction,
   coordinates, as-of, branches, restore, undo, and crash recovery.
5. Converge canvas/surface/card UI vocabulary, delete weak context/inventory
   paths, port `/data` to bounded lazy database units on the one feed, finish
   responsive layout/controls/focus, and cut over ACME after default proof.
6. Harden and adopt one remote writer/replica protocol over UDS/WebSocket
   adapters.
7. Ship thin hosted web, browser replica, downstream native shell, and a proven
   cloud database backend.
8. Run transition/failure/browser/Inspect AI/profiling acceptance, update the
   one architecture, and graduate only when active searches find no duplicate
   mechanism.

## Entry points

- [[roadmap]] — authoritative eight-phase order, acceptance transitions, and
  owner decisions.
- [[provenance-and-lifecycle-design]] — database provenance/config/lifecycle
  target semantics.
- [[research/jvm-archive-boundary-2026-07-13]] — retained/deleted JVM closure,
  build aliases, tests, and archive sequence.
- [[research/jvm-server-cljs-client-storage-sync-2026-07-13]] — writer,
  Datahike/Kabel/Konserve synchronization, protocol, and cloud evidence.
- [[research/client-distribution-and-server-rendering-boundary-2026-07-13]] —
  canonical CLJS renderer, browser/Tauri/server-agent boundaries.
- [[research/surface-vocabulary-and-dead-ui-path-audit-2026-07-13]] — exact
  UI rename/delete graph and four dormant context adapters.
- [[research/seon-cli-lifecycle-audit-2026-07-13]] — current shell/process
  failure modes and Babashka target.
- [[research/cljs-test-suite-speed-and-quality-audit-2026-07-12]] — active
  behavioral test authority and runtime tiers.
- [[research/database-runtime-responsiveness-audit-2026-07-13]],
  [[research/web-responsiveness-audit-2026-07-13]], and
  [[research/live-feed-fix-review-2026-07-13]] — performance baselines.
- [[docs/seon/architecture/architecture]],
  [[docs/seon/architecture/data-model]],
  [[docs/seon/architecture/agent-runtime]], and
  [[docs/seon/architecture/ui]] — ideal system docs updated as phases land.
