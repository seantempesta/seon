---
type: research
status: active
tags: [research, database, schema]
---

# Context-plan storage in datahike

## 1. TL;DR

Use **option (c) extended with a composite tuple index**: model each plan entry as a top-level entity with attributes `:seon.plan.entry/plan` (ref to the parent agent/plan), `:seon.plan.entry/position` (long), `:seon.plan.entry/key` (keyword), `:seon.plan.entry/source-kind` (`:literal | :computed`), `:seon.plan.entry/source-value` (the EDN-encoded source string or literal value, typed via `:or`), plus a composite-tuple attribute `:seon.plan.entry/plan+position` declared via `:db/tupleAttrs [:seon.plan.entry/plan :seon.plan.entry/position]` with `:db/index true` and `:db/unique :db.unique/identity`. The parent agent reaches entries by reverse-ref pull (`(:seon.plan.entry/_plan ...)`) sorted on `:seon.plan.entry/position` — or, for large plans, by a single `d/datoms :avet :seon.plan.entry/plan+position [plan-id]` range scan that returns entries in position order directly out of the HitchHiker tree. Reorder/insert costs are bounded by how many entries you touch (renumber-after-insert is the only honest weakness), every other property of the wishlist falls out for free.

## 2. The structure being optimized

A seon agent has a **context plan** — an ordered vector of `[key source]` pairs. Each entry is either a literal value (`[:seon.agent.ctx/instructions "You are the agent..."]`) or a computed entry whose `source` is a Clojure-fn string that takes the accumulator-so-far and returns a value (`[:seon.agent.ctx/recent-messages "(fn [acc] (d/q '[...] @(:conn acc)))"]`). The loop reduces over the plan in order; later steps see earlier results.

Desired properties for the persisted form:

1. Every entry is a Malli-spec'd entity (key spec, value-type spec, source spec).
2. Ordering preserved across reads and history.
3. Independent updates — one entry changes → only that entry's datoms are touched.
4. Per-entry queryable (single entry by key or position).
5. Datahike-native efficiency — leverage the HitchHiker tree's structural sharing.
6. Per-entry history — `as-of` for "what was at index 3 a week ago".
7. Reasonable update cost — insert/remove/reorder should not rewrite the whole plan.

## 3. Datahike internals relevant to this question

**Three indexes — EAVT, AEVT, AVET — no VAET.** `empty-db` builds exactly these three on top of the configured index backend (`reference/datahike/src/datahike/db.cljc:871-880`). `:db.type/ref` attrs are stored in EAVT/AEVT and (if `:db/index true` or declared via `:db/unique`) AVET — there is no separate value-to-entity index, so reverse-ref lookups must hit EAVT or use a query (`reference/datahike/src/datahike/index/persistent_set.cljc:31-33`). The AVET index is filtered at construction to attrs the schema marks as indexed (`reference/datahike/src/datahike/db.cljc:867-880`); refs, uniques, and `:db/index true` qualify (`reference/datahike/src/datahike/db/utils.cljc:279-294`).

**Cardinality:many = a set of datoms with the same `[e a]`, distinct `v`.** Transactions on multivals dispatch through `maybe-wrap-multival` (`reference/datahike/src/datahike/db/transaction.cljc:449-453`); each value lands as its own datom keyed by `[e a v]`. There is no order metadata — readback order is whatever the index spits out (EAVT sorts by `v` within `[e a]`). This is fatal for an ordered plan and rules out the naive cardinality-many design.

**`:db/isComponent` is for cascading retract only**, not order (`reference/datahike/src/datahike/db.cljc:775-781`, `reference/datahike/src/datahike/db/utils.cljc:38`). It does not buy us anything for sequencing.

**Tuples are first-class.** Schema accepts `:db/tupleAttrs` (composite, derived from peer attrs), `:db/tupleTypes` (heterogeneous, fixed length 2–8), and `:db/tupleType` (homogeneous, up to 8 values) (`reference/datahike/src/datahike/schema.cljc:65,140-145`, `db/transaction.cljc:720-746`). For composite tuples datahike auto-maintains the tuple value when any constituent attr is asserted (`db/transaction.cljc:273-279`, `:408-440`) — you never write the tuple directly, you write its parts.

