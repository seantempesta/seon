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
- one versioned local writer protocol with a clean future remote-transport seam;
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

**Current phase: finish the operator cutover and test trim (phase 3 of 6).**
The permanent JVM database server is isolated, duplicate runtime authority is
removed, the paused application is archived, and the Babashka operator has
cold-started and restarted the default cluster through its one public door.
Remaining phase-3 gates are ACME/Inspect caller migration and consolidation of
the active test doors. The broken JVM gold-patch replay scripts and dangling
`bin/test-clj` symlink are deleted while their dated evidence remains. Remote
replication, cloud topology, browser
replicas, offline mutation, mobile packaging, and the full paid Inspect AI
battery are explicit follow-on work rather than completion gates for this
branch.

The shared ACME/plan/REPL work is checkpointed at `3e0e0bff`; the directly
affected schema, plan, and AI dispatch CLJS namespaces pass their focused tests,
and `runtime-reliability-pre-refactor-2026-07-13` anchors the complete
`b4efd4f5` handoff. The Phase 1 baseline is
[[research/phase-1-baseline-2026-07-13]]. Since that capture, `writer-uber` and
source launch have converged on one complete `:writer` basis. The writer closure
is down from 188 libraries/194 classpath roots to 111/117, resolves the exact
maintained Datahike/Konserve SHAs, and has one SLF4J provider. `bin/test-writer`
runs only the retained writer suites. The unused query-subscription engine,
second in-process subscriber bus, dead writer operations, and alternate backend
routing are deleted, leaving raw committed-transaction fanout plus bounded
replay. The evaluator's global timeout and duplicate result-membership registry
are gone; timeout ownership follows the value and result membership is derived
from the runtime namespace. The web host now has one normalized feed registry,
database-fact-driven route invalidation, and one explicitly owned replica-feed
attachment lifecycle. The focused Datastar gate covers 38 behavioral tests
with 182 assertions. Fifteen replica tests cover 87 assertions, and 5 route tests cover
74 assertions. Writer database initialization, transaction transformation, KNN,
and publication now enter through one immutable boot-composed runtime; the
load-order callback registries are deleted, and initialization failure can no
longer publish a half-initialized connection. The live web channel now also has
one lifecycle-owned, lossless bounded coalescer: Datahike's stable listener key
is the installation authority, a coalesced window retains its complete database
evidence, and continuous structural commits cannot postpone a render past 500
ms. The atomic database-protocol cut is now implemented: keyword operations and
fully namespaced maps live once in `seon.db.protocol`; the JVM writer/server,
CLJS replica, backend adapter, connection registry, and UDS transports have
single responsibilities; legacy server/store namespaces are deleted; and the
managed database leaf is `/db`. Fifteen replica tests (87 assertions) and the
eleven-namespace writer gate (47 tests/295 assertions) cover retry/recovery,
replay/live overlap, explicit routing, generated identities, durable receipt
encapsulation, bounded publication, and lifecycle. Typed administration, cold
live transition proof, and a published artifact manifest remain outstanding.

The archival cut is now committed. `38a4dbe8` removes the atom-backed agent
membership registry and derives MCP addressing from database agent facts;
`294d47a1` removes the obsolete Rust/WASM and old Datahike prototype trees; and
`6c1079c8` removes the paused Integrant/core.async application, its entrypoints,
resources, dependency aliases, and obsolete tests. The surviving writer gate
passes 47 tests/295 assertions, direct Markdown tooling passes 22/340, and the
runtime-addressing gate passes 4/16. The large Bash supervisor is now replaced
in place by a seven-line launcher over one Babashka process graph. Kernel file
locking, exact process identity, bounded readiness-log reads, relevant-
environment digests, artifact manifests, scoped reset, and fail-closed process-
group ownership pass 10 focused tests/29 assertions. Phase 3 remains open for
active caller and test-door migration; the default-cluster cold live proof now
passes, so ACME and Inspect can follow.

The latest 2026-07-13 cold reset rebuilt a fresh default database and returned
READY. A subsequent config-free status independently reported the watcher,
writer, and pod alive and ready; operation-scoped `SEON_CONFIG` no longer poisons
permanent process identity. The pod attached its replay/live feed, replayed 2/2
forms, instrumented 767 definitions with zero bad specs, and created `root` plus
`mighty-spoons-clap`. `/` and `/data` returned HTTP 200, while the retired
`GET /agents` correctly redirects to `/`. The database-defined `POST /agents`
created readable-word agents in both direct HTTP and real-browser button proofs.
The new agent view rendered its canvas, plan, and transcript surfaces without a
browser console error; its gzip feed delivered an immediate Datastar patch. A
single-process mutation proof observed a 307 ms POST-to-patch interval, including
a 68 ms targeted render. All three long-lived processes returned to 0% sampled
CPU after agent work stopped. The cross-agent invalidation gap found by that
proof is now closed through the existing database-read observer: each rendered
surface and header owns immutable query/pull/entity observations, the normalized
subscription learns them on its shared first paint, and later candidate changes
replay results before entering Hiccup or SCI. A behavioral test proves the same
attribute changing on agent B does not materialize agent A's surface.

The canonical live-feed cut now includes `/data`: its separate connection atom,
listener flag, coalescer, uncompressed `/data/sse`, and the unused generic
`/sse` registry are deleted. A cheap `/data` shell opens `/data/feed`, which
uses the same gzip, heartbeat, latest-wins backpressure, response-owned cleanup,
and normalized subscription cache as agent/debug views. Live proof observed a
database transaction produce a second data-browser morph and then retracted the
proof row. A first-paint ownership bug discovered during that proof is fixed at
the shared feed boundary: pre-normalized sockets can no longer alias through a
nil cache key and receive another page's HTML. Twenty-four equivalent agent
feeds completed first paint within a 1 ms spread, closed back to empty view and
subscription registries, and used about 66 MB less heap than the prior
comparable run. The optional Caddy edge served the same gzip feed over HTTP/2
with immediate flushing; it remains outside the default development process
graph.

The UI vocabulary cut is now underway in the existing render path. Core focus
derivation uses `last-updated-surface`/`::surface-sym`, unresolved canvas facts
use one canvas warning, the overridable failure seam is `error-card`, block
slots use stable `#surface-*` identifiers, and the generated stylesheet uses
`.seon-card*` plus `.surface-focus`. Focused recency, warning, render, canvas,
and agent-view suites pass with no forwarding aliases. Remaining active prose,
helper names, and downstream ACME references are part of the same in-place cut.
Canvas resolution now also has one authority: explicit pin, configured canvas
block default, derived focus, then welcome. The human renderer returns that
resolved metadata to the context block, eliminating the split reader that made
root describe `system-view` while displaying the welcome. Live root proof shows
the configured system view in both projections and a 214-token canvas block.

