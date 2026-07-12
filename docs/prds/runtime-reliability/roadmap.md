---
type: prd
status: active
tags: [prd, database, flow, agent]
---

# Runtime reliability refactor roadmap

## Goal

Turn the proven CLJS pod into one explicit, restart-safe system:

- one process lifecycle and one agent lifecycle;
- one minimal transaction provenance model;
- one exact desired-state compiler for declared populations;
- one durable schema/program source with deterministic runtime reconstruction;
- one reactive web feed driven by actual database dependencies; and
- no compensating pruner, healer, duplicate SSE registry, or converged write.

This refactor succeeds by deleting overlap. It does not introduce a lifecycle
framework, ownership layer, event-sourcing promise, persisted dependency graph,
or authorization system.

The authoritative target semantics and exact transition sequences are in
[[provenance-and-lifecycle-design]]. The dated research documents contain the
source proofs and measurements behind this order.

## Settled target

- `:seon.db/user` refs the existing root, human, or agent identity.
- `:seon.db/process` refs one of boot, config, or REPL.
- Every normal post-genesis production transaction carries both refs.
- Turn/eval/session/origin/replay/resume transaction metadata is not persisted;
  ordinary run/turn/eval/message domain facts remain.
- When selected for startup/apply, config restores a declared subset exactly;
  outside facts are untouched and converged state emits no transaction. A
  populated store can boot from its canonical DB facts without config.
- Native Datahike schema is reopened, not reinstalled wholesale.
- Full canonical Malli forms live in the database; the runtime registry is one
  validated projection of them.
- Arbitrary evals and external effects are never replayed.
- Read-only `as-of`, isolated writable forks, quiesced live restore, and undo are
  one database/supervisor lifecycle; the external supervisor acts as root for
  crash-recovery transactions.
- UI invalidation derives from runtime-observed database reads, never from
  transaction provenance.
- The persistent header is transaction-driven; interval usage rates are queried
  from timestamped facts on demand.
- Only `:seon.agent/id` uses package-owned readable words. Every other actual
  generated persistent identity uses the compact package adapter behind the
  same schema-driven `seon.db.id/allocate!` operation. The serialized writer
  enforces each Datahike identity constraint plus generated-value uniqueness
  across all generator-managed identity attrs in that logical DB/branch.

## Measured starting point

Three warm new-agent requests took 8.43–8.89 seconds. Roughly 7.8–8.1 seconds
was cluster maintenance rather than agent-specific work:

- core function indexing: about 3.1 seconds for 1,044 rows;
- test indexing: about 0.6 seconds for 282 rows;
- schema indexing: about 0.004 seconds for 1,180 rows;
- the complete builders run again inside ghost pruning;
- declaration loading: about 0.07 seconds;
- global instrumentation: about 0.10 seconds; and
- actual one-agent initialization: about 0.44–0.58 seconds.

On a grown store of roughly 192,000 datoms, an open SSE feed caused repeated
SCI deadline overruns and transient RSS sawtoothing around 1.4–2.5 GB. The pod
remained responsive, pointing to broad repeated render/query allocation rather
than a proven retained leak.

## Implementation rules

- Observe live state before and after every phase.
- Preserve the current branch/store; leave the ACME pod alone.
- Use source-grounded Datahike/Malli/Reitit/Datastar behavior.
- Keep public inputs/outputs and all map keys fully namespaced and schema'd.
- A migration may transform an old store once; it must not leave two live
  semantic paths.
- Each replacement deletes the compensating old path in the same phase after
  proof.
- Tests assert datoms, query results, calls, transaction counts, and rendered
  units—not exact context prose.
- Commit each phase independently after unit, REPL/live, restart, and relevant
  browser/feed proof.

## Transition acceptance table

| Transition | Durable work | Runtime work | Forbidden work |
|---|---|---|---|
| Fresh store | Genesis; missing native schema; exact core/config deltas | Build Malli/analyzer/program runtime; services; resume | Circular genesis attribution; duplicate scans |
| Converged restart | No seed transaction | Rebuild only process-local state; recover/resume | Full schema reassert; arbitrary eval replay |
| Changed config | Exact managed-subset delta | Affected listeners/projections | Core scan, global instrumentation, service restart |
| Core hot reload | Exact program/schema delta | Load/instrument changed declarations | Cluster restart; ghost-pruning pass |
| Agent mint | Agent + initial components | Home namespace, wake, host | Seed/config/program reload/global services |
| Agent resume | Normally none | Rebuild one host/wake from durable facts | Mint/overwrite initial state |
| Agent eval | Eval/result/domain/declaration facts | Evaluate once; instrument changed defs | Whole-program reconcile; effect replay |
| Route/view fact change | Ordinary fact transaction | Recompile affected route/plan and units | Stored dirty flags; provenance fan-out |

## Phase 0 — freeze the baseline and add observability

The transaction/lifecycle audit is complete. Before implementation, finish the
mutable-authority/display inventory and make the existing failure measurable
without persisting derived counters.

Progress on 2026-07-12: the integrated design/research package is committed.
The first mutable-authority fix retired three process-global error-scope
counters in favor of one fiber-local AsyncLocalStorage map; focused behavioral
coverage passes 14 tests / 79 assertions, including concurrent scope and pending
write isolation. The unbounded test-run `globalThis` properties have also been
replaced in place by one bounded recent-run process store with oldest-first
eviction. Full recorded results now live directly in that vector and
`last-result` reads the newest entry without a DB lookup; the generated scalar
run id and durable DB→process pointer are deleted rather than replaced. Durable
per-test summaries remain database facts. Normal process boot no
longer creates an unused second in-memory Datahike database—the real cluster
attachment is the health proof. The first full CLJS gate took 348 seconds and
exposed a cross-test pending-buffer fixture dependency; the fixture order is
corrected and its focused namespace is green. Baseline profiling is still
partially contaminated by a separately owned research lane restarting the
default pod, so clean mint/feed measurements remain open rather than being
inferred.

