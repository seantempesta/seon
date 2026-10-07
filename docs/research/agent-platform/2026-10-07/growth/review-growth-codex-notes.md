---
type: review
status: changes-requested
created: 2026-10-07
commit: 3071fef19c0d9e293e9dd41221dddf4b14dd67dc
lane: review-growth-codex
---

# Independent review of the shared error-data fold

The existing error path replaces worlds and places in maps, vectors and sets. An explicit post-order stack
rebuilds changed containers; 3071fef19 adds a per-call identity table of completed containers. The useful change
is eliminating repeated descent into a shared subtree. It does not make arbitrary identity projections pure,
bound opaque-object hashing, or recognize cycles in nodes still being visited.

## Findings and disposition

1. **BEHAVIOR CHANGE for the admitted callable contract.** `post-order` accepts three arbitrary
   `:seon.instrument/callable` values; `referenced` accepts an arbitrary projector. Neither contract enforces or
   declares deterministic projection. A projector returning successive identity values for the same leaf gives
   parent `[{:x 1} {:x 2}]`, change `[{:x 1} {:x 1}]` when its containing map is repeated. Exact probe below.
   The published EQUIVALENT claim is corrected in growth-notes.md. Task:
   `seon.error.refusal/identity-folding-assumes-stable-projectors` (writer returned `9a82ce6a9355`, 75 ms).
   This falsifies the universal helper-contract claim. It does **not** demonstrate a changed result with one of
   the five shipped identity projectors through public `references`; that stronger result remains unproved.
2. **Existing eager hashing can lose the value.** Both versions throw StackOverflowError when a rebuilt map
   contains a self-referencing `java.util.ArrayList`. The traversal correctly treats the Java list as a leaf;
   `referenced`'s closing `hash` nevertheless enters it. A finite lazy seq beside a replaced place is realized
   by the same hash, even though sequences are traversal leaves. Task:
   `seon.error.refusal/closing-a-rebuilt-container-hashes-opaque-leaves` (writer returned `5cd338b0bb65`, 30 ms).
   Do not remove hashing blindly: its stated purpose is making later set insertion depth-safe.
3. **The regression needs a small correction.** Its main identity assertion tests the intended reuse and fails
   on the parent; public `references` is the right entrance for this pure transform. But `str/starts-with?` tests
   a printed representation outside printer tests; the comment carries a date and measurements; and `every?`
   alone accepts empty output. A concrete replacement is below. The atom is a test specimen, not new production
   state, but a promise serves equally well without adding an atom.
4. **The changed docstring violates the lane rule.** `post-order` has a six-line docstring with a measured depth;
   its callback contract spans lines. A replacement is below. The existing polymorphic exemption is appropriate
   for arbitrary producer evidence and compiles; no contract was weakened by the commit.

**Design stop:** the optimization is useful, but universal equivalence is not its guarantee. Per lane-rules.md,
stop rather than add a hidden mutable-value exception. Root's options (engineering estimates, not measurements):

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| Accept completed-node reuse as explicit semantics (recommended) | The first completed composite result is reused, even with a stateful projector | About 1–2 hours: public semantics, registered-projector entrance regression, contract description, central proof | Per-occurrence projection of a repeated composite changes |
| Preserve per-occurrence semantics while memoizing only a separately established stable projection | Equality for the previous admitted behavior | At least half a day across schema/projection ownership; prove stable inputs and callbacks, not a class-name whitelist | More API/contract work; mutable projections retain repeated work |
| Remove the memo | Previous traversal semantics | Small patch and central regression adjustment | Restores the measured 7.25 GB / 11.3 s shared-depth cost |

The opaque-leaf hashing problem is a separate pre-existing producer defect, not a reason to hide or relabel it.
Its fix must preserve the original value and settle hash/set construction with the printer/evidence owners.

## Runtime and foreign boundaries

- `bin/seon status` and MCP `runtime_status` both reported default **faulted**, pid 65136, source archive
  `42e569d9d911140148b401ebbe04f10c4fe61ae7`, no missing readiness layers, 16 open faults, 174 errored receipts,
  43 unknown tests. This review did not restart, stop, reset, reload or install reviewed source into default.
