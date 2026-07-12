---
type: decision
status: draft
tags: [decision, database, flow, agent]
---

# Runtime facts, reconstruction, and exact state transitions

## Decision in one page

The reliability refactor has one governing rule: persist the facts produced by
processing, not a trace of the processing algorithm. Datahike already gives
every datom a transaction id and every transaction a time. Seon adds exactly two
normal application-provenance facts:

| Attribute | Shape | Meaning |
|---|---|---|
| `:seon.db/user` | cardinality-one `:seon.db/ref` | Existing root, human, or agent identity whose operation submitted the facts |
| `:seon.db/process` | cardinality-one `:seon.db/ref` | Stable execution path that produced the facts: boot, config, or REPL |

The user and process are deliberately orthogonal. Root is a user. Boot and
config are processes running as root. An agent is its own database user and its
evals run through the REPL process. No duplicate actor/user entity is created.
The transport separately retains `:seon.store.wire/write-id` as request/commit
correlation; it is not application provenance.

Normal production transactions after genesis carry both refs. They do not
carry a persisted turn, eval, session, origin, replay flag, resume marker,
operation, owner, or entity kind. Runtime scopes may still carry values such as
the current run, turn, eval, or replay guard, but the transaction boundary
copies only the explicit user/process whitelist.

Config is authoritative for a declared subset of database facts when it is an
explicit input to startup or apply. Defaults + the selected manifest +
environment overrides compile to canonical desired maps; exact reconciliation
restores that subset and leaves every fact outside its population contracts
alone. A converged apply emits no transaction. A populated database may boot
without a config input and reconstruct from its canonical facts unchanged.

The database is the durable source of truth for native Datahike schema,
canonical Malli forms, program declarations, configuration results, and domain
facts. A new JavaScript runtime reconstructs only safe executable declarations.
It never re-executes arbitrary evals or claims to recreate external/runtime
state.

UI dependency routing is a runtime projection, not provenance or stored graph
data. One compiled subscription per normalized Reitit view key observes actual
`seon.db` reads, uses changed attributes only as a candidate gate, compares the
read result before and after the transaction batch, and renders only units whose
inputs changed.

The identity allocator and Datahike/Konserve lifecycle have now been grounded in
their package/library sources. Only agent identities use readable package words;
all other generated persistent identities use the compact adapter behind the
same schema-driven allocator. Historical simulation, writable branches, and
live restore use Datahike's existing commit graph with one exact coordinate.
Neither decision changes the provenance/config/replay boundaries above.

## Transaction provenance

### Users are existing entities

`:seon.db/user` points through the shared ref shape to an existing identity:

```clojure
[:seon.agent/id "root"]
[:seon.agent/id agent-id]
[:seon.user/id human-id]
```

There is no `:seon.db.user/id`. Root is the existing root agent. A child-mint
transaction names its parent agent; a human-created agent names the human; boot
and config writes name root. A newly created agent cannot claim authorship of
its own creation merely because runtime setup has already selected its home
namespace.

Genesis installs the native `:seon.user/id` capability but does not need to
claim the human authored itself. The first normal root/boot desired-state
transition ensures the stable human row before web/REPL admission opens. The
same ensure runs on existing-store migration/cold boot, so `{user human,
process repl}` always resolves before a human write.

The user ref is provenance, not ownership, authentication, authorization,
credentials, or a role. Those concerns may later use trusted transaction
metadata, but they are not part of this refactor.

### Processes are stable execution paths

The process identity and transaction ref are:

```clojure
(schema/register! :seon.db.process/id
  [:keyword {:seon.db/identity true}])

(schema/register! :seon.db/process :seon.db/ref)
```

Genesis creates exactly these process identities:

```clojure
{:seon.db.process/id :seon.db.process/boot}
{:seon.db.process/id :seon.db.process/config}
{:seon.db.process/id :seon.db.process/repl}
```

They answer where committed facts came from without inventing an operation
taxonomy:

- `{user root, process boot}` — compiled program/schema seed, migrations, and
  runtime recovery facts;
- `{user root, process config}` — the exact configured subset;
- `{user agent, process repl}` — an agent eval and its resulting domain/program
  facts;
- `{user human, process repl}` — an interactive human operation;
- `{user root, process repl}` — an orchestrator operation outside boot/config.

There are no per-restart process instances, OS PIDs, web/scheduler/test/replay
process rows, or persisted operation names. Add another process only when it
describes a stable ingress whose historical distinction is useful.

### Provenance is per datom

The durable relationship is:

```text
datom -> transaction -> user
                     -> process
                     -> :db/txInstant
```

