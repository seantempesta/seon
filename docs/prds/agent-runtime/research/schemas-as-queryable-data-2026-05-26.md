---
type: research
status: draft
tags: [research, malli, datahike, schema, query]
---

# Schemas as queryable Datahike entities — validate or revise

**Date:** 2026-05-26
**Branch:** `feature/agent-runtime`
**Pod:** session `default` on shadow port 7889 (live)
**Context:** Proposed design decomposes every `seon.schema/register!` call into a
`:seon.schema` datahike entity carrying `:seon.schema/required-attrs` as a
`:db.cardinality/many :db.type/keyword`. Render-time kind-lookup becomes a
datalog query against those entities, scored by specificity. This research
validates the shape end-to-end against the live pod.

## TL;DR

1. **The shape works.** Two-query datalog approach (matched-count + total-count)
   correctly identifies the most-specific schema that ALL of whose required
   attrs are present in the entity. Verified live in the pod against 3 hand-built
   schemas and again against 103 schemas. Performance is fine: ~100µs per
   kind-lookup at 103 schemas, with totals cached.
2. **One critical revision.** The proposal's `:seon.schema/required-attrs =
   #{:seon.eval/id :seon.eval/source :seon.eval/ok? :seon.eval/at}` assumes the
   registered `:seon.eval` entity-schema enumerates those keys as children.
   **It does not today.** Live probe shows `(m/children (m/schema :seon.eval))`
   returns ONLY `[[:seon.eval/id nil :seon.eval/id]]`. The current entity-shape
   schemas are degenerate (one child = the id-attr). The proposed design implies
   we must FIRST fill out those entity-shape schemas — every attr that belongs
   to the kind has to be a child of the `:map` — before the required-attrs index
   has any meaningful content.
3. **Specificity metric: "all required matched, pick max total".** Absolute
   count beats fraction. A schema's required-attrs are its CONTRACT — partial
   match = no match. Among complete matches, the one with the most required
   attrs is the most-specific.
4. **Drop `:seon.schema/optional-attrs`.** Not used in the kind-lookup, not used
   in any current render path, and the optional/required distinction is already
   in the source Malli schema if anyone needs it.
5. **Single-tx with cardinality-many is the right shape.** Verified — `[:db/add
   tid :seon.schema/required-attrs k]` per attr (tuples, not nested entity maps).
   The reverse-lookup ("find all schemas containing attr X") falls out of the
   same index for free.
6. **Self-bootstrap is straightforward.** `:seon.schema` is registered before
   any user-domain schemas in `seon.schema.cljc`-load order; `seon.db/transact!`
   is what writes the schema entity, so the order is:
   (a) register `:seon.schema/key` + `:seon.schema/required-attrs` as datahike
   attrs via the bridge (no chicken-and-egg — these are leaf attrs, not entity
   maps); (b) on every subsequent `register!`, write a corresponding
   `:seon.schema` entity. The first such write is for `:seon.schema` itself.

## Source citations

- `reference-code/malli/src/malli/core.cljc:2772-2799` — `m/entries`
  (`MapEntry`s with `[k props val-schema]` shape, wrapped in `:malli.core/val`).
  Reads from `-entries` on the EntrySchema protocol.
- `reference-code/malli/src/malli/core.cljc:2596` — `m/children`
  (`[k props child-schema]` raw tuples, no `:malli.core/val` wrap). Simpler
  accessor for the required/optional split.
- `reference-code/malli/src/malli/core.cljc:2801-2809` — `m/explicit-keys`
  (just the keys, ignores `::m/default` entries).
- `reference-code/datahike/src/datahike/db.cljc:866-880` — AVET index is
  populated ONLY for attrs in `(:db/index rschema)`. Other attrs query via
  AEVT scan + value filter.
- `reference-code/datahike/src/datahike/constants.cljc:55-60` — `:db/index`
  defaults to true for `:db/unique`/`:db.cardinality/many` only when explicitly
  marked; ref attrs are indexed.