The token-reporting surface audit is also dispositioned: required active paths
now use the single `seon.ai.tokens` estimator at reporting boundaries; public
filesystem/web/shell contracts no longer expose private raw-size quantities;
and operational substring/transport/RAM coordinates remain exact and private.
The non-gym focused CLJS gate is green at 151 tests / 686 assertions, and the
gym-driver gate is green at 33 tests / 165 assertions. The optional JVM
embedding-writer log contract is green at 1 test / 3 assertions. The full-suite
script now trusts one authoritative Node run: a missing final summary fails
without a discovery process, polling, killing, or a fresh-process tail retry
that could conceal the observed failure.
Paused JVM UI/MCP, dormant shared UI, and the separately owned ACME-only tile
remain explicitly deferred rather than gaining another estimator.

The duplicated eval-result authority is removed in place. A successful eval now
has one capped `globalThis.result.<id>` value slot shared by the analyzer-emitted
`result/<id>` symbol and `lookup-result`; eviction removes the value and analyzer
def together. Pending Promise settlement updates only a still-live slot, so
late work cannot resurrect an evicted result and displace newer values. Focused
coverage passes 20 tests / 75 assertions with no synthetic core-fault or
data-loss output; the result-var fixture now owns the CLJS root connection for
its complete asynchronous span.

The test-runtime trim design identifies 24 full cluster seeds in the gym driver
namespace and two obsolete reconciler seeds. Gym alone accounts for roughly
half the Node gate. The owner subsequently marked the homegrown gym for
replacement, possibly by Inspect AI, so no new predicate/scorer architecture
will be built into it. Unique regressions move to their production owners,
scenario/evidence data remains available for the selected external harness, and
the old driver/scorer leaves the default gate and is deleted. The independent
immutable-Datahike test support, test tiers, and presentation-text cleanup still
apply.

- Capture cold boot, converged restart, five sequential mints, one concurrent
  mint attempt, core reload, and config apply timings.
- Record transaction/broadcast counts for each transition.
- Add runtime-only UI counters for commits, batches, live sockets, unique view
  keys, candidate reads, compared reads, dirty units, renderer/SCI invocations,
  suppressed output, event-loop delay, heap/GC, and RSS.
- Break render cost down by view/unit, database read, SCI setup/body, hiccup
  serialization, and gzip.
- Reproduce the grown-store feed case on the default-store copy/synthetic store
  and identify the exact route and renderer for every over-budget event.
- Inventory every `atom`, `volatile!`, `defonce`, AsyncLocalStorage value, and
  in-memory registry. Classify it as an irreducible process handle, a DB-derived
  cache with named invalidation/cold rebuild, a missing durable fact, or
  duplicated authority; move/delete the latter two before their owning phase
  graduates.
- Inventory every human/agent-visible size in logs, persisted transcript text,
  debug/UI output, and error messages. Route all reporting through
  `seon.ai.tokens/estimate`; raw character counts may remain storage facts but
  never displayed. Remove existing `N chars` elision/log output, not only new
  violations.

Exit proof: every current global side effect/write and the grown-store render
cost has a named runtime call site; every mutable cell has a documented owner,
loss behavior, and disposition. No human or agent surface reports raw character
counts.

Commit: baseline instrumentation and evidence only.

## Phase 1 — replace candidate IDs with one atomic allocator

Establish collision-safe creation before mint starts relying on the new agent
grammar.

Progress on 2026-07-12: implementation and focused mechanical proof are
complete; cold default-cluster proof remains after the production rebuild. The
single allocator now owns every generated domain identity. Generator policy is
a database fact on each `:seon.schema/key` entity, not a client-supplied
catalog. The writer validates the complete uncommitted Datahike report before
commit, including nested maps, transaction-function output, transaction
metadata, exact known-id upserts, policy transitions, and cross-attribute
collisions. Wire protocol v2 carries only the candidate manifest and uses a
durable UUID receipt plus frozen request hash for same-id recovery. Eval values
are prepared once outside retryable builders, and an ambiguous/committed wire
result can never allocate a replacement eval id. JVM writer tests pass 19 tests
/ 112 assertions; the CLJ/CLJS allocator, wire, schema-index, and eval-record
suites are green with zero compile warnings.

- Add `src/seon/db/id.cljc` as the only owner of generated persistent
  identities, with named Malli request/response schemas and fully namespaced
  schema metadata `:seon.db.id/generator`.
- Make `:seon.db/id` the broad legacy/word/compact transport union, while
  `:seon.agent/id` uses the narrow root/legacy/word value schema and every other
  generator-managed identity attr uses the narrow legacy/compact schema. Policy
  metadata constrains generation; narrow values also reject explicit
  wrong-grammar writes.
- Add the pinned package adapters: npm `human-id` 4.2.0 and JVM
  `com.github.kkuegler:human-readable-ids-java` 0.4 for `:seon.agent/id` only;
  npm `@paralleldrive/cuid2` 3.3.0 and JVM
  `io.github.thibaultmeyer:cuid` 2.0.5 for every other
  actual generated persistent identity. Put JVM deps in `deps.edn`, Node deps in
  `package.json`, and keep adapter differences private in the one `.cljc` file.
- Reserve `root`; preserve every 14-character value the old schema admitted
  without rewriting; remove time from newly generated values and project
  creation time from the identity datom's transaction.
- Replace the query-then-entity-map `new-id!` path with `allocate!`: declarations
  name identity attributes, persisted generator-policy facts choose the
  adapter, and a pure builder receives candidates and produces the complete
  normal transaction.
