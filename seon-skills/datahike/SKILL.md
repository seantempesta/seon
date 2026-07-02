---
name: datahike
description: "Seon database patterns. Use when writing Datalog queries, transacting data, debugging empty/unexpected results, designing a schema, or working with seon.db / seon.schema. Use for schema/register!, db/transact!, db/query, db/pull, db/entity, db/store-inventory, lookup-refs, refs/components/identity, upsert, retract, cardinality-many, CAS fences, as-of/since history, listen!/triggers, or any 'where do I put this data / how do I read it back' question in the CLJS pod."
---

# Datahike — Seon Database Patterns

You write to a database through ONE namespace: `seon.db` (alias it `db`).
`seon.schema/register!` is the single source of truth for every attribute's
shape — you NEVER hand-write datahike schema. This skill owns the
database-specific facts: query/pull/transact in the pod, `register!` + the
Malli→datahike bridge, refs/components/identity, provenance/scope, discovery,
and the pod↔wire-server read/write split.

> Hand-offs (single-ownership of facts — don't duplicate them here):
> the general data-oriented mindset (errors-as-values, derive-don't-store, no
> bare keys) → **`data-oriented-clojure`**; `^:async`/`await`/self-host eval /
> a Promise leaking into output → **`clojurescript`**; test fixtures/generators
> → **`clojure-testing`**. The Datalog/Datomic mindset, source-grounded, is
> `docs/prds/agent-fsm/research/datahike-primer.md` — read it once.

## The runtime: one connection, reads local, writes over a wire

The ACTIVE runtime is the **CLJS pod** (a long-running Node process). It does
**NOT** embed datahike. The picture:

- **Your universe is ONE connection.** `seon.db/*conn*` is bound for you before
  your code runs. Never thread it, never open another. Every `db/` fn defaults
  to `*conn*`, so you omit it.
- **Reads are SYNC and local.** `query`/`pull`/`entity` resolve against the
  current db value (`@*conn*`) — a lazy, immutable snapshot reconstituted from
  the store with LRU node fetch (memory ∝ working set, not whole-DB). Compose
  reads in straight-line code.
- **Writes return a Promise envelope over a wire.** `transact!` is `^:async`;
  it forwards the tx over a Unix socket to the **wire-server** (the single JVM
  writer; durable file-backed store at `data/clusters/default/store`). You get
  back a data ENVELOPE, never a throw.

A db is a **value, not a place** (`datahike-primer.md` §1). The "race" you
think you have ("the DB moved between deciding and acting") is almost always
re-reading `@*conn*` three times instead of threading one value. To act on a
value the writer can confirm hasn't moved, use a CAS fence (below).

> The embedded-LMDB-in-JVM model — `data/datahike/`, core.async flow,
> `db/*direct-mode*`, per-db `db-name` keywords — is the **paused JVM track**
> (`src/seon/db.clj`). It is NOT how the pod works. If you see those, you're in
> the wrong lane.

## There are NO entity kinds — only attributes + connections

The most-repeated correction. An entity has no type/class/kind — it is an id
plus a set of datoms. What it "is" comes entirely from which attributes it
carries and how refs connect it. Schema attaches to **attributes**, never to
entities; an entity is open (it can carry attrs from several domains at once).
(Full mindset + the OO reflexes to drop: `data-oriented-clojure`. Datahike
specifics: `datahike-primer.md` §0.)

Applied to datahike, four moves replace every "for each kind":

- **FIND** a set → query by **attribute presence**: `[?e :my.kb.source/id]`
  enumerates every source. There is no "list all of kind K".
- **IDENTIFY** one → a `:db.unique/identity` **attribute**. That is also how
  `transact!` upserts (same identity value ⇒ update in place, no duplicate).
- **RELATE / REMOVE** → follow **refs**. Cascade is a property of the
  connection (`:db/isComponent` retracts children), never a kind operation.
- **SCOPE** is two independent axes, don't conflate them:
  - **provenance** — `:seon.db/origin` on the *transaction* (`:agent`,
    `:core-seed`, `:user`, …), auto-stamped via `with-tx-context`/`with-agent`.
    Answers "where did this fact come from". Drives `store-inventory`'s
    user-vs-system split.
  - **ownership** — a domain ref like `:seon.agent.todo/owner` pointing at the
    owning entity. Answers "whose row is this". Per-agent filtering.

If you catch yourself writing a `:type`/`:kind` field or a per-kind loop, stop
and reframe in attributes + connections + provenance.

**ENUMERATE stored data the right way** — `store-inventory`'s actual job is to
group the live attributes BY THEIR NAMESPACE (a display grouping, not an entity
type) and, when you want the entities themselves, scan one identity attr's index.
REPL-proven against the live store:

```clojure
;; "what holds data?" — attr namespaces + counts (no entity kind anywhere)
(seon.db/store-inventory)
;; => {:seon.db/attr-groups   [{:seon.db/attr-ns :my.kb
;;                              :seon.db/attrs {:my.kb/question 3 :my.kb/answer 3}} …]
;;     :seon.db/attr-ns-count 9 :seon.db/attr-count 53 :seon.db/datom-count 124}

;; enumerate entities BY ID-ATTR PRESENCE (scan that attr's index)
(seon.db/query {:seon.db/query '[:find ?id :where [?e :seon.agent/id ?id]]})

;; group the whole store by which identity attr each entity carries
(->> (seon.db/query {:seon.db/query '[:find ?a :where [?s :seon.schema/key ?a]]})
     (map first)
     (filter seon.schema/identity-attr?)
     (keep (fn [a]
             (let [n (count (seon.db/query
                              {:seon.db/query [:find '?e :where ['?e a]]}))]
               (when (pos? n) [a n])))))
;; => ([:seon.fn/sym 614] [:seon.eval/id 317] [:my.kb.runtime/slug 7] …)
```

The grouping label is always an **attribute** (a namespace or an id-attr), never a
"kind". `store-inventory` returns `:seon.db/attr-groups` keyed by `:seon.db/attr-ns`
for exactly this reason.

## Quick start — register, transact (check the envelope), read back

Inside namespace `my.kb.source` you write `::id` and the reader expands it to
`:my.kb.source/id`. Schemas live in the namespace whose name they carry.

```clojure
(require '[seon.db :as db] '[seon.schema :as schema])

;; 1. REGISTER first — transact! refuses any attr it doesn't recognize.
(schema/register! ::id    [:string {:seon.db/identity true}])  ; natural key → upsert
(schema/register! ::title :string)
(schema/register! ::rank  :int)

;; 2. TRANSACT — map-in, every key namespaced. Returns a Promise ENVELOPE
;;    (auto-awaited at the REPL top level; inside an ^:async fn you await it).
;;    ALWAYS read ::ok? — an eval can succeed yet the write did NOT happen.
(let [{::db/keys [ok? error]}
      (db/transact! {::db/tx-data [{::id "s1" ::title "Alpha" ::rank 1}]})]
  (if ok? :saved error))

;; 3. QUERY — SYNC, db auto-injected (omit it). Pick the :find shape:
(db/query '[:find ?t :where [?e ::title ?t]])           ;=> #{["Alpha"]}     relation
(db/query '[:find [?t ...] :where [?e ::title ?t]])     ;=> ["Alpha"]        collection
(db/query '[:find (count ?e) . :where [?e ::id]])       ;=> 1                scalar

;; 4. PULL / ENTITY — read one entity by lookup-ref [identity-attr value]:
(db/pull '[*] [::id "s1"])                ;=> {:db/id N :my.kb.source/id "s1" …}
(db/entity [::id "s1"])                   ;=> touched plain map
```

`my.kb` (`src/my/kb.cljs`) is the runnable, test-exercised manual — every
recipe compiles. `seon.agent.todo` (`src/seon/agent/todo.cljs`) is the EXEMPLAR
store/retrieve ns (identity, refs, tree/DAG queries, derived datalog rules).
Read those for live idiom.

## Schema: register! is the single source of truth

Register the **Malli type**; the bridge (`seon.db.internal/malli->datahike-attr`)
derives every datahike facet — `:db/valueType`, `:db/cardinality`, `:db/unique`,
`:db/isComponent`. You never write `:db.type/*` yourself. Datahike installs an
attr's schema **lazily, at its first `transact!`** (register! alone only teaches
the Malli registry — see the uninstalled-attr gotcha below).