- `src/seon/schema.cljc:152-181` — current `register!` already derives
  `:seon.entity/id-attr` from `:map` children and stashes it as schema property.
  Bridge for the proposed design extends this with a `seon.db/transact!` write.
- `src/seon/db.cljs:1127-1164` — `malli->datahike-attr` bridge. Needs no change
  for `:seon.schema/required-attrs [:vector :keyword]` — already maps to
  `{:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}`.
  Confirmed via REPL.

## A. Malli — extracting required + optional attrs

`m/children` is the canonical, cheap, idempotent accessor. It returns a vector
of `[entry-key props child-schema]` tuples; `props` carries `{:optional true}`
when set, nil otherwise.

```clojure
(defn required+optional [map-schema]
  (reduce (fn [acc [k props _child]]
            (if (:optional props)
              (update acc :optional conj k)
              (update acc :required conj k)))
          {:required #{} :optional #{}}
          (m/children map-schema)))
```

REPL-probed (pod, session default):

```
(required+optional [:map [:probe.x/a :string]
                         [:probe.x/b {:optional true} :int]
                         [:probe.x/c :keyword]])
;; => {:required #{:probe.x/a :probe.x/c}, :optional #{:probe.x/b}}

;; Nested-map containment — confirms no bleed:
(required+optional [:map [:outer/id :string]
                         [:outer/nested [:map [:inner/x :string]
                                              [:inner/y {:optional true} :int]]]])
;; => {:required #{:outer/id :outer/nested}, :optional #{}}
```

Both confirmations from this probe. `m/children` does not descend into nested
schemas — `:inner/x` is not surfaced as a parent-level key. Idempotent: calling
twice returns identical output, no parse-and-reparse cost.

**Important live-pod finding:** the existing Seon entity schemas are degenerate.
`(m/children (m/schema :seon.eval))` returns ONLY `[[:seon.eval/id nil :seon.eval/id]]`.
The `:seon.eval/source`, `:seon.eval/at`, `:seon.eval/ok?`, etc. are registered as
top-level attrs in `src/seon/agent.cljs:149-178`, but they were never enrolled as
children of the `:seon.eval` map. Same for `:seon.fn`, `:seon.message`, `:seon.ns`,
`:seon.schema`. The proposed design REQUIRES filling these out. That's a separate
edit (and a desirable one anyway — the entity schema should describe the entity).

## B. Datahike — AVET claim, verified with caveat

The proposal says "AVET-indexed query, O(log N) on the index." Reading
`reference-code/datahike/src/datahike/db.cljc:866-880`: AVET is populated only
for attrs in `(:db/index rschema)`. Our current bridge (`src/seon/db.cljs:1127`)
emits `:db/index` for nothing — only `:db/unique` for identity-attrs. Cardinality-many
keywords go to AEVT only.

**Today, kind-lookup uses an AEVT scan over `:seon.schema/required-attrs` rows,
then the datalog `:in [?req ...]` collection-binding intersects with the
caller-supplied attr-set. The cost is O(N-required-attr-datoms), NOT O(log N).**

Performance probe (live pod, in-memory backend, 103 schema entities × ~4 reqs
each = 405 rows in the `:seon.schema/required-attrs` index):

| Test | Time |
|------|------|
| Both queries (totals + matched), 1000 iterations | 155ms (155µs each) |
| Cached totals, matched-only, 1000 iterations | 100ms (100µs each) |
| Pure Clojure-map alternative, 1000 iterations | 83ms (83µs each) |

At 100 schemas, datalog is 20% slower than a hand-rolled `(every? attr-set reqs)`
walk of an in-memory `{kind reqs}` map. Negligible. **Recommendation:** mark
`:seon.schema/required-attrs` with `:db/index true` if we ever cross ~1K schemas,
or alternatively cache the `{kind reqs}` snapshot in a `defonce` atom rebuilt on
every `register!`. Either is fine — at our size class the datalog path is plenty.

