---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, print, floor, repl, dogfood]
---

# The floor prints `keys`, `sort`, `vals` and other ordinary seqs as `#object`, and `(map inc [1 2])` as `(…?)`

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 33ea69298, branch `dogfood-repl-opus-2`, 2026-09-26)

```clojure
[(sort [3 1 2]) (sort-by - [3 1 2]) (seq (int-array [1 2])) (reverse [1 2]) (map inc [1 2])
 (seq [1 2]) (list 1 2) (into-array [1 2]) (seq {:a 1}) (keys {:a 1}) (vals {:a 1})
 (seq "ab") (rseq [1 2])]
```

shown:

```
[#object[clojure.lang.ArraySeq]
 #object[clojure.lang.ArraySeq]
 #object[clojure.lang.ArraySeq/ArraySeq-int]
 (2 1)
 (…?)
 (1 2)
 (1 2)
 #object[[Ljava.lang.Long;]
 #object[clojure.lang.PersistentArrayMap/Seq]
 #object[clojure.lang.APersistentMap/KeySeq]
 #object[clojure.lang.APersistentMap/ValSeq]
 #object[clojure.lang.StringSeq]
 #object[clojure.lang.APersistentVector/RSeq]]
```

Earlier, `(sort (map str (keys t)))` over a pulled entity answered `#object[clojure.lang.ArraySeq]`.
An agent cannot read the keys of a map, a sorted result or a `map` result at the REPL. These are
the first forms an agent writes. `clojure.main` prints all of them as data.

## Which model is wrong

The floor admits values by an **exact-class allowlist** (`src/seon/render/value.clj:455-478`,
`seq-cells` at `:362-389`). Every `ISeq` implementation not on the list becomes opaque, and
Clojure has dozens of them (`ArraySeq`, `KeySeq`, `ValSeq`, `StringSeq`, `RSeq`, map seqs,
array seqs, and more in libraries). The list can never be complete, because it is a
hand-maintained list of classes standing in for an interface.

Laziness is modeled as "unrealized, so pending" **after the evaluation has finished**. The
REPL's contract (`clojure.main/repl` prints, and printing realizes) means a finite lazy result is
data. Bounded realization belongs to the printing stage, under the same budget that bounds
everything else. Infinite or poisoned seqs are what the budget is for; they are not a reason to
refuse every lazy value.

The smaller model: classify by interface (`ISeq`/`Sequential`, `IPersistentMap`, Java arrays by
component type), and realize a seq's prefix up to the floor's own member bound through `first`
and `next` under the evaluation's deadline. A seq that fails or times out shows as a pending tail
with its cause.

## Expected

`(keys {:a 1})` shows `(:a)`, `(sort [3 1 2])` shows `(1 2 3)`, and `(map inc [1 2])` shows
`(2 3)`. `(range)` shows a bounded prefix and an elision that names the bound.

## Regression

One member over the vector above: every element prints as its readable data, and `(range)`
prints a bounded prefix.

## Evidence 2026-09-26: `subvec` too

`(subvec result/e49e526b52349 32)`, a slice of a vector result reached through its handle, printed
`#object[clojure.lang.APersistentVector/SubVector]` (branch `dogfood-repl-opus-7`, archive 10d6fdff5). The
allowlist misses vector implementations too, not only seqs.