```clojure
(schema/register! ::name   :string)
(schema/register! ::count  :int)
(schema/register! ::active :boolean)
(schema/register! ::ratio  :double)
(schema/register! ::when   :inst)          ; java.util.Date / js/Date
(schema/register! ::uid    :uuid)
(schema/register! ::tag    :keyword)
(schema/register! ::status [:enum :open :done])      ; enum of keywords
(schema/register! ::tags   [:vector :keyword])        ; cardinality-MANY
(schema/register! ::parent :seon.db/ref)              ; ref → another entity
```

### Bridge derivation (live-verified)

`(db/malli->datahike-schema [attr …])` shows exactly what gets installed:

| You register | Bridge installs |
|---|---|
| `[:string {:seon.db/identity true}]` | `:db.type/string` + `:db/unique :db.unique/identity` (UPSERT + lookup-ref) |
| `[:and {:seon.db/identity true} :seon.db/id]` | same — `:seon.db/id` is the canonical 14-char id shape |
| `[:enum :open :done]` | `:db.type/keyword`, cardinality one |
| `[:vector :seon.db/ref]` | `:db.type/ref`, cardinality **many** |
| `[:vector {:seon.db/component true} :seon.db/ref]` | `:db.type/ref`, many, **`:db/isComponent true`** (children cascade-retract) |
| `:inst` | `:db.type/instant` |
| `:seon.db/ref` | `:db.type/ref`, cardinality one |

