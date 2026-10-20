# next/ status (lane A1, 2026-10-20 16:40)

`next/` is the Jolt version of Seon, assembled by lane A1. It is a local repo and is never pushed.

**How to resume:**
1. Read this file and `git log`.
2. Build with `bin/build seon`, or `bin/build all` to rebuild from Chez up. Run builds under `nice -n 15 /usr/bin/lockf -k ~/src/seon-jolt/build.lock`.
3. Run the suite with `bin/check 100` (about 13 min at load 7–14), then `bin/test` (57 tests).

## Committed phases (each booted and passed `bin/check 100` unless noted)

| commit | phase |
|---|---|
| 0bf56b7 | runtime and forks |
| 80797ce | core (f2-final as `seon.*`) |
| 73b3f96 | contracts, schema layer, recording, flush window |
| 3c5d363 | tasks, reconciler, merge as a task |
| d1f8d22 | run-ahead writer, lean eval record, census gate |
| 87928db | FX1 effects (owner procs, `my.fx`, model through root's `:model` owner) |
| b7459c3 | R3 (incremental reconciler, trains off by default, model token bucket, deepest-first) |
| e0c9963 | system procs declared in `resources/seon/system.edn`; `start!` builds them, `apply!` update!s only what changed |
| a721881 | MCP (`seon.entrance` and `seon.mcp`); outside evals recorded `:outside` |
| 8715527 | clean rebuild: Chez cd1e16c, Jolt 3b7e8525, konserve stable jfasl; generative checks run in the release binary |
| 66ac239 | RC1: root's agent sockets are `jolt.async.remote` ends; agents stay single-threaded (physics, see DRAGONS.md); −105 lines |
| 09e81cd | scale 1000 fixes: O(1) branch check in the writer; 4 KB remote-end buffers. Now 3.97 s and root 111 MB, against 9.9 s and 311 MB. Proven by smoke, robust, e2e calc, swarm 100 and threads; the full suite has not run on this commit |

## In flight, uncommitted: the R5 port (`notes/option3-R5-trains.md`)

This is R5's 0006–0010 three-way merged onto 09e81cd. A copy of the diff is in `docs/wip/r5-port.diff`, and `test/r5/` is new.

**What it does:**
- The judge is an observer: `seon.reconcile/judge-proc`, declared in `system.edn` with conn `[:judge :to-api] -> [:api :from-writer]`.
- Agents run their own attempts (`seon.repl/run!` with `:work`).
- An attempt is judged once.
- Combined runs for trains, with bisection.
- Reach-selected tests.
- The LMDB reader-slot dial.
- Kept from my HEAD:
  - FX1's model owner: there is no provider in the reconciler; `:work` carries `:model`.
  - Model `"outside"`: the agent is spawned with no work.
  - The entrance's `:done` is now `seon.turn/hand-back!`: the agent writes its end, then root stops it and the judge records the end.
  - `seon.entrance/attach!` waits for the reconciler's attempt.
- The proc-failure defect is fixed: `seon.root/health` reads `:failed` for a root proc whose step threw.
- New check modes: `bin/check proc-failure` (OK), `mcp-proof`, now extended (attach, context, fix, done, merged onto main: OK), and `system` (now expects `:judge`).

**Green so far:**
- e2e calc, conflict, train-semantic (a red combined run lands `a` alone; `b` goes behind, then merges), swarm 20, smoke, system, mcp-proof, proc-failure.
- `bin/test` 57/919.

**par 500, by train size** (`SEON_E2E_MERGE_RUNNING`):

| train size | result |
|---|---|
| 8 | 131 s, 155 MB |
| 32 | 104 s, 154 MB |
| 64 | one run 103 s, 148 MB; one run hung in the work phase: 500 attempts started, none ended in 180 s, no root errors |

That hang is not reproduced or explained yet. Repeats are running, with logs in the scratchpad (`par500-*.log`).

**Next for R5:**
1. Find the par-500 work-phase hang.
2. Set the default train size from the measurements, probably 32.
3. Full `bin/check 100`.
4. Commit.

## Still to do, in root's order
- **R4** (learning loop, errors that file their own work). 01–03 were applied with two small rejects (the attempt pull list and the attempt schema gain `:refusals` and `:waste`). The saved work-in-progress is in `docs/wip/r4-wip/`: `tracked.diff` plus the new files `seon.learn.edn`, `error.clj`, `learn.clj` and their tests. 04 (the reconciler) and 05 (turn) must be ported by hand onto R3/R5's reconciler; 06 needs only `exp.clj`, since `data.json` is already a dependency. R4's recorded-error facts are where the proc-failure "error fact" lands.
- **Then:**
  - the reach-digest test runner, with the non-literal-resolve lint;
  - the seon-min printer, with the elided-quoted-form test;
  - renders and UI (W1; views as data; zoom; chat; context as the AI render, warm allocation ≤ 2.5 MB; ideas #1, #3, #7; screenshots);
  - ideas #5, #8 and #19;
  - embeddings (EM1);
  - the reclaimer proc (L1);
  - errors from non-request messages delivered;
  - COMPARISON.md.

## Findings to carry into COMPARISON.md
- **Scale 1000:**
  - fixed: O(n²) through `d/branches`, and 64 KB per remote end;
  - left: serial `d/branch!` (0.8–2 ms, a roster append per branch) and `fork()` of a large fork server (about 1 ms); a batched `branch!` in the Datahike fork is the next step.
- **RC1:**
  - effect call (echo) p50 127.5 µs, against 189 before;
  - the floor is 55 µs, against 117;
  - fan-in throughput into one reader is 118–199k msg/s, against about 1M (the one-reader fan-in is a follow-up);
  - `::flow/undeliverable` for orphan outcomes is not wired.
- **Superlinear at par 500:** each merge rebuilds the whole program map. The fix is M1's filed index-tree diff, for a follow-up lane.
- **Jolt:** release builds broke test.check until the Chez vfasl fix (8715527). Jolt's `clojure.repl` has no `source`, so we have our own.
- **The eid lease:** 100,000 × N approaches tx0 (2^29) after about 5,000 agents per boot.
- **Swarm/par slowdowns** from R3's era: swarm 20 took 32 s against M1's 11 s, and par 50 took 165 s against 95 s. Both are now much faster (par 50 is about 3 s with R5).
- **Paid DeepSeek so far:** about 7.1k tokens across 6 calls.
- **Strict cost table (#16):** to note.
