---
type: research
status: measured
date: 2026-10-09
lane: platform-step-card
---

# What `bin/seon check --test` spends its time on (platform-step-card, 2026-10-09)

**Expected cost vs measured.** A host-bound function change proven by one test namespace should cost what the work is
proportional to: reload the changed namespace and its dependents (well under a second to a few seconds) plus the
tests (a warm test body runs in 2-6 ms, card `COST-REG.md`; the 14 tests of `seon.ref-test` take 6.1 s warm on
default). Measured: **113 s uncontended and 178 s under contention** for those 14 tests. That is 15-20x the warm
test cost and two to three orders of magnitude over one test. **More than 90% of the time is spent on work
independent of the requested tests.**

The card that designs the replacement is `docs/seon/schedule/cards/PLATFORM-STEP.md`.

## Runs

Both runs requested `--test seon.ref-test` (14 deftests). Both were red on the open task
`seon.ref/a-missing-anchor-ranks-headings-by-shared-prefix`, which does not change the timings. The phases are the
check's own `:seon.operator/phases` and `:seon.operator/test-phases`, read from the operator's diagnostic EDN
(`data/clusters/default/logs/operator-check-*.edn`). The boot layers come from polling the throwaway JVM's
`seon.operator.runtime/running-instances` over its prepl while it ran.