The CLJS test process now installs the pod's existing third-party log gate as a
Shadow preload before any test namespace. A representative database run fell
from about 1.85M estimated tokens of trace-heavy output to about 43 estimated
tokens with the same 43 tests/329 assertions passing. Canonical timestamped
test logs are bounded to the newest 20, and normal client/ACME/bench bundles no
longer preload the platform test graph.

The direct Babashka edit hook now proves repository containment before it loads
configuration or writes diagnostics, serializes bounded diagnostic writes
across concurrent hook processes, and cannot throw from its terminal log sink.
The disabled-but-retryable Gemini queue, timestamp, and pending-file mechanism
are deleted; model review is explicit rather than an automatic network side
effect of editing a file.

The public operator now owns `test pod|database|operator|all` and delegates to
the existing CLJS and writer runners. The operator gate includes lifecycle,
artifact, Markdown, and docstring behavior; it no longer leaves the two linter
suites orphaned. The underlying focused scripts remain implementation doors,
not competing harnesses.

Focused pod selectors now drive Shadow's native compile-time `:namespaces`
input as well as runtime selection. The one test bundle has a portable
compile-plus-run owner lock, and `--no-build` requires an exact content
fingerprint over namespace selection, source/config/dependency inputs, and
downstream flavor. Concurrent agents cannot overwrite one another's running
artifact, dead locks recover, and stale bundles fail loudly.

The writer test process now suppresses only `datahike.writer` error logging:
expected transaction-conflict cases remain behavioral assertions, while their
repeated full stack traces no longer dominate a successful focused run.

The test runner's bounded full-result atom and `last-result` API are deleted.
Full run values already return through the evaluator's addressable result
symbols; only durable, queryable per-test outcome facts are projected into the
database. There is no second process-local result-history authority.

The source-substring test dependency heuristic is also deleted from both auto-
rerun selection and function status rendering. Newly defined tests still run
from the exact analyzer diff; existing-test reruns wait for durable analyzer-
derived reference facts rather than manufacturing relationships from text.

Platform tests are no longer a boot-time program-graph population. The obsolete
test preload, compile-time deftest enumerator, `!indexed-test-vars`, and
`index-tests` builder are deleted. Agent-defined tests enter through the same
analyzer tee as other declarations; the compiled snapshot reconciler removes
legacy boot-authored test rows while preserving agent-authored ones.

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
- [[research/root-view-presence-crash-batch-audit-2026-07-13]]
- [[research/cljs-test-suite-speed-and-quality-audit-2026-07-12]]
- [[research/phase-1-baseline-2026-07-13]]

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

The route schema also now records its one same-origin middleware gate as one
keyword fact. The previous vector schema became unordered cardinality-many data
in Datahike and falsely promised middleware-chain ordering that the database
could not preserve.

Those gains are the base. The remaining work is not a restart from scratch.

## Target system

### Runtime roles

| Role | Owns | Does not own |
|---|---|---|
| JVM server | serialized Datahike writes, durable Konserve storage, transaction receipts, branch/as-of/restore, schema/config commit authority, embeddings, secondary indexes, bounded heavy work | agent execution, context rendering, HTML, a duplicate application |
| CLJS UI host and agent runtimes | agent loop/eval, program reconstruction, context derivation, canvas/surfaces, Hiccup, Datastar, server-hosted agents | authoritative writes, cloud credentials, a second database |
| Browser | thin Datastar HTML, per-tab navigation identity, human input, and device-originated facts | authoritative writes, a local full-history database, JVM-only indexes |

The local development composition co-locates the JVM writer, Shadow watcher,
and Node CLJS runtime. A hosted deployment may run the same JVM server beside
headless Node CLJS agent/UI processes. Phone-class clients are intentionally
thin and connect to that hosted cluster; local phone data enters through typed
facts. Browser replicas and a native shell are later work, not a second runtime
introduced by this refactor.

### Current local data path and preserved remote seam

The current refactor proves two local contracts without turning them into
independently configured systems:

1. **Commit notification** — old/new coordinate, effective datoms, changed
   attributes, request ID, and transaction metadata for listeners, dependency
   invalidation, and durable processors.
2. **HTML delivery** — complete-element Datastar morphs for thin clients.

The authoritative local writer acknowledges a transaction after the local
Datahike commit and its same-request receipt are accepted; it does not wait for
a UI replica, remote mirror, or future cloud copy to catch up. Exact bounded
transaction replay remains available for receipts and forensics. Coalesce
notifications, never state.

The source-grounded immutable-Konserve-root and Kabel research is preserved for
a later remote-replica PRD. It does not justify retaining a second live routing
path in this branch, and its unresolved cloud/RPO/client choices do not block
the local system.

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

The active result-persistence ceiling follows the same rule:
`:seon.config.render/database-edn-cap`, `seon.config/database-edn-cap`, and
`SEON_RENDER_DATABASE_EDN_CAP` are the one schema/accessor/environment family.
The obsolete comparison manifest is deleted; config-free boot now means the
database remains authoritative rather than silently falling back to legacy
context.

Namespace ownership follows the same vocabulary:

| Namespace | Owns |
|---|---|
| `seon.db` | canonical public query/transaction/database API on each platform |
| `seon.db.protocol` | one platform-neutral message schema and pure protocol data transformations |
| `seon.db.backend` | JVM-only translation from fully namespaced Seon database options into private Datahike/Konserve config maps |
| `seon.db.registry` | JVM-only live connection/database/branch registry and lifecycle |
| `seon.db.browser` | bounded, index-backed, read-only projections used by the canonical `/data` database browser |
| `seon.db.transport.uds` | local Unix-socket framing and delivery only |
| `seon.db.transport.websocket` | later remote framing and delivery only |

Protocol semantics never live in a transport adapter. Every Seon-owned map key
is fully namespaced to the namespace that specs and manages it.

### Database browser target

`/data` is the one operator-facing database exploration view. It describes
facts as attributes, entities, references, transactions, and history—never as
entity kinds and never as an unqualified “inventory.”

| Region | Default cost | Expanded capability |
|---|---|---|
| database bar | O(1) database datom count/head coordinate plus installed-schema size | branch/as-of coordinate selection when lifecycle support lands |
| attribute navigator | installed schema only; grouped visually by attribute namespace | selected attribute schema, bounded AEVT/AVET rows, values, carrier entities, and cursor |
| entity table | one cursor-bounded page for the selected attribute/search | sortable visible columns only; no complete pull of offscreen rows |
| entity detail | absent until selected | EAVT facts, identity, outbound refs, reverse refs, provenance, and bounded entity history |
| transaction browser | absent until selected/opened | latest transaction metadata, user/process/instant, effective datoms, and bounded history reconstruction |
| raw data | closed stub | exact EDN/datoms for the selected bounded object, rendered only when expanded |

