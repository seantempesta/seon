---
type: prd
status: active
tags: [prd, database, flow, agent, web]
---

# Runtime reliability refactor roadmap

## Outcome

Turn the proven Seon prototype into one small, explicit system that can be
started, understood, repaired, and extended without knowing its archaeological
layers:

- one authoritative JVM database/heavy-compute server;
- one canonical CLJS agent and web UI implementation;
- one versioned writer/replica protocol with local and remote transports;
- one database-derived block/render/surface model;
- one robust development operator;
- one tiered behavioral test system; and
- no paused application, compatibility path, duplicate reactive channel, or
  stale vocabulary left in active code.

The refactor succeeds by deleting overlap. It does not add an authorization
system, a second renderer, a Seon-specific cloud object layout, a second event
bus, a local authoritative browser writer, or prose-heavy context intended to
compensate for unclear functions.

## Current position

**Current phase: plan review and clean-base freeze (phase 1 of 8).** Production
implementation does not start until the owner resolves the three choices under
[[#Owner decisions before phases 6–7]].

The source-grounded system audits are complete and committed:

- [[research/database-runtime-responsiveness-audit-2026-07-13]]
- [[research/web-responsiveness-audit-2026-07-13]]
- [[research/live-feed-fix-review-2026-07-13]]
- [[research/agent-lifecycle-responsiveness-audit-2026-07-13]]
- [[research/seon-cli-lifecycle-audit-2026-07-13]]
- [[research/jvm-archive-boundary-2026-07-13]]
- [[research/jvm-server-cljs-client-storage-sync-2026-07-13]]
- [[research/client-distribution-and-server-rendering-boundary-2026-07-13]]
- [[research/surface-vocabulary-and-dead-ui-path-audit-2026-07-13]]
- [[research/cljs-test-suite-speed-and-quality-audit-2026-07-12]]

Several foundational corrections have already landed:

- generated persistent identities have one schema-driven atomic allocator;
- normal transaction provenance is only resolvable user and process refs;
- cold runtime boot, agent birth, and agent resume are separate operations;
- agent birth is one transaction and ordinary resume does not write;
- the duplicate homegrown evaluator/gym is deleted; Inspect AI is the sole
  model/agent evaluation harness;
- the second complete program build and boot-time ghost-pruning pass are gone;
- the maintained Datahike/Konserve forks include effective-datom, connection,
  branch, ordered-commit, cache, and shutdown fixes;
- transaction IDs have durable same-payload receipt/recovery semantics;
- replay is bounded, cursor-checked, and deduplicated against concurrent live
  frames;
- normal transcript HTML is bounded and chat-first;
- stable render units and the lazy debug web UI are partly cut over; and
- the external shell supervisor now protects against PID reuse, lifecycle
  races, and orphan process groups.

Those gains are the base. The remaining work is not a restart from scratch.

## Target system

### Runtime roles

| Role | Owns | Does not own |
|---|---|---|
| JVM server | serialized Datahike writes, durable Konserve storage, transaction receipts, branch/as-of/restore, schema/config commit authority, embeddings, secondary indexes, bounded heavy work | agent execution, context rendering, HTML, a duplicate application |
| CLJS UI host and agent runtimes | agent loop/eval, program reconstruction, context derivation, canvas/surfaces, Hiccup, Datastar, server-hosted agents | authoritative writes, cloud credentials, a second database |
| Browser/Tauri client | thin HTML mode or a CLJS memory/IndexedDB read replica, local projections, device input, bounded unsent-intent outbox | JVM-only indexes, direct cloud database authority, offline conflict-free commits |

The local development composition co-locates the JVM writer, Shadow watcher,
and Node CLJS runtime. A hosted deployment runs the JVM server beside headless
Node CLJS agent/UI processes. A native client is a webview around the canonical
browser CLJS build, not the rejected Wasmtime/WIT pod.

### Three planes, one connection lifecycle

The target protocol distinguishes three contracts without turning them into
three independently configured systems:

1. **Replica data** — missing immutable Konserve nodes, then the mutable branch
   head, with the head exposed only after every reachable node is readable.
2. **Commit notification** — old/new coordinate, effective datoms, changed
   attributes, request ID, and transaction metadata for listeners, dependency
   invalidation, and durable processors.
3. **HTML delivery** — complete-element Datastar morphs for thin clients.

Pure projections reconnect to the current root and derive once. Exact bounded
transaction replay remains available for receipts, forensics, and consumers
whose durable contract genuinely requires every transaction. Coalesce
notifications, never state.

### One UI vocabulary

| Term | Meaning |
|---|---|
| block | database-owned context unit carrying zero or more render declarations |
| render | ephemeral projection for an audience/format |
| surface | resolved HTML render displayed by the web UI |
| twin | AI and HTML projections of the same block/function |
| canvas | focal, agent-controlled surface in an agent view |
| card | visual CSS component or compact/expanded face only |
| slot | named layout placement for a surface |
| view/page | route-level composition of surfaces |

Active APIs, DOM, CSS, config, skills, tests, and downstream ACME converge on
this vocabulary. There is no live-tile/tile architectural API, world view, or
inspector product name. Historical research, WIT's language keyword, Node
Inspector/CDP, Inspect AI, geometric “tile the frame,” and ordinary English are
not rewritten.

The persisted canvas attribute is already correct:
`:seon.render.canvas/content`. Do not add a stored surface/card entity.

### One database vocabulary

Seon calls the durable EAV system a **database** or **db**, everywhere. “Store”
is not a second product concept and is removed from Seon namespaces, schemas,
coordinates, functions, paths, CLI output, UI, skills, tests, and active docs.

| Canonical term | Meaning |
|---|---|
| database / db | one logical Datahike database and its accumulated facts |
| database name / database ID | routing label / stable identity for that database |
| database coordinate | `{database-id, branch, commit-id, t}` |
| backend | the physical Konserve implementation and location behind a database |
| replica | a readable local representation synchronized from an authoritative database |
| cache | bounded, discardable derived runtime data |
| blob archive | content-addressed durable large values referenced by database facts |

Third-party APIs may still use a literal `:store` key internally. That spelling
is confined to the Datahike/Konserve adapter and translated immediately; it is
never re-exported as Seon vocabulary. Ordinary English verbs in historical
material and upstream source are not compatibility APIs.

### Operator contract

The owner-selected primary door is:

- `bin/seon up` starts the complete development stack;
- it waits for real readiness and prints all useful URLs;
- it opens a browser only with `--open`;
- it makes no fake production claim; and
- paused and advanced process verbs are not part of the primary UX.

`down`, `restart`, `status`, `logs`, `doctor`, scoped
`cluster reset`, and explicit config/branch operations remain available.
The implementation is a Babashka program with process specifications and state
transitions as data; the shell file becomes a tiny launcher.

## Settled invariants

- The JVM application is archived; the JVM server is permanent.
- The canonical renderer is CLJS. The JVM never grows a parity renderer.
- `seon.db` remains the sole application database API.
- The database stores facts and canonical source forms, not processing traces,
  dirty flags, render output, or derivable lifecycle state.
- Config is optional on an existing healthy database. When explicitly selected,
  it repairs exactly its declared subset and does nothing when converged.
- A fresh writable database receives one explicit genesis/config floor.
- Malli runtime state is rebuilt from canonical database facts and changed
  incrementally after committed schema/program changes.
- Arbitrary evals and external effects are never replayed.
- Every database identity, map key, and public contract is fully namespaced and
  schema'd.
- `my.canvas` is the one permanent agent-facing canvas/control API; current
  agent/database identity is injected.
- Skills are not a default context block. Dynamic context and compact
  namespaces surface relevant capabilities.
- Four dormant display adapters are deleted precisely:
  `seon.agent.ctx.findings`, `inventory`, `jobs`, and `testrun`.
  Durable findings, job execution, and parsed test-run facts remain. The weak
  whole-database `db/store-inventory` API is also deleted, not renamed: schema
  discovery uses installed attributes, and domain discovery belongs in small
  purpose-specific database queries. A refined KB may compose those facts
  later without restoring a global inventory/context mechanism.
- One canonical skill source generates or links tool/runtime views; three
  hand-edited copies are not authorities.
- Local UDS and remote WebSocket may both exist as transport adapters, but they
  carry one message schema and one client state machine.
- The existing UDS path is the behavioral oracle until the Datahike/Kabel path
  proves parity. No permanent dual routing toggle survives cutover.
- Tests assert facts, transitions, envelopes, DOM identity, omission,
  idempotency, and rendered structure—not teaching prose.
- Every replacement deletes the superseded mechanism in the same phase after
  proof.
- ACME is updated only after the default cluster passes and its current shared
  work lane is clean.

## Known defects to remove

| Area | Current defect |
|---|---|
| JVM source | The retained writer reaches thirteen namespaces; one is the unused second reactive system. The old Integrant/core.async/agent/web application remains searchable and on broad classpaths. |
| JVM artifact | `writer-uber` claims the live fork/SIMD composition but builds a basis containing only `:writer`. |
| Dependencies | Heavy paused-app dependencies live in base `:deps`; the writer, CLJS build, tools, and tests do not have honest narrow closures. |
| Writer protocol | Dead query subscriptions, in-process subscriber routing, duplicate Transit helpers, unused read/filter/batch operations, an unwired agent registry, and a fake SQLite backend remain. |
| Database vocabulary | `seon.store.wire`, `:seon.store.wire/*`, `{store-id, ...}`, `db/store-inventory`, `seon.server.store`, `/store` paths, and matching docs/UI/skills expose a second name for the database. |
| Database inventory | The inventory context block, `db/store-inventory`, `my.kb/inventory`, root-canvas “STORE” panel, header link, and `/data` browser repeat broad namespace/count scans. `/data` also retains a second legacy SSE connection registry. |
| Developer hooks | Active hook config still calls the paused nREPL JVM on port 7888, so several claimed checks silently do not run. |
| Operator | `bin/seon` is a 2,186-line shell program exposing implementation processes and a destructive global nuke. |
| Tests | Disabled tests and old JVM application suites remain; focused writer and CLJS doors are not the complete active authority split. |
| UI | Surface/focus machinery exists, but active symbols, CSS, DOM, docs, skills, and ACME still say tile; four dead context renderers still load. |
| Live rendering | Agent view unitization/read-observation is incomplete; legitimate work still needs bounded caching, layout/focus/browser proof, and grown-database profiling. |
| Skills | `.agents/skills`, `.claude/skills`, and `seon-skills` drift. One runtime copy still teaches deleted `my.tile`, live-tile, and world APIs. |
| Datahike/Kabel | `src-kabel` is beta/test-only, does not wire foreign commits through normal listeners, can wait forever, and lacks durable same-ID recovery. |
| Replica docs | Active architecture incorrectly calls transaction-datom replay the settled replica bootstrap. Immutable-root synchronization is the implemented mechanism. |
| Browser client | There is no current browser CLJS application target or IndexedDB replica integration. |
| Cloud | S3/GCS are external Konserve packages and are not configured or proven by Seon. |
| Prototypes | Wasmtime/WIT Tauri, Rust client-runtime, and old libdatahike CLJS spikes remain in the active tree despite settled rejection. |

## Implementation discipline

- Observe the current default cluster before and after each phase.
- Start each phase from a coordinated commit and stage only files owned by that
  phase.
- Commit small, reviewable gains; do not accumulate the entire refactor.
- Read the relevant vendored library source before relying on behavior.
- Fix Datahike/Konserve/Kabel behavior in the maintained source that owns it;
  do not copy a frozen fork of the mechanism into Seon.
- Keep one state-machine implementation behind transport/platform adapters.
- Use `apply_patch` for source edits and preserve other agents' work.
- Prove behavior at the smallest useful tier, then cold-reset/live-prove at the
  phase boundary.
- No exact context prose tests, no hidden retry-to-green test runner, no
  compatibility namespace, and no in-repo archive source tree.
- Human-visible sizes are estimated tokens through the one estimator.

## State-transition acceptance table

| Transition | Durable facts/work | Process/reactive work | Failure proof |
|---|---|---|---|
| `bin/seon up`, cold | build/publish complete artifact if inputs changed | reconcile watcher → writer → pod; wait for stage-specific readiness; print URL | failed stage returns nonzero with exact logs; prior healthy stage is reported truthfully and next `up` resumes |
| `bin/seon up`, warm | none when artifacts/config/database converge | verify existing process identities/readiness | no duplicate process, seed, listener, or rebuild |
| fresh database | minimal genesis, native schema floor, root/process refs, explicitly selected initial config | rebuild Malli/program runtime and services | no circular provenance, partial schema, or hidden ambient config |
| existing database, no config | normally no transaction | rebuild process-local handles/registries; resume durable work | restart does not “heal” by rewriting canonical facts |
| explicit config apply | exact managed-subset delta plus lifecycle intent/recovery facts | invalidate only affected projections | missing/changed/extra facts repaired; outside facts unchanged; convergence writes nothing |
| core/schema hot reload | one exact program/schema delta | load/instrument only changed dependency closure | removal and same-key schema change work; no global rescan or ghost prune |
| agent birth | one allocation transaction for identity and initial components | create compiler namespace, host, listener, wake | no cluster seed/global instrumentation; failed birth leaves no partial agent |
| agent resume | normally none | restore one host/wake from durable facts | arbitrary evals/effects are not replayed |
| agent eval | eval/result/domain/declaration facts once | execute once; await/bound; instrument changed defs | ambiguous result does not allocate a new ID or re-fire effects |
| remote write, lost reply | one commit and one durable same-ID receipt | retry identical request and catch replica up to accepted coordinate | different-payload ID reuse rejects; every disconnect edge is at-most-once commit |
| local reader attach | none | open the shared immutable database read-only and catch the transaction cursor | no reader backend mutation; exact own/foreign listener behavior |
| cold remote replica | none on writer beyond attachment facts if required | sync reachable immutable nodes, publish head last, reconstruct local DB | interruption never exposes a head with missing children |
| warm reconnect | none | differential node sync to current head, one explicit root-advance invalidation | pure UI derives once; exact replay remains available to durable processors |
| browser action | one typed command/transaction and receipt | Datastar call → writer → commit notification → affected unit morph | no manual refresh, duplicate client state, or silent handler failure |
| debug route closed/open | none | closed owns no debug render/listener; open activates only requested units | prompt/raw/HTML/token work is absent while closed |
| as-of/fork/restore | branch/head/intent facts through Datahike primitives | quiesce, drain, attach exact coordinate, rebuild process state | stale writers/cursors cannot cross head movement; external effects are not undone/replayed |
| stop/reset | only explicit lifecycle facts | reverse-order drain, verify PID+start stamp/process group, then mutate the named database | no global nuke, reused-PID signal, orphan child, or deletion under a live writer |
| cloud commit crash | immutable nodes may be orphaned; head is old or complete new | reopen exact published head | never a visible head referencing absent objects |

## Ordered implementation plan

### Phase 1 — review, coordinate, and freeze the archival boundary

1. Resolve the three owner decisions below.
2. Let the active ACME/plan/repl-autosuggest lane commit or clearly hand off
   its files. Do not absorb its dirty working tree into this refactor.
3. Record the exact default-cluster process set, writer namespace closure,
   dependency trees, targeted test doors, cold/warm boot, mint, live feed,
   browser action, CPU, heap, event-loop delay, and RSS.
4. Build the current CLJS artifact and writer artifact from a clean dependency
   state far enough to expose packaging defects honestly.
5. Create an annotated pre-removal tag or protected archive branch. Add one
   concise pointer document; Git is the archive.

Exit proof: one known commit can still start, mint/resume an agent, commit and
replay a transaction, render the web UI, and process a canvas form. Every
subsequent deletion is recoverable from the archive ref.

### Phase 2 — isolate the permanent JVM server

1. Atomically rename the database boundary in place: `seon.store.wire` to
   `seon.db.wire`, `:seon.store.wire/*` to `:seon.db.wire/*`,
   `seon.server.store` to a database-owned namespace, and every Seon
   `store-id`/`store-path`/`store-name` contract to its database/backend name.
   Rename the managed filesystem leaf from `/store` to `/db`; test databases
   need no migration. Do not leave aliases or dual protocol keys.
2. Fold the exact Datahike/Konserve fork, secondary-index source, JVM flags,
   writer dependencies, and main class into one honest server build contract.
3. Split dependency ownership into minimal shared, CLJS, writer,
   writer-test, build, and tool aliases. Remove accidental transitive reliance.
4. Fix `writer-uber` and preflight the artifact produced from the same basis
   used by local launch.
5. Add `bin/test-writer` with only writer, receipt/replay, schema bridge,
   IDs, branches/restore, storage, codec, and embedding tests.
6. Delete `seon.server.reactive`, its boot schemas/ops/hooks, and the
   in-process subscriber registry.
7. Delete the duplicate string Transit helper, unwired agent registry, facts
   POC, fake SQLite path, and unused filter/entity/pull/batch wire operations.
8. Replace arbitrary writer-REPL administration with a small typed
   root/supervisor admin surface for database/branch lifecycle and bounded
   diagnostics.
9. Keep the UDS transaction/receipt/raw-commit/replay path unchanged as the
   correctness baseline.

Exit proof: a standalone JVM process loads only the retained server closure,
opens fresh and existing stores, commits/recoveries one request, broadcasts and
replays it, runs optional KNN work, performs typed admin operations, and drains
cleanly. No paused application or nREPL namespace loads.

### Phase 3 — replace the operator, archive the old application, and cut test tax

1. Replace `bin/seon` in place with a thin launcher and Babashka
   `seon.dev.cli` library. Process graph, dependencies, readiness, locks,
   artifacts, and transitions are data.
2. Preserve PID+OS-start identity, process-group ownership, atomic lifecycle
   locks, stale-artifact cleanup, idempotent reconciliation, reverse drain, and
   scoped destructive safety.
3. Make `up` start the owner-selected complete development stack and
   `--open` the only browser-launch switch. Bound the Shadow JVM and make the
   latest build result—not an old log line—its readiness truth.
4. Publish build inputs/outputs through one atomic artifact manifest. Remove
   presence/mtime heuristics and special benchmark artifact paths.
5. Remove global nuke. Reset only a named cluster after proving its writer and
   readers are drained.
6. Port the few useful syntax/markdown/docstring checks to a direct
   Babashka/tool door. Delete the dead nREPL hook pipeline and update hook
   configuration atomically.
7. Delete the paused Integrant/core.async JVM application, old agent/providers,
   context/graph/session/embedded DB, JVM renderer/web/SSE, old MCP/REPL, app
   resources/profiles/aliases, and their tests.
8. Delete the disabled-test graveyard and the Wasmtime/WIT, Rust client-runtime,
   old libdatahike CLJS, and unused harness trees after their evidence is linked.
9. Keep two primary code gates: focused `bin/test-cljs` and focused
   `bin/test-writer`. Separate fast pure tests from explicit runtime,
   subprocess, browser, and process acceptance tiers.
10. Remove test/demo preloads from the ordinary pod artifact. Delete hidden
    list/poll/kill and tail-retry-to-green runner behavior. Every async test has
    one bounded terminal.

Exit proof: a clean source/dependency search contains only the JVM server and
active shared CLJ/CLJC sources; `bin/seon up` brings a nontechnical user to a
ready URL; no port 7888/8080 or paused process exists; focused tests do not load
or discover archived behavior.

### Phase 4 — finish database truth and lifecycle reconstruction

1. Finish one exact desired-population compiler: scalar, cardinality-many,
   component, removal, full-head fence, bounded reread/recompile, and
   transact-if-nonempty.
2. Make external config operation-scoped and optional. Explicit apply freezes a
   canonical desired payload and repairs only declared populations; no-input
   restart preserves database facts.
3. Persist full canonical Malli forms and native backend signatures in the
   database. Reopen installed Datahike schema instead of reasserting it.
4. Build one validated Malli/catalog candidate from the database and swap only
   after durable validation succeeds. Remove persisted derived schema
   decomposition and atom-backed duplicate authority.
5. Use one analyzer/program snapshot and one exact add/change/remove
   transaction. Verify the ghost-pruning builder and every stale compatibility
   branch are absent.
6. Instrument once at cold boot and incrementally for changed function/schema
   dependency closures after commit.
7. Reconstruct declarations/program state only. Never replay arbitrary evals or
   process-local values.
8. Finish the canonical `{database-id, branch, commit-id, t}` coordinate through
   reads, receipts, feeds, turns, caches, bookmarks, and errors.
9. Finish read-only as-of, same-database writable branches, non-autonomous forensic
   runtimes, quiesced restore/undo, branch-local blobs, and crash recovery
   through the maintained Datahike lifecycle.

Exit proof: fresh, converged, partial-config, config-free, hot-reload, mint,
resume, as-of, fork, restore, undo, and crash-boundary transitions satisfy the
acceptance table with no broad rewrite, physical copy fork, arbitrary replay,
or duplicate runtime registry.

### Phase 5 — converge the local web UI and agent-facing surface

1. Freeze the vocabulary in active architecture, then rename the existing
   symbols in place:
   `last-updated-surface`, `::surface-sym`,
   unresolved-canvas warning, error-card seam, surface renderers, roster cards,
   `#surface-*`, and `.seon-card*`.
2. Update every producer/consumer/schema/test and regenerate CSS atomically.
   Do not leave forwarding vars or old selectors.
3. Delete exactly the four dormant context-display namespaces and their display
   tests while preserving their underlying domain/runtime facts. Delete the
   entire duplicate inventory family: `db/store-inventory`,
   `my.kb/inventory`, the root-canvas “STORE” panel, header inventory call/link,
   `/data`, `/data/sse`, their whole-database scans and legacy feed registry,
   warning coupling, teaching references, and brittle tests. Keep
   installed-schema and direct attribute-presence queries as the small
   composable discovery tools. A later KB surface must be a focused domain
   query through the normal block/render/surface mechanism, not a restored
   global browser.
4. Make `seon-skills` the canonical distributable skill source; generate or
   validate tool-facing views mechanically. Delete `ui-live-tiles` and stale
   world/inspector/`my.tile` teaching.
5. Keep `my.canvas` as the permanent API, make its leaf encodings
   browser-portable, and ensure its docstrings/Malli errors make buttons,
   inputs, selects, toggles, forms, state, save, pin, and clear self-explanatory.
6. Complete stable render-unit membership, runtime database-read observation,
   changed-result invalidation, bounded compositional caches, and identical
   output suppression in the existing Datastar feed.
7. Pay only for open/visible work: debug remains an empty shell until opened;
   offscreen/closed bodies are stubs; hidden source/result/error trees are not
   constructed.
8. Finish the responsive layout: full-height primary canvas, independent
   readable right rail, bounded fonts/code, compact plan disclosures,
   transcript bottom anchoring, no visible focused duplicate, and no live-bar
   overlap.
9. Prove deliberate focus: canvas/domain writes select canvas; an agent reply
   selects transcript; human selection stays locally sticky until invalid.
10. Prove every `my.canvas` control with valid, invalid, rejected, rapid, and
    throwing handlers. Feedback is structured and visible to the agent.
11. Cold-prove the default cluster, then coordinate the same no-alias cutover in
    ACME and rebuild/reset it.

Exit proof: one database transaction causes only affected units to render and
one Datastar path to update; the agent view, compact previews, forms, focus,
scroll, debug view, CSS, skills, and ACME use the same canvas/surface/card
contract. Grown-database idle feeds do not repeat SCI/HTML work or sawtooth RSS.

### Phase 6 — harden and adopt one remote writer/replica protocol

1. Define one fully namespaced Malli protocol for attach, sync, command,
   receipt, commit notification, resume/control, heartbeat, deadlines,
   backpressure, and terminal errors. Transport framing is an adapter.
2. Keep transaction semantics in Seon's writer owner: durable request ID,
   payload fingerprint, candidate allocation, effective datoms, exact receipt,
   and bounded forensic replay.
3. Promote/package Datahike's `src-kabel` and its pinned Kabel,
   distributed-scope, and Konserve Sync dependencies in the maintained fork.
   Do not vendor-copy those namespaces into Seon.
4. Fix the general library gaps in their owning sources:
   node/head ordering, full handshake drain, multi-client foreign listeners,
   finite catch-up deadlines, cancellation, reconnect, page reload,
   backpressure, branch scoping, and clean shutdown.
5. Make immutable-root synchronization the canonical remote replica data
   mechanism. Emit an explicit `root-advanced` event for coalesced catch-up;
   do not pretend it is one ordinary transaction.
6. Feed exact live commit notifications through native listener semantics once.
   Durable processors query remaining actionable facts under fences; pure
   projections may derive once at the current head.
7. Drive the same state-machine behavior suite over UDS and WebSocket adapters.
8. After parity proof, delete overlapping custom remote sync/reconnect code and
   the disconnected Datahike tx-broadcast path. Keep one bounded exact replay
   operation, not two event systems.

Exit proof: cold/warm/interrupted sync, two-client foreign writes, every
lost-request/reply edge, read-your-own-write, replay/live overlap, branch
movement, restore, backpressure, and shutdown all preserve one logical commit
and one readable coordinate. No unbounded promise or global fan-out remains.

### Phase 7 — ship server, browser, native-shell, and cloud deployment modes

1. Ship thin remote web mode first: the canonical Node CLJS UI host runs beside
   the JVM and serves Datastar HTML to a browser with no local database.
2. Split Node-specific adapters from portable CLJS render/program/database
   code in place; do not create a browser-v2 namespace tree.
3. Add one browser Shadow target using the canonical renderer and a
   memory/IndexedDB tiered Datahike replica.
4. Restore only the program graph required for active local surfaces. Server
   action functions and heavy capabilities remain server-side.
5. Add a bounded durable outbox of typed, stable-ID transaction/fact intents.
   Datahike optimistic overlays improve immediate display but are never durable
   authority. Arbitrary server actions require connectivity.
6. Initially sync the complete implemented root/history contract and measure
   it. Add a Datahike-owned current-state export/origin format only if potato
   client measurements require it; do not invent an unproven datom applier.
7. Publish the browser build/protocol from Seon core. Package Tauri 2 or another
   native webview shell downstream with only secure storage, notifications,
   file/device access, and lifecycle bridges.
8. Integrate one selected Konserve cloud backend behind the JVM database
   compiler.
   Benchmark direct cloud authority versus an explicitly specified local-hot
   topology; state acknowledgment durability/RPO honestly.
9. Keep cloud credentials, secondary indexes, embeddings, branch mutation, and
   heavy queries on the server.

Exit proof: server agents remain available with the user's device offline; a
thin browser works from a clean profile; a full browser reloads from IndexedDB,
reads offline, reconnects, drains idempotent intents, and renders the same
surface data; the selected cloud topology survives writer crash/restart and
client resync.

### Phase 8 — acceptance, profiling, documentation, and graduation

1. Run focused structural/generative tests during each phase, then run the
   complete active CLJS and writer gates once at the boundary.
2. Complete Inspect AI journeys for durable multi-step planning across restart,
   schema-backed write/later-query, and interactive UI construction.
3. Run the full transition table from a destructively reset authorized default
   cluster, including failure injection at every process/protocol/restore/cloud
   boundary.
4. Browser-drive roster, agent, debug, canvas controls, focus, scroll, route
   facts, two tabs, reconnect, and responsive layouts. Verify gzip SSE
   server-side.
5. Profile cold/warm boot, five mints, writer latency, sync, dirty-unit renders,
   SCI invocations, gzip bytes, browser morph time, event-loop delay, CPU, heap,
   GC, and RSS on small and grown stores.
6. Establish explicit budgets and fail loudly when agent-authored database
   reads/renders exceed them; do not hide unbounded work by increasing timeouts.
7. Update active architecture, skills, runbooks, Docker/build docs, and
   operator help to describe only proven behavior. Mark historical material as
   history rather than rewriting it.
8. Prove the default cluster first, then ACME. Mark the PRD complete only after
   active searches and runtime process/classpath inspection find no superseded
   mechanism.

Exit: fast, stable, responsive agents; one writer, one CLJS runtime/UI, one
protocol state machine, one operator, one test authority split, one vocabulary,
and no known duplicate or compatibility path.

## Owner decisions before phases 6–7

These choices do not block the archival/server/operator work, but they materially
change the remote/cloud implementation:

1. **Remote replica:** approve immutable Konserve root synchronization as the
   first supported remote replica, with exact datom-apply deferred unless
   Datahike gains and proves a native replica primitive? **Recommendation:
   yes.**
2. **Cloud durability:** must a successful transaction wait until GCS/S3 has
   durably accepted the commit, or may a local hot database acknowledge under an
   explicit nonzero cloud RPO? **Recommendation: require zero acknowledged
   cloud data loss for authoritative facts, then benchmark direct GCS first
   because the current deployment already uses Google infrastructure.**
3. **Client sequence:** approve thin server-rendered mode first, then the full
   browser/IndexedDB/Tauri replica from the same CLJS source? **Recommendation:
   yes.** This delivers hosted agents early without creating a temporary
   renderer.

Defaults that do not require an owner decision now:

- durable offline mutation is a bounded stable-ID outbox plus optimistic
  projection, not a local authoritative branch/CRDT;
- initial clients use the complete implemented root/history sync and route
  heavy/history work to the server only after a real current-state export
  exists;
- GCS is evaluated first, S3 is not added speculatively;
- generic remote Datalog/admin operations are kept only when a named bounded
  capability requires them; and
- `seon-skills` is the canonical distributable skill source, with tool-facing
  views generated/validated rather than hand-maintained.

## Commit and proof policy

Each numbered phase is several small commits, not one giant patch. A normal
sequence is:

1. contract/schema or build-boundary commit;
2. implementation and caller cutover;
3. old-path deletion;
4. focused behavioral proof;
5. cold/live evidence and active-doc update.

Do not begin the next phase while the current phase has a known broken
transition. Report remaining work honestly; a green test suite never overrides
the running system.

## Definition of done

- A new user runs `bin/seon up`, sees truthful progress, receives the web UI
  URL, and can operate agents without knowing process names.
- Cold and warm starts are bounded, idempotent, and free of ghost pruning,
  broad schema/program rewrites, global instrumentation, or duplicate services.
- Agent mint/resume/eval and config/schema/program/restore transitions are
  explicit and database-correct.
- One JVM server owns writes/storage/heavy work; one CLJS source owns agents and
  rendering on server and client.
- Local and remote clients share one semantic protocol and reconnect state
  machine.
- Canvas, surfaces, cards, blocks, slots, and views mean one thing everywhere;
  tile/live-tile/world/inspector are absent from active product vocabulary.
- The normal web UI is bounded and reactive; closed debug/offscreen content
  costs nothing; context and HTML render only when used.
- Buttons, inputs, selects, toggles, forms, errors, focus, and scroll work live
  from database facts.
- The active test doors are fast by default, bounded, behavioral, and contain no
  retired application or homegrown evaluator.
- Server agents, thin browsers, full browser replicas, and downstream native
  shells use the same data/render contracts.
- The selected cloud backend has measured durability, latency, recovery, and
  cost behavior.
- The old JVM application and rejected prototypes are recoverable from Git but
  absent from active source, classpaths, startup, tests, docs, and skills.