- Every evaluated review form used MCP `eval_clj`, `agent="review-growth-codex"`, `cluster="default"`,
  `mode="sci"`, `namespace="review.growth"`. The agent's branch is `:agent-2ebb339c9c8c`.
- Exact committed bodies were copied to private SCI definitions, renamed `parent-post-order`,
  `change-post-order`, `parent-reference`, `change-reference`; `IdentityHashMap` was fully qualified, and
  `entries`/`object-name` were copied unchanged. Double-colon keys consistently resolve in the scratch namespace.
- **Admission failed, execution remained possible:** five definitions received produced-definition-digest
  refusals, and `entries` received a nilable-return refusal under its scratch name. No installed/adopted proof
  is claimed. Values and measurements are private SCI-body probes. Task:
  `seon.program/sci-scratch-definitions-refuse-their-produced-digest` (`ef53e2b4605d`, 28 ms).
  The entries refusal says `:seon.schema/nilable-return`, operation `seon.db/transact!`, producer
  `seon.schema.internal/contract-error!`; its return contract is an unchanged committed `[:maybe ...]`.
- Example digest refusal: `parent-post-order`, result `ef21291ea2899`, evaluation 4 ms, expected
  `e83262171cc02db3f0f653bb2c0b2e0b1596d507a16937f83bce198e0529ca38`, offending
  `b3fd8ecd2e939ef23b39cb3e3309f8876c22fdc49b3214a1e15cc40dc7840166`.
  Other definition evaluation times were 1, 4, 4, 10 and 2 ms. Definition diagnostics are not test passes.
- Foreign source edits in task-tags, review-final-codex and the printer lane were excluded from the archive.
  The reviewed source/test paths were claimed, inspected, and left unchanged at the design stop. No foreign
  session was resumed or messaged, and no foreign file was edited. No model/provider calls or test runs occurred.
- Existing stale REPL-skill descriptions of admission/context were not used as installed evidence. That skill
  is owned by task-tags; live receipt evidence above takes precedence. Existing health findings remain open;
  `seon.error/health-stays-faulted-on-dead-process-faults` and
  `seon.error/a-fault-without-an-operation-is-recorded-under-normalize` cover the historical health/attribution
  observations. The newly observed admission failure has its own task above.

## Equality, identities and the admitted boundary

The stable-value probe used one promise inside two equal-but-distinct maps with different metadata; the first
map also appeared under another parent. Parent/change data compared equal; the changed output shared the first
rebuilt map across parents, did not merge the equal-but-distinct map, and preserved both metadata maps.
Result `e9d6fb1c16b31`, 3 ms / 22 reported calls. Its initial mutable-cycle field was vacuous (an empty input
vector); disregard that field. The separate corrected probe returned `[true true]`, `e0a567af19722`, 0 ms / 6 calls.

```clojure
(let [place (promise)
      shared (with-meta {:p place} {:tag :kept})
      equal (with-meta {:p place} {:tag :other})
      input [shared {:nested shared} equal]
      a (parent-reference (constantly nil) input)
      b (change-reference (constantly nil) input)]
  {:same-data (= a b)
   :distinct-input (not (identical? shared equal))
   :distinct-output (not (identical? (b 0) (b 2)))
   :shared-output (identical? (b 0) (:nested (b 1)))
   :metadata (mapv meta [(b 0) (b 2)])})
```

**EQUIVALENT under stable, deterministic traversal preconditions:** nil/false leaf results are correctly
distinguished from missing entries using `containsKey`; identity, rather than equality/hashCode, keys the table.
Records are leaves: `seon.env/map->Environment` with `{:place (promise)}` remained identical in both copies
(`efabc0c7861b0`, 0 ms). A root lazy seq remained identical and unrealized in both. An ordinary Java list,
including a self-cycle, remains identical when no rebuilt enclosing collection hashes it.

**BEHAVIOR CHANGE counterexample**, result `ed300126a27dd`, 1 ms / 9 calls:

```clojure
(let [value {:x :world}
      run (fn [f]
            (let [counts (java.util.IdentityHashMap.)
                  project (fn [x]
                            (when (= :world x)
                              (let [n (inc (or (.get counts :n) 0))]
                                (.put counts :n n)
                                {:seon.schema/identity-value n})))]
              (f project [value value])))]
  {:parent (run parent-reference) :change (run change-reference)})
;; {:parent [{:x 1} {:x 2}], :change [{:x 1} {:x 1}]}
```

