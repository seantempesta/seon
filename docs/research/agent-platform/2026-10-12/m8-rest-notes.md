---
title: "M8 rest (goal lanes goal-m8, goal-m8b): S5, the dead chain and publication's signal committed; the gate's proofs and the verb's deletion next"
type: research
status: goal lanes, 2026-10-12. (a) committed (2736c187d); the dead chain of (c) committed (ad20b764d); (b) committed by lane goal-m8b on root's ruling (option 1); the rest of (c) not started.
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

## (b) Publication's signal: done (lane goal-m8b), root's option 1

`seon.merge/merge!`'s gated path adds `[:db/add a :seon.task/merged-declarations n]` (n = the count of
`seon.cluster.source/prepare-merge!`'s `:seon.source/expected-digests`, never 0: an empty delta refuses at the gate) to
the `merging` data `seon.cluster.source/accept-merge!` transacts, so the fact is written in the merge transaction
itself. `:seon.task/merged-declarations` (`[:int {:min 1}]`) is declared in `seon.task.edn` and on
`:seon.task/assignment-row`. `seon.cluster.agent/publication-due?` reads only the pulled row (merged, merge commit,
merged declarations, no git commit, no refusal) and no longer queries the merge request; `lifecycle-facts` pulls the
new attribute. A facts-only merge records nothing and is never due.

Proofs: `seon.cluster.merge-test/an-accepted-merge-is-published-by-the-next-pass` asserts `(= 1 merged-declarations)`
after the gated merge: red with the test alone on HEAD (run 57d2d156d5fd: the attribute is undeclared), green with the
change (run 639de3be31d6) except its line "main is unchanged" after a gate refusal, red on HEAD too (run 26fdcd4daab7
at 52ff26287): a refused gate moves main's commit; pre-existing, not this change's. New
`seon.merge-test/a-facts-only-merge-is-never-due-for-publication` (the facts-only path: no fact, no `:publish?`) and
`seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act` (publication now depends on
the fact) green.

Left from (b): `seon.merge/publish!` still reads `merged-request` for the git commit's run id; it goes with the
request in (c) (the acceptance's run id is optional; the gate's own run, `:seon.test.run/id` of the prepared merge,
is the one to record if a consumer needs it).

## (c) Deletions

- **Dead chain, committed in ad20b764d from this patch:** `m8-dead-chain.patch` (HEAD c264da25e-based, applies to 2736c187d; scratch commit
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

### (c) next, as found by lane goal-m8b (not started)

- The gate needs one more decision before the verb can go: without the request, `merge!` must know whether the
  branch changed declarations at all (`prepare-merge!` refuses an empty delta, and most merges are facts only).
  Simplest: `merge!` reads the three-way once (`seon.cluster.source/compare-branch`, as
  `seon.cluster.agent/unmerged-count` does) and gates only when it is non-empty, with the proofs from
  `seon.task/obligations` of the assignment's task (`:seon.task/proofs`); the alternative is `prepare-merge!`
  answering an empty delta as a value rather than a refusal (source.clj, lane m8-apply). The first costs a second
  comparison per declaration merge; the second changes `prepare-merge!`'s contract.
- Callers to convert in the deleting commit: `seon.turn/end-task!`'s merge clause and `end-merge-request`,
  the `program-request-merge!` delay (turn.clj); `my.program/request-merge!`; `seon.task/request-merge!`,
  `red-first`; `seon.merge/merge-request`, `merged-request`; the request schema (`:seon.task/merge-request`,
  `-row`, `-view`, `:merge`, `:of-assignment`, `:commit`, `:proved`, `:red-first`) in `seon.task.edn`; tests
  `seon.cluster.merge-test` (`requested-agent!` calls the verb), `seon.cluster.host-test`
  (`a-merge-request-waits-for-its-assigned-tasks-proofs-then-reaches-root`, 427-460),
  `seon.turn-continue-test` (306, 427-520), `seon.cluster.db-flow-test` (461, 470, 751, 989; held by m8-apply).
- Red proof: `seon.task-test/a-done-branch-merges-with-no-merge-request-written-by-the-agent` (task exists in
  `seon.task.edn`, deftest absent): an own-branch agent defines the fix, its done end is written with no request,
  one `db<->flow` pass: `:merged`, main's `:seon.program/definition-digest` of the fixed function equals the
  branch's, and the branch holds no `:seon.task/merge-request`.

### (c) gate, committed fa0b0b6cb (lane goal-m8c); refused-merge writes, 7a4fba11c

- **A refused merge's writes on main** (7a4fba11c). Measured (probe run 68cce6c04481, every attribute in
  `db/since` main's basis-t after a refused `merge!`): only `:seon.test.run/*`, `:seon.test.member/*`,
  `:seon.fn.cost/*` and the transaction's own attributes: `seon.test/change-reds`' main side
  (`seon.test/fork-point-red` records on the execution's connection since R5, 3130cd58c), root's own evidence,
  which main keeps by the owner's Q2/R8. No assignment, program or cluster fact. The test now asserts no attribute
  of `seon.program/program-attributes` or `seon.program/cluster-attributes` was written; change-reds' docstring
  corrected.
- **The gate** (fa0b0b6cb). `seon.merge/merge!` counts with `seon.cluster.source/unmerged-count` (moved from
  `seon.cluster.agent`) and gates with `seon.task/obligations`' `:seon.task/proofs` of the assignment's task on the
  branch head. Facts only for a `:sent-back` source and for a step another task needs (the producer rule
  `end-task!` applied before; a Prove step's product reds pass no gate until M7). `publish!` no longer reads the
  request; `merge-request` and `merged-request` are deleted. Proof red on HEAD (run 516b8f25b293), green (run
  571360df84c7). `seon.cluster.db-flow-test`: HEAD's three reds, same tally (runs 15e982d8be0f, 47db8056ddd3).
- **Left: the verb's deletion**, one commit with `src/seon/turn.clj` (lane one-bound holds it, with
  `test/seon/turn_continue_test.clj`): `seon.turn/end-task!`'s merge clause (`merge?`, `recorded`, `execution`,
  `request` and its fault), `end-merge-request`, the `program-request-merge!` delay; `my.program/request-merge!`;
  `seon.task/request-merge!`, `red-first`; the request schema in `seon.task.edn`; tests: merge-test's
  `requested-agent!` becomes `assigned-agent!` (its callers keep their assertions), `seon.cluster.host-test`
  427-460, `seon.turn-continue-test` 306, 427-520, `seon.cluster.db-flow-test` 461, 470, 751, 989. Retire
  `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent` in that commit (its deftest is green).
