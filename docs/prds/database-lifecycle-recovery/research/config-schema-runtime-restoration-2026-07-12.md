---
type: research
status: completed
tags: [research, database, schema]
---

# Config, schema, and runtime restoration audit

> **Supersession note (2026-07-12):** The config/schema/genesis findings remain
> authoritative. Recommendations below to use Seon's physical `fork-database`
> path for writable historical work are superseded by
> [[datahike-as-of-fork-and-restore-2026-07-12]]: same-store `branch!` is the one
> writable simulation path, the pinned historical-secondary bug is fixed in
> Datahike, and live restore uses guarded `force-branch!` under the external
> supervisor.

## TL;DR

The database already persists the expensive state that Datahike needs to reopen:
its native attribute schema, transaction history, and persistent index roots.
Cold boot should not reinstall every known attribute, copy the database into a
second registry-shaped store, or replay arbitrary evals. It should reconnect,
restore the small process-local runtimes from durable program facts, calculate
exact deltas for code-derived and config-derived facts, and transact only
nonempty deltas.

The recommended target has three deliberately separate layers:

- Datahike's native attribute declarations are durable storage facts. Install a
  declaration only when a registered attribute is absent. Never try to reconcile
  incompatible value-type or cardinality changes in place.
- Each Malli schema is one canonical, untruncated schema-form fact keyed by
  `:seon.schema/key`. Rebuild the process-local Malli registry in one validated
  bulk operation from compiled core forms plus database-authored forms. Do not
  persist or restore Malli registry objects or the CLJS analyzer state.
- Config is a pure projection from defaults, manifest EDN, and environment into
  canonical desired entity maps. An exact-delta compiler compares those maps
  with the current managed population and emits additions, replacements,
  omitted-attribute retractions, cardinality-many set differences, component
  replacement, and stale-entity retractions. A converged apply emits no
  transaction.

The provenance model should reflect the user's clarification: root and agents
are database users; boot, config, REPL, and similar labels are processes attached
to transaction metadata. `:seon.db/user` is a ref to either the root user's
identity or the agent's existing identity. Agents do not need a second
`:seon.db.user/id`.

There is one unavoidable bootstrap base case. Datahike expands transaction
metadata before processing transaction data, so the new `:seon.db/user` and
`:seon.db/process` attributes must already be installed before a tagged
transaction can use them. Seed their attribute declarations and the initial root
and process identities in one explicitly un-attributed genesis transaction,
with schema declarations ordered before entity maps. Every later transaction can
carry user and process refs. Although a ref-valued transaction-metadata tempid can
technically point at an entity created later in the same transaction once the
metadata schema exists, relying on that subtle ordering makes genesis appear to
author itself and is not the recommended contract.

The current entity-schema decomposition is a materialized projection used only
by the renderer. It is incomplete after cold replay and its cardinality-many
facts accumulate. The simplest correct design is to remove it from storage and
derive the renderer catalog from the canonical schema forms after bulk registry
restoration. If profiling later proves that catalog derivation is expensive, it
can be reintroduced explicitly as an exact-reconciled materialized view.

## Scope and settled input

This audit covers config compilation and application, native Datahike schema
installation, Malli registry restoration, program-schema persistence, bootstrap
ordering, and database time travel. It does not redesign agent eval replay or
promise to recreate arbitrary runtime side effects.

The following product decisions are treated as settled input:

- Database users are root, human users, and agents. An agent's existing
  `:seon.agent/id` is its user identity through the shared ref shape.
- Boot, config, REPL, and similar labels describe the process performing a
  transaction, not distinct users.
- Provenance is transaction metadata, not attributes copied onto domain
  entities.
- Store durable facts, not descriptions of the procedure that computed them.
- The database is the durable source of truth. Process-local Malli and CLJS state
  may be reconstructed, but arbitrary eval side effects are never replayed.
- Config is a recovery input and override surface. The database is what runtime
  readers consume after startup.

## What the code does today

### Connection and native schema