- Extend the existing wire transaction/result shape with a generated-candidate
  manifest and structured Datahike uniqueness details. Inside the serialized
  writer operation, the canonical CLJC preparer injects collision-free tempids;
  Datahike assigns final eids and the adapter resolves them from the committed
  transaction report.
- Retry only when the normalized error attribute and attempted value exactly
  match the generated manifest. Rebuild the whole candidate-dependent
  transaction on each attempt; after sixteen rounds return a namespaced
  exhaustion error. Unrelated uniqueness/schema/domain failures return without
  retry.
- At the serialized writer, derive the managed identity set from persisted
  `:seon.schema/key` + `:seon.db.id/generator` facts. Preflight candidates
  through indexed AVET lookups, then inspect Datahike's complete uncommitted
  transaction report before it reaches the commit queue. Reject duplicate or
  raw fresh current IDs, invalid policy changes, and cross-attribute reuse from
  literal maps, nested entities, transaction functions, or transaction
  metadata. A generated-value hit is a matching candidate conflict and
  retries; it stores no global id entity. Local and JVM wire-registry
  connections name the fully namespaced
  `:seon.db.id.writer/serialized` backend. Its runtime multimethod installs the
  private operation in Datahike's self writer while the persisted config
  contains only the keyword, so there is no client-side query-then-transact
  path or unserializable live function.
- Gate creation of generator-managed identity attributes through `allocate!`;
  known-id exact reconciliation remains a separate intentional-upsert path.
  This is a consistency boundary, not an authorization system.
- Remove the old timestamp/`Math.random` generator and retiring scalar session
  metadata generator. Do not invent a replacement session identity.

Exit proof: package adapters match registered syntax; legacy reads still work;
forced candidate repetition exhausts with zero datoms; an unrelated unique
conflict does not retry; the same compact candidate already stored under a
different generator-managed attr retries without a global id datom; a forced
agent-id collision rebuilds all home namespace,
function/schema identity, ref, nested source, and response values and commits no
part of the rejected attempt; URLs, DOM ids, and CLJS namespace munging work;
concurrent allocation never upserts an existing entity.

Commit: canonical schema-driven identity allocation and caller migration.

## Phase 2 — split process, mint, and resume lifecycles

Remove the largest known latency before changing the data model.

Progress on 2026-07-12: the code split is committed; cold restart and live mint
timing remain open until the concurrent instrumentation/fixture lanes finish and
the default pod is rebuilt. `seon.client/start-runtime!` now owns cold cluster
attachment, seed/replay/instrument/recovery, shared services, and roster resume.
`seon.agent` owns one complete birth transaction, while
`seon.agent.runtime/resume!` reconstructs only compiler namespace, listener,
loop input, and process advertisement. `/agents/new`, `/agents/run`, and
programmatic spawn call that same birth path directly. The two injected web
callbacks, `:mint?` lifecycle mode, child-arm callback atom, and web creation
lock are deleted. Identity, configured context components, scalar dials, parent,
and structural home-namespace facts commit together; namespace reconstruction
no longer writes analyzer-derived require edges. Focused proof is green at 50
tests / 251 assertions, including a one-transaction birth and a resume whose
database basis does not advance. The production client build completes with
zero warnings.

- Give cluster/runtime boot one owner that opens the connection, reconstructs
  global runtime state, installs services/listeners/ticker/hooks once, recovers
  crashed runs, and resumes eligible agents.
- Replace `init-agent!`'s `:mint?` mode with explicit mint and resume operations
  sharing a small host/wake setup helper.
- Wire `/agents/new`, `/agents/run`, programmatic spawn, and CLI creation through
  one schema'd namespaced mint service.
- Ensure runtime namespace selection does not enter the unborn agent as the
  transaction submitter.
- Compile agent identity, purpose/defaults, complete context components,
  home-namespace/require rows, and safe initial declarations into one atomic
  writer allocation transaction before creating transient host/wake state.
  Initial declarations must have identities unique to that agent; rely on the
  already validated root/boot program contract for shared schemas/defaults and
  never reassert a shared row on each mint.
- Make hot reload replace only reload-scoped hooks and definitions.
- Remove the duplicate create/mint injection seams and remove the global create
  lock if no shared mutable resource remains after the split.

Exit proof: five warm mints run no seed, prune, declaration-load, global
instrumentation, server/listener/ticker, or global provider synchronization and
remain below one second at the current store size. Cold restart still resumes
the same agents.

Commit: lifecycle split and web wiring.

## Phase 3 — establish genesis and transaction provenance

Install the final two-fact model atomically across writers/readers.

Implementation status: complete. The default-store proof began at
`t=536871159` with 177 historical transactions and no process rows. The
one-shot transition installed boot/config/REPL, the human user, and resolvable
user/process refs for all 177 transactions; at `t=536871162`, the unjoined
count was zero. The migration classifier and fixture were then deleted. The
remaining runtime supports only minimal fresh genesis and exact convergence;
historical retired datoms remain immutable data with no production reader or
writer.

- Colocate `:seon.db/user` and transaction-context selection in `seon.db`.
- Colocate `:seon.db.process/id`, its boot/config/REPL rows, and lookup helpers
  in `seon.db.process`.
- Split AsyncLocalStorage execution context from the explicit persisted metadata
  whitelist.
- Add one fresh-store genesis operation and one existing-store migration
  boundary. Both install the minimal native schema and root/process refs before
  normal metadata is required.
- In the first normal root/boot desired-state transition, ensure the stable
  human `:seon.user/id` row before enabling web/REPL writes; repeat the check on
  cold boot/migration.
- Backfill old transaction entities only where the existing scalar/origin facts
  map honestly to user/process refs; report ambiguous rows rather than inventing
  provenance.
