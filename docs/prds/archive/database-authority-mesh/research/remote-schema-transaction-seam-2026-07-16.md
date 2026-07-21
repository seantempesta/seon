---
type: research
status: complete
tags: [research, prd, database, schema, flow]
---

# Remote schema transaction seam — 2026-07-16

## Decision

Do not add a schema-install protocol operation. Datahike already accepts an
attribute declaration and facts using that attribute in one ordinary atomic
transaction. Seon's existing transaction request already provides durable
request identity, generated-candidate recovery, transaction metadata, and an
optional expected coordinate. A separate operation would add a round trip, a
second commit, and a race between "schema installed" and "data committed"
without adding authority.

Move the Malli-to-Datahike derivation to the JVM transaction-preparation owner
and compose its declarations into the same Datahike transaction as the facts.
The strongest seam is the accepted `:seon.schema/key` + `:seon.schema/form`
transaction: derive and install its Datahike declaration atomically with that
canonical schema fact. Then ordinary first use has no special path. Retain a
writer-side lazy fallback for an already-admitted but not-yet-installed schema
while old databases converge; the fallback prepends the declaration to the
same domain transaction, never commits it separately.

The Bun child may validate values against its active Malli projection for fast
feedback, but it does not send trusted `:db/valueType` declarations and does
not retain a Datahike schema view. The database's canonical
`:seon.schema/form` facts and the installed Datahike schema are the authority.

## Dependency ledger

| Owner | Selected source | Relevant fact |
|---|---|---|
| Seon transaction protocol | `src/seon/db/protocol.cljc` at `7e4f9afb`, protocol version 7 | `transaction-request` already carries transaction data, request ID, expected coordinate, metadata, and generated candidates; its durable hash includes all four semantic inputs. |
| Seon writer | `src/seon/db/writer.clj` at `7e4f9afb` | `prepare-transaction!` is the one serialized preparation boundary before `d/transact!`; it owns receipt recovery and generated-ID preparation. |
| Seon schema authority | `src/seon/schema.cljc` and `src/seon/eval.cljs` at `7e4f9afb` | A successful schema declaration is persisted as the canonical `:seon.schema/key` + full EDN `:seon.schema/form` row in the accepted eval transaction. |
| Current CLJS bridge | `src/seon/db/internal.cljs` at `7e4f9afb` | `ensure-datahike-attrs!` derives missing declarations but commits a separate schema transaction before the data transaction; this entire connection-dependent path disappears with the replica. |
| Current JVM bridge | `src/seon/db/datahike/schema.clj` at `7e4f9afb` | Pure Malli-to-Datahike derivation already exists on the authority side, but it currently depends on the process-global Malli registry and is not yet parameterized by one database's canonical forms. |
| Generated identities | `src/seon/db/id.cljc` at `7e4f9afb` | `effective-schema` deliberately merges declarations found in incoming transaction data with the installed schema, so generated IDs, nested refs, and caller tempids already understand a schema-plus-data transaction. |
| Datahike | `reference-code/datahike` at `d21abadb9412f1b828b02ddb3c08ddc81d57c595` | `entity-map->op-vec` applies schema updates while interpreting one transaction; `writing/transact!` owns the serialized expected-basis fence. |

## Shortest executable probes

All probes used an isolated `:memory` database through `clojure -M:writer`; no
Seon lifecycle process was started.

One transaction containing two declarations followed by an entity using both
attributes committed successfully. Its string tempid resolved to eid `3`, and
the value was queryable immediately. Repeating the same declarations in a
later data transaction also succeeded. This confirms that declarations are
ordinary idempotent-upsert transaction data and do not need a pre-commit.

A transaction containing a new `:db.type/long` declaration followed by an
invalid string value failed, and the attribute remained absent afterward.
Schema plus data is therefore atomic: neither half lands on failure.

Datahike's direct serialized fence also rejects a transaction whose
`:datahike/expected-basis-t` no longer equals the writer's current `:max-tx`.
That is the exact primitive Seon must pass through, rather than checking only a
pre-dispatch snapshot.

The probes also found two blockers that must be fixed before this seam can be
trusted:

- `datahike.schema/find-invalid-schema-updates` can return `nil` for a real
  value-type change because its `reduce-kv` step returns `nil` whenever the
  current entry is unchanged or allowed. Later map entries can erase an
  earlier detected difference. A direct call changing string to long returned
  `nil`, and Datahike accepted the change. See
  [[../../../seon/issues/datahike-schema-update-reducer-loses-differences]].
- `seon.db.writer/prepare-transaction!` checks the expected coordinate against
  `d/db` before dispatch but does not pass `:datahike/expected-basis-t` into
  the transaction map. Its `locking` ends after asynchronous dispatch, before
  durable completion, so two concurrent requests can both pass the same
  pre-check. See
  [[../../../seon/issues/writer-expected-coordinate-is-not-a-serialized-fence]].

## Exact owner and algorithm

Keep the public API unchanged:

```clojure
(schema/register! ::name :string)
(await (db/transact! {::db/tx-data [{::name "Ada"}]}))

```

Strengthen `seon.db.writer/prepare-transaction!`, before generated-ID
preparation and the one `d/transact!` dispatch:

1. Capture the current immutable database value after durable receipt lookup.
2. Enforce the complete expected coordinate there for a helpful protocol
   error, and also pass its `::coordinate/t` as
   `:datahike/expected-basis-t` to the serialized Datahike writer. The latter
   is the atomic fence.
3. Inspect transaction data for accepted schema rows and collect domain
   attributes used by entity maps, nested ref maps, transaction tuples, and
   transaction metadata. Reuse the existing extraction semantics; do not add
   a second transaction language.