`open-cluster-conn!` reconnects to the shared store and then transacts the entire
`pod-full-schema` on every start (`src/seon/client.cljs:689-716`). That schema is
assembled from the hand-maintained `agent-bootstrap-attrs` vector plus seven
legacy transaction-metadata attributes (`src/seon/client.cljs:425-670`). The
vector currently duplicates ownership information already present in the Malli
registry and grows whenever a new feature adds an attribute.

This full startup write is not required for an existing database. Datahike
serializes its schema and identity/ref maps alongside the index roots and restores
them in `stored->db` (`reference-code/datahike/src/datahike/writing.cljc:226-287`).
Seon's ordinary write path already has the more appropriate behavior:
`ensure-datahike-attrs!` compares transaction attributes with the connected
database's installed schema and transacts only missing declarations
(`src/seon/db/internal.cljs:1232-1358`).

There is one important hole in that lazy installer. `transact!*` extracts only
attributes present in `:tx-data`, then merges ambient transaction metadata later
(`src/seon/db/internal.cljs:1406-1458`). Therefore a newly introduced
transaction-metadata attribute is not automatically installed before Datahike
attempts to flush it.

### Config and desired state

`resolve-config-singleton` is already close to the right compilation boundary:
it turns defaults, manifest values, and environment-derived defaults into one
flat, typed entity map (`src/seon/config.cljs:616-678`). Routes and skills are
compiled separately, then all three populations are concatenated into a single
desired vector during `boot-seed!` (`src/seon/client.cljs:2512-2540`).

The current generic `seon.state/reconcile!` does not calculate an exact delta. It:

- requires one identity attribute per desired map;
- scans historical origin metadata to discover managed entities;
- submits every desired entity map again whether it changed or not;
- retracts entities whose identities disappeared; and
- leaves attributes that were omitted from a surviving entity untouched.

The implementation is at `src/seon/state.cljs:47-115`. Because omitted
attributes survive, config has a second special-purpose
`stale-singleton-retractions` pass and transaction
(`src/seon/config.cljs:680-701`, `src/seon/client.cljs:2547-2561`). That helper is
not a config requirement; it is evidence that the shared reconciler does not
implement exact desired-state semantics.

### Malli and program-schema restoration

`schema/register!` immediately checks that a schema compiles against the current
global registry, mutates `*schemas`, and then invokes an asynchronous durability
tee (`src/seon/schema.cljc:227-268`). Agent-authored schema rows store a replayable
`(seon.schema/register! ...)` call, while boot-indexed rows store a printed Malli
form. `registration-call-source?` distinguishes those two meanings by checking
whether the stored string starts with `(` (`src/seon/eval.cljs:687-699`).

The same `:seon.schema/source` attribute therefore means two different things:

- canonical-ish schema shape for compiled registrations; and
- executable registration source for agent-authored registrations.

Boot-indexed shapes are truncated at 1,000 characters
(`src/seon/client.cljs:1824-1863`), so they cannot be a correctness source for
large schemas. Agent registrations are restored as members of reconstructed
namespace source and recompiled through the self-hosted evaluator
(`src/seon/eval.cljs:748-807`). This entangles a data restoration problem with
CLJS source ordering and tee-suppression controls.

Malli already provides the primitives for a cleaner restoration boundary:
`fast-registry`, `composite-registry`, and mutable registries live in
`reference-code/malli/src/malli/registry.cljc:17-104`, and `m/schema` accepts a
registry option in `reference-code/malli/src/malli/core.cljc:2551`. A complete
candidate map can therefore resolve forward references without mutating the
process-global registry one form at a time.

### Entity-schema decomposition

At boot, every entity-marked Malli map is decomposed into database attributes for
identity, required fields, and render functions
(`src/seon/schema.cljc:316-384`). `index-schemas` separately upserts the schema
key, source, creation time, and namespace. The two writers merge through
`:seon.schema/key`.

Only `src/seon/render.cljs:258-294` queries the decomposition. Live inspection of
the default store on 2026-07-12 found 1,159 schema rows but only 18 with
`:seon.schema/id-attr`; all current schema-row facts were written by the current
core-seed path. Thirty-six source values were call-shaped registration strings,
and none of those rows had an id-attr decomposition.

