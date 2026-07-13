# Datahike Internals

What's underneath `seon.db` — useful when debugging, reading
`reference-code/datahike/`, or interpreting an error message. Normal code uses
`seon.db` + `seon.schema`, never these APIs directly. The full source-grounded
mindset is `docs/prds/agent-fsm/research/datahike-primer.md` — read it.

## Where Datahike runs

The pod does not own a Datahike writer. It maintains a local immutable replica:

- **Writes** forward typed protocol maps over a Unix socket to
  `seon.db.server`, the sole JVM writer for the durable file-backed database
  (`data/clusters/default/db`). Pure-data tx-ops (`:db/add`,
  `:db/retract`, `:db.fn/cas`, `:db.fn/retractEntity`) serialize and cross fine;
  an inline `:db.fn/call` closure cannot cross the protocol (`datahike-primer.md`
  §2). That's why the work-fence is `:db.fn/cas`, not a tx-fn.
- **Reads** are local: `@*conn*` resolves a database value from shared immutable
  Konserve data with lazy LRU node fetch, so memory follows the working set. Two derefs at the same
  basis-t are equal-by-value but not identical objects.

Database configuration (`:keep-history? true`, `:schema-flexibility :write`,
`:attribute-refs? false`) is set by the database server when the database is created, not
in the pod. The pod asserts `:keep-history? true` as a boot precondition
(`db/assert-preconditions!`).

## EAV data model

Every fact is an `[entity-id attribute value tx added?]` datom. Entity ids are
auto-assigned positive longs. Tempids in a tx are negative ints or strings.
History is on, so retractions remain queryable via `db/history` (5-tuple
`:where` binds `tx` and the `added?` flag).

## Datahike schema is transacted as datoms — the bridge derives it

In datahike, schema is entity maps with `:db/ident`, `:db/valueType`,
`:db/cardinality`, optionally `:db/unique` / `:db/isComponent`. **You never
write these** — the Malli→datahike bridge (`src/seon/db/internal.cljs`,
`malli->datahike-attr`) derives them from `schema/register!`, and `transact!`
installs them lazily at an attr's first use. Illustrative output:

```clojure
[{:db/ident :my.ns/name   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one
  :db/unique :db.unique/identity}
 {:db/ident :my.ns/tags   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
 {:db/ident :my.ns/parent :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}]
```

Datahike requires `:db/cardinality` on every attr; the bridge supplies
`:db.cardinality/one` unless the Malli form is a `:vector`/`:set`/`:sequential`
container (→ `:db.cardinality/many`).

## Value types

| Malli type | datahike `:db/valueType` | Notes |
|---|---|---|
| `:string` | `:db.type/string` | |
| `:int` | `:db.type/long` | |
| `:double` / `:float` | `:db.type/double` / `:db.type/float` | |
| `:boolean` | `:db.type/boolean` | |
| `:inst` | `:db.type/instant` | `java.util.Date` / `js/Date` |
| `:uuid` | `:db.type/uuid` | |
| `:keyword` | `:db.type/keyword` | `[:enum …keywords]` maps here too |
| `:symbol` | `:db.type/symbol` | |
| `:seon.db/ref` | `:db.type/ref` | entity reference |

A mixed-type `:or` (the render slots) is stored as `:db.type/string` carrying
pr-str'd EDN — `transact!` encodes, `db/decode-edn-value` is the read-side
inverse (`malli->datahike-attr` `:or` branch). A `:db.secondary/only` vector of
floats becomes a `:db.type/tuple` in the secondary (HNSW) index — the fork's
secondary-index support, used by `:seon/embedding`.

## Schema properties

| Property | Values | Purpose |
|---|---|---|
| `:db/valueType` | see above | attr value type |
| `:db/cardinality` | `:db.cardinality/one` \| `…/many` | single vs multi (required) |
| `:db/unique` | `:db.unique/identity` \| `:db.unique/value` | identity enables UPSERT + lookup-refs |
| `:db/isComponent` | `true` | child cascade-retracts with parent |

