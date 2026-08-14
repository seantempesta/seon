---
type: research
status: active
tags: [research, database, render, datahike, sci, agent, context, wave/evolving-session-prd]
---

# Can we do better than double execution? — general diff, 2026-08-13

Follow-on to
[generic-diff-exploration-2026-08-13.md](generic-diff-exploration-2026-08-13.md),
which settled *what shape* the result takes. This note attacks the three
places a better mechanism could live — Datahike's index internals, a
read-set recorded during evaluation, and an external diff algorithm — and
then states how the winner renders.

**One-line answers.**

- **Datahike internals:** an index-level datom diff between two db values is
  real and cheap — successive db values share **232/235** index nodes by JVM
  identity, and a db value re-materialized from an old commit shares
  **156/157** konserve addresses with the current one — but it is
  **inapplicable to `as-of`**, because `as-of` returns no second tree at all:
  `AsOfDB` wraps the *identical* index objects behind a time predicate
  (measured `identical?` → `true`). A datom diff is therefore an
  **invalidation gate**, never a replacement for executing the read.
- **sci read-tracking:** **real, not fantasy, and it does not belong in sci.**
  A recording read-set already works today through the public API
  (`d/filter` with a recording predicate; measured 750 datoms / 3 attributes
  touched for a 250-row result). The reads happen in Datahike, below sci;
  `seon.sci.eval` records only duration, `fn-entries`, and allocated bytes.
  The honest version records **attributes**, not datoms — per-datom recording
  measured **15×** the cost of the plain execution.
- **Best external algorithm:** **editscript's `:quick` mode, on
  identity-keyed input only.** Measured **3.06 ms** on a 5000-row change
  where `clojure.data/diff` took **13.62 ms** — 4.5× faster, on a strictly
  more general input, returning a minimal path-keyed edit script that is
  simultaneously the diff, the render, and a patch. Zero runtime
  dependencies, 31 KB jar, EPL. It has **no identity keying and no move
  operation of its own**, so handing it a positional collection of
  identity-carrying rows reproduces the exact defect we are trying to fix
  (§3.3).
- **Render integration:** already declared and already correct in shape —
  `seon.db.diff.edn:18` names `seon.db/render-diff-ai` on the result map, and
  `seon.render/candidates` selects it by contract fit. Three defects: no
  `:seon.render/html` producer, the renderer hand-builds strings instead of
  crossing `seon.print/fit`, and the elision reports one estimate for the
  whole result rather than a per-slot elision value.

**Recommendation: design 2** — keep the double execution, replace the
comparison with a vendored editscript edit script **rooted at derived
identity**, keep the loud refusal for identity-free collections, and add the
attribute-level read-set gate only when a measurement demands it. Identity
rooting is what buys both correctness and idempotent joinability (§3.3).

Every transcript below was executed. Probe scripts are committed beside this
note as `scripts/probe_index_identity_2026_08_13.clj`,
`scripts/probe_node_sharing_2026_08_13.clj`,
`scripts/probe_addresses_2026_08_13.clj`,
`scripts/probe_commit_as_db_2026_08_13.clj`,
`scripts/probe_read_set_2026_08_13.clj`,
`scripts/probe_costs_2026_08_13.clj`,
`scripts/probe_editscript_2026_08_13.clj`, and
`scripts/probe_identity_coverage_2026_08_13.clj`; run with
`clojure -M:dev:test -e '(load-file "<path>")'` from the repository root
(the editscript probe needs its own `-Sdeps`, given in §3.1).

## 0. What is already built

`seon.db/diff` exists at HEAD as design B of the prior note:
`src/seon/db.clj:1547-1585` derives the row identity attribute,
`src/seon/db.clj:1607-1636` performs the identity-keyed `clojure.data/diff`,
`src/seon/db.clj:1638-1663` renders it, and
`resources/seon/schemas/seon.db.diff.edn:16-24` declares the result map with
its `:seon.render/ai` producer. Everything below is measured against that
implementation, and every design states its migration from it.

## 1. Datahike internals

### 1.1 `as-of` produces NO second tree — this closes the obvious idea

`as-of-pred` (`reference-code/datahike/src/datahike/db.cljc:142-148`) is a
plain datom predicate, and `AsOfDB`
(`reference-code/datahike/src/datahike/db.cljc:567`) holds only
`[origin-db time-point]`, delegating every search to the origin under
`(context-with-temporal-timepred … (as-of-pred …))` (db.cljc:612-614).

