---
type: research
status: draft
tags: [research, database, schema]
---

# Ref Model Research — Datahike Bridge, Tempids, and `:seon.db/ref`

Research grounding for redesigning the `:seon.db/ref` Malli marker, the bridge mapping, and the `tu/transact-full-graph!` stub-then-fill workaround. All probes run against `datahike` `:memory` on the live orchestrator REPL; all behavior claims are quoted from REPL results. Source citations are line-numbered from `seon/reference-code/datahike/`.

## Headline findings (TL;DR)

1. **Datahike DOES resolve same-tx tempids for refs, both as `:db/id` and as ref-attr values, in any order — including cycles.** This is Datomic-compatible. The `tu/transact-full-graph!` stub-then-fill pattern is unnecessary if the helper uses tempids. See Probe 1.
2. **Datahike does NOT resolve lookup-refs (`[:my/id "a"]`) against same-tx not-yet-committed entities.** Lookup-refs require the target to be in db-after at the moment the value is resolved. Order matters; if the target appears earlier in the same tx (and gets committed before the reference is processed), it works — otherwise `Nothing found for entity id`. See Probes 2a/2b.
3. **`:db.type/uuid` is a plain value type with NO ref semantics.** A raw UUID written into a `:db.type/ref` attribute is rejected (`Expected number or lookup ref for entity id`). Refs require either a pos-int eid, a tempid, or a `[attr value]` lookup-ref tuple. Therefore the seon bridge mapping `:seon.db/ref → :db.type/uuid` produces a UUID-valued scalar, NOT a datahike ref — cross-store routing is purely a seon convention layered on top via `pull-deep` + ref-registry. See Probe 4.
4. **`:db.unique/identity` enables upsert; `:db.unique/value` rejects duplicates.** Standard Datomic semantics. See Probe 7.
5. **Pull returns refs as `{:db/id N}` maps**, or nested entity maps with a recursive selector. See Probe 6.

## Source-code primary evidence

The intra-tx resolution loop sits at `reference-code/datahike/src/datahike/db/transaction.cljc:865–949`. Two lines are load-bearing:

- Line 920–930: when entity `e` is a tempid (string or neg-int), datahike either resolves it to an upsert hit (via `:db.unique/identity`), or allocates a fresh eid, and substitutes everywhere in the remainder of the tx.
- Line 932–935: when an attribute is a ref and its value is a tempid, datahike substitutes from `tempids` if known; otherwise it allocates `(next-eid db)` forward and prepends the tempid to the work queue. **This is the order-independence mechanism.**

The `tempid?` predicate at line 65–68:
```
(defn- tempid? [x]
  (or (and (number? x) (neg? x)) (string? x)))
```

Lookup-ref resolution sits in `reference-code/datahike/src/datahike/db/utils.cljc:105–135` (`entid`). It looks up `[attr value]` against the live `:avet` index — which only sees datoms already committed to db-after. So same-tx lookup-refs work only if the target was processed earlier in this tx's reduction loop.

## Probe results (verified live)

### Probe 1 — tempid forward refs and cycles
Schema: `:my/id` (`:db.unique/identity`, `:db.type/string`), `:my/refs` (`:db.type/ref`, cardinality-many).

**1a (string tempids, cycle a↔b):**
```
(d/transact c1 [{:db/id "a" :my/id "a" :my/refs ["b"]}
                {:db/id "b" :my/id "b" :my/refs ["a"]}])
=> {:tempids {"a" 3, "b" 4, :db/current-tx 536870914},
    :datoms [[3 :my/id "a"] [3 :my/refs 4] [4 :my/id "b"] [4 :my/refs 3]]}
```
**1b (negative-int tempids):** identical result with `:db/id -1`/`-2`. Datahike supports both forms.

### Probe 2 — lookup-refs same-tx

**2a (target appears first in tx):**
```
(d/transact c2 [{:my/id "a"}
                {:my/id "b" :my/refs [[:my/id "a"]]}])
=> :datoms [[3 :my/id "a"] [4 :my/id "b"] [4 :my/refs 3]]   ;; OK
```