From the seon side you never set these directly — `{:seon.db/identity true}` →
`:db.unique/identity`, `{:seon.db/component true}` → `:db/isComponent true`.

## Transaction op forms (all pure data)

```clojure
{:my.ns/name "Dave" :my.ns/score 40}                ; map: create / upsert by identity
[:db/add eid :my.ns/score 50]                       ; add one datom
[:db/retract eid :my.ns/score 50]                   ; retract a specific value
[:db.fn/retractAttribute eid :my.ns/score]          ; retract all values of an attr
[:db.fn/retractEntity eid]                           ; retract whole entity (components cascade)
[:db.fn/cas eid :my.ns/v old new]                    ; compare-and-swap (the work-fence)
```

CAS is `compare-and-swap` at `reference-code/datahike/src/datahike/db/
transaction.cljc:873`; on mismatch it raises `{:error :transact/cas, :old …,
:expected …, :new …}`, which surfaces as the `{:seon.db/ok? false …}` envelope.
`:db/current-tx` / `"datomic.tx"` resolve to the current tx entity
(`transaction.cljc:62`) — use as a `:db/id` to attach reified-tx provenance.

## A conn is an atom over a db value — reads take the VALUE

`(d/q query db)`, `(d/pull db sel eid)`, `(d/entity db ref)` take an immutable
db value, not a conn. In seon the conn (`seon.db/*conn*`) is bound once; `db/`
reads deref it for you. You never call `datahike.api` directly outside
`src/seon/db/` — if a primitive you need isn't surfaced in `seon.db`, ADD the
wrapper there (that's "porting the function" — keeps the one-API rule).

## History and as-of

```clojure
(db/query '[:find [?v ...] :where [?e :my.ns/name ?v]] (db/history))      ; all values ever
(db/query '[:find [?v ...] :where [?e :my.ns/name ?v]] (db/as-of t))      ; as it was at t
(db/query '[:find [?e ...] :where [?e :my.ns/done? true]] (db/since t))   ; only after t
```

`t` is a tx-id (int), a Date, or a txInstant. `db/basis-t` is the latest tx a db
value reflects (the "now" end); `db/origin-t` is datahike's origin tx (the empty
pre-seed floor). **Gotcha:** `as-of`/`since`/filtered values report their
*origin* db's `max-tx`, NOT the as-of point (`db.cljc:493`) — don't key a cache
on basis-t alone across db shapes (`datahike-primer.md` §4).

## How datahike differs from Datomic

- **Embedded in the database-server process**, with the pod reading shared
  immutable Konserve data.
- **History is configurable** (`:keep-history? true` here) — retractions stay
  queryable.
- **`:db/txInstant`** exists on every tx entity.
- **Schema-on-write** (`:schema-flexibility :write`) — attrs must be declared
  before use (which the bridge + lazy install handle).
- **Our fork adds secondary indexes** (including Proximum HNSW for embedding
  KNN), reached through the database protocol's `knn-search` operation.

## Where to read in the fork (don't reverse-engineer)

| Topic | File |
|---|---|
| db value: `DB`/`FilteredDB`/`AsOfDB`/`SinceDB`/`HistoricalDB`, `-max-tx`, `equiv-db`, hashing | `reference-code/datahike/src/datahike/db.cljc` |
| `:db.fn/cas`, `:db.fn/call`, `:db.fn/retractEntity`, tx expansion, `:db/current-tx` | `…/db/transaction.cljc` |
| public surface: `with`, `as-of`, `since`, `history`, `tx-range` | `…/api/specification.cljc` |
| pull (incl. reverse-ref expansion) | `…/pull_api.cljc` |
| The Seon seam | `src/seon/db.cljs`, `src/seon/db/internal.cljs`, `src/seon/db/replica.cljs`, `src/seon/db/protocol.cljc`, `src/seon/db/transport/uds.{cljs,clj}`, `src/seon/db/writer.clj`, `src/seon/db/server.clj` |