Navigation state is encoded in validated URL parameters so links, reloads, and
back/forward work without database writes. Index cursors replace offset walks.
A page reads at most `page-size + 1` rows to prove whether another page exists;
it does not compute an exact global count merely to render pagination. Total
datoms use the database index's counted root rather than Datahike `metrics`,
whose per-attribute diagnostics scan the complete EAVT index. Transaction
reconstruction is explicitly on demand and budgeted because Datahike does not
currently expose a TX-leading primary index.

The browser is intentionally complete: knowledge-base facts, plans, messages,
agent-authored domain attributes, schemas, framework facts, and transaction
metadata are all reachable through the same attribute/entity/ref/history
machinery. User/domain attributes lead and framework/system groups begin
collapsed, but no second KB inventory or hidden data path is created.

The source-grounded access rules are:

- EAVT cursors page entities/facts; AEVT cursors page one attribute's carrier
  entities; AVET sorts/searches values only when that attribute is indexed.
- Datahike Datalog offset/limit is not browser pagination because it slices
  after collecting/deduplicating results. Browser pages use `seek-datoms` or
  `rseek-datoms` and opaque validated cursors.
- Non-indexed values are bounded AEVT samples labeled as such; the UI never
  implies that unsupported value sorting/search is complete.
- Reverse refs probe the schema's indexed ref attributes lazily. There is no
  cross-attribute incoming-ref index, so “all incoming refs” never becomes one
  unbounded wildcard query.
- Add a general Datahike `count-datoms` API backed by the existing subtree
  `-count-slice` primitive, with CLJ/CLJS behavioral tests and Seon wrapper.
  Keep it library-general and upstreamable; do not cache counts as database
  facts.
- Transaction IDs page backward arithmetically from the database head and
  metadata reads by exact EAVT prefix. Exact transaction datoms remain a
  capped, explicitly opened history reconstruction. If profiling proves that
  inadequate, add a Datahike-owned transaction-leading index rather than a
  Seon transaction projection.

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

In a source checkout, every `up` performs one complete canonical writer + CLJS
build before process reconciliation, then leaves file watchers running for
incremental updates. The build artifact digest is the launch truth: a changed
artifact restarts only its dependent process; an unchanged artifact proves the
running code without a stale-log or mtime shortcut. A packaged installation
verifies immutable shipped artifacts instead of pretending to be a source
checkout.

Readiness is one atomic application-ready fact backed by direct process/socket/
HTTP verification. There is no fixed three-second stabilization ritual.

### First run, root, and human navigation

A provably fresh database is initialized once from the explicitly selected
manifest, creates the reserved root plus one ordinary readable-word agent, and
prints both URLs. `bin/seon up --open` opens the ordinary agent; `/` remains the
root system view rather than the default work destination.

Root is the system-scoped coordinator. It may technically do ordinary work, but
its small root-only context tells it to understand the fleet, start an ordinary
agent when necessary, route/delegate work, and move the human to that agent. The
role text stays deliberately short. Operational knowledge comes from root's
fully specified home-require namespace cards; entering a namespace makes its
source current and brings in the colocated/state-gated context for that work.
Root's home requirements are one complete, deliberately smaller role-specific
list, replacing the ordinary agent list through the existing scalar override.
That lets root omit workbench capabilities it has not proven it needs; do not
add a second union/merge rule. The root canvas's bounded AI twin supplies current
fleet facts through the existing canvas block; there is no second fleet-summary
instruction block. No skills catalog or long generic manual is injected merely
because the agent is root.

The root canvas is the fleet view. Its cheap shell lists every agent with
identity, purpose, derived state, and the label of its shared agent-derived
focus (pin, then agent recency, then welcome). Each human-facing agent card uses
the same surface catalog, agent-derived focus function, and compact materializer
as an agent page with no session override; the current
`seon.ui.agent-view` functions and `:seon.ui.agent-view/*` working-map keys move
to `seon.render.surface` / `:seon.render.surface/*`, and their old definitions
are deleted. Visible/expanded cards are independent view units, so one agent
update does not rebuild every preview. The root AI twin
always carries the complete compact agent list, then spends a bounded detail budget
on running, erroring, and most-recently-active agents: up to five recent
messages, recent failed-eval summaries, and the bounded AI render of their
canvas. Omitted detail is explicit, never mistaken for an absent agent.

Each browser tab has one database-backed UI-session identity. The session stores
one normalized local location fact plus a ref to the human; the transaction
already supplies recency/provenance, so no duplicate `updated-at` or active flag
is stored. On an agent page, an explicit surface pin is encoded in that
location's query component; page focus is the valid session pin when present
and the shared agent-derived focus otherwise. A root card never claims to mirror
another tab's pin. Unpinned selection, scroll position, open disclosures, and
form signals remain transient.
A human message carries the originating session ref, and each turn records the
exact inbound message it is assigned to answer. Root's fully-specified
navigation function follows turn → cause-message → web-session through normal
injection, reverse-routes an agent target, and updates the same location fact.
The tab's existing Datastar feed applies the official Datastar redirect-helper
semantics for that changed fact. In the reference SDK this is an auto-removing
script patch on the existing stream, not a second redirect event or channel.
Browser navigation writes the same fact, so root can query what the human
is seeing without a parallel presence service. Per-tab identity prevents two
open tabs from fighting over one global cursor.

### Skills are importable data, not standing context

The existing `my.skills` corpus/import mechanism is retained and refined in
place. A standard `SKILL.md` directory, CLI import, or later web upload all pass
through one parser/validator and transact the same canonical skill source facts;
config-free restart reads those facts from the database rather than requiring
the original upload path. `seon-skills` is the shipped corpus source and tool
directories are generated or validated adapter views.

Importing a skill does not install a permanent skills context block. Default and
test agents keep that block disabled so dynamic context, compact namespace cards,
current-namespace source, and colocated state-specific blocks must surface what
is actually needed. Explicit skill loading remains available as an override and
is evaluated behaviorally, not by asserting prose.

## Settled invariants

- The JVM application is archived; the JVM server is permanent.
- The canonical renderer is CLJS. The JVM never grows a parity renderer.
- `seon.db` remains the sole application database API.
- The database stores facts and canonical source forms, not processing traces,
  dirty flags, render output, or derivable lifecycle state.
- Config is optional on an existing healthy database. When explicitly selected,
  it repairs exactly its declared subset and does nothing when converged.
- A fresh writable database receives one explicit genesis/config floor, the
  reserved root, and one ordinary initial agent. This one-time birth is not a
  config-managed population on later boots.
