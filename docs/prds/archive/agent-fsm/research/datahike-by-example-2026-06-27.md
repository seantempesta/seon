---
type: research
status: active
tags: [research, database, reference]
---

# Datahike by example — a verified `seon.db` primer

Source-grounded catalog for the upcoming `my.kb` manual that replaces
`src/seon/db/examples.cljs`. Every semantic claim below is cited to the REAL
datahike library source or test in `reference-code/datahike/`; every example is
expressed in `seon.db` terms (the pod's sole DB API), with the underlying
datahike grammar named alongside. Read + verify only — no source was changed.

## TL;DR

- **The pod calls `seon.db`, never `datahike.api`.** `seon.db` (`.cljs`) forwards
  writes over a Unix socket to the JVM datahike writer and reads local lazy db
  values. Every public fn takes a **map-in/map-out** arity AND a **positional**
  arity that mirrors `datahike.api`. The `db`/`conn` auto-injects from the bound
  `seon.db/*conn*` when omitted.
- **Writes are `^:async` and return a COMPACT ENVELOPE, never a throw.** Success
  `{:seon.db/ok? true :seon.db/tempids {…} :seon.db/tx n :seon.db/tx-count n
  :seon.db/added n :seon.db/retracted n}`; failure `{:seon.db/ok? false
  :seon.db/error {…}}`. At the REPL top level the runtime auto-awaits; inside a
  fn you `(await …)`. **Always read `:seon.db/ok?`.**
- **Reads (`query`/`pull`/`entity`) are synchronous** over a db value.
- **There is NO `pull-by-name` in the pod's `seon.db`.** The lookup-ref
  `[identity-attr value]` IS the "by name" addressing mechanism — pass it to
  `pull`/`entity`. (The `pull-by-name` token in `CLAUDE.md` is stale; the manual
  must not reference it.)
- **VERDICT on `db/examples.cljs`: all 14 functions are IDIOMATIC.** Every
  pattern matches verified datahike semantics. No bugs found, no non-idiomatic
  Clojure. Details + per-fn citations at the end.
- **Schema bridge** (`seon.db.internal/malli->datahike-attr`,
  `db/internal.cljs:286-350`): `{:seon.db/identity true}` →
  `:db/unique :db.unique/identity`; `{:seon.db/component true}` →
  `:db/isComponent true`; `:seon.db/ref` → `:db.type/ref`; a container form
  (`:vector`/`:set`/`:sequential`) → `:db.cardinality/many`, a scalar → `one`.

---

## The `seon.db` surface — exact signatures

All map keys are `:seon.db/*`; below aliased as `::` for the `seon.db` namespace.
Source: `src/seon/db.cljs`.

### `transact!` (`^:async`, `db.cljs:422-514`)

```clojure
;; map-in / map-out (PREFERRED):
(db/transact! {::db/tx-data    [{::name "A"}]      ; required, a vector
               ::db/opts       {:tx-meta {…}}      ; optional
               ::db/conn       <conn>              ; optional, defaults *conn*
               ::db/return-report? true})          ; optional, attaches raw report
;; positional, mirroring (d/transact conn tx-data) — conn FIRST & explicit:
(db/transact! <conn> [{::name "A"}])
(db/transact! <conn> [{::name "A"}] {:source :import})   ; 3-arity tx-meta
;; bare-tx-data 1-arg — conn defaults to *conn* (internal/normalize-transact-args,
;; db/internal.cljs:912-913):
(db/transact! [{::name "A"}])
```

Returns the `::db/transact-response` envelope (schema `db.cljs:159-178`). Safe by
default — bad shape / unregistered attr / value-fails-schema / commit explosion
all come back as `{::db/ok? false …}`, never a throw (`db.cljs:508-514`,
`internal/commit-error-envelope`). `:seon.db/error`'s `:seon.error/data` carries
`:seon.error/kind` (`:user-input` vs `:core-bug`).

### `query` (sync, `db.cljs:524-610`)

```clojure
;; map-in:
(db/query {::db/query '[:find ?n :where [?e ::name ?n]]
           ::db/args  [...]            ; extra :in inputs after $
           ::db/db    <db> | ::db/conn <conn>})   ; default *conn*
;; positional, mirroring (d/q query db & inputs):
(db/query '[:find ?n :where [?e ::name ?n]] <db>)
(db/query '[:find ?n :in $ ?t :where …] <db> "Alice")
;; positional, db OMITTED — auto-injects from *conn*:
(db/query '[:find ?n :where [?e ::name ?n]])
(db/query '[:find ?n :in $ ?t :where …] "Alice")
```

The second positional arg is the **explicit db** only when it IS a db value
(`internal/db-value?`, `db/internal.cljs:861-869`); otherwise it is the first
`:in` input and the db auto-injects from `*conn*` (`db.cljs:602-610`). Guarded
against silent typos: a `:where` attr neither installed nor registered throws a
legible error rather than returning `#{}` (`db.cljs:747-777`).

### `pull` (sync, `db.cljs:864-912`)

```clojure
;; map-in:
(db/pull {::db/pull-pattern '[*] ::db/ref <eid-or-lookup-ref>})
;; positional, mirroring (d/pull db selector eid):
(db/pull <db> '[*] <ref>)
;; positional, db OMITTED — auto-injects from *conn*:
(db/pull '[*] <ref>)
```

`ref` is a raw eid OR a lookup-ref `[identity-attr value]` (value must be the
attr's STORED type). Returns the pulled map or `nil`. Guarded: a never-installed
explicit-pattern attr that IS registered is silently filtered (equivalent to
zero-rows); one that is NOT registered throws (`db.cljs:825-862`).

### `entity` (sync, `db.cljs:946-973`)

```clojure
;; map-in:
(db/entity {::db/ref [::name "Alpha"]})
;; positional, db OMITTED:
(db/entity <eid-or-lookup-ref>)
;; positional, explicit db:
(db/entity <db> <eid-or-lookup-ref>)
```

Returns a **plain TOUCHED map** — `:db/id` plus every attr (`touch->map`,
`db.cljs:935-944`), `nil` if the ref doesn't resolve. A ref attr reads back as
`{:db/id N}`; drill in with a follow-up `entity`/`pull`. (Contrast: raw datahike
`d/entity` returns a lazy Entity that navigates refs lazily —
`entity_test.cljc:57`; seon.db deliberately touches to a value.)

### `store-inventory` (sync, `db.cljs:1198-1295`)

```clojure
(db/store-inventory)                          ; post-bootstrap data rows only
(db/store-inventory {::db/system? true})      ; full, incl. core boot index
;; => [{::db/kind :my.kb.codebase
;;      ::db/attrs {:my.kb.codebase/question 3 :my.kb.codebase/answer 3}} …]
```

One row per attribute NAMESPACE (the "kind") that has ≥1 live row, with per-attr
entity counts. User-domain kinds first, core kinds after; alphabetical within
each. **Omits registered-but-dataless kinds** (it is existence + sparsity, not
the schema catalog — pair with `(keys (db/installed-schema @db/*conn*))` to see
zero-row registered kinds). Call it BEFORE registering a new kind.

### Temporal wrappers (sync, db-in/db-out)

`(db/history)` / `(db/history db)` (`db.cljs:987-1002`) — all-time db, read with a
5-tuple `[?e ?a ?v ?tx ?added]`. `(db/as-of t)` / `(db/as-of db t)`
(`db.cljs:1004-1016`) — db as it was at `t`. `(db/since t)` / `(db/since db t)`
(`db.cljs:1018-1029`) — only datoms added after `t`. `t` is a tx-id / Date /
txInstant. Pass the resulting db value positionally to `query`/`pull`/`entity`.

---

## Catalog — verified patterns

For each: a minimal correct `seon.db` example, the footgun, and a `file:line`
citation proving the semantics.

### SCHEMA → storage

`schema/register!` teaches the Malli registry only; **the FIRST `transact!` that
uses an attr installs its datahike schema lazily** (`db.cljs:612-655`
`installed-schema`, the lazy-install trap). The bridge derives storage from the
Malli form (`db/internal.cljs:286-350`):

```clojure
(schema/register! :my.kb.doc/id    [:string {:seon.db/identity true}]) ; natural key / upsert anchor
(schema/register! :my.kb.doc/title :string)                            ; plain scalar, cardinality/one
(schema/register! :my.kb.doc/tags  [:vector :keyword])                 ; cardinality/many
(schema/register! :my.kb.doc/author :seon.db/ref)                      ; plain ref → :db.type/ref
(schema/register! :my.kb.doc/notes  [:vector {:seon.db/component true} :seon.db/ref]) ; component ref (many)
```

- identity/natural-key → `:db/unique :db.unique/identity` — `db/internal.cljs:349`.
  A lookup-ref `[attr v]` is only legal on a `:db/unique` attr —
  `lookup_refs_test.cljc:26` (`[:age 10]` throws "Lookup ref attribute should be
  marked as :db/unique").
- cardinality-many from a container form — `db/internal.cljs:268-275`,
  `:db.cardinality/many` for `:vector`/`:set`/`:sequential`.
- plain ref `:seon.db/ref` → `:db.type/ref` — `db/internal.cljs:169-170,219-220`.
- component ref → `:db/isComponent true` (REQUIRES `:db.type/ref`, datahike
  throws otherwise — `components_test.cljc:16-19`) — `db/internal.cljs:350`.

### WRITES

**Add entity-map.** Always read the envelope:

```clojure
(let [{::db/keys [ok? error]}
      (db/transact! {::db/tx-data [{:my.kb.doc/id "d1" :my.kb.doc/title "Intro"}]})]
  (if ok? :saved error))
```

Footgun: an eval can succeed while the write did NOT happen (`ok? false`).

**Same-tx ref link via `:db/id` tempid.** The target doesn't exist yet → give it
a `:db/id` tempid (string or negative int) and put that SAME tempid in the ref
slot; datahike resolves both to one new entity:

```clojure
(db/transact! {::db/tx-data [{:db/id "p1" :my.kb.author/id "alice"}
                             {:my.kb.doc/id "d2" :my.kb.doc/author "p1"}]})
```

Footgun: a **lookup-ref does NOT resolve forward** to an entity that appears only
later in the tx / only as an unresolved tempid — use the shared tempid for
same-tx links. String tempids are valid — `upsert_test.cljc:43-53`. (Lookup-refs
DO resolve against entities asserted EARLIER in the same tx —
`lookup_refs_test.cljc:53-56` "resolved at intermediate DB value" — but tempid
linking is order-independent and is the robust idiom.)

**Link to an already-committed entity via lookup-ref** (never a bare value in a
ref slot):

```clojure
(db/transact! {::db/tx-data [{:my.kb.doc/id "d2"
                              :my.kb.doc/author [:my.kb.author/id "alice"]}]})
```

Proof: `[[:db/add 1 :friend [:name "Petr"]]]` → `:friend {:db/id 2}` —
`lookup_refs_test.cljc:41-48`. A lookup-ref to a nonexistent entity throws
"Nothing found for entity id" — `lookup_refs_test.cljc:86-91`.

**Inline component child** — built from the nested map, no tempid:

```clojure
(db/transact! {::db/tx-data [{:my.kb.doc/id "d1"
                              :my.kb.doc/notes [{:my.kb.note/id "n1"
                                                 :my.kb.note/body "…"}]}]})
```

Proof: `{:db/id 1 :comp [{:name "C"}]}` mints entity 2 as the component —
`explode_test.cljc:111-119`; nested-map explosion `explode_test.cljc:68-109`.

**Upsert by identity (absent = unchanged).** Re-transacting a map carrying an
existing identity value UPDATES in place — no duplicate, no tempid needed;
OMITTED keys are left unchanged:

```clojure
(db/transact! {::db/tx-data [{:my.kb.doc/id "d1" :my.kb.doc/title "Intro v2"}]})
```

Proof: `{:name "Ivan" :age 35}` over an existing Ivan yields
`{:name "Ivan" :email "@1" :age 35}` (email preserved), `:tempids {}` —
`upsert_test.cljc:22-27`. Conflicting upserts throw — `upsert_test.cljc:92-109`.

**Clear ONE attr — explicit value-less retract** (omission only leaves it
unchanged):

```clojure
(db/transact! {::db/tx-data [[:db/retract [:my.kb.doc/id "d1"] :my.kb.doc/title]]})
```

A `:db/retract` with no value (2-element, or 3-element with nil v) retracts EVERY
current value of the attr — `transaction.cljc:959-970` (`(if (nil? v) [e a] …)`
→ searches and retracts all matching). For a cardinality-one scalar there is one
value, so this clears it.

**Replace a cardinality-many set — retract-all-then-add in ONE ordered tx:**

```clojure
(db/transact! {::db/tx-data [[:db/retract [:my.kb.doc/id "d1"] :my.kb.doc/tags]
                             {:my.kb.doc/id "d1" :my.kb.doc/tags [:a :b]}]})
```

Footgun: transacting tags alone only ADDS to the set
(`lookup_refs_test.cljc:106-108` — two adds accumulate `#{ {:db/id 2}{:db/id 3} }`;
`explode_test.cljc:36-43` — a coll explodes to one datom per element). tx-data is
applied IN ORDER, so the value-less retract clears the old set before the new set
lands — correct even when sets overlap (a surviving value is retracted, then
re-added). Same-tx ordering is honored — `lookup_refs_test.cljc:53-56`.

**Delete an entity via `:db.fn/retractEntity`** (component children cascade):

```clojure
(db/transact! {::db/tx-data [[:db.fn/retractEntity [:my.kb.doc/id "d1"]]]})
```

Proof: `[[:db.fn/retractEntity 1]]` removes entity 1 AND its component child 3 —
`components_test.cljc:46-51`; lookup-ref form `lookup_refs_test.cljc:83-84`.

**CAS work-fence** (`db/cas-assert`, `db.cljs:399-420`). `[:db.fn/cas e a old new]`
with `old == new == value` is a no-op assertion that commits IFF the current value
== value, else aborts the WHOLE tx with `:transact/cas` (surfaced as
`{::db/ok? false …}`):

```clojure
(db/transact! {::db/tx-data
               (into [(db/cas-assert [:seon.agent/id id] :seon.agent/run
                                     [:seon.agent.run/id run-id])]
                     work-tx)})
```

Proof: `compare-and-swap` raises `:transact/cas` on mismatch —
`transaction.cljc:873-895`; CAS resolves lookup-refs in e/v slots —
`lookup_refs_test.cljc:59-68`.

> **CAVEAT — `:db.fn/call` is NOT usable from the pod.** `[:db.fn/call f & args]`
> carries an actual fn closure `f` (`transaction.cljc:1052-1053`,
> `[report (apply f db args)]`), which cannot be serialized across the Unix-socket
> wire to the JVM writer. Use the declarative ops (`:db/add`, `:db/retract`,
> `:db.fn/retractEntity`, `:db.fn/cas`) only.

### QUERY — `:find` shapes (`d/q`)

```clojure
;; relation (set of tuples) — DEFAULT:
(db/query '[:find ?t ?r :where [?e :my.kb.doc/title ?t] [?e :my.kb.doc/rating ?r]])
;; => #{["A" 5] ["B" 4]}
;; scalar `.`:
(db/query '[:find (count ?e) . :where [?e :my.kb.doc/id]])      ;=> a number
;; collection [?x ...] — one column as a vector:
(db/query '[:find [?t ...] :where [?e :my.kb.doc/title ?t]])    ;=> ["A" "B"]
;; single tuple [?a ?b] — one row:
(db/query '[:find [?t ?r] :where [?e :my.kb.doc/title ?t] [?e :my.kb.doc/rating ?r]])
```

`:in` inputs — positional trailing (db is implicit `$`):

```clojure
(db/query '[:find [?t ...] :in $ ?name
            :where [?a :my.kb.author/name ?name]
                   [?d :my.kb.doc/author ?a]
                   [?d :my.kb.doc/title ?t]]
          "Alice")
;; map-in equivalent: {::db/query '[…] ::db/args ["Alice"]}
```

Lookup-refs work as `:in` inputs and bind in results —
`lookup_refs_test.cljc:186-235`.

**REF-JOIN — match through the ref, never put a value in the ref slot.** A ref
attr stores an EID. To match by the target's name, JOIN through it:

```clojure
;; GOOD: [?d :my.kb.doc/author ?a] [?a :my.kb.author/name "Alice"]
;; BAD : [?d :my.kb.doc/author "Alice"]      ; the slot holds an eid, not a name
```

Footgun: a literal lookup-ref in a ref slot resolves
(`[?e :friend [:name "Petr"]]` → matches — `lookup_refs_test.cljc:256-259`), but
a lookup-ref to a NONEXISTENT entity throws "Nothing found for entity id"
(`lookup_refs_test.cljc:261-264`); a bare keyword in the slot does not address
the joined entity. Ref-traversal join: `explode_test.cljc:51-54`.

**Aggregates + the `:with` dedup footgun.** An aggregate runs over the
DEDUPLICATED set of projected tuples; `:with ?e` keeps each entity's row distinct
WITHOUT projecting `?e`:

```clojure
(db/query '[:find (sum ?r) . :with ?e :where [?e :my.kb.doc/rating ?r]])
;; without :with, two docs both rated 5 collapse to one before summing
```

Proof — the canonical demonstration: heads `[3 1 1 1]`. WITHOUT `:with`,
`(sum ?heads)` → `[[4]]` (the three 1s dedup) — `query_aggregates_test.cljc:24-28`.
WITH `:with ?monster`, `(sum)(min)(max)(count)(count-distinct)` →
`[[6 1 3 4 2]]` — `query_aggregates_test.cljc:30-35`. `:with` keeps duplicate
rows: `[[1][1][1]]` — `query_aggregates_test.cljc:15-22`. `avg`/`median`/
`variance`/`stddev` — `query_aggregates_test.cljc:62-84`. Predicates DO apply to
aggregates (`[(> ?v 2)]`) — `query_aggregates_test.cljc:103-127`.

### PULL / ENTITY

```clojure
;; lookup-ref source + wildcard:
(db/pull '[*] [:my.kb.doc/id "d1"])
;; => {:db/id N :my.kb.doc/id "d1" :my.kb.doc/title "…"
;;     :my.kb.doc/author {:db/id M}            ; plain ref → {:db/id M}
;;     :my.kb.doc/notes [{:db/id K :my.kb.note/body "…"}]}  ; component auto-expands
;; nested ref sub-pattern to read the author's fields:
(db/pull '[* {:my.kb.doc/author [:my.kb.author/name]}] [:my.kb.doc/id "d1"])
;; reverse ref (who points at me):
(db/pull '[:my.kb.note/body :my.kb.doc/_notes] [:my.kb.note/id "n1"])
```

- `[*]` wildcard returns `:db/id` + scalars + refs as `{:db/id N}` —
  `pull_api_test.cljc:144-150`.
- A plain ref reads back as `{:db/id N}` until you NAME it with a sub-pattern —
  `pull_api_test.cljc:72` vs `:200-203`.
- Component refs auto-expand recursively under `[*]`/`[:attr]`; a map spec
  OVERRIDES expansion (`{:part [:name]}` or depth `{:part 1}`) —
  `pull_api_test.cljc:99-142,218-224`.
- Reverse ref `:attr/_back`: non-component yields a collection, component yields
  a single map — `pull_api_test.cljc:79-97,134-139`.
- Recursion `{:ref ...}` (unbounded) / `{:ref 2}` (limited); cycles return
  `{:db/id …}` for seen entities — `pull_api_test.cljc:226-308`.
- `(default :a "x")` and `(limit :a 500)` (default limit 1000) —
  `pull_api_test.cljc:152-198`; missing attrs are dropped — `:210-216`.
- Lookup-ref / ident pull source — `ident_test.cljc:27-29`.

`entity` (seon.db) — touched plain map; ref as `{:db/id N}`; `nil` for an
unresolved lookup-ref:

```clojure
(db/entity [:my.kb.doc/id "d1"])
;; => {:db/id N :my.kb.doc/id "d1" :my.kb.doc/title "…" :my.kb.doc/author {:db/id M}}
```

Lazy/touch semantics + cardinality-many reads back as a SET (`:aka #{"X" "Y"}`) —
`entity_test.cljc:16-44`; ref-attr lazy navigation in raw datahike —
`entity_test.cljc:46-82`; an unresolved lookup-ref → `nil`, a bad-attr
lookup-ref throws — `entity_test.cljc:84-94`. (Note: `d/entity db 777` for a
nonexistent NUMERIC eid returns an entity whose only key is `:db/id 777` — eids
are not existence-checked; lookup-refs ARE — `entity_test.cljc:91-92`.)

### INVENTORY

`(db/store-inventory)` is the discovery call — see the signature section. Run it
(or `(keys (db/installed-schema @db/*conn*))` for zero-row kinds) BEFORE
registering a new kind; reuse an existing shape rather than forking a parallel
one. The store starts empty in a fresh cluster — inventory is the ground truth
for what is actually there, never the demo data a manual builds.

---

## VERDICT ON `db/examples.cljs`

Read against every test above, **all 14 functions are IDIOMATIC**. No bugs, no
non-idiomatic Clojure, no schema/caller mismatches. Honest result: I went in
looking for the owner's "non-Clojure ways" and did not find any in this file —
the examples are a sound base for `my.kb`.

| Fn | Verdict | Proof |
|----|---------|-------|
| `register-reading-schema!` | IDIOMATIC ✓ | identity/scalar/many/ref/component all map correctly — `db/internal.cljs:286-350` |
| `seed-readings!` | IDIOMATIC ✓ | same-tx **string tempid** link (`upsert_test.cljc:43-53`) + inline component child (`explode_test.cljc:111-119`); correctly prefers tempid over a forward lookup-ref |
| `rename-reading!` | IDIOMATIC ✓ | upsert by identity, omitted keys unchanged — `upsert_test.cljc:22-27` |
| `clear-rating!` | IDIOMATIC ✓ | value-less `:db/retract` clears the scalar — `transaction.cljc:959-970` |
| `replace-tags!` | IDIOMATIC ✓ | retract-all-then-add, ordered in one tx — `lookup_refs_test.cljc:106-108,53-56`; many-add is additive `explode_test.cljc:36-43` |
| `delete-reading!` | IDIOMATIC ✓ | `:db.fn/retractEntity` cascades to components — `components_test.cljc:46-51` |
| `titles` | IDIOMATIC ✓ | collection `[?t ...]` → vector — `pull`/q shapes, `lookup_refs_test.cljc:199-203` |
| `title+rating` | IDIOMATIC ✓ | relation find, join on `?e` — standard |
| `titles-by-author` | IDIOMATIC ✓ | `:in`-bound + ref-JOIN (not a value in the slot) — `explode_test.cljc:51-54` |
| `reading-stats` | IDIOMATIC ✓ | scalar `.`, `(sum ?r) :with ?e` cures dedup, grouped count→map — `query_aggregates_test.cljc:24-35` |
| `reading-detail` | IDIOMATIC ✓ | `[*]` + ref sub-pattern; component auto-expands — `pull_api_test.cljc:144-150,200-203,99-142` |
| `reading-entity` | IDIOMATIC ✓ | lookup-ref → touched map, ref as `{:db/id N}` — `entity_test.cljc:16-44`, `db.cljs:935-944` |
| `inventory` | IDIOMATIC ✓ | discovery call — `db.cljs:1198-1295` |
| `build-reading-log!` | IDIOMATIC ✓ | `^:async`, awaits write before reading — matches `db.cljs:34-43` async contract |

### Precision notes (NOT defects — adopt verbatim in `my.kb`)

1. `seed-readings!`'s docstring claim — "Lookup-refs do NOT resolve against
   not-yet-committed entities" — is accurate AS APPLIED (it links via tempid).
   The fully-precise rule, if `my.kb` restates it: a lookup-ref resolves against
   the committed db plus ops asserted EARLIER in the same tx, but NOT forward to
   entities that appear later / only as an unresolved tempid
   (`lookup_refs_test.cljc:53-56` vs `:86-91`). Tempid linking is the
   order-independent idiom — keep it.
2. `replace-tags!`'s overlap claim is correct: within one ordered tx the
   value-less retract clears all current values before the add map re-asserts the
   surviving ones (`transaction.cljc:959-970`, same-tx ordering
   `lookup_refs_test.cljc:53-56`).
3. `reading-stats` `::count` guards the scalar with `(or … 0)` — harmless
   belt-and-suspenders for the empty-db case; keep it.

### One stale-reference correction for the new manual

`CLAUDE.md`'s Database-Access bullet lists `db/pull-by-name` — that fn does NOT
exist in the pod's `seon.db` (`db.cljs` public fns: `transact!`, `query`, `pull`,
`entity`, `entity-lazy`, `installed-schema`, `history`, `as-of`, `since`,
`listen!`/`unlisten!`, `store-inventory`, `cas-assert`, …). The lookup-ref
`[identity-attr value]` passed to `pull`/`entity` IS the by-name mechanism. `my.kb`
must teach the lookup-ref, never a `pull-by-name`.
