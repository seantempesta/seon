# The machine

The machine Seon is built on and for, and the rules that keep builds, disk and processes bounded. The short form, with the git rules, is [AGENTS.md §8](../AGENTS.md); how a branch merges is [merging](merging.md).

## Seeing what runs

`bin/worktrees` shows everything running across every checkout at once: each worktree's branch and how far it is from main, its root, the processes under it (count, CPU, memory, threads), its stores' size; then seon processes no checkout claims (orphans: a parent of 1, or a checkout that is gone), the heaviest processes on the machine, and its load and free memory. `bin/worktrees -w` repeats it every 5 s. Root reads it when worktree sessions run in parallel and before a session ends; a worktree session reads it before it reports that nothing of its own is running.

## The target: a 16 GB MacBook M1

The target is a 16 GB MacBook M1 (owner, 2026-10-22: "I want this system to run well on a 16GB Macbook M1"). Seon itself stays within `:seon.config.machine/memory-mb` (4096: root, the fork server and the default number of agents), and `bin/check scale` fails past it. A cost that grows with something other than the work (a probe walking a 64 GiB address-space reservation took 100 agents from 0.6 s to 18 s) is found by a cost proof sized by what it walks. Like every bound, the key lives in `resources/seon/config/default.edn` ([config](config.md)).

## The clock

Seon has one clock: `seon.profile/now-ns`, the machine's monotonic CLOCK_UPTIME_RAW read by a Chez foreign procedure (9.5 ns a read, nothing allocated, 41 ns resolution; measured 2026-10-22), and `seon.profile/ms-since` for "milliseconds since t0". Every process reads the same timeline, so a stamp one process sends (the writer's `:main` commit, a message's `:ns`) is a t0 another can subtract from. The profiler's samples use the same read. `System/nanoTime` (Jolt's, 1 µs and counted from the process's start) and Chez's `current-time` are not used; a new reading of time goes through `seon.profile` (session DUP-HELPERS, task ``seon.profile/one-monotonic-clock``). Time is measured and recorded, never acted on ([AGENTS.md §4](../AGENTS.md)); [clocks](clocks.md) names every function that reads it.

## Builds and checks

Builds run as `nice -n 15 bin/build`, in parallel, with no lock (owner, 2026-10-21: "WTF is there a build lock?"; one machine-wide lock queued every worktree's build single-file on an 18-core, 128 GB machine where a build peaks under ~2 GB; each worktree builds into its own `target/`, so builds share no mutable state). Every process on a store must use the same LMDB build (`target/lib/liblmdb.dylib`, robust locks); stop a store's processes before a build that changes it.

Full checks run one at a time, niced (there is no lock: the owner removed it). Iterate with single modes.

A check mode is its body over one harness, `seon.check/run-check`: it boots root, runs the body, prints the body's report and its checks map, stops root, and ends on the one verdict line `NAME OK` or `NAME FAILED` with the exit status to match (`:then :kill` makes the mode phase 1 of a kill/reopen pair). A mode waits only on what the system emits: root's events on a tap taken before their cause (`seon.check/ready!` for spawns and clones, `seon.check/await-agents`, `seon.check/await-event`), or a watch on a reference (`seon.check/await-ref`, `seon.e2e/await-db`). An agent's records are applied ahead of its commit (`seon.agent/transact!`), so a mode reads them from its branch at the agent's `:commit` events (`seon.check/await-records`), never right after the eval answered: `mcp-proof` read too early under load. Where a mode meant "the thing happened", the producer that knows says so: an agent's eval began (`:started`, sent by `seon.repl/agent-step`, awaited (`seon.check/started`) before a kill, an interrupt or a pause merges; several awaited at once by `seon.check/await-each`, since awaiting one at a time drops the others' events), its test child forked (`:tests-started` with the child's pid, `seon.agent/run-tests-forked`), a declared proc took a message (`seon.check/probe-proc` on root's events), a tab's view is current (the render proc has nothing in flight: `: settled` on the feed, `seon.web`); a Flow pause needs no wait at all (a proc's control channel is FIFO, so the next ping reads it). It never polls and has no deadline, so a mode run without what it waits for (lease without `--set :seon.config.store/eid-lease=50`) waits until it is stopped. The time left is named in [clocks](clocks.md): measurement windows (`:seon.config.check/hold-ms`, `:seon.config.check/pages-ms`, idle's window) and a ping's bound, past which a proc is recorded `:blocked` (`seon.check/ping`). An outside client a mode drives reads its stdin from a file, so its EOF cannot hang on a pipe end held elsewhere (`mcp-proof`).

## Processes

Anything that can make threads or processes starts at n ≤ 100 under `nice -n 15`. Watch `ps -M -p PID | wc -l`, and kill your own process past 200 threads. One experiment at a time.

A process that must outlive your tool call is double-forked, which `bin/root` does. The tool host kills a background task's whole process tree when the task stops or its agent exits.

Leave nothing running when you finish, and say so in your report. Kill only PIDs you started; never `pkill` or `killall`. Delete only paths you created.

## Disk stays bounded

Disk stays bounded (owner, 2026-10-21: "cleanup shit we aren't using anymore and build in rules so this doesn't get out of control"; the tree had reached 261 GB):
- a worktree session's worktree is removed in the merge that merges it (`bin/worktree --remove NAME`, run by root after the merge; the branch stays in git);
- a worktree session's scratch lives inside its worktree (`target/`, `runs/`) and goes with it; nobody creates a new top-level directory in `~/src/seon-jolt` (the fork repositories [forks](forks.md) lists, `notes/`, `bin/`, `test-results/` (test results by reach digest, shared by every checkout) and the worktrees are the only ones);
- what is kept is what git and the store record (commits, task facts, notes); build output, compile caches and finished experiments' stores are regenerable and are deleted when their checkout or experiment ends;
- root prunes each checkout's `target/check/` to its last three runs at each merge, and a run's old generations (each a tree and a binary, ~200 MB) to the one running and the one before it.

Your scratch: under your worktree (`target/`, `runs/`) or a folder named for your worktree. The session scratch folder is shared.

## Secrets and paid runs

Secrets are never printed. Check the context before any paid model call; paid runs need the owner's go. Install nothing system-wide. Never write in `~/src/seon`.