Shapes used in two+ schemas get **registered once and referenced** — never
inlined twice. The canonical examples: `:seon.db/ref` (every ref attr
references it) and `:seon.db/id` (every id attr). If the bridge can't map a
shape you need, **fix the bridge** (`src/seon/db/internal.cljs`) — don't inline.

### Banned types

No `:any` / `:some` / `:nil` / `[:maybe X]` for seon-authored data. Optional
field = `{:optional true}` and **absent, never nil**. (`:any` is allowed only
at genuine third-party boundaries — e.g. a raw datahike db handle.)

## Write path — `transact!` returns data, never throws

`transact!` is **safe by default**: every failure (bad shape, unregistered
attr, value fails its schema, datahike commit explosion, CAS abort) comes back
as `{::db/ok? false ::db/error <error-map>}`. Success is COMPACT DATA
(`::db/ok? true`, `::db/tempids`, `::db/tx`, `::db/tx-count`, `::db/added`,
`::db/retracted`) — not the raw datahike report. The error's
`:seon.error/data :seon.error/kind` is `:user-input` (fix tx-data, retry) vs
`:core-bug` (report it).

```clojure
;; UPSERT by identity — same identity value ⇒ same entity, no duplicate.
;; OMITTED keys are LEFT UNCHANGED (absent ≠ cleared):
(db/transact! {::db/tx-data [{::id "s1" ::title "Alpha v2"}]})

;; CLEAR one field — retraction is EXPLICIT (omission does nothing):
(db/transact! {::db/tx-data [[:db/retract [::id "s1"] ::title]]})

;; DELETE the whole entity (component children cascade):
(db/transact! {::db/tx-data [[:db.fn/retractEntity [::id "s1"]]]})

;; CARDINALITY-MANY: transacting a value ADDS to the set. To REPLACE,
;; retract-all then add, bundled BEFORE the add-map in ONE ordered tx:
(db/transact! {::db/tx-data [[:db/retract [::id "s1"] ::tags]
                             {::id "s1" ::tags [:lisp :db]}]})

;; LINK new entities in ONE tx via shared TEMPID strings. A lookup-ref does
;; NOT resolve forward to a not-yet-committed entity; a tempid does. ::author
;; is a ref, so the tempid "p1" in its slot resolves to the new person:
(schema/register! ::person-id [:string {:seon.db/identity true}])
(schema/register! ::author :seon.db/ref)
(db/transact! {::db/tx-data [{:db/id "p1" ::person-id "alice"}
                             {::id "s2" ::author "p1"}]})
```

