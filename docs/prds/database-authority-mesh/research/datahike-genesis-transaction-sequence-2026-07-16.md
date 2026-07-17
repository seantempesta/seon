---
type: research
status: complete
tags: [database, research, schema]
---

# Datahike genesis transaction sequence

## Decision

Use two application transactions for a fresh database:

1. one un-attributed genesis transaction installs only the native attributes
   needed to identify root and the database processes, the two transaction
   metadata ref attributes, root's identity stub, and the stable process
   identities; then
2. one root/boot-attributed transaction installs every other missing compatible
   native attribute first, followed by the exact compiled program and required
   initial-data delta.

Datahike cannot express this as one provenance-attributed transaction when the
metadata uses lookup refs to root and boot. This is a transaction engine ordering
constraint, not a Seon protocol limitation. No connection, session, child, query,
or application behavior is admitted between the two commits, so database ensure
remains the one externally atomic admission boundary.

Do not broaden genesis merely to make later diff code convenient. Native
attributes that describe the program or initial domain data can be installed at
the front of the second transaction and receive the same root/boot provenance as
the facts they enable.

## Dependency ledger

| Dependency or mechanism | Selected source | Relevant behavior |
|---|---|---|
| Seon | `422800ed9f03aaaff02390470b41c71fb016355a` plus current shared writer work | `:seon.db/user`, `:seon.db/process`, root identity, and stable process identities |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | transaction metadata expansion, intermediate database values, schema-on-write, lookup refs |
| Seon backend | `src/seon/db/backend.clj:122-126` | history is enabled and schema flexibility is `:write` |
| Process identities | `src/seon/db/process.cljc:11-36` | `:seon.db.process/id` is identity and genesis supplies boot, config, and REPL entities |
| Provenance refs | `src/seon/db.cljs:40-41` | transaction user and process are ordinary refs |

## Why one transaction fails

### Transaction metadata cannot use schema installed in its own transaction

Datahike builds the transaction metadata operations before processing any user
transaction data. `flush-tx-meta` translates each metadata attribute against
`db-before`; an absent attribute raises `:transact/schema`
(`reference-code/datahike/src/datahike/db/transaction.cljc:822-841`). The main
transaction loop calls that function before making the database transient
(`transaction.cljc:1130-1153`).

Therefore an ordered transaction such as this still fails:

```clojure
{:tx-data [;; schema for :seon.db/user and :seon.db/process
           ;; root and boot entities
           ;; program and initial facts
           ]
 :tx-meta {:seon.db/user [:seon.agent/id "root"]
           :seon.db/process
           [:seon.db.process/id :seon.db.process/boot]}}

```

The apparent order inside `:tx-data` is irrelevant to metadata-schema
resolution because metadata has already been expanded from `db-before`.

### Transaction metadata cannot lookup-ref entities created later in that transaction

With history enabled, Datahike prepends the expanded transaction metadata
operations to the caller's transaction data
(`transaction.cljc:1152-1157`). Ref values are resolved by `transact-add`
against the current intermediate database. Because the metadata additions are
first, lookup refs to root or boot fail before their entity maps are reached.

Datahike otherwise intentionally supports lookup refs against entities created
earlier in ordinary transaction data. Its maintained test adds an identity and
then resolves it as a ref in the next operation
(`reference-code/datahike/test/datahike/test/lookup_refs_test.cljc:53-56`). That
facility cannot help transaction metadata because Datahike, not the caller,
places metadata first.

## What can share the second transaction

Schema-on-write updates the transaction's intermediate database as schema datoms
are processed. `with-datom` immediately calls `update-schema` and
`update-rschema` for schema datoms
(`reference-code/datahike/src/datahike/db/transaction.cljc:372-398`), while the
transaction loop passes the resulting `db-after` into the next entity
(`transaction.cljc:1158-1194`).

Consequently the second transaction may safely contain, in order:

1. every missing compatible native attribute declaration other than the four
   genesis requirements;
2. compiled schema, namespace, function, and test entity changes; and
3. required initial domain entities.

All of those facts share one root/boot-attributed transaction. A conflicting
schema or invalid later fact aborts the entire transaction.

## Executable probe

The smallest probe used an in-memory Datahike database with Seon's production
settings, `{:schema-flexibility :write :keep-history? true}`. It separated the
two possible one-transaction failures and the two-transaction success:

```clojure
(def provenance-schema
  [{:db/ident :probe.user/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :probe.process/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :probe.tx/user
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :probe.tx/process
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}])

(def targets
  [{:probe.user/id "root"}
   {:probe.process/id :probe.process/boot}])

(def tx-meta
  {:probe.tx/user [:probe.user/id "root"]
   :probe.tx/process [:probe.process/id :probe.process/boot]})

```

Observed results from `clojure -M:writer`:

| Probe | Result |
|---|---|
| schema + targets + lookup-ref metadata in one transaction | `:transact/schema` for `:probe.tx/user` |
| previously installed schema; targets + lookup-ref metadata in one transaction | `:entity-id/missing` for `[:probe.user/id "root"]` |
| un-attributed schema + targets; then metadata transaction | success |
| second metadata transaction installs a new native string attribute immediately before an entity using it | success |

The successful second transaction advanced from the genesis database value and
returned both lookup refs unchanged in `:tx-meta`; their transaction datoms were
resolved to the pre-existing target entity ids.

## Exact genesis population

For current Seon semantics, the un-attributed genesis transaction needs only:

- native schema for `:seon.agent/id` as a unique identity;
- native schema for `:seon.db.process/id` as a unique identity;
- native ref schema for `:seon.db/user` and `:seon.db/process`;
- `{:seon.agent/id "root"}`; and
- the three maps returned by `seon.db.process/genesis-entities`.

The human `{:seon.user/id "user"}` is not a transaction-metadata target for the
boot commit and remains ordinary boot-attributed initial data. Root's complete
agent/home/config facts likewise remain outside genesis; root identity presence
must not be mistaken for completed root initialization.

If the existing database is partial, genesis derives and transacts only missing
compatible declarations and identities. If all genesis requirements exist, it
emits no transaction. The program transaction likewise emits no write when its
exact schema/program/initial-data delta is empty.

## Implementation implication

Keep one ensure operation and one writer serialization owner. Before compiling
the boot transaction, compute initial-data identity and absence from the
validated desired schema projection plus the current database, rather than
requiring every initial-data identity attribute to be preinstalled by genesis.
That preserves a minimal genesis without introducing another schema registry or
another reconciliation path.

Datahike's `:initial-tx` does not collapse the sequence. `create-database`
creates the physical database, reconnects, and then calls ordinary `transact`
for `:initial-tx` (`reference-code/datahike/src/datahike/api/impl.cljc:50-66`).
Using that shorthand would hide the same genesis transaction inside physical
creation, would not help already-existing partial databases, and would create a
second initialization mechanism. Use the authority's existing ensure and
transaction path for both fresh and resumed databases.

## Acceptance evidence

Focused writer tests should establish:

- one-transaction provenance bootstrap fails for the two source-grounded
  reasons above;
- fresh ensure produces exactly genesis then one root/boot program transaction;
- program-native schema and its first entity coexist in the boot transaction;
- no successful ensure response is visible between those transactions;
- converged ensure produces neither transaction;
- a partial genesis repairs only its missing requirements; and
- a failed boot transaction leaves genesis durable but the database unpublished,
  and the next ensure completes through the same idempotent path.
