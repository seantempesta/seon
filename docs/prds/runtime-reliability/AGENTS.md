---
type: orchestrator
status: active
tags: [orchestrator, prd, database, flow, agent, web]
---

# Runtime reliability refactor — working context

## Current state

The proven CLJS pod is responsive enough to preserve, but the repository still
contains a paused JVM application, several duplicate runtime paths, broad build
closures, stale UI/database vocabulary, and an
expensive test surface. Source-grounded audits have now mapped the permanent
JVM server, canonical CLJS runtime/UI, archive boundary, local and remote
database synchronization, client distribution, test authority, and exact
canvas/surface/card cutover.

This PRD is in **phase 3 of 6: finish the operator cutover and test trim**. The
Babashka operator and default-cluster cold/restart proof are complete. ACME and
Inspect callers, direct hook/tool doors, and the remaining obsolete harness
trees are the phase-3 work still open. The active plan replaces mechanisms in
place and deletes their superseded paths; it does not create compatibility
namespaces or parallel architectures.

The previously shared ACME/plan/REPL work is checkpointed at `3e0e0bff` and the
tree was clean immediately afterward. Focused `seon.schema-test`,
`my.plan-test`, and `seon.ai.dispatch-test` runs pass. The annotated
`runtime-reliability-pre-refactor-2026-07-13` tag anchors the complete
`b4efd4f5` handoff. The writer now has one complete `:writer` basis for source
launch and `writer-uber`: 111 libraries/117 roots instead of 188/194, the exact
maintained Datahike/Konserve SHAs, and one SLF4J provider. `bin/test-writer`
proves the eleven retained namespaces (47 tests/295 assertions in about ten
seconds). The unused query-subscription engine and its second in-process
subscriber bus are deleted; raw transaction fanout plus bounded replay is the
one retained update channel. The active database boundary now has one shared
`seon.db.protocol`, one `seon.db.replica`, one JVM writer/server, and transport-
only UDS adapters. Legacy server/store namespaces and 14 legacy writer suites
are deleted; public transaction updates omit the durable internal receipt
datoms and metadata. Typed administration, cold live transition proof,
profiling, and artifact-manifest truth remain. Dead writer routing and
load-order callback registries are gone; boot now supplies one immutable writer
runtime, and a failed database initializer cannot publish a broken connection.
The exact starting evidence is
[[research/phase-1-baseline-2026-07-13]].

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
- Node tests install the existing library-log gate before any test namespace;
  focused runs no longer serialize Datahike/Konserve trace payloads, and the
  canonical timestamped test-log tail is bounded to 20 files.

The authoritative ordered plan is [[roadmap]]. The durable provenance and
lifecycle semantics remain [[provenance-and-lifecycle-design]] until phase 4
folds the final database vocabulary into the architecture docs.

## How to run the current system

These are the current Babashka operator doors:

```bash
bin/seon status
bin/seon restart
bin/test-cljs
curl -fsS http://127.0.0.1:7890/agents >/dev/null
```

Use the authorized default cluster/database for destructive live proof. Do not
touch ACME while its shared lane is dirty; update it only after the default
cluster passes. Use the `browser-automation` skill for Seon's own UI and verify
long-lived gzip SSE with a Node gunzip client because the browser bridge does
not reliably proxy the feed.

The primary door, selected by the owner, is:

```bash
bin/seon up
bin/seon up --open
```

Bare `bin/seon` means `up`. In a source checkout, `up` fully rebuilds the
canonical writer + CLJS artifacts, reconciles only changed dependents, starts
incremental watchers, waits on atomic verified readiness, and prints useful
URLs. Only `--open` launches a browser; on first-ever boot it opens the ordinary
initial agent, not root. There is no fake production alias and paused/advanced
process verbs are not primary UX.

## Load-bearing findings

- The permanent Java component is a small JVM database/heavy-compute server,
  not the old JVM application. The canonical agent/runtime/renderer remains
  CLJS and server agents run as Node processes beside the JVM.
- The retained writer reaches twelve namespaces. Its complete `:writer` basis
  excludes the paused application and is also the `writer-uber` basis.
- `writer-uber` originally built from `[:writer]` while runtime used the
  maintained fork/SIMD composition. Source and artifact now resolve identical
  111-library/117-root graphs and build/preflight; the remaining artifact defect
  is the lack of one published launch manifest.
- The writer publishes committed transaction frames and serves bounded replay.
  There is no durable query-subscription engine or second in-process subscriber
  bus.
- `bin/seon` is now a seven-line launcher over the Babashka process graph. Its
  lifecycle gate passes 10 tests/29 assertions. A canonical restart rebuilt all
  artifacts, changed all three process identities, resumed `root` and
  `little-cars-laugh` idle, replayed 2/2 forms, registered 772 contracts with
  zero bad specs, served `/`, `/agents`, and `/data`, and emitted a gzip
  Datastar patch. Downstream ACME/Inspect caller migration remains before phase
  3 closes.
- The direct Babashka hook has no runtime/nREPL dependency. It ignores events
  outside this checkout before resolving paths, keeps syntax/Markdown/docstring
  checks local, leaves Gemini review explicitly disabled by default, and bounds
  its diagnostic log.
- The UDS writer has the stronger transaction contract: durable request
  receipts, same-ID recovery, bounded replay, overlap deduplication, and
  read-your-own-write. Datahike/Kabel has useful immutable-root sync but needs
  listener, deadline, cancellation, reconnect, and backpressure fixes.
- Immutable-root sync/Kabel evidence is preserved for a later remote PRD; it is
  not a second live path or completion gate for this branch.
