---
type: research
status: active
tags: [research, agent]
---

# Probe: merging sci.interrupt/clojure-core into the JVM host context

Executed de-risk probe for the gap that `seon.host.context/build-base!`
(src/seon/host/context.clj:926-931) installs an `:interrupt-fn` but never
merges sci's interrupt-aware core replacements, leaving native iteration
like `(reduce + (range))` uninterruptible and permanently eating a pool
thread.

- Probe code: `tmp/sci-probe/jvm/src/probe/interrupt_merge.clj`
- Run: `cd tmp/sci-probe/jvm && clojure -M -m probe.interrupt-merge
  {drills|corpus|bench|fork}` (run `bench` in its own process; drills
  leave HUNG daemon threads spinning)
- sci source: `reference-code/sci/src/sci/interrupt.cljc` (pinned local
  root via `tmp/sci-probe/jvm/deps.edn` and the repo `:host` alias)
- JVM: OpenJDK 26.0.1. Interrupt mechanism identical to production and
  the C1 harness: watchdog thread calls `Thread/interrupt`; the
  `:interrupt-fn` turns `.isInterrupted` into `sci.interrupt/interrupt!`.

## Verdict: SAFE WITH CAVEATS

The merge is one map entry at `sci/init` time
(`:namespaces {'clojure.core interrupt/clojure-core, 'clojure.string
interrupt/clojure-string}`), fixes every targeted runaway shape with
sub-millisecond interrupt latency, produced zero semantic divergence over
a 41-form corpus, and shares correctly through `sci/fork`. Caveats:
3-6x slowdown on hot seq iteration (O(1) `count` of `range` becomes
O(n)), a short residual-uninterruptible list, and one host-side
lazy-realization trap that the eval runner must handle.

## 1. Interruptibility (drills, interrupt at 200ms, join timeout 3s)

Latency = time from `Thread/interrupt` to eval return. HUNG = still
running 3000ms after the interrupt (= today's permanently-lost pool
thread).

### Fixed by the merge (unmerged → merged)

| form | unmerged | merged |
|---|---|---|
| `(reduce + (range))` | HUNG | interrupted, 0.6ms |
| `(into [] (range))` | HUNG | interrupted, 0.2ms |
| `(count (range))` | HUNG | interrupted, 0.1ms |
| `(doall (repeat 1))` | HUNG | interrupted, 0.2ms |
| `(dorun (cycle [1 2 3]))` | HUNG | interrupted, 0.1ms |
| `(re-find #"(.*a){15}b" "a"*32)` | HUNG | interrupted, 0.2ms |
| `(re-seq ...)` same pattern, doall'd | HUNG | interrupted, 0.2ms |
| `(re-matches ...)` same pattern | HUNG | interrupted, 0.2ms |
| `(str/replace ...)` same pattern | HUNG | interrupted, 0.2ms |
| `(str/split ...)` same pattern | HUNG | interrupted, 0.2ms |
| `(str s s)` doubling loop | HUNG | interrupted, 0.1-19ms |
| `(last (range))` | HUNG | interrupted, 0.1ms |
| `(vec (range))` | HUNG | interrupted, 0.1ms |
| `(apply + (range))` | HUNG | interrupted, 0.1ms |
| `(set (range))` | HUNG | interrupted, 0.1ms |
| `(frequencies (cycle [1 2]))` | HUNG | interrupted, 0.1ms |
| `(some #{-1} (range))` | HUNG | interrupted, 0.1ms |
| `(first (filter neg? (range)))` | HUNG | interrupted, 0.1ms |
| `(mapv inc (range))` | HUNG | interrupted, 0.1ms |
| `(run! identity (range))` | HUNG/OOM | interrupted, 0.1ms |
| `(every? number? (range))` | HUNG | interrupted, 0.1ms |
| `(doall (take-while pos? (map inc (range))))` | HUNG | interrupted, 0.1ms |
| `(reduce + (repeatedly rand))` | HUNG | interrupted, 0.1ms |
| `(into [] (repeatedly rand))` | HUNG | interrupted, 0.1ms |
| `(count (repeatedly rand))` | HUNG | interrupted, 0.1ms |
| `(doall (repeatedly rand))` | HUNG | interrupted, 0.1ms |

Key structural insight: interruptibility propagates from BOTH sides.
The overridden producers (`range`/`repeat`/`cycle`/`iterate`) yield
lazy seqs that fire the interrupt-fn per element, so even NATIVE
consumers over them (`last`, `vec`, `set`, `mapv`, `every?`, `apply +`,
`frequencies`, `first`+`filter`) become interruptible. Symmetrically the
overridden consumers (`reduce`/`into`/`count`/`doall`/`dorun`) check per
element, rescuing native producers like `repeatedly`.

Regex note: on JDK 26 the textbook ReDoS shapes `(a+)+$` / `(a|a)*`
complete in <1ms natively (engine short-circuit), so they prove nothing.
`(.*a){15}b` over 32 a's is genuinely exponential here (13.4s native at
30 a's, ~1min at 32) and interrupts in 0.2ms through sci's
`InterruptibleCS` charAt wrapper (interrupt.cljc:120-123).