**Don't write `await` on `transact!`.** An `^:async` call is auto-awaited, so
you get the envelope directly; writing `await` at the top level is an error
(it's a macro, valid only inside an `^:async` body — see `clojurescript`).

### The CAS work-fence (commit iff the world hasn't moved)

A `[:db.fn/cas ref attr old new]` with **`old == new`** is an in-tx assertion
"this value is STILL `old`". Lead a work-tx with it and the whole tx commits
atomically at the single writer iff the assertion holds; otherwise it aborts
(`:transact/cas`) and surfaces as `{::db/ok? false …}`. This is the database —
not a pre-read predicate — telling the writer it lost authority. CAS is pure
data, so it crosses the write wire (an inline `:db.fn/call` closure would NOT).
Source: `reference-code/datahike/src/datahike/db/transaction.cljc:873`. Full
pattern + the live proof table: `datahike-primer.md` §3 and `db/cas-assert`.

## Read path — Datalog, sync, db auto-injected

`db/query` wraps datahike's `d/q`; the db is injected from `*conn*` (omit it);
`:in` inputs come AFTER the query (`$` is the db, implicit and first).

```clojure
;; relation / scalar / collection / single-tuple — chosen by :find shape:
(db/query '[:find ?n ?r :where [?e ::name ?n] [?e ::rank ?r]])   ;=> #{["A" 1] …}
(db/query '[:find ?n . :where [?e ::name ?n]])                   ;=> "A"  (one scalar)
(db/query '[:find [?n ...] :where [?e ::name ?n]])               ;=> ["A" "B"]
(db/query '[:find [?n ?r] :where [?e ::name ?n] [?e ::rank ?r]]) ;=> ["A" 1]

;; :in parameter — pass inputs AFTER the query:
(db/query '[:find [?n ...] :in $ ?min
            :where [?e ::rank ?r] [(>= ?r ?min)] [?e ::name ?n]] 5)

;; predicate + binding-expr inside :where:
(db/query '[:find ?s :where [?e ::doc ?d] [(count ?d) ?l] [(> ?l 400)] [?e ::sym ?s]])

;; pull inside a query — navigate refs:
(db/query '[:find (pull ?e [::name {::parent [::name]}]) :where [?e ::name _]])
```

Advanced shapes (aggregates, rules, `not`/`or`, the `:with` aggregate footgun)
→ `references/querying.md`. `seon.agent.todo`'s `rules` is a live datalog-rules
example (transitive tree closure, ready/blocked derivation).

### Two read traps that bite everyone

```clojure
;; REF-JOIN, not keyword-in-slot. A ref attr stores an EID, not the target's
;; value. To match by the target's name, JOIN through it:
(db/query '[:find (count ?e) . :where [?e :seon.fn/ns ?n] [?n :seon.ns/name :seon.db]]) ; GOOD
;; (db/query '[:find ?e :where [?e :seon.fn/ns :seon.db]])  ; THROWS "Nothing found …"

;; LOOKUP-REF value is the STORED type. :seon.fn/sym is a :string, so pass the
;; STRING — a quoted symbol throws "Cannot compare String to Symbol":
(db/pull '[*] [:seon.fn/sym "seon.db/query"])   ; GOOD
```

Results are CLIPPED (~50 rows) for context. Want a number? `(count …)` in the
query, don't list-then-count. Empty `#{}` on a query that should match? The
attr keyword is almost certainly misspelled (the guard below catches it).

## Discovery — consult the store BEFORE registering a new shape

```clojure
;; store-inventory: WHICH attribute namespaces hold data RIGHT NOW (so you know
;; what you can query, and which shapes to REUSE rather than fork). Default
;; scope = data added after the core seed.
(db/store-inventory)
;;=> {:seon.db/attr-groups [{:seon.db/attr-ns :my.kb :seon.db/attrs {:my.kb/question 3 …}} …]
;;    :seon.db/attr-ns-count … :seon.db/attr-count … :seon.db/datom-count … :seon.db/topology …}

;; installed-schema: EVERY attr installed on the db, including
;; registered-but-dataless ones store-inventory omits. Filter keyword? — the
;; map is also keyed by numeric attr-eid (a datahike internal):
(->> (keys (db/installed-schema @db/*conn*)) (filter keyword?)
     (filter #(= "seon.agent.todo" (namespace %))) sort)
```

An attr namespace that already holds data means data you can datalog (its listed
attrs ARE the `:where` keywords) and a shape to REUSE. Check before inventing an
attr.

## Common errors and gotchas

- **"Query/Pull names attribute(s) … never seen"** — the guard caught a typo:
  an attr neither installed nor registered can only return `#{}`, so it throws
  legibly instead. Fix the spelling (check `installed-schema`), or `register!` +
  transact data first.
- **Uninstalled attr throws on read** — datahike installs schema at first
  `transact!`. A registered-but-dataless attr in a `d/datoms` scan or explicit
  pull throws under `:schema-flexibility :write`. `query`/`pull` guard this for
  you (registered → silently treated as no-data; unregistered → legible throw).
  Raw scans must gate on `(contains? (db/installed-schema db) attr)`.
- **Empty results** — wrong attr spelling, a type mismatch (querying `30` where
  `30.0` is stored), or a ref-join you wrote as keyword-in-slot.
- **Nil values rejected** — never store nil. To clear, `[:db/retract eid attr]`.
- **Schema evolution** — add attrs any time; you cannot change an existing
  attr's value type, and adding uniqueness fails if duplicates exist.

## Transaction metadata — provenance rides on the tx entity

Every datom's 4th field names its **transaction**, and the transaction is a
real entity: datahike turns `:tx-meta` into datoms ON the tx
(`reference-code/datahike/src/datahike/db/transaction.cljc:802` `flush-tx-meta`)
and auto-stamps a monotonic `:db/txInstant`. Seon **already auto-merges
provenance into every `transact!`**: the active `with-agent`/`with-tx-context`
scope (the agent loop sets it for you) stamps `:seon.db/agent-id`,
`:seon.db/session-id`, `:seon.db/turn-id`, `:seon.db/eval-id`,
`:seon.db/origin`, `:seon.db/replay?`, `:seon.db/resume-marker?` — and it
survives the wire to the JVM writer.

So WHO/WHAT/WHEN-wrote-this is a **join, not an attribute**:

```clojure
;; which agent/turn wrote this entity's title? Bind the datom's ?tx, read the tx entity:
(db/query '[:find ?agent ?turn
            :where [?e :my.kb.source/id "src-1"]
                   [?e :my.kb.source/title _ ?tx]
                   [?tx :seon.db/agent-id ?agent]
                   [?tx :seon.db/turn-id ?turn]])
;; WHEN is [?tx :db/txInstant ?at] — auto-stamped, no attr of yours needed.
```

**DON'T register provenance attrs on domain entities** — a
`:my.thing/created-by-agent`, `/created-at`, `/updated-at`, or `/source-turn`
duplicates what the tx already records (the derive-don't-store violation in
temporal form). Custom per-tx facts (an import batch, a source label) also go
on the tx: include `{:db/id :db/current-tx :my.ingest/source "…"}` in the
tx-data (`references/data-modeling.md` §Transactions).

**The one exception:** a PRE-event snapshot coordinate — an application fact
about a db value observed BEFORE the entity's own tx (canonical example:
`:seon.agent.turn/rendered-as-of`, the frozen basis-t a prompt rendered from;
other agents' txs interleave, so the entity's creation-tx is NOT that
coordinate). Genuinely underivable → a real domain attr.

