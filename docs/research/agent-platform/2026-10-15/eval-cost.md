---
title: "eval-cost: what one trivial eval request costs, stage by stage"
type: research
status: measured (lane eval-cost, Opus, 2026-10-15); slice 1 committed, later slices proposed
created: 2026-10-15
---

# eval-cost: `(+ 1 2)` as an agent, stage by stage

Default pid 90798 (running `ed9eb4f99`, konserve `af35a974` and datahike `1a149891` already bumped), SCI eval
requests through MCP `eval_clj` as agent `eval-cost`, read from each reply's `:seon.repl/cost-result`. The 805 ms /
936 MB and 199 ms / 252 MB of leaf-hot were measured before the fork bump; on this JVM a first request is 192 ms / 92 MB
and a settled one 65 ms / 45 MB.

## What the request must do, and what it does

| stage (ms, `:seon.cost/stages`) | first request | settled (3 runs) | what it is proportional to |
|---|---:|---:|---|
| custody (find the agent, its branch) | 88.0 | 0.6-0.7 | first: branch creation and context acquisition (acquisition, once per agent) |
| fork, read, evaluate, print | 3.7 | 3.2-3.5 | the form |
| settle (the recording transaction) | 41.8 | 41.0-45.8 | the writer: ~6 ms processing, then ~20 new index-node files and two directory `F_FULLFSYNC`s per commit |
| test-step | 43.8 | 8.7-12.3 | the whole program's `:seon.program/definition-digest` history (below) |
| unattributed | 14.2 | 8.1-10.2 | |
| **request** | **191.5 ms / 91.7 MB** | **64.0-67.9 ms / 43.5-47.0 MB** | |
| turn thread (`:seon.cost/step`) | 48.4 MB, 47 ms CPU | 23-27 MB, 22-28 ms CPU | |
| writer (`:seon.cost/writer`) | 10.1 MB, 12 ms CPU | 6.8-7.1 MB, 6.5-8.9 ms CPU | |

Per-request profile (15 back-to-back requests, `seon.profile` cells diffed, per request):
`seon.turn/settle-batch!` 35.4 ms of which `seon.db/transact-call` 31.0 (a park on the commit promise);
`seon.turn/test-step` 8.7, of which `seon.test/branch-changes` 8.2 and `seon.program/changed-identities` 5-6;
the rest is many sub-millisecond reads (`seon.turn/agent-dial` x5, `seon.config/effective` x14,
`seon.ai/agent-overlay` x7 per request).

## Where each excess comes from

1. **The writer's wait (31-38 ms quiet, 91 ms when requests run back to back).** JFR (`jdk.FileForce`,
   `jdk.FileWrite`, `jdk.ThreadPark` at threshold 0): inside each turn-thread park on
   `datahike.tools/throwable-promise`, the writer's first file write starts 11-16 ms in (~6 ms of it CPU:
   `seon.db/write-report-error`, `seon.db/write-owned-values-error`, the final-report validator's queries), then the
   commit writes **20 files** (new index nodes of every index the transaction touched, the commit record, the head)
   and pays **two `FileChannel.force` = `F_FULLFSYNC`** (5-13 ms and 3.7-4.5 ms) for the barriered mutable keys.
   `force(true)` on this machine: 4-8 ms each (10 measured). This is Datahike/konserve storage work, proportional to
   the index paths a commit rewrites, not to the request; the deferred-sync fork change already removed the per-node
   forces. Owner: the datahike/konserve forks (a fork bump is root's restart batch).
2. **The test step's branch delta (6 ms of 8.7).** `seon.test/branch-tests` runs every request since TASK-CHAIN
   S2b-1 (`e3716cd30`), through `seon.program/own-changed-identities` -> `changed-identities`. Its entity read was a
   Datalog query over `since(history)` of `:seon.program/definition-digest`, which walks every historical digest datom
   of the program (26,737) and filters by transaction afterwards: proportional to the program's history, not to the
   branch's change. `seon.db/entities-changed-after` filters the same history by transaction inside the native index
   read. The same function runs again on `db<->flow` (`seon.cluster.source/compare-branch`), 2.4 calls per request.
   S2b-1 also made `seon.test/request-changes` run for a recording with no program entity, turning
   `seon.pass-cost-test/a-request-that-changed-nothing-runs-no-test-step` red (1 call, test-step 8.3 ms).
3. **The reaction on `db<->flow` (not on the reply path, every write).** In 40 back-to-back requests the
   `db<->flow` thread ran 3,195 1-ms CPU samples and allocated 7.3 GB (about 80 ms CPU and 180 MB per request):
   `seon.cluster.agent/run-pass!` -> `lifecycle-facts` -> `taskless-agents` calls `branch-admission-end` for every
   agent (125 per pass), each opening `seon.db/branch-database` -> `seon.db/commit-database` and running Datalog
   (`seon.db/vector-query-call` 35% of the thread). Proportional to the number of agents ever admitted, per write.
   Owner `src/seon/cluster/agent.clj` (held by lane step-merge): proposed below, not edited.

## Slice 1: the branch delta reads only the transactions after the base

`seon.program/declaration-changed-identities` reads its changed entities with `seon.db/entities-changed-after`
(and skips the entity-attribute query when none changed); `seon.test/branch-changes` reads the merge base
(`seon.cluster.source/branch-merge-base`, ~3 ms, a walk of the branch's commit graph that grows with every eval
request the agent records, plus `seon.cluster.source/database`, ~1.4 ms) only when the branch wrote a declaration
digest since its fork (`seon.test/untouched?`: `seon.cluster.registry/fork-of` and the same native read, about
0.5 ms when nothing changed); `seon.turn/test-step` calls `seon.test/request-changes` only when the recording wrote a
program entity.

Same JVM, same run, a local copy of the old and new entity read (compiled), warm:

| read | old | new |
|---|---:|---:|
| my branch since its fork (80 transactions, nothing changed) | 5.81 ms / 8.6 MB | 1.31 ms / 1.0 MB |
| main, last 400 transactions (429 changed rows, identical sets) | 99.8 ms / 57.9 MB | 4.4 ms / 5.9 MB |

The end-to-end proof is `seon.pass-cost-test/a-request-that-changed-nothing-runs-no-test-step` (red on HEAD: 1 call
of `seon.test/request-changes`, test-step 8.3 ms; with the entity read alone, 5.3 ms, still over its 5 ms bound under
a loaded check JVM, which is why the merge base is now read only for a touched branch). Default runs compiled `seon.turn`; the request-level number
after the change needs root's restart.

## Proposed next slices (not done here)

- **`db<->flow` per write (largest system cost per request).** `seon.cluster.agent/taskless-agents` should read
  only agents whose branch changed in the woken commit (the listen route already names the branch), not open every
  admitted agent's branch database each pass. Needs lane step-merge's file; filed as a finding for root.
- **The writer's validator (~6 ms)** and **the commit's 20 node writes + 2 full syncs (~25 ms)**: fork-side;
  measure `datahike.writing/commit!` node count per index before proposing (fewer indexes touched by an
  evaluation-record transaction, or one barrier for head and record).
- **`seon.db/changed-attributes` with a basis t** costs 38-80 ms and ~400 MB per call (commit walk through
  `seon.db/basis-revisions`); not on this path, but any caller pays it.