**2b (target appears second — forward lookup-ref):**
```
(d/transact c2b [{:my/id "b" :my/refs [[:my/id "a"]]}
                 {:my/id "a"}])
=> ExceptionInfo "Nothing found for entity id [:my/id \"a\"]"
   :error :entity-id/missing
```

**2c (same as 2b but with string tempid instead of lookup-ref):**
```
(d/transact c2c [{:db/id "b" :my/id "b" :my/refs ["a"]}
                 {:db/id "a" :my/id "a"}])
=> :tempids {"b" 3, "a" 4}   ;; OK — order-independent
```

This is the central finding. Lookup-refs are order-dependent; tempids are not.

### Probe 3 — UUID identity attr, lookup-ref vs raw UUID
Schema: `:my/uuid` (`:db.unique/identity`, `:db.type/uuid`), `:my/ref` (`:db.type/ref`).

**3a (explicit lookup-ref form):**
```
(d/transact c3 [{:my/name "bob-lookup" :my/ref [:my/uuid u]}])
=> :datoms [[5 :my/name "bob-lookup"] [5 :my/ref 4]]   ;; resolved to eid 4
```

**3b (raw UUID as ref value):**
```
(d/transact c3 [{:my/name "bob-raw" :my/ref u}])
=> ExceptionInfo "Expected number or lookup ref for entity id, got #uuid \"…\""
   :error :entity-id/syntax
```

Datahike does NOT auto-resolve a UUID against an identity attr at ref-write time. You must use the explicit `[:my/uuid u]` form.

### Probe 4 — `:db.type/uuid` as plain value
Schema: `:my/external-uuid` (`:db.type/uuid` only, NO `:db.type/ref`).
```
(d/transact c4 [{:my/name "bob" :my/external-uuid u4}])
(d/pull @c4 '[*] bob-eid)
=> {:db/id 3, :my/external-uuid #uuid "…", :my/name "bob"}
```
UUID stored as-is, returned as-is. No ref semantics. This is what seon's `:seon.db/ref → :db.type/uuid` bridge actually produces today.

### Probe 6 — pull return shape
```
(d/pull @c6 [:my/refs] peid)         => #:my{:refs [#:db{:id 4} #:db{:id 5}]}
(d/pull @c6 '[*] peid)               => {:db/id 3, :my/id "p", :my/refs [#:db{:id 4} #:db{:id 5}]}
(d/pull @c6 '[:my/id {:my/refs [:my/id]}] peid)
   => #:my{:id "p", :refs [#:my{:id "x"} #:my{:id "y"}]}
```

### Probe 7 — `:db.unique/identity` vs `:db.unique/value`
- Identity: second tx with same value upserts; `:my/name` flips from `"first"` to `"second"`.
- Value: second tx with same value throws `unique constraint: :transact/unique`.

---

## A. What `:seon.db/ref` should mean

**Proposal:** rename intent. `:seon.db/ref` becomes an **intra-DB ref**, mapping to `:db.type/ref`. Cross-DB references stop pretending to be refs at the Malli level; they're declared as `:uuid` (with optional `:seon.db/ref-to <ns>` metadata for the seon-side `pull-deep` walker). This matches reality — Probe 4 already proves datahike treats `:seon.db/ref → :db.type/uuid` as a scalar, not a ref.

Mapping table:

| Malli marker | Datahike `:db/valueType` | Meaning | Write shape |
|---|---|---|---|
| `:seon.db/ref` | `:db.type/ref` | Intra-DB entity reference | tempid (string or neg-int), pos-int eid, or `[unique-attr value]` lookup-ref |
| `:uuid` + `{:seon.db/ref-to :seon.foo}` | `:db.type/uuid` | Cross-DB UUID handle | raw UUID; resolution via seon's `pull-deep` |

Sean's framing — "no legacy. uuid or pos-int. avoid `:any`" — is satisfied: the intra-DB marker accepts pos-ints and tempids (and lookup-ref tuples, which datahike requires for the lookup-ref API); the cross-DB case is just `:uuid` and needs no marker at all.

