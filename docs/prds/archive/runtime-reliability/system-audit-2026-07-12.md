---
type: research
status: active
tags: [research, database, flow, agent]
---

# Runtime lifecycle and transaction provenance system audit

## TL;DR

The design document is sufficient to begin refactoring only after this audit
turns it into a code map. The main problem is not one slow function. Three
concepts are currently entangled:

1. process lifecycle — opening the cluster and installing global runtime work;
2. agent lifecycle — minting, resuming, wiring, and hosting one agent;
3. transaction execution context — runtime facts used while evaluating work,
   automatically copied wholesale into persisted transaction metadata.

That entanglement creates the observed eight-second new-agent path, duplicate
program scans, broad database scans, redundant transactions, special cleanup
passes, and metadata fields whose only production purpose is to control code
while it is running.

The refactor should retain the proven runtime mechanisms and make their
boundaries explicit. It should not replace them with a framework. Follow-up
source research and owner decisions corrected this audit's initial turn-ref
hypothesis. The minimum durable transaction facts are:

- `:seon.db/user`, a ref to the existing root, human, or agent identity;
- `:seon.db/process`, a ref to boot, config, or REPL; and
- the wire write id, because it correlates a submitted write with the writer's
  committed transaction and reactive feed.

Turn/eval association is not a complete or safe effect-replay boundary. Normal
turn/eval entities retain their domain links; arbitrary transaction causality is
not promised. All other legacy transaction-context fields are runtime-only,
dead, or derived classifications.

## Scope and method

This audit covers the active CLJS pod and the JVM wire writer. The paused JVM
application lane is out of scope except where a same-named mechanism could
cause a duplicate implementation.

Evidence inspected:

- `src/seon/client.cljs` boot, program index, prune, replay, instrumentation,
  agent init, and web injection paths;
- `src/seon/state.cljs` reconciliation;
- `src/seon/db.cljs` provenance queries and transaction-context public API;
- `src/seon/db/internal.cljs` AsyncLocalStorage and metadata stamping;
- transaction-metadata consumers across agent debug, context rendering,
  typeahead, planning, warning classification, web invalidation, and eval;
- `src/seon/server/wire.clj` and `src/seon/store/wire.cljs` write correlation;
- focused tests that currently pin the old mechanisms;
- vendored Datahike transaction metadata, upsert, retract, component, and
  history semantics.

Live measurements from the default pod established the performance baseline:

- three warm new-agent requests: 8.43–8.89 seconds;
- cluster-wide maintenance per mint: roughly 7.8–8.1 seconds;
- actual one-agent initialization: roughly 0.44–0.58 seconds;
- core function indexing: roughly 3.1 seconds for 1,044 rows;
- test indexing: roughly 0.6 seconds for 282 rows;
- schema indexing: roughly 0.004 seconds for 1,180 rows;
- replay: roughly 0.07 seconds;
- global instrumentation: roughly 0.10 seconds.

## Current lifecycle graph

### Process entry

`seon.client/start-agent!` is currently both cluster startup and the public
new-agent operation. It performs, in order:

1. reuse or open the cluster connection;
2. set the global database connection;
3. assert history and transaction-metadata preconditions;
4. recover crashed runs on a genuinely new process connection;
5. ensure the self-host CLJS compiler state;
6. decide which existing agents resume and whether a new agent is minted;
7. run `boot-seed!` unconditionally;
8. run `prune-core-ghosts!` unconditionally;
9. replay the complete agent-authored program graph;
10. instrument every database-described function;
11. initialize every selected agent sequentially;
12. start the web server;
13. sync the AI provider row;
14. install the debug database listener;
15. install the global ticker;
16. register the child-arm hook.

This sequence is valid only for process/cluster startup. `/agents/new` invokes
it with `:mint? true`, so all sixteen steps run for a one-agent request.

### Existing one-agent path

`seon.client/init-agent!` already contains almost all correct one-agent work:

1. ensure or accept compile state;
2. establish the agent's home namespace;
3. optionally create the agent entity;
4. install its wake trigger;
5. host its runtime id.

The function is nevertheless mode-driven by `::mint?`, combining mint and
resume semantics. Hot reload and spawned children call the same function with
`false`; `/agents/run` calls it with `true`; `/agents/new` does not use it
directly and instead re-enters `start-agent!`.