- Malli runtime state is rebuilt once from canonical database facts. Committed
  eval changes carry exact symbol deltas; Shadow reloads query only the namespace
  resources Shadow actually loaded and restore only wrappers that are absent.
- Arbitrary evals and external effects are never replayed.
- After an unexpected runtime crash, every interrupted nonterminated agent is
  fenced back to derived `:idle`; the supervisor records one recovery anchor in
  that same transaction. The affected agents and ambiguity are projections of
  the transaction, and root renders the notice. Root or the human decides what
  to resume.
- Batch mode attempts every complete parsed form in order. A normal form error
  is persisted and does not suppress later forms; the next turn sees every real
  success and failure.
- Every database identity, map key, and public contract is fully namespaced and
  schema'd.
- `my.canvas` is the one permanent agent-facing canvas/control API; current
  agent/database identity is injected.
- Root has one concise role-specific block plus orchestration/navigation
  namespace cards. It does not receive a long generic manual.
- Skills are importable database facts but not a default context block. Dynamic
  context, compact namespace cards, current-namespace source, and colocated
  state blocks surface relevant capabilities.
- Four dormant display adapters are deleted precisely:
  `seon.agent.ctx.findings`, `inventory`, `jobs`, and `testrun`.
  Durable findings, job execution, and parsed test-run facts remain. The weak
  whole-database `db/store-inventory` API is also deleted, not renamed: schema
  discovery uses installed attributes, domain discovery belongs in small
  purpose-specific database queries, and operator exploration belongs in the
  canonical `/data` browser. A refined KB may compose those facts later without
  restoring a global inventory/context mechanism.
- One skill importer persists exact validated source; `seon-skills` supplies the
  shipped corpus and generated/validated tool views are not authorities.
- One runtime attaches to exactly one `{database-id, branch}` coordinate. The
  existing UDS path is the local behavioral authority; no permanent dual
  routing toggle survives this refactor.
- A successful write is acknowledged after the authoritative local commit and
  receipt are accepted, without waiting for UI catch-up or future cloud
  mirroring.
- One database-backed per-tab UI-session location is the only human-navigation
  state. Root redirects the originating session through the normal Datastar
  feed; there is no second presence or push channel.
- Tests assert facts, transitions, envelopes, DOM identity, omission,
  idempotency, and rendered structure—not teaching prose.
- Every replacement deletes the superseded mechanism in the same phase after
  proof.
- ACME is updated only after the default cluster passes and its current shared
  work lane is clean.

## Known defects to remove

| Area | Current defect |
|---|---|
| JVM source | The retained writer reaches twelve namespaces. The old Integrant/core.async/agent/web application remains searchable until the archive cut. |
| JVM artifact | Source and uberjar use the same complete `:writer` basis with the maintained forks and one SLF4J provider, but no published launch manifest yet records the artifact/runtime contract. |
| Dependencies | The writer and writer-test closures are honest and narrow. Heavy paused-app dependencies still live in the base graph used by old JVM/tools, and CLJS/tool ownership is not yet fully separated. |
| Writer protocol | The semantic protocol, JVM writer/server, CLJS replica, and UDS transports are separated and the duplicate operations/helpers are deleted. A typed supervisor administration surface and cold process proof remain. |
| Database vocabulary | The protocol/backend/replica path is canonical, the managed leaf is `/db`, and the generic `store-inventory` API/context/tooling family is deleted. Runtime and developer skills are converged; downstream ACME still needs the proven vocabulary cut. |
| Database browser | The obsolete inventory surfaces are deleted. `/data` uses the canonical shared gzip feed, cheap shell, schema navigator, and bounded AEVT cursor pages. Entity/ref/transaction/history units remain. |
| Developer hooks | The direct Babashka hook is repository-contained before config/artifact access, runtime-independent, locally deterministic, and log-bounded under a cross-process lock. Automatic model review is deleted. The operator gate includes its Markdown/docstring checks. |
| Operator | The Babashka graph and thin launcher are built and focused-tested; active caller migration plus default/ACME/Inspect live proof remain. |
| Tests | Public pod/database/operator doors delegate to one runner each; focused pod builds use compile-time namespace selection, one bundle lock, and exact freshness fingerprints. Disabled/paused-application tests and remaining intentional expected-failure noise still need removal. |
| UI | The four dormant context renderers and their unconditional boot load are deleted. Active symbols, CSS, DOM, docs, and ACME still need the tile-to-surface/card vocabulary cut; skill teaching is already converged. |
| Live rendering | Agent surfaces and the whole debug/data targets use runtime-observed reads; normalized subscriptions suppress identical consecutive output. Per-region debug/data unitization, layout/focus browser proof, and grown-database profiling remain. |
| Recent activity reads | `seon.render.default/recent-messages`, `seon.agent.ctx/messages`, transcript/activity queries, `seon.derive/real-eval-oks`, and the function menu independently scan and sort growing message/eval history before taking a small tail. Root's current cross-agent activity does the same over the whole database. |
| Root/UI presence | `/` already renders root's system canvas, but first-run routing, concise root role context, originating-tab identity, database-backed current location, and feed-driven agent navigation are not one finished path. |
| Root context | Root's scalar home-require replacement, sparse system-canvas pin, and ordinary-agent fallback are now distinct. Concise root role context and browser-location awareness remain unfinished. |
| Skills | `seon-skills` now generates exact shared tool adapters, Codex-only operator skills generate their Claude views, and the operator suite rejects drift. File-backed imported bodies still depend on source paths after import. |
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
| `bin/seon up`, source checkout | fully rebuild and publish canonical writer + CLJS artifacts; no database write when converged | reconcile changed artifact dependents, start watchers, wait for atomic readiness, print URLs | no stale artifact/log truth, fixed delay, duplicate process, or manual build prerequisite |
| `bin/seon up`, packaged | verify immutable shipped artifact manifest | reconcile process identities/readiness | packaged mode never silently compiles a different program |
| fresh database | minimal genesis, native schema floor, root/process refs, explicitly selected initial config, root plus one ordinary agent | rebuild Malli/program runtime and services; `--open` selects the ordinary agent | no circular provenance, partial schema, hidden ambient config, or root-as-default-workspace |
| existing database, no config | normally no transaction | rebuild process-local handles/registries; resume durable work | restart does not “heal” by rewriting canonical facts |
| explicit config apply | exact managed-subset delta plus lifecycle intent/recovery facts | invalidate only affected projections | missing/changed/extra facts repaired; outside facts unchanged; convergence writes nothing |
| core/schema hot reload | one exact program/schema delta | load/instrument only changed dependency closure | removal and same-key schema change work; no global rescan or ghost prune |
| agent birth | one allocation transaction for identity and initial components | create compiler namespace, host, listener, wake | no cluster seed/global instrumentation; failed birth leaves no partial agent |
| agent resume | normally none | restore one host/wake from durable facts | arbitrary evals/effects are not replayed |
| unexpected runtime crash | close/fence interrupted runs, terminalize running turns, and persist one recovery anchor in the same transaction | rebuild root and safe transient services; derive the detailed notice; leave affected agents idle | no interrupted form/effect is replayed and root sees exactly which agents may need resumption |
| agent eval batch | one eval/result fact per parsed entry plus resulting domain/declaration facts | execute every complete form in order, capture each error, continue, instrument changed defs | an early ordinary error cannot erase later attempts; a process crash cannot fabricate missing results |
| local write, lost reply | one commit and one same-ID receipt | retry identical request and catch the local reader up to accepted coordinate | different-payload ID reuse rejects; every disconnect edge is at-most-once commit |
| browser action | one typed command/transaction and receipt | Datastar call → writer → commit notification → affected unit morph | no manual refresh, duplicate client state, or silent handler failure |
| root navigation | upsert the originating UI session's normalized location | the same session feed applies one redirect patch to the reverse-routed agent URL | another tab is unchanged; reload derives the selected location from the database |
| root fleet view | none beyond normal agent/session facts | cheap all-agent catalog; visible non-root card units materialize the compact agent-derived focus; bounded AI detail derives separately | every agent is represented in structured summary data; a card equals a no-session-pin agent page and never claims parity with another tab's pinned selection; unrelated cards do not render; token caps are proven without prose assertions |
| debug route closed/open | none | closed owns no debug render/listener; open activates only requested units | prompt/raw/HTML/token work is absent while closed |
| as-of/fork/restore | branch/head/intent facts through Datahike primitives | quiesce, drain, attach exact coordinate, rebuild process state | stale writers/cursors cannot cross head movement; external effects are not undone/replayed |
| stop/reset | only explicit lifecycle facts | reverse-order drain, verify PID+start stamp/process group, then mutate the named database | no global nuke, reused-PID signal, orphan child, or deletion under a live writer |

