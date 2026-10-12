---
title: "M8 rest (goal lane goal-m8): S5 committed; publication's signal and the deletions wait on held files"
type: research
status: goal lane, 2026-10-12, time box 45 minutes. Slice (a) committed (2736c187d). Slices (b) and (c) not committed: their files are held (src/seon/cluster/agent.clj and test/seon/cluster/db_flow_test.clj by lane agent-batch, src/seon/turn.clj by lane dsml).
---

# M8 rest notes

## (a) S5, committed 2736c187d

`seon.task/ready-rules` `leaf-done` reads `[?a :seon.task/outcome :merged ?tx]` (with the later `:sent-back`
clause unchanged), so `seon.task/ready`, `seon.task/done?`, `seon.task/resolution` and
`seon.cluster.agent/plan-facts` count a need done only once its assignment merged on main. Proof
`seon.task-test/the-ready-leaves-are-those-whose-needs-are-done`: red on HEAD (run df0d5496bfef, 2 fails), green.

**Patch outstanding for lane agent-batch:** `m8-s5-db-flow-test.patch` (one line).
`seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` hand-writes need a's `:done` end
on main without `:seon.task/outcome :merged`; it is red at 2736c187d until the patch is applied. Verified green with
the patch on a scratch commit (f91470f79, run 4b9a9ce117d2: only the three db-flow reds already red on HEAD,
run ac16981b4df8).

Why no gap opens: on main a `:done` end arrives only through `seon.merge/merge-tx`, in the same transaction as its
`:merged` outcome (`next-actions` merges only `:done` and `:sent-back` ends; `:sent-back` closes `:closed`). A `:done`
end on main without the outcome exists only in hand-written fixtures; such a leaf would read as ready again.

## (b) Publication's signal: not done, a design decision for root

`seon.cluster.agent/publication-due?` (agent.clj, held) reads "its merge request reached main". Replacing it by
"`:merged` + `:seon.task/merge-commit`, no `:seon.task/git-commit`, no refusal" alone makes every fact-only merge
(no declarations: most db-flow merges, every cluster without a checkout) due for publication on every pass:
`seon.merge/publish!` then either refuses at `checkout` (a `:seon.task/refusal` recorded on every such merge) or,
with an empty path set, records nothing and stays due forever (a `merge-accepted` per pass). Options:
1. (recommended) `seon.merge/merge!` knows its accepted declarations at merge time: record whether the merge moved
   any (the accepted paths) in `merge-tx`'s transaction, e.g. publication due iff the merge commit's accepted
   declarations are non-empty, derived once and written as a fact; `publication-due?` reads it.
2. `publish!` with no paths writes a terminal fact (it published nothing) and answers; costs one `merge-accepted`
   per fact-only merge, once.
3. Keep the request read until (c) deletes the request; then derive declarations from `seon.cluster.source/merge-accepted`.
Also `seon.merge/publish!` reads `(merged-request database a)` for the run id: it goes with the request.

## (c) Deletions

- **Dead chain, ready as a patch:** `m8-dead-chain.patch` (HEAD c264da25e-based, applies to 2736c187d; scratch commit
  fdf6c20f6). Deletes `seon.turn/land-ended!` (no caller anywhere), `land-end!`, `landing-tx`, `root-execution`,
  the `source-land!` delay and the now-unused `clojure.java.io` require; `seon.cluster.source/land!` (its only caller
  was that delay); `:seon.config.root/land?` (`resources/seon/schemas/seon.config.root.edn` deleted whole, it held
  only that dial; its entry in `config/default.edn`); and the tests of the deleted machinery in
  `test/seon/turn_continue_test.clj`: `a-green-end-lands-its-merge-without-root` (long; asserted landing-tx's
  `:seon.task/landed` facts) and `root-without-a-branch-row-lands-in-the-clusters-world` (asserted `root-execution`),
  with their only helpers `ended-request`, `git!`, `declare-file!`, `landing-source`. 201 lines deleted, 0 added.
  Checked: `seon.turn-continue-test` and `seon.config-test` at fdf6c20f6 (run 7346e48f24b8) hold exactly HEAD's
  reds (run 86699c687d81: `config-test/the-default-document-has-one-canonical-complete-location`,
  `turn-continue-test/provider-progress-has-its-own-bound`, `read-results-arrive-before-done`). Lint of the touched
  files shows no new finding. It needs `src/seon/turn.clj` (lane dsml). Same commit must also convert the task
  references to the deleted symbols: retire the `seon.turn.edn` task whose intent is `seon.turn/land-end!`'s
  refusal; drop `seon.cluster.source/land!` / `seon.turn/landing-tx` from `:must-read` in `seon.cluster.source.edn`
  (lines 3-4, 12, 93-94), `seon.test.edn` (210, 228), `my.program.edn` (4); the `seon.task.edn` M8 entry stays
  until the rest goes. Docs: `docs/seon/plan.md`, `docs/seon/schedule/cards/TASK-CHAIN.md` name `:land?`/`land-end!`.
- **Not started (needs (b) and agent.clj/turn.clj):** `seon.task/request-merge!`, `my.program/request-merge!`,
  `seon.turn/end-merge-request` and `end-task!`'s merge clause, the merge-request schema. The live `:merge` action
  (`seon.cluster.agent/next-actions` → `seon.merge/merge!`) still reads the agent's merge request
  (`seon.merge/merge-request`) for its proofs and run id, and `publication-due?` reads it: the gate must take the
  proofs from the assignment's task instead before the verb can go (prebrief-m6-m8-m10 risk: a done branch whose
  declarations silently stay on the branch; the proof must assert the digest on main).
- `(help)`'s "green proofs end your task and tell root" (src/seon/bootstrap.clj) is still true at 2736c187d.

RESTART NEEDED for 2736c187d to be live on default (ordinary `src/seon/task.clj`).