### Hot reload

`after-reload` currently:

- re-arms every wake trigger;
- replaces the child-arm closure;
- reinstalls the global ticker;
- performs global database-driven instrumentation;
- restarts the heartbeat.

It does not rebuild or reconcile the program graph. Therefore the live
JavaScript definitions may be fresh while the persisted program graph still
describes the previous source until another full `start-agent!` occurs. This is
a correctness gap hidden by the expensive new-agent path.

## Current transaction-context model

`seon.db.internal` holds two AsyncLocalStorage instances:

- agent id — a useful runtime identity/injection scope;
- a generic transaction-context map — nested maps merge and are copied into
  every transaction's `:tx-meta`.

`merge-tx-context-into-opts` additionally derives `:seon.db/origin`, merges the
agent id, accepts arbitrary explicit metadata, and persists the resulting map.
The same map is being used for two different jobs:

- runtime execution control, such as suppressing eager schema tee during
  replay or an eval batch;
- durable provenance queried after commit.

Those jobs must separate. Runtime context may remain fiber-local without being
stored. Persisted transaction facts should be constructed explicitly from a
small whitelist of schema-registered refs.

## Metadata field audit

| Current field | Writers | Production readers | Finding | Proposed disposition |
|---|---|---|---|---|
| `:seon.db/agent-id` | `with-agent`, turn/eval/web scopes | plan blame, typeahead authors, agent canvas functions, UI recency, debug error attribution, web fan-out | Real authorship/scoping need, but stored as a scalar duplicate of the agent entity | Replace with `:seon.db/user` ref to the agent |
| `:seon.db/turn-id` | turn and scheduled-run scopes | turn inspection lists tx ids; debug/logging also has ordinary turn/eval links | The initial audit overclaimed a complete causal/replay query. Arbitrary eval effects cannot be reconstructed safely | Keep current turn in runtime context; remove persisted transaction field |
| `:seon.db/eval-id` | per-form eval scopes | no provenance query; render code merely excludes it; runtime schema tee checks its presence | Primarily a runtime control marker today | Keep runtime eval context; do not persist unless a concrete “all transactions in eval” forensic query is ratified |
| `:seon.db/session-id` | no production writer found | no production reader; precondition test only | Dead legacy session concept | Delete schema, precondition, tests, and documentation |
| `:seon.db/resume-marker?` | no production writer found | no production reader; precondition test only | Dead marker | Delete schema, precondition, tests, and documentation |
| `:seon.db/replay?` | replay scope | eager schema tee suppression reads current runtime context | Necessary runtime guard, not durable provenance | Move to execution context not copied into transaction metadata |
| `:seon.db/origin` | derived at transact boundary from claimed runtime keyword | reconciliation, core overwrite/prune guards, warnings, inventory grouping, web fan-out, eval override guards | Overloaded classification combining user, operation, security posture, and UI scope | Replace core/config/agent cases with user/process refs and direct datom queries; keep runtime-only test/replay distinctions outside persisted metadata |
| `:seon.store.wire/write-id` | pod wire client/writer | response correlation, replayed feed, reactive server | Necessary transport correlation across asynchronous request, commit, and broadcast | Keep in the wire namespace; it is not application provenance |

### Origin enum audit

The registered enum is `:user`, `:agent`, `:system`, `:replay`, `:core-seed`,
`:config`, and `:test-run`.

- `:core-seed` maps to `{user root, process boot}`.
- `:config` maps to `{user root, process config}`.
- `:agent` maps to `{user agent, process repl}`.
- `:system` inside an agent turn maps to the actual submitting user and REPL
  process; it is not a separate user or operation class.
- `:replay` is used to suppress runtime tee behavior; replay writes only logs
  in the current design and does not need a permanent classification field.
- `:test-run` is used as nested runtime context so eval behavior can distinguish
  automated tests. No production historical query consumes it.
- no production writer for `:user` was found.

This enum can disappear after its consumers move. Do not replace it with an
equally broad operation enum.

## Minimal target transaction facts