- Make the ordinary transaction compiler account for attributes used in final
  transaction metadata and validate/resolve both refs before submission.
- Migrate every author/recency/debug/warning/program query to joins through
  `:seon.db/user` and `:seon.db/process`.
- Preserve `:seon.store.wire/id` as the durable idempotency receipt and
  transport correlation key.
- Remove all writers, readers, and public source registrations for the retiring
  transaction-metadata attributes `:seon.db/agent-id`, `:seon.db/turn-id`,
  `:seon.db/eval-id`, `:seon.db/session-id`, `:seon.db/origin`,
  `:seon.db/replay?`, and `:seon.db/resume-marker?` after the migration proof.
  Historical datoms may remain in the immutable store but have no live semantic
  path. The retired Malli registrations are deleted; the installed native
  Datahike attributes remain accumulating schema/history facts and require no
  runtime compatibility path. Keep ordinary domain identity attributes and
  separately audit
  `:seon.agent.message/origin`, which currently controls wake behavior.

Exit proof: a live query shows every post-boundary transaction has resolvable
user/process refs; root/boot, root/config, agent/REPL, and human/REPL current and
history queries work; no production code names a removed metadata field.

Commit: provenance schema, migration, writer/reader cutover, and deletion.

## Phase 4 — build the exact population reconciler

Replace broad origin scans and reassert-all upserts with one pure compiler.

- Introduce the canonical fully namespaced database coordinate projection
  `{store-id, branch, commit-id, t}` and writer-side expected-head comparison
  now. The current main branch uses the same shape that forks/restores extend in
  Phase 9; do not build a temporary numeric-basis fence.
- Define schema'd population contracts with identity attributes, managed
  attributes, exclusivity, component attributes, and user/process metadata.
- Validate active contracts as pairwise disjoint by managed attribute and
  recursive component subtree. A fact may have one desired-state authority;
  overlap fails before compilation.
- Query only contract identities/managed values for normal retained-entity
  deltas; destructive candidates take the full guard read below.
- Compile scalar replacement/omission, cardinality-many set differences,
  component replacement, new identities, and guarded stale-identity removal.
- Before `:db.fn/retractAttribute`/`:db.fn/retractEntity`, inspect every current
  attr, the recursive component closure, and incoming refs; fail loudly on any
  fact outside the explicit contract.
- Return an empty vector when current and desired values are equal; do not call
  `transact!`.
- Submit the compiled delta with an expected full-head commit fence. On a
  mismatch, re-read and recompile from the new head under a bounded retry;
  writer serialization alone does not make the preceding CLJS read atomic.
  Schema/config/program registry publication uses the same transition owner and
  occurs only after its matching fenced transaction commits.
- Route writes to canonical config/program/schema-managed populations through
  this transition owner. Direct `db/transact!` rejects a write that would bypass
  those contracts; ordinary schema-valid domain facts remain writable. This is
  a consistency invariant for exact projections, not an authorization layer.
- Use `datahike.api/with` in tests/REPL to prove the transaction's resulting
  facts, not in production to discover the diff.

Exit proof: focused generative/structural cases cover every cardinality and
component transition; a converged reconciliation creates no transaction and no
listener event; a write injected after read but before submit forces a clean
re-read/recompile and cannot leave a stale cardinality-many value, retract a
new outside fact, or publish an incomplete runtime registry.

Commit: pure exact-delta engine and transact-if-nonempty wrapper.

## Phase 5 — make config an exact recovery surface

- Compile defaults + selected manifest + environment overrides into one
  canonical desired value before touching the database.
- Make config selection operation-scoped. No input means no config transaction
  and never implies `config/system.edn`; remove the startup script's ambient
  default export. A successful apply leaves canonical DB facts that later
  config-free boots preserve.
- Add one explicit operator/API door: `bin/seon config apply --config <path>`
  (or an explicitly present `SEON_CONFIG`) snapshots and applies that input;
  `--empty` supplies literal `{}`; ordinary `bin/seon restart pod` supplies no
  overlay and preserves DB config. The result reports action, canonical digest,
  expected/committed coordinate, changed-fact count, and convergence.
- Require an explicit initial canonical config value for a fresh store; `{}` is
  valid and materializes the schema-owned safe floor. A restored target missing
  that non-optional database floor is inspection/fork-only unless an explicit
  config migration overlay supplies it. “Config optional” means no external
  artifact is needed once canonical DB facts exist, not that writable runtime
  behavior silently projects today's defaults over missing historical facts.
- Introduce the one schema'd external lifecycle-intent store/scanner using temp
  write, file fsync, atomic rename, and directory fsync. Before accepting an
  apply/startup overlay, persist an intent containing its action, immutable
  canonical desired payload, digest, target attachment coordinate, and expected
  head. Fenced reconciliation verifies the result and clears the intent
  atomically; crash recovery resumes from the payload rather than rereading a
  path or environment. Phase 9 extends this same schema/mechanism with branch,
  undo, and confirmation facts; it does not add a restore-intent path. This is
  not a stored config mode.
- Validate every map against its registered schema and require exactly one
  identity attribute.
- Declare exactly: the config singleton attrs, the AI config singleton attrs,
  the web-brand singleton attrs, complete route entities/components, and root's
  configured context component subtree/explicit root defaults. The general
  agent-context is a mint template;
  existing non-root agent context copies remain agent-owned and unchanged.
- Treat compiled route/root-context defaults as config-compiler inputs only.
  Root/boot owns program/schema and named non-config root capability facts;
  root/config alone owns routes, the root context instance, and templates.