That incomplete state follows directly from startup ordering: the boot
decomposition is built before replay, while `schema-tee-row` writes only key,
source, created-at, and namespace (`src/seon/eval.cljs:1883-1904`). An
agent-authored entity schema restored during replay cannot receive decomposition
until another heavyweight boot-like path happens in the same warm process. The
cardinality-many `:seon.schema/required-attrs` assertions also only accumulate;
removed required keys are not retracted.

## What Datahike and Konserve actually provide

### Durable reopen is already structural

Datahike writes persistent index roots, schema metadata, max transaction and
entity ids, and commit metadata. `stored->db` restores those values and attaches
the store to the persistent index structures. The resulting database reads
deeper nodes lazily through Konserve; reconnect does not require rebuilding every
application entity in memory.

Konserve is the pluggable key/value and serialization layer beneath that
representation. Changing its backend, cache, or serializer does not make a live
Malli registry or a `cljs.js` analyzer state a safe durable value. Those objects
contain process-specific functions, caches, and side effects. Serializing them
would create a second, opaque source of truth that must be versioned with the
runtime.

The right use of the storage plugin boundary is to let Datahike own durable index
and schema restoration. Seon should rebuild only the small runtimes that are
functions of database facts and compiled code.

### Transaction order matters for genesis

Datahike's `transact-tx-data` calls `flush-tx-meta` before it loops over the
provided transaction data
(`reference-code/datahike/src/datahike/db/transaction.cljc:1104-1141`).
`flush-tx-meta` resolves each metadata attribute against `db-before` and throws
when the attribute is not already in the current schema
(`reference-code/datahike/src/datahike/db/transaction.cljc:802-821`).

Normal transaction data is ordered. A schema declaration processed earlier in
the transaction updates the transient database seen by later entity maps. This
is why Datahike's unstructured helper can emit schema declarations followed by
data in one transaction, and why an explicit genesis vector can do the same.
It does not help transaction metadata, which has already been expanded.

Datahike supports `:initial-tx`, but its API implements that shorthand by creating
the store, connecting, issuing one ordinary transaction, and releasing the
connection (`reference-code/datahike/src/datahike/api/impl.cljc:49-65`). The
source itself calls the shorthand something that “really should have been
avoided.” Seon's sole-writer startup should use an explicit, observable genesis
transaction rather than hide this special case inside database configuration.

### Time travel restores database state, not fired effects

With history enabled, `as-of` provides an immutable database view at a prior
transaction. Datahike branches are root pointers into structurally shared
persistent indexes; `branch!` copies the stored root and branch metadata rather
than copying all datoms (`reference-code/datahike/src/datahike/versioning.cljc:98-145`).
`fork-database` creates an independent store when isolation is required.

Those operations make database-state inspection and experimental migrations
cheap. They do not and cannot undo external effects already caused by evals,
network calls, filesystem writes, or launched processes. Restoring a database
point must never imply replaying those evals. Stored eval results from a previous
process may remain elided or explicitly unavailable in the transcript.

## Recommended target model

### Transaction facts

Keep transaction provenance minimal:

| Attribute | Shape | Meaning |
| --- | --- | --- |
| `:seon.db/user` | cardinality-one ref | Root, human, or agent whose operation caused the transaction |
| `:seon.db/process` | cardinality-one ref | Boot, config, or REPL execution path |

Do not persist eval id, replay flag, resume marker, origin class, or session id
unless a concrete query requires one. They can remain process-local execution
context. In particular, “all transactions caused by an eval” is not a current
requirement.

Root and agents should not be duplicated into a parallel actor table. The
`:seon.db/user` ref accepts lookup refs through the shared `:seon.db/ref` storage
shape. Root has one stable identity. An agent is referenced through
`[:seon.agent/id agent-id]`. The process entities are the small seeded list whose
presence is justified by actual operational queries: boot, config, and REPL.
Web is an adapter that selects the actual user/one of those paths, not another
process identity.

### The genesis exception