The inclusion test is factual, not procedural: persist a transaction attribute
only when it states an irreducible fact about the committed assertions. Runtime
branch markers stay in execution context. The domain datoms themselves are the
record of what processing accomplished.

### `:seon.db/user`

A cardinality-one ref on the transaction entity. It points directly to the
existing root agent, human user, or agent entity. There is no
`:seon.db.user/id` and no duplicate actor table.
Required
queries:

- assertions submitted by root, a human, or a particular agent;
- per-agent UI recency and agent-authored function discovery;
- error attribution when ordinary domain links do not reach the database user.

Root authors boot/config work; the process ref distinguishes their ingress.

### `:seon.db/process`

A cardinality-one ref to a stable `:seon.db.process/id` entity. Seed exactly
`:seon.db.process/boot`, `:seon.db.process/config`, and
`:seon.db.process/repl`. This records the execution path that produced the
facts without turning root/config/boot into separate users or persisting a
generic operation enum.

### Runtime-only execution context

The runtime still needs fiber-local values:

- current agent;
- current turn;
- current eval;
- replaying?;
- test-running?.

Only the selected user/process refs cross the transaction boundary. Current
turn/eval/replay/test state remains useful to runtime control and logging, but
the context API must stop equating “present in AsyncLocalStorage” with “copy
this attribute onto the transaction.”

### Wire write id

Retain `:seon.store.wire/write-id`. It answers a transport question the domain
graph cannot derive: which asynchronous request corresponds to a committed
writer transaction and broadcast event.

## Reconciliation audit

### `seon.state/reconcile!`

Current behavior:

- requires exactly one registered identity attribute per desired entity;
- calls `db/managed-identities`;
- calculates stale entity ids absent from the desired identity set;
- submits all desired entity maps on every call;
- retracts stale entities;
- always calls `db/transact!`, including when desired/current are converged.

Correct pieces to retain:

- desired state is plain immutable data;
- identity attributes are the upsert handles;
- additions and removals belong in one atomic transaction;
- component removal follows Datahike component semantics;
- errors surface as values at the public boundary.

Defects to replace:

- whole-store `managed-identities` scan;
- “first live datom transaction” as a proxy for first-ever assertion;
- reasserting every desired entity;
- no general retraction for attributes omitted from a surviving entity;
- entity-wide retraction without explicit mixed-origin attribute analysis;
- unconditional empty/converged transaction;
- response count called `upserted` even when most desired rows were unchanged.

### `db/managed-identities`

The function queries every current datom, calculates a minimum transaction per
entity, separately queries every transaction origin, and then scans again for
identity attributes. It is both broad and historically imprecise: retracted or
replaced original datoms are absent from the current database, so the minimum
currently-live transaction is not necessarily the entity's first assertion.

Delete it after population-specific identity/attribute queries are proven.
Config authority comes from its explicit population contract, not from who last
wrote a value. Provenance remains available for audit and mixed-fact guards.

### `row-origin-scan` and bootstrap inventory

`row-origin-scan`, `bootstrap-row-ids`, and `core-attr-namespaces` reuse the
same first-live-transaction assumption to classify the data browser and warning
surface. These are display classifications rather than domain state. Replace
them with direct user/process datom queries or a cheaper attribute-presence read
model; do not preserve the current broad scan merely for UI grouping.

### Config healing

`config/stale-singleton-retractions` exists because generic reconciliation does
not retract omitted attributes from a surviving config singleton. Once exact
attribute reconciliation is live, delete:

- the helper;
- its focused tests;
- the `:config-heal` transaction in `boot-seed!`;
- documentation describing it as a required second phase.

## Core program graph audit

### Snapshot construction

`index-core!`, `index-schemas`, and `index-tests` collectively calculate the
desired core program graph. The work is repeated by `prune-core-ghosts!`.

`var->fn-row` and `var->test-row` each call `read-src-file` for an individual
var. Hundreds of vars from the same file therefore reread identical source.
The snapshot should group var metadata by `:file`, read each file once, and
extract all referenced forms from the shared text.

Creation instants are generated on every snapshot. Existing comparison code
selectively ignores them. Remove those derivable program `created-at` fields;
the identity/source datom transaction and `:db/txInstant` already provide
creation/change time. Retain a domain timestamp only when it is a genuine event
or pre-event coordinate that cannot be projected from the entity's transaction.

