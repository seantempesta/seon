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
boundaries explicit. It should not replace them with a framework. The minimum
durable provenance currently justified is:

- a transaction database-user ref;
- a transaction turn ref, because turn replay requires all transactions that
  occurred inside a turn, including arbitrary domain writes;
- the wire write id, because it correlates a submitted write with the writer's
  committed transaction and reactive feed.

Everything else is either replaceable by those refs and normal domain links,
runtime-only context, dead, or still requires a named query before retention.

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
| `:seon.db/turn-id` | turn and scheduled-run scopes | turn replay, debug error linkage, AI logging reads runtime context | Real query: every transaction performed during a turn, including unrelated domain entities | Replace persisted scalar with `:seon.db/turn` ref; runtime logging may read the same scoped ref |
| `:seon.db/eval-id` | per-form eval scopes | no provenance query; render code merely excludes it; runtime schema tee checks its presence | Primarily a runtime control marker today | Keep runtime eval context; do not persist unless a concrete “all transactions in eval” forensic query is ratified |
| `:seon.db/session-id` | no production writer found | no production reader; precondition test only | Dead legacy session concept | Delete schema, precondition, tests, and documentation |
| `:seon.db/resume-marker?` | no production writer found | no production reader; precondition test only | Dead marker | Delete schema, precondition, tests, and documentation |
| `:seon.db/replay?` | replay scope | eager schema tee suppression reads current runtime context | Necessary runtime guard, not durable provenance | Move to execution context not copied into transaction metadata |
| `:seon.db/origin` | derived at transact boundary from claimed runtime keyword | reconciliation, core overwrite/prune guards, warnings, inventory grouping, web fan-out, eval override guards | Overloaded classification combining user, operation, security posture, and UI scope | Replace core/config/agent cases with database-user refs and direct datom queries; keep runtime-only test/replay distinctions outside persisted metadata |
| `:seon.store.wire/write-id` | pod wire client/writer | response correlation, replayed feed, reactive server | Necessary transport correlation across asynchronous request, commit, and broadcast | Keep in the wire namespace; it is not application provenance |

### Origin enum audit

The registered enum is `:user`, `:agent`, `:system`, `:replay`, `:core-seed`,
`:config`, and `:test-run`.

- `:core-seed` maps to the boot database user.
- `:config` maps to the config database user.
- `:agent` maps to the relevant agent entity as database user.
- `:system` is used inside an agent turn and does not identify a distinct
  user. The turn and agent refs already state its durable context.
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

A cardinality-one ref on the transaction entity. Stable system database users
have `:seon.db.user/id`; agent writes may point directly to the agent entity.
Required
queries:

- current and historical boot assertions;
- current and historical config assertions;
- assertions made by a particular agent;
- per-agent UI recency and agent-authored function discovery;
- error attribution when ordinary domain links do not reach the database user.

The audit has not yet proven that boot and config must be separate users. Their
resulting entities use distinguishable identity/attribute sets, so one stable
system database user may support both reconciliation queries without storing a
processing-stage distinction. Resolve this with concrete queries before seeding
either identity.

### `:seon.db/turn`

A cardinality-one ref to `:seon.agent.turn/id`. This is justified because an
agent can transact arbitrary domain facts during a turn; those entities do not
all need a duplicated turn attribute, yet forensic replay must enumerate the
turn's transactions.

### Runtime-only execution context

The runtime still needs fiber-local values:

- current agent;
- current turn;
- current eval;
- replaying?;
- test-running?.

Only the first two currently have proven persisted consumers. The context API
must stop equating “present in AsyncLocalStorage” with “copy this attribute onto
the transaction.”

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

Delete it after the new database-user-constrained candidate queries are proven.

### `row-origin-scan` and bootstrap inventory