```clojure
{:as-of-record-type datahike.db.AsOfDB
 :as-of-origin-identical-to-db1 true
 :as-of-eavt-identical true}
```

The as-of value's `eavt` **is** the current value's `eavt` — the same JVM
object. There is no pair of trees to co-walk. Any design premised on
"compare the two index values" must first obtain a genuinely separate db
value, which `as-of` does not provide.

### 1.2 Two genuine db values DO share almost everything

Successive db values from one connection are separate index objects with
massive structural sharing. 20 000 entities, then one attribute change:

```clojure
{:node-count-db0 235 :node-count-db1 235
 :identical-nodes-shared 232 :fraction 0.9872340425531915
 :root-identical false}
```

232 of 235 nodes are the *same JVM object*. Only the root-to-leaf path of the
one changed datom was rebuilt. A co-walk that prunes on `identical?` would
visit 3 nodes instead of 235.

The same holds across the store, and across a process restart, because
addresses are assigned only to **new** nodes: `CachedStorage.store`
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:410-425`)
calls `gen-address` (persistent_set.cljc:277-282) once per stored node, and
an unchanged node is never re-stored. Walking konserve addresses of the two
`eavt` roots on a real file store:

```clojure
{:addr-count-db0 235 :addr-count-db1 235
 :shared-addresses 234 :only-in-db1 1}
```

And a db value re-materialized from an old commit id — `commit-as-db`
(`reference-code/datahike/src/datahike/versioning.cljc:469`), reached through
`commit-id` (versioning.cljc:457) and `parent-commit-ids` (versioning.cljc:463) —
is a **real `datahike.db.DB`**, not a filtered wrapper, and still shares
addresses:

```clojure
{:commit-id-0 #uuid "6a7e2ab8-7c48-562a-9f3b-2682035edaed"
 :commit-id-1 #uuid "6a7e2ac0-8981-5d51-bfba-8b6b05773c12"
 :parents #{#uuid "6a7e2ab8-7c48-562a-9f3b-2682035edaed"}
 :old-type datahike.db.DB
 :old-addrs 157 :new-addrs 157 :shared 156 :only-new 1
 :old-eavt-count 40009 :new-eavt-count 40010
 :q-old 0 :q-new 1}