### `core-index-tx`

Correct responsibilities to retain:

- compare desired program rows with current rows;
- protect agent-authored program facts;
- heal changed source, specs, docs, arglists, and visibility;
- retract a specification that disappeared;
- maintain namespace require edges.

Problems:

- it embeds its own special-purpose partial reconciliation;
- different program entity shapes use different ad hoc comparisons;
- it must query origin to decide whether a row may be changed;
- it cannot remove identities absent from the desired set;
- it is coupled to a second cleanup function.

Move its comparison logic onto the exact reconciliation data functions. Keep
program-specific desired-data construction close to the namespaces that own
`:seon.fn`, `:seon.ns`, `:seon.schema`, and `:seon.test` data; keep only
cross-domain snapshot orchestration at the client/runtime boundary.

### `prune-core-ghosts!`

This function rebuilds all four desired identity sets, queries core-origin
source datoms, applies special schema-source exceptions, and retracts absent
entities. It exists solely because `core-index-tx` cannot express deletion.

Delete after exact program reconciliation covers removed and renamed
identities. Its degenerate-empty guards should become snapshot validation: an
invalid or unexpectedly empty required snapshot must fail before compiling a
mass-retraction transaction.

## Runtime service audit

| Effect | Correct lifecycle | Current duplication/change |
|---|---|---|
| Open connection and set `db/*conn*` | process boot | currently revisited by `start-agent!`; retain one cluster runtime owner |
| Assert DB preconditions | process boot and explicit new-connection tests | remove dependency on seven legacy metadata attrs |
| Recover crashed runs | cold process boot | current `existing-conn` guard is correct; move intact |
| Ensure bootstrap compiler | cold boot; rebuild when compiler generation changes | do once and pass state into per-agent operations |
| Reconcile schema/core/config | cold boot, warm restart, or relevant reload | never agent mint |
| Replay persisted agent code | new JS runtime | never agent mint; normally not hot reload |
| Instrument complete runtime | after cold replay; targeted after hot reload | never agent mint |
| Start web server | process boot | currently idempotently called after every `start-agent!` |
| AI config sync | process boot/config change | never agent mint |
| Debug listener | process boot/hot reload replacement | never agent mint |
| Ticker | process boot/hot reload replacement | never agent mint |
| Child-arm hook | process boot/hot reload replacement | never agent mint |
| Wake trigger | one agent init/resume and hot reload replacement | retain per-agent behavior |
| Runtime host | one agent init/resume | retain per-agent behavior |

## Web creation audit

The web layer contains two injected creation seams:

- `set-create-agent-fn!` for `/agents/new`, wired to heavyweight
  `start-agent!`;
- `set-mint-agent-fn!` for `/agents/run`, wired directly to `init-agent!`.

There should be one namespaced mint service with a request containing optional
purpose/provider inputs and a response containing the new identity. Both web
routes call it. The web namespace keeps HTTP parsing/navigation concerns; the
runtime namespace owns minting.

`!create-in-flight` currently serializes `/agents/new` because the path reruns
global boot. After mint becomes one atomic per-agent operation, reassess the
lock. It should be deleted if Datahike identity and runtime-host fencing make
concurrent independent mints safe. Do not retain global serialization without
a demonstrated shared mutable resource.

## Namespace and code placement

The target should clarify ownership without introducing parallel versions:

- existing `seon.agent` and `seon.user` namespaces continue to own their
  identity attributes;
- `seon.db.process` owns `:seon.db.process/id`, the boot/config/REPL seed data,
  and process lookup helpers;
- `seon.db` owns `:seon.db/user`, `:seon.db/process`, execution-context to
  durable-metadata selection, provenance queries, and transaction submission;
- `seon.state` owns pure exact-diff compilation and the transact-if-nonempty
  operation.
- `seon.client` should retain runtime composition, not hundreds of lines of
  program-row extraction and reconciliation policy.

There is already a paused-lane `src/seon/db/tx.clj` that stamps caller/source
and a second timestamp. Do not create an unrelated CLJS sibling with another
semantic model. Before implementation, decide whether to retire that paused
mechanism and establish `seon.tx` as the eventual shared namespace, or keep the
new active implementation in an existing namespace until both lanes can
converge. The final result must have one transaction-provenance model.