| phase (ms) | R1 `check --test` on HEAD f2c0b15d2 (two other lanes' checks running) | R2 `check --at <HEAD + comment in src/seon/ref.clj>` (uncontended) | proportional to the tests? |
|---|---|---|---|
| candidate archive (`committed-source!`) | 209 | 1,484 | no: the whole tree |
| rehearsal: publish + adopt on disposable branches of default | 12,358 | 0 (`--at` skips it) | no: the whole diff from default's published commit |
| slot wait | 64,650 | 2 | no: one JVM per root slot |
| prepare: throwaway archive + classpath + `share-caches!` | 8,058 (1,791 + 1,119 + 5,083) | 8,986 (1,514 + 830 + 6,554) | no |
| JVM launch to `start!` (boot-ms - ready-ms) | 8,443 | 14,627 | no |
| boot `start!` to ready | 67,329 | 67,656 | no |
| of which `:seon.source/current` (publication onto an empty store) | n/a | **61,219** | no: the whole program |
| of which config, SCI context, flow graph, web | n/a | 5,953 | no |
| tests (`bin/test-check` over prepl) | 11,693 (run 11,506) | 16,319 (run 16,174) | **yes** |
| teardown (terminate + delete root), by difference | ~5,200 | ~4,200 | no |
| **total** | **178,023** | **113,337** | |

The R2 boot layers (cumulative ms from `start!`): prepl 171, store 484, **source/current 61,703**, branch 61,809,
config 63,209, SCI ctx 66,064, flow graph 67,624, web 67,655.

For comparison, the same layers on default (pid 40778, warm store at the same published commit): ready 10,158 ms,
with `:seon.source/current` at 8,056 ms. So **about 53 s of the throwaway's boot is whole-program publication into
an empty store.** It produces the same facts for every check of the same base commit.

Warm cost of the same tests on default (`bin/test-check default --ns seon.ref-test`): 6,078 ms for 14 tests.

## Phases a host-bound function change needs

| phase | needed? | why |
|---|---|---|
| compiled code of the changed namespaces | yes | the platform runs compiled code on every branch, so the change must be compiled into a JVM |
| the reached tests | yes | they are the proof |
| a store holding the program's facts | yes, but not rebuilt | tests select and record by definition digests, so the facts must be present. The base commit's facts are identical for every check, so they can be built once per base commit and cloned |
| whole-program publication onto an empty store | **no** | it re-derives facts that are identical for every check of the same base (61 s) |
| rehearsal on the live store | only for schema resources, publication, adoption, boot, config | for an ordinary function, the throwaway's own adoption already proves that the candidate loads |
| full archive + private analysis-cache copy | no | 8-9 s. A snapshot that stays in place and receives only the changed files does not need either |
| teardown on the answer path | no | 4-5 s. Exit must still be awaited, but the reply does not need to wait for the deletion |

## Floors

- **(a) Fresh JVM per change with cached classes, the store cloned from a per-base template, and only the diff
  adopted.**
  - archive or snapshot copy: about 1.5 s
  - JVM launch: 8-15 s measured today; default's own launch is about 10 s
  - store clone: 3.9 s measured, for `cp -c -R` of a fresh-boot store of 79 MB and 18,599 files (APFS clone)
  - adoption of the unchanged base: about 8 s, which is default's `:seon.source/current` today. It should become
    a commit-identity compare, which is far less
  - adoption of the one changed file: rehearsal-sized, 1.7-12 s
  - tests in a fresh JIT: 16 s for 14 tests, which is about 2.6x warm
  - **Floor: about 25-35 s plus the tests.** This is still not "seconds", because JVM launch and per-boot
    adoption alone take about 20 s.
- **(b) A warm, pre-started platform JVM per base commit.** Its source root is a snapshot directory. A change is
  written into the snapshot (`my.program/write-branch!` with `:seon.fn/root` set to the snapshot). The JVM's
  development adoption path (`seon.cluster` "development loaded definitions" through "development cluster
  converged": entities, `require :reload` of the changed namespaces and their dependents, arm, record) reloads
  only what changed. The reached tests then run warm.
  - Floor: incremental adoption of one file plus the tests. Today that is about 1.7 s adoption plus up to 7 s
    publication of the file (`docs/research/agent-platform/2026-10-07/publish-cost2/check1.edn`), and the 7 s is
    itself the publish-cost defect already on the schedule. The tests take milliseconds each warm.
  - **Floor: seconds, and about 1-2 s once one-file publication is proportional to the file.**

## The write-back seam (verified)

`my.program/write-branch!` passes `:seon.fn/root` straight to `seon.cluster.source/write-back!`. That function
refuses a file whose bytes do not hash to the `:seon.fn.file/digest` that the branch's base commit recorded. At the
REPL (JVM mode on default, 2026-10-09), the recorded digests of `src/seon/ref.clj`, `src/seon/fn.clj` and
`src/my/program.clj` equal the bytes in the published archive `data/source/f54e73c83…`. They differ from the
checkout, which has moved past that commit:

| file | recorded | published archive | checkout |
|---|---|---|---|
| `src/seon/ref.clj` | f134ab178816 | f134ab178816 | 8ccea38745da |
| `src/seon/fn.clj` | e7fc32614913 | e7fc32614913 | 179728ac13e0 |
| `src/my/program.clj` | 91057e857700 | 91057e857700 | 8f8a87049340 |

So write-branch! can write into any snapshot tree, provided the snapshot is a copy of the **published** commit's
archive, not HEAD's. Root already did this by hand in d0c42078b: it wrote back into a copy of the branch's base file
in 117 ms. It cannot write into the checkout whenever HEAD has moved past the published commit, and that is the
refusal d0c42078b recorded.

## Findings outside this card

- **Empty-store publication doubled in two days.** On 2026-10-07 (`publish-cost2/check1.edn`) the throwaway reached
  ready in 36,710 ms; today it took 67,656 ms uncontended, with 61.2 s in `:seon.source/current`. Not yet bisected.
- **Dead checks leave their disposable roots behind.** `data/check/63c6dc965958-28984`, `ce061d06e9a1-20233` (334 MB)
  and `e6d6bfb74f28-56765` (89 MB) belong to dead pids. A killed check skips `candidate-tests!`'s `finally`, and
  nothing sweeps the root. Not deleted here: they are not this lane's roots.
- A trivial JVM-mode `eval_clj` on default spent 236 ms in `:settle`, over the 100 ms recording bound (CLAUDE.md
  "Recover facts, not execution").