```

So the raw material for a structural datom diff exists in two forms —
identity for in-memory pairs, address for stored pairs — and this holds
**without** `:crypto-hash?`, which Seon does not enable
(`src/seon/cluster/store.clj:151-168` sets `:fuse-index-roots? true` and
`:index-config {:diff-buf-size 256}`, nothing else). Content addressing
(`branch-content-uuid`, persistent_set.cljc:253-270) would additionally make
addresses comparable across *unrelated* stores; that is not needed here.

### 1.3 What the index actually exposes — and what it does not

`IIndex` (`reference-code/datahike/src/datahike/index/interface.cljc:5-24`)
declares slice, rslice, lookup, count-slice, insert, upsert, remove, flush,
mark, root-node, seed-root!. **There is no `-diff`, no set-difference, and no
co-walk.** The persistent-sorted-set extension
(persistent_set.cljc:185-237) adds nothing beyond those. Confirming the prior
note's §2: the api specification enumerates `history`, `since`, `as-of`,
`valid-at`, `valid-between`, `valid-during`, `valid-all` and **no `diff`, no
`tx-range`**.

Two adjacent mechanisms in the fork are worth naming because they are the
building blocks a diff would use:

- **`IMeasure`**
  (`reference-code/persistent-sorted-set/src-java/org/replikativ/persistent_sorted_set/IMeasure.java`)
  is a full monoid interface — `identity`, `extract`, `merge`, `remove`,
  `weight` — cached per node and consumed by `measure`
  (`reference-code/persistent-sorted-set/src-clojure/org/replikativ/persistent_sorted_set.clj:320-331`)
  and `measure-slice` (persistent_sorted_set.clj:401-418, documented
  `O(log n + k)`). It is carried on `Settings`
  (`src-java/…/Settings.java:12,146`). Seon configures none:

  ```clojure
  {:branching-factor 512, :ref-type :soft, :measure nil,
   :leaf-processor nil, :diff-buf-size 0}
  ```

  A `max-tx` measure over `temporal-eavt` would let a "changed since t" walk
  prune every subtree whose max tx ≤ t. `remove` is non-invertible for a max
  measure, but the interface anticipates exactly that with its `recompute`
  supplier.
- **`ISecondaryIndex`**
  (`reference-code/datahike/src/datahike/index/secondary.cljc:16-45`) is a
  declared, schema-driven, in-transaction-maintained pluggable index with
  `-transact` receiving each datom. A tx→entity index is expressible here
  without touching the primary indexes at all.

### 1.4 The cost this would remove, measured

The only mechanism available today for "which datoms changed since t" is a
full history scan (the prior note's §2). Scaling it:

```clojure
{:changed-datoms 3 :scan-ms 17.660666 :history-size 60012}
{:changed-datoms 11 :scan-ms 3.424333 :history-size 6018}
```

Linear in history size, indifferent to how little changed. At 600 k history
datoms this is ~180 ms per question. A node-pruning co-walk between two db
values would answer the same question in time proportional to the *change*,
i.e. ~3 nodes in the measurement above.

### 1.5 Exactly what an index-level diff would take

Three additions, all inside forks we control:

1. **`persistent-sorted-set`:** one co-walk function over two roots, pruning
   when child references are `identical?` or their addresses are `=`,
   emitting keys present on one side only. It needs `Branch.child`,
   `Branch.addresses()`, `Branch.address(int)`, and `Leaf.keys()` — all
   already public (`src-java/…/Branch.java:178,196,212`,
   `src-java/…/ANode.java:73`). Estimated ~150 lines plus tests. The one real
   subtlety is the `diff-buf` slot buffer (Branch.java:313-334,
   `slotsForStorage` at 1756): with `:diff-buf-size 256` a branch may hold
   buffered per-child diffs, so the co-walk must fold slots before comparing,
   exactly as `branch-content-uuid` does (persistent_set.cljc:253-270).
2. **`datahike`:** a `-diff` method on `IIndex` (index/interface.cljc) plus a
   **`tx-range`** operation in the api specification — Datomic's own name for
   the t₁..t₂ delta primitive, taken per the vocabulary law rather than
   inventing one — defined only for two `datahike.db.DB` values of the same
   store lineage and refusing loudly for any filtered/as-of/history wrapper.
   Datomic's `tx-range` is cost-proportional-to-change because Datomic keeps
   a log; Datahike keeps none (§1.3), so the co-walk is how we would earn the
   same property from the indexes we already have.
3. **`seon`:** obtain the second db value from `commit-as-db` rather than
   `as-of`, which means a receipt must record the **commit id**, not only
   `:seon.db/basis-t`. This is the part with the real cost: it is a new
   durable fact on the run receipt, and `release-materialized-db`
   (versioning.cljc:490) must be called, so custody of the materialized value
   becomes someone's job.

**Verdict on front 1.** Build none of this now. Even a perfect datom diff
does not answer "what does this form return differently" — the prior note's
§2 argument stands unchanged, and this note's §1.1 adds that the mechanism
does not even apply to the `as-of` value the current contract uses. Its
genuine use is the *gate* (§2.3), and the gate has a cheaper first
implementation. Record the design; revisit when a measurement shows the gate
is hot.

## 2. The sci / read-tracking front

### 2.1 sci records nothing about reads, and should not

`seon.sci.eval` records `:seon.eval/duration-ms`, `:seon.eval/fn-entries`,
`:seon.eval/allocated-bytes`, `:seon.eval/host-interop-count`, and
`:seon.eval/outcome` (`resources/seon/schemas/seon.sci.admit.edn:31-37`), and
its own header is explicit that entries are a **recorded diagnostic, never a
limit** (`src/seon/sci/eval.clj:22-33`). Nothing about database reads, and
nothing could be: a `d/q` call is one host call from sci's point of view. The
reads happen inside Datahike, below the interpreter. **Read-tracking at the
sci layer would be tracking the wrong events at the wrong altitude.**

### 2.2 Read-tracking at the db-value seam works today, with no new mechanism

Datahike's `FilteredDB` (`reference-code/datahike/src/datahike/db.cljc:415`)
applies a caller-supplied predicate to every datom the search touches. A
predicate that records and returns `true` is a read-set recorder built
entirely from the public api:

```clojure
(def touched (atom []))
(def rec (d/filter db (fn [_db datom] (swap! touched conj [(:e datom) (:a datom) (:tx datom)]) true)))
(d/q '[:find ?id ?t :where [?e :m/read? true] [?e :m/id ?id] [?e :m/text ?t]] rec)
```

```clojure
{:result-count 250
 :touched-datoms 750
 :distinct-entities 250
 :distinct-attrs (:m/read? :m/id :m/text)
 :max-tx-touched 536870914}
```

and for a pull:

```clojure
{:pull {:db/id 7, :m/id "m3", :m/read? false, :m/text "t3"}
 :pull-touched 4}
```

A lower-level seam exists too if the predicate proves too coarse:
`context-with-xform-after`
(`reference-code/datahike/src/datahike/db/interface.cljc:58-63`) attaches an
arbitrary transducer to a search context, so a recording db record is a
30-line `defrecord` delegating `ISearch`/`IIndexAccess`. Neither seam needs a
fork change.

### 2.3 What it costs, and the one thing it cannot see

```clojure
{:single-execution-ms 2.093583
 :double-execution-ms 2.379542
 :double-plus-diff-ms 15.544583
 :recording-execution-ms 31.818875 :read-set-size 15000
 :changed-pairs-scan-ms 5.388084}
```

Three results reorder the whole problem:

1. **The second execution is nearly free.** 2.09 ms → 2.38 ms. The prior
   note framed double execution as the helper's real content and its real
   cost; measured, it is 14 % overhead.
2. **The comparison is the expensive part.** `group-by` + `update-vals` +
   `clojure.data/diff` over 5000 rows costs **13.2 ms** on top — 5.5× the two
   executions combined. Any optimisation effort belongs *here*, which is
   what §3 addresses.
3. **Per-datom read recording costs 15×** (31.8 ms vs 2.09 ms) and produces a
   15 000-element set. As an optimisation it is strictly negative.

And the structural limit: **a datom-level read set cannot see membership
additions.** A newly-transacted entity that now satisfies the query was never
touched by the old execution, so it is absent from the read set by
construction. The read set is only sound as an invalidation gate at
**attribute** granularity — here 3 attributes, not 15 000 datoms — where the
question becomes "did any datom with attribute `a` change since t", one
`aevt` slice per attribute. That recording is nearly free (a `conj` per
distinct attribute), and it is the same shape posh and re-posh use over
DataScript tx-reports.

**Verdict on front 2.** Read-tracking is a real mechanism, available today
without a fork change or a sci change, and it is worth exactly one thing: an
**attribute-level invalidation gate** that answers "nothing this read depends
on changed" without the second execution or the comparison. It is not a diff
and cannot become one. Build it when a measurement shows the diff is hot, not
before — and build it at `seon.db`, never in `seon.sci`.

## 3. External algorithms and libraries

Probe transcripts are mine; library facts come from a dedicated web sweep
whose findings are folded in below, plus pages I fetched directly. Where the
sweep and my measurements disagree, §3.3 reconciles them rather than picking
a side.

### 3.1 editscript, measured

Probed at `juji/editscript {:mvn/version "0.7.0"}` via
`clojure -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.12.5"} juji/editscript {:mvn/version "0.7.0"}}}' -M -e '(load-file "…")'`.

On the prior note's canonical rows (`m1` changed, `m2` removed, `m3`
unchanged but shifted, `m4` added), **as raw vectors**:

```clojure
[[[0 :m/read?] :r true] [[1] :-] [[2] :+ #:m{:id "m4", :text "fourth", :read? false}]]
```

This is the decisive contrast with `clojure.data/diff`, which the prior note
proved misreports the shifted `m3` on exactly this input. editscript's A*
mode **aligned `m3` across the removal** and reported three edits, no false
positive — on positional input, with no identity supplied. Its `:quick` mode
does not:

```clojure
[[[0] :-] [[0 :m/id] :r "m1"] [[0 :m/text] :r "hello"] [[0 :m/read?] :r true]
 [[2] :+ #:m{:id "m4", :text "fourth", :read? false}]]
```

Keyed by identity, both modes agree and the paths become readable:

```clojure
[[["m1" :m/read?] :r true]
 [["m2"] :-]
 [["m4"] :+ #:m{:id "m4", :text "fourth", :read? false}]]
```

with the classification counters free, a patch round-trip, and — the property
design A failed on — **empty output when nothing changed**:

```clojure
{:keyed-size 19 :adds 1 :dels 1 :reps 1}
{:patch-roundtrip true}
{:no-change-edits []}
{:core-diff-no-change [nil nil {"m1" #:m{…} "m3" #:m{…} "m4" #:m{…}}]}
```

At scale, 5000 rows with one attribute changed:

```clojure
{:editscript-5000-quick-ms 3.059167 :edits [[["m5" :m/read?] :r true]]}
{:editscript-5000-astar-ms 45.469   :edits [[["m5" :m/read?] :r true]]}
{:core-diff-5000-ms 13.619625 :only-a-keys ("m5")}
```