For a fresh store, or a one-time migration of an existing store, perform this
sequence in the sole writer:

1. Detect genesis by the absence of the root identity, not by a process-local
   boolean or a derived “initialized” datom.
2. Build one transaction vector containing, in order:
   - native attribute declarations for the user identity, process identity,
     `:seon.db/user`, and `:seon.db/process`;
   - the stable root entity; and
   - the minimal process identity entities.
3. Submit that transaction directly without custom Seon transaction metadata.
4. Read back the root and process lookup refs before enabling normal writers.
5. From that point onward, require user and process metadata for production
   writes where the caller context can provide them.

This is one documented un-attributed transaction, not an open-ended bypass. It
is the base case that creates the values later provenance refers to. It is more
honest than claiming root authored its own existence.

After genesis, fix `transact!*` so the schema installer sees the union of:

- attributes extracted from transaction data; and
- keys present in the final merged `:tx-meta` map.

Validate both sets against the Malli registry before committing. Pass the merged
metadata to later missing-schema installation transactions so schema facts are
attributed too; the genesis attributes themselves are the sole exception.
`assert-preconditions!` should verify that the minimal provenance attributes are
registered and installed, and that root/process lookup refs resolve. Merely
checking process-local Malli registration, as it does today, is insufficient.

Datahike can technically resolve a ref-valued metadata tempid that is created
later in the same transaction once its attribute schema is already installed:
the metadata operation allocates the tempid and the later entity map reuses it.
Do not use that as the public genesis contract. It requires a prior schema
transaction anyway, depends on internal transaction ordering, bypasses normal
transaction-metadata validation, and records a circular authorship claim.

### Config facts and exact desired-state compilation

Keep config compilation pure:

```clojure
(compile-desired-config defaults manifest env)
;; => [{:seon.config/id ...}
;;     {:seon.route/name ...}
;;     {:my.skills/name ...}]
```

Every top-level map must contain exactly one registered identity attribute. The
compiler returns canonical database values, not procedural `:remove` commands.
Absence from the desired set means stale entity; absence of a managed attribute
from a surviving map means retract that attribute.

The reusable reconciliation layer should accept an explicit population
descriptor, not infer universal ownership from every historical datom:

- identity attributes that enumerate the population;
- attributes config is authoritative for;
- whether population entities are exclusive and may be retracted as a whole;
- component attributes whose nested values are replaced as a unit; and
- user/process metadata for the resulting transaction.

Then calculate a pure delta from a database value and desired maps:

- new identity: transact the canonical entity map;
- cardinality-one difference: `:db/add` replaces the prior value;
- omitted managed cardinality-one value: value-less retract;
- cardinality-many difference: add desired-minus-current and retract
  current-minus-desired;
- changed component collection: use `:db.fn/retractAttribute` on the component
  attr so Datahike cascades the old component entities, then add the new nested
  component maps;
- missing desired identity in an exclusive population: retract entity;
- empty delta: do not call `transact!`.

This is not a universal diff over the entire database. Each state transition
states which facts it controls. Live inspection found no current or historical
mixed-writer facts on config singleton, route, skill, schema, function,
namespace, test, user, or shared-KB identities, so there is no evidence for a
more complicated per-attribute ownership framework today. Preserve a guard that
fails loudly if a supposedly exclusive stale entity contains current attributes
outside the population contract. Previous writer provenance does not define
config authority.

Population policy should start narrowly:

- config singleton: exact registered attrs, including agent/root context
  templates; never delete the identity entity;
- AI config singleton: exact config/env-derived attrs; never delete identity;
- routes: exact entity/component population, safe to retract stale entities only
  after the full-attr/component/incoming-ref guard;
- root agent: exact configured context component subtree and explicitly listed
  root defaults; preserve every other root fact;
- non-root agent context: copied from the template at mint and outside later
  config reconciliation;
- root/process identities: ensure-if-absent genesis facts, never exact-reconcile
  arbitrary later attributes;
- shared KB and all other agent facts: outside config reconciliation;
- core program graph: its own code-derived population and collision rules, not
  concatenated with config.