## B. Do we need a second marker?

**No.** The current `:seon.db/local-ref` proposal in remaining.md smell #10 was framed as "add a marker for intra-DB so we can keep the existing `:seon.db/ref → :db.type/uuid` mapping for cross-DB." Probe 4 falsifies the premise: the current mapping produces NOT a ref but a UUID-valued scalar. Calling that a "ref" misleads readers about what datahike is doing.

The right separation is one marker (intra-DB ref) and zero markers (cross-DB UUID — plain `:uuid` is enough; `:seon.db/ref-to` metadata names the target store for the pull-deep walker). Whether to also keep a `:seon.db/cross-ref` *marker* (different schema entry, but identical bridge output to `:uuid`) for self-documentation is a smaller call — recommend skipping it; the `:seon.db/ref-to` property on a `:uuid` attr already self-documents.

## C. Canonical write shape

Inside a single tx, against a `:seon.db/ref` (= `:db.type/ref`) attribute, the supported values are:

| Want to say | Write this | Notes |
|---|---|---|
| "ref this existing entity by entity-id" | `42` (pos-int) | Rarely used at call sites; mostly internal |
| "ref this existing entity by its unique attr" | `[:seon.shape/id "abc"]` | Lookup-ref; requires target already in db-after |
| "ref this entity also created in this tx" | `"shape-1"` or `-1` (tempid) | Order-independent; cycles OK |
| "ref an entity in another DB" | raw `:uuid` on a `:uuid`-typed attr | NOT a datahike ref; seon's pull-deep follows it |

Mixing forms in one entity map is fine. The recommended **default** for code that constructs tx-data programmatically (like `seon.graph.ingest`) is **string tempids derived from the entity's natural key** — e.g. `(str "shape:" id)` — because:
- Order-independent (Probe 1 + 2c)
- Identity-aware: if the same tempid string appears twice in the tx, datahike resolves both to the same allocated eid
- Cooperates with `:db.unique/identity` upsert (Probe 7) — the natural-key attr resolves the upsert and the tempid threads it through.

## D. Does the stub-then-fill pattern need to exist?

**No, not for the reason given.** `tu/transact-full-graph!`'s docstring (line 261–263) says "Datahike resolves lookup-refs only against pre-existing entities, not same-tx tempids — so we must transact shape stubs (id-only) before entries, then fill shapes afterwards." Probe 1a directly refutes this when the helper uses **tempids**, not lookup-refs. With tempid-based refs, the whole 9-category graph could go in one tx, including the shape↔entry cycle:

```clojure
[{:db/id "shape:s1" :seon.shape/id "s1" :seon.shape/entries ["entry:e1"]}
 {:db/id "entry:e1" :seon.entry/key :k :seon.entry/value-shape "shape:s2"}
 {:db/id "shape:s2" :seon.shape/id "s2" :seon.shape/entries [...]}
 ...]
```

Today's helper uses lookup-refs (the values inside `::extract/shapes` are `[:seon.shape/id "s1"]` tuples). To remove the stub-fill pattern, the helper needs to:
1. Switch to string-tempid form at construction (`:db/id (str "shape:" id)` on each entity), AND
2. Rewrite intra-graph references from lookup-ref tuples to the matching tempid strings.

That's a one-pass transformation on the extracted graph, then a single `db/transact!` of the whole shebang. The helper's category-ordering logic stays (for schema-readability / partial-graph use), but the stub-fill split goes away.

There's a secondary concern: when **`seon.graph.extract` itself emits the refs as lookup-refs**, fixing the helper without fixing the extractor leaves a discontinuity. Cleanest path is to either (a) have the extractor emit tempids, or (b) have the helper rewrite lookup-refs → tempids at transact time. (b) is the safer in-place fix.

## E. Malli predicate shape for `:seon.db/ref`

Given (A), the bridge maps `:seon.db/ref → :db.type/ref`. The predicate guards `seon.db/transact!`'s `validate-values!` gate. Datahike will accept any of {pos-int, neg-int, string, `[keyword value]` lookup-ref tuple} at the value position of a ref attr, then resolve internally. So:

```clojure
(register! :seon.db/ref
  [:or
   [:int]                                ;; pos-int eid OR neg-int tempid
   :string                               ;; string tempid
   [:tuple :keyword :any]])              ;; lookup-ref [attr value]
```

Notes:
- Drop the `pos-int?` restriction — neg-int tempids are valid.
- Drop the `:uuid` extension proposed in smell #18. With `:seon.db/ref → :db.type/ref`, UUIDs don't belong here. Cross-DB UUID values use the `:uuid` Malli type directly (with `:seon.db/ref-to` metadata for routing).
- `[:tuple :keyword :any]` is the lookup-ref shape. Sean's anti-`:any` framing is reasonable, but tightening the second element requires re-deriving the target attr's Malli type at validation time — possible but a bigger lift. Recommend `:any` here as the pragmatic v1; revisit if real bugs emerge.

If Sean wants the absolutely minimal form ("no `:any`, just `:int` or `:string`"), the lookup-ref tuple shape drops from the predicate; callers either pre-resolve lookup-refs to eids themselves, or use tempids — which we already established is the better default. That's a defensible v1: **only pos-int eids and tempids accepted at the validate gate**, lookup-refs disallowed via Malli. Datahike still handles them if a caller bypasses `seon.db/transact!`, but the public API encourages tempid use. Sean's call.

## F. Migration cost (call sites)

Files declaring or consuming `:seon.db/ref` in `src/`:

- `src/seon/schema.clj:84` — the registration itself; predicate body changes.
- `src/seon/runtime.clj:187, 244` — `:seon.agent.run/runtime` ref.
- `src/seon/graph/extract.clj:223` — emits ref-typed values.
- `src/seon/graph/ingest.clj` — 13 callers (lines 59, 60, 73, 74, 96, 104, 108, 109, 138, 139, 140, 141, 148, 149, 193, 204).
- `src/seon/db/datahike/schema.clj:176` — the bridge case for `:seon.db/ref`. **Changes from `:db.type/uuid` to `:db.type/ref`.**
- `src/seon/db/schema.clj:130` — legacy datalevin bridge, slated for deletion (cluster 1+3 in remaining.md). No action.

Plus the `:or {:seon.db/value-type :db.type/ref}` workaround sites added during stage-2.1 test migration (smell #10):
- `test/seon/health/workout_test.clj:58, 60` — two sites; revert after the marker change.

The `:seon.db/value-type` escape hatch in `src/seon/db/datahike/schema.clj:183–195` for `:or` schemas stays (Probe doesn't speak to this; it's a separate concern for genuinely polymorphic value attrs). It just won't be needed for refs anymore.

**Net migration:** one bridge line, one Malli predicate, two test workarounds reverted. `seon.graph.ingest`'s 13 declarations don't change (the marker name stays `:seon.db/ref`; only its meaning shifts). What DOES change is the runtime contract for those attrs — they now hold eids/tempids/lookup-refs, not UUIDs. Anyone writing ref values into those attrs needs to use a supported form. **Production code today already does this** (the values seen in workout-test are `[:seon.shape/id "abc"]` lookup-ref tuples — datalevin-shaped — that the predicate happens to accept). So the predicate change is a tightening, not a re-shape.

The five dropped `pipeline_test` ref-roundtrip tests (smell #18) can be restored once the predicate accepts ref shapes that match the new bridge output.

## Side note — naming hygiene

The `:seon.db/ref` symbol has carried three meanings across the migration: datalevin entity-id (`:db.type/ref`), cross-DB UUID (Decision 6), and the proposed intra-DB-ref-via-:uuid hybrid. If the team takes the proposal above, this is the third rename and worth a one-line ADR (or PRD addendum) so the next agent doesn't infer wrong from history. A clean break: `:seon.db/ref` ALWAYS means intra-DB `:db.type/ref`; cross-DB handles use `:uuid` + `:seon.db/ref-to` and never claim to be refs at the Malli layer.
