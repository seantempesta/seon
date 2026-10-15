---
title: "Card KEEP-BRANCHES: ended branches stay as history"
type: reference
status: active
created: 2026-10-16
---

# KEEP-BRANCHES Ended branches stay as history

Owner rulings (2026-10-16): "Save the agent records too ... I want it to be configurable whether the branches are
saved as historical data or deleted. If we just save them then we can mine them directly"; then "it should be cheap to
store the branches and if it isn't it's a bug so flip that config to true so we can mine the sessions ... The receipt
writer at the attempts end is high complication and low benefit if we just keep and query the branches." So: no
receipt facts on main; one dial keeps every ended branch, shipped true. Lane keep-receipts (Opus).

## What exists

- Dial `:seon.config.agent/keep-ended-branches?` (`resources/seon/schemas/seon.config.agent.edn`, shipped false in
  `config/default.edn`). Two readers, each its own copy of the decision: `seon.task/close!` (an assignment root
  closes) and `seon.cluster.agent/kept?`, which `seon.cluster.agent/next-actions` turns into
  `:seon.flow.actions/kept?` to withhold `:delete-branch` for a merged attempt branch. When kept, nothing closes the
  branch's admission, contexts or route: keeping skips the whole retirement, not only the deletion.
- A chain's end, `seon.cluster.agent/finish-chains!` (TASK-CHAIN §2.6), deletes the chain branch and every merge branch
  its tasks name (`seon.task/delete-branch!`, which runs `my.agent/retire-branch!`: retire the host, close admission,
  release contexts, release the chain route, `seon.cluster.registry/retire-branch!`), then retracts the chain fact. It
  never reads the dial.
- The records to mine live only on those branches: on main `[:seon.agent/id "attempt/cec65a8d91f7"]` pulls nil while its
  attempt row merged. Main's attempt rows name the branch (`:seon.task/branch`) and the branch commit the attempt ended
  at (`:seon.task.attempt/commit`), so a kept branch is found from main without any new fact.

## Measured cost (live default store, 2026-10-16, JVM-mode read-only marks with `datahike.gc/reachable-in-branch` and Datahike's `-mark`; file bytes on disk)

| branch | commits since its merge base | its own writes (head vs merge base) | head vs main's head now | its commit history beyond main (plain collection, shipped) |
|---|---|---|---|---|
| `:chain-b7ab51e090d5` (10 h old) | 129 | 584 nodes, 2.0 MB | 2,053 nodes, 6.4 MB | 4,190 nodes, 18.5 MB |
| `:chain-10d1fd5956a9` (10 h) | 151 | 808 nodes, 2.9 MB | 3,841 nodes, 11.5 MB | 5,733 nodes, 25.4 MB |
| `:chain-e439df9798c6` (33 h) | 27 | 324 nodes, 1.0 MB | 15,019 nodes, 40.2 MB | 1,301 nodes, 5.1 MB |
| `:merge-66345c83c2b9` | n/a | 384 nodes, 1.7 MB | 2,388 nodes, 7.2 MB | 420 nodes, 1.9 MB |
| `:agent-4aa84ceffa01` | n/a | n/a | 2,425 nodes, 7.2 MB | 420 nodes, 1.7 MB |

Main for scale: head 36,376 nodes, 80 MB; its whole history (epoch mark) 226,927 nodes, 833 MB, marked in 18.8 s; the
store directory holds 493,374 files. Blobs: no kept branch references a blob main does not (0 bytes). Heap: none once the
branch is closed (a kept branch holds no connection, context or route).

What the cost is proportional to:

- **Under the shipped collection** (`:seon.config.maintenance/retention? false`, `registry/plain-cutoff`, every commit of
  every live branch kept) a kept branch costs its own commit history: about 140 KB per branch commit (b7ab: 18.5 MB over
  129 commits), so 2 to 25 MB per chain, about 2 MB per merge or agent branch. Main pays the same per commit and keeps
  all of it, so a kept chain adds its share of what the store already grows by. The lever is commits per chain: three
  per model call (`seon.turn/open-call`, `seon.turn/plan-call`, `seon.turn/receipt-settle-batch-call`; b7ab 25, 25 and 21
  of its 129), each path-copying six indexes.
- **Under retention** (`retention? true`, the hour window) a kept branch's history beyond its head is dropped, but its
  head pins the old version of every node main rewrote since its fork: 6 to 11 MB after 10 hours, 40 MB after 33 hours,
  bounded by one snapshot (80 MB). Kept branches near each other in time share it: all seven ended branches together
  pin 69.6 MB against 95 MB summed, the five recent ones 23.3 MB against 40 MB. Retention collection is broken on this
  store today (below), so this mode is not running anyway.