The proposal's "AVET sub-millisecond" is correct in spirit (it's well under 1ms
at our actual sizes); the underlying index is AEVT not AVET unless we mark
`:db/index true`. No reason to either fix or hide this — performance is fine.

## C. The kind-lookup query — working form

```clojure
;; Step 1 (cached at register-time): totals per kind
;; {:seon.eval 4 :seon.fn 3 :seon.mixed 2}
(def totals
  (into {} (d/q '[:find ?k (count ?req)
                  :where [?s :seon.schema/key ?k]
                         [?s :seon.schema/required-attrs ?req]]
                @conn)))

;; Step 2 (hot path, per render): matched count per kind for THIS entity
(defn kind-for [conn entity]
  (let [entity-attrs (vec (keys entity))
        matched (into {} (d/q '[:find ?k (count ?req)
                                :in $ [?req ...]
                                :where [?s :seon.schema/key ?k]
                                       [?s :seon.schema/required-attrs ?req]]
                              @conn entity-attrs))]
    (->> totals
         (keep (fn [[k total]]
                 (when (= total (get matched k 0)) [k total])))
         (sort-by second >)
         first
         first)))
```

The clever bit: `:in $ [?req ...]` binds the caller's attr-set as a relation
of one variable. Datalog naturally joins it against the `:seon.schema/required-attrs`
column, yielding only rows where the required-attr is present in the entity.
`(count ?req)` then gives matched count per `?k`. No custom predicate needed,
no `contains?` clause. This is the idiomatic shape.

**The original proposal's `[(contains? ?present-set ?req)]` does NOT work** —
`?present-set` would have to be bound as a value via `:in`, and datahike-cljs
errored on the same with `:find and :with should not use same variables`.
The `:in [?req ...]` collection-binding shape is what works.

## D. Specificity metric — "all matched, max total"

Two candidates: an entity that has `{:seon.eval/id … :seon.eval/source …
:seon.eval/ok? … :seon.fn/sym … :seon.fn/arglists …}`. Both `:seon.eval`
(3 required) and `:seon.fn` (2 required) are fully matched.

| Metric | Picks | Reasoning |
|--------|-------|-----------|
| absolute count of matched required | `:seon.eval` (3 > 2) | More attrs satisfied = more specific |
| fraction matched/total | tie (1.0 vs 1.0) | useless tiebreaker |
| explicit priority attr | depends | extra metadata, ad-hoc |

**Pick: absolute count of matched required attrs.** All required must be
present (partial match = no match — a schema is a contract). Among complete
matches, the one demanding more is more specific.

Edge case: ties (two schemas with identical required-attrs sets). Live in the
probe: `:probe.fn` and `:probe.mixed` both have 2 required attrs and `:probe.mixed`
matches with `{:probe.eval/id :probe.fn/sym}`. Tie-breaker is `:seon.schema/key`
namespace specificity — falls out of `(sort-by second >)` deterministically by
input order, but for stability, add `(sort-by (juxt second :seon.schema/key) >)`.
Document the tiebreaker; don't rely on iteration order.

## E. Drop `:seon.schema/optional-attrs`

The proposal lists it. Probed: it isn't read by the kind-lookup, it isn't
read by any current render fn, and it isn't needed to disambiguate kinds.
The optional/required distinction lives in the source Malli schema; if a
future caller wants it, they can read it from `(m/children …)` directly.

Storing it as `:db.cardinality/many` keywords would double the schema-entity
row count and add zero query power. **Drop.**

## F. Self-describing bootstrap — no chicken-and-egg

`:seon.schema/key` and `:seon.schema/required-attrs` are leaf attrs (string/
keyword scalars, cardinality-many of keywords). Their datahike registration
goes through `malli->datahike-attr`, which depends only on the existence of
the Malli registrations for those attrs — not on any `:seon.schema` ENTITY
yet. Order:

1. **Load `seon.schema`** — registers `:seon.db/ref`, `:seon.db/id`, etc.
2. **Load `seon.db`** — registers `:seon.db/tx-data` etc. Imports `:seon.schema`.
3. **`register!` augments to write schema entities.** It does:
   ```clojure
   (defn register! [k v]
     (swap! *schemas assoc k (with-entity-id-attr v))
     ;; NEW: write a :seon.schema entity
     (when-let [conn (current-conn)]   ;; may be nil during boot
       (let [reqs (required-attrs v)]
         (seon.db/transact!
           {:seon.db/tx-data
            (into [[:db/add -1 :seon.schema/key k]]
                  (for [r reqs]
                    [:db/add -1 :seon.schema/required-attrs r]))})))
     k)
   ```
4. **Pre-conn registrations are queued.** Until `seon.db/*conn*` is bound,
   buffer the `:seon.schema` writes in an atom and flush them at session
   start. Same shape as the existing schema-entity boot in `seon.client/start-agent!`.
5. **`:seon.schema` describes itself.** When `register!` fires for
   `:seon.schema` (the entity-shape map), `required-attrs` extracted from its
   own children includes `:seon.schema/key`. The handler writes a row for
   `:seon.schema` listing its own required-attrs. The cycle resolves naturally —
   reading the data later, `:seon.schema` is just another schema entity.

Datahike does the same trick for itself: `(:db/ident :db/ident)`, `(:db/ident
:db/valueType)`, etc. are persisted as datoms with the very attrs they describe.
The system fixed-points cleanly.

## G. Single-tx vs multi-tx — single, with cardinality-many

REPL-verified shape (tested above):

```clojure
[[:db/add -1 :seon.schema/key :seon.eval]
 [:db/add -1 :seon.schema/required-attrs :seon.eval/id]
 [:db/add -1 :seon.schema/required-attrs :seon.eval/source]
 [:db/add -1 :seon.schema/required-attrs :seon.eval/ok?]]
```