## Ordered implementation plan

### Phase 1 — review, coordinate, and freeze the archival boundary

1. Let the active ACME/plan/repl-autosuggest lane commit or clearly hand off
   its files. Do not absorb its dirty working tree into this refactor.
2. Record the exact default-cluster process set, writer namespace closure,
   dependency trees, targeted test doors, cold/warm boot, agent birth, live feed,
   browser action, CPU, heap, event-loop delay, and RSS.
3. Build the current CLJS artifact and writer artifact from a clean dependency
   state far enough to expose packaging defects honestly.
4. Verify the existing root system view, root-only blocks, multi-form batch
   behavior, and skill importer against the new settled contract before
   deciding what old material survives.
5. Create an annotated pre-removal tag or protected archive branch. Add one
   concise pointer document; Git is the archive.

Exit proof: one known commit can still start, birth/resume an agent, commit and
replay a transaction, render the web UI, and process a canvas form. Every
subsequent deletion is recoverable from the archive ref.

### Phase 2 — isolate the permanent JVM server

1. Atomically rehome the database boundary in place: `seon.store.wire` and
   `seon.server.wire` converge on shared `seon.db.protocol` plus the local
   `seon.db.transport.uds` adapter; `seon.server.store` becomes
   `seon.db.backend`; `seon.server.registry` becomes `seon.db.registry`; and
   every `:seon.store.wire/*` / Seon `store-id` / `store-path` / `store-name`
   contract becomes the fully namespaced protocol/database/backend term owned
   by that namespace. Rename the managed filesystem leaf from `/store` to
   `/db`; test databases need no migration. Do not leave aliases, forwarding
   vars, or dual protocol keys.
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
opens fresh and existing databases, commits and recovers one request, broadcasts and
replays it, runs optional KNN work, performs typed admin operations, and drains
cleanly. No paused application or nREPL namespace loads.

### Phase 3 — replace the operator, archive the old application, and cut test tax

1. Replace `bin/seon` in place with a thin launcher and Babashka
   `seon.dev.cli` library. Process graph, dependencies, readiness, locks,
   artifacts, and transitions are data.
2. Preserve PID+OS-start identity, process-group ownership, atomic lifecycle
   locks, stale-artifact cleanup, idempotent reconciliation, reverse drain, and
   scoped destructive safety.
3. Make bare `bin/seon` equivalent to `bin/seon up`; `up` starts the complete
   development stack and `--open` is the only browser-launch switch.
4. In source mode, perform one complete canonical writer + CLJS build on every
   `up`, publish it through one atomic artifact manifest, then start incremental
   watchers. Restart only processes whose artifact digest changed. Packaged mode
   verifies immutable shipped artifacts. Remove presence/mtime heuristics and
   special benchmark artifact paths.
5. Replace fixed stabilization waits with one atomic application-ready signal
   plus direct process/socket/HTTP verification. Bound the Shadow JVM and make
   the current build result—not an old log line—its readiness truth.
6. Remove global nuke. Reset only a named cluster after proving its writer and
   readers are drained.
7. Port the few useful syntax/markdown/docstring checks to a direct
   Babashka/tool door. Delete the dead nREPL hook pipeline and update hook
   configuration atomically.
8. Delete the paused Integrant/core.async JVM application, old agent/providers,
   context/graph/session/embedded DB, JVM renderer/web/SSE, old MCP/REPL, app
   resources/profiles/aliases, and their tests.
9. Delete the disabled-test graveyard and the Wasmtime/WIT, Rust client-runtime,
   old libdatahike CLJS, and unused harness trees after their evidence is linked.
   Remove old Inspect run branches/artifacts after proving they are not recent or
   referenced by the concurrently active lane; do not introduce an arbitrary
   retention policy in this refactor.
10. Keep two primary code gates: focused `bin/test-cljs` and focused
   `bin/test-writer`. Separate fast pure tests from explicit runtime,
   subprocess, browser, and process acceptance tiers.
11. Remove test/demo preloads from the ordinary pod artifact. Delete hidden
    list/poll/kill and tail-retry-to-green runner behavior. Every async test has
    one bounded terminal.

Exit proof: a clean source/dependency search contains only the JVM server and
active shared CLJ/CLJC sources; `bin/seon up` brings a nontechnical user to a
ready URL; no port 7888/8080 or paused process exists; focused tests do not load
or discover archived behavior.

### Phase 4 — finish database truth and lifecycle reconstruction

1. **Exact desired-population compiler complete:** scalar,
   cardinality-many, ref/component structural comparison, omitted-attribute
   removal, stale-entity cascade, unmanaged-identity collision rejection,
   full-head fence, bounded reread/recompile, and transact-if-nonempty all run
   through `seon.state/reconcile!`. The maintained Datahike writer owns the
   atomic basis precondition and keeps an expected stale rejection out of error
   logs; the canonical UDS protocol carries the same fact end to end. Focused
   proofs cover first-use schema installation/retry and basis-stable no-op.