One entity may accumulate datoms written by different users and processes.
That is normal EAV history. Queries for current or historical authorship join a
datom's transaction to these refs; they do not stamp an owner or creator on the
domain entity.

This also means provenance does not determine reconciliation authority. Config
may deliberately correct a managed value last written through the REPL. The
config population contract, not the previous writer, says which values config
recalculates.

### The one genesis exception

The provenance attributes and their ref targets cannot describe the transaction
that creates them. A fresh store therefore has one explicit un-attributed
genesis transaction:

1. Detect absence of the root identity in the database.
2. Install the minimal native identity/ref attributes needed for root, human
   users, process identities, `:seon.db/user`, and `:seon.db/process`.
3. Create root and the three process entities in the same ordered transaction.
4. Read every lookup ref back from the committed database.
5. Enable the normal transaction boundary, which requires user and process.

This is a mathematical base case, not a bypass API. It is more accurate than
claiming root authored root. On an existing store, a one-time migration performs
the equivalent minimal installation before any transaction starts using the new
metadata.

Root identity presence proves genesis only; it never means root initialization
finished. The next idempotent root/boot and root/config desired-state transitions
fill root's program/configured attributes and components. A crash between those
steps simply leaves a visible delta for the next boot—no `fresh?` shortcut may
skip it.

Datahike resolves transaction metadata attributes against `db-before`. The
normal transaction compiler must therefore inspect the union of application
attributes and final metadata keys, while genesis must install the metadata
schema before metadata can be used. Missing ordinary native attributes are
ordered before facts that use them.

### Facts deliberately not stored

Do not add:

- `:seon.db/turn` or `:seon.db/eval` transaction refs;
- scalar copies of agent, turn, eval, message, or run ids;
- generic transaction origin/classification, replay, resume, session, operation,
  generation, projection, or status;
- entity owner, manager, kind, role, or provenance summary;
- duplicate timestamps or runtime-instance ids;
- dependency edges, subscriptions, dirty flags, render hashes, or last-seen
  state;
- authorization roles, permissions, credentials, or principals.

The wire write id remains a transport correlation fact in
`:seon.store.wire/write-id`. It is not application provenance and is retained
because it joins an asynchronous write request to its committed response/feed
event.

## Config restores a known subset

### Pure compilation

Config resolution is a pure precedence function:

```clojure
(compile-desired-config defaults manifest environment)
;; => canonical identity maps
```

Explicit environment values override the selected manifest, which overrides
defaults. Procedural remove commands do not enter the database compiler;
absence from the final desired population means absence.

The database is the source read by the running system. Manual changes are live
until a selected config is next applied. A startup with an explicit config input
and an explicit apply intentionally restore the managed subset to the newly
compiled desired value; a config-free startup performs no config transaction.

### Population contracts

Exact reconciliation is reusable data processing, but it cannot infer what an
arbitrary EDN document owns. Each caller supplies a validated runtime
population contract containing:

- the identity attributes that enumerate the population;
- the attributes this transition calculates;
- cardinality and component semantics from registered schema;
- whether absent identities are exclusive and may disappear; and
- the transaction user/process refs.

These contracts are code/schema, not persisted ownership entities. Initial
config populations are concrete and narrow:

| Population identity | Managed facts | Existing-entity behavior |
|---|---|---|
| `[:seon.config/id "cluster"]` | Every registered config singleton attr, including agent/root context templates | Exact attrs; never delete identity |
| `[:seon.ai/id "config"]` | Config/env-derived provider/model dials | Exact attrs; never delete identity |
| `[:seon.web.brand/id "brand"]` | Config/env-derived brand text/assets/theme dials | Exact attrs; never delete identity |
| `:seon.route/name` identities | Complete registered route attrs and owned middleware/components | Exact exclusive set with destructive guard |
| `[:seon.agent/id "root"]` | Only the configured root `:seon.agent/ctx` component subtree and explicitly listed root defaults | Replace that subtree exactly; preserve every other root fact |

The general agent-context value on the config singleton is a mint template.
Non-root agents receive a component copy once at creation and may customize it;
later config applies do not rewrite existing agents. Agents, messages, plans,
knowledge, and agent-authored program facts remain outside config. The retiring
`my.skills` corpus is not a default managed population. Root/process identities
are ensure-if-absent genesis facts. The core program graph has its own policy.

Active population contracts must be pairwise disjoint at the attribute and
component-subtree level. Compiled defaults for routes/root context are inputs to
the config compiler, not a second root/boot desired set. Root/boot owns native
schema, canonical program declarations, identity ensures, and explicitly named
non-config root capability facts; root/config owns route entities, the root
context instance, and context templates. Any overlap fails candidate validation
before a transaction, preventing boot-default/config-override churn on every
restart.