- **GC mark**: a kept branch adds only its nodes main does not reach (420 to 5,733 against main's 226,927): under 3%
  of the mark each. **Passes and boot**: `seon.cluster.registry/branches` grows by one entry; `route-chains!` routes
  only chain branches main still names, and the chain fact is retracted at the chain's end.

Answer to "It's cheap right?": about 2 MB per merge or agent branch and 5 to 25 MB per chain under the shipped plain
collection, disk only. Above "a few MB" for chains, and the cause is not keeping: it is about 140 KB written per commit
and three commits per model call, which main pays too. That producer is already filed as
`seon.cluster.store/a-small-commit-rewrites-thirty-index-nodes` (AEVT and temporal AEVT leaves per touched attribute)
and `seon.cluster.store/each-commit-serializes-its-root-record-twice`; this measurement is new evidence for them, not
a reason to default the dial false.

## Decisions

1. **One dial, every ended branch.** `:seon.config.agent/keep-ended-branches?` keeps its name: its meaning ("an ended
   branch stays") is unchanged, its reach widens from attempt branches to chain and merge branches; the description
   says so. Shipped default `true` (owner). The 16 GB rule is about heap, and a kept branch holds none.
2. **One decision point, read once per subject.** `seon.config/keeps-ended-branches?` reads the cluster's effective
   dial (the shipped decision when its row leaves it undecided). `seon.task/delete-branch!` becomes
   `seon.task/end-branch!` (context, branch, keep?): it always ends the branch's work through `my.agent/retire-branch!`
   (retire the host, close admission, release contexts and root's chain route) and passes `:my.agent/keep?`, which
   leaves the branch. Its callers: `seon.task/close!` (the agent's cluster), `seon.cluster.agent/finish-chains!` (the
   chain branch and every merge branch its tasks name; main's cluster, read once per pass), and the `:delete-branch`
   action (`keep?` false). For a merged assignment the plan keeps deciding (`seon.cluster.agent/kept?` withholds
   `:delete-branch`; its `:stop` already released the branch's contexts), now through the same reader.
3. **No new lifecycle fact; boot keeps what main names.** The chain fact is retracted at the chain's end as today
   (TASK-CHAIN §2.6); that releases root's chain route (`route-chains!` routes only branches a chain fact names). A kept
   branch is found from main by the rows that merged (`:seon.task/branch`, `:seon.task.attempt/commit`). Boot's sweep
   (`seon.cluster.registry/retire-unclaimed!`) claimed only chain facts and open agents, so it deleted every kept branch
   at the next restart; `seon.cluster.registry/cluster-claims` now also claims every branch a task or attempt row on
   the cluster names. Deletion stays db<->flow's decision by the dial, never boot's. Marking the chain fact "ended"
   would be a new lifecycle attribute (TASK-CHAIN lock); nothing reads it, so it is not added.
4. **Removing a kept branch** stays root's explicit `my.agent/retire-branch!` (no `:my.agent/keep?`).
5. **Not done (owner):** receipt facts at the attempt's end and the grader's promotion into `src`.

## Proofs (red first)

- `seon.cluster.db-flow-test/a-three-task-chain-ends-merged-with-no-hand-act`, two rows: with the dial true the chain
  branch stays, readable by name (its terminal attempt's end), no context or route of root's holds it, its chain fact
  gone; with it false the branch is deleted. Red at HEAD for the kept row.
- `seon.cluster.registry-test/a-chain-fact-keeps-its-branch-and-base-through-retirement`: a branch a row on main
  names survives `retire-unclaimed!`. Red at HEAD.
- `my.agent-test/a-kept-lane-ends-and-stays-readable-until-it-is-retired` (committed ec3b1562f).
- Tests of the deletion path set the dial false explicitly (`seon.cluster.db-flow-test/keep-ended!`).

## Findings beside it

- `seon.cluster.registry/retained-before` throws a NullPointerException on the live store: `fork-of` reads
  `:seon.branch/name` on the store's `:db` branch, whose schema predates the attribute; `seon.db/datoms` answers a
  refusal map and `(apply max-key :tx (seq refusal))` meets nil. Retention collection (`seon.maintenance`,
  `retention? true`) therefore cannot run on this store. Filed as
  `seon.cluster.registry/retained-before-fails-on-a-branch-whose-schema-lacks-the-fork-name`.
- Main shows task `b7ab51e090d5` `:seon.task/execution :running` after its merge: the merge carried the chain branch's
  value at merge time (`:running`, main tx 536873806); root's later hold on the branch (`:held`, branch tx 536873884)
  never reaches main. Belongs to the reactive-start lane (flow-pass, R-P3/R-P4), which holds the writers.