**Composite tuples in AVET are sorted lexicographically and support prefix range scans.** The tuples test demonstrates `d/datoms :avet :a+b+c ["A" "b" nil]` and `d/index-range` over a `[start end]` window (`reference/datahike/test/datahike/test/tuples_test.cljc:544-564`). This is the load-bearing feature for ordered plans: a composite `[plan-eid position]` indexed in AVET keeps all entries of a given plan contiguous, ordered by `position`.

**HitchHiker-tree structural sharing.** Datahike's tree-backed indexes use path-copying: updates to one key rewrite the leaf and the path of inner nodes back to the root, leaving the rest of the tree shared with the previous version. The `UpsertOp` in `reference/datahike/src-hitchhiker-tree/datahike/index/hitchhiker_tree/upsert.cljc:60-72` removes the old key and inserts the new one inside a single tree-op, and the insert delta is small. Practical implication: asserting one new datom or retracting one existing datom in a tree of N entries is `O(log N)` in nodes touched, regardless of N. Updating a single attribute is two datoms (retract old + assert new) — never the whole plan.

**`persistent-sorted-set` keying.** Datoms in the persistent-set index are compared with index-specific comparators (`reference/datahike/src/datahike/index/persistent_set.cljc:30-33`), and Datalog-level slicing accepts partially-bound keys (the `slice-from-to-tree` macro). For our purposes this means a `d/datoms :avet :plan+position [plan-id nil nil nil nil]`-style slice runs against the same sorted set without scanning unrelated tuples.

**History / `as-of`.** Datahike maintains separate temporal indexes (`temporal-eavt`, `temporal-aevt`, `temporal-avet`, `reference/datahike/src/datahike/db.cljc:950-952`) when history is enabled (default), so per-entry history is per-entity and works the same for plan entries as for any other entity. `:db/noHistory` is opt-in (`reference/datahike/src/datahike/schema.cljc:115`).

## 4. Options evaluated

### (a) Single EDN-blob attribute on the agent

`:seon.agent/plan` :string, value is `pr-str`'d vector.

- Spec'd: only at the blob boundary; per-entry validation is your code's job.
- Ordered: yes (vector).
- Independent updates: no — any change rewrites the whole datom.
- Queryable per entry: no — must parse the blob.
- Structural sharing: zero within the value; tree still shares paths to unchanged neighbors.
- History: yes, but as full snapshots of the blob.
- Update cost: O(plan-size) bytes per change.

Verdict: easy and tempting, but fails properties 1, 3, 4, 5, and 7. Reject.

### (b) cardinality:many ref set

`:seon.agent/plan-entries :db.type/ref :db.cardinality/many` → entry entities.

Ordering not preserved (§3). Reject without further analysis.

### (c) Per-entry entity with `:position` attribute (BASELINE)

```
:seon.plan.entry/plan      :db.type/ref       cardinality/one  index=true
:seon.plan.entry/position  :db.type/long      cardinality/one  index=true
:seon.plan.entry/key       :db.type/keyword   cardinality/one
:seon.plan.entry/source    :db.type/string    cardinality/one
:seon.plan.entry/kind      :db.type/keyword   cardinality/one   ;; :literal | :computed

```

- Spec'd: yes — each attr registered through `seon.schema/register!`.
- Ordered: yes — sort entries by `:position`.
- Independent updates: yes — touching one entry touches its own datoms only.
- Queryable: yes — `d/q` by `[?e :seon.plan.entry/plan plan-id]` and pull.
- Structural sharing: full HitchHiker sharing across unchanged entries.
- History: native datahike `as-of`.
- Update cost: change-in-place is one retract+assert. Reorder needs to renumber affected positions. Insert at the front means N shifts.

Verdict: solid, passes all properties, weak only on dense renumbering.

### (d) Linked list (`:seon.plan.entry/next :db.type/ref`)

- Insertion: rewires two pointers, cheap.
- Read order: requires walking the list O(N) — no single sorted scan from the index.
- Queryable per index: no; "what's at position 3" means traversal.
- History: works, but the *position* of a historical entry requires reconstructing the chain at that tx — annoying.

Verdict: best for insertion-heavy workloads, worst for "what's at index N" or "give me entries in order". For the agent's read-dominated loop that wants the plan in order each tick, this is the wrong tradeoff.