- Retire the old `:seon.config/skills` manifest key, corpus scan, seeded skills
  block, load/unload default, and config prune path together. One guarded
  migration retracts only still-current config-authored `my.skills` defaults;
  later agent-authored facts and intentional retractions are not overwritten.
  No skills/loadout population remains in the target config contract.
- Convert procedural route removal to absence in the canonical desired set and
  remove the old syntax/path in the same phase.
- Validate every managed population first, combine their deltas, and apply one
  atomic root/config transaction when nonempty.
- Delete singleton config healing, `managed-identities`, first-live-transaction
  classification used for management, unconditional desired upserts, and the
  separate catch-and-continue `seon.web.brand/sync-from-env!` boot healer.
- Inject missing, changed, extra, and partially written managed facts before a
  restart and prove config restores the declared subset while agent/domain data
  remains byte-for-byte equivalent as facts.

Exit proof: config repairs every injected managed-state fault, removes stale
managed values/entities, preserves outside data, respects env precedence, and
performs zero writes when already converged; a crash before/after intent,
fenced commit, result readback, and intent clear deterministically completes or
recognizes the same apply, and the next no-config cold boot performs no config
write.

Commit: config compiler/reconciler cutover and healer deletion.

## Phase 6 — restore native and Malli schema correctly

- Prove a populated Konserve/Datahike reopen restores native schema/index roots;
  remove complete `pod-full-schema` retransactions and the hand-maintained full
  bootstrap attribute list.
- Derive/install a native attribute declaration only when its ident is absent;
  compare value type, cardinality, uniqueness, and component semantics and treat
  any storage-facet divergence as an explicit migration.
- Replace truncated/overloaded schema source with one full canonical EDN form
  fact per `:seon.schema/key`.
- Query current forms and build one complete in-memory candidate before any
  durable change. Overlay compiled core registrations only for fresh-store
  genesis or an explicitly selected current-core/hot-reload/reset transition;
  a populated no-overlay runtime uses canonical DB forms alone.
- Parse/validate every candidate form and its complete native storage signature,
  reject protected core-key collisions, commit only the valid canonical/native
  delta, then atomically swap/relink the already validated registry/catalog.
- Make runtime agent schema registration transact the canonical form/native
  declaration before swapping the validated live registry; leave runtime state
  unchanged on transaction failure.
- Guard schema deletion/rename through dependencies on other forms, fn specs,
  installed native attrs, and current/history facts. Installed/referenced attr
  validators remain until an explicit migration; do not generic-stale-retract
  them with program rows.
- Derive the entity/render catalog once per registry generation and delete the
  persisted schema-decomposition projection.

Exit proof: forward references restore on cold restart; one invalid schema
leaves the prior live registry intact; agent schemas are immediately renderable;
native schema is not rewritten on a converged restart.

Commit: canonical schema facts, atomic registry restore, and decomposition
deletion.

## Phase 7 — build one program snapshot and delete ghost pruning

- Group compiled vars/tests by source file and read each file once per
  selected current-core generation. Do not build this source snapshot during a
  populated preserve-target/no-overlay restart.
- Extract namespace, function, schema, test, source, spec, and requires into one
  deterministic desired graph. Its schema slice replaces Phase 6's temporary
  compiled-registration input and is submitted only through the one fenced
  canonical-schema transition; program reconciliation is never a second schema
  writer. Omit derivable `created-at` metadata; identity/source datom
  transactions already provide time.
- Audit other stored creation timestamps under the same rule; retain only true
  domain event/pre-event coordinates that a transaction join cannot project.
- Validate required snapshot populations before compiling any mass retraction.
- Make every namespace/function/schema/test definition an exact whole-row
  replacement over its declared program attrs/components. Retract omitted
  declaration fields in the same transaction; prohibit partial source/spec/doc/
  arglists/ns/require patches. Audit legacy mixed-author rows datom-by-datom and
  normalize or preserve/report them before source-datom authorship is used.
- Reconcile the protected `seon.*` program population exactly as root/boot,
  including removed and renamed identities, only for fresh-store genesis or an
  explicit current-core/hot-reload/reset transition. Treat shipped editable `my.*`
  definitions as history-aware defaults: seed never-seen identities; preserve
  agent/REPL-authored current definitions and agent/REPL retractions; reinstall
  a desired-but-absent default when the latest relevant retraction was
  root/boot; retract removed defaults only when their current row is
  root/boot-authored; and reassert everything on explicit reset. Leave
  `my.agent.<id>` outside core reconciliation.
- Reuse the same snapshot to select hot-reload definitions/instrumentation.
- Delete `prune-core-ghosts!`, the second complete builder pass,
  `core-index-tx`'s parallel partial-diff policy, and stale comments/tests.

Exit proof: injected reader counts one source read per file for a selected
generation and zero source-snapshot reads/reconciliation writes for a populated
no-overlay restart; unchanged selected source is an equal value and no write;
edit/delete/rename produce one exact transaction;
all declaration-managed datoms for a row share one authoring transaction;
agent-authored program facts survive; unsafe schema deletion fails before the
durable decision.

Commit: deterministic snapshot/reconciliation and pruner deletion.

## Phase 8 — make runtime reconstruction honest and bounded

Progress on 2026-07-12: the instrumentation authority split is implemented.
Cold reconstruction queries the canonical function facts once and passes Malli
one explicit exact `:data` map; Seon no longer populates or reads Malli's global
function-schema roster. Committed eval definitions call one changed-symbol
delta that unstruments/removes and reinstruments only the affected functions;
unaffected wrapper identity is stable and spec removal restores the original.
Shadow's zero-argument `after-load` hook exposes no changed-source set, so the
current hot-reload integration derives live wrapper gaps from the canonical
rows once and sends only those gaps through the same delta. A second scan on
the same definition performs zero Malli mutation. The exact-data suite is green
at 91 tests / 714 assertions; the reload integration adds 3 tests / 28
assertions with zero warnings. Schema-ref transitive dependent selection and
the Phase 7 accepted program snapshot remain open; when that snapshot supplies
the reload delta directly, it can remove the gap-discovery fallback.

