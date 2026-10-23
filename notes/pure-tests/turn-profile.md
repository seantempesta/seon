# One loop turn, profiled (pure-tests, 2026-10-23 06:50, load 25-28 on 18 cores)

How: a scratch root on this worktree's binary (jolt/pure-tests at b6c98cf), source = a clone
of the checkout, dirs src + test + schemas (as source-test's restart test), scripted provider;
one server eval ran `(seon.e2e/loop-turn! sys)` and, in the same eval (one profile epoch:
seon.repl opens one per eval), `(seon.profile/top (seon.profile/touched) 50)`: root's
process, every contracted call counted, 1 in 64 timed (times are estimates, inclusive).
Store kept: scratchpad loopprof/runs/store.

## The turn: 393 s, five attempts instead of two

| attempt | ms | outcome |
|---|---|---|
| loop/gen#1 | 103 733 | :cap, stray-red `seon.proofs-test/a-test-reads-no-input-its-digest-misses` (this branch's own guard read user.dir/test, absent in an agent: moved to a check step, 86b2b08) |
| loop/gen-r1-design#1 | 58 876 | green |
| loop/gen-r1-implement#1 | 85 049 | :cap proof-red app.proofs/gen-test |
| loop/gen-r2-design#1 | 63 659 | green |
| loop/gen-r2-implement#1 | 81 588 | :cap proof-red app.proofs/gen-test |

So an attempt costs 59-104 s; the restart test's green turn (work + merge) was 193 s at load
40-53, and check step e2e-loop's (src only, 1713 forms) 43 s at load ~25 (work 17.5 s, merge
23 s with 0 model turns). An earlier run here with no provider (each attempt ending at its
first model call) still took 31-36 s per attempt: about 30 s per attempt is overhead before
any model work.

## Root's process during the turn (seon.profile/top)

| function | ms (est.) | calls | share of 393 s |
|---|---|---|---|
| seon.writer/writer-proc (inclusive) | 141 573 | 87 | 36% |
| seon.merge/program | 13 526 | 20 (4 per attempt, ~0.68 s each) | 3.4% |
| seon.program/live-forms (inside program) | 10 674 | 20 | (in the above) |
| seon.writer/branch-merge | 10 034 | 5 (~2 s each) | 2.6% |
| seon.program/form-row | 6 568 | 42 401 (2120 per live-forms) | (in live-forms) |
| seon.program/live? | 3 731 | 42 401 | (in live-forms) |
| seon.program/form-of | 2 858 | 42 081 | (in live-forms) |
| seon.render.proc/main-info | 1 947 | 9 | 0.5% |
| everything else contracted | < 1 000 each | | |
| eval thread's own CPU | 13 | | it waited |

Measured apart in a REPL on Seon's program db (2479 live forms, 71 847 datoms, load 11):
seon.merge/program 0.67 s wall = 0.67 s CPU, 0.54 GB; live-forms 0.54 s, 0.39 GB;
seon.program/reach-digests 3.1 s, 7.4 GB (an agent's test-plan calls it twice a turn).

## What is accounted for, and what is not

- writer-proc: ~142 s, of which its contracted children ~25 s (program 13.5, branch-merge 10,
  conn-main 0.8). The other ~115 s are writer work outside contracted functions (Datahike
  transacts and merges of branches holding the whole program as facts, the :branch-diff op);
  the 1-in-64 sample of 87 calls makes this an estimate, an order of magnitude only.
- ~250 s are outside root's process: the agents (forked processes, their own counts not read
  here): spawn, merge-main (3 x seon.merge/program in the agent), each turn's observe
  (test-plan: 2 x reach-digests ~6 s CPU), the context render, the forked test run.
- Ruled out: (b) the gate running Seon's reached tests: changing seon.fixtures/generation
  moves 2 of 444 digests (~0.35 s recorded); (e) the model: scripted.
- Load: at load 25 wall ~ CPU in the REPL measures; at 40-60 (the restart test) the same work
  takes ~2-4x longer.

## Simple fixes, by owner

| what | cost now | fix | owner |
|---|---|---|---|
| seon.merge/program, the whole program per call, 4+ per attempt in root and 3 in each agent merge-main | 0.67 s and 0.54 GB each | derive the three-way from what changed (Datahike's branch diff), not three whole-program reads; global-ids measured O(program) | global-ids (merges) |
| writer-proc's unaccounted ~115 s | ~30 s per attempt | profile the writer's ops by kind (commit, branch-merge, :branch-diff): count and time per op | global-ids / dry-run |
| reach-digests in every turn's test-plan, twice | 3.1 s, 7.4 GB each | queue in closure, edges once per call, base digests once per attempt, branch's only for what changed | pure-tests (with shrink2's brief3) |
| the agent side (~250 s) | unknown | read an agent's own profile per eval (its records carry :fns) and the turn's splits | dry-run (turns) |
