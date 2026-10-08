---
title: Base roster design (lane base-roster), 2026-10-08
type: design
status: active
created: 2026-10-08
---

# Base roster: where the cluster base SCI context lives (lane base-roster, 2026-10-08)

What exists: the cluster base is the `seon.cache` kind `:seon.sci.eval/base-context`, keyed
`[store-identity loaded-commit]` (`seon.sci.eval/cluster-base-key`), derived by `derive-base-ctx` on a miss and
forked by `copy-base-ctx` for every `acquire!` whose program differs from the context's. Weigher:
`(* 2048 function-count)`. The principal Clojure approach proposed here: the base is a live host object, so it
leaves the content cache and becomes one slot of a validated state map, replaced (never evicted) when its owner
advances it.

Source: review finding 3 (`docs/research/agent-platform/2026-10-08/review/landed-review-opus.md`).

## Measured (default's JVM, pid 68190, HEAD `b23b52ca6`, JVM mode as agent `base-roster`)

- Cache row before probing: hits 51, misses 2, evictions 1, 11.72 MB weighed, load 2,089 ms total; cache
  504.9-509.3 MB of 512 MB; heap live 2.0-2.9 GB.
- `derive-base-ctx` of the loaded program (5,724 functions): first call 1,564 ms; then 4 in 539 ms and 16 in
  2,433 ms (135-152 ms each). The first call's 1.5 s matches the review's 1,396 ms rebuild after eviction.
- **Retained size, by heap histogram difference** (`jcmd 68190 GC.class_histogram`, each a full GC; held N fresh
  bases in a JVM Var, histogram, dropped them, histogram):

  | N held | total diff | per base, classes that scale with N |
  |---|---|---|
  | 1 | -11.1 MB (background noise exceeds signal) | 4,453 `sci.lang.Var` |
  | 4 | -18.1 MB (noise) | 4,460 `sci.lang.Var`, 14,980 `PersistentArrayMap` |
  | 16 | +12.3 MB | 4,461 `sci.lang.Var`, 16,804 `PersistentArrayMap`, 143 `sci.lang.Namespace`, ~4,600 sorted-map nodes |

  Summing the classes whose count scales 4→16 by 2.5-6×: **0.93 MB per base**; adding the scaling `Symbol` and
  `Object[]` rows of the N=16 diff: **about 1.6 MB per base**. The base's unique retained size is therefore
  roughly 1-2 MB: one `sci.lang.Var` per copied host Var plus SCI namespace maps. The function objects, the
  projection and the loaded rows are shared with the JVM or held by other cache kinds.
- **Correction to the review's premise.** The 624 MB in PB-01's commit message was allocation of a second
  derivation (PB-03a: 246-248 MB allocated per warm derivation), not retained size. The weigher (11.7 MB)
  over-estimates the retained size by about 7-10×, not 50× under. Weighing it by measurement would not stop the
  eviction: Caffeine's W-TinyLFU evicts by frequency and size under pressure, and the cache sits full. The base was
  evicted because it is an entry in a full content cache at all, and each eviction costs 135 ms-1.5 s and about
  250 MB of allocation.

## Design

**Where it lives.** The base is a function of the JVM's compiled Vars (one set per process) and the loaded
program rows of one store. Its true scope is the process, beside `seon.sci.eval/loaded-identity`, which names the
JVM's loaded commit. AT-2 (ledger, "One validated process state", ready: its dependency PB-02 landed) moves
`loaded-identity` into the validated process state. The base belongs as one key there:

```clojure
:seon.sci.eval/bases {store-identity {:seon.source/commit-id commit
                                      :seon.sci.eval/ctx base
                                      :seon.sci.eval/derived-ms ms
                                      :seon.sci.eval/vars n}}
```

One slot per store identity, holding the base of that store's loaded commit. Clusters on one store (default and
its branches) share it, as the cache key does today (PB-01's ruling that agents share the cluster's base).

**Who advances it.**
1. `base-ctx` on a slot miss or a slot at another commit derives once (concurrent callers join the one pending
   derivation, as `seon.cache/claim` does, holding no monitor) and replaces the slot. The previous commit's base
   is released by that replacement; nothing evicts it otherwise.
2. PB-03b's adoption advances the slot in place (`advance-base` over the reloaded namespaces), replacing it with
   the advanced base. Its card's `seon.cache/evict!` of the previous key becomes the slot replacement.
