# A task chain on one branch (experiment lane, 2026-10-14)

Probe on default through `eval_clj` JVM mode, connection `(seon.cluster.boot/connection "default")`, store
`(:seon.store/store (#'seon.cluster.boot/selected-instance "default"))`. Branches `probe-chain-branch-*` only; nothing
transacted to `:cluster-default`; all probe branches retired (`(d/branches conn)` holds none; `probe-sci-reuse-a` is
another lane's). No source edited. "Inferred" marks reading without a run.

## Recommendation in one table

| Q | Mechanism | Cost measured |
|---|---|---|
| 1 fork/delete | keep `seon.cluster.registry/fork!` and `retire-branch!` unchanged | fork 53-56 ms, retire 15-17 ms (5 warm runs) |
| 2 sync current task | `seon.merge/squash-merge` facts-only path (= `merge!` path (b), `merge-tx`), moving `:seon.task/merge-base` each time; NOT `datahike.api/merge-db` | squash 8 ms, apply 22 ms |
| 3 final merge | one ordinary `seon.merge/merge!` of the LAST step's assignment | needs: see below |
| 4 sequential agents | `plan` gives step N+1 the predecessor's branch; `fork-attempt!` already no-ops on an existing branch | inferred, 5 edits listed |

## 1. Branch mechanics

- Fork: `seon.cluster.registry/fork!` (arg `:seon.cluster.registry/branch-request`). It calls `registry/branch!`
  (idempotent wrapper of `datahike.api/branch!`, versioning.cljc `branch!`: reads the source head record, `k/assoc`s the
  new branch key, then publishes the `:branches` roster last under a reachability permit; no index copy), then writes
  two things: a retained base ref `base/<branch>` (`registry/base-branch`, a second `branch!` from the fork commit,
  `registry/base-commit-id` reads it) and one transaction on the new branch holding its fork entity
  (`registry/branch-fork-entity`: `:seon.branch/fork-id`, `:name`, `:target`, `:base-t`).
- Measured, store from `selected-instance`:
  - `(reg/fork! {... :seon.cluster.registry/from :cluster-default :seon.branch/target :cluster-default :seon.db/db (db/db conn)})`
    then `(reg/retire-branch! ...)`, 5 runs: fork 56 54 54 54 53 ms, retire 16 17 15 17 17 ms (first cold fork 61 ms).
  - Raw `(d/branch! conn :cluster-default b)` 17 ms cold, `(d/delete-branch! conn b)` 8 ms. So fork! is raw branch (~17)
    plus base ref plus one checked transaction (~35 ms writer settle).
  - `store/open-branch!` of a fresh branch 2 ms.
- Delete: `registry/retire-branch!` calls `datahike.api/delete-branch!` (versioning.cljc: removes the name from the
  `:branches` roster; refuses a branch with a live connection -> `::cluster-connected`; bytes stay), then retires the
  `base/` ref the same way. Reclaim is `registry/collect!` -> `datahike.api/gc-storage` (gc.cljc `reachable-in-branch`):
  roots are every remaining branch head, walking `datahike/parents` of each commit whose updated-at is newer than the
  cutoff (`registry/retention-cutoff`, `:seon.config.db/snapshot-window-ms`). A deleted, unparented branch's commits
  are therefore unreachable and sweep with no per-branch cleanup.
- Measured reclaim (dry run, `collect!` with `{:seon.operator.collect/dry-run? true}` and a future cutoff): a branch with 3
  transactions retired changed candidates 68208 -> 68227 (+19 keys), key-count 98059 constant.
- FINDING (not mine, record as task): that one whole-store dry-run took 27 s and 17 s, `:jvm-window` alloc 62 GB, mark
  duration 27051 ms (`:mark-ms`), over the ten-second rule. `collect!` is whole-store by design; retiring chain branches
  is cheap but reclaiming them is not, so do it in a batch, not per chain.

## 2. Sync only the current task's facts to main

Setup: `main-like` = `d/branch!` of `:cluster-default`; `chain` = `reg/fork!` toward `main-like`. On `chain` (checked
writer `db/transact!`) I wrote a status row (`:seon.task/assignment "probechain01"` with the required
`:seon.task/assigned`, `:revision` (64 chars), `:agent`, `:branch`, `:seon.task.attempt/end :done`) and two scratch program
entities (`{:seon.ns/name 'probe.chain.scratch}`, `...scratch2`). A defn needs the publish path; the ns entity is the same
partition (program, not cluster).

| Option | Result | Cost |
|---|---|---|
| A. `seon.merge/squash-merge` source base target projection (pure) then `db/transact!` its `:tx-data` on target | copied exactly 6 datoms of the assignment row; the two ns entities NOT on target (`#{probe.chain.mainside}` only) | squash 8.4 ms, apply 21.8 ms |
| B. root transacts the status attrs by lookup ref: `(db/pull chain-db [:seon.task/assignment :seon.task.attempt/end ...] [:seon.task/assignment k])` then `(db/transact! main [row])` | 1 datom `:end :capped` upserted by identity; no program entity | pull 0.24 ms, apply 22.9 ms |
| C. `(d/merge-db main #{chain-head} [[:db/add k :seon.task.attempt/end :stalled]])` (the datahike.api name; `merge!` is not in `datahike.api`) | works, tx-data is exactly the supplied subset, ns entities absent; BUT the new commit has `(d/parent-commit-ids after)` = main's previous head plus the chain head | 23.7 ms |

Why not C: the chain head becomes a parent of a main commit, so `reachable-in-branch` walks the whole chain history from
main while the commit is inside the retention window. Abandoning the chain (delete branch) then reclaims nothing until
the window passes. A and B add no parent. (Seon's `accept-merge!` also adds parents deliberately, but only for the final
merge, where the history is wanted.)

Recommended: A, because it is already the code that closes assignments. `seon.merge/merge!` -> `merge-onto` takes the
facts-only branch (`(not accepted?)`: `merge-tx` inside main's writer) whenever the source did not end `:done`/`:sent-back` and
`source/unmerged-count` is zero, and `merge-tx` moves `:seon.task/merge-base` to the synced commit in the same transaction,
so each later sync is a delta of only what changed since the last (a repeat adds nothing). Cluster-vs-program partition
is `program/cluster-attributes projection`, so "task status facts" is whatever the schema declares cluster: the assignment
row, ends, outcome, not defns/tests.

Gaps that make this not drop-in (inferred from `merge-onto`): (1) `accepted?` is true when the source ended `:done` or
the branch holds unmerged declarations (`unmerged-count` > 0), which routes to `prepare-merge!`/`accept-merge!` and a
gated merge of the code. A mid-chain sync must be forced facts-only (a predicate "this assignment is a non-final chain
step", derived from `:seon.task/next` being present). `unmerged-count` was 0 for my ns entities; a real defn counts.
(2) `merge-tx` sets `:seon.task/outcome :merged` from end `:done`, which `ready` reads as "need merged", the right
trigger for starting the next step, but only if the facts path may carry `:done` (comment in `merge-tx` says it does
not today). (3) Eids: `squash-merge` emits the source's entity ids for branch-born entities (my tx was
`[:db/add 659252 ...]`) and remaps only by natural identity (`collision-remap`). It worked in my run (main-like allocated
659319 on its own, no clash) but a long chain with main moving is the case to test; option B by lookup ref has no such risk
for rows main already holds, which is every row after the first (`write-attempt!` creates the row on main before
the fork).

## 3. The final merge

`seon.merge/merge!` (`merge-onto`) takes one assignment `key` and needs, all present for a chain's LAST step:
- on main: the assignment row with `:seon.task/branch` and a merge base: `:seon.task/merge-base` or
  `registry/base-commit-id` (the `base/<branch>` ref `fork!` retains; `assignment-row`). Attempt-tx stores the base when the
  branch exists (`task/attempt-tx`: `(or (registry/base-commit-id database branch) ...)`), and `write-merge-base!` writes it.
- the branch's head commit (`registry/branch-commit-id`), its `:seon.task.attempt/end` on the branch for that assignment
  (`:done` forces the gated path).
- the assigned task's proofs: `assigned-task` -> `source/tested-task-basis head branch base task-sym` -> `task/obligations`
  (and the Prove step's `:seon.task/product` beside them when the step is `:prove`).
- `source/prepare-merge!`: forks a candidate `merge-xxxx` branch of main, applies the delta (`replacement`), runs the
  save gate (`candidate-gate!`) and the tests reaching it, `merge-verdict`; `accept-merge!` commits with parents
  `(conj merged e-id)` and `advance-base!` (moves `base/<branch>`).
One merge at the end fits: the three-way declarations comparison (`compare-branch`, `registry/fork-of`) reads the base ref
and branch, not `:seon.task/merge-base`, and the base ref only moves in `accept-merge!`, so facts-only syncs in between do
not hide declarations (inferred; verify with a red test). Use the last step (Implement) as the merge request: its task's
proofs are the chain's proofs, the earlier steps' tagged deftests were written on this branch by Prove. The earlier
steps' assignments need only their ends/outcomes synced by A; they never reach `accept-merge!`.

## 4. Sequential agents on one branch (inferred, symbols cited)

Today every `:attempt`/`:start-flow` pair in `seon.cluster.agent/plan` mints
`:seon.task/branch (keyword (str "attempt-" key))` with `:seon.flow.plan/fork-point` main (or a relaunched attempt's end commit).
What already works for an existing branch: `fork-attempt!` forks only `(when-not (contains? (registry/branches store) branch) ...)`
and returns `registry/base-commit-id`; `acquire-context!` with `{:seon.agent/branch branch}` "selects an existing branch";
`seed-attempt!` writes the new agent and `task/attempt-rows-tx` row onto a branch lacking them. So the next step's agent
attaching to the chain branch is mostly the existing relaunch path minus the fork.

Changes needed:
1. `seon.cluster.agent/plan` / `plan-facts`: a step with a predecessor (`:seon.task/next` backlink, new attribute: not in
   the schema today, `(get (:schema db) :seon.task/next)` is nil; TASK-CHAIN amendment plus a red deftest required by
   the design-lock rule) reuses the predecessor assignment's `:seon.task/branch` and fork-point nil. Only the first step
   forks main.
2. `seon.cluster.agent/assignment-actions`: for an ended non-final step, replace `:merge` (gated, `accepted?` when
   `:done`) and `:delete-branch` by a facts-only sync (A above); the branch is deleted only after the final merge or a
   chain failure (`close!`).
3. `seon.merge/merge-onto`: the facts-only forcing predicate of Q2; `ready-rules` ("every need merged") then starts step
   N+1 once N's outcome is `:merged` on main.
4. Connection lifecycle: `store/open-branch!` refuses `::branch-already-open` for a second connection in-process, so
   step N's graph must be stopped and `release-context!` run before step N+1's `acquire-context!`. `assignment-actions`
   already stops the graph first (`(and end (not exited?)) [:stop]`), so sequencing holds; verify the child registry
   releases the branch connection.
5. `seon.cluster.agent/lifecycle-facts` reads `(registry/branch-commit-id ...)` per assignment and uses `head = merge base`
   as "nothing left": with several assignments on one branch that comparison needs the per-assignment synced commit
   (`:seon.task/merge-base`), which facts-sync maintains.
6. Failure: a failed step ends `:error`/`:given-back`; `unfinished-intent` already files root's problem and `close!`;
   chain abandon = `registry/retire-branch!` of the branch (which also drops `base/`), reclaim by the batched `collect!`.

## What this deletes from today's per-step flow
- N-1 branch forks per chain (each ~54 ms + a base ref + fork entity) and N-1 base refs; the `relaunch` nested fork
  (`fork-attempt!` forking from an end commit and `branch-fork-entity`'s inherited-base branch) because a retry can continue
  on the same branch (inferred; keep the allowance counter).
- N-1 gated merges (`prepare-merge!` candidate branch + `candidate-gate!` + test run + `accept-merge!` + `advance-base!`) for
  intermediate steps, and their `:delete-branch` actions.
- The re-import of earlier steps' work into main so the next step can fork it: `task/ready` still gates on the `needs`
  outcome, but main no longer needs the predecessor's code, only its status.
- What it adds: `:seon.task/next`, one forcing predicate in `merge-onto`, and a chain-aware `plan` (all small).

## Risks to measure before building
- Eid collision on `squash-merge` tx-data for branch-born entities (Q2 gap 3).
- The facts delta is all cluster facts changed on the branch since `:seon.task/merge-base` (evaluations, messages are
  declared non-program but may be cluster-rule rows); check what a real agent branch syncs and its size (only an assignment
  row appeared here).
- Whole-store GC mark cost (finding above) once chain branches are common.