Retained identities query only their managed values for the normal delta. Before
any destructive component/entity operation, however, the guard reads every
current attr on the candidate, recursively walks the declared component closure,
and checks incoming refs. Only identity/system attrs, explicitly managed attrs,
and the contract's complete component subtree are allowed. An unexpected attr
or outside ref fails before deletion. It never treats transaction provenance as
permanent ownership.

### Exact delta

For current database values and canonical desired maps, pure functions compile:

- a new identity to one canonical entity map;
- a changed cardinality-one value to `:db/add`;
- an omitted managed scalar to value-less attribute retraction;
- a cardinality-many value to additions and retractions of the set difference;
- a changed component value to
  `[:db.fn/retractAttribute entity-id component-attr]` followed by the
  replacement tree;
- a stale exclusive identity to `[:db.fn/retractEntity entity-id]` after the
  recursive guard; and
- equal state to an empty transaction vector.

The caller submits only a nonempty vector. `datahike.api/with` is useful as a
test oracle for the compiled transaction, not as the production diff engine.
All config populations are compiled and validated before submission, then one
config apply commits their combined nonempty delta atomically as root/config.
There is no crash-visible half-applied config/AI/brand/route/root-context state.

This one mechanism replaces reassert-all upserts, config healing, broad
first-transaction scans, and core ghost pruning.

## Schema and runtime reconstruction

### Three distinct layers

Do not collapse these layers:

1. **Native Datahike schema** is durable database capability state. Reopening
   the Konserve store restores it with the database indexes. Install only
   missing attributes. Compare the complete bridge-derived storage signature—
   value type, cardinality, unique identity, and component semantics—before
   accepting a canonical Malli form. Any divergence is an explicit migration,
   regardless of whether a particular Datahike version can mutate one facet.
2. **Canonical Malli schema facts** are full, untruncated EDN forms keyed by
   `:seon.schema/key`. They are data, not executable `(register! ...)` strings.
3. **The process-local Malli registry** is a runtime projection rebuilt from the
   complete canonical facts and replaced atomically after validation.

Compiled core registrations are bootstrap/desired inputs, not a second durable
registry. Fresh-store genesis, an explicit current-core overlay, hot reload, or
reset compares them with the database's canonical core schema facts. An ordinary
populated no-overlay runtime reconstructs from database forms without compiling
or reconciling today's core snapshot. After any selected reconciliation, the
database supplies the complete candidate registry. Agent-authored keys may not
silently override protected core keys.

### Atomic Malli restore

A fresh runtime:

1. loads the minimal compiled bootstrap needed to access the database;
2. queries every current canonical schema form, including agent-authored forms;
3. overlays compiled core desired additions/changes/removals in memory only for
   fresh-store genesis or an explicitly selected current-core/hot-reload/reset
   transition;
4. parses all forms as EDN and compares every attribute's full native storage
   signature with installed Datahike schema;
5. builds one complete temporary registry so forward references resolve and
   validates every form/catalog entry;
6. leaves both the database and live registry untouched if any candidate is
   invalid;
7. commits the validated canonical/native core delta as root/boot only when that
   overlay was selected and is nonempty; and
8. swaps/relinks the already validated complete registry/catalog once after the
   commit succeeds.

No self-host eval is needed to rebuild Malli state. Do not serialize registry
objects, CLJS analyzer state, Promises, or function objects into Konserve.

### Instrument once per effective definition

Function instrumentation is another disposable runtime projection of canonical
program/schema facts. A fresh runtime derives one exact Malli `:data` map from
the validated program graph and performs one bulk instrumentation call after
the complete registry and safe declarations are live. Seon does not populate or
read Malli's process-global function-schema roster as a second authority. Agent
mint/resume, config apply, renders, and ordinary transactions never run that
pass.

After boot, the program write path passes one delta containing exact qualified
symbols whose body/spec changed, appeared, lost a spec, or were deleted. It
filtered-unstruments the affected old exact-data entries from Malli's recorded
originals, compiles replacements against the candidate registry, and
instruments the remaining exact map once. A replacement wrapper never stacks;
spec removal/deletion leaves no stale wrapper or roster entry. Same-key schema
changes are detected by canonical form equality, not key-set snapshots.

