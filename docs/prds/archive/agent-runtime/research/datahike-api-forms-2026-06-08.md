---
type: research
status: active
tags: [research, database, agent]
---

# Datahike API forms — authoritative positional signatures (2026-06-08)

## TL;DR

The real datahike public API is declared once in
`reference-code/datahike/src/datahike/api/specification.cljc`
(the `api-specification` map) and emitted into `datahike.api` via the
`emit-api` macro in `datahike/src/datahike/api.cljc`. Every `defn` you
call as `datahike.api/q`, `.../transact`, `.../pull`, etc. has its
arglist + impl declared there. The malli `:args` schemas in that file
ARE the canonical signatures — quoted below with file:line.

Canonical positional signatures Seon should mirror (db/conn ALWAYS
explicit — no ambient-conn shorthand):

| Op | Canonical positional datahike form | seon.db wrapper today |
| --- | --- | --- |
| query | `(d/q query db & inputs)` — db is the first `:in $` input | `query` map-in |
| transact | `(d/transact! conn tx-data)` → Promise (CLJS) / `(d/transact conn tx-data)` → report (JVM) | `transact!` map-in |
| pull | `(d/pull db selector eid)` | `pull` map-in |
| entity | `(d/entity db eid)` | `entity` map-in |
| listen | `(d/listen conn key callback)` | `listen!` map-in |

