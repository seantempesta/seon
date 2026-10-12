---
title: "M6 notes: one merge, keyed by the assignment"
type: research
status: lane m6, 2026-10-12. M6 done in two commits (slice 1, the rename seon.cluster.squash → seon.merge, 7c3077b9e; slice 2, the fold, 3da1f6449). Root's ruling of 2026-10-12 applied: ONE engine, `seon.merge/merge!`, keyed by the assignment; `seon.cluster.source/land!`'s declaration merge folded into it; publication stays today's call after acceptance until M9.
---

# M6 notes

## What landed

- **One merge.** `seon.merge/merge!` `[execution assignment root]` merges an assignment's branch head C onto main
  in ONE transaction from its merge base (`:seon.task/merge-base`, else the commit its branch forked at, which
  the merge stores): the cluster facts by `seon.merge/merge-tx` inside main's writer (M3's path: base moved to
  C and the assignment closed in the same transaction), and, when the branch holds the agent's merge request
  for the assignment, the declarations it changed since the base: `seon.cluster.source/prepare-merge!` gates
  them with the request's proofs and the reaching tests on a fresh merge branch of main,
  `seon.cluster.source/accept-merge!` carries `merge-tx` in its own transaction (new `:seon.source/merging`
  argument), then the merge branch is deleted, `write-back!` and `commit!` publish (today's call). A base
  already at C transacts nothing. `:seon.task/merge-commit` (new, assignment row) records main's commit.
- **Callers.** `my.root/merge!` and `my.root/close!` take `:seon.task/assignment`; `my.root/merge-requests`
  lists open assignments (no `:seon.task/outcome`) with their end, offering both by assignment;
  `seon.task/close!` writes `:seon.task/outcome`/`:seon.task/reason` on the assignment (now members of
  `:seon.task/assignment-row`) and deletes its branch; db<->flow's `:merge` action calls `seon.merge/merge!`
  (refusals thrown to `failed-pass!`); `seon.context/ended` offers `my.root/merge!` by the agent's latest
  assignment. `seon.cluster.source/land!` is one call of `seon.merge/merge!` (its only caller is
  `seon.turn/land-end!`, dead since M5; M8 deletes both). Deleted: `land-held!`, `acceptance-names`,
  `:seon.source/landed`, `:seon.source/step-ms`, `:seon.squash/copy`, `/datoms`, `/conflicts`, `/context`,
  the open-merge-request clause of `seon.cluster.agent/kept?` (moot: nothing reaches main with its code
  unmerged). The outcome rule in `merge-tx`: `:closed` for a `:sent-back` end, else `:merged`.
- **Two red tests on HEAD went green** by the same root cause, a repeat merge that still transacted:
  `seon.merge-test/a-repeated-merge-adds-nothing` and
  `seon.cluster.db-flow-test/an-error-end-copies-nothing-and-writes-roots-problem-task` (a base at C now
  answers without a transaction). Two code merges red on HEAD (the request never reached main since M5)
  are green: `seon.cluster.merge-test/a-green-request-lands-on-main-and-disk-in-one-call` and
  `a-function-and-its-schema-land-as-one-unit`; tasks `seon.cluster.source/land-is-one-call`,
  `seon.cluster.source/a-function-and-its-schema-land-as-one-unit` and `my.root/a-merge-is-keyed-by-the-assignment`
  retired, their deftests kept untagged.

## The rule the code forced: a requested merge waits for root, whole

One base means the facts and the declarations of a branch merge in the same transaction; a facts-only
merge would advance the base past the unmerged code. Publication cannot run automatically on default yet:
`:seon.config.fs/working-root` is `"."`, which on a JVM running its committed archive resolves to
`data/source/<sha>`, and `write-back!` refuses that root (`seon.fs/checkout-root?`), so an automatic
code merge would put declarations on main that no file holds (measured: `(System/getProperty "user.dir")`
on default is `/Users/sean/src/seon/data/source/46a1c4f06…`). So db<->flow derives, from the branch head
(`seon.cluster.agent/lifecycle-facts`, `:seon.flow.plan/requested?`):

- a branch whose head holds the agent's merge request for its assignment is never `:merge`d by db<->flow;
  its end alone is written on main (`:close`, with root's notice task naming `(my.root/merge! …)`), so the
  planner sees the assignment ended and starts no second graph;
- root merges it whole with `my.root/merge!`; the next pass deletes the branch (head = base);
- every other merged end (no request: a step a successor needs, a sent-back end) merges its facts as
  before; its declarations, if any, reach main with its successor's requested merge (whose base is the
  chain's fork from main).

This is today's behavior of code (root reviews it) on the one engine. M8 removes the request and the
`requested?` rule together, once M9 makes publication derived due work with a valid root.

Still two bases inside the declaration gate: `prepare-merge!` compares from the branch's fork entity
(`seon.cluster.source/merge-base`), `merge-tx` from `:seon.task/merge-base`. They are equal for every merge
M6 can make (a requested branch merges once, from its fork), and both advance in the one transaction.
M7 (`merge-tx`'s compare-and-swap replaces `declarations-held-tx`; `:parents` dropped) makes the gate read
the assignment's base and deletes the fork-entity base from the merge.

## For M8 and M4

- **M8** deletes `my.program/request-merge!`, the request rows, `:seon.flow.plan/requested?`, the
  `requested?` clauses in `next-actions` and the request read in `seon.merge/merge!` (`merge-request`); the
  declaration partition then merges for every done step (proofs from the task's success tests, as
  `my.program/request-merge!` reads them today), which needs M7's designed-red rule for Prove steps and M9's
  publication. `seon.cluster.source/land!` and `seon.turn/land-end!` go with it.
- **M4** has its place now: `seon.merge/merge!` holds the store and the merge branch exists in the
  requested path (`prepare-merge!`'s candidate). The facts three-way still runs only in main's writer;
  `conflict-task!` writes root's prose task. M4 needs the facts three-way applied on the merge branch
  too (today only declarations are written there), so the unmerged entities are written in the transaction
  that meets them; `:seon.merge/conflicts` in `merge!`'s answer is the shape to replace.

## Proofs and costs

- New: `my.root-test/a-merge-is-keyed-by-the-assignment` (red on HEAD: the contract refused
  `:seon.task/assignment`; green: outcome `:merged`, merge base = C, merge commit, the end reached main in the
  merge, it leaves the open list). Converted: the `my.root-test` deftests, `seon.cluster.merge-test`'s code
  merges (through `my.root/merge!`), `seon.cluster.db-flow-test`'s lifecycle table (two rows) and
  `a-requested-green-end-waits-on-its-branch-for-roots-merge`, `seon.cluster.host-test`'s two merge-request
  tests.
- Cost (`seon.profile/measure`, check JVM): a facts merge through `seon.merge/merge!`, warm, 6.4 ms, 1,587
  calls, 1 MB (M3 measured `merge!` at 18–26 ms warm); the same through `my.root/merge!` as a JVM's first
  merge, 417 ms, 1.0 GB, 359 ms in `seon.db/vector-query-call` (cold: the first squash three-way and
  projection reads). A one-function code merge (gate run on the merge branch, accept, write-back, git
  commit) 548 ms / 142k calls / 470 MB second in its JVM, 1,275 ms / 2.3 GB first. Under the 1 s target warm;
  the first merge's gigabyte is a finding for the cost lane.
- Still red, not M6's: `seon.cluster.merge-test/a-require-in-the-agents-user-namespace-lands-with-its-change`
  (write-back refuses `[:seon.ns/name user]` as `:one-file`: open task
  `seon.cluster.source/an-agents-repl-scratch-namespace-never-blocks-its-landing`),
  `an-end-message-naming-a-merge-task-is-not-a-request` (root's inbox refuses the end message: "The
  recipient's inbox could not accept this message."), `seon.cluster.host-test/a-merge-request-waits-for-its-assigned-tasks-proofs-then-reaches-root`
  (red on HEAD: the task the hosted agent creates is on its branch, so root's `assign!` answers
  `unknown-task`), `seon.turn-continue-test/a-green-end-lands-its-merge-without-root` (the dead
  `land-end!` path, M8), `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection`
  (red on HEAD).