## Specific code change inventory

### Modify

- `src/seon/client.cljs`
  - split process boot from mint/resume;
  - build one program snapshot per generation;
  - reconcile rather than prune;
  - move service installation to process boot;
  - make hot reload reconcile changed source before targeted instrumentation;
  - replace duplicate web injection closures with one mint service.
- `src/seon/state.cljs`
  - extract pure current/desired diff functions;
  - add exact omitted-attribute and cardinality-many handling;
  - accept an explicit database-user/provenance scope;
  - skip transaction submission for empty tx-data.
- `src/seon/db.cljs` and `src/seon/db/internal.cljs`
  - separate execution context from persisted metadata;
  - replace scalar agent/origin provenance with user/process refs;
  - remove origin derivation and broad managed scans after migration;
  - update preconditions to the minimal schema.
- `src/seon/config.cljs`
  - remove stale-singleton special handling after exact reconciliation.
- `src/seon/eval.cljs`
  - replace core-origin queries with root/boot queries;
  - keep replay/eval guards runtime-only;
  - stamp agent user and REPL process through the common transaction context;
  - remove persisted turn/eval correlation.
- `src/seon/agent/turn.cljs` and `src/seon/agent/loop.cljs`
  - establish agent user and REPL process once for downstream writes;
  - remove `:system` origin classification.
- `src/seon/web/reactive/call.cljs`
  - establish the agent user without an origin keyword.
- `src/seon/web/debug.cljs`
  - move onto the shared Datastar subscription/read-dependency graph;
  - remove origin-specific fan-out entirely.
- `src/seon/agent/debug.cljs`
  - query ordinary turn/eval facts and transaction user/process where useful;
  - do not imply arbitrary transaction/effect replay.
- `src/seon/agent/ctx/render_fns.cljs`, `src/seon/ui/agent_view.cljs`,
  `src/seon/ai/typeahead.cljs`, and `src/my/plan/internal.cljs`
  - replace scalar agent-id joins with database-user-ref joins;
  - remove legacy metadata attrs from dependency-noise lists.
- `src/seon/warn.cljs` and inventory readers in `src/seon/db.cljs`
  - use boot-user provenance without a whole-store first-tx scan.
- `src/seon/web/serve.cljs`
  - one injected mint operation;
  - remove heavyweight-boot wording and likely the global create lock.
- `src/seon/server/wire.clj` and `src/seon/store/wire.cljs`
  - retain write-id behavior;
  - pass the new schema-registered transaction refs unchanged.

### Delete after replacement is proven

- `seon.client/prune-core-ghosts!`;
- the second `index-core!` / `index-tests` / `index-schemas` build;
- `seon.db/managed-identities` and its managed-scope schemas;
- `seon.db/row-origin-scan` first-live-tx classification;
- `seon.config/stale-singleton-retractions`;
- the config-heal transaction;
- `:seon.db/session-id`;
- `:seon.db/turn-id`;
- `:seon.db/eval-id`;
- `:seon.db/resume-marker?`;
- persisted `:seon.db/replay?`;
- `:seon.db/origin`, `managed-origins`, and `derive-origin` after all consumers
  migrate;
- duplicate `set-create-agent-fn!` / `set-mint-agent-fn!` seams;
- `:mint?` as a mode switch between mint and resume;
- tests and documentation whose only purpose is to preserve these mechanisms.

### Retain or refine

- AsyncLocalStorage for fiber-local agent/execution context;
- `db/with-agent` semantics for runtime injection;
- one database transaction API;
- wire write-id correlation;
- Datahike history and transaction entities;
- identity lookup refs and component retraction semantics;
- crash recovery fencing;
- one idempotent wake trigger per agent;
- one ticker, listener set, server, and spawn hook per runtime;
- inline instrumentation of newly evaluated functions;
- structural tests and Datahike `with` as a correctness oracle.

### Required in-memory authority audit

The writer/reader inventory above does not yet classify every `atom`,
`volatile!`, `defonce`, AsyncLocalStorage value, or in-memory registry. That is
a required Phase 0 source audit, not an assumption that all current mutable
state is legitimate. For each cell record its owner, complete value shape,
writers/readers, whether loss on process death is harmless, database inputs,
invalidation trigger, and cold-rebuild function. Its disposition must be one of:

- irreducible process handle/state such as a live connection, socket, timer, or
  analyzer object;
- a DB-derived cache/projection with one explicit rebuild/invalidation path;
- a missing durable fact that moves to Datahike; or
- duplicated authority that is deleted.

There is no goal of forcing unrelated handles into one global atom. A small
self-contained subscriber registry or in-flight dedupe set is valid when it is
safe to lose. A generated-id history set, lifecycle status, durable routing
registry, schema/program inventory, or last-seen flag is suspect until proven.
The Malli registry is the reference pattern: canonical forms are database facts;
one complete validated runtime projection swaps atomically and can always be
rebuilt.

The first source pass has already found these concrete cells; Phase 0 completes
this census instead of rediscovering them:

- `src/seon/eval.cljs` keeps `!timeout-ms` and `!next-budget-ms` globally, so
  concurrent agents can change one another's eval budget. Durable defaults and
  overrides belong in config/agent facts; a one-shot override is lexical or
  AsyncLocalStorage execution context.
- `src/seon/error.cljs` previously kept `!expecting-core-fault`,
  `!dev-eval-depth`, and process-global `!persists-inflight` counters. Phase 0
  retired all three in favor of one AsyncLocalStorage scope map. Expected-test,
  dev-eval, and persistence markers now follow only their originating async
  work; behavioral tests pin both propagation and isolation while one write is
  still pending. The injected DB hooks and bounded pending-error buffer remain
  valid process-local handles whose loss behavior is explicit.
- `src/seon/client.cljs` keeps `!agent-conn` beside `seon.db/*conn*`, plus
  `!indexed-test-vars` and `!extra-core-vars` beside the database program graph.
  Delete these duplicate authorities after their callers use the canonical
  connection/program projection.
- `src/seon/agent/run.cljs` keeps `!runs-this-process`, which becomes false
  history across resume. Inspect the actual live result/host handle instead of
  remembering a semantic run set.
- `src/seon/schema.cljc` keeps a mutable registry, asynchronous tee, and
  `!last-tee` as overlapping schema paths. Canonical database forms produce one
  validated registry generation; there is no second durable tee authority.
- `src/seon/server/reactive.clj` persists `:seon.subscription/*` while
  `src/seon/server/boot.clj` also keeps `!engines`, and the CLJS web surface has
  three SSE registries across serve, Datastar, and debug. Subscriptions and
  dependency state are ephemeral projections; consolidate them under one
  branch-qualified live-channel owner and persist none of them.
- `!create-agent-fn`, `!mint-agent-fn`, `!create-in-flight`, and the agent
  `!arm-child-fn` are overlapping creation seams. One lifecycle entry point and
  one post-commit host constructor replace them.
- Embedding hit identities currently live only in `src/seon/embed/stash.cljs`
  AsyncLocalStorage. Persist the selected hit refs/ids on the turn because they
  are durable evidence of what the model saw; keep only the in-flight payload
  handle lexical.
- Loop input/ticker handles, REPL compiler state, sockets, and wire connections
  are legitimate process handles, but every one needs an explicit stop and
  cold-rebuild owner. Agent/server registries are branch-qualified DB-derived
  caches, never routing authority.
- `!config-view-cache`, render/route caches, filtered database handles, the blob
  `!dir`, and `!own-write-ids` must be keyed by the full branch/commit or adapter
  generation that makes their values valid and cleared on root replacement.
  `!own-write-ids` in particular must not be reinitialized mid-flight; its
  lifecycle belongs to one wire-adapter generation.

Exact reconciliation has one additional shared-state hazard: its current read,
pure delta compilation, and writer submission are not atomic merely because the
JVM serializes submitted writes. Config/program/schema transitions must include
an expected full-head commit fence. A mismatch returns conflict, re-reads the
new head, recompiles the candidate, and retries within a bounded policy. The
validated Malli registry swap is serialized by the same transition owner and
may publish only after its matching fenced transaction commits. A mechanical
test injects a concurrent write between read and submit and proves that no stale
many-set omission, destructive entity retraction, or incomplete registry can
land.

