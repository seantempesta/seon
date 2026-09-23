---
type: issue
status: open
severity: critical
created: 2026-09-23
tags: [issue, datahike, store, write-path, durability]
---

# A published head references index nodes that were never written

## Observation

Twice on 2026-09-23 default's store refused a read with Datahike's
`Node not found in storage.` (`persistent_set.cljc:436-439`): nodes
`6ab361f1-9fef…`, `6ab36206-bc18…` on pid 90963 (before its heap death at
05:40Z), and `6ab376ef…`, `6ab377b7…`, `6ab378a2…` on pid 31476 (around 06:51–06:58Z).
The first store survives as `tmp/orchestrator/heap-2026-09-23/store-broken`.
The second was nuked at 08:40Z and left no evidence.

## Evidence (first store; APFS copy opened read-only through konserve, 2026-09-23 ~13:55Z)

The collector did not delete them. **No collection ever ran on this store.**
- The roster holds 9 branches. 1,639 retired branch-head keys are still on
  disk, the oldest `:building-source-24492-…` written at 01:09:09Z, about two
  minutes after genesis.
- `gc-storage!` sweeps every unreachable key older than its cutoff
  (`reference-code/datahike/src/datahike/gc.cljc:84-169`), so any collection
  after 01:09Z would have removed all of them.
- The new default has no maintenance receipts. The only runtime caller of
  `registry/collect!` is the scheduled `seon.maintenance/collect!` (Sunday
  03:00 UTC, floored at task creation, `schedule.clj:227-254, 665-719`).

**Two commits were accepted as committed while none of their writes reached the directory.**

1. The `publish!` source seal, on the scratch branch.
   - `current-src`'s head (commit `6ab361f3`, max-tx 536871088, head written
     05:21:56, commit record 05:21:55) inlines fused roots.
   - Every child those roots gained in their flush is absent: seven
     `6ab361f1-*` nodes (squuid second 05:21:53) across eavt, aevt, avet,
     temporal-eavt and temporal-aevt. No `6ab361f1-*` key exists at all.
   - Transaction 088 is `source.clj` ~636-646's seal (the
     `:seon.source/digest` change, read from temporal-avet).
   - Its scratch branch `building-source-…-959aeadc` has no 088 record:
     its head stayed at 087 (`6ab361b8`, written 05:21:03, parent
     `6ab360bd`).
   - Still `force-branch!` (`source.clj:660`) published an 088 database from
     `@connection`. Datahike advances `@connection` only after `commit!`
     returns (`writer.cljc:245-257`), so the scratch writer believed 088 was
     durable.
2. An ordinary writer commit, on `cluster-default`.
   - Of 1,040 `cluster-default` commit records, exactly one parent is absent:
     `6ab36209` (written 05:22:19) names parent `6ab36206-db57…` (05:22:14).
   - Neither that commit record nor any `6ab36206-*` node exists.
   - `6ab36209`'s roots reference the missing `6ab36206-bc18…`, and about
     60 later records (cluster-default, forked agent heads, probe branches)
     share it.
   - A fork taken by `registry/branch!` at about 05:22:20
     (`probe-c8-parent-…`) branched from `6ab361f5`, the head before
     `6ab36206`. So `6ab36206`'s head write never landed either, while the
     writer threaded it as `last-cid` into the next commit.

Both instances fall within 25 seconds, while pid 90963 was nineteen minutes
from heap exhaustion (old generation 10.26/10.74 GB, GC about 62 s of every 120 s).
Writes landed about 0.6 s per three files, and 087's flush began 63 s after its
transaction (squuid 05:20:56 vs transaction instant 05:19:53).

## What the source and disposable reproductions rule out

Reproductions ran in-process on disposable file stores under
`tmp/store-damage/`, using Seon's configuration: fused roots, branching
factor 4096, diff buffer 256, history on. `tmp/store-damage/probe.clj`
holds the forms. Each run was verified by a fresh connection walking all six
indexes.
- Sequential publication (fork from the published commit, two
  transactions, then `force-branch!` of `@scratch`), 10 rounds: no loss.
- The same with a concurrent branch writer and agent forks
  off the main connection, 10 rounds: no loss.
- Two queued transactions batched into one commit: no loss.
- `force-branch!` while the commit is in flight publishes the previous
  committed database (in-flight transactions are not published), not a
  dirty one: no loss. Losing the in-flight transaction is a separate
  caller-correctness issue.
- In Datahike, `-flush` stores through the set's own storage
  (`persistent_set.cljc:219-221`). `commit!` and `force-branch!` drain
  `(:storage (:store db))` (`writing.cljc:37-47, 493`;
  `versioning.cljc:400`). `stored->db` binds every index to its
  connection's storage (`writing.cljc:253-270`). No path advances a
  connection without `commit!` returning.

## Open: the mechanism

The unexplained step: `datahike.writing/commit!` returned success, so the
connection advanced, yet its ordered batch of nodes, commit record and head
(`k/multi-assoc`, `writing.cljc:505-551`; konserve `write-blobs-in-order!`,
`filestore.clj:120-154`) is absent from the directory.

Candidates to verify next:
- A fatal `Error` inside a nested go block of konserve's asynchronous
  filestore path (`go-try-` catches `Exception` only) under heap exhaustion.
- A connection or store resolved by name across two physical stores in one
  JVM, as in-process tests do.

Recording the failed commit is also owed. The fork's commit loop converts an
`Error` to `:fatal-commit-error` (`writing.cljc:565-569`), but nothing
observed here produced one.

## Not the fix

Publishing `(:db-after report)` instead of `@connection` at
`source.clj:660` changes nothing: the committed report's `:db-after` is the
same `commit-db` the writer installs (`writer.cljc:257-262`).

## Owner and regression

Owner: the Datahike fork's commit path (`reference-code/datahike`).

Wanted regression: once the failure is reproducible, a commit whose durable
write does not complete never advances the connection and never becomes a
parent. A fresh connection reads every node of every published head.