One entity per kind, multiple `:seon.schema/required-attrs` datoms via
cardinality-many. Reverse-lookup ("which schemas list `:seon.eval/id` as
required?") falls out trivially:

```clojure
(d/q '[:find ?k :in $ ?attr
       :where [?s :seon.schema/required-attrs ?attr]
              [?s :seon.schema/key ?k]]
     @conn :seon.eval/id)
```

Useful for the agent: "what kinds use this attr?" — discoverability win.

**Map-shorthand alternative** (`{:seon.schema/required-attrs #{:a :b}}`) errored
in the probe: datahike-cljs rejected the vector-of-keywords value with `Bad
entity value … must conform to: keyword?`. The cardinality-many container needs
to flatten via `:db/add` tuples (or `[:db/add e a v1] [:db/add e a v2]` works).
This is a pure shape issue and the multi-`:db/add` form is the standard.

## H. Open revisions / honest uncertainty

1. **The entity-shape schemas need filling out.** The current `:seon.eval` /
   `:seon.fn` / `:seon.message` etc. registrations enumerate ZERO non-id children
   (see Section A finding). For `required-attrs` to mean anything we have to fix
   that. Cost: ~5 schema edits in `agent.cljs` and `client.cljs`. Benefit: the
   schemas describe the entity shape correctly — they're currently lying.

2. **Should the schema entity also carry the render-fn symbol?** Currently
   `(m/properties (m/schema :seon.eval))` returns `{:seon.render/ai 'sym
   :seon.render/html 'sym :seon.entity/id-attr :seon.eval/id}`. If we have a
   `:seon.schema` entity already, do we also persist `:seon.schema/render-fn`?
   I'd argue **no for now** — Malli's `m/properties` is the source of truth and
   we don't need to duplicate. The schema entity exists to make required-attrs
   QUERYABLE; render-fn lookup is a property read on the Malli registry, which
   is in-memory and free. If we ever want a cross-pod / agent-discoverable
   render-fn registry, persist it then. YAGNI now.

3. **The kind-lookup is for entities that arrive with a `db` snapshot.** If the
   renderer wants to enumerate "all entities of kind K," that's a separate query
   path (`(d/datoms db :aevt id-attr)` per the entity-kind discrimination
   research). The proposed schemas-as-data design supports both: given an
   entity-attrs set → which kind?; given a kind → which `id-attr` to walk?
   (read from the schema entity).

4. **Performance trade-off vs in-memory atom.** A `defonce kind->reqs` atom
   rebuilt on every `register!` would give 83µs lookups vs the 100µs datalog
   path. We pick datalog because (a) the substrate's own state lives in the DB
   (per CLAUDE.md "code as data" — the runtime IS the database), (b) cross-agent
   visibility falls out for free, (c) the diff isn't visible at production size.

5. **Migration.** No migration concerns per the task. Fresh schema. Existing
   `:seon.eval`/etc. entities don't need updating; only the entity-shape `:map`
   schemas need filling out so future writes index correctly. The `:seon.schema`
   entities materialize lazily as `register!` calls fire on boot.

## I. Final recommendation

**Adopt the proposed shape, with two revisions:**

1. **Fill out the entity-shape map schemas first** (`:seon.eval`, `:seon.fn`,
   `:seon.message`, `:seon.ns`, `:seon.schema`). Each `:map` must enumerate the
   attrs that constitute the kind's contract, with `{:optional true}` for any
   that aren't required. This is a pre-requisite and a desirable cleanup
   independent of this design.
2. **Drop `:seon.schema/optional-attrs`.** Single attr `:seon.schema/required-attrs`
   is enough for the lookup. The Malli schema retains optional info for any
   other consumer.

Persist one `:seon.schema` entity per registered entity-kind, with
`:seon.schema/key` (keyword, identity) and `:seon.schema/required-attrs`
(cardinality-many keyword). Kind-lookup is the two-query datalog above; cache
totals at register-time; pick the schema with all-required-matched and max-total.

The PRD slot this goes into is `ctx-render-strategies-prd.md` (`docs/prds/
agent-runtime/architecture/`) — the "schema-level render-fn dispatch" path that
prior research (`entity-kind-discrimination-2026-05-26.md`,
`malli-schema-defaults-2026-05-26.md`) laid the groundwork for. With schemas
queryable, the renderer's `(d/datoms db :aevt :seon.render/ai)` walk gets
replaced by a datalog over `:seon.schema/required-attrs` + an
`(m/properties (m/schema kind))` lookup for the render-fn symbol.

## J. Probes that failed (honesty)

- First two probe attempts crashed silently because `(def …)` inside an
  `^:async` fn doesn't survive the promise boundary in this CLJS environment.
  Switched to a `defonce probe-stash` atom; that pattern worked. Lesson for
  future REPL probes: never `def` inside async fns — use an atom stash.
- Initial `(:store {:backend :mem})` errored — needs `:memory`, not `:mem`.
  And store `:id` needs `random-uuid`, not a string. Both fixed.
- Initial query used `(into {} (d/q …))` with `:with ?req` — datahike-cljs
  rejected with `:find and :with should not use same variables`. Removed `:with`;
  the `(count ?req)` aggregate handles the same purpose because the datalog
  collection-binding `[?req ...]` already gives a per-row binding.
- Initial map-shorthand `{:probe.schema/required-attrs [:a :b :c]}` rejected
  with `Bad entity value`. Switched to per-attr `[:db/add tid :seon.schema/
  required-attrs k]` tuples; that worked.

All probes ran in pod `default` session at port 7889; nothing in the running
pod's state was disturbed (probe-stash atom; in-memory backend; fresh conn per
probe-boot).