3. The store's release retires its slot (one `dissoc`), so a test JVM opening many disposable stores keeps none
   after release.

**Status.** `seon.health`'s status unit (or `seon.cluster.status`) shows the slot per store: commit, Var count,
derive ms, and estimated bytes (Var count × the calibration above, about 210-360 B per Var), plus the derived-at
instant. `seon.cache/status` stops listing `:seon.sci.eval/base-context`; the cache's byte budget then describes
only derived values.

**What disappears.** The `::base-context` cache kind and its unmeasured weigher; the eviction-rebuild path
(135 ms-1.5 s, ~250 MB allocated per miss); PB-03b's evict call.

**Cost.** A hit is one map read of the process state (nanoseconds, as the cache hit is today). A miss is the same
derivation, now only on a new loaded commit or a new store. Memory: one base per store, about 1-2 MB retained.

**Alternatives considered.**
- Weigh by measurement in the cache: does not stop frequency-driven eviction; refused.
- Zero weight (Caffeine never evicts zero-weight entries by size): a pinned entry inside the bounded cache is an
  exemption, and nothing retires the previous commit; refused.
- AT-1's cluster state (one base per cluster): bounded by cluster lifetime, but clusters on one store would each
  hold an equal base, and the base's content is process-scoped (compiled Vars); second choice.
- A new `defonce` slot in `seon.sci.eval` now, folded into AT-2 later: the smallest immediate move, but it adds an
  atom AT-7's lint must allow-list and AT-2 then moves; built twice in miniature.

## Interaction with approved rows

- **AT-2** owns the process state and `src/seon/sci/eval.clj`'s `loaded-identity`; the base slot is a key of the
  same map. Landing the slot before AT-2 means adding the atom AT-2 is about to fold.
- **PB-03b** (waits on RED-5) writes `advance-base` and changes `base-ctx`'s derive function in the same lines this
  change moves; its card assumes the cache and its previous-key eviction.
- **AT-1** is not needed: the base is not cluster state.

## Ruling requested (stop point)

Implementation stopped after this design, for three reasons:
1. `src/seon/sci/eval.clj` is claimed by lane `impl-fk-sci` with an uncommitted diff.
2. The recommended home is AT-2's process state, which does not exist yet.
3. PB-03b's card must change from cache eviction to slot replacement.

Options for root (simplest first):
1. **Slot now.** A `defonce` process slot in `seon.sci.eval` keyed by store identity, released on store release,
   shown in status; AT-2 folds it. About 30 lines; needs `eval.clj` from `impl-fk-sci` and a store-release
   callback in `src/seon/cluster/store.clj`. Fixes the eviction today; sacrifice: one more atom until AT-2.
2. **(Recommended) Base slot lands with AT-2.** AT-2's row gains the `:seon.sci.eval/bases` key and this note's
   proof; PB-03b's card is revised to replace the slot. One move, no interim atom; sacrifice: the base can still be
   evicted (about once per boot under pressure, 1.5 s) until AT-2 lands.
3. **Order PB-03b after AT-2** and let PB-03b introduce the slot as part of advancing it. Fewest edits of the
   same lines; sacrifice: waits on RED-5 too.

Proof for whichever lands: `seon.sci.branch-execution-test/the-cluster-base-survives-cache-pressure` (fill the
process cache past its budget with another kind through `seon.cache/lookup`; the next `acquire!` of a branch runs
no `derive-base-ctx`, by `seon.profile` rows) and a status read showing the slot with its commit and Var count,
through `seon.mcp/call`.

## Out-of-scope findings (recorded as tasks)

- `seon.cluster.boot/one-cluster-without-a-database-fails-the-whole-status`: `bin/seon status` exited 1 at 17:35
  with `seon.health/render-ai refused argument 0 at [:seon.db/db]` (contract `:seon.render/unit`, called from
  `seon.cluster.boot/request!`), then answered OK on the next call. Diagnostic file (not committed):
  `data/clusters/default/logs/operator-status-20261008T173536.285.edn`.
- The first `derive-base-ctx` call on default took 1,564 ms against 135-152 ms for the next twenty: the cost of the
  first derivation after other work is ten times the warm cost, which is what every eviction paid. Not separately
  recorded: the slot removes the repeat; the first-call cost is covered by `seon.cluster.boot/reset-takes-over-six-seconds`
  (its 1.1 s base-context derivation).
