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

This PRD is in **integrated completion**. Work from the original phases 3–5
landed out of order, so the former “phase 3 of 6” label was retired. The
authoritative remaining order is the seven-slice **Active execution ledger** at
the top of [[roadmap]]; slice 0 (reconcile and baseline) is in progress. The
active plan replaces mechanisms in place and deletes superseded paths; it does
not create compatibility namespaces or parallel architectures.

The 2026-07-14 live-browser baseline in [[roadmap]] is the current falsification
set: existing feeds partially stale plan/transcript/root units because a
non-transitive declared-attribute gate skips exact observed reads; root uses the
ordinary agent shell; open debug broadcasts cost hundreds of milliseconds; and
simple plan/reply probes consumed 10–13 turns without a visible page interrupt.
Do not tune caps or patch individual renderers around these owning defects.

The settled reactive target is one automatic render-unit engine. Runtime-
observed read requests derive the candidate index and remain correctness
authority; every unit receives bounded read/result/serialized-output reuse;
root, agent, canvas, debug, and `/data` define layouts but no custom transition
logic. Agents never write memoized renderers, and no cache retains a database
value. See [[roadmap]] slice 2 and [[docs/seon/architecture/ui]].

The completed cache audit proves that active-unit reuse needs no library. Add
no JVM cache and expose no cache API to renderers. A Node `lru-cache` recent
layer is allowed only after active-unit profiling proves meaningful reopen
reuse. The completed test audit proves the common latency tax is a fresh
Shadow JVM, while the current complete run is independently broken by a Malli
projection leak and duplicate async completion. Repair that baseline first;
then reuse the existing managed Shadow process and compiler dependency graph.
Do not infer function-level test impact from the incomplete runtime graph.
Automatic agent feedback uses the existing synchronous `PostToolUse`
`additionalContext` hook path. Codex `apply_patch` and Claude edits are now
normalized in `bin/seon-hook`; the managed Shadow process publishes one
immutable artifact/manifest; and `bin/seon test changed --path PATH` is the
public operation called by the hook. Test feedback is advisory and never gates
refactoring. Do not add
Shadow autorun, another daemon, registry, event bus, or database notification
projection. Codex hook trust is a one-time user review, never a committed or
bypassed setting.

The stable autosuggest lane has been collapsed into this branch through its
five reviewed implementation commits only. Keep the provider-derived Inspect
egress, declared `openai` dependency, first-class long-term-planning task,
same-title `plan!` guard, and EDN-only `reconcile!`. Do not import the lane's
redundant compact-context commits, local ACME/src-needle state, or removed
markdown plan path. The offline Inspect gate is 293 passed/eight gated skips;
planning good/bad score 1.000/0.000; focused `my.plan-test` is 40/241.

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
- Focused pod selectors are also Shadow compile inputs. One portable owner lock
  brackets compile plus execution, and `--no-build` refuses any artifact whose
  content fingerprint does not match its namespace/source/config/dependency
  inputs or downstream build flavor.

The authoritative ordered plan is [[roadmap]]. The durable provenance and
lifecycle semantics remain [[provenance-and-lifecycle-design]] until phase 4
folds the final database vocabulary into the architecture docs.

## How to run the current system

These are the current Babashka operator doors:

```bash
bin/seon status
bin/seon restart
bin/seon test pod seon.example-test
bin/seon test database seon.db.registry-test
bin/seon test operator
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
  outside this checkout before loading config or writing artifacts, keeps
  syntax/Markdown/docstring checks local, and bounds its diagnostic log under a
  cross-process file lock. The automatic Gemini queue/retry mechanism is
  deleted; model review is an explicit operator action.
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
- Full test-run values are owned by the evaluator's ordinary addressable result
  symbols. The runner persists only queryable per-test outcome facts; do not
  restore a second atom-backed recent-result history.
- Test dependencies are facts or they do not exist. Source-substring scans no
  longer invent fn↔test relationships for reruns or status rendering; newly
  defined tests still run from the exact analyzer diff.
- Compiled product boot does not import the platform test suite into the
  database. Agent-defined tests enter once through the analyzer tee; the old
  preload, deftest enumeration macro, boot atom, and test indexer are deleted.
- Database-browser pages use EAVT/AEVT/AVET cursors, never Datalog
  offset/limit. Add a general upstreamable Datahike `count-datoms` API over its
  existing O(log n) subtree count-slice; keep transaction bodies lazy/capped
  until a measured need justifies a Datahike-owned transaction-leading index.
- `my.kb` facts are ordinary database facts and must be browsable through
  `/data`; a later focused KB surface must not restore a global inventory block.
- Skill adapter drift is mechanically closed. `seon-skills` is the shipped
  runtime authority; `bin/seon skills sync` generates shared tool views and the
  operator gate runs `bin/seon skills check`. Import does not imply a standing
  skills context block. Persisting imported bodies instead of checkout paths is
  still open.
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
- Recent message/eval fact owners expose bounded reverse-index windows through
  `seon.db/rseek-datoms`; context evals, chat/default conversation, function
  menus, error-storms, root activity, and the normal HTML transcript consume
  them. HTML caps both sources before its retained-turn projection; the
  deliberate AI transcript history policy remains unchanged.
- Hot reload now derives its affected namespaces from Shadow's Node reload
  selection instead of the browser-only filter that returned an empty set.
  Every reloaded definition is re-instrumented, so the root-only derived
  instrumentation alarm self-omits at zero gaps after both reload and cold boot.
- Persisted `:seon.ns/require-edges` are the sole alias/refer authority for SCI,
  replay ordering, and context resolution. The render-time namespace-source
  parser and its fallback-tracking atom are deleted.
- Persisted `:seon.fn/read-attrs` are the sole declared renderer dependency
  facts. The render path no longer regex-scans source for old rows; fresh code
  is indexed or teed once and runtime observed reads remain the exact check.
- SCI render input and deadline are invocation-local closures. The former
  process-global volatiles are deleted, so nested/future concurrent renders
  cannot cross-contaminate input or extend another invocation's budget.
- SCI failure suppression is a 256-key FIFO window, not an unbounded
  process-lifetime seen set. Derived error cards remain self-healing.

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

Follow the roadmap's active execution ledger exactly: baseline; database
lifecycle; lazy reactive/data units; root/session/canvas/UI; tests/operator/
callers; profiling; acceptance/graduation. Keep one slice in progress, close it
with focused tests + live proof + docs + commits, then mark the next slice in
progress. Bugs and smells follow the ledger's interruption/recording policy.
Human-visible work also follows the roadmap's browser journey discipline: use
the public controls a normal user sees, verify database facts plus affected
gzip-SSE element patches, exercise narrow/wide and two-tab behavior, and treat
browser discoveries as implementation evidence rather than final visual QA.
Test work follows the roadmap's test-selection design gate: improve the
existing CLJS/writer doors in place, derive the smallest sound affected set from
proven program-graph edges, widen on uncertainty, and document every selected
test's reason. Do not implement a new runner while the source-grounded test
impact audit is open.

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
- [[research/root-reactive-system-view-audit-2026-07-14]] — live stale-unit
  reproduction, owning invalidation defect, dedicated root layout, plan overlay,
  unit-engine migration/deletion map, and browser acceptance matrix.
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
