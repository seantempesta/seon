# review-growth-opus: review of 3071fef19 (`seon.error.refusal/post-order` folds a repeated value once)

Probe setup: the committed `refusal.clj` and `id.clj` of 3071fef19 and of its parent, extracted with `git show`,
loaded side by side (namespaces renamed `refusal-parent` / `refusal-change`) in ONE throwaway JVM (Clojure 1.12.5,
`-Xss1m`, unarmed, nil projection). Not default's JVM; the change is not installed on default (42e569d9d).
Allocation is `getThreadAllocatedBytes` of the calling thread. Live probes on default used agent
`review-growth-opus` (SCI scratch namespace `scratch.review-growth-opus`) and JVM mode for host measurement.
Scripts: session scratchpad `review-growth-opus/probe*.clj` (not committed; numbers below are the evidence).

## Broken first

### A. A deep UNCHANGED child under a rebuilt parent overflows the JVM stack (pre-existing, not fixed: file held)

`referenced`'s `:close` hashes a collection only when it was rebuilt:
`(cond-> held (not (identical? held value)) (doto hash))`. An unchanged child is never hashed, so a parent rebuilt
around a place or world (or a sibling that was) hashes that child recursively, on the JVM stack.

- Probe JVM, `(references nil {:place (atom 1) :deep <n-deep {:a ..} map>})`: n = 3,000 returns; n = 5,000 and
  10,000 throw `StackOverflowError` (top frames `clojure.lang.Util.dohasheq` / `APersistentMap.hasheq` /
  `Murmur3.hashUnordered`) on parent and change alike.
- Live on default (agent `review-growth-opus`, SCI):
  `(throw (ex-info "sibling" {:place (atom 1) :deep deep100k}))` answers `:seon.repl/error
  java.lang.StackOverflowError` with fault `4bcdb76f43617d6a…` (`seon.sci.eval/shown-failure refused argument 0
  error: expected a map, got nil`, called from `seon.turn/show-row`). The same throw WITHOUT `:place` returns its
  error normally, so the rebuilt-parent hash is the producer. 10,000 deep does not overflow on default's thread.
- This contradicts the docstring claim "a value nested 10,000 deep costs heap, never JVM stack" and growth notes §2
  ("all return their value or error, no new fault"): the overflow class is still open through this path.
- `3fab5b880` (batch-C) fixed only the rebuilt-set case (its regression
  `seon.error.refusal-test/a-set-holding-a-deep-value-is-referenced-without-the-stack` pre-hashes its value).

Patch for the owner (`src/seon/error/refusal.clj` is held by lane `review-growth-codex`):

```diff
-    ;; A rebuilt collection's hash is computed as it closes, its children's
-    ;; already cached: a set rebuilt around it then hashes one level, never
-    ;; the whole depth on the JVM stack.
-    :close (fn [{:keys [value held]}] (cond-> held (not (identical? held value)) (doto hash)))}
+    ;; Every collection's hash is computed as it closes, its children's
+    ;; already cached: a parent rebuilt around a deep unchanged child, or a set
+    ;; rebuilt around it, hashes one level, never the whole depth on the JVM stack.
+    :close (fn [{:keys [held]}] (doto held hash))}
```

Measured on the patched copy (same JVM, fresh value per side so no cached hash helps either; median of 15):

| probe | change ms / MB | patched ms / MB |
|---|---|---|
| `{:place a :deep <100,000 deep>}` | StackOverflowError | 66 ms / 93 MB, result `=` input minus the place, `:deep` identical |
| wide map, 10k keys, no world | 8.6 / 11.1 | 7.5 / 11.1 (first walk 11.5 / 12.1 vs 10.5 / 11.0) |
| wide map, 10k places | 12.5 / 24.6 | 7.7 / 24.6 |
| 1M distinct vectors | 877 / 1,802 | 824 / 1,802 (first walk 1,301 vs 1,367) |
| small ex-data map | 0.004 / 0.004 | 0.004 / 0.004 |

No measurable cost: a collection's hash is cached in it, so each node is hashed once, one level deep. Remaining
edge the patch does not cover: map KEYS are not walked, so a deep key in an array map under a rebuilt parent can
still hash recursively. Regression to add with the patch (same file as the set test):
`(references nil {:place (atom 1) :deep (reduce (fn [v _] {:a v}) 1 (range 100000))})` returns, `:deep` identical
to the input's.

Task: `seon.error.refusal/a-deep-unchanged-child-under-a-rebuilt-parent-overflows-the-stack` (727ee396a9f4).

### B. An overflow in the error path reaches `shown-failure` as nil (not fixed: outside files)

Same live probe: the reply's `:error` is the bare `java.lang.StackOverflowError` and `seon.sci.eval/shown-failure`
is called with nil (fault above; an earlier occurrence 2026-10-07T01:49:04Z, signature `8ef41c77e87f…`). The
boundary did not turn the throwable into an error map. Task:
`seon.sci.eval/a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil` (a97a188decbe).