- Rename program “replay” to declaration/program loading in public docs and
  runtime APIs.
- Keep the strict one-literal namespace/function/test declaration persistence
  gate; do not persist arbitrary eval source as reconstructable code.
- Load durable namespace/function/test declarations once per fresh JS runtime
  after Malli restoration. Exclude schema registration calls; canonical schema
  EDN has exactly one registry reconstruction path.
- Instrument the complete runtime once after loading by passing Malli one exact
  explicit `:data` map. Stop populating/reading Malli's process-global
  function-schema atom as a second roster; remove the bulk pass from agent
  mint/resume/start and Shadow reload.
- Add one delta operation that unions direct body/spec additions, changes,
  removals, and deletions; filtered-unstruments exact old entries from Malli's
  recorded originals; and instruments the exact remaining map once. Wire eval
  and the Shadow build-notify transition through it only after their fenced
  canonical facts commit.
- During candidate-registry validation, derive schema-reference dependencies
  with Malli's walk/ref API, including local recursive registries and cycles. On
  a same-key or add/remove schema change, union the old/new transitive dependent
  sets into the one delta; do not use key-set snapshots, keyword regexes, or a
  global safety pass.
- Require complete multi-arity/variadic contract coverage before any filtered
  unstrument. An invalid candidate changes neither durable facts nor the live
  registry/wrappers; a post-commit install failure closes readiness and rebuilds
  the committed generation.
- Audit detached Promise callbacks against the live run/CAS fence so stale work
  cannot commit merely because it retained ALS context.
- Implement the explicit supervisor recovery matrix for terminated, idle,
  stale-closed-pointer, paused, expired/exhausted, stranded-running-turn, and
  safely resumable open-run states. An in-bounds stranded turn is CAS-fenced and
  marked terminal error without replay, while the same open run continues at
  the next turn. Every genuinely destructive root/boot repair begins with
  old→old run-pointer CAS, then retracts the exact ref and writes closing facts
  in the same transaction; never CAS a ref to nil.
- Render prior-session process-local result values as missing or elided without
  executing source.
- Keep replication gap recovery terminology/logic isolated to the wire reader.

Exit proof: an effectful scratch eval runs once across restart; a declaration
returns after restart; prior result lookup is honest; turn inspection is
read-only; feed recovery applies committed datoms once; mint/resume invoke zero
instrumentation work; a redefinition replaces exactly one wrapper without
stacking; a referenced schema change rewraps exactly its dependent functions.

Commit: reconstruction naming/scope, async fence, and result behavior.

## Phase 9 — one upstream Datahike versioning path

Use exactly Datahike's existing immutable/versioned-store primitives; improve
the current Seon coordination rather than creating a snapshot or versioning
system.

### Phase 9a — exact coordinates and immutable reads

Progress on 2026-07-12: the first truthful-coordinate correction is committed.
`seon.db/basis-t` now reports an `AsOfDB`'s selected point rather than the
origin database head, and JVM wire query/pull responses inherit that selected
coordinate. CLJS and JVM behavioral proofs are green. The complete
branch-qualified coordinate resolver/capture/cursor work below remains open;
the writable phases are deliberately not exposed by this read-side correction.

- Extend Phase 4's `{store-id, branch, commit-id, t}` schema/resolver through
  registry, wire, API, turn/error capture, bookmarks, and caches. Logical
  db-name remains routing data beside the coordinate.
- Keep `as-of` a read-only database value accepted by ordinary fully schema'd
  queries/renders/simulations. Upgrade `basis-t` to return the selected view
  time for an `AsOfDB`; never mistake its origin head for the selected point.
- Resolve convenience `{branch, t}` only by walking retained ancestry and
  requiring exactly one commit. Responses always echo all four fields; commit
  id is canonical.
- Migrate legacy turn/error t-only facts only when resolution is unique. Leave
  ambiguous history honest and use the prompt blob as byte ground truth.
- Make cursor/feed continuation commit-ancestry aware. Attachment mismatch or
  non-ancestor cursor means full reset, never numeric `since-t` replay.

Exit proof: immutable reads create no writer/transaction; two lineages reusing t
resolve to different commits; an ambiguous legacy t fails; new turn/error facts
round-trip the exact coordinate; frozen caches/bookmarks never key on bare t.

Commit: canonical coordinate, immutable resolver, and capture migration.

### Phase 9b — correct the pinned Datahike primitives

- Preflight effective commit-graph support and actual retained commit records;
  an absent literal `:commit-graph?` key may still mean the default true, while
  an effective false or missing record rejects commit branching. Also require
  history retention, readable ancestry, and the selected commit's primary and
  secondary key maps before intent/admission. A store created with commit graph
  disabled cannot be repaired by flipping the config flag or backfilled in
  place; it requires an explicit supported migration/export outside this path.
- Fix the pinned Datahike `branch!` in place: only an exact current-branch source
  may reuse live secondary state; a commit UUID or non-current branch must use
  the selected stored commit's `secondary-index-keys`.
- Make connection release await writer shutdown before reporting success.
- Add an expected-current-commit guard/readback to `force-branch!`; do not add a
  Seon-only root-pointer mutation. Keep the upstream API shape and add focused
  regression tests suitable for a later Datahike PR.

Exit proof: a branch from historical T returns T in primary and secondary
indexes even after head H adds indexed facts; stale force is rejected; no old
writer can commit after release; commit-graph-disabled/missing-record stores
fail before mutation.

Commit: pinned Datahike branch/release/force correctness gates.