2. **Runtime boundary complete:** external config is operation-scoped and
   optional. A config-free boot preserves database facts, the singleton now
   stores agent/root context and skill selection needed for later births, fresh
   `bin/seon up` selects the shipped manifest once, and
   `bin/seon config apply <path>` is explicit. Singleton attribute removal now
   uses the exact compiler, and the old config-heal function/transaction are
   deleted. Remaining: freeze the payload in the supervisor intent.
3. **Canonical form cut complete:** every schema row now persists the full
   EDN-round-tripping `:seon.schema/form`; runtime function/regex objects are
   rejected as durable definitions, schema source replay and the async self-tee
   are removed, failed redefinitions restore exactly, and replay activates
   database forms before code. Native backend reopening remains.
4. **Candidate base complete:** a complete form set now builds and validates an
   immutable Malli registry, entity render catalog, and stable fingerprint
   before activation. The same projection now derives exact direct and reverse
   transitive schema-reference indexes through Malli's walker (keyword data is
   not mistaken for a reference). The renderer consumes that catalog directly; persisted
   required/id/render decomposition, its boot transaction, Datalog discovery,
   and the renderer cache atom are deleted. Remaining: compute compatible
   missing Datahike attributes in the candidate, bound
   historical projections by fingerprint. Agent program/schema transitions now
   build the complete candidate before recording; an invalid dependent contract
   becomes the eval's user-input failure and commits no declaration facts.
   Remaining: stop admission/reconstruct from committed facts if the already
   validated post-commit wrapper publication itself fails. The full evidence and failure matrix are in
   [[research/malli-runtime-schema-authority-audit-2026-07-13]].
5. Use one analyzer/program snapshot and one exact add/change/remove
   transaction. Verify the ghost-pruning builder and every stale compatibility
   branch are absent.
6. **Incremental instrumentation active:** cold boot and Shadow reload compile
   contracts against the exact active immutable registry; an accepted schema
   change refreshes only function contracts in its old/new transitive closure.
   Delta replacement compiles completely before var surgery, so one rejected
   target leaves the prior wrappers untouched, and omitted spec/schema-error
   facts become explicit retractions rather than surviving identity upserts.
   The immutable candidate now also owns every parsed/validated function
   contract and its exact schema-reference index. Cold publication consumes
   that data directly, and schema/function deltas use the old/new indexes with
   no contract-row scan or EDN reparse. Shadow's Node build-notify path now
   selects exactly the resources its Node client actually required; the former
   browser helper returned an empty set after reload and silently left hundreds
   of replaced live vars unwrapped. A cold reset instruments the complete
   projection, and a live reload repairs only the affected namespace rows.
   Remaining: close admission/reconstruct when post-commit publication cannot
   complete.
7. Reconstruct declarations/program state only. Never replay arbitrary evals or
   process-local values.
8. **Crash recovery complete:** the cold-start supervisor transition fence/closes every
   interrupted open run, mark its running turn `:interrupted` without executing
   or fabricating an eval, leave every affected agent derived idle, and persist
   one idempotent recovery anchor in that same transaction. Derive affected
   agent/run/turn refs and prior/current coordinates by joining the anchor's
   transaction to its changed datoms and commit parent; root renders that join
   as the notice. Recovery runs before agent resume, a second pass is a no-op,
   terminated agents are untouched, and focused tests prove no fabricated
   messages. Remaining: have clean planned restarts quiesce at turn boundaries
   rather than masquerading as crashes.
9. Make batch evaluation explicitly non-fail-fast: attempt every complete parsed
   form in order, persist each success/error at its transcript position, and show
   the complete real batch on the next turn. Later dependent forms may fail
   normally; no synthetic results are inserted.
10. On a provably fresh database, create root plus one ordinary agent through the
    normal atomic birth compiler exactly once. Existing/config-repair boots never
    reassert or recreate that ordinary agent.
11. Finish the canonical `{database-id, branch, commit-id, t}` coordinate through
   reads, receipts, feeds, turns, caches, bookmarks, and errors.
12. Finish read-only as-of, same-database writable branches, non-autonomous forensic
   runtimes, quiesced restore/undo, branch-local blobs, and crash recovery
   through the maintained Datahike lifecycle.

Exit proof: fresh, converged, partial-config, config-free, hot-reload, first-run,
birth, resume, multi-form failure, as-of, fork, restore, undo, and crash-boundary
transitions satisfy the acceptance table with no broad rewrite, physical copy
fork, arbitrary replay, or duplicate runtime registry. A crash leaves affected
agents idle and one exact notice visible to root.

### Phase 5 — converge the local web UI and agent-facing surface

1. Freeze the vocabulary in active architecture, then rename the existing
   symbols in place:
   `last-updated-surface`, `::surface-sym`,
   unresolved-canvas warning, error-card seam, surface renderers, fleet cards,
   `#surface-*`, and `.seon-card*`.
2. Update every producer/consumer/schema/test and regenerate CSS atomically.
   Do not leave forwarding vars or old selectors.
3. **Complete:** the dormant findings, inventory, jobs, and test-run display
   adapters, their unconditional boot requires, display-only tests,
   `db/store-inventory`, `my.kb/inventory`, warning coupling, and teaching
   references are deleted. Durable KB facts, job controls, parsed test-run
   facts, and lifecycle tests remain. The header keeps its cheap database link
   and `/data` is the only exploration surface.
4. Port `/data` in place to the canonical render-unit and shared gzip Datastar
   feed lifecycle. **Feed cut complete:** `/data/sse`, `!data-connections`, its
   listener flag, broadcast loop, and the generic `/sse` registry are deleted;
   the route returns a cheap shell and `/data/feed` owns one normalized view
   descriptor. **Bounded navigator complete:** the full `[?e ?a]` plus
   transaction-history scans are deleted. The default reads installed schema;
   selecting an attribute reads a cursor-bounded AEVT page through the shared
   observed-read boundary. **Remaining:** let `/view/unit` activate entity,
   transaction, reverse-ref, and history details only while opened. URL params
   remain the shareable navigation state.
5. Add fully specified, read-only `seon.db.browser` projections backed by
   Datahike indexes and bounded pages: installed attributes/schema, attribute
   values and carrier entities, entity facts/outbound and reverse refs,
   transaction datoms/user/process/instant, and history. Omit unavailable
   sections. List user/domain/KB data first and keep framework/system groups
   collapsed, while making every installed attribute reachable. Counts/samples
   that cannot be obtained cheaply are lazy units with explicit budgets, not
   work performed on every transaction. **Partial:** installed attribute
   grouping, schema detail, AEVT datom rows, opaque cursor continuation, and
   exact reactive replay are complete. Entity/ref/transaction/history units
   remain.