At startup, the resolved files and environment recover the managed config slice
to a known state. Between applies, runtime reads the database and live database
edits remain visible. A subsequent config apply intentionally reasserts the
compiled desired facts; this behavior should be confirmed with the user.

### Canonical Malli schema facts

Replace the overloaded source convention with one durable fact:

```clojure
{:seon.schema/key  :my.domain/item
 :seon.schema/form "[:map {:seon.db/entity true} ...]"
 :seon.schema/ns   [:seon.ns/name :my.domain]}
```

The form string must be full and untruncated. It is parsed as EDN data, never
evaluated as a registration call. Original eval source remains on the eval or
program member row where it belongs. A source hash may be derived when needed;
do not store it unless a measured query justifies the duplication.

Cold Malli restoration should be atomic at the process level:

1. Collect compiled core registrations into a plain map.
2. Query agent-authored schema-form facts from the database.
3. Parse every form as EDN and reject unreadable rows as explicit restoration
   errors.
4. Reject an agent row that collides with a protected compiled-core key unless
   an explicit override policy exists.
5. Build a complete candidate map before compiling any member.
6. Derive entity identity properties against that complete candidate map.
7. Create a temporary fast/composite Malli registry and call `m/schema` for
   every candidate form with that registry.
8. If any form fails, leave the live registry unchanged and report all invalid
   keys.
9. If all pass, replace/merge the live Seon registry once and relink Malli's
   default registry once.

This resolves forward references by construction and eliminates schema-specific
replay ordering, call-string classification, tee suppression during restore,
and partial registry state after a failure.

Core compiled registrations still run when modules load; that is not wasted
replay, it is how compiled code declares its current contract. What should stop
is recompiling database schema facts through the self-host evaluator merely to
rebuild an in-memory map.

### Native Datahike schema evolution

Malli schema facts and Datahike native schema declarations serve different
purposes:

- Malli forms express validation and semantic structure.
- Datahike declarations express durable value type, cardinality, uniqueness,
  component behavior, and reference storage.

Derive a native declaration from a registered attribute only when the ident is
absent. When the ident exists, compare the complete bridge-derived storage
signature—`:db/valueType`, `:db/cardinality`, `:db/unique`, and
`:db/isComponent`—with the stored declaration before accepting the canonical
Malli form. Compatible validator-only refinements may update the process-local
registry without touching Datahike. Any storage-signature divergence is an
explicit migration: register a new fully namespaced attribute, copy values,
verify, retract old facts when appropriate, and update readers. Do not rely on
which schema mutations a particular Datahike version happens to permit in place.

Do not put native Datahike schema into the config exact-reconciler. Schema
declarations are accumulating database capabilities and migration facts, not a
desired set to retract when a source file no longer mentions an attribute.

### Renderer schema catalog

Remove persisted `:seon.schema/id-attr`,
`:seon.schema/required-attrs`, `:seon.schema/render-fn`, and
`:seon.schema/render-html-fn` as the default design. After bulk Malli restoration,
derive a catalog map once per registry generation from canonical forms. The
renderer reads that process-local projection.

This follows the project's “derive, do not store” rule and fixes the current
replay-order hole. If profiling with the full registry demonstrates material CPU
cost, cache by registry generation. Only if that remains inadequate should the
catalog return as a database materialized view, in which case one exact
reconciliation must add and retract every projected field; it must not be a
second append-only boot seed.

## State transitions

### Fresh cluster creation

1. Create/open the Datahike store in the sole writer.
2. Query for the root identity.
3. If absent, issue the one un-attributed genesis transaction containing minimal
   schema declarations first and root/process entities second.
4. Verify lookup refs and native schema from the returned database value.
5. Attach pod readers and normal transaction context.
6. Restore Malli and the agent program layer from database facts.
7. Compile current core and config desired populations.
8. Calculate and commit only nonempty deltas, tagged as root plus boot or config
   process respectively.
9. Install listeners and start runtime services once.

### Cold restart of a populated cluster