The projector's local mutable counter is a deliberately admitted counterexample, not proposed production state.
First-party schema projection calls validators and registered projection Vars (`src/seon/schema.clj:3948-3963`);
it does not establish callback purity. Shipped SCI/environment identity projections select held references
(`src/seon/sci/admit.clj:201-216`). No evidence here shows those shipped functions return changing identities.

The following catch is the disposable probe's boundary; it does not change production handling:

```clojure
(let [obj (java.util.ArrayList.)
      _ (.add obj obj)]
  (mapv (fn [f]
          (try (f (constantly nil) {:place (promise) :cycle obj})
               :returned
               (catch StackOverflowError _ :stack-overflow)))
        [parent-reference change-reference]))
```

The first uncaught parent probe reported StackOverflowError, `e942dc4449b11`, 7 ms / 10 calls; the caught change
probe returned `:stack-overflow`, `e0e641052c51a`, 4 ms / 4 calls. A root `[obj]` with no replaced sibling returned
the same leaf in both versions. A changed map with a finite lazy-seq sibling returned equal data but had realized
the seq, `e05fbf0bece37`, 0 ms / 8 calls. Do not infer “sequences are not realized” merely from the open-node cases.
A separate 10,000-deep unchanged map beside a replaced promise returned in both scratch copies (163 ms total);
this review does not claim that probe demonstrates another overflow.

## Cost and memory

Allocation is exact current **platform-thread** allocated bytes from `com.sun.management.ThreadMXBean`.
The SCI evaluation thread is virtual: `getThreadAllocatedBytes` returns -1 there; subtracting two readings
misleadingly returned zero in the first probe. We switched to one local `Thread`, delivered the result into a
promise, and joined actual exit. No persistent thread, atom or cache was added. Input construction is outside
the measured interval. This is interpreted-code cost, not compiled default's cost or full recording/printing.

| Same input, parent then change | Parent ms | Change ms | Parent bytes | Change bytes |
|---|---:|---:|---:|---:|
| 30 mentions × 100-deep map, changed promise leaf | 11.213 | 0.663 | 7,414,496 | 328,576 |
| 300 mentions × 10,000-deep map, changed promise leaf | 11,336.868 | 93.836 | 7,249,546,240 | 27,523,840 |
| 1,000-key unchanged map, second sample | 2.232 | 3.690 | 1,147,336 | 1,310,184 |
| 5,000-key unchanged map, second sample | 142.712 | 192.125 | 5,751,656 | 6,554,504 |
| 10,000-key unchanged map, second sample | 278.607 | 168.327 | 11,595,736 | 13,198,584 |
| 1,000-key all-place map, first sample | 6.822 | 7.634 | 2,763,344 | 2,915,600 |
| 5,000-key all-place map, first sample | 147.168 | 110.555 | 13,883,744 | 14,686,592 |
| 10,000-key all-place map, first sample | 632.580 | 608.779 | 28,244,968 | 29,847,816 |

The wide-map allocation slope is linear. Wall times were noisy under shared-JVM work: the two 10,000-key
changed-map samples were parent 632.580/502.285 ms and change 608.779/387.581 ms. These measurements cannot
establish a small percentage regression; the 1,000-key unchanged case does show lookup overhead (1.46 ms).
No measured regression exceeds 50 ms; do not interpret noisy individual wide samples as a clean speedup.
Wide-map work is traversal plus persistent assoc and hashing, not quadratic iteration of the map by this memo.

**Armed calls:** the joined-thread body reports none through the enclosing evaluation's call sink; private SCI
definitions were not admitted as program entities, and the synthetic `(constantly nil)` projector deliberately
excludes the five armed world predicates. Zero reported calls is not evidence of an armed production run.
The direct small SCI probe reported 3,168 calls for both walks together; no parent/change production profile
delta was collected. End-to-end armed default performance remains root's installation probe.

The 300×10,000 probe's envelope was 11,435 ms and marked slow (`ea5ff719fcaa0`); this is the requested parent
stress case, not ordinary acceptable latency. The wide batch envelope was 4,718 ms (`e8de6e785299e`). Its
individual operations stayed below one second. The identity-folding task records the large parent cost.