Compiled wrappers close over validators, so a schema definition change is also
an effective contract change. The candidate-registry build derives schema refs
through Malli's own schema walk/ref API—including local recursive registries and
cycles—and unions the old and candidate transitive reverse dependency closures.
Only functions whose specs intersect that closure join the same delta. It does
not regex/literal-scan keywords or reinstrument the whole program. Candidate
validation requires complete live multi-arity/variadic contract coverage before
any unstrument. Definition/spec/schema versions are derived from canonical
facts and registry generation; no durable `instrumented?`, dependency datoms,
or second roster is stored. If post-commit wrapper surgery cannot finish, close
readiness and reconstruct the committed generation rather than admitting a
mixed registry/wrapper state.

Persisted entity-schema decomposition is a derived projection and is removed.
The renderer catalog is calculated once per registry generation and cached only
if measurement justifies it.

### Program population policy

The compiled program snapshot contains two policies derived from qualified
symbols, not a stored entity kind:

- the protected `seon.*` floor is an exact root/boot population; edit/delete/
  rename follows the compiled desired value;
- shipped editable `my.*` toolkit definitions are defaults. If a desired
  identity has never existed in database history, install it (including after a
  crash immediately following genesis). If its current source was authored by
  an agent/REPL, preserve it; if an agent/REPL retracted it, preserve that
  intentional absence. If a desired default is absent and its latest relevant
  retraction was root/boot (for example, source removed it and a later release
  reintroduces it), install it again. An explicit reset reasserts shipped
  defaults regardless of history. Removed root/boot defaults may retract only
  current root/boot-authored rows;
- `my.agent.<id>` and other agent-authored program identities are outside core
  reconciliation.

The current source datom's transaction user answers who authored that version;
the symbol namespace answers whether it is protected. Neither becomes an entity
owner/kind attribute. This preserves agent work while allowing deleted compiled
floor symbols to disappear without ghost pruning.

That source-datom shortcut is valid only because declaration writes are exact
whole-row replacements. Namespace/function/schema/test declaration APIs define
their complete managed attribute/component sets; each definition transaction
adds/replaces the canonical row and retracts omitted declaration fields in the
same transaction. Identity/native-system attrs and independent runtime result
facts such as test pass/fail instants are outside that row contract. No public
path partially patches source, spec, arglists, doc, namespace, or require edges.

Migration audits every managed datom on legacy rows. A mixed-author row may not
be classified from source alone: normalize it through an explicit complete
definition or preserve/report it for review. After the invariant is established,
all declaration datoms share one transaction user and removal/default policy is
unambiguous.

Schema identities do not use generic stale-program deletion. Before a canonical
schema form can disappear/rename, build its dependency closure across other
Malli forms, function specs, installed native attributes, and current/history
facts. A validator for an installed or referenced attribute remains canonical
until an explicit data/code migration removes every dependency; a runtime-only,
unreferenced schema may retract normally. Native Datahike attr idents remain
accumulating capability/history facts.

### Safe program reconstruction only

“Replay” is split into three accurately named operations:

- runtime program loading evaluates only persisted namespace, function, and test
  declarations that pass the strict declaration gate;
- turn inspection reads stored prompt/reply/eval/error facts without execution;
- replication gap recovery applies already-committed datoms to a reader.

Arbitrary eval source is never executed during restoration. Process-local
results may be functions, handles, streams, Promises, or external objects; after
restart they are honestly missing or elided in the transcript. Datahike
`as-of`/history restores database state at a transaction, not the external
universe or JavaScript runtime at that time.

Current turn/eval execution context remains runtime-only, while ordinary durable
turn/eval domain records keep their direct modeled facts. No broad turn/eval
causality edge is copied onto transactions. Detached asynchronous work must pass
the live run/CAS fence before it can commit; transaction metadata would neither
make the effect replayable nor make stale work legitimate.

After canonical-form restoration lands, schema registration calls are excluded
from declaration loading. Schemas have exactly one reconstruction path: parse
and validate canonical EDN into the atomic registry projection. The strict
executable declaration loader handles namespaces/functions/tests only.

## Historical simulation, writable forks, and restore

All three database operations are required, but they are not synonyms:

- **Read-only simulation** resolves a full coordinate and passes its immutable
  database value into ordinary queries/renders. It does not move a branch head,
  open a writer, or execute an eval.
- **Writable fork** is a Datahike same-store branch created with `branch!` at
  the resolved retained commit. Logical writes diverge while immutable index nodes remain copy-on-write
  shared. A distinct debug pod/writer connects to that branch through normal
  branch-qualified Datahike configuration. This explicitly debug-scoped branch
  may remain historically exact until it is destroyed.