`:quick` is **4.5× faster than `clojure.data/diff`** and returns one edit
instead of a triple whose third slot is the entire unchanged collection. A*
is 3.3× slower than core and must be bounded if ever used.

Dependency weight, read from the fetched pom
(`~/.m2/repository/juji/editscript/0.7.0/editscript-0.7.0.pom`): **runtime
dependencies are `org.clojure/clojure` alone** — clojurescript, rrb-vector,
criterium, doo, and test.check are all `<scope>test</scope>`. Jar is
**31 198 bytes**. License **EPL**. The README (github.com/juji-io/editscript)
states CLJ, CLJS, .NET, and babashka support, an `[path op value]` edit
format with `:-`/`:+`/`:r`/`:s` operations and `update-in`-shaped paths, and
that `:quick` uses Wu et al. 1990 O(NP) sequence comparison — a real sequence
alignment, which is why the vector case above came out right.

### 3.2 The library table

| Candidate | Algorithm | Output shape | Pairs by | Cost (5000 rows, 1 change) | Weight | Renders? |
|---|---|---|---|---|---|---|
| `clojure.data/diff` (in use today) | recursive same-partition walk; vectors by index | `[only-a only-b both]` triple | **position** (vectors), key (maps) | 13.62 ms | core | **poorly** — the no-change case returns the entire collection in slot 3 |
| **editscript `:quick`** | Wu et al. O(NP) sequence alignment + structural walk | `[[path op value]…]`, patchable | position, or identity when keyed | **3.06 ms** | 31 KB, zero runtime deps, EPL | **excellently** — counts + one line per edit |
| editscript `:algo :astar` | A* over the edit graph, size-optimal | same | same, better aligned | 45.47 ms | same | same |
| `lambdaisland/deep-diff2` 2.14.235 | recursive, clj-diff underneath | `Insertion`/`Deletion`/`Mismatch` records inline in the structure | **position** | not probed | pulls clj-diff, clj-arrangements, puget, fipp | for humans only — its own README says editscript suits programmatic use better; no patch |
| extending `clojure.data`'s `Diff`/`EqualityPartition` protocols | ours | inherited three-tuple | ours (a record could pair by key) | — | none | inherits the weak triple |
| `java-diff-utils` 4.17 (Myers + HistogramDiff) | Myers / histogram over `List<T>` | `Patch` of typed deltas | **controllable** — a `BiPredicate` equalizer decides what "equal" means | not probed | Java library, JVM-only | edit script |
| `differ` | positional vector diff, explicitly no move detection | vectors with a `0` removal sentinel | position | — | dormant | stored-nil-shaped sentinel |
| Zhang-Shasha tree edit distance | tree edit distance, ~O(n⁴) in the general formulation | mapping between tree nodes | **structural, not identity** | — | no Clojure implementation found | poorly |
| `magnars/datoms-differ` (2025.11) | entity maps → datom sets, keyed by `:db.unique/identity` | datom-level added/retracted | **identity** | not probed | small | n/a — prior art, not a renderer |
| delta-state CRDTs (arXiv:1603.01529) | delta-mutators over join-semilattices | delta fragments joinable onto stale state | by lattice identity | — | literature, not a library | n/a — but see §3.3 |

Three rejections with reasons:

- **`deep-diff2`** is explicitly a human-print library, pairs positionally,
  and pulls four transitive dependencies for a colorized printer we would
  never use (we have `seon.print`). Reject.
- **Extending `clojure.data`'s protocols** is technically available —
  `EqualityPartition` and `Diff` are ordinary protocols
  (`reference-code/clojure/src/clj/clojure/data.clj:69-75`, extended for
  atoms/sets/sequentials/maps at 106-122, dispatched at 124-141) — but both
  docstrings read "Implementation detail. Subject to change." Building a
  public Seon contract on a documented non-contract violates the
  read-the-seam law. Reject.
- **Zhang-Shasha / tree edit distance** solves a harder problem (unordered
  tree alignment) at O(n²)+ for a gain we cannot state. Reject on the
  simplicity gate.

### 3.3 Reconciling the sweep with the measurements — and the one property that decides it

The sweep's disqualifier for editscript is that **it has no identity keying
anywhere and no move operation**, so reordered rows become replace-storms. My
measurements confirm both halves and locate exactly where they bite:

- On raw vectors, `:quick` **did** produce the storm predicted — four edits
  where three sufficed (§3.1). A* did not, aligning `m3` across the removal;
  one removal is a weak test and I would not generalise from it.