The same Phase 0 pass must remove existing token-reporting violations rather
than grandfather them. Known examples are `src/seon/eval.cljs` emitting
`N chars elided` into agent-visible persisted EDN and `src/seon/embed.clj`
logging raw characters beside tokens. Storage-tier character fields may remain;
every log, transcript, debug, UI, and error display uses the one
`seon.ai.tokens/estimate` boundary.

## Test migration inventory

Tests requiring semantic replacement include:

- `test/seon/db/tx_context_test.cljs` — new execution-context and explicit
  durable-metadata boundary;
- `test/seon/db/origin_guard_test.cljs` — remove security-flavored origin
  stamping; replace with database-user selection and validation;
- `test/seon/boot/preconditions_test.cljs` — minimal tx ref schemas, not seven
  fields;
- `test/seon/state_test.cljs` — exact attribute/cardinality diff and zero-write
  convergence;
- `test/seon/index_core_test.cljs` — one snapshot and one reconciliation path,
  including deletion/rename without a pruner;
- `test/seon/config_test.cljs` — omit special singleton heal tests once the
  generic exact diff covers the behavior;
- eval, warning, render-function, UI, typeahead, plan, and debug tests that join
  on scalar agent/origin metadata;
- boot/reconcile tests that currently expect repeat desired upserts rather than
  no transaction.

Do not add assertions for context wording. Test datoms, query results,
transaction counts, listener events, runtime call boundaries, and durable
agent workflows.

## Implementation order derived from the audit

1. Finish the runtime-authority/token/performance baseline and name every
   mutable owner.
2. Replace candidate ID generation with the one schema-driven atomic allocator.
3. Split process boot, mint, and resume to remove the measured warm-mint
   lifecycle bug without waiting on the database migration.
4. Separate runtime execution context from the metadata map without changing
   existing persisted fields yet.
5. Install/migrate `:seon.db/user`, `:seon.db/process`, and the three process
   refs; migrate all metadata readers/writers atomically and remove the old live
   model.
6. Build exact pure reconciliation functions with the full-head fence and
   verify them with Datahike `with`.
7. Move config onto exact reconciliation and the one crash-safe operation
   intent; delete config/AI/brand healing.
8. Replace schema reassertion/truncated schema source/decomposition with native
   reopen plus canonical Malli facts and one atomic runtime registry projection.
9. Add one file-grouped deterministic program snapshot, move the program graph
   onto exact reconciliation, and delete ghost pruning.
10. Refine declaration loading, hot reload, async fencing, and incremental
    exact-data instrumentation.
11. Correct/extend Datahike's coordinate, branch, attachment, fork, and guarded
    restore path.
12. Unify live subscriptions/feeds, introduce observed-read result-diff routing,
   and delete provenance fan-out/stored literal read sets.
13. Bound legitimate render queries/units and profile cold boot, converged
    restart, sequential/concurrent mint, SSE CPU, event-loop delay, and RSS.

The lifecycle split deliberately precedes the provenance migration. It is a
small, independently falsifiable correction that removes most observed mint
latency without requiring the database model refactor to be rushed.

## Questions resolved after the audit

- `:seon.db/user` points directly to existing agent/human/root entities through
  `:seon.db/ref`; there is no duplicate user identity.
- Root is the user; boot, config, and REPL are process refs.
- Turn/eval domain identities remain ordinary durable facts. They are removed
  only from transaction metadata/runtime provenance, which does not promise
  complete causal effect reconstruction or replay.
- Config authority is an explicit population/attribute contract and repairs
  that subset regardless of the last writer. Exclusive deletion has a
  mixed-fact guard.
- Entity-schema decomposition is removed as a stored projection. Native
  Datahike schema remains durable installation state; canonical Malli forms
  rebuild the runtime registry/catalog.
- UI invalidation uses observed database read results. Provenance is not a
  dependency graph.

Only agent identities use readable package words; all other generated
persistent identities use the compact package adapter behind the same atomic
allocator. The rolling header rate is removed. Read-only `as-of`, isolated
writable forks, and a quiesced live restore/undo lifecycle are all in this PRD
and have completed Datahike/Konserve source audits.