### (e) Composite tuple `[plan position]` (RECOMMENDED extension of c)

Add to (c):

```
:seon.plan.entry/plan+position
  :db.type/tuple
  :db/tupleAttrs [:seon.plan.entry/plan :seon.plan.entry/position]
  :db/cardinality :db.cardinality/one
  :db/unique :db.unique/identity
  :db/index true

```

The tuple is **derived** — you keep writing `:plan` and `:position` separately and datahike maintains the composite (`db/transaction.cljc:408-440`). Per (§3) the tuple is sorted in AVET lexicographically, so `d/datoms :avet :seon.plan.entry/plan+position [plan-id]` is a contiguous slice in position order.

- Spec'd: yes.
- Ordered: yes, *and* returned in order from a single AVET slice — no in-memory sort needed.
- Independent updates: yes.
- Queryable per entry: yes, by `[plan position]` directly via `d/datoms :avet ... [plan-id pos]` or `d/entity` after a unique lookup-ref `[:seon.plan.entry/plan+position [plan-id pos]]`.
- Structural sharing: full.
- History: yes; the composite is just another indexed attr in temporal-avet.
- Update cost: same as (c). Plus: changing `:position` retracts the old tuple and asserts the new one automatically.

Verdict: strictly dominates (c) for the read path. Same write cost.

### (f) Sparse positions / fractional indexing

Use `:position :db.type/double` and insert between two entries by averaging. No renumbering on insert.

- All (c)/(e) properties hold.
- Insert cost: O(1) — pick the midpoint.
- Risk: floating-point precision degrades after ~50 in-between inserts at the same spot. Mitigation: periodic compaction pass, or use `:string` with lexorank-style strings (`"a"` < `"an"` < `"b"`) at the cost of giving up the homogeneous-tuple type.

Verdict: if reorder-without-renumber is a real requirement, this is the right cherry on top. Otherwise overkill.

## 5. Recommendation

**Adopt option (e): per-entry entity with a composite-tuple index.** Defer option (f) — Plans are short (<20 entries), so renumber-on-insert costs are trivially small and not worth the precision-management overhead of fractional indices yet.

Malli schema (using seon's `schema/register!`):

```clojure
;; in seon.agent.plan (or the consumer's own ns; this file uses :seon.* for example only)
(schema/register! :seon.plan.entry/plan      :seon.db/ref)
(schema/register! :seon.plan.entry/position  :int)
(schema/register! :seon.plan.entry/key       :keyword)
(schema/register! :seon.plan.entry/kind      [:enum :literal :computed])
(schema/register! :seon.plan.entry/source    :string)  ; pr-str'd value or fn source
(schema/register! :seon.plan.entry/plan+position
                  [:tuple :seon.db/ref :int]
                  {:seon.db/tuple-attrs [:seon.plan.entry/plan :seon.plan.entry/position]
                   :seon.db/identity true
                   :seon.db/index true})

```

Tx:

```clojure
(db/transact! :seon
  [{:db/id "plan-entry-0"
    :seon.plan.entry/plan plan-eid
    :seon.plan.entry/position 0
    :seon.plan.entry/key :seon.agent.ctx/instructions
    :seon.plan.entry/kind :literal
    :seon.plan.entry/source "\"You are the agent...\""}
   {:db/id "plan-entry-1"
    :seon.plan.entry/plan plan-eid
    :seon.plan.entry/position 1
    :seon.plan.entry/key :seon.agent.ctx/recent-messages
    :seon.plan.entry/kind :computed
    :seon.plan.entry/source "(fn [acc] (d/q ...))"}])

```

Read the plan in order (single contiguous slice over AVET):

```clojure
(->> (d/datoms @conn :avet :seon.plan.entry/plan+position [plan-eid])
     (map :e)
     (map #(d/pull @conn '[:seon.plan.entry/key
                           :seon.plan.entry/kind
                           :seon.plan.entry/source] %)))

```

Update one entry's source (touches one entry's datoms only):

```clojure
(db/transact! :seon [[:db/add entry-eid :seon.plan.entry/source "new source"]])

```