### Phase 9c — branch-qualified attachment and runtime resources

- Make `[store-id branch]` the connection identity. Maintain a bijection between
  logical db-name and that attachment while connected; every registry/wire/MCP/
  UI request verifies both instead of routing by bare agent id or path.
- Key filtered DB handles, route/render/config caches, live feeds, wire
  correlations, and agent host registries by attachment/commit or adapter
  generation as appropriate; clear/rebuild them on root change.
- Give a branch blob store a read-only source base plus branch-local write
  overlay. Before guarded promotion, verify and materialize every
  target-referenced overlay hash into main's base; extra content-addressed files
  after a pre-force crash are harmless. Release removes only that branch's
  overlay, and GC treats every retained branch as a root before deleting only
  content unreachable after explicit branch release.
- Persist an attachment-only runtime descriptor by temp write, fsync, atomic
  rename, and parent-directory fsync. It contains logical db-name, backend/store
  path, store-id, branch, source attachment/blob read base, overlay write path,
  and launch endpoints. Core/config overlay policy never lives in it.

Exit proof: duplicate agent ids on main/debug route to their qualified hosts;
cross-attachment frames/writes are rejected; cold branch attach reconstructs
from descriptor without config; destroying its overlay cannot mutate/delete a
source blob.

Commit: branch-qualified registry/wire/runtime/blob attachment.

### Phase 9d — writable fork and release lifecycle

- Expose one plan/apply lifecycle service for `:fork`, `:restore`, and
  `:release`; keep pure `as-of` separate. Database calls stay in `seon.db`; the
  external supervisor owns process coordination.
- Make same-store `branch!` the only normal writable fork. Retire Seon's
  `fork-database` physical-copy path; physical export/clone is a different,
  unproven requirement.
- Freeze the plan's source/target commit and require its confirmation token at
  apply. Briefly gate/drain source writes while creating/verifying the branch.
- Start every debug/fork runtime non-autonomously: no ticker, wake-trigger
  or agent-host registration, agent-loop resume, schedule execution, provider
  synchronization, or external-effect worker runs merely because history was
  opened. Explicit forensic actions alone may drive it.
- Release stops the branch pod, drains/releases its handles, calls upstream
  `delete-branch!`, and removes only its descriptor/overlay.

Exit proof: a same-store branch shares immutable index storage, diverges under
writes without moving source, remains exact while idle, and releases without
touching source; a stale confirmation token mutates nothing.

Commit: lifecycle plan/apply fork and release.

### Phase 9e — quiesced restore, undo, and crash recovery

- Plan against immutable heads, validate the target reconstruction floor and
  optional current-core/supplied-config candidates, and freeze canonical
  overlay payloads/digests. Absence of config is valid and never falls back to
  `config/system.edn`.
- Before apply, extend Phase 5's one external lifecycle intent with requester,
  exact source/target coordinates, policies/payloads, undo branch/head, and
  confirmation. Derive
  progress from actual heads, fences, connections, and reconciliation results;
  do not persist a procedural checklist.
- Reject admission, fence active runs/actions, drain submitted writes, stop
  hosts/listeners/readers/writer, create/verify undo and target branches, then
  guarded-`force-branch!` main. Reopen every handle from the new attachment and
  discard old write ids, feeds, caches, and cursors.
- Reconstruct only safe canonical runtime state, apply the independently
  selected frozen overlays through exact fenced reconciliation, recover valid
  runs, record completion, clear intent, and reopen traffic. The stopped root
  agent never coordinates its own restore; later repair txs use root/boot or
  root/config provenance.
- Undo is the same planned restore to the preserved head. State plainly that DB
  restore cannot undo emails, API calls, files, or other external effects.

Exit proof: preserve-target boots the selected DB with no core/config input and
does so again after a second cold boot; explicit overlays repair only their
declared subsets; undo returns the saved head; process kill at every boundary
resumes or safely refuses; stale writers/cursors cannot cross the moved head;
no arbitrary eval or external effect re-executes.

Commit: guarded restore/undo supervisor and full crash matrix.

## Phase 10 — one live subscription owner and lossless batches

- Keep the initial observability counters and replace per-connection dependency
  state with one subscription per normalized Reitit view key.
- Delete the replaced per-connection main-feed state/refresh ownership in this
  phase; existing debug/data paths remain untouched until their own cutover and
  no new parallel registry is introduced.
- Attach socket/gzip/backpressure handles to that subscription; keep
  latest-event-per-socket behavior.
- Retain earliest `db-before`, latest `db-after`, and unioned datoms/entities/
  attributes for each coalesced batch.
- Replace reset-forever trailing debounce with frame coalescing plus a bounded
  maximum wait.
- Add the missing route-projection listener and rebuild the cached Reitit router
  exactly once when that projection changes.

Exit proof: two equivalent tabs cause one render/two pushes and remain correct
after either tab closes; continuous writes cannot starve the latest view; frozen
`as-of` feeds do no current work.

Commit: shared subscription/batch/router foundation.

## Phase 11 — stable render units and one Datastar feed

- Decompose the global header, roster membership/rows/previews, agent shell,
  each surface pair, focus controller, debug raw/HTML/diagnostic panes, and data
  browser result into stable ID-addressed units.
- Render the shared header once per relevant batch, not per page/socket.
- Add one ephemeral normalized-unit registry (unit id + params + full resolved
  coordinate, or live attachment generation)
  whose read/output cache and refcount may be shared by several view
  subscriptions; evict when the last subscriber closes.
- Move each debug/data surface onto the gzip Datastar subscription path and
  delete that surface's old listener/registry/timer/framing in the same cutover.
- Preserve one full `#app-view` morph as the correctness fallback when unit
  membership/disappearance changes.
