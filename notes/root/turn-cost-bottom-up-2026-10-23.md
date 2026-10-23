# Turn cost, bottom-up (2026-10-23)

**Bottom line:** a scripted loop turn spends **96% of its time recomputing whole-program values that do not change**. The model, parse, eval, render and the forked proof run together cost about 3 s of 112 s. The biggest cost is `seon.agent/test-plan`: it recomputes `seon.program/reach-digests` over all 441 tests and 2630 forms, twice per call (once for the branch, once for its base). Each call takes 10–16 s and allocates 21 GB. One loop turn calls it **5 times**, about 66 s in all. Next come the `seon.merge/program` whole-program reads, 0.8–0.9 s each, called about 25 times (about 23 s). These sit in contract gaps, unmerged entries, merge-main, the writer's branch-diff and a resumed spawn's load-diff.

Worktree `next-turn-cost` (`jolt/turn-cost`, nothing committed). Its own root ran under `nice -n 15` with `SEON_PROFILE=scripted`, on a clone of HEAD (b39cef5) holding dirs src, test and schemas, as `seon.source-test/a-restart-catches-up-and-a-new-generation-resumes` runs it. Store: 69,944 datoms, 2104 forms (2630 live forms counting schemas), 1983 vars and 441 tests. Measured with `System/nanoTime`, `seon.profile/cpu-ns` and `alloc-bytes` (this Jolt has no `jolt.host/ticks-of`). The CPU time was about equal to the wall time throughout, so all of this is compute. None of it is waiting.

## The rows: estimate against measurement

| step | estimate | measured | × | cause | simple fix |
|---|---|---|---|---|---|
| model reply (scripted) | <1 ms | 1–2 ms (`:model-ms`) | ok | | |
| parse reply (`reader/forgive`) | <1 ms | 0.09 ms | ok | | |
| eval + record 2 forms (`repl/eval-string`) | <2 ms | 11–15 ms warm, 43–63 ms in the turn; 7 MB | 7–30× | the recording path allocates 3.5 MB per form (not dug into) | profile it later; it is small next to the rest |
| record turn facts (`agent/transact!`) | 2–5 ms | 1 ms (`:record-ms`) | ok | | |
| **reach digests** (`program/reach-digests`, `program.clj:769`) | 1–10 ms | **6.6–10.5 s, 10.7 GB per call** | ~1000× | the whole program each call, for every test. The BFS `(into (vec more) …)` at `program.clj:811` copies the frontier on every pop, so it is quadratic. Edges are rebuilt on every visit (lazy seqs). 441 tests × about 748 reached forms. | (1) Build adjacency once (`id → [edge ids]`) and walk it with a stack. In the REPL that took the closures from **4702 ms / 8 GB to 386 ms / 138 MB** (12×), with identical closures for all 441 tests. (2) The base's digests are fixed for the whole attempt: compute them once per base commit. (3) Better still, re-digest only the tests that reach `program/divergence`'s changed keys (16 ms) and take the rest from the base. |
| **test-plan** (`agent.clj:302`, two reach-digests at `:327` and `:329`) | (inside digests) | **9.7–16.6 s, 21 GB** | | calls reach-digests on the branch **and** on the base | the same fixes; also `live-forms` costs 530–640 ms per db |
| fork the proof run (`lib/fork!`) | 5–20 ms | 5.8 ms (no-op child) | ok | | |
| run the task's proofs (child) | ~20 ms | 9.6 ms (1 test reused); 0.66–1.04 s when 9 tests ran | ok to 50× | 9 tests ran because the branch's changes reach them. That part is correct. | |
| contract gaps (`turn.clj:62-66` `added-gaps`) | — | **1.0–1.8 s** | | two `pm/program` whole-program reads (base and branch) on each observe | ask `divergence`'s changed keys for contracts |
| unmerged (`my/branch.clj:25`) | — | **0.5–1.4 s** | | `pm/program db` (the whole program) to find a few unmerged entries | read the unmerged-entry facts directly |
| `pm/program` (`merge.clj:77`) alone | — | 0.76–0.88 s, 500 MB | | `live-forms` + defs over the whole program | |
| render the next context | 10–50 ms | 61–88 ms (`:context-ms`; the outside read took 1037 ms) | ~1.5× | | |
| stage (`stage/stage-tx`) | — | 58–61 ms | | `ns-interns` built for each of 1983 vars | look each symbol up in the namespace, rather than building `ns-interns` per var |
| spawn, new branch | 5–20 ms | 28–62 ms to `:ready` | ok | | |
| spawn, resumed branch (`agent.clj:600` `load-diff!`) | 5–20 ms | **2.5–3.0 s** | 150× | a task's branch already exists at its first attempt, so the attempt is "resumed" and diffs the whole program | load-diff only what the branch changed (divergence) |
| merge-main (`agent.clj:202`, three `pm/program`) | — | **2.5–5.2 s** | | three whole-program reads plus unmerged's | three-way over the changed keys only |
| writer `:branch-diff` (`writer.clj:186-190`, and `:30`) | — | **2.6 s, inside the writer proc** | | four `pm/program` reads. **The writer is blocked meanwhile**, so every branch's transact waits. | the same: changed keys only |
| release (`e2e/release!`, `fixtures/program-txs` + `create!`) | — | ~14–16 s before `run!` | | not dug into; outside `:turn-ms` but inside the test's wall time | likely another whole-program read; profile it next |