- On the **identity-keyed** map, both modes emitted the same three minimal
  edits. Ordering is not a concept in a map, so "no move op" cannot bite.

So the sweep's disqualifier and my recommendation are consistent: editscript
must **never** be handed a positional collection of identity-carrying rows.
Identity keying is not an optimisation on top of editscript — it is the
precondition that makes it correct. This is the same conclusion the prior
note reached about `clojure.data/diff`, which is reassuring: the failure is
in positional pairing, not in either library.

**The property that decides the return shape.** The delta-state CRDT
literature (Almeida/Shoker/Baquero, arXiv:1603.01529) contributes one lesson
worth adopting: a delta is most useful when it **joins idempotently and
commutatively onto stale state**, so a consumer that missed a package, or
applied one twice, converges anyway. We need none of the causal-context
machinery — `basis-t` gives a total order — but the join property is a real
requirement on `#:seon.db.diff{…}`, because the render pipeline already
delivers deltas that a consumer can miss (a revision gap snaps to keyframe).

Checking each representation against it:

- An **index-rooted** edit script is **not** idempotent: `[[1] :-]` applied
  twice deletes two different rows.
- An **identity-rooted** edit script **is**: `[["m1" :m/read?] :r true]`,
  `[["m2"] :-]`, and `[["m4"] :+ {…}]` are each an `assoc-in`/`dissoc-in` at
  a stable address, so applying twice equals applying once, and any order of
  application over distinct identities gives the same result.
- The current `#:seon.db.diff{:added :removed :changed}` map is also
  idempotent, for the same reason: every element carries its own identity.

This is a second, independent argument for the same rule: **identity-rooted
paths, always; positional paths only for values that have no collection
identity to lose** (a scalar, a single nested map). It also gives the refusal
in §5 design 2 a principled boundary rather than a taste judgement.

**Prior art worth naming.** `magnars/datoms-differ` keys entity maps by
`:db.unique/identity` and diffs at the datom level — the same identity
derivation the owner's ruling now mandates for us (§4.1), arrived at
independently. And `posh`/`re-posh` never diff results at all: they match a
tx-report's `:tx-data` against each live query's **patterns** and invalidate,
at O(changed datoms × patterns) — precisely the attribute-level gate §2.3
derived from measurement. Two independent codebases converging on our two
mechanisms is the strongest evidence in this note that neither is exotic.

**A vocabulary correction for §1.5.** Datomic's name for the t₁..t₂ delta
primitive is **`tx-range`**, and its cost is proportional to the change
because Datomic keeps a log. Datahike has neither the name nor the log
(§1.3). The operation §1.5 proposes should therefore be called `tx-range`,
not the invented `datoms-between` — the closest integration seam already
named this thing.

**Verdict on front 3.** editscript `:quick`, **on identity-keyed input only**,
is the one candidate that is faster, more general, and more concise than what
we run today, at 31 KB and zero runtime dependencies. If a new dependency is
ever justified, this is what the evidence for one looks like — and the house
pattern is to vendor it as a `reference-code/` submodule so its semantics are
readable. `java-diff-utils` is the one library whose pairing is genuinely
controllable (a `BiPredicate` equalizer), and it is the fallback to
re-examine if ordered results ever become agent-relevant; it is JVM-only,
which Seon can afford but pays nothing for today.

## 4. Render integration

`seon.render/candidates` (`src/seon/render.clj:156-183`) selects a producer by
**contract fit**: public functions in the owning namespace whose declared
input accepts the value and whose declared output is `:seon.render/ai`. A
schema may also name its producer directly, which is what the diff result
does today (`resources/seon/schemas/seon.db.diff.edn:16-24`):

```clojure
:result
[:map
 {:seon.render/ai seon.db/render-diff-ai}
 [:seon.db.diff/added :seon.db.diff/added]
 …]
```

That mechanism is right and needs no change. Three defects in how it is used:

1. **No `:seon.render/html` producer.** The web UI falls to the floor
   renderer for every delta. One `seon.db/render-diff-html` returning Hiccup,
   declared beside the `/ai` key, closes it.
2. **The renderer hand-builds its string** (`src/seon/db.clj:1638-1663`) and
   calls `tokens/estimate` on `(pr-str result)` itself, rather than crossing
   `seon.print/fit`. A long changed-attribute list is unbounded output from a
   function whose whole purpose is bounding output.
3. **One estimate for the whole result is not an elision value.** The ruled
   shape is ordinary data carrying count, path, next offset, and requery
   identity — per omitted slot, not one number for the map.