- **Live restore** resets the current cluster's complete database state to an
  explicitly selected known coordinate/fork. It must first fence new work,
  drain/stop writers and agent hosts, preserve an undo point, switch through the
  supported Datahike branch primitive, reopen the one writer/readers, rebuild
  the runtime from the restored database facts, optionally reconcile selected
  current core and/or an explicitly supplied config, and only then accept work.

None restores JavaScript objects or external effects and none replays arbitrary
eval source. Current source/config are optional desired-state overlays, not
prerequisites for opening durable state. A restore plan independently selects:

- `:seon.db.restore/core-policy` as `:seon.db.restore.policy/preserve-target`
  or `:seon.db.restore.policy/reconcile-current`;
- `:seon.db.restore/config-policy` as
  `:seon.db.restore.policy/preserve-target` or
  `:seon.db.restore.policy/reconcile-supplied`.

Preserving both reconstructs from the target's own canonical schema/program/
config/domain facts and requires no external config. Overlay policy belongs to
this requested transition, not to a stored config mode. The durable attachment
descriptor records logical db-name, store path/id, branch, source/blob read
base, branch-local blob write overlay, and launch endpoints. After a selected
overlay commits, its resulting database facts survive; a later cold boot with
no overlay performs no desired-state write and never rereads current source,
environment, a prior path, or `config/system.edn`.

A live restore is coordinated by the external supervisor, not the root agent it
stops. Config apply and restore share one schema'd external lifecycle-intent
store/scanner and atomic-write protocol; restore extends the same record with
branch/undo fields rather than adding a second mechanism. Before quiescing, the
supervisor persists an irreducible intent record in
its durable registry outside the branch being replaced: request id/requesting
user lookup; exact source, target, and undo `{store-id, branch, commit-id, t}`
coordinates; selected core/config policies; each frozen canonical desired
overlay payload and digest; and the confirmation token.
Completion is a resulting fact; intermediate phase is derived by comparing that
intent with actual branch heads, connections, run fences, and the requested
overlay result. The intent survives supervisor crashes until completion/
undo; it never requires a config artifact that was not part of the confirmed
plan.

Rewinding a branch can reuse transaction ids from the abandoned lineage. All
writer/read-replica connections, pending write-id correlations, SSE
subscriptions, view/read caches, and replay cursors are therefore closed and
recreated from the restored head. Historical coordinates/bookmarks contain
store, branch, commit, and t—not bare transaction ids. Registry/wire routing
verifies logical db-name against the registered `{store-id, branch}` attachment,
and filtered handles/agent hosts are attachment-qualified so duplicate agent ids
on main/debug cannot cross-route. The nontransactional branch
head move is audited by the supervisor intent/completion facts; later database
repairs carry ordinary root/boot or root/config transaction provenance.

The source proof in
[[research/datahike-as-of-fork-and-restore-2026-07-12]] records the API
assessment, branch metadata, crash/undo behavior, and fully namespaced public
shape. One lifecycle service owns all state-changing variants; callers never
manipulate Konserve roots directly. Seon wraps upstream `as-of`, corrected
`branch!`, and guarded `force-branch!`; it does not define a
snapshot format or version tree. The existing physical `fork-database` copy path
is retired from the normal cluster-fork workflow unless a future, separately
proven physical-store-clone requirement justifies it.

Every mutable lifecycle plan rejects before intent/admission unless history and
the effective commit graph are enabled, the selected commit/ancestry records
are readable, and the primary plus every secondary key map exists. An absent
literal `:commit-graph?` key may mean the default true; an effective false store
cannot be repaired by flipping the flag or backfilled in place. The pinned
Datahike fix makes historical/non-current `branch!` use the selected commit's
secondary roots, makes release await writer shutdown, and gives
`force-branch!` an expected-head guard/readback so stale writers/plans cannot
move main.

## Reactive UI is a runtime projection

Reitit supplies the compilation boundary, not database invalidation. Datahike
supplies immutable before/after values and an existing conservative query
attribute-dependency extractor. Datastar accepts multiple complete
ID-addressed elements in one morph event. The target composes those primitives:

1. Reitit matches a route and normalizes path/query plus the live attachment or
   resolved historical coordinate into a unique view key.
2. Database route/context/program facts compile to one view plan with stable
   render units.
3. Each unit renders under a synchronous `seon.db` read observer.
4. Observed reads compile to an in-memory attribute-to-read-to-unit index.
5. A transaction batch retains earliest `db-before`, latest `db-after`, and the
   union of changed datoms/attributes.
6. Changed attributes select candidate reads; each normalized read is evaluated
   once against both snapshots.
7. Only unequal results dirty units; each dirty unit renders once and produces
   complete ID-addressed elements.