**Single biggest gotcha:** datahike's `transact`/`transact!` take
`(conn arg-map)` where the SECOND positional arg is the **whole tx
payload** — either a bare tx-data vector OR a `{:tx-data … :tx-meta …}`
map. There is NO `(transact conn tx-data tx-meta)` 3-arity. `tx-meta`
only travels via the map form. And in **CLJS the sync `transact` throws**
— only `transact!` (returns a Promise in the pod's build) works. An
agent trained on Datomic will reach for `(d/transact conn tx-data)` and
get a thrown "Synchronous transact not supported in ClojureScript"
unless the wrapper routes to `transact!`.

---

## How the API is defined (read this first)

- `datahike/src/datahike/api/specification.cljc` L80-749 — `api-specification`,
  the declarative map of every public op: `:args` (malli fn schema),
  `:ret`, `:doc`, `:impl`, `:examples`.
- `datahike/src/datahike/api.cljc` L28-41 — `emit-api` macro `def`s each
  op pointing `:arglists` at `(malli-schema->argslist args)` and the
  fn value at `:impl`.
- `datahike/src/datahike/api/impl.cljc` — the backwards-compat /
  arg-normalization shims (`transact!`, `transact`, `datoms`, `with`,
  `index-range`, temporal ops, versioning). **This is where the
  positional-vs-map dispatch actually lives.**
- `datahike/src/datahike/api/types.cljc` — the `:datahike/S*` malli
  schemas referenced by `:args` (e.g. `SQueryArgs`, `SPullOptions`,
  `STransactionReport`).

Because `:arglists` is codegen'd from the malli `:cat` schema, the
printed arglist is generic (`[arg0 arg1]`) — confirmed live on JVM:
`(:arglists (meta #'d/q))` → `([arg0] [arg0 arg1])`. The malli `:args`
in the spec is the real source of arg shape, not the printed arglist.

Datahike version probed: commit `717a0d2` (2026-05-17), Clojure 1.12.4,
malli 0.20.1.

---

## 1. Query — `datahike.api/q`

### Real arglist (specification.cljc L277-296)

```clojure
q
{:args [:function
        [:=> [:cat :datahike/SQueryArgs] :any]                       ; map form
        [:=> [:cat [:or [:vector :any] :map :string] [:* :any]] :any]] ; positional form
 :impl datahike.query/q}
```

Impl (`datahike/src/datahike/query.cljc` L105):

```clojure
(defn q [query & inputs] (raw-q (normalize-q-input query inputs)))
```

### Two variants — both real, both supported

**A. Positional (Datomic/DataScript style):** `(q query & inputs)`.
The query is `'[:find … :in $ ?x … :where …]` (vector OR `'{:find …}`
map OR an EDN string). **The db is passed as the first input**, i.e. it
binds the implicit `$` (or the first `:in` source). Extra `:in`
bindings bind positionally to the following inputs.

```clojure
;; db binds $, "Alice" binds ?target
(d/q '[:find ?n :in $ ?target :where [?e :name ?n] [(= ?n ?target)]]
     @conn "Alice")
;; => #{["Alice"]}   (verified live, JVM + CLJS pod)
```

If `:in` is omitted, datahike defaults to `[$]`, so `(d/q '[:find ?n
:where [_ :name ?n]] @conn)` works (db → `$`).

**B. Arg-map (pagination / explicit args):** `(q {:query … :args [...]
:limit … :offset … :order-by … :stats?})`. Here the db(s) live INSIDE
`:args`, in `:in` order. `normalize-q-input` (query.cljc L85-103)
detects the map by presence of `:query`; if a map with `:args` is
passed AND extra positional inputs are also given, the positional
inputs are **ignored with a warning**.

```clojure
(d/q {:query '[:find ?n :where [?e :name ?n]]
      :args  [@conn]
      :limit 1})
;; => #{["Alice"]}   (verified live)
```

Extra map keys hoisted out of the query by `normalize-q-input`:
`:offset :limit :order-by :stats? :settings :cancel`.

### Pull-in-query

`:find` can carry a pull expr, e.g.
`'[:find (pull ?e [:db/id :name]) :where [?e :name]]` — no API change,
pull pattern is inline.

### Datahike vs DataScript/Datomic

- Datahike's `q` **does** accept the map form (`{:query … :args …}`) —
  same as modern Datomic Client `q`, unlike classic DataScript.
- Multiple dbs: pass several `:in $ $2 …` sources, supply each db in
  input order. Valid and the reason Seon forbids ambient-conn shorthand.

### Seon mapping

`seon.db/query` (db.cljs L1234-1246) is map-in:
`{::db/query q ::db/args [...] ::db/db <db-val> ::db/conn <conn>}` →
`(apply d/q query db args)` where `db` is `(or ::db/db @(resolve-conn
conn))`. **Note Seon prepends the db itself** — so `::db/args` is the
*extra* inputs only (the `:in` bindings AFTER `$`), NOT the db.

Positional → seon map for an added positional form:

```
(query q db & inputs)  ==>  {::db/query q ::db/db db ::db/args (vec inputs)}
;; OR pass ::db/conn instead of ::db/db and let resolve-conn deref it,
;; but the positional form should take db/conn explicitly per the
;; no-ambient-conn constraint.
```

---

## 2. Transact — `datahike.api/transact` and `transact!`

### Real arglists (specification.cljc L200-230)

```clojure
transact
{:args [:=> [:cat :datahike/SConnection :datahike/STransactions]
        :datahike/STransactionReport]
 :impl datahike.api.impl/transact}

transact!
{:args [:=> [:cat :datahike/SConnection :datahike/STransactions] :any]
 :categories [:transaction :write :async]
 :impl datahike.api.impl/transact!}
```

Yes — datahike has a `!` variant, and it is the async one (returns a
future on JVM, a Promise in the pod's CLJS build). Both are **2-arity:
`(conn arg)`**. There is no 3-arity.

### How tx-data + tx-meta are passed (impl.cljc L29-47)

```clojure
(defn transact! [connection arg-map]
  (let [arg (cond
              (map? arg-map)  (if (contains? arg-map :tx-data) arg-map
                                  (raise "missing key :tx-data"))
              (or (vector? arg-map) (seq? arg-map)) {:tx-data arg-map}
              :else (raise "expected map, vector or sequence"))]
    (dw/transact! connection arg)))

(defn transact [connection arg-map]
  #?(:clj  @(transact! connection arg-map)
     :cljs (throw (ex-info "Synchronous transact not supported in
                            ClojureScript, use transact! instead." …))))
```

So the second arg is EITHER:

- a bare tx-data collection (vector/seq) — wrapped to `{:tx-data …}`, or
- a map `{:tx-data [...] :tx-meta {...}}` — passed through. **`tx-meta`
  rides in this map; there is no positional tx-meta arg.**

```clojure
;; bare tx-data
(d/transact conn [{:db/id -1 :name "Ivan" :likes ["fries" "pizza"]}])
(d/transact conn [[:db/add 1 :name "Ivan"]])
(d/transact conn [[:db/retract 1 :name "Ivan"]])

;; with tx-meta (the ONLY way to attach metadata)
(d/transact conn {:tx-data [{:db/id -1 :name "Ivan"}]
                  :tx-meta {:source :import}})

;; async (JVM future / CLJS Promise)
@(d/transact! conn [{:db/id -1 :name "Alice"}])   ; JVM deref
(.then (d/transact! conn [...]) (fn [rep] …))      ; CLJS Promise
```

### Return value — the tx-report (types.cljc L75-89, verified live both runtimes)

```clojure
{:db-before <DB>
 :db-after  <DB>
 :tx-data   [#datahike/Datom[e a v tx added] …]   ; sequence of datoms
 :tempids   {-1 1, -2 2, :db/current-tx 536870913}
 :tx-meta   …}                                     ; optional, the map you passed
```

Live JVM and CLJS pod both returned keys
`[:db-before :db-after :tx-data :tempids :tx-meta]` and tempids
`{-1 1, -2 2, :db/current-tx 536870913}`.

### Datahike vs Datomic/DataScript — gotchas

- **Datomic** `transact` returns a deref'able future of the report;
  **DataScript** `transact!` mutates a conn atom and returns the report
  directly. Datahike splits: `transact` = sync-and-return-report (JVM
  only), `transact!` = async future/Promise. An agent writing
  `(d/transact! conn tx)` on JVM gets a **future** they must `@`-deref
  to see the report — easy to forget.
- **CLJS: `transact` throws.** Only `transact!` works, and it returns a
  Promise in the pod build (verified: `(type txret)` → native
  `Promise`). The spec's impl uses `go`/`<!`, but the pod's
  datahike-cljs build returns Promises end-to-end (`create-database`,
  `connect`, `transact!` all return Promises — verified live; awaiting
  them with core.async `<!` parks forever, the wstd-timer hang noted in
  memory). Use `.then`/native `^:async/await`.
- **No 3-arity.** `(d/transact conn tx-data tx-meta)` is NOT a thing —
  Datomic-trained agents may try it. tx-meta goes in the arg-map.

### Seon mapping

`seon.db/transact!` (db.cljs L878-960) is `^:async`, map-in:
`{::db/tx-data [...] ::db/opts {:tx-meta {...}} ::db/conn <conn>}`. It
builds `arg-map = (merge {:tx-data tx-data} merged-opts)` and calls
`(await (d/transact! c arg-map))` (db.cljs L946-947). It returns an
**envelope**, not the raw report: `{::db/ok? true ::db/tx-report
<report>}` or `{::db/ok? false ::db/error <map>}`.

Positional → seon map for an added positional form:

```
(transact! conn tx-data)            ==> {::db/conn conn ::db/tx-data tx-data}
(transact! conn tx-data tx-meta)    ==> {::db/conn conn ::db/tx-data tx-data
                                         ::db/opts {:tx-meta tx-meta}}
```

A positional wrapper should still return Seon's envelope (or document
that it unwraps), and remain `^:async` since the underlying call awaits
a Promise.

---

## 3. Pull — `datahike.api/pull` and `pull-many`

### Real arglists (specification.cljc L327-355; impl pull_api.cljc L316-325)

```clojure
pull
{:args [:function
        [:=> [:cat :datahike/SDB :datahike/SPullOptions] [:maybe :map]]      ; arg-map
        [:=> [:cat :datahike/SDB [:vector :any] :datahike/SEId] [:maybe :map]]] ; positional
 :impl datahike.pull-api/pull}

pull-many
{:args [:function
        [:=> [:cat :datahike/SDB :datahike/SPullOptions] [:sequential :map]]
        [:=> [:cat :datahike/SDB [:vector :any] :datahike/SEId] [:sequential :map]]]
 :impl datahike.pull-api/pull-many}
```

```clojure
(defn pull
  ([db {:keys [selector eid]}] (pull db selector eid))  ; arg-map form
  ([db selector eid] …))                                ; positional, DB-FIRST
(defn pull-many [db selector eids] …)
```

**DB-first, confirmed.** `SPullOptions` = `{:selector [vector] :eid eid}`
(types.cljc L95-99).

```clojure
(d/pull @conn [:db/id :name :likes {:friends [:db/id :name]}] 1)
;; => {:db/id 1, :name "Alice"}     (verified live)
(d/pull @conn {:selector [:db/id :name] :eid 1})           ; arg-map variant
(d/pull-many @conn [:name] [1 2 3])                         ; verified: [{:name "Alice"}]
```

`eid` may be an entity id, a lookup ref `[:attr value]`, or a keyword
(SEId, types.cljc L51-61).

### Seon mapping

`seon.db/pull` (db.cljs L1248-1254) map-in:
`{::db/pull-pattern selector ::db/ref eid ::db/db <db> ::db/conn <conn>}`
→ `(d/pull db pull-pattern ref)`. Note Seon names the selector
`::db/pull-pattern` and the eid `::db/ref`.

```
(pull db selector eid)  ==> {::db/db db ::db/pull-pattern selector ::db/ref eid}
```

(No `pull-many` wrapper exists in seon.db yet — adding one would map
`(pull-many db selector eids) ==> {::db/db db ::db/pull-pattern selector
::db/refs eids}`.)

---

## 4. Entity — `datahike.api/entity` (+ `entity-db`, `touch`)

### Real arglist (specification.cljc L357-383; impl entity.cljc L17, L205, L217)

```clojure
entity
{:args [:=> [:cat :datahike/SDB [:or :datahike/SEId :any]] :any]
 :impl datahike.impl.entity/entity}

entity-db
{:args [:=> [:cat :any] :datahike/SDB]
 :impl datahike.impl.entity/entity-db}
```

```clojure
(defn entity [db eid] …)             ; DB-FIRST
(defn entity-db [^Entity entity] …)  ; the db the entity came from
(defn touch [^Entity e] …)           ; force-realize all attrs (NOT in api spec; entity.cljc L205)
```

```clojure
(d/entity @conn 1)                          ; lazy map-like Entity
(:name (d/entity @conn 1))                  ; => "Alice"  (verified live)
(d/entity @conn [:email "alice@example.com"]) ; lookup-ref eid
(d/entity-db e)                             ; db the entity was created from
```

`touch` is a `datahike.impl.entity` fn (not re-exported in
`api-specification`); reach it as `datahike.impl.entity/touch` or just
deref the keys you need.

### Seon mapping

`seon.db/entity` (db.cljs L1256-1262) map-in:
`{::db/ref eid ::db/db <db> ::db/conn <conn>}` → `(d/entity db ref)`.

```
(entity db eid)  ==> {::db/db db ::db/ref eid}
```

---

## 5. Listen — `datahike.api/listen` / `unlisten`

### Real arglists (specification.cljc L643-669; impl core.cljc L206-224)

```clojure
listen
{:args [:function
        [:=> [:cat :datahike/SConnection :any] :any]       ; (conn callback)
        [:=> [:cat :datahike/SConnection :any :any] :any]] ; (conn key callback)
 :impl datahike.core/listen!}      ; NOTE: api name `listen`, impl `listen!`

unlisten
{:args [:=> [:cat :datahike/SConnection :any] :map]
 :impl datahike.core/unlisten!}
```

```clojure
(defn listen!
  ([conn callback]     (listen! conn (rand) callback))   ; auto-keyed
  ([conn key callback] (swap! (:listeners (meta conn)) assoc key callback) key))
(defn unlisten! [conn key] …)
```

**Public API name is `listen` / `unlisten`** (the `!` is dropped in the
api ns — `(emit-api)` defs `datahike.api/listen`), but the impls are
`datahike.core/listen!` / `unlisten!`. The 2-arity auto-generates a key
(`(rand)`); the 3-arity takes an explicit opaque `key`. Same key
replaces the prior callback (idempotent). Returns the key.

### Callback shape

The callback is `(fn [tx-report] …)` — it receives the SAME tx-report
map as `transact` returns:
`{:db-before :db-after :tx-data :tempids :tx-meta}`. Fired on every
`transact!` against the conn.

**WARNING (from the spec doc):** inside the callback use only async
writers (`transact!`, `merge-db!`); synchronous `transact` deadlocks the
writer.

```clojure
(d/listen conn :my-listener
          (fn [tx-report] (println "tx:" (:tx-data tx-report))))
(d/unlisten conn :my-listener)
```

### Seon mapping

`seon.db/listen!` (db.cljs L1310-1359) map-in:
`{::db/handler f ::db/key k ::db/conn <conn>}`. It wraps the handler:
the user fn receives a RICHER map (not the raw report) —
`{::db/tx-report <raw> ::db/db <db-after> ::db/db-before <db-before>
::db/datoms [{::db/e ::db/a ::db/v ::db/tx ::db/added?} …]
::db/attr-index {attr [datoms…]}}` (build-handler-input, db.cljs
L1297-1308). Handler sync-throws and rejected Promises are swallowed
(safe-by-default). Returns `{::db/key k}`.

```
(listen! conn handler)        ==> {::db/conn conn ::db/handler handler}
(listen! conn key handler)    ==> {::db/conn conn ::db/key key ::db/handler handler}
(unlisten! conn key)          ==> {::db/conn conn ::db/key key}
```

Caveat for a positional form: Seon's handler gets the enriched map, not
the raw datahike tx-report. A positional wrapper should keep that
contract (or expose `::db/tx-report` for the escape hatch) so behavior
is identical to the map form.

---

## 6. DB access + time-travel

### `db` / deref-of-conn (specification.cljc L170-182; impl.cljc L127)

```clojure
db {:args [:=> [:cat :datahike/SConnection] :datahike/SDB] :impl datahike.api.impl/db}
(defn db [conn] @conn)   ; literally deref. Prefer @conn directly.
```

`@conn` and `(d/db conn)` are equivalent; the spec doc itself says
"Prefer using @conn directly." A db value is what every read op
(`q`/`pull`/`entity`/`datoms`/temporal) takes.

### `as-of` / `since` / `history` (specification.cljc L467-505; impl.cljc L130-148)

```clojure
as-of   {:args [:=> [:cat :datahike/SDB :datahike/time-point?] :datahike/SDB]}
since   {:args [:=> [:cat :datahike/SDB :datahike/time-point?] :datahike/SDB]}
history {:args [:=> [:cat :datahike/SDB] :any]}
```

All **DB-first**. `time-point?` = a tx-id int OR a `java.util.Date`
(types.cljc L142-146). Only work on `:keep-history? true` dbs
(impl raises otherwise).

```clojure
(d/as-of @conn 536870913)                 ; db state at that tx
(d/as-of @conn (java.util.Date.))
(d/since @conn some-date)                  ; only datoms added since
(d/history @conn)                          ; full assertion/retraction log
(d/q '[:find ?n ?a :where [?e :name ?n] [?e :age ?a]] (d/history @conn))
```

### `datoms` / `seek-datoms` (specification.cljc L389-419; impl.cljc L75-107)

`datoms` is a `defmulti` dispatching on the 2nd arg's type — supports
BOTH an arg-map and positional index+components:

```clojure
;; positional: (datoms db index & components)
(d/datoms @conn :eavt 1)            ; all datoms for entity 1
(d/datoms @conn :eavt 1 :likes)     ; entity 1, attr :likes
(d/datoms @conn :avet :likes "pizza") ; requires :db/index on :likes
;; arg-map: (datoms db {:index … :components […]})
(d/datoms @conn {:index :eavt :components [1]})
```

Verified live: `(d/datoms @conn :eavt e1)` → `[[1 :name "Alice"]]`.
Indexes: `:eavt :aevt :avet`. `seek-datoms` is identical but returns
datoms from the components through end-of-index (range-start semantics).

### `index-range` (specification.cljc L421-433; impl.cljc L150-151)

```clojure
index-range {:args [:=> [:cat :datahike/SDB :datahike/SIndexRangeArgs] :datahike/SDatoms]}
(defn index-range [db {:keys [attrid start end]}] (dbi/index-range db attrid start end))
```

**arg-map only** (no positional variant): `(d/index-range db {:attrid
:age :start 18 :end 60})`. Over the `:avet` index.

### Seon mapping

seon.db has no wrappers for `as-of`/`since`/`history`/`datoms`/
`index-range` yet. If added, all are DB-first read ops and should follow
the `query`/`pull` pattern — accept `::db/db` directly or
`::db/conn` + deref, db explicit:

```
(as-of db time-point)        ==> {::db/db db ::db/time-point tp}
(datoms db index & comps)    ==> {::db/db db ::db/index idx ::db/components (vec comps)}
(index-range db attrid s e)  ==> {::db/db db ::db/attrid a ::db/start s ::db/end e}
```

---

## 7. Conn / db lifecycle (brief — not the focus)

From specification.cljc L94-194 / impl.cljc L50-128:

- `(d/create-database config)` — `:args` `[:cat :datahike/SConfig]`,
  1-arity also exists. Config map needs `:store {:backend … :id …}`.
  **`:id` must be a UUID** (string throws — verified both runtimes:
  "Store :id must be a UUID type"). Backend keyword is **`:memory`**,
  not `:mem` (verified: `:mem` → "Unsupported store backend").
  Returns the config (JVM: deref'd; CLJS: Promise).
- `(d/connect config)` — also `(connect config opts)` and `(connect)`.
  Returns an `SConnection` (CLJS: Promise of one).
- `(d/database-exists? config)` / `(d/delete-database config)` /
  `(d/release conn)`.
- CLJS lifecycle fns (`create-database`, `connect`, `transact!`,
  `database-exists?`) return **Promises** in the pod build — verified
  live; `(type (d/create-database cfg))` → native `Promise`. Await with
  `.then` / native `^:async/await`, NOT core.async `<!` (parks
  forever).

---

## CLJS (datahike-cljs / pod) vs JVM — differences that matter

| Concern | JVM | CLJS pod (verified live) |
| --- | --- | --- |
| `transact` (sync) | returns report (impl `@(transact! …)`) | **throws** — use `transact!` |
| `transact!` return | future (deref with `@`) | **Promise** (`.then` / await) |
| `create-database`/`connect` | value (deref'd internally) | **Promise** |
| Read ops (`q`/`pull`/`entity`/`datoms`/temporal) | sync over db value | **sync** once you hold `@conn` — no Promise |
| `explain` | available | `:impl … :cljs nil` (spec L325) — not in CLJS |
| tx-report shape | `[:db-before :db-after :tx-data :tempids :tx-meta]` | identical |
| tempids | `{-1 1, … :db/current-tx N}` | identical |

The asymmetry to design around: **writes are async (Promise) in the pod;
reads are sync.** Seon's `transact!` is `^:async` and `await`s the
Promise; `query`/`pull`/`entity` are plain sync fns over `@conn`. A
positional layer must preserve that split — positional `transact!` stays
`^:async`, positional reads stay sync.

---

## Translation table — positional → seon.db map-in (summary)

| Positional datahike call | seon.db map-in equivalent |
| --- | --- |
| `(q query db & inputs)` | `{::db/query query ::db/db db ::db/args (vec inputs)}` |
| `(transact! conn tx-data)` | `{::db/conn conn ::db/tx-data tx-data}` |
| `(transact! conn tx-data tx-meta)` | `{::db/conn conn ::db/tx-data tx-data ::db/opts {:tx-meta tx-meta}}` |
| `(pull db selector eid)` | `{::db/db db ::db/pull-pattern selector ::db/ref eid}` |
| `(pull-many db selector eids)` | (new) `{::db/db db ::db/pull-pattern selector ::db/refs eids}` |
| `(entity db eid)` | `{::db/db db ::db/ref eid}` |
| `(listen! conn key handler)` | `{::db/conn conn ::db/key key ::db/handler handler}` |
| `(unlisten! conn key)` | `{::db/conn conn ::db/key key}` |
| `(as-of db tp)` / `(since db tp)` | (new) `{::db/db db ::db/time-point tp}` |
| `(datoms db index & comps)` | (new) `{::db/db db ::db/index index ::db/components (vec comps)}` |

Key naming already chosen by seon.db: selector = `::db/pull-pattern`,
eid = `::db/ref`, query inputs (post-db) = `::db/args`, tx-meta nested
under `::db/opts {:tx-meta …}`. Reuse these exact keys for the
positional shims so the two front doors funnel to one back door.

---

## Source citations (all under `reference-code/datahike/src/`)

- `datahike/api/specification.cljc` — `api-specification` L80-749 (every
  op's `:args`/`:impl`/`:examples`); `q` L277-296; `transact`/`transact!`
  L200-230; `pull`/`pull-many` L327-355; `entity` L357-371; `listen`/
  `unlisten` L643-669; temporal L467-505; `datoms`/`seek-datoms`
  L389-419; `index-range` L421-433; `db` L170-182; lifecycle L94-194.
- `datahike/api/impl.cljc` — `transact!`/`transact` L29-47 (the
  tx-data/tx-meta normalization + CLJS-throws); `datoms` defmulti
  L75-107; `with` L109-122; `index-range` L150-151; temporal L130-148;
  `db` L127.
- `datahike/api/types.cljc` — `STransactionReport` L75-89; `SQueryArgs`
  L101-107; `SPullOptions` L95-99; `SEId` L51-61; `time-point?`
  L142-146; `SIndexLookupArgs` L115-119.
- `datahike/query.cljc` — `q` L105, `normalize-q-input` L85-103.
- `datahike/pull_api.cljc` — `pull` L316-321, `pull-many` L323-325.
- `datahike/impl/entity.cljc` — `entity` L17, `touch` L205,
  `entity-db` L217.
- `datahike/core.cljc` — `listen!` L206-217, `unlisten!` L219-224,
  `tempid` L230-241.
- `datahike/api.cljc` — `emit-api` macro L28-41.

Seon wrapper source: `src/seon/db.cljs` — `transact!` L878-960,
`query` L1234-1246, `pull` L1248-1254, `entity` L1256-1262, `listen!`
L1310-1359, handler-input builder L1297-1308.