Insert at position 1, shifting 1→2, 2→3, …: emit retracts+asserts for `:position` on each affected entry in a single tx. For the agent's plan sizes this is cheap; for larger plans, switch to option (f).

Per-entry history (asks the property-6 question for free):

```clojure
(d/pull (d/as-of @conn week-ago) '[*] entry-eid)

```

## 6. Tradeoffs we accept

- **Insert at the head is O(N).** For a 100-entry plan that's 100 retract+assert pairs in one tx — still bounded, still indexed, but visible in tx-data. If real workloads insert at the head frequently, escalate to option (f). the agent's planned plan sizes (single digits to low double digits) make this a non-issue today.
- **Composite-tuple support has to be added to seon's Malli→datahike bridge.** Today `seon/src/seon/db/datahike/schema.clj` does not emit `:db/tupleAttrs`/`:db/tupleType`/`:db/tupleTypes` for any case (verified by grepping the bridge: zero hits). We can either (a) extend the bridge to recognize `:seon.db/tuple-attrs` properties on a `:tuple` schema and emit the appropriate datahike attr-map, or (b) bypass the bridge for this one attribute and register it directly via the datahike schema-txn surface that the migration is standing up. (a) is the right answer long-term and a ~30-line addition; surface it as a follow-up in the migration backlog.
- **The composite tuple is duplicate state.** Datahike maintains it from `:plan` + `:position` automatically, but it's still rows in EAVT/AEVT/AVET. Net cost is one extra indexed datom per entry — cheap.
- **Source field is a string, not a parsed AST.** Per-entry Malli validation can spec it as `:string` plus a custom validator that ensures `read-string` succeeds; runtime eval still happens out of band. If we want richer validation (e.g. "the fn has arity 1"), that's a Malli pred at the entry level, not a schema change.
- **No range queries by `:key`.** If we end up needing "all entries across all plans whose key is `:seon.agent.ctx/recent-messages`", that's a separate AVET index on `:seon.plan.entry/key` — cheap to add later.

## 7. Open questions

- **Bridge gap (code smell).** `seon/src/seon/db/datahike/schema.clj` does not emit any tuple attrs from Malli. The simplest fix is to recognize `[:tuple ...]` schemas in `schema->attr-partial` and forward `:seon.db/tuple-attrs` / `:seon.db/tuple-types` / `:seon.db/tuple-type` properties to `:db/tupleAttrs` / `:db/tupleTypes` / `:db/tupleType`. Path to verify: REPL-build a tiny in-memory db with a composite tuple, transact entries, and confirm `d/datoms :avet plan+position [plan-id]` returns position-ordered entries. Worth ~15 minutes of REPL probing before committing the bridge change.
- **History under composite-tuple changes.** I assume the temporal-avet index logs the auto-maintained composite-tuple datom on each constituent change, so `(d/as-of db t)` correctly reconstructs `plan+position` at time `t`. The transaction code in `db/transaction.cljc:273-279,408-440` queues the tuple alongside the constituents but I did not chase every branch into the temporal index path. REPL probe: assert plan at position 5, change to 6, `as-of` the earlier tx, scan AVET → expect to see position 5.
- **Tuple range scan API surface.** `d/datoms :avet :plan+position [plan-id]` is shown working in `reference/datahike/test/datahike/test/tuples_test.cljc:558-564` with both partially-bound and fully-bound prefixes. We rely on prefix scans returning in tuple order — this is consistent with how `slice-from-to-tree` builds comparators (`reference/datahike/src/datahike/index/persistent_set.cljc:34-…`), but I did not trace every comparator branch for the partially-bound case. REPL probe: 10 entries, mixed plan IDs, scan one plan, confirm contiguous and ordered.
- **Reorder-heavy workloads.** If a consumer turns out to insert at index 0 a lot (rather than appending), we should escalate to option (f) before the renumber tx-size becomes a UX issue. The current default design (start-at-zero + append observations) suggests appends dominate, but worth a sanity check once the agent loop is running on the substrate.
- **Source-string Malli predicate.** Per §6, we want a custom Malli validator on `:seon.plan.entry/source` that confirms `read-string` succeeds and (for `:computed`) that the result is a 1-arg fn form. This is a registration concern, not a datahike concern — flagging it so it doesn't get forgotten when this lands.
