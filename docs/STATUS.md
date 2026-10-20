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

This is R5's 0006–0010 three-way merged onto 09e81cd. **It is not committed:** par 500 hangs (below), and review fixes 1 and 2 must land before it commits.
- The diff against HEAD is in `docs/wip/r5-port.diff`, and the new tests are in `docs/wip/r5-new/r5/` (`test/r5/judge_test.clj`).
- To resume: `git apply docs/wip/r5-port.diff && cp -r docs/wip/r5-new/r5 test/`, then `bin/build seon`.

**What it does:**
- The judge is an observer: `seon.reconcile/judge-proc`, declared in `system.edn` with conn `[:judge :to-api] -> [:api :from-writer]`.
- Agents run their own attempts (`seon.repl/run!` with `:work`).
- An attempt is judged once.
- Combined runs for trains, with bisection.
- Reach-selected tests.
- `SEON_LMDB_READERS`.
- Kept from HEAD:
  - FX1's model owner: there is no provider in the reconciler; `:work` carries `:model`.
  - Model `"outside"` spawns an agent with no work.
  - The entrance's `:done` is `seon.turn/hand-back!`: the agent writes its end, root stops it, and the judge records the end.
  - `seon.entrance/attach!` waits for the reconciler's attempt.
- `seon.root/health` reads `:failed` for a root proc whose step threw.
- New check modes: `proc-failure` and `branch-cost`; `mcp-proof` is extended to attach, context, fix, done and merged onto main.
- The api's ping now shows `:pending-kinds`, `:writes` and `:agent-status`.

**Green on it:** e2e calc, conflict, train-semantic, swarm 20; smoke, system, mcp-proof, proc-failure; `bin/test` 57/919.

**par 500 hang, findings so far** (logs in the scratchpad; reproduce with `SEON_E2E_MERGE_RUNNING=32 bin/check e2e par 500`):
- Results so far:
  - 2 of 3 runs at train size 32 failed, and 2 of 3 at size 64 failed;
  - size 8 passed once (131 s), 32 passed once (104 s), 64 passed once (103 s).
- The failure happens in the **work phase, not the trains**: all 500 work attempts start, none ends, and main stops at t 536871425 after the 508 spawn writes.
- At the stall the api's ping shows `{:agents 165 :pending 333 :pending-kinds {:tx 333} :writes 333 :agent-status {:dead 335 :spawning 154 :idle 11}}`, while the writer is idle (tx-count 508, `:op-ms {:spawn 453}`) and the fork server is idle (waiting 0).
- So **335 agents died** before ending, and **333 `:tx` writes sit in the api unanswered**. These are probably the judge's `:dead` writes (`judge-proc` sends `{:op :request ... :tag 0}`). They never reach, or never return from, the writer. `:root-errors` is empty.
- Next steps:
  1. Find why the agents die: an agent's stderr, LMDB readers (`SEON_LMDB_READERS` = 64 + 2 × MAX-AGENTS), or `MAX-AGENTS`/open files.
  2. Find why the api's 333 writes do not reach the writer: the api's `::flow/input-filter` at `API-WRITES` versus the writer's answer route.
- The hang was not seen on committed HEAD, but par 500 was never run there.

## Review fixes (SEON - Main's review, `~/src/seon/docs/research/agent-platform/2026-10-20/next-review.md`; the owner wants causes fixed at their owner, no guards)

| # | finding | state |
|---|---|---|
| 1 | agents can't create namespaces (`program-ns?` gate in `seon.repl/wrap-record`; the `ns` form, `:refer`, requires and `:refer-clojure` are not recorded; main loads namespaces by name, not by dependency) | **not done**. Owner's ruling: a namespace is program unless it is `user` or `^:scratch`; delete the gate; record the `ns` form exactly; load in dependency order. Proofs: a new ns with a defn and a deftest using a `:refer`'d fn merges and passes on main; `user` defs stay scratch; `reaching-tests` finds the test. Comes before the R5 commit. |
| 2 | per-turn cost grows with the program (`observe` runs every test; R5 re-reads every source) | **not done**: reach selection over the call facts (K1), measured. Comes before the R5 commit. |
| 3 | merge changes meaning (`record-tx` drops metadata; `harvest/stage-tx` drops quoting) | not done: store the exact form (EDN plus positions) and the var metadata as facts at record time; one proof per case (`'(inc 1)`, `'[a b]`, `^:dynamic`) |
| 4 | one printer (`*value-printer*` unbound; `builtin-print`, `seon.contract/*printer*` 400-char clip) | not done: bind seon-min at the REPL boundary, delete the others |
| 5 | `seon.context/messages` cuts at 48k characters and drops `:last-evals` | not done: per-unit token budgets through the printer |
| 6 | `seon.reconcile/references` backtick parsing | not done: one edamame rule (symbol, keyword, `path#heading`, form) |
| 7 | prose in a reply without a fence is evaluated | not done: the forgiving reader as a stage, with its two laws |
| 8 | one error map (`no-agent`, `{:ok false :error}`, `{:merged? false}`) | not done |
| 9 | health: `seon.root/state` falls back to `{}` after 5 s; health uses a hand list of procs | partly done: health reads `:failed` (R5 port). Still to do: delete the `{}` fallback (`:unknown` as data) and derive the pids from `system.edn`, `:fx/*` owners included. |
| 10 | dials are env vars and hard-coded numbers; `default-max-merging` | not done: dials as config facts (idea 19). Check what `default-max-merging` evaluates to on HEAD; it should be 1 until the R5 port, then 32. |
| 11 | the census gate fails (74/488 on the R5 build) | not done: R5's `branch-of`, `running` and `actions` contracts; the 43 `:any` contracts; real predicates for `:seon.host/object`, `flow-graph` and `void` |
| 12 | (in #2) | — |
| 13 | AGENTS.md installed, CLAUDE.md a symlink to it | **done in this commit** (the step-fn rules folded into Laws) |

Other open items from root:
- `bin/build` must build LMDB from source into `target/`, without `~/src/seon-jolt/vendor-c` or Homebrew (not done).
- The UI phase acceptance is AGENTS.md's "demo is the spec" and "insight is a feature": a live dashboard and `demo.sh`'s guided tour ported onto `next/`.

## How a fresh lane resumes

1. `cd ~/src/seon-jolt/next && git log --oneline -5`. HEAD should be this STATUS commit on top of 09e81cd.
2. Build: `nice -n 15 /usr/bin/lockf -k ~/src/seon-jolt/build.lock bin/build seon`. The source must have `bin/env`, which needs `~/src/seon-jolt/vendor-c/lmdb` for now.
3. Review fix 1 on HEAD (namespaces): `src/seon/repl.clj` (`wrap-record`), `src/seon/program.clj` (`program-ns?`, `record-tx`, `load-all!`), `src/seon/harvest.clj`. Write the proof as a check or deftest first.
4. Then fix 2, then re-apply the R5 port (`docs/wip`), chase the par-500 hang, and run `bin/check 100` and `bin/test`. Commit.
5. Then review fixes 3–11, R4 (`docs/wip/r4-wip`, see below), and the rest of the list below.

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