The table keeps strong references to each completed input composite and its result until the one call returns:
O(V) extra entries, plus the existing O(depth) explicit stack. It does not survive the call and is a mutable local
fold accumulator, not an atom, shared state owner or cross-call cache. Input sharing does not cause equality
hashing in this table. It memoizes completed composites only: O(V+E) fold visits in a stable finite DAG, not
O(V) independent of edge count; repeated leaves still invoke `open`. There is no visiting set for cycles.
Root itself is not inserted because it has no parent edge to revisit after completion.

Hashing is a separate cost: opaque Java values can recursively hash their contents every time an enclosing
changed container closes. Repeated such leaves can therefore retain work proportional to references × leaf
contents. Cycles fail as measured above. The new table does not fix that pre-existing path.

Dependency evidence: pinned Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`,
`APersistentMap.java:98-124` caches map hashes but computes them through members;
`Util.java:164-173` calls arbitrary objects' `hashCode`. Pinned Malli
`43c6dca8d2d8df3162b13725939954807be4a4ed`, `core.cljc:203-207` owns error construction,
`:353-361` owns schema-derived caching, and `:2672` supplies `validator`. None establishes purity of an
arbitrary callable. These are source guarantees, not a claim that a new dependency API was installed.

## Historical and cause-chain spot checks

The stored fact for signature `6886474456ca76ee702370272231e2bad23aef1cc5066e6d704c4ef2d0483128` names
`deep-value-probe`, process `30874-1791285324710`, exception `java.lang.StackOverflowError`, at
`2026-10-07T02:08:06.811Z` (11:08 JST). Git gives 77c9f5459 at 11:21:56 JST and 7a6878374 at 12:10:23 JST.
**The occurrence predates both fixes.** The fact's operation is the fallback `seon.error/normalize`; this fact
alone cannot attribute all three historical faults to a particular producer. Git's fix evidence identifies the
recursive canonical printer and the recursive error-data walk separately. The live read was 704 ms / 96,614
calls (`ec87611aab0fe`).

An initial lookup passed the note's 12-character signature abbreviation to `open-faults`; its contract correctly
refused it (requires 64 characters), `ed8a99b5b28be`, 5 ms / 10 calls. The corrected probe read `open-faults`
once, filtered by prefix, and inspected the complete stored fact. No guessed full identity was used.

Installed `refusal/chain` on plain finite `ex-info` causes, explicit nil projection, creation outside timer:

| Links | Kept | ms | Thread-allocated bytes |
|---:|---:|---:|---:|
| 10 | 10 | 0.287 | 216,080 |
| 100 | 100 | 0.579 | 794,880 |
| 1,000 | 1,000 | 5.317 | 6,852,984 |

Result `e56abfcbd84f7`, entire form 23 ms. This supports item 4 for an ordinary cause chain with similar stack
depth per link. It is not a proof that arbitrary suppressed graphs, changing Throwables, or trace depth have
linear cost. The chain still has equality-based seen sets; the owning errors spec already schedules identity
membership in its cut. No change to that separate behavior was attempted.

## Concrete source/test corrections for root's central landing

These are proposed patches, not edits claimed as installed. No semantic workaround was added. Shorten the
`post-order` docstring to the following and put the existing contract on one line without changing its members:

```clojure
"Returns the bottom-up fold of value using an explicit stack.
  A completed composite encountered again by identity reuses its first result."
