---
type: research
status: complete
created: 2026-09-17
tags: [research, dependencies, pins]
---

# persistent-sorted-set pin move — verified, then declined

Date: 2026-09-17. Lane: `pss-pin`. Branch `steward-platform`, superproject HEAD
`578d86598`. Assignment: move the `reference-code/persistent-sorted-set` gitlink
from `e1a17bbe` to upstream `8fea23b`, on the
[selection-efficiency](selection-efficiency-2026-09-17.md) §5 recommendation
("**MOVE** to `8fea23b` (clean fast-forward, 0 ahead) — `5c5999e` is a real
cold-tree correctness bug beneath every index read").

**Verdict: the fast-forward half of the premise is TRUE and the dependency's own
suites are green at `8fea23b`; the load-bearing half — "beneath every Datahike
index read" — is FALSE on two independent counts. The pin was NOT moved. No
commit. The evidence and the decision are below.**

## 1. Premise part A — clean fast-forward: VERIFIED

`git fetch origin` in the submodule, then:

```
$ git log --oneline 8fea23b..e1a17bbe     # ours ahead of upstream
(empty)
$ git merge-base --is-ancestor e1a17bbe 8fea23b
(exit 0 — e1a17bbe IS an ancestor of 8fea23b)
$ git log --oneline e1a17bbe..8fea23b     # 7 behind
8fea23b perf: hint the node reconstruction constructors (#27)
74a2f22 Add cross-platform delta walker (#26)
a8e90b3 Breadth-first warming, on both platforms (#25)
5c5999e A write to a cold tree erased the measure, and the next read restored the subtree (#24)
2b5bff9 Streaming bulk build, and a correctness pass over diff-buf and the B-tree (#23)
b8ae9a8 Add from-sorted-seq: streaming bulk build in O(depth) memory (#22)
1722663 Format-agnostic node codec, plus CBOR and transit node handlers (#21)
```

Zero local commits ahead, seven behind, clean fast-forward. §5's table is
correct on this point. Working checkout SHA equals the recorded gitlink
(`git rev-parse HEAD:reference-code/persistent-sorted-set` = `e1a17bbe`),
so nothing was uncommitted there either.

## 2. The `5c5999e` fix, and what it is guarded by

Commit `5c5999e181eebc632be0675006b2637b8b66ef1d`, Christian Weilbach,
2026-08-16, *"A write to a cold tree erased the measure, and the next read
restored the subtree (#24)"*. Its own statement of the defect:

> `_subtreeCount` is delta-maintained -- `Branch.add` does `_subtreeCount += 1`,
> with no reference to the siblings. `_measure` was not: every arm of
> `Branch.add` recomputed it with `tryComputeMeasure`, which folds the measures
> of ALL `_len` children and returns null the moment one of them is not
> resident.

Measured cost it reports: a cold `measure` after five `conj` went from 1 blob
read to 79 (39 branches + 40 leaves), non-amortising under `:weak` refs —
399 blob reads over five write-then-read rounds on a 6451-blob tree. Real, and
well evidenced.

The fix introduces `Branch.measureAfterInsert` and calls it at each `add` arm.
`src-java/org/replikativ/persistent_sorted_set/Branch.java:861` at `8fea23b`
declares it; the representative hunk (from `git show 5c5999e`, the same-len arm):

```java
       if (measureOps != null && _measure != null) {
-        _measure = tryComputeMeasure(storage);
+        _measure = measureAfterInsert(settings, measureOps, tryComputeMeasure(storage), _measure, key);
       }
```

**Every changed site is behind `measureOps != null && _measure != null`** —
`Branch.java:942`, `:1022`, `:1233`, `:1439`, `:2095`, `:2189`, `:2222` at
`8fea23b`. `measureOps` is non-null only for a consumer that configures an
`IMeasure`.

### Count 1 — Datahike configures no measure

`reference-code/datahike/src/datahike/index/persistent_set.cljc:547`, in the
comment on the fressian storage construction, states the configuration in the
dependency's own words:

> comparator = per-index via `:index-type`; **no measure**; bf self-describes
> from the blob.

`grep -rni measure reference-code/datahike/src/` returns no `IMeasure` use and
no measure passed into `Settings`; the only hits are unrelated prose in
`query.cljc` and query-planner cardinality wording in `query/execute.cljc`.
Datahike maintains `_subtreeCount` (`psset/has-subtree-counts?`,
`psset/count-slice` at `persistent_set.cljc:199`, `:201`) — which was already
delta-maintained and is not what `5c5999e` changes.

With `measureOps` null, every arm `5c5999e` touches takes the pre-existing
branch. **The fix is a no-op for Datahike**, hence a no-op for Seon. §5's
"a correctness bug beneath every Datahike index read" overstates it: it is a
correctness bug beneath every index read *of a measure-configured tree*, and
we configure none.

### Count 2 — the submodule is not on our classpath at all

Independently of count 1: `deps.edn` declares no `persistent-sorted-set`
coordinate, and nothing in `bin/ src/ test/ resources/ deps.edn` references
`reference-code/persistent-sorted-set`. Datahike pulls it from Maven:

```
reference-code/datahike/deps.edn:20
  org.replikativ/persistent-sorted-set {:mvn/version "0.4.137"}
```

and that is what resolves, on the default and the `:test` classpath alike:

```
$ clojure -Spath | tr ':' '\n' | grep persistent.sorted
/Users/sean/.m2/repository/org/replikativ/persistent-sorted-set/0.4.137/persistent-sorted-set-0.4.137.jar
$ clojure -A:test -Spath | tr ':' '\n' | grep persistent.sorted
  (same single jar)
```

So the gitlink governs **reading material only**. Moving it changes nothing our
JVM loads — not on the platform tier, not in `default`, not in a worker.

### The correspondence the current pin holds, and that a move would break

```
$ git describe --tags e1a17bbe   -> 0.4.137     (the Maven artifact we load)
$ git describe --tags 8fea23b    -> 0.5.144
```

**The current gitlink is exactly the released tag of the jar on our
classpath.** `build.clj:11` reads `(format "0.4.%s" ...)` at `e1a17bbe` and
`build.clj:14` reads `(format "0.5.%s" ...)` at `8fea23b` — upstream took a
deliberate minor bump across these seven commits, which include a node codec
change (`1722663`), a diff-buf and B-tree correctness pass (`2b5bff9`) and a
new streaming bulk build (`b8ae9a8`).

AGENTS.md §"How we work here" states why `reference-code/` exists: it "vendors
dependencies as submodules so their semantics can be READ rather than
remembered." A vendored copy earns that only while it equals what runs. Moving
this pin to `0.5.144` while the JVM loads `0.4.137` would leave every future
reader deriving our runtime's B-tree semantics from seven commits of code we do
not execute — the same shape as acting on a mirror the authority will re-decide.
That is a worse state than being seven commits stale and exact.

## 3. Tallies at `8fea23b` (isolated worktree, run anyway)

Isolated worktree `tmp/pss-pin-wt` created with
`git -C reference-code/persistent-sorted-set worktree add tmp/pss-pin-wt 8fea23b`
(a worktree of the SUBMODULE repository, so the main checkout's submodule
working tree was never touched), prepped with `clojure -T:build java`, removed
before reporting. Aliases read from the dependency's own `deps.edn`: `:test`
carries `:jvm-opts ["-Dpss.diffBufSize=256" "-ea"]` and the cognitect runner
over `test-clojure`, as §5 names.

| suite | command | result |
|---|---|---|
| JVM | `clojure -X:test` | **Ran 418 tests containing 56149 assertions. 0 failures, 0 errors.** |
| cljs / node (v26.4.0) | `npx shadow-cljs release node-tests && node target/pss/tests.min.js` | **Ran 252 tests containing 3209 assertions. 0 failures, 0 errors.** |

Note on the assignment's `clj -M:node-tests`: that alias carries `:extra-deps`
and `:extra-paths` only, no `:main-opts`, so it is not runnable as a `-M`
command. The shadow-cljs build named in `shadow-cljs.edn:5` is the real entry
point and is what ran. Its `:closure-defines` set
`default-diff-buf-size 256` — the cljs analogue of the JVM `-D` flag.

The stress grid inside the JVM run reported `PASS 45 FAIL 0` over 9 grid × 5
seeds with coverage `{:buffered 34, :removes 3409, :measure-checks 637,
:adds 5603, :ops 10800, :restores 187, :flushed 371, :stores 405,
:replaces 1788, :max-depth 6}`.

**`8fea23b` is green on its own terms.** The reason not to move the pin is not
quality; it is that the move buys nothing and costs the reference's fidelity.

## 4. Datahike's suite — why it does not apply

Assignment step 3 was conditional on Datahike resolving persistent-sorted-set
from `reference-code/`. It does not (§2, count 2): the coordinate is
`{:mvn/version "0.4.137"}`. Running Datahike's `bb test` against the moved
gitlink would have exercised `0.4.137` either way and proven nothing about the
pin. Not run, and it is not a gap.

## 5. What the orchestrator should do next

**Nothing to gate.** There is no commit, so `bin/test --platform` and
`seon.db-test seon.cluster.registry-test seon.test-support-test` have no new
input from this lane. `tmp/orchestrator/gate-requests/pss-pin.txt` records
"no pin move" rather than a SHA.

The decisions this lane surfaced, in priority order:

1. **The real delivery question is a Maven coordinate, not a gitlink.** If Seon
   wants anything in these seven commits at runtime, the change is
   `reference-code/datahike/deps.edn:20` `0.4.137 -> 0.5.144`, in the Datahike
   fork, gated by Datahike's own suite (`bb test`, kaocha,
   `-ea --add-modules jdk.incubator.vector`) plus `bin/test --platform`. That
   is inside the datahike upstream-merge lane §5 already recommends opening,
   not a standalone pin move. **It is not urgent**: with no measure configured,
   `5c5999e` buys us zero. The commits that could matter to us are `2b5bff9`
   (diff-buf and B-tree correctness) and `1722663` (node codec) — both wanting
   their own read before any bump, because `persistent_set.cljc` carries
   explicit backwards-compat node-tag handling around exactly that seam.
2. **If the pin is moved anyway, move it together with the coordinate**, so the
   vendored copy and the loaded jar stay the same code. Moving them apart is
   the only outcome this lane argues against.
3. **§5's persistent-sorted-set row should be amended** with the two counts
   above, so the next reader does not re-derive them. A follow-up lane owns
   that edit; this lane did not touch
   [selection-efficiency-2026-09-17.md](selection-efficiency-2026-09-17.md).

## 6. Boundary

- Verified only the persistent-sorted-set pin. No other submodule was fetched,
  read for currency, or touched.
- `default` (pid 53320) was never contacted; no cluster was started, stopped or
  evaluated against; no MCP evaluation was made. `bin/test` and `bin/test-fast`
  were never launched.
- The only writes to the main checkout are this note and the gate-request line.
  No gitlink, no source file, no schema was modified. `git status` for
  `reference-code/` is clean.
- `git fetch origin` inside the submodule updated remote-tracking refs only;
  `HEAD` there is still `e1a17bbe`.
- The worktree `tmp/pss-pin-wt` was removed and pruned before reporting.
- Green dependency suites are evidence about `8fea23b`, not about Seon. Nothing
  here proves anything about Seon's runtime, because nothing about Seon's
  runtime changed.
