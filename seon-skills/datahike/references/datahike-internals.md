# Datahike Internals

What's underneath `seon.db` — useful when debugging or interpreting an
error message. You always call `seon.db` + `seon.schema`, never a raw
datahike API directly.

## Where datahike actually runs (pod ≠ JVM)

The **pod does not embed datahike.** It is a follow-the-store replica:

- **Writes** forward over a Unix socket (Transit-JSON, values only) to the
  **wire-server** — the single JVM writer that owns the durable file-backed
  store (`data/clusters/default/store`). Pure-data tx-ops (`:db/add`,
  `:db/retract`, `:db.fn/cas`, `:db.fn/retractEntity`) serialize and cross fine;
  an inline `:db.fn/call` closure CANNOT cross the wire (it carries a JS
  closure, not data). That's why the work-fence is `:db.fn/cas`, not a tx-fn.
- **Reads** are local: `@*conn*` reconstitutes a fresh db VALUE from the store
  with lazy LRU node fetch, so memory ∝ working set. Two derefs at the same
  basis-t are equal-by-value but not identical objects.

Store configuration (`:keep-history? true`, `:schema-flexibility :write`,
`:attribute-refs? false`) is set wire-server-side when the store is created, not
in the pod. The pod asserts `:keep-history? true` as a boot precondition
(`db/assert-preconditions!`).

> An embedded-LMDB-in-process model exists elsewhere in this codebase on a
> paused track — that is NOT how your world works. Don't mistake it for
> yours if you ever see it mentioned.

## EAV data model

Every fact is an `[entity-id attribute value tx added?]` datom. Entity ids are
auto-assigned positive longs. Tempids in a tx are negative ints or strings.
History is on, so retractions remain queryable via `db/history` (5-tuple
`:where` binds `tx` and the `added?` flag).

## Datahike schema is transacted as datoms — the bridge derives it

In datahike, schema is entity maps with `:db/ident`, `:db/valueType`,
`:db/cardinality`, optionally `:db/unique` / `:db/isComponent`. **You never
write these** — the Malli→datahike bridge (`malli->datahike-attr`) derives
them from `schema/register!`, and `transact!` installs them lazily at an
attr's first use. Illustrative output:

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

CAS is `compare-and-swap` (a real datahike primitive, not a seon invention);
on mismatch it raises `{:error :transact/cas, :old …, :expected …, :new …}`,
which surfaces as the `{:seon.db/ok? false …}` envelope — proven live: a
matching `old` commits, a stale `old` aborts with exactly that error kind.
`:db/current-tx` / `"datomic.tx"` resolve to the current tx entity — use as
a `:db/id` to attach reified-tx provenance.

## A conn is an atom over a db value — reads take the VALUE

`(d/q query db)`, `(d/pull db sel eid)`, `(d/entity db ref)` take an immutable
db value, not a conn. In seon the conn (`seon.db/*conn*`) is bound once; `db/`
reads deref it for you. You always call through `seon.db`, never a raw
datahike API — if a primitive you need isn't surfaced there, that is a gap to
report, not something to route around.

## History and as-of

```clojure
(db/query '[:find [?v ...] :where [?e :my.ns/name ?v]] (db/history))      ; all values ever
(db/query '[:find [?v ...] :where [?e :my.ns/name ?v]] (db/as-of t))      ; as it was at t
(db/query '[:find [?e ...] :where [?e :my.ns/done? true]] (db/since t))   ; only after t
```

`t` is a tx-id (int), a Date, or a txInstant. `db/basis-t` is the latest tx a db
value reflects (the "now" end); `db/origin-t` is datahike's origin tx (the empty
pre-seed floor). **Gotcha, proven live:** `(db/basis-t (db/as-of older-t))`
returns the CURRENT db's max-tx, not `older-t` — `as-of`/`since`/filtered
values report their *origin* db's `basis-t`, not the as-of point. Don't key
a cache on basis-t alone across db shapes; if you need to know what point a
filtered db represents, keep the `t` you asked for, don't re-derive it from
`basis-t`.

## How datahike differs from Datomic

- **Embedded** (the wire-server store is file-backed via konserve), not
  client/server.
- **History is configurable** (`:keep-history? true` here) — retractions stay
  queryable.
- **`:db/txInstant`** exists on every tx entity.
- **Schema-on-write** (`:schema-flexibility :write`) — attrs must be declared
  before use (which the bridge + lazy install handle).
- **Our fork adds secondary indexes** (incl. Proximum HNSW for embedding KNN),
  reached via the wire-server's `knn-search` RPC.

## Live namespaces to check when in doubt

`seon.db`'s own docstrings are the reference for the pod-facing surface —
`(:doc (meta (resolve 'seon.db/pull)))` etc. Test the actual behavior in
your eval rather than guessing from this doc's prose.