- The current agent view already has a surface catalog, database-recency focus,
  and right rail. Rename and finish that mechanism; do not invent a new view.
- The competing Seon store vocabulary and generic inventory family are deleted.
  Literal upstream `:store` keys remain private inside the third-party adapter.
  `/data` is the single database browser and direct indexed queries are the
  agent discovery path; do not restore a whole-database inventory projection.
- Database-browser pages use EAVT/AEVT/AVET cursors, never Datalog
  offset/limit. Add a general upstreamable Datahike `count-datoms` API over its
  existing O(log n) subtree count-slice; keep transaction bodies lazy/capped
  until a measured need justifies a Datahike-owned transaction-leading index.
- `my.kb` facts are ordinary database facts and must be browsable through
  `/data`; a later focused KB surface must not restore a global inventory block.
- Three skill trees drift. One importer persists canonical `SKILL.md` source;
  `seon-skills` is the shipped corpus and tool-facing trees are generated or
  validated adapters. Import does not imply a standing skills context block.
- `/` already points at root's system canvas. The remaining target is one short
  root-role block, namespace-led orchestration/navigation context, one ordinary
  first-run agent, and database-backed per-tab location so root can switch the
  originating human view through the normal feed.
- Root's complete home-require scalar correctly replaces the ordinary workbench
  so root can stay smaller; fix the misleading “shared plus” comment rather
  than inventing union semantics. The actual leak is the no-config fallback,
  which still exposes `seon.agent` orchestration to ordinary agents. Let the
  existing canvas AI twin carry bounded fleet facts.
- Root fleet cards consume the shared agent-derived focus (pin, then agent
  recency, then welcome) and the same compact materializer as an agent page with
  no session override. Per-tab manual page focus remains session-scoped. Always
  list every agent; lazily render human detail, and budget AI detail by
  running/erroring/recent priority. CPU/RSS waits for one reusable operator
  status projection and never gets its own feed.
- Recent messages/evals currently have several duplicate full-history scans.
  Put bounded reverse-index readers in the fact-owning namespaces, expose
  Datahike `rseek-datoms` only through `seon.db`, and make transcript, chat,
  menus, error-storms, activity, and root cards consume those functions.

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
- An unexpected crash fences interrupted runs closed, leaves affected agents
  idle, and writes one recovery anchor in that same transaction. Root derives
  the detailed notice from the transaction and commit graph; root/human chooses
  resumption.
- Batch mode attempts every complete form in order and persists every real
  success/error; an ordinary early failure does not suppress later forms.
- No persisted UI dependency/subscription/cache entities. Read observation and
  bounded runtime caches are derived and discardable.
- Four dormant context-display adapters—findings, inventory, jobs, and
  testrun—are deleted precisely. The generic inventory API/root panel/header
  scan are deleted with the inventory adapter. The header database link and
  `/data` route remain; `/data/sse` and its private feed registry do not.
  Useful domain/runtime facts remain.
- `my.canvas` is the one permanent agent-facing canvas/control API and injects
  current agent/database identity.
- The local UDS path is authoritative in this branch. Writes acknowledge after
  the local commit/receipt is accepted, without waiting for UI/cloud catch-up.
- Root context is concise and role-specific. Core operational knowledge lives in
  fully specified namespace cards/current source plus colocated state blocks.
- Standard skills remain importable facts, but default/test skills blocks stay
  disabled so dynamic context must prove relevance.
- Tests assert facts, transitions, envelopes, DOM identity, omission,
  idempotency, and rendered structure—not context prose.
- No migration is needed for current test data. Rename filesystem/database
  layouts directly after coordinated reset.
- Every phase commits small gains and finishes with focused plus live proof.

## Explicitly deferred follow-on work

Remote replica/reactive catch-up, WebSocket adoption, cloud topology, browser/
IndexedDB, offline mutation, mobile packaging, and the full paid Inspect AI
battery are not this branch's completion criteria. Preserve the existing
Datahike/Kabel/Konserve research and useful source, but do not run or maintain a
parallel live path. GCS is evaluated first later; cloud mirroring has an honest
nonzero RPO under the selected local-ack policy. Phone clients are thin and use
a hosted JVM + Node cluster.

## Ordered next steps

1. Review/freeze the Git archive ref, database/UI vocabulary, deletion matrix,
   and coordinated clean-base evidence.
2. Isolate and prove the permanent JVM database/heavy-compute server, including
   the atomic store→database namespace/schema/path cutover.
3. Replace the operator with Babashka, archive/delete the paused JVM app and
   rejected prototypes, and split fast CLJS/writer/runtime/browser test doors.
4. Finish exact config/schema/program/database lifecycle reconstruction,
   first-run root+ordinary-agent creation, non-fail-fast batches, coordinates,
   as-of, branches, restore, undo, and idle-and-notify crash recovery.
5. Converge canvas/surface/card UI vocabulary, delete weak context/inventory
   paths, port `/data` to bounded lazy database units, restore concise root
   context, persist per-tab location/navigation, refine skill import without a
   default block, finish responsive layout/controls/focus, and cut over ACME
   after default proof.
6. Run local transition/failure/browser/basic-Inspect/profiling acceptance,
   update the one architecture, and graduate only when active searches find no
   duplicate local mechanism.

## Entry points

- [[roadmap]] — authoritative six-phase order, acceptance transitions, settled
  choices, and explicit follow-on boundary.
- [[research/phase-1-baseline-2026-07-13]] — current process/feed/render/build
  evidence, shared checkpoint, focused proof, and remaining archival checklist.
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
- [[research/root-view-presence-crash-batch-audit-2026-07-13]] — exact root
  canvas/focus/session/crash/batch/telemetry ownership and minimal slice.
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
