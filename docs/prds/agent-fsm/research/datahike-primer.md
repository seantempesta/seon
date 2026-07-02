---
type: reference
status: active
tags: [reference, database, agent, architecture]
---

# Datahike / Datomic Mindset Primer — read this BEFORE touching the run loop

Every agent working on the agent-runtime collapse reads this first. It exists
because the runtime accreted a **place-oriented** mental model (re-read a
mutable `@*conn*`, guard work with a predicate) on top of a database that is
**value-oriented** (an immutable snapshot you transform). The fixes only make
sense in datahike's grain — so **port datahike's primitives and use them as
intended; do not roll our own.** Where a claim cites source, it is in
`reference-code/datahike` (our fork) — **open it and read it**, don't trust this
summary blindly. The non-obvious findings below were each verified in the fork
(and some live on the running pod).

`seon.db` is the SOLE database API on the pod. Never call `datahike.api`
directly outside `src/seon/db/`. If a datahike primitive you need isn't
surfaced in `seon.db`, **add the wrapper there** — that is "porting the
function," and it keeps the one-API rule.

## 0. There are NO entity "kinds" — only attributes and connections

The most basic datahike/Datomic mindset, and the easiest to lose: **an entity has no
type, class, or kind.** An entity is an entity-id with a set of assertions (datoms),
nothing more. **What an entity "is" — "what you're looking at" — is determined entirely
by which attributes are present/absent on it and how it is connected to other entities
via refs.** A thing "is a route" because it carries `:seon.route/pattern` +
`:seon.route/method`, not because of a kind label; it "belongs to" an agent because a ref
connects them, not because of a class.

Schema attaches to **attributes** (`:db/valueType` / `:db/cardinality` / `:db/unique`),
never to entities. An entity is open — it may carry attributes from several "domains" at
once, or just one. So:

- **To FIND a set of entities:** query by **attribute presence** — scan the attribute's
  index (AEVT) for every entity asserting it. There is no "list all of kind K"; there is
  "every entity with attribute a". (seon's `:seon.entity/id-attr` is exactly this
  attribute-presence enumeration — it is **not** a per-row kind stamp; there is
  deliberately no `:seon.entity/kind`, see `src/seon/schema.cljc`.)
- **To IDENTIFY one entity:** use one of its `:db.unique/identity` **attributes** — that
  is also how `transact` upserts (existing-or-new is resolved by the unique attribute, no
  kind needed).
- **To RELATE or REMOVE:** follow **refs**. Removal/cascade is a property of the
  connection (`:db/isComponent` cascade-retracts children) — never a kind operation.

The anti-pattern (correct it on sight): designing or coding "per kind" — a kind taxonomy,
a `for each kind` loop, a per-kind dispatch, a `:type`/`:kind` discriminator field. If you
catch yourself there, reframe in **attributes + connections + provenance**. (This is the
same rule as CLAUDE.md "the namespaced keyword IS the discriminator" — general shapes top
level, specialize only on real shape divergence.)

## 1. A db is a VALUE, not a place

`(d/q query db)`, `(d/pull db sel eid)`, `(d/entity db ref)` are referentially
transparent over a db value: the value cannot change under you. The race you
think you have ("the DB moved between when I decided and when I acted") is, in
almost every case, an artifact of re-reading `@*conn*` three times instead of
**threading one value**.

- `seon.db` ALREADY exposes an explicit-db arity for every read —
  `(db/query {:seon.db/db db :seon.db/query …})`, `(db/entity {:seon.db/db db
  :seon.db/ref …})`, positional `(db/query q db & inputs)` (`seon.db.cljs`
  ~478-564, ~900-927). The machinery to thread a value exists; most callers just
  don't use it. **Use it.**
- **The pod is a follow-the-store replica:** every `@*conn*` deref
  *reconstitutes a fresh db value* from konserve with lazy LRU node fetch
  (`seon.store.wire` ns docstring; `:streaming? false`). So re-reading `@*conn*`
  in each leaf fn is not free, and two derefs at the same basis-t are
  **equal-by-value but NOT identical objects**. Threading one value through a
  unit of work is a correctness *and* a performance win.

## 2. The wire rule: ONLY VALUES cross. This is load-bearing.

The pod↔wire-server frame is one Transit-JSON map of native values
(`seon.store.internal.wire-node` ns docstring). Writes go over this wire to the
single JVM writer. The consequence shapes our fencing choice:

- **`:db.fn/cas` / `:db/cas` are PURE DATA** — they serialize and cross the
  write wire fine (`db/transaction.cljc` ~766-768, 1048). `open-run!` already
  CASes over the wire today (`seon.agent.run/open-run!`).
- **`:db.fn/call` (inline tx fn) carries a CLOSURE** — it **cannot** cross our
  wire (`db/transaction.cljc` ~1052-1068). A server-side `:db/fn` registered by
  `:db/ident` could (the fn lives JVM-side), but for pod-issued writes the
  wire-crossable fencing primitive is `:db.fn/cas`, not a closure.

This is why the work-fence is a CAS and not an inline tx-fn. It is not a
preference — it is forced by "only values cross."

## 3. CAS is a pure in-transaction assertion — LIVE-PROVEN (use it as the fence)

A `[:db.fn/cas eid attr old new]` with **`old == new`** is an *assertion*: "this
value is STILL `old`." Bundle it as the FIRST item of a work-tx and the whole tx
commits iff the assertion holds — atomically, at the single writer.

Proven on the live pod (2026-06-26), agent `1115`, attr `:seon.agent/run`:

| Current value of the attr | `[:db.fn/cas agent :seon.agent/run R R]` + bundled work | Result |
|---|---|---|
| still `R` | the bundled write **commits** | ✓ ok? true, work landed |
| moved/retracted (≠ `R`) | tx **aborts atomically** — `:transact/cas` error, bundled write **rejected** | ✓ ok? false, work did NOT land |

The error shape on failure: `{:error :transact/cas, :old <current>, :expected R,
:new R}`, surfaced through `seon.db` as the `{:seon.db/ok? false :seon.db/error
…}` envelope (errors are values; nothing throws into the loop).

**The work-fence pattern** (replaces every `owns-run?` pre-read):

```clojure
;; every work-tx (beat, open-turn, eval-batch) LEADS with the fence
(db/transact!
  {:seon.db/tx-data
   (into [[:db.fn/cas [:seon.agent/id id] :seon.agent/run
           [:seon.agent.run/id run-id]      ; expected: still mine
           [:seon.agent.run/id run-id]]]    ; new: unchanged → assertion
         work-tx)})
;; ok? false  ⇒  a watchdog/human/newer-run moved the pointer; the loop
;;              has lost authority. Terminate. No "zombie" work commits.

```

