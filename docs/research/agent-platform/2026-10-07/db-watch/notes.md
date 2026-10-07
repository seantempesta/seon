# DB watch, 2026-10-07 (lane db-watch; watch script and tasks: 4524136d7)

Default runs a8e3bde5b (38 commits behind HEAD), so value storage (removed in c08e2c766) was still live during these measurements.

## Store

- 186 MB real bytes, 339 MB on disk (APFS block rounding), 61,954 files. The "145 MB in 9.5 h" was an on-disk vs real-bytes mix-up: real growth is ~15 MB/hour under heavy lane activity.
- A collection dry run: almost everything on disk is retained history. Reclaimable now: 1.7% with no history limit, 24% under the configured 1-hour window. The window only applies at the once-daily collection (03:00Z), so the store carries up to a day of commits.

## Database (cluster-default, 762k current datoms)

- Program facts: 64% of datoms, 47% of value bytes.
- Read evidence: 19% of datoms, 41% (15.7 MB) of value bytes, held by only 115 evaluations; four `prepare-merge!` runs hold 2,259 reads each (~1.5 MB per evaluation). Its only reader is the system-turn refresh, which the agent-loop spec deletes.

## Per operation (files / bytes / datoms)

| operation | files | bytes | datoms |
|---|---|---|---|
| (+ 1 2) via an agent | 24-25 | ~140 KB | 28 |
| defn | 66 | 411 KB | — |
| JVM-mode probe | 22 | 129 KB | — |
| 17-test run-owned | 253 | 843 KB | 2,139 (1,566 profile-measurement upserts) |
| idle minute | 0 | 0 | 0 |
Every commit costs ~130 KB whatever its datom count (unchanged since 09-28). cluster-default made 440 commits in 24 h.

## Memory

3.32 GB live after full GC, including 15.45M boxed Longs (371 MB). Live results are never released (root holds 1,311 values, waste-rank-probe 245). Twelve probe agents from other lanes were never retired. A finished review lane's scratch cache kinds hold 71 MB (15% of the 512 MB cache). 970k StackTraceElements retained (47 MB), holder unknown.

## Ranked causes (bytes x frequency)

1. Per-commit floor ~130 KB (existing seon.cluster.store tasks).
2. Daily-only collection: history window applied once a day (existing maintenance task).
3. Read-evidence rows nobody reads after the loop cut (new task).
4. Profile-measurement upserts per branch (new task).
5. Republishing rewrites every definition below an edit, 10-20x the changed sources, because each definition stores its whole file's digest (new task).
6. Live results never released (new task).
7. ~200 KB/eval read-evidence-sink leak (fixed in bd5e0e8c9 + c7559abb1, not yet installed).
8. Transaction hot/repeated stamps nothing reads (new task).
9. Agent-branch settlements stamped seon.db/absent-call, hiding the writer (new task).

## Watch

Paste `watch.clj` as the code of an eval_clj jvm call; set `since-minutes` and `basis-t` first. ~0.8 s; it records one ~130 KB run itself. First basis: 536872613.

## Loose ends

- Probe branch db-watch-probe (:agent-24fa54f9a5b7) retire refused "admission-closed" (known task); root retires it.
- One probe hit the 32 s MCP timeout and may have kept running (read-only).