8. Identical serialized output is suppressed and the shared event fans out to
   every socket subscribed to the view key.

Dependencies, subscriptions, output caches, and dirty state are never
transacted. Transaction user/process is consulted only for semantic questions
such as “which surface did this agent deliberately update?” It never decides
whether a view can have changed.

One subscription owns plan state for all equivalent tabs and references an
ephemeral unit registry keyed by normalized unit id + parameters + attachment +
resolved commit (or live attachment generation).
Each unit owns its observed reads, output/basis cache, and subscriber refcount.
That is how one global header unit renders once across different page view keys
without merging their plans; the unit is evicted when its last subscription
closes. None of this registry is persisted.

Deliberate-focus recency uses the current renderer's captured scoped reads to
run a bounded indexed history query for the newest matching transaction user +
entity/attribute datom. It is explicitly a current-renderer heuristic: it does
not reconstruct conditional dependencies for every old code version or compare
every historical result. Broad/unknown reads receive definition recency only;
pin/human selection is the exact override. This policy is separate from live
invalidation, which still compares before/after read results exactly.

One gzip Datastar feed mechanism serves agent, roster, debug, and data views.
The separate debug registry, unused `/sse` registry, hard-coded read sets, and
provenance fan-out are deleted after equivalence proof. Route-projection changes
recompile the cached Reitit router through the same database listener.

Legitimate renders must still be bounded. Roster rows, transcript/debug HTML
twins, and data-browser results become stable/windowed units; the shared header
uses cheap index counts rather than full inventory scans. Raising the SCI render
budget is not a scaling fix.

Every process-local mutable cell follows the same authority rule as the Malli
registry. It is either an irreducible live handle, or a disposable projection of
named database facts with one invalidation and cold-rebuild path. Durable
semantic state never lives only in an atom; duplicated registries are deleted.
Self-contained socket/subscriber/in-flight state may remain ephemeral when loss
on restart is explicitly harmless. The target does not force unrelated handles
into one monolithic atom, but it leaves no mutable cell unclassified.

## Explicit state transitions

### Fresh cluster creation

1. Validate a fresh-runtime request that explicitly selects current core and an
   initial canonical config input. An explicit `{}` is valid and compiles the
   schema-owned safe defaults; the ordinary CLI may instead explicitly supply
   `config/system.edn`. A fresh store never infers either from absence. A
   populated store needs no external config because its canonical config floor
   is already database data.
2. Open the empty store, set the one runtime connection, and perform/verify the
   un-attributed genesis transaction.
3. Compile one deterministic core program/schema snapshot, grouped by source
   file, and the selected canonical config maps from defaults, manifest, and env.
4. Query current canonical schemas, overlay the core desired schema set, build
   the complete candidate registry/native signatures, and validate every form,
   program snapshot invariant, and config map before any post-genesis write.
5. Compile exact core/native/program and config deltas from that validated data.
6. Commit the ordered nonempty native/core/program delta as root/boot.
7. Atomically install the already validated Malli registry/catalog projection.
8. Commit the combined nonempty config delta as root/config.
9. Load persisted safe namespace/function/test declarations, instrument once,
   install global services/listeners once, recover fenced runs, and resume
   eligible agents.

The root identity created by genesis is completed through steps 6–8. Core and
config exact transitions are independently restart-safe through their frozen
operation intent; after completion the database facts are sufficient.

Step 6 also ensures the stable human identity row before any interactive route
accepts a write.

### Cold restart of a populated cluster

1. Reopen the durable database; do not reassert its complete native schema.
2. Read the branch-qualified attachment descriptor. Rebuild a current core
   desired value only when this startup request explicitly supplies that
   overlay; compile config only when this request supplies a specific input.
   Otherwise preserve those target DB populations.
3. Overlay/query/parse the complete selected candidate and validate native
   signatures, snapshot invariants, schema deletion guards, and any config maps
   before a write.
4. Commit only selected nonempty root/boot and root/config deltas, then swap the
   validated registry/catalog. A converged or preserve-target restart advances
   no seed transaction and wakes no UI listener for seed work.
5. Rebuild analyzer/function/instrumentation/service state and recover/resume
   agents from durable run/FSM facts without replaying arbitrary evals.

### Config apply

1. Resolve the explicitly supplied input and compile/validate canonical desired
   maps.
2. Fsync one operation intent containing the immutable canonical payload,
   digest, target attachment coordinate, and expected head; do not retain a
   mutable path/env reference as the recovery input.
3. Query only each declared population and compile its exact delta.
4. Fail before transaction on invalid/mixed exclusive deletion.
5. Submit one fenced root/config transaction containing the combined nonempty
   delta, or verify convergence at the same head.