## Temporal, listeners, triggers (brief)

```clojure
;; Time-travel: derive a db VALUE at another point, pass it positionally.
(db/query '[:find ?v :where [?e :seon.ns/name ?v]] (db/as-of some-tx))  ; as it was
(db/query '[:find ?e :where [?e ::status :done]]   (db/since last-seen)) ; only after
(db/query '[:find ?v ?tx ?added :where [?e :seon.ns/name ?v ?tx ?added]] (db/history))
;; db/basis-t = the "now" end; db/origin-t = the empty pre-seed floor.
```

`db/listen!` installs a tx-listener by key; the handler gets one rich map
(`:seon.db/db` the post-commit value, `:seon.db/datoms`, `:seon.db/attr-index`)
— no reaching back to `*conn*`. The data-driven layer over it is
`seon.trigger/register!` (triggers persisted as DB entities). The canonical
reaction is an agent's wake-up on new `:seon.agent.message/to` datoms.

## Key files

| File | Purpose |
|------|---------|
| `src/seon/db.cljs` | The whole agent-facing API — read its docstrings, they're the reference |
| `src/seon/schema.cljc` | `register!`, the registry, entity-schema decomposition |
| `src/seon/db/internal.cljs` | the Malli→datahike bridge + tx validation gate |
| `src/my/kb.cljs` | runnable manual — copy a recipe, swap your attrs |
| `src/seon/agent/todo.cljs` (+ `todo/internal.cljs`) | EXEMPLAR: identity, refs, tree/DAG, rules |
| `reference-code/datahike/` | the fork's source — read it, don't guess semantics |

## When to read references

- `references/querying.md` — aggregates, rules, `not`/`or`, predicates, the
  `:with` footgun, performance.
- `references/data-modeling.md` — fact-DB modeling: deep-namespace `::`
  convention, natural-key upsert, one-directional refs (free reverse), reified
  relationships, reified-tx provenance, intra-tx tempids vs lookup-refs, a
  worked example in pod idiom.
- `references/datahike-internals.md` — EAV/datoms, value types, schema-as-datoms,
  history, the pod↔wire-server split, and where to read in the fork.