`[:seon.agent.run/id run-id]` is a lookup-ref to an **existing** entity, so it
resolves in the CAS value slot (a tempid would NOT — see `open-run!`'s comment).

## 4. basis-t: cheap and stable — but DON'T key it naively across db types

- Every db value carries `max-tx` (the basis-t) via `dbi/-max-tx`, implemented on
  EVERY db type (`db.cljc` ~336 for `DB`). It's a plain int; reading it is O(1)
  (`seon.store.wire` RYOW already reads `(:max-tx db)`). This is the right cache
  key and the right "did the world advance?" check.
- **GOTCHA (Gemini got this WRONG; the source is right):** `as-of` / `since` /
  `historical` / filtered db values report their **origin db's** `max-tx`, NOT
  the as-of/since point — `db.cljc:493` `(-max-tx [db] (dbi/-max-tx
  (.-origin-db db)))`. So the live db at t=1000 and `(d/as-of db 500)` **share
  basis-t 1000 but answer queries differently.** A basis-t-keyed cache is
  correct ONLY for current-db derivations; if it could ever be handed a
  filtered/as-of value (e.g. the inspector's historical views), key on
  `[basis-t db-type]` or don't share the cache across shapes.

## 5. Do NOT memoize on the db VALUE (it faults the whole index in)

Tempting: `(memoize (fn [db id] …))`. In our replica this is a trap.

- A `DB`'s `hashCode` is O(1) — a *cached field*, the sum of all datom hashes
  computed once at construction (`db.cljc:316`, value at `db.cljc:944`
  `:hash (reduce #(+ %1 (hash %2)) 0 datoms)`). So two reconstitutions at the
  same basis-t are hash-equal, and a *miss* is cheap.
- But `clojure.core/memoize` confirms a hash-bucket hit with `=`, and `=` on a
  DB calls `equiv-db`, which **walks the entire EAVT index** datom-by-datom
  (`db.cljc:674` `(equiv-db-index (dbi/datoms db :eavt []) …)`). In our
  follow-the-store replica that **forces every index node in off konserve from
  disk** — on every cache HIT, i.e. exactly when you wanted it to be fast.

Conclusions:

- **`clojure.core.memoize`** (the contrib LRU/TTL/FIFO) is **JVM-only — not
  available in CLJS.** Don't reach for it pod-side.
- Plain `clojure.core/memoize` keys on the *whole* arg list; it can't key on a
  *projection* (basis-t) while computing with the db. Wrong tool here.
- **First choice: no cache.** Threading already computes each derivation once
  per turn. `:memory`/local reads are sub-ms on small datom counts
  (house doctrine: measure before caching; don't bifurcate into a stored
  fast-path plus a derived slow-path).
- If a profile later shows a derivation hot *across* consumers at one basis-t
  (loop + inspector together), add a **~15-line bounded map keyed on
  `[basis-t db-type]`** inside the one derive leaf. That is the only shape that
  is both correct (dodges `equiv-db` + the as-of collision) and CLJS-available.

## 6. Primitives that exist — port them through `seon.db`, don't reinvent

| Primitive | Where in the fork | In `seon.db`? |
|---|---|---|
| `q` / `pull` / `entity` w/ explicit db | `api/specification.cljc` | yes — explicit-db arities (~478-564) |
| `as-of` / `since` / `history` | `api/specification.cljc` ~467-489 | yes — wrappers (~941-983) |
| `d/with` / `db-with` (speculative tx → new value, no commit) | `core.cljc:126`, `api/specification.cljc` ~244-271 | **NO — surface it if a unit needs "what would this tx produce"** |
| `:db.fn/cas` (data) | `db/transaction.cljc` ~766-768, 1048 | via `transact!` tx-data (it's just data) |
| tx-log range / `since` for replay (DE-2) | `api/specification.cljc` (`tx-range`/`since`) | **NO — the since-t feed replay needs a JVM-side wrapper** |

"State at turn N" = `db-as-of(t)` over the stored turn basis-t — the bitemporal
DB *is* the storage. We never replay events; we bound the *view*, not the
storage.

## 7. The two wire channels (so you fix DE-2 in the right place)

- **Request/reply RPC** (`wire-node/rpc` → JVM `handle-op` multimethod): `q`,
  `transact`, `pull`, `schema`, `knn-search`, … Synchronous frame-in/frame-out;
  **reliable — nothing to drop.** `knn-search` is already "a remote function
  call that returns data" (pod sends NL text, JVM embeds via Gemini, returns
  hits) — the values-only RPC pattern, already in production. Embeddings live on
  the JVM and are reached this way (+ the embed-on-write tx-augmenter in
  `server/wire.clj`).
- **The tx FEED** (`subscribe-tx`/`next-tx-event`, server-side per-handle bounded
  `ConcurrentLinkedQueue` in `seon.server.boot`): a broadcast the pod polls.
  `boot.clj` explicitly assumes "a dropped event is not a correctness hazard —
  the guest re-reads at the latest basis-t on its next read." **That is true for
  rendering and FALSE for the wake edge** (a wake event IS the trigger to act;
  if dropped, no "next read" fires on its own → an idle agent sits with unread
  mail). On UDS reconnect the pod re-subscribes with a fresh handle → the gap is
  lost. The fix is `since-t` replay (DE-2), not a change to the RPC channel.

Within a run, the loop reads the store itself each turn — it does **not** depend
on the feed. The feed's only jobs are (a) waking an *idle* agent and (b) the
inspector SSE. So only the wake edge must be made lossless.

## 8. Where to read in the fork (don't reverse-engineer)

- `src/datahike/db.cljc` — the db value: `DB`/`FilteredDB`/`AsOfDB`/`SinceDB`/
  `HistoricalDB` records, `-max-tx`, `equiv-db`, hashing.
- `src/datahike/db/transaction.cljc` — `:db.fn/cas`, `:db.fn/call`, tx expansion.
- `src/datahike/api/specification.cljc` — the public surface: `with`, `as-of`,
  `since`, `history`, `tx-range`.
- `src/datahike/core.cljc` — `db-with` and friends.

Our own seam: `src/seon/db.cljs` (the API), `src/seon/store/wire.cljs` (the pod
replica, write wire, feed adapter), `src/seon/store/internal/wire_node.cljs`
(transport), and `src/seon/server/wire.clj` with `src/seon/server/boot.clj` (the
JVM writer and feed).
