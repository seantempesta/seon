---
type: research
status: measured
date: 2026-10-09
lane: store-gc
---

# The first retention collection of default's store, rehearsed (lane store-gc, 2026-10-08 22:00-22:45 UTC)

Closes `seon.maintenance/the-first-collection-of-a-785000-file-store-is-unmeasured` (retired in
`resources/seon/tasks/seon.maintenance.edn`; its question is answered below). Follows slow-alloc.md item E.

## Verdict

The scheduled run fits. On a clone of default's store the exact schedule handler (`seon.maintenance/collect!`, not a dry
run) completed in a 4 GB JVM: **763 s wall (12.7 min), peak heap used 1.89 GB, live set after GC about 0.85 GB**, swept
**740,671 of 796,403 files** (93%), **4.21 GB -> 0.173 GB**, `:complete? true`, `:roots-verified? true`,
`:verification-pass-swept 0`, `:unstored-digests 0`. No Full GC; longest pause 1.7 s (a young pause during the mark).

| Phase (real run) | Measured |
|---|---|
| mark (`seon.cluster.registry/collect!` first pass, includes key count and `reachable-in-branch` of every roster branch) | 424 s |
| sweep, 740,671 deletes | about 150 s (0.2 ms per file) |
| verification pass (second `collect!`) + root reopen | rest of the 763 s |
| dry run on the same store (mark only) | 666 s wall, `mark-duration-ms` 524 s, peak used 1.76 GB |

Default's JVM is a different case from my solo 4 GB JVM: it already holds about 1.2 GB used (status: `heap-used` 1.17 GB,
cache 491 of 512 MB), so the collection's 0.85 GB live plus allocation headroom has about 2.5 GB of margin. I did not
measure inside a loaded JVM. `-XX:+ExitOnOutOfMemoryError` is set on default, so an OOM would be a restart, not a failed
run. The collection holds the sweep permit for the whole mark and sweep (sys CPU 20 min in the rehearsal: the file
deletes are system time), so expect other writers' latency to suffer for about 13 minutes.

## Recommendation for 03:00 UTC

Let it run. The bound that holds: finishes within 15 minutes on a quiet store, 1.9 GB peak. Caveats the rehearsal could
not cover: it ran on a quiescent copy while the live run races lanes' commits (the second pass will sweep a few files,
as `seon.cluster.registry/collect!` documents) and the clone predates root's two restarts (22:28 and 22:37 UTC), so those
JVMs' commits are not in it. A fresh JVM after those restarts starts with a smaller heap, which only helps. If root wants
a rehearsal after the last restart, rerun the recipe below (about 25 minutes including the copy).

## How it was rehearsed

1. `cp -cR data/store tmp/store-gc/root/data/store` (APFS clone, 2.6 min for 796,120 files) then `cp -cRn` (44 s) to
   pick up the files written meanwhile. Heads (mutable keyword keys) stay as copied.
2. **Store identity is the path.** `seon.cluster.store/datahike-configuration` derives `:store :id` from
   `nameUUIDFromBytes(canonical path)`, and Datahike's `ensure-stored-config-consistency` refuses a mismatch
   ("Store identity mismatch"). The copy is only openable after rewriting `[:config :store :id]` in every branch head
   record (1,332 keyword keys, 1,330 patched; 2 skipped hold no config) through konserve (`k/assoc-in`). Default's own
   store needs none of this: it runs at the path its id names.
3. A throwaway `java -Xmx4g -XX:+UseG1GC` against HEAD's source (`git archive`; the working tree had another lane's
   unloadable edit, `No such var: config/effective`) calling
   `(seon.maintenance/collect! {:seon.operator/managed-root root :seon.config.maintenance/collect? true})`, first with
   `:seon.operator.collect/dry-run? true`, then without, on the identical copy; GC log via `-Xlog:gc`, a 500 ms sampler for
   peak `used`. The dry run used the working tree (the maintenance code is the same).
4. The clone, the scripts' JVMs and the pristine copy were removed afterwards.

## The dry run's projection is wrong by 5x

`seon.maintenance/dry-run-store!` reported `:projected-duration-ms` 4,227,249 (70 min): the mark plus the private
constant `projected-delete-ms-per-file` (5 ms) times 740,671 candidates. The measured sweep is 0.2 ms per file and the
whole collection 763 s. Task `seon.maintenance/the-dry-run-projects-seventy-minutes-for-a-three-minute-sweep`.

## What writes while the system is "idle" (re-sampled on pid 75161)

Nothing writes while idle. The 9.5 files/s of slow-alloc.md was other lanes' eval requests, not a background producer:

- File mtimes in `data/store` after the 22:28 restart settled: 200-380 files per minute (3-6 per s) while lanes worked; the
  first minute after boot was 4,807.
- Commits, by walking each branch's commit parents over the last 5 minutes (`konserve.core/get` of the head and commit
  records): 34-35 commits in 5 minutes across 5 branches: `:agent-870dfb75c425` 10, `:agent-efd491e32aa8` 9,
  `:agent-fb424f52cd60` 6, `:agent-7ec37b8f455c` 4, `:cluster-default` (main) 5. Main committed once a minute.
- Main's transactions in the last 15 minutes (`d/since`, grouped by tx): every one is an eval request's recording
  (`:seon.turn.work/situation`, `:seon.cluster.eval/source`, `:seon.eval/duration-ms`), a test member run, a profile
  measurement flush (247 datoms, once), the program analysis of a source change (4,619 datoms, once) or
  `:seon.runtime/agent` / `:seon.agent/runtime` upserts (10 datoms each). No health listener, schedule or cache writer
  commits on a timer.
- So the 9.5 files/s is 1,382 files over 35 commits: **39 files per commit**, from the six indexes each rewriting a leaf
  and the path above it, plus the commit record and head. Eval requests are the commits (every lane's reply commits at least once;
  even a no-effect probe like mine wrote `:seon.runtime/agent` upserts). Task
  `seon.maintenance/a-commit-writes-forty-index-files-and-an-eval-request-commits-at-least-once`.

## Other findings

- The store held 1,332 keyword-keyed head records: 22 agents' worth plus many stale `:agent-*` and
  `:building-source-*` heads. The clone's `d/branches` roster after the collection is what verification reopened; the
  `:building-source-*` heads (from `full-source-refresh!` snapshots) are candidates for deletion on completion, not examined.
- slow-alloc.md and `docs/research/agent-platform/2026-10-09/` still cite the retired task name above.