### Residual uninterruptible AFTER the merge

- Native consumer over a native producer, i.e. the runaway seq source is
  not `range`/`repeat`/`cycle`/`iterate` and the sink is not
  `reduce`/`into`/`count`/`doall`/`dorun`/`re-*`. Observed HUNG:
  `(last (repeatedly rand))`, `(vec (repeatedly rand))`,
  `(sort (repeatedly 100000000 rand))`.
- `reduce-kv` is not in the override set — but it only iterates an
  already-realized collection, so it is bounded by what the agent could
  materialize, never infinite.
- `(loop [] (recur))` and interpreted-fn recursion were ALREADY
  interruptible/erroring unmerged (per-fn-entry/recur `:interrupt-fn`
  check in sci.impl.fns; recursion stack-overflows to an error) —
  verified, no change.
- `Thread/sleep` is NOT exposed by the default context ("Unable to
  resolve symbol: Thread/sleep") — no blocking-sleep hole, verified in
  both contexts.
- `(apply str (repeatedly (fn [] "x")))` interrupted in BOTH contexts
  (sci's own apply/arg-seq stepping hits interpreted checks), so it is
  not a residual.

The residual class is narrow: an infinite native source other than the
four overridden producers, consumed by a native sink other than the
overridden ones. The deadline watchdog must therefore remain the outer
guard and thread-loss remains possible, but every shape observed from
real agent mistakes so far (`range`-family runaways, regex, string
loops) is covered.

## 2. Semantics and performance

### Semantic corpus

41 forms covering reduce arities (empty/no-init/`reduced`), into
0-3-arity with transducers and stateful `partition-all`/`take`,
range/repeat/cycle/iterate shapes, count on string/map/nil, doall/dorun,
the full re-* family incl. a stepped `re-matcher`, `clojure.string`
split/replace/replace-first (incl. `$1` substitution), and
HOF/var-value uses (`(map count ...)`, `(partial reduce +)`,
`(apply reduce ...)`, `(reduce into ...)`): **41/41 identical results
merged vs unmerged, 0 divergent.**

### Microbench (5 samples, median, 2 warmups per ctx)

| bench | merged | unmerged | ratio |
|---|---|---|---|
| `(reduce + (range 1000000))` | 27.7ms | 4.8ms | **5.7x** |
| `(into [] (comp (map inc) (filter odd?)) (range 1e6))` | 38.6ms | 6.8ms | **5.6x** |
| `(reduce + (filter odd? (map inc (range 1e6))))` | 67.9ms | 21.0ms | **3.2x** |
| `(count (range 1000000))` | 37.5ms | 0.1ms | **397x** |

The 3-6x on hot seq loops is the honest cost of a per-element
`Thread.isInterrupted` check through the lazy-seq override. The 397x on
`count`+`range` is a complexity change, not a constant: native
`(range n)` is counted O(1); `sci-range` returns an uncounted lazy seq
so `count` walks it O(n). Absolute cost stays small at agent scale
(37ms per million elements), and agent eval is interpreter-dominated
anyway — sci interpretation overhead dwarfs this for typical agent code.
Anything relying on `counted?` of `range` results degrades the same way.

## 3. Fork sharing

Merging into the BASE context before forking is the right shape — one
merge serves all agents:

- `(sci/eval-string* fork "reduce")` is `identical?` across two forks
  and to the base var value; the base `reduce` is NOT native
  `clojure.core/reduce` (override installed once, shared by reference).
- A forked context interrupts `(reduce + (range))` in <1ms.
- A `def` in one fork does not leak to a sibling fork (fork isolation
  unchanged).

## Caveats for the production merge

1. **Escaped lazy values must be realized under the ctx.** The overrides
   read `:interrupt-fn` via `sci.ctx-store/get-ctx` at call time. A lazy
   value that escapes eval and invokes an overridden fn during LATER
   realization — e.g. `(map count [[1] [1 2]])` realized by host
   rendering/serialization — throws
   `IllegalStateException: No context found in: sci.ctx-store/*ctx*`
   outside `eval-string*`. The eval runner must force/serialize results
   on the eval path or wrap realization in
   `(sci.ctx-store/with-ctx ctx ...)` (the probe corpus does exactly
   this). Any host path that realizes agent-returned seqs lazily needs
   the same treatment.
2. **Perf**: 3-6x on hot native-seq loops, O(n) `count` of `range`.
   Acceptable for agent eval; do not route host-side hot paths through a
   merged sci context.
3. **Residuals**: the narrow native-source x native-sink class above
   still hangs; keep the deadline watchdog and thread-pool hygiene as
   the outer boundary.
4. **Upstream nit** (reference-code/sci/src/sci/interrupt.cljc:355):
   `clojure-string` builds the `replace-first` var as
   `(copy-vars/new-var 'replace sci-string-replace-first true)` — the
   var NAME says `replace` but binds replace-first. Behavior is correct
   (verified in the corpus); var metadata (`:name`) is wrong. Fix in the
   pinned sci fork when convenient.