6. Read back the committed result, clear the intent atomically, and let normal
   database listeners recompile only affected runtime/UI projections. Recovery
   from any crash resumes the same frozen intent or proves it already committed.

It does not rebuild core source, reload all code, reinstrument globally, or
restart services.

### Core hot reload

Build one changed source snapshot and overlay its schema delta onto the complete
current candidate. Prebuild/prevalidate every Malli/native/program artifact
possible before any write, including the schema-deletion dependency guard. If
valid, commit the exact root/boot program/schema delta—the durable decision—then
perform a non-throwing registry/catalog pointer swap, load changed safe
namespace/function/test declarations, and run the one old/new dependency-aware
instrumentation delta over directly changed/spec-removed/deleted functions plus
schema dependents. A post-commit runtime-install failure closes readiness and
reconstructs from the committed database; it does not claim durable rollback or
keep a compatibility path. Function/test/namespace deletions are ordinary delta
retractions; schema deletion follows the migration guard. There is no
ghost-pruning pass.

### Agent mint

Allocate the identity through the sole writer and atomically transact every
initial durable fact with the actual submitting user and REPL process: agent,
purpose/defaults, complete context components, home-namespace row/require edges,
and canonical safe declaration facts whose identities are unique to that agent.
Shared schemas/default declarations such as `:my.agent/purpose` are established
once by their root/boot program contract and are never reasserted by a later
mint. Only after commit does Seon
establish the analyzer namespace, load those declarations, install the wake
trigger/runtime host, and return the identity. A crash before commit created
nothing; a crash after commit is an ordinary resume, never a half-bootstrap
repair. Mint does not seed/reconcile core or config, reload the global program
graph, instrument globally, or install services.

### Agent resume

Pull and validate the existing agent, reconstruct its transient host and wake
trigger, and continue from durable run/FSM facts. It does not mint or overwrite
initial state. Crash-recovery facts, when needed, are root/boot writes.

### Supervisor crash recovery matrix

The external cluster supervisor runs this derivation before admitting new work;
the in-pod root agent is a database user/capability holder, not the process that
survives its own pod. Recovery threads one current database value and applies
only the necessary CAS-fenced root/boot repairs:

| Durable facts | Recovery decision | Durable repair |
|---|---|---|
| `:seon.agent/terminated-at` present | Do not host or wake | None |
| No `:seon.agent/run` ref | Rebuild idle host + wake trigger | None |
| Ref points to a closed run | Rebuild idle host; clear stale pointer | Assert old→old, retract exact ref |
| Open run has `:seon.agent.run/paused-at` | Rebuild host/wake; remain paused | None |
| Open run exceeded deadline/work bound | Do not resume loop | Assert old→old, retract exact ref, close run with derived bound reason |
| Open run contains a stranded `:running` turn/eval but remains within its bounds | Never rerun its source/effects; continue the same run at the next turn | Assert old→old, mark only the interrupted turn/eval terminal error; retain the run pointer |
| Open run is within bounds and every prior turn is terminal | Rebuild host/wake and continue with the next turn | None |

Each destructive repair transaction uses this ordered fence shape (Datahike
cannot CAS a ref to `nil`):

```clojure
[:db.fn/cas agent-ref :seon.agent/run run-ref run-ref]
[:db/retract agent-ref :seon.agent/run run-ref]
;; run/turn closing facts follow in this same transaction
```

The old→old assertion leads, so a changed fence aborts the whole transaction.
Closing cases then retract the exact pointer and write close facts atomically;
an in-bounds interrupted turn instead becomes terminal while the same open run
and pointer remain current. Recovery never creates a replacement run, never
replays a prior turn, and never stores a resume checklist. After runtime rebuild,
the existing driver opens the next turn behind the retained CAS fence. A crash
before a repair commit leaves the same derivation for the next supervisor; a
crash after commit sees the repaired facts.

### Agent eval

Run in the agent's home namespace under `{user agent, process repl}`. Persist
the eval/result facts and any resulting domain/program declarations. Instrument
only new/redefined functions. Scratch effects execute once and are not made
reconstructable merely because their source/result is visible in the
transcript.

### Route or view-definition change

The committing transaction carries its ordinary user/process provenance. The
database listener compares the route/view-plan projection, recompiles the
Reitit router or affected view plan once, recaptures unit reads, and morphs only
changed targets. No separate route invalidation flags are stored.

## One schema-driven identity allocator

