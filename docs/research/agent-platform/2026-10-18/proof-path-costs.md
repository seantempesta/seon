---
title: "Proof path costs: an ordinary change, edit to commit"
type: evidence
status: "lane shared-testing (Opus), 2026-10-18, measured on default after the 04:31 restart; kept current as the waste is fixed"
---

# Proof path costs: an ordinary change, edit to commit

Lane shared-testing, 2026-10-18. Owner: "none of this should be expensive. Go back to algorithmic estimates for work
done and find and fix the waste."

**What exists.** An ordinary change follows this path:

1. The edit hook (`bin/seon-hook`, REPL entrance) evaluates the changed top-level forms on the lane's branch in the
   shared root.
2. The hook then asks `my.test/check` about the reaching tests.
3. `bin/lane commit --branch-evidence AGENT` asks `seon.cluster.branch-evidence/branch-evidence` whether the branch
   proves the file.
4. The commit lints narrowly, runs the census, and finishes with `git commit --only`.

Before this lane, 0 of the last 409 commits used branch evidence. Every lane passed `--test`, which booted a 2.5-3 GB
throwaway JVM (30-90 s plus its tests).

## Ordinary path: estimate against measurement

All rows were measured on default after the 04:31 restart (HEAD `ddfc5bdd6`, warm unless marked cold). The change is
one form of `src/seon/test/cache.clj` (270 lines, 21 definitions) on the lane's own branch. The proof commit is
`5d0afa61b`. Each ratio is the measured value divided by the estimate.

| Stage | What it must do, proportional to | Estimate | Measured | Ratio | Producer, status |
|---|---|---|---|---|---|
| Hook: kondo analysis | lint one file: its size | 30 ms | 29-39 ms | 1 | clj-kondo |
| Hook: branch state | one query for the file's 21 symbols | 10 ms | 8-16 ms | 1 | `branch-state-form` |
| Hook: changed forms found | text compare of 21 spans | 1 ms | 1 ms | 1 | `top-level-definitions` |
| Hook: re-sent `declare` | nothing: `(declare x)` defines no value | 0 | +60-115 ms eval and +80-100 ms check on **every** edit of a file holding a `declare` | ∞ | `bin/seon-hook` `top-level-definitions`: **fixed** (declare dropped, 22→21 forms, 2→1 changed) |
| Eval: redefine one form | read, analyze, digest and record 1 form | 100 ms, 24 MB | 63-390 ms, 40-378 MB | up to 16× alloc | `seon.repl/redefining-one-form-costs-three-hundred-megabytes` (lane new-def-2) |
| Eval: docstring-only change | 1 form, no test | 150 ms, 30 MB | 562 ms, 450 MB | 15× alloc | `seon.repl/a-docstring-only-change-to-a-hub-function-costs-hundreds-of-megabytes` (lane new-def-2) |
| Eval: fresh agent's first request (warm) | 1 agent entity, 1 `d/branch!`, 1 `sci/fork` | 75 ms, 12 MB | 346-389 ms, 247-252 MB | 20× alloc | `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork` |
| Eval: first request after a restart (**cold**) | the same, plus cold derivations of what it reads | about 0.5 s | **3.6 s, 3.7 GB, 587k calls** (2 forms) | ~50× | **new finding**, producer not yet isolated (below) |
| Hook `my.test/check` after a body change | reach-select, then run the tests whose reach changed: their bodies (970 ms) | 1.0 s | 1.65 s, 1.66 GB (446 MB inside the bodies) | 1.6× time, 1.2 GB outside the bodies | **finding**: recording and selection overhead; 6 tests selected where the gate needs 2 (below) |
| Hook `my.test/check` after a docstring-only change | no test: behavior digest unchanged | 0 | 0.93 s, 474 MB (1 test run) | ∞ | **finding**: the hook names the symbol, so the check runs the tests (below) |
| Cold `my.test/check` after a restart | as above | ~1 s | 2.7 s, 1.4 GB | ~3× | with the cold row |
| Branch evidence (gate) | analyze 1 file, compare 21 digests, look up 2 tests by reach digest | 50 ms, 20 MB, ~50 reads | 61-69 ms, 37 MB, 6.5k calls, 106 reads (JVM); 57-190 ms through bb and prepl | 1.3-2× | `seon.cluster.branch-evidence/branch-evidence`: fine |
| Evidence: run only missing tests | the 2 tests without current evidence | their bodies | 182-191 ms, 152 MB | ~1 | `bin/lane` `run_branch_tests`: **new**, replaces a throwaway JVM |
| Guard census | parse the changed file and its base, judge changed functions | 20 ms | 24 ms (46 ms with bb start) | 1 | `seon.guard-census`: **new**, Babashka, no JVM |
| Narrow lint | kondo on 1 file with a warm cache | 50-100 ms | 630 ms (kondo itself 25 ms) | 6-12× | `bin/lane` `lint_candidate`: `cp -R` of the whole 3.5 MB, 605-file cache is 250 ms (program-proportional; `cp -Rc` clones it in 66 ms on APFS), and the `cache_shape` python runs about 100 ms. Not fixed. |
| Gate classification | classify the paths | 10 ms | 73 ms (bb start) | 7× | skipped once evidence proves |
| `git commit --only` | write one tree and commit | 50 ms | 260 ms | 5× | git index refresh over the checkout |
| **`bin/lane commit` total** | census, evidence, lint and commit | ~0.5 s | **1.46 s** | 3× | |
| **Edit to commit (machine time)** | | ~1 s plus test bodies | hook 2.5 s + commit 1.46 s ≈ **4 s** | | throwaway-JVM path before: 30-90 s boot plus tests, 2.5-3 GB per commit |