```

Move callback grammar to its owning documentation if it is still needed. Replace the test with this one behavior
(remove the clojure.string require). The production atom count does not change; the specimen is now a promise.

```clojure
(deftest a-value-repeated-under-every-problem-is-referenced-once
  (let [place (promise)
        deep (reduce (fn [v _] {:a v}) {:place place} (range 10000))
        problems (mapv (fn [i] {:seon.error/path [i] :seon.error/offending deep}) (range 300))
        out (:seon.error/problems
             (error.refusal/references nil {:seon.error/problems problems}))
        first-value (:seon.error/offending (first out))
        bottom (loop [m first-value]
                 (if-let [[_ child] (find m :a)] (recur child) (:place m)))]
    (is (= 300 (count out)))
    (is (every? #(identical? first-value (:seon.error/offending %)) out))
    (is (= (error.refusal/references nil place) bottom))))
```

The last assertion compares the public transform's data, not its printed spelling. Existing default body bound
is five seconds; the change's shared-depth walk measured 93.8 ms, but **no current harness run was performed**.
Root should run exactly:

- `seon.error.shared-data-test/a-value-repeated-under-every-problem-is-referenced-once`
- `seon.error.refusal-test` (public reference/cause regressions)
- `seon.sci.kernel-test/an-error-holding-a-ten-thousand-deep-value-keeps-its-error`

The future stateful-projector and opaque-leaf behavior choices need entrance regressions with their fixes; the
current shared-data test does not prove those classes.

## Validation, changes and cleanup

- Archived committed HEAD `b461077c73415273a8c16b5d2526ca3849340301`; the only review diff is documentation, so
  archive src/script/test/resources equal HEAD plus this slice. Linked pinned reference-code and ignored analyzer
  configuration into the owned archive; no worktree and no shared cache write.
- `clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,488 warnings, 15,383 ms**. Warnings
  remain; this is not a zero-findings report. The archived tree excludes foreign in-flight source edits.
- Installed `seon.contracts-compile-test/check`, called through own SCI entrance with the archive root and
  archived refusal/shared-data paths, returned `[]`, **334 ms / 3,758 reported calls** (`e6d5a0dc16064`). It
  statically read archived source against its packaged projection; it did not execute a test or load reviewed
  definitions into the host. No contracts changed in this documentation slice.
- `bin/lane commit --test` currently invokes `bin/seon check`, which boots and runs tests. The user's newer
  instruction says lanes start no test runs. Therefore this is a **documentation-only lane commit**, with root's
  exact focused test list above; source/test corrections are concrete patches awaiting that central landing.
- Growth notes' universal equivalence and cost claims were corrected in place. Source/test delta: **0/0**.
  No runtime behavior changed in this review; neither restart nor reset is needed for these notes. Root still
  needs its normal adoption/restart and focused proof before claiming 3071fef19 is installed.
- Every measurement worker was joined. The owned archive is disposable and removed after recording proof.
  Preserve the private agent branch/result handles as unresolved evidence; do not retire it from inside its own
  run because growth already recorded that retirement closes admission before settlement.

## Reproducing the measurement forms

Obtain the exact source with `git show 3071fef19^:src/seon/error/refusal.clj` and
`git show 3071fef19:src/seon/error/refusal.clj`. In the **own agent's SCI namespace only**, copy `object-name` and
`entries` from the change, both versions of `post-order` with the names above, and two copies of `referenced`
whose names and calls select their corresponding `post-order`. Keep contracts and bodies unchanged; fully
qualify `java.util.IdentityHashMap.`. Do not evaluate the namespace's whole file or load it in default's JVM.

The exact shared-depth measured body (use 30/100 for the smaller row):

```clojure
(let [answer (promise)
      worker
      (Thread.
       (fn []
         (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
               tid (.threadId (Thread/currentThread))
               p (promise)
               v (vec (repeat 300
                              (reduce (fn [v _] {:a v}) {:p p} (range 10000))))]
           (deliver answer
                    (mapv (fn [[label f]]
                            (let [a (.getThreadAllocatedBytes bean tid)
                                  t (System/nanoTime)
                                  out (f (constantly nil) v)]
                              {:variant label
                               :ms (/ (- (System/nanoTime) t) 1e6)
                               :allocated (- (.getThreadAllocatedBytes bean tid) a)
                               :count (count out)}))
                          [[:parent parent-reference] [:change change-reference]])))))]
  (.start worker)
  (.join worker)
  @answer)
```

For wide rows the same thread/timer surrounded each `f` call, with inputs built once outside timing:
`(into {} (map (fn [i] [i (if changed? p i)])) (range n))`, for n = 1000/5000/10000, changed? false/true,
two samples each, parent then change. The cause-chain body used the same timer on
`(seon.error.refusal/chain e nil)` where
`e = (reduce (fn [cause _] (ex-info "link" {} cause)) nil (range n))`, n = 10/100/1000, constructed before timing.
The explicit thread exists only to read supported exact per-thread allocation counters, not as new product
execution machinery. Await actual exit; an interrupted measurement is not a result.