## One whole scripted turn, accounted (measured)

`(seon.e2e/loop-turn! sys)`, with marks on stderr at Seon's one clock (`seon.profile/now-ns`). The marks were added for measurement only and reverted. Turn = 112.5 s (other runs: 121, 129 and 130 s; the restart test measured 157–193 s).

**Attempt `loop/gen#1`, 56.6 s.** Every ms is accounted for:
- spawn + init: 0.03 s
- observe #0: 19.8 s (test-plan 16.6, fork + run 0.015, gaps 1.8, unmerged 1.4)
- act (the only model turn): 0.15 s (basis 1 ms, render 88 ms, model 2 ms, eval 63 ms)
- observe #1: 19.3 s (test-plan 15.9, fork + run 1.0 for 9 tests, gaps 1.6, unmerged 0.8)
- end!: stage 0.06 s
- root's `:tests` request (`reconcile.clj:636`): the agent runs `run-tests-forked` again, 14.2 s (test-plan 13.2, run 1.0)
- `:branch-db`, `:branch-diff` and judge write: 3.0 s

**Attempt `loop/gen-merge#1`, 37 s.**
- resumed spawn's load-diff: 2.8 s
- merge-main #1: 5.2 s
- observe: 13.2 s (test-plan 11.1)
- merge-main #2: 2.5 s
- root's `:tests`: 10.6 s (test-plan 9.7)
- branch-diff: 2.6 s

**Then on root:** the octopus merge into main (about 2.75 s, its own three-way), the git commit (0.2 s) and quiescence. About 3 s in all.

**Sum:**

| part | time |
|---|---|
| test-plan (5 calls) | 66.5 s |
| other whole-program reads | ~23 s |
| root's merge | ~6 s |
| everything the estimate lists | ~3 s |

That is about 99 of 112 s. The rest (about 13 s) is the spaces between marks: the reconciler's steps, the fork server and sockets. Release (about 15 s) comes before `turn-ms`. A real model would add its network time to act's 2 ms. That part is outside this budget and was not measured here.

## Why it repeats, and what fixes it

The same unchanged value is computed many times:
- **Reach digests:** 10 per turn. Each test-plan computes the branch's and the base's; there are three test-plans per worker attempt and two per merge attempt.
- **Whole-program reads:** about 25 `pm/program` reads.

So the turn's cost grows as (size of the program) × (number of observes), not with what changed.

The fixes, in order of payoff:
1. Fix the quadratic BFS and build the adjacency once (REPL-proven 12× on the closures).
2. Compute the base's digests once per attempt.
3. Make root's `:tests` request reuse the turn loop's last observe instead of planning again. The turn loop already ran the same proofs on the same db.
4. Change every observe and merge read to start from `program/divergence` (16 ms), not from the whole program.

With 1–3 alone, each test-plan should drop from about 15 s to about 1.5 s (estimated) and the turn from 112 s to about 40 s. With 4 as well, the turn should land in the estimated 50–100 ms band, plus root's merge.

## Left behind

- The worktree's `target/seon` was built with the measurement marks. They write to a scratchpad `marks.log`. Rebuild before using this binary; the source is reverted.
- Kept experiment stores: `next-turn-cost/runs-noprovider`, `runs-2` and `runs-3`. One store (`runs`, from the second instrumented attempt) was removed.
- Root stopped. Nothing of this session is running.