The current `<three letters>-<minute>` generator has only 140,608 choices per
minute, and its query-before-entity-map transaction can turn a collision into a
Datahike upsert. Creation and reconciliation are different operations: creation
must allocate a genuinely fresh identity; exact reconciliation intentionally
upserts a known lookup ref.

`seon.db.id` in one `.cljc` file owns the only allocation operation,
`allocate!`. Its fully specified request names one or more generated identity
attributes plus one pure transaction builder. Registered schema metadata
`:seon.db.id/generator` selects a private adapter; callers never select a
profile and there is no public candidate-only function.

- `:seon.agent/id` alone uses
  `:seon.db.id.generator/human-readable`: npm `human-id` in CLJS and
  `human-readable-ids-java` on the JVM. Humans discuss agents, so recognition is
  useful here. The literal `root` is reserved and reconciled, not generated.
- Every other generated persistent identity attribute uses
  `:seon.db.id.generator/compact`: npm `@paralleldrive/cuid2` in CLJS and
  `io.github.thibaultmeyer:cuid` on the JVM. This includes runs, turns,
  messages, evals, schedules, plans, and future high-volume identities.
- Stable config/program identities and external protocol ids are known domain
  facts, not alternate generated-ID paths.

Both runtime adapters expose the same Seon contract but need not emit the same
package grammar. Existing legacy ids remain valid and are never rewritten.
Creation time is projected through the identity assertion's transaction and
`:db/txInstant`; it is not duplicated into the id.

The allocator submits the complete candidate-dependent domain transaction
through the normal wire writer using concrete fresh eids. Datahike therefore
reports an existing identity as structured `:transact/unique` rather than
upserting. Only an error whose attribute and value exactly match that attempt's
generated manifest is retryable. Each retry discards and rebuilds the entire
transaction—agent home namespace, function/schema identities, lookup refs,
source forms, and response included. Sixteen failed candidate rounds return a
namespaced exhaustion error; there is no fallback grammar or unbounded loop.
The write id preserves commit ambiguity resolution, and ids are returned only
after commit plus the normal read-your-own-write fence.

The shared `:seon.db/id` value schema accepts reserved, exact legacy, word, and
compact syntax. Generator policy belongs to each registered identity attribute,
not to an entity and not to a caller option. Values stay lowercase URL/DOM/
CLJS-namespace safe; the schema does not copy package word lists.

Compact syntax also has a direct runtime obligation: every eval id becomes the
name portion of the real ClojureScript symbol `result/<eval-id>`. The reader
rejects a digit-leading name, and ClojureScript munges hyphen to underscore.
Therefore the compact profile is exactly `[a-z][a-z0-9]{11}`—letter first, no
slash, colon, hyphen, underscore, whitespace, or reader punctuation. Eval ids do
not get a separate generator; `:seon.eval/id` registers this compact profile
with the same allocator.

Datahike enforces uniqueness per fully namespaced identity attribute, while
Seon's serialized allocator additionally guarantees that a newly generated
value is unused under every registered generator-managed identity attribute in
the same logical database/branch. The writer performs indexed AVET lookups over
that derived attribute set immediately before the concrete-eid transaction and
also rejects duplicate candidates within one allocation request. A matching hit
is the same retryable generated-candidate conflict; no global identity table or
duplicate universal-id datom is stored. Independent branches may diverge and
generate the same later value; their full database coordinates distinguish
them.

## Graduation criteria

The design is implemented only when live proof shows:

- every post-genesis production transaction has resolvable user/process refs;
- no retained metadata field lacks a named provenance/transport consumer;
- config restores its exact subset after injected partial/corrupt state and is a
  no-op when converged;
- native schema reopens without full reinstallation;
- Malli restoration is complete, forward-reference-safe, and atomic on failure;
- a warm agent mint runs no cluster lifecycle work and remains below the agreed
  latency bound;
- deleted/renamed core and omitted config facts disappear through exact
  reconciliation, with no pruner/healer;
- arbitrary eval effects never run during restart or inspection;
- `as-of` simulation is immutable, writable forks diverge in isolation, and a
  quiesced live restore/undo survives injected supervisor crashes;
- unrelated transactions invoke zero renderers/SCI work;
- equivalent tabs share one correct subscription after either socket closes;
- debug/roster/agent/data pages use one bounded reactive feed path;
- the grown-store CPU/RSS profile scales with dirty unique units rather than
  sockets times whole views; and
- every retained mutable cell is either irreducible runtime state or a proven
  rebuildable projection of database facts; and
- agent-word and compact allocation pass collision/retry/exhaustion, whole-
  transaction rollback, namespace, URL, legacy, and token-cost proofs.