**For the recommended edit-script representation the render gets simpler, not
harder.** The headline is the three counters editscript already computes
(`get-adds-num` / `get-dels-num` / `get-reps-num`, measured `1/1/1` above),
and the body is one line per edit whose path already reads as the identity
plus the attribute:

```
db diff t 536870914 → 536870917: +1 −1 ~1
~ "m1"  :m/read? → true
− "m2"
+ "m4"
… 3 of 3 edits shown; requery (my.message/inbox) at t 536870917
```

Every line above is a projection of one `[path op value]` triple, so the
renderer is a `map` over the edit script with `seon.print/fit` owning the
bound and the elision value naming the remaining edit count.

### 4.1 Identity derivation after the `{:seon.db/entity true}` ruling

The owner's ruling deleting `{:seon.db/entity true}` **improves** this
mechanism, and the flag-free primitive already exists:
`seon.schema.internal/identity-attr?`
(`src/seon/schema/internal.cljc:151-161`) reads `{:seon.db/identity true}`
from the attribute's own form, and `map-identity-entry-key`
(src/seon/schema/internal.cljc:163-171) finds a map schema's identity entry
key with no entity flag involved. Measured over the merged registry:

```clojure
{:total-keys 2246
 :identity-attr?-count 40
 :entity-flagged-kinds 37
 :maps-with-derivable-identity-entry 116
 :inbox-entry nil
 :cluster-message :seon.cluster.message/id}
```

`derive-entity-id-attr`'s 37 flagged kinds become **116 map schemas with a
directly derivable identity entry** — the coverage more than triples by
deleting the flag. This is the same derivation `magnars/datoms-differ`
reached independently (keying entity maps by `:db.unique/identity`), which is
mild evidence that the ruling lands on the idiom rather than away from it. `:my.message/inbox-entry` still reports `nil` here because
its entry key `:my.message/id` is a registry alias for
`:seon.cluster.message/id`; that is precisely what the alias chasing already
implemented at `src/seon/db.clj:1547-1585` resolves.

The migration is one expression. `result-identity-attribute`
(`src/seon/db.clj:1571-1585`) currently builds its `identity-attributes` set
by mapping `derive-entity-id-attr` over every form; after the ruling it
builds the same set as `(into #{} (filter #(internal/identity-attr? forms %)) (keys forms))`.
Nothing else in `seon.db/diff` touches the flag, and
`seon.render/entity-lookup` (`src/seon/render.clj:504-516`), which scans for
`:seon.entity/id-attr` values, needs the same substitution — confirming the
prior note's call for **one shared owner** rather than two derivations.

Storability stays where the ruling puts it: `storable-attribute-in?`
(`src/seon/schema/datahike.clj:284`), consulted at the bridge
(datahike.clj:301,319). `seon.db/diff` never needs it — it pairs projection
rows, which may be unstored.

## 5. Ranked end-state designs

### Design 1 — keep `clojure.data/diff`, fix the identity derivation (simplest)

- **Mechanism.** Unchanged from HEAD: double execution, identity re-key,
  `clojure.data/diff`, classify into `#:seon.db.diff{:added :removed
  :changed}`.
- **Generality.** Collections of maps with a derivable row identity. A
  scalar, a nested map, or a collection of identity-free values gets the
  typed refusal (`src/seon/db.clj:1676-1679`), which is honest but is a
  refusal, not an answer — it does **not** meet the owner's "any data output
  shape".
- **Identity.** Swap the flag-gated `derive-entity-id-attr` set for
  `identity-attr?` (§4.1). Coverage 37 → 116 map schemas.
- **Cost.** 2.38 ms execution + 13.2 ms comparison per 5000-row surface.
- **Render.** As today, plus the three §4 fixes.
- **Migration.** One expression in `result-identity-attribute`; one shared
  derivation with `seon.render/entity-lookup`; no schema change, no
  dependency.

### Design 2 — edit script over the identity-keyed value (RECOMMENDED)

- **Mechanism.** Derive the row identity as in design 1; when it exists,
  re-key both results and hand them to `editscript.core/diff` with
  `{:algo :quick}`. When the result is **not** a collection (a scalar, a
  single nested map), hand the two raw values over unchanged — there is no
  collection identity to lose. When the result **is** a collection with no
  derivable identity, **refuse loudly**, exactly as HEAD does today: §3.3
  shows a positional edit script over identity-carrying rows is both
  storm-prone and non-idempotent, so the silent positional answer is the
  same defect the prior note rejected. Return `#:seon.db.diff{:edits …}` —
  the plain `[[path op value]…]` vector from `editscript.edit/get-edits`,
  ordinary Clojure data with no library types crossing the boundary.