1. Connect; do not transact the complete attribute schema.
2. Verify minimal provenance schema and identities.
3. Rebuild process-local Malli and safe program runtime state.
4. Compare current compiled core/config facts with database facts.
5. Skip converged populations without advancing the database basis.
6. Start services and resume/mint agents through separate lifecycle functions.

### Config reload or restart with changed inputs

1. Load and validate one manifest.
2. Read environment once into an explicit input map.
3. Compile canonical config, route, and skill desired maps.
4. Query only their registered identity and managed attributes.
5. Compile an exact delta.
6. If nonempty, transact atomically with root user and config process metadata.
7. Let ordinary Datahike listeners drive reactive consumers from changed
   attributes.

### Runtime schema registration

1. Parse/receive the schema form as data and validate the fully namespaced key.
2. Build and validate a candidate registry without mutating the live registry.
3. Transact the canonical schema-form fact with the agent as user and REPL as
   process.
4. Install a missing native Datahike declaration before the first data fact that
   uses the attribute; attribute the install transaction with the same user and
   process.
5. Commit the live registry replacement/relink.
6. On database failure, leave the live registry unchanged; on process failure
   after the database commit, the next cold restoration deterministically
   reconstructs it.

The exact order of steps 3 and 5 should use a small transactional API that makes
the database commit the durable decision. There should be no background tee
whose failure is deliberately swallowed after mutating the registry.

### Inspecting or testing an earlier database state

Use `as-of` for read-only inspection and render/debug views. Use a branch in the
same store for cheap database-only experiments when shared storage is acceptable.
Use `fork-database` for an isolated writable store. Never move the live branch
head as a casual “restore” operation, and never replay arbitrary eval source to
try to recreate non-database state.

## Code change and deletion map

### `src/seon/db.cljs` and `src/seon/db/internal.cljs`

- Replace the seven legacy metadata registrations and `tx-meta-attrs` set with
  the ratified user/process refs.
- Make `transact!*` validate and install metadata-key schema as well as
  transaction-data schema.
- Add an explicit genesis precondition/read-back surface; do not encode genesis
  as a hidden atom flag.
- Let post-genesis missing-schema installs carry the ambient transaction
  metadata.
- Replace origin-derived management queries with direct user/process transaction
  joins where those queries remain necessary.
- Keep the current incompatible native-schema divergence gate.

### `src/seon/client.cljs`

- Delete `agent-bootstrap-attrs` and `pod-full-schema` after the fresh-store and
  lazy-install experiments pass.
- Remove the unconditional full-schema transaction from
  `open-cluster-conn!`.
- Split cluster runtime boot from mint and resume as already proposed in the
  lifecycle audit.
- Build the core program snapshot once, exact-reconcile it once, and do not
  rebuild it for a second prune pass.
- Remove the separate entity-schema decomposition seed.
- Replace `index-schemas`' truncated source rows with canonical full form facts.
- Remove the config-heal transaction after exact reconciliation lands.

`open-agent-conn!` is a test helper and may still create an isolated store. It
should exercise the same genesis helper rather than a second full-schema list.

### `src/seon/state.cljs` and `src/seon/config.cljs`

- Replace `reconcile!`'s whole-origin scan and unconditional desired upserts with
  a pure exact-delta compiler plus a thin commit function.
- Express managed populations and attributes explicitly.
- Make omitted managed attributes retract normally.
- Delete `stale-singleton-retractions` and its tests.
- Compile route removal as absence from canonical desired maps and retire the
  procedural remove semantics in the same migration.
- Keep `resolve-config-singleton` as the single effective-value compiler, but
  pass defaults/manifest/env explicitly so it is pure and testable.

### `src/seon/schema.cljc`, `src/seon/eval.cljs`, and `src/seon/render.cljs`

- Add one bulk candidate-registry validation and atomic replacement operation.
- Stop using executable schema registration strings for restoration.
- Remove schema self-tee as a fire-and-forget durability path; schema
  registration should transact the form fact through one explicit API.
- Delete `registration-call-source?` and schema-member replay from
  `reconstitute-ns-source` once form restoration is live.