## Platform slices: the throwaway check JVM

A check proves what the shared root cannot: compiled code, boot, publication and configuration.

A cold check must:

- extract the candidate archive (git archive, about 1 s);
- fill the class cache (a miss compiles only changed namespaces);
- boot a JVM and load the program (the root's boot reaches readiness in 21.8 s on this machine);
- seed the store from the slot's template clone;
- run the named tests.

Boot dominates and is proportional to the loaded program, not to the change. A warm reuse would keep a booted JVM per
slot and `require :reload` the changed namespaces. That would be proportional to the change, about 1 s plus the tests.
Today every check boots cold, so the check path stays for platform slices only. A warm check-JVM pool is the next step,
if platform commits remain frequent.

## Cost ratchet (`seon.cost-estimate-test`): run centrally

It drives nine operations through a running agent (about 13 s), against the compiled program of the JVM it runs in. An
ordinary commit does not change default's compiled code until root's restart, so a per-commit run on default measures
the old program against the new estimate. It belongs in root's batch run after `start --head`, where HEAD is loaded and
one run judges every commit of the batch.

## Waste found and not yet fixed (findings for root to file)

1. **The first eval request after a restart costs about 50 times its warm cost.** On a warm agent, two forms of
   `seon.test.cache` measured `#:seon.cost{:ms 3624.9, :calls 586603, :alloc-mb 3674.2}`; the warm cost is
   `{:ms 342 :calls 28100 :alloc-mb 252}`. Lanes' 24-68 s hook evaluations (`logs/hook-debug.log`, `src/seon/db.clj`,
   9 forms, 68,258 ms) fit this row together with the `seon-db-nine-forms` overrun. Next step: run
   `seon.profile/measure` around the first request after a `start --head`.
2. **The selection behind `my.test/check {:seon.test/changed [...]}` and the gate's `test-evidence` disagree.** The
   check ran 6 tests for `seon.test.cache/input-path?`. The gate needs 2 of them
   (`seon.fn.publication-toolchain-test/analyzer-configuration-is-a-toolchain-input` and
   `seon.test.selection-test/gate-inputs-no-call-edge-can-reach-widen`). The extra 4 are body time a commit never uses.
   One derivation should serve both.
3. **A docstring-only change still runs tests through the hook.** The gate judges by `seon.db/behavior-digest`, so it
   needs none, but `my.test/check` with `:seon.test/changed` runs every reaching test (0.93 s and 474 MB for one). The
   check should drop changed symbols whose behavior digest equals the base's.
4. **`my.test/check` spends 1.2 GB outside the test bodies** for 6 small tests: recording, reach digests and coverage.
5. **The turn loop's test evidence threw a ClassCastException in Datahike's `datoms-diff`.** The path is
   `seon.test.runner/changed-result-data` → `seon.db/datoms-diff` (`Arrays$ArrayList` cannot be cast to `Comparable`).
   The reply printed the whole stack trace, about 200 frames, which is bulk that Seon generated itself. Reproduce it
   with `(my.test/check {:seon.test/identities [...]})` called with a vector instead of a set, as a fresh agent.
6. **The narrow lint copies the whole kondo cache per commit.** The copy takes 250 ms; a clone, or linting against a
   read-only cache, would remove it.

## Rows for `seon.cost-estimate-test/estimates`

That file is held by lane new-def-2. The eval-request stages above already have rows: `:redefine-one-form`,
`:fresh-agent-first-request`, `:hub-docstring-change` and `:reuse-one-unchanged-test`. The new row is the gate, measured
in the JVM with `seon.profile/measure`. Its measurement function is still to be written beside `measured`; the probe was
run on default, but the row has not been run inside the test.

```clojure
{:op :branch-evidence-one-file
 :why "The commit gate for one 21-definition file: analyze its bytes once, compare each declaration's digest with the
 branch's and the head's, and look up each reaching test's result by reach digest: proportional to the file's
 declarations and the changed definitions' reaching tests, never to the program."
 :estimate {:alloc-mb 40.0 :calls 8000 :reads 120 :ms 100.0}}
```

The probe form, run in JVM mode on default for agent `st-fresh-4`, measured
`{:seon.profile/ms 61.08 :seon.profile/calls 6563 :seon.profile/reads 106 :seon.profile/alloc-bytes 37008808}`:

```clojure
(seon.profile/measure
  (fn [] (seon.cluster.branch-evidence/branch-evidence store
           {:seon.store/branch b :seon.source/head (seon.cluster.branch/cluster-branch "default")
            :seon.fn/root root :seon.source/paths ["src/seon/test/cache.clj"] :seon.source/accepted-red-before #{}})))
```
