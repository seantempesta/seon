# The machine

The machine Seon is built on and for, and the rules that keep builds, disk and processes bounded. The short form, with the git rules, is [AGENTS.md §8](../AGENTS.md); how a branch lands is [landing](landing.md).

## The target: a 16 GB MacBook M1

The target is a 16 GB MacBook M1 (owner, 2026-10-22: "I want this system to run well on a 16GB Macbook M1"). Seon itself stays within `:seon.config.machine/memory-mb` (4096: root, the fork server and the default number of agents), and `bin/check scale` fails past it. A cost that grows with something other than the work (a probe walking a 64 GiB address-space reservation took 100 agents from 0.6 s to 18 s) is found by a cost proof sized by what it walks. Like every bound, the key lives in `resources/seon/config/default.edn` ([config](config.md)).

## Builds and checks

Builds run as `nice -n 15 bin/build`, in parallel, with no lock (owner, 2026-10-21: "WTF is there a build lock?"; one machine-wide lock queued every lane's build single-file on an 18-core, 128 GB machine where a build peaks under ~2 GB; each worktree builds into its own `target/`, so builds share no mutable state). Every process on a store must use the same LMDB build (`target/lib/liblmdb.dylib`, robust locks); stop a store's processes before a build that changes it.

Full checks run one at a time, niced (there is no lock: the owner removed it). Iterate with single modes.

## Processes

Anything that can make threads or processes starts at n ≤ 100 under `nice -n 15`. Watch `ps -M -p PID | wc -l`, and kill your own process past 200 threads. One experiment at a time.

A process that must outlive your tool call is double-forked, which `bin/root` does. The tool host kills a background task's whole process tree when the task stops or its agent exits.

Leave nothing running when you finish, and say so in your report. Kill only PIDs you started; never `pkill` or `killall`. Delete only paths you created.

## Disk stays bounded

Disk stays bounded (owner, 2026-10-21: "cleanup shit we aren't using anymore and build in rules so this doesn't get out of control"; the tree had reached 261 GB):
- a lane's worktree is removed in the landing that merges it (`bin/worktree --remove NAME`, run by root after the merge; the branch stays in git);
- a lane's scratch lives inside its worktree (`target/`, `runs/`) and goes with it; nobody creates a new top-level directory in `~/src/seon-jolt` (the fork repositories [forks](forks.md) lists, `notes/`, `bin/`, `test-results/` (test results by reach digest, shared by every checkout) and the worktrees are the only ones);
- what is kept is what git and the store record (commits, task facts, notes); build output, compile caches and finished experiments' stores are regenerable and are deleted when their checkout or experiment ends;
- root prunes each checkout's `target/check/` to its last three runs at each landing, and a run's old generations (each a tree and a binary, ~200 MB) to the one running and the one before it.

Your scratch: under your worktree (`target/`, `runs/`) or a folder named for your lane. The session scratch folder is shared.

## Secrets and paid runs

Secrets are never printed. Check the context before any paid model call; paid runs need the owner's go. Install nothing system-wide. Never write in `~/src/seon`.