- Delete `entity-schema-tx-data`, `entity-schema-keys`, and
  `all-entity-schemas-tx-data` if the derived renderer catalog is accepted.
- Change the renderer to consume the derived catalog cached by registry
  generation.

### Tests to change without context-wording fixtures

Test transitions and invariants, not exact context prose:

- fresh genesis installs minimal schema and identities exactly once;
- a tagged first normal transaction succeeds because metadata attrs are already
  installed;
- converged cold restart emits no schema/core/config transaction;
- exact config reconciliation retracts omitted scalar, many, and component
  facts and skips an empty delta;
- a mixed-writer guard fails before deleting a supposedly exclusive entity;
- bulk Malli restore handles forward refs and leaves the live registry unchanged
  on any invalid form;
- an agent-authored entity schema is renderable immediately after cold restore;
- incompatible native Datahike schema changes fail with migration guidance;
- `as-of` changes the database view without executing program source.

## Experiments required before implementation

1. On a fresh strict-schema in-memory Datahike database, transact ordered native
   schema declarations and root/process entity maps in one untagged transaction;
   read all lookup refs back.
2. Prove that custom transaction metadata fails when its attribute appears only
   later in the same transaction data, matching `flush-tx-meta`'s `db-before`
   lookup.
3. After installing metadata schema, demonstrate the same-transaction metadata
   tempid behavior, then retain it only as a regression fact, not the production
   genesis API.
4. Modify a local branch of `transact!*` to install the union of tx-data and
   tx-meta keys; prove first-use user/process metadata succeeds and invalid
   metadata values fail at the Seon boundary.
5. Connect to a populated copy without the full `pod-full-schema` transaction;
   run boot, query, agent mint, canvas, and write paths to find any query that
   incorrectly assumes an unused attribute was eagerly installed.
6. Exercise a pure exact-delta compiler against cardinality-one,
   cardinality-many, ref, and component-ref shapes using `d/with` before any live
   commit.
7. Verify that an empty delta performs no `transact!` and does not advance
   `:max-tx`.
8. Build a temporary Malli registry containing mutually forward-referencing
   forms; validate all, atomically install, and prove rollback on one invalid
   form.
9. Cold-start a copied store containing agent schema forms with entity render
   properties; verify the derived renderer catalog before any agent mint.
10. Benchmark connect, Malli bulk restoration, catalog derivation, exact core
    diff, exact config diff, `as-of`, branch creation, and isolated fork on the
    grown store. Report tokens for human-visible size figures, not characters.

## Decisions after the audit

- Startup/apply restores the explicitly managed config subset to defaults +
  manifest + environment. Live database edits are visible until that boundary.
- Procedural route removal migrates to absence from the canonical desired set;
  no parallel compatibility path remains.
- Protected core schema collisions fail loudly and require an explicit
  migration; neither side silently overwrites the other.
- Durable transaction turn correlation is removed.
- Read-only `as-of`, isolated writable forks, and a quiesced live restore/undo
  lifecycle are all in this refactor. Their exact Datahike/Konserve mechanics are
  owned by the dedicated branch/restore source audit.

## Recommended implementation order

1. Ratify transaction user/process refs and implement the explicit genesis
   sequence plus metadata-key schema installation.
2. Add the pure exact-delta compiler and migrate config/routes/skills; delete the
   special singleton heal.
3. Introduce canonical full schema-form facts and bulk Malli restoration; prove
   equivalence in tests/REPL, then remove the executable replay path in the same
   phase rather than retaining two live implementations.
4. Move the renderer to the derived catalog and delete stored entity-schema
   decomposition.
5. Remove executable schema-source restoration and the asynchronous self-tee.
6. Remove the full boot schema list/transaction after the copied-store cold-boot
   experiment proves all first-use paths.
7. Consolidate core program snapshot/diff work and finish the process-boot versus
   mint/resume split.
8. Profile the grown store and add caching only where measurements demonstrate a
   real cost.

This order establishes honest provenance and exact writes first, then simplifies
runtime restoration, then deletes the legacy boot scaffolding. Each step has a
database read-back proof and can be committed independently without changing
context wording.