- **Generality.** Every EDN shape that carries identity or needs none: a
  scalar, a deep nested map, a set, a mixed result, and any collection whose
  rows declare an identity attribute — one return shape for all of them, which
  dissolves the prior note's "one genuine contract wart" (non-collection
  results returning a different shape). The residual refusal — a collection of
  identity-free rows — is smaller than design 1's, which refuses whenever no
  identity is derivable at all.
- **Identity.** Same derivation as design 1, and load-bearing for a second
  reason: identity-rooted paths are what make the delta **idempotently and
  commutatively joinable** onto stale state (§3.3), so a consumer that missed
  or replayed a package still converges. Index-rooted paths are not, which is
  why the collection refusal above is principled rather than conservative.
- **Cost.** 2.38 ms execution + **3.06 ms** comparison per 5000-row surface:
  the whole call drops from ~18 ms to ~5.4 ms, and the no-change case returns
  `[]` instead of the entire collection.
- **Render.** §4's four-line block, derived mechanically from the edit
  script; `get-adds-num`/`get-dels-num`/`get-reps-num` give the headline
  counts, `seon.print/fit` bounds the body, the elision value names the
  remaining edit count and the requery identity. The edit script is
  *simultaneously* the durable delta, the render, and a patch — `patch`
  round-tripped in the probe.
- **Migration from HEAD.** Accretion, then deletion in the same refactor, per
  the one-mechanism law: add `:seon.db.diff/edits` to
  `resources/seon/schemas/seon.db.diff.edn` beside the existing three keys;
  re-point `seon.db/render-diff-ai` at the edit script; delete
  `:added`/`:removed`/`:changed` and `identity-diff`
  (`src/seon/db.clj:1607-1636`) once no caller reads them. Net deletion of
  code.
- **What it costs us.** One dependency. That is a genuine owner decision, and
  the evidence is: 4.5× faster than the core function it replaces, strictly
  more general, 31 KB, **zero runtime dependencies**, EPL, CLJ+CLJS+bb. House
  pattern says vendor it as a `reference-code/` submodule so its A* and
  `:quick` implementations are readable rather than remembered.

### Design 3 — structural datom diff in the fork + attribute read-set gate

- **Mechanism.** Record the attribute-level read set during the current
  execution (`d/filter` predicate or a recording db value, §2.2). On the next
  question, gate: if no datom carrying a read attribute changed since the
  basis, return the empty delta without executing anything. Only when the
  gate fires do designs 1/2 run. This is `posh`/`re-posh`'s mechanism exactly
  — match changed datoms against each live read's patterns and invalidate,
  never diff the results — arrived at here from measurement (§2.3) rather
  than borrowed. Long-term, replace the gate's history scan with the
  `tx-range` co-walk between two real db values (§1.5), obtained via
  `commit-as-db` from a commit id recorded on the receipt.
- **Generality.** The gate is universal; the answer still comes from design
  1 or 2, so this is a *layer*, not an alternative.
- **Identity.** Unchanged.
- **Cost.** Gate today: 5.4 ms (history scan) against ~18 ms for the full
  design-1 call — worth it only when nothing changed. Gate after the fork
  work: proportional to the change, ~3 nodes rather than 235 in the
  measurement above.
- **Render.** Nothing new: the gate's output is the empty delta the chosen
  design already renders.
- **Migration.** Requires the receipt to record the **commit id** as a
  durable fact (not only `:seon.db/basis-t`), custody for
  `release-materialized-db`, and three fork changes across
  persistent-sorted-set and datahike. Meaningful work for a saving that no
  current measurement demands.
- **When to build it.** When a profile shows `seon.db/diff` calls dominated
  by the no-change case on a large history. Not before.

## 6. What no design fixes

- **A read whose result depends on time or randomness** replays differently
  for reasons no diff can attribute. `:seon.fn/external-sink` catches sinks,
  not impurity.
- **Composed forms** (`(count (my.message/inbox))`) still cannot be passed as
  a var; a lambda defeats the graph's sink check. Unchanged by every design
  here.
- **The gate can never be sound for membership additions** at datom
  granularity (§2.3). Attribute granularity is sound and coarse; that
  trade-off is inherent, not an implementation shortfall.
- **`as-of` will never give a second tree** (§1.1). Any future structural
  work must go through `commit-as-db`, which changes what a receipt must
  record.