6. Add the general Datahike `count-datoms` public primitive over its existing
   subtree count-slice implementation, then expose it only through the
   fully-specified Seon database API. Use cursor windows—not Datalog
   offset/limit—for every page. Prove CLJ/CLJS, current/history, indexed and
   non-indexed edge behavior in the maintained fork and prepare it for upstream.
7. Give each browser region a stable fully namespaced unit coordinate and
   observed database dependencies. A commit rerenders only the open summary,
   table, or detail whose read result changed. Attribute pages match changed
   attrs; entity/reverse-ref pages match the existing changed datoms/entity IDs;
   immutable past transaction units never rerender. Equivalent tabs compose
   through the existing cache/fan-out; identical output sends no morph.
   Pagination and row windows are bounded, and closed details construct no
   Hiccup or SCI work. **Partial:** agent surfaces already transition by exact
   observed read result; the current whole debug/data targets now use the same
   observer and normalized subscriptions suppress identical consecutive
   morphs. `/view/unit` activation now returns the Datastar SSE patch protocol
   rather than inert bare HTML, so expanded debug disclosures actually mount.
   A canvas SCI failure is recorded once at the bounding source; its outer
   fallback wrapper cannot transact again and create a render/error
   invalidation loop. The source-checkout operator also restores fail-loud
   development rendering by default while retaining an explicit graceful-mode
   override. The canvas context now composes the existing bounded render-fn cap
   over its AI twin and renderer source independently; the observed failing
   agent fell from 11,870 to 4,358 estimated tokens without another stored
   projection or context path. Debug panes and database details still need
   their own coordinates and bounded projections.
8. Keep installed-schema and direct attribute-presence queries as the small
   composable agent/domain discovery tools. A later KB surface must be a focused
   domain query through the normal block/render/surface mechanism, not a
   restored global inventory/context block.
9. **Adapter generation complete:** `seon-skills` is the runtime authority;
   `bin/seon skills sync` generates exact shared tool views, operator-only Codex
   skills generate their Claude adapters, and `bin/seon skills check` runs in
   the operator gate. Refine the existing import path in place: one
   parser/validator and desired-fact compiler accepts the shipped corpus, an
   operator directory, or uploaded `SKILL.md` content; it stores exact canonical
   source/body facts so a later config-free restart does not depend on the
   original path. Keep default/test skill context blocks absent; explicit load
   remains an override through the normal block mechanism.
10. **Bounded fact-owner readers active:** `seon.agent.message` owns recent
    conversation/global message windows, `seon.eval` owns recent per-agent/global
    eval windows, and `seon.log/tail` remains the one error-log tail. Datahike's
    fixed lazy `rseek-datoms` is exposed only through the fully specified
    `seon.db` wrapper; agent context and root system activity now compose these
    bounded append-order streams without a complete history scan. Remaining:
    delete the duplicate readers in `seon.render.default`, error-storm,
    transcript, and menu code. Do not store a recent-list projection or rely on
    a growing Datalog sort.
11. Restore a deliberately small root-only role block after behavioral review:
    root understands the fleet, starts/routes to ordinary agents, and handles
    recovery notices. Keep root's home requirements as one complete curated
    scalar replacement rather than unioning in the ordinary workbench; align the
    no-config ordinary fallback so it does not grant orchestration. Put
    operational detail in root's orchestration/navigation namespace cards;
    moving into a namespace brings its full source and colocated/state-gated
    context. Root's system canvas contributes bounded current fleet facts
    through the existing canvas AI twin. Do not restore the retired instruction
    wall or add a second fleet block.
    Move the current surface catalog/focus/materialization logic out of the page
    layout into `seon.render.surface`, colocating its fully namespaced schemas
    and deleting the old `seon.ui.agent-view` definitions, then use it for both
    the agent page and root's fleet cards. Every agent gets a cheap card shell;
    visible non-root cards show the compact agent-derived focus, and closed
    details lazily show up to five recent messages and failed evals. Root remains
    in the agent list, but its own card is summary-only: materializing root's focused
    `system-view` inside itself would recurse. The root AI
    twin lists every agent and includes bounded canvas-AI/message/error detail
    for non-root running, erroring, then most-recently-active agents until its
    block cap.
    Make `/` + its one feed the only fleet/root view. Delete the separate
    `/agents` GET/feed; keep `POST /agents` as the sole HTTP birth action, and
    canonicalize `/agent/root` to `/` before opening a feed.
    **Route cut complete:** the duplicate fleet renderer, shim, feed, route
    datoms, and display-only tests are deleted; agent birth is now a canonical
    database route at `POST /agents` instead of a conflicting static
    supplement entry, the shared header calls it, and `/agent/root` redirects to `/`. Remaining work in this
    step is the concise root role block, bounded lazy card detail, and session-
    aware navigation.
12. Add one fully specified database-backed UI-session model owned by its web
    namespace: per-tab identity, human ref, and normalized local location only.
    Keep `{database-id, branch, session-id}` in `sessionStorage`. Bootstrap reuses
    it only when the attachment matches and the lookup ref exists for the current
    human; otherwise allocate the replacement through the one writer-side
    `seon.db.id/allocate!` path, return/store it, then open the keyed feed. If a
    reset or restore removes an open feed's session, clear that tuple and force
    the same bootstrap instead of client-upserting a ghost identity. Compare
    normalized locations and transact only when changed.
    Encode a manual agent-surface choice in the location query; no query value
    means the shared agent-derived focus. Do not persist scroll,
    disclosure, or form-signal state.
    Link an inbound human message to its originating session and record the
    exact message assigned to each turn as
    `:seon.agent.turn/cause-message`. Browser route changes and root's protected
    `seon.web.session/select-agent!` update that same location fact; its
    context-only injected session ID comes only through
    turn → cause-message → web-session, caller input cannot override it, and
    absence is an error. The existing feed applies
    the official Datastar redirect-helper semantics only when its normalized
    current route differs from the stored location.
    Do not store duplicate agent/route projections, `updated-at`, active flags,
    or a presence registry.
13. Keep `my.canvas` as the permanent API, make its leaf encodings
   browser-portable, and ensure its docstrings/Malli errors make buttons,
   inputs, selects, toggles, forms, state, save, pin, and clear self-explanatory.
14. **Agent surface observation complete:** materialized agent surfaces and both
   headers capture runtime database reads, normalized subscriptions learn those
   observations from the shared first paint, and changed-result replay suppresses
   unrelated cross-agent Hiccup/SCI work. Remaining: carry the same unit contract
   through data/debug/root units, add the measured bounded compositional output
   cache, and suppress identical serialized output in the existing Datastar feed.