- Use tests/server-side captured output for equivalence before routing the live
  surface; do not commit a production dual-feed toggle. Delete the unused `/sse`
  registry/route and duplicate full-page paths with the last cutover.

Exit proof: agent, roster, debug, and data pages share one transport; a local
update renders only its stable units; conditional disappearance removes stale
DOM; inputs, focus, and transcript scroll anchoring survive morphs.

Commit: render units/feed unification and duplicate-path deletion.

## Phase 12 — runtime read observation and exact invalidation

- Add a synchronous observer at every `seon.db` read boundary with no
  meaningful overhead when unbound.
- Normalize query/pull/entity requests and reuse/expose Datahike's conservative
  query attribute-dependency extractor; wildcard/dynamic/unknown reads become
  broad candidates.
- Move render-layer direct Datahike access and lazy entity walks behind explicit
  bounded `seon.db` reads.
- Compile read-to-unit and attribute-to-read reverse indexes per view plan.
- Evaluate each candidate normalized read once on batch `db-before` and
  `db-after`; render only units with unequal results.
- Recapture conditional reads after a dirty render and suppress identical
  serialized complete-element output.
- Use user provenance only to fix deliberate surface recency/focus scope.
- On cold restart, capture current scoped reads and prove the bounded indexed
  user+entity/attr history heuristic; broad reads get definition recency rather
  than claiming historical precision.
- After that recency proof, delete `:seon.fn/read-attrs`, literal/regex
  extraction, hard-coded header/surface/structural sets, and provenance-based
  debug fan-out in the same production cutover. Runtime capture may be exercised
  privately in tests first, but no committed route chooses between two
  dependency implementations.

Exit proof: unrelated attributes invoke zero renderers; same attribute on an
unrelated entity may compare one read but invokes no renderer; shared facts
update every actual dependent regardless of writer; change-then-revert emits
nothing; renderer redefinition/current namespace changes recapture the correct
plan.

Commit: observed-read compiler/invalidation and stored-read-set deletion.

## Phase 13 — bound legitimate work and profile the grown store

- Replace the header's whole-store inventory with an honest index count and
  shared bounded projections.
- Window/page roster previews, transcript/debug HTML twins, and data results
  before building hidden hiccup; keep exact raw AI text available on demand.
- Add a render-scoped database-read/allocation budget for agent-authored HTML
  and fail loudly with selective-query guidance rather than silently changing
  domain meaning.
- Remove the rolling clock-driven token-rate metric. Derive usage/rates on
  demand from timestamped turn/log facts.
- Profile Datahike result-cache retention separately from transient
  query/SCI/serialization allocations.
- Consider worker isolation only if exact invalidation and bounded reads still
  leave measured event-loop stalls.

Exit proof: one and several open grown-store feeds stay within agreed CPU/RSS/
event-loop bounds; work scales with dirty unique units, not sockets multiplied
by whole views; no SCI budget increase masks unbounded work.

Commit: bounded render/read paths and profiling evidence.

## Phase 14 — replace the homegrown gym with Inspect AI

Retire the duplicate evaluation lifecycle after the runtime, coordinates, and
restore surfaces it must drive are stable. The existing `src-inspect-ai/`
integration is the only evaluation control plane; Harbor remains an optional
upstream source of containerized benchmark tasks, not a second Seon harness.

- Inventory every gym assertion and classify it as a production subsystem
  regression, an end-to-end agent journey, presentation-only text, or obsolete
  duplicate coverage.
- Move unique deterministic regressions to the owning subsystem tests. Keep
  those tests behavioral: assert datoms, state transitions, process survival,
  and rendered structure rather than exact model prose.
- Express the first two Inspect journeys through Seon's public operator surface:
  a multi-step plan that resumes after a pod restart, and schema-backed facts
  stored in one turn then queried in a later turn.
- Make Inspect consume structured run evidence from the database and blob
  archive. Do not teach a scorer to scrape the transcript or duplicate Seon's
  lifecycle in Python.
- Replace the homegrown scorecard writer with one import/projection of Inspect
  result facts. Keep raw artifacts outside the hot Datahike graph and persist
  only the facts needed for comparison and provenance.
- Remove gym namespaces, drivers, scenarios, scorecards, scripts, and default
  test discovery in the same commit once the two journeys and migrated
  regressions are green. No compatibility wrapper remains.

Exit proof: the fast CLJS gate contains the migrated subsystem regressions but
no gym namespace; Inspect can drive both canonical journeys against a freshly
started pod and after restart; failures identify a durable run/turn/eval fact;
and repository search finds no live homegrown gym control path.

Commit: Inspect AI evaluation control plane and atomic gym retirement.

## Phase 15 — system acceptance and graduation

Run from a cold process and a converged restart:

- fresh genesis and existing-store provenance migration;
- config fault injection/recovery and zero-write convergence;
- five sequential and concurrent web-created agents with correct navigation;
- durable multi-step planning across restart;
- schema-backed knowledge write and later retrieval;
- core function/schema edit, deletion, rename, and agent-authored declaration;
- honest prior-session eval results and no effect replay;
- read-only as-of simulation, isolated writable fork/divergence, live restore,
  crash-boundary recovery, and undo;
- agent canvas, transcript, context twins, debug view, buttons, and forms;
- route fact add/change/remove;
- equivalent tabs, time-travel feed, server-side gzip morph verification;
- CPU/RSS/event-loop/profile matrix on the grown store; and
- full CLJS suite plus focused structural/generative tests.

Exit: no known lifecycle duplication, unexplained transaction churn, stale
reactive path, unbounded repeated render, or compatibility implementation
remains. Update the architecture docs to match live proof and mark the PRD
complete only then.

Commit: acceptance evidence, final deletions, and documentation graduation.