`row-origin-scan`, `bootstrap-row-ids`, and `core-attr-namespaces` reuse the
same first-live-transaction assumption to classify the data browser and warning
surface. These are display classifications rather than domain state. Replace
them with direct database-user-constrained datom queries or a cheaper attribute-presence
read model; do not preserve the current broad scan merely for UI grouping.

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
selectively ignores them. The desired snapshot should instead distinguish
stable calculated content from insert-only metadata so a fresh timestamp never
manufactures drift.

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

- `seon.db.user` owns `:seon.db.user/id`, system database-user seed data, and
  user lookup helpers.
- `seon.db` owns `:seon.db/user`, `:seon.db/turn`, execution-context → durable
  metadata selection, provenance queries, and transaction submission.
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
  - replace scalar agent/turn provenance with refs;
  - remove origin derivation and broad managed scans after migration;
  - update preconditions to the minimal schema.
- `src/seon/config.cljs`
  - remove stale-singleton special handling after exact reconciliation.
- `src/seon/eval.cljs`
  - replace core-origin queries with boot-user queries;
  - keep replay/eval guards runtime-only;
  - stamp agent user and turn ref through the common transaction context;
  - decide whether eval ref persistence has a proven forensic consumer.
- `src/seon/agent/turn.cljs` and `src/seon/agent/loop.cljs`
  - establish agent user and turn ref once for downstream work;
  - remove `:system` origin classification.
- `src/seon/web/reactive/call.cljs`
  - establish the agent user without an origin keyword.
- `src/seon/web/debug.cljs`
  - route invalidation from transaction user and changed attributes;
  - remove origin-specific global-fanout rules where the datoms themselves
    identify shared core/config changes.
- `src/seon/agent/debug.cljs`
  - query transaction turn ref and database-user ref.
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

1. Ratify `:seon.db/user` and `:seon.db/turn` with manual current/history
   queries on a fresh in-memory Datahike database.
2. Separate runtime execution context from the metadata map without changing
   existing persisted fields yet.
3. Split process boot, mint, and resume; immediately remove eight-second mint
   behavior while leaving seed/index internals unchanged.
4. Add one file-grouped deterministic program snapshot.
5. Build exact pure reconciliation functions and verify them with Datahike
   `with`.
6. Move config onto exact reconciliation and delete config healing.
7. Move the program graph onto exact reconciliation and delete ghost pruning.
8. Introduce database-user/turn ref metadata and migrate all readers atomically.
9. Delete origin and dead metadata fields after a code-search and live-query
   proof finds no consumers.
10. Refine hot reload and instrumentation using the single snapshot.
11. Profile cold boot, converged restart, sequential/concurrent mint, SSE CPU,
   and RSS; then remove any remaining broad scans or redundant broadcasts.

The lifecycle split deliberately precedes the provenance migration. It is a
small, independently falsifiable correction that removes most observed mint
latency without requiring the database model refactor to be rushed.

## Remaining questions before schema implementation

- Should `:seon.db/user` accept both system-user and agent lookup refs through
  the shared `:seon.db/ref`, or should agents also carry `:seon.db.user/id`? The
  former avoids duplicating identity; prove the query and validation shape.
- Are root, boot, and config genuinely distinct database users, or are some of
  them operation names? Begin with the fewest identities and prove each one
  through a query that cannot be expressed from the resulting domain facts.
- Does turn replay require transactions from nested asynchronous work after a
  turn closes, and if so, what fence determines whether they legitimately
  retain the turn ref?
- Is “all transactions caused by one eval” a real required forensic query? If
  yes, retain it as a ref; if not, keep eval id runtime-only.
- Which core/config entities currently contain attributes later asserted by
  another database user? Query the live store before choosing entity versus
  attribute-level retraction for each desired set.
- Should entity-schema decomposition be boot-user data reconciled from the
  current Malli registry, or append-only Datahike installation facts? Its
  current dual role must be separated before consolidation.
- Which UI invalidations truly require global fan-out after changed-attribute
  routing is considered? User provenance should not become a substitute for
  dependency tracking.