15. Pay only for open/visible work: debug remains an empty shell until opened;
   offscreen/closed bodies are stubs; hidden source/result/error trees are not
   constructed.
16. Finish the responsive layout: full-height primary canvas, independent
   readable right rail, bounded fonts/code, compact plan disclosures,
   transcript bottom anchoring, no visible focused duplicate, and no live-bar
   overlap.
17. Prove agent-derived focus: canvas/domain writes select canvas; accepted
    human messages and agent replies select transcript; an unpinned rail choice
    yields to the next deliberate update, while the explicit per-tab pin remains
    until released or its surface disappears.
18. Prove every `my.canvas` control with valid, invalid, rejected, rapid, and
    throwing handlers. Feedback is structured and visible to the agent.
19. Add one optional root system-status surface only after the operator owns a
    reusable process-status projection. It samples pod/writer liveness, CPU,
    RSS, uptime, and feed pressure on demand; it persists no rolling projection,
    refreshes as one view unit on the existing feed at a modest cadence, and
    contributes only anomalous status to root's AI context. Do not revive the
    paused JVM health application or create a second metrics stream.
20. Cold-prove the default cluster, then coordinate the same no-alias cutover in
    ACME and rebuild/reset it.

Exit proof: one database transaction causes only affected units to render and
one Datastar path to update; the agent view, compact previews, forms, focus,
scroll, debug view, database browser, CSS, skills, and ACME use the same
render-unit/feed contract. `/data` can inspect schema, entities, refs,
transactions, provenance, history, and KB/domain facts without a global
per-commit scan. `/` is root's coherent system view; root can start an ordinary
agent and redirect only the originating browser tab through database facts.
Grown-database idle feeds do not repeat SCI/HTML work or sawtooth RSS.

### Phase 6 — local acceptance, profiling, documentation, and graduation

1. Run focused structural/generative tests during each phase, then run the
   complete active CLJS and writer gates once at the boundary.
2. Run a bounded Inspect AI smoke check that covers the basic agent loop,
   database write/read, and one canvas interaction. The full paid planning/
   memory/UI battery is deliberately deferred; this branch only proves the
   refactor did not break the harness or basic agentic work.
3. Run the full transition table from a destructively reset authorized default
   cluster, including failure injection at every local process, write/receipt,
   restore, and crash boundary.
4. Browser-drive `/`, first-run routing, agent, debug, canvas controls, root
   navigation, focus, scroll, `/data`, route facts, two tabs, reconnect, and
   responsive layouts. Verify gzip SSE server-side.
5. Profile cold/warm boot, five agent births, writer latency, sync, dirty-unit renders,
   SCI invocations, gzip bytes, browser morph time, event-loop delay, CPU, heap,
   GC, and RSS on small and grown databases.
6. Establish explicit budgets and fail loudly when agent-authored database
   reads/renders exceed them; do not hide unbounded work by increasing timeouts.
7. Update active architecture, skills, runbooks, Docker/build docs, and
   operator help to describe only proven behavior. Mark historical material as
   history rather than rewriting it.
8. Prove the default cluster first, then ACME. Mark the PRD complete only after
   active searches and runtime process/classpath inspection find no superseded
   mechanism.

Exit: fast, stable, responsive agents; one writer, one CLJS runtime/UI, one
local protocol, one operator, one test authority split, one vocabulary, and no
known duplicate or compatibility path in the local cluster.

## Deferred follow-on direction — preserve evidence, do not implement here

The research remains useful, but these are separate PRDs after local graduation:

- **Remote writer/replica.** UDS remains local and WebSocket is the likely remote
  adapter over one `seon.db.protocol` state machine. Immutable Konserve-root sync
  with head-last publication is the leading state-transfer design; exact replay
  remains opt-in. Before adoption, Datahike/Kabel must own and prove foreign
  listeners, deadlines, cancellation, reconnect, backpressure, branch scoping,
  and clean shutdown. Do not delete source that is useful to that work, but do
  not run a second live path now.
- **Cloud.** Evaluate GCS first when cloud work begins. The current owner policy
  is local-authority acknowledgment: success does not wait for a cloud mirror,
  so the eventual deployment must publish and measure its nonzero cloud RPO
  honestly. Exact mirroring/topology remains undecided.
- **Thin/mobile clients.** Phone-class clients are thin and use a hosted JVM +
  Node cluster; the UI is phone-focused and primarily admits local device facts.
  Browser/IndexedDB replica shape, history depth, native packaging, and offline
  mutation semantics remain open. They must reuse the canonical CLJS renderer
  and database protocol rather than create a second client runtime.
- **Inspect AI.** Full paid journeys for long-term planning, later database
  recall, interactive UI construction, and cross-agent behavior are deferred.
  Stale old run branches/artifacts may be deleted after active references are
  checked; no permanent retention policy is selected here.

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

- A new user runs bare `bin/seon` or `bin/seon up`, sees truthful build/readiness
  progress and useful URLs, and can operate agents without knowing process names.
- A first-ever database contains root plus one ordinary agent; `--open` lands on
  the ordinary agent while `/` is root's system/coordinator view.
- Cold and warm starts are bounded, idempotent, and free of ghost pruning,
  broad schema/program rewrites, global instrumentation, or duplicate services.
- Agent birth/resume/eval/crash and config/schema/program/restore transitions are
  explicit and database-correct; a crash never replays effects and leaves
  interrupted agents idle with one exact recovery anchor and a derived root
  notice.
- Batch mode attempts every complete form and preserves each real success/error
  for the next turn.
- One JVM server owns writes/storage/heavy work; one CLJS source owns agents and
  rendering on server and client.
- The local UDS writer/read path has one fully namespaced semantic protocol and
  same-request recovery contract; remote transport remains a documented seam.
- Canvas, surfaces, cards, blocks, slots, and views mean one thing everywhere;
  tile/live-tile/world/inspector are absent from active product vocabulary.
- The normal web UI is bounded and reactive; closed debug/offscreen content
  costs nothing; context and HTML render only when used.
- Buttons, inputs, selects, toggles, forms, errors, focus, and scroll work live
  from database facts.
- Root can see the originating tab's database-backed location, start an ordinary
  agent, and switch only that tab through the existing Datastar feed.
- `/data` lazily explores every installed attribute, including KB/domain facts,
  without a global per-transaction scan.
- Standard skills import into canonical database facts through one path, while
  the default/test skills context block stays absent and root context stays
  concise, namespace-led, and state-gated.
- The active test doors are fast by default, bounded, behavioral, and contain no
  retired application or homegrown evaluator.
- The old JVM application and rejected prototypes are recoverable from Git but
  absent from active source, classpaths, startup, tests, docs, and skills.