## 1. Result equivalence: EQUIVALENT

The node result is a function of the node alone: `open` depends on the value (`project`, `object-name` by identity
hash, record/collection tests), `close` on the node's own state, and only `step` depends on the parent, which the
cache never skips. So a reused result equals a recomputed one; the only observable difference is that repeated
occurrences now share one rebuilt object instead of equal copies.

Same JVM, `(= (P nil v) (C nil v))` and metadata equal for every case: a shared plain value under several parents
and as a set member; a shared value holding a place, with metadata, under a vector, a map and a set; two
equal-but-distinct maps (identity keys keep them separate, as before); records (not entered, unchanged); a lazy seq
mentioned twice (not entered, not realized by the walk); a `java.util.HashMap` holding an atom (not entered); map
entries; nil/false members. Precondition: a world whose identity projection reads mutable state (a connection) is
now projected once per walk instead of once per mention, a consistent snapshot rather than a behavior change.
No cycles are possible: only persistent maps, vectors and sets are entered, and places are named, never entered.

## 2. Cost: SUPPORTED, proportional to distinct data

300 problems sharing one `n`-deep value holding a place (probe JVM, unarmed):

| depth | parent ms / MB | change ms / MB |
|---|---|---|
| 50 | 41.5 / 16.8 | 5.4 / 1.1 |
| 400 | 108 / 120 | 3.6 / 1.5 |
| 2,000 | 225 / 592 | 5.4 / 3.1 |
| 10,000 | 1,173 / 2,953 | 20.4 / 11.6 |

No-sharing inputs: 10k-key map 3.5 -> 7.2 ms in one run, 6.5 -> 7.1 ms median (+0.35 MB); 1M distinct vectors
681 -> 770 ms median, 1,769 -> 1,802 MB (+34 MB, the identity map): a few percent. The `IdentityHashMap` is local
to one `post-order` call and dropped with it; it holds one entry per collection node, so peak extra memory is
O(collection nodes) for the walk's duration. Wide maps: `assoc`/`conj`/`disj` per changed child, no quadratic path.
Pre-existing linear constant worth recording: an unchanged walk allocates ~1.8 KB per collection node (1M vectors:
1.8 GB) in `entries` tuples and per-node `{::state ..}` maps.

Live after-numbers on default need root's restart after landing (re-run growth notes §3's table).

## 3. Growth notes' other claims

- §2 (StackOverflow faults predate 77c9f5459 / 7a6878374): SUPPORTED by timestamps (fault 02:08:06Z = 11:08 +0900;
  commits 11:21 and 12:10 +0900; both are ancestors of 42e569d9d). But "no new fault" is CONTRADICTED for the
  unchanged-sibling shape (finding A).
- §4 (cause chain linear): SUPPORTED. Default, JVM mode, `seon.error.refusal/chain` on an ex-info chain (second
  call): 11 links 0.40 ms / 0.82 MB; 101 links 1.08 ms / 1.59 MB; 1,001 links 8.6 ms / 9.5 MB; 4,001 links 40.7 ms /
  35.9 MB (~9 KB and ~9 us per link).

## 4. Test `seon.error.shared-data-test`

- One behavior asserted on data (every problem's `:seon.error/offending` is the identical rebuilt value); red on the
  parent (300 equal but distinct copies) and slow there (~1.2 s / 3 GB unarmed), green on the change in ~20 ms.
  Public entrance `references`; deterministic.
- Defects (minor, not fixed: the test file is held by `review-growth-codex`): the comment carries a lane name, a date and a measurement ("cost gigabytes"), which source must not; the
  second assertion (the bottom is `"#object[clojure.lang.Atom…"`) re-tests place naming, already owned by
  `seon.error.refusal-test/a-place-in-an-error-is-named-never-printed-through`; a one-test namespace where
  `seon.error.refusal-test` already holds the walk's regressions.

## 5. Rules

- Contract unchanged and still complete. Docstring gains one sentence, no measurement.
- The `IdentityHashMap` is a local accumulator of one fold, not an atom, cache or state: it never escapes the call.
  Reason a persistent map does not serve: an equality-keyed map would hash each deep key recursively on the stack
  (the very overflow above) and cost equality checks; identity is the needed relation. Acceptable.
- Comment `;;` above the `let` describes the form; fine.

## Design check

The cache belongs in `post-order`, the one walk under `chain`, `references` and the recorder; the change is the
smallest composition. While reviewing it the walk's other depth hazard (finding A) turned up in the same closure;
its fix is one line in the owner, handed over as a patch because the file is held.

## Commits and tasks

- This notes file only (no source change of mine: `src/seon/error/refusal.clj` held by `review-growth-codex`).
- Tasks created (database, agent `review-growth-opus`): 727ee396a9f4, a97a188decbe.
- Default needs no restart for this review; landing the patch needs root's ordinary restart.