4. Build one pure per-database Malli registry from the current canonical
   `:seon.schema/key` / `:seon.schema/form` population plus schema rows in this
   same request. Derive only affected attribute declarations through one
   shared JVM bridge.
5. Compare every derived declaration with the installed schema. Equal means
   no added data. Missing means prepend the declaration. A changed stored
   shape fails as `:user-input` in this transaction, before either the schema
   fact or domain data commits. Initially compare the existing Seon contract:
   value type and cardinality, then deliberately settle uniqueness/component
   evolution instead of inheriting Datahike's current permissiveness by
   accident.
6. Run generated-candidate and caller-tempid preparation against the effective
   schema (installed plus the declarations to prepend). `seon.db.id` already
   implements this merge.
7. Dispatch exactly one Datahike transaction containing declarations, original
   transaction data, tempid receipt rows, transaction metadata, generated
   candidates, and the serialized basis fence.

The derived augmentation does not need a new wire field. The durable logical
request hash remains over the caller's semantic request. Exact redelivery
reaches the committed receipt before derivation and returns the original
result. A schema definition or domain-data change requires a new request ID
because the original transaction data or canonical schema-fact transaction is
different.

## Why schema admission is better than first-use installation

Installing at the accepted schema-fact transaction removes the transient state
where Malli says an attribute exists but Datahike does not. An incompatible
redefinition fails the declaration's own commit instead of changing the
runtime/database schema fact and failing only when some later write happens to
touch the attribute. It also moves bridge cost off the first domain write,
which is the latency-sensitive path the user actually intended.

The lazy fallback is still useful during migration and for old databases whose
schema facts predate this mechanism. It uses identical derivation and ordinary
transaction composition. Once convergence proof shows every canonical schema
fact has its matching installed declaration, delete the fallback rather than
retain two permanent install paths.

## Concurrency, idempotency, and generated IDs

- Different databases retain independent Datahike writers and can install
  schemas concurrently.
- One database's schema admission and domain writes remain ordered by its one
  Datahike writer. No process-global schema registry is mutated; database A's
  agent-authored forms cannot leak into database B.
- Two equivalent schema admissions without a coordinate fence serialize and
  converge to one installed shape. Each logical transaction may still have its
  own transaction receipt, as expected.
- Two conflicting admissions from one coordinate must not both win. Passing
  the basis fence into Datahike makes exactly one commit; the other gets the
  ordinary stale-coordinate response and recomputes from the new head.
- Exact request redelivery is recovered by the existing durable receipt and
  does not create another schema transaction.
- Schema declarations and ordinary string/numeric tempids already compose in
  one Datahike transaction. Generated identity preparation already reads the
  effective schema from declarations in transaction data. A newly declared
  generated identity should be admitted first; allocation then uses the
  committed generator-policy fact. There is no value in combining schema
  design and the first generated domain allocation into one remote request.

## Alternatives and tradeoffs

### Bun derives and sends `:db/*` declarations

This is the smallest mechanical port of `ensure-datahike-attrs!`, but it keeps
the duplicate CLJS bridge, trusts a client storage declaration, copies schema
data over the socket, and leaves per-database canonical forms separate from
the commit decision. Use only as a temporary implementation scaffold, never as
the graduated owner.

### JVM derives lazily on every first domain write

This is correct when composed into the domain transaction and is a good
migration fallback. Its downside is Malli registry construction and bridge
work on first-use latency, plus a longer window where canonical schema and
installed schema differ.

### Dedicated schema protocol operation

This has the worst trace: schema request, schema commit, changed coordinate,
then data request and data commit. It needs its own idempotency and stale-head
rules while exposing a partial-success state. It is materially weaker than
ordinary transaction composition and should not be added.

## Deletion implications

After the writer mechanism graduates:

- delete `seon.db.internal/ensure-datahike-attrs!` and its local connection,
  schema-read, schema-install, and divergence-query code;
- delete the production `seon.db/malli->datahike-schema` / client bootstrap
  use after isolated diagnostic fixtures have moved to the shared pure JVM
  helper or an explicit test fixture;
- collapse the CLJS and CLJ Malli-to-Datahike bridges into one pure owner; do
  not preserve two type mappings;
- remove `agent-bootstrap-attrs` as an independently maintained storage-schema
  inventory once the canonical schema facts drive cold database creation;
- retain `seon.schema/register!`, canonical schema facts, and runtime Malli
  projection publication—the change removes Datahike ownership from Bun, not
  schema design from agents; and
- retain the one transaction operation, receipt mechanism, generated-ID
  manifest, and ordinary Datahike tempids unchanged.

## Acceptance proof

1. A schema-fact admission installs its matching Datahike declaration in that
   same commit. The later first domain write uses the ordinary transaction
   path without a schema request or schema commit. During old-database
   convergence, the lazy fallback composes declaration and domain facts in its
   one request/response and one Datahike commit.
2. Failure in either the declaration or data leaves neither installed schema,
   canonical schema fact, domain fact, nor durable success receipt.
3. Compatible re-admission produces no schema diff; incompatible value type,
   cardinality, uniqueness, and component changes fail deterministically with
   ordinary `:user-input` data before runtime publication.
4. Two concurrent requests fenced at the same coordinate yield exactly one
   commit and one stale-coordinate response; unfenced equivalent declarations
   converge without corruption.
5. Exact redelivery recovers the same coordinate, tempids, and generated entity
   IDs without another commit.
6. Schema-plus-data works with nested refs, cardinality-many refs, caller
   tempids, and generated-candidate preparation.
7. Two databases may admit unrelated schemas concurrently without sharing a
   mutable Malli registry or blocking each other's writer.
8. A cold existing database converges through the temporary lazy fallback;
   after convergence the fallback is removed and no Bun process contains a
   Datahike connection, installed-schema cache, or schema installer.
