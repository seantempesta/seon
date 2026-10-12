---
title: "Plan (Opus): done is derived from proofs, root merges the merge request whole"
type: research
status: independent plan for the design round on done and merge (one of two; Fable writes the hybrid). Read-only against HEAD fede4d3d4, Datahike 42309184, Malli dd13ff81. Experiments on agent plan-opus's own branch with `d/db-with` values; nothing written on main.
---

# Done is derived; root merges the merge request whole

**What exists.** Today the agent's loop derives an end (`seon.turn/task-end`), calls `my.program/request-merge!` to write a merge task plus a merge request, and root's `seon.cluster.agent/db<->flow` copies facts with `seon.cluster.squash/copy!` (fixed base, decision taken before the writer, a marker `:seon.squash/attempt` as the completion test), while code goes through `seon.cluster.source/land!` (a merge commit with parents). That is two merge shapes, two merge bases, and an agent verb.

**This plan.** One entity, the **merge request**, opened by root when it assigns the task (what the code calls an attempt today). It holds one stored **merge base** that root's writer advances in the same transaction as each merge. *Done* is a query over test evidence on the source branch, judged by the step rule `seon.context/step-status` against the merge base, and never stored. Root's `db<->flow` derives each merge request's next action from facts and the branch roster, nothing else. The actions are: merge it whole into main, close it with the reason the facts give, stop its graph, delete its branch, or merge main into its source branch. The actions use one merge shape: a three-way per coordinate over both partitions (program and cluster facts). It runs on a merge branch for the test gate, and the writer re-checks it on its own db value. It is a squash: one parent, base stored. Every action is idempotent by identity or by `head = merge base`, so a crash at any point is recovered by the next derivation. There is no marker, retry or catch-and-continue.

## 0. REPL evidence (plan-opus branch, values only)

| Form (abridged) | Value | ms |
|---|---|---|
| `(c/step-status {:seon.task/step :implement :seon.task/proofs #{'a/t} :seon.context/head {'a/t {:seon.context/proof-status :green}} :seon.context/fork-point {'a/t true}})` | `{:seon.task/status :done …}`: a proof green at the fork point is done (dig edge 14 reproduced) | 0 |
| base intent "a", branch t1 "b", first `squash-merge t1 base base` → main1; branch reverts to "a" (t2); `(squash-merge t2 base main1)` with the **fixed** base | `{:tx-data []}`: the agent's revert is lost | 0 |
| same with the **advanced** base `(squash-merge t2 t1 main1)` | `[[:db/retract 52281 :seon.task/intent "b"] [:db/add 52281 :seon.task/intent "a"]]`: correct | 0 |
| repeat at the advanced base `(squash-merge t1 t1 main1)` | `{:tx-data [] :conflicts []}`: a repeat writes nothing | 0 |
| `(d/db-with main1 [[:db.fn/call (fn [db] (:tx-data (sq/squash-merge t2 t1 db proj nil)))]])` | intent "a": the three-way runs inside the writer on its own db value | 7 |
| root edits main after capture ("root edited"), then the three-way on that db | `:conflicts [{:base "b" :ours "root edited" :theirs "a"}]`: computed in the writer it is a conflict, never an overwrite (Astra #3) | 0 |
| base `#{"x"}`; branch adds "y", main adds "z" on `:seon.task/evidence` | a **conflict**, with no union: disjoint additions to a set conflict today (a new finding) | 2 |
| `squash-merge` on one changed task | 3.1 ms; the request's `:seon.repl/ms` ≈ 200 to 360 ms, mostly context acquisition and the test step | |

Pinned seams verified: `datahike.versioning/force-branch!` takes `:expected-current-commit` (a compare-and-swap on a ref). `:datahike/expected-basis-t` is a whole-head precondition (`datahike.writing`). `:datahike.gc/roots` is absent (zero hits).

## 1. The data

**Identity.** The merge request is the existing assignment entity (`:seon.task/assignment`, `seon.id/id` over `[task number]`). There is one per assignment, so it answers dig question 9. The separate merge request entity and the generated `:merge` task are deleted (§6).

| Attribute (schema `seon.task.edn`) | Stored or derived | Producer, then consumers |
|---|---|---|
| `:seon.task/merge-base` `:uuid` (new; replaces `:seon.task.attempt/fork` and the marker) | stored, root only | **Producer:** `seon.task/assign!` writes the main head it forks from. Each merge's writer transaction (`seon.merge/merge-tx`) moves it to the merged source commit C. A merge of main into the source branch moves it to that main commit M. **Consumers:** the three-way, `step-status` (its fork point), branch deletion, GC roots. |
| `:seon.task/outcome` `:merged \| :closed` (exists) | stored, root only, written inside a transaction function that writes only when it is absent | **Producer:** `merge-tx` or `close-tx`. **Consumers:** the plan (a need is done when its latest merge request is `:merged`), `my.root/merge-requests`. |
| `:seon.task/closed-as` enum `:given-back :sent-back :capped :stalled :cancelled :error :satisfied :invalid` (renames `:seon.task.attempt/end`, minus `:done`) | stored with `:closed` | **Producer:** derived by root from the source head C and main's intent, then recorded in `close-tx`. **Consumers:** relaunch, root's problem tasks, successors. |
| `:seon.task/reason`, `:seon.task.attempt/target` (exist) and `:seon.task/counterexample` (new, shown text) | stored with `:closed` | **Producer:** read from the branch's `my.task/send-back!` facts at C. |
| `:seon.task/merge-commit` (renames `:seon.task/landed`), `:seon.task/git-commit` (renames `:landed-git`) | stored | **Producer:** `merge-tx`, then the write-back. |
| `:seon.merge/unmerged` (new entity on the **merge branch** only; identity `seon.id/id [assignment attribute entity-identity]`; members `:seon.merge/attribute`, `:seon.merge/entity`, `:seon.merge/base`, `:seon.merge/ours`, `:seon.merge/theirs`, `:seon.merge/source-commit`; value shape = today's `:seon.squash/conflict`) | stored on the merge branch, git's index stages | **Producer:** `seon.merge/merge!`. **Consumers:** root at its REPL, `my.root/merge-requests`. |
| *done* | **derived** | `seon.context/step-status` over the source head and the merge base (§2) |
| *up to date* (`head = merge-base`), *unmerged* (the merge branch holds unmerged entities), *deletable*, *should stop* | **derived** | root's `seon.merge/next-actions` |
| occurrence count | **derived**: `(count :seon.error.occurrence/evaluations)`, a set merged by union | replaces the `:add` rule and `add-count` |

Retired: `:seon.squash/attempt`, `:seon.squash/source-branch`/`source-commit` as completion evidence (their provenance moves into the merge transaction's metadata, with no reader), the merge request entity schema `:seon.task/merge-request-row` (`:seon.task/merge-request`, `:merge`, `:of-assignment`, `:proved`, `:red-first`, `:run-id`, `:program`), `:seon.task/kind :merge`, `:seon.task.attempt/fork`, `:seon.task.attempt/commit` (the successor's fork basis is main, §3), and the `:base/<branch>` retained ref once GC roots land (§5 Q10).

## 2. Done, derived

A merge request is **done** at source commit C when `step-status` returns `:done` over an observation that has three parts. **Head:** C's latest results for the proofs and for the reached reds. **Fork point:** the results at the merge request's merge base, meaning results whose reach digest is the base program's reach digest. These come from the first proof run (`seon.turn/prove-turn` runs the proofs at the base program before any model call). After a merge of main into the branch, they come from the rerun at the new base. **Rule.** Two changes to today's rule:

- `:implement`: the proofs are non-empty and all green at the head; **every proof is red at the base** (the red-first observation, derived, never a stored set); nothing is broken (red at head, not red at base); every new red is a proof.
- `:prove`: unchanged (own proofs green; product non-empty, new, each one red by design).
- A proof green at the base is reported as `:seon.context/green-before`. If every proof is in it, root closes the merge request `:satisfied`: the task is already true on main (R-8), it retires itself, and nothing merges. If only some are, root closes it `:invalid` with a problem task for the task's author ("a task already green proves nothing").

**Who computes it, when, and at what cost.** The agent's own test step already writes its judgment on its branch (`seon.turn/record-broken!`) and its loop stops calling the model when it is done. Root recomputes `done?` from the evidence on each wake, only for merge requests whose branch head differs from the commit root last judged (one roster read). The cost is proportional to |proofs| + |reached reds| of the changed branches: one reach digest read and one latest result read per test. That is about 1 ms per proof, and nothing proportional to the program.

- **Question task:** its proofs read the answer that `my.agent/answer!` stored on the branch, so it is done by the same rule and its answer reaches main in the whole merge.
- **Prove step:** done by its rule; its red product merges into main (§3).
- **Root's gate** is this same function, judged on the merge branch (the merge result) with the fork point at main's head. There are no verdict tiers.

## 3. The merge, by root

**One shape.** A three-way per coordinate `[attribute entity]` over the program partition and the cluster partition, with base = `:seon.task/merge-base`, ours = main, theirs = C. Rules:

- Changed on one side only: take that side.
- Changed on both sides to the same value: nothing to do.
- Changed on both sides to different values: the attribute's `:seon.cluster/conflict` rule (`:ours`/`:theirs`), otherwise it is **unmerged**.
- Cardinality-many attributes merge **member by member**: `(ours − (base − theirs)) ∪ (theirs − base)`, as git merges lines. This makes counters unnecessary.

It is a squash: one parent, main. Git's merge commit is not needed because the merge base is stored and advanced atomically, so ancestry never has to be walked (and walking it would fail after GC). Datahike's `merge-db!` records parents and computes nothing, and parents would pin the agent's lineage inside the snapshot window for no reader. `accept-merge!` drops its `:parents`; one shape remains.

**The steps, all in `seon.merge/merge!`, called only by root's `db<->flow` (and `my.root/merge!` at root's REPL, the same function):**

1. Fork a merge branch `merge/<assignment>` from main's head B. Apply the three-way(base, C, B) there. Conflicts become `:seon.merge/unmerged` entities on that branch and stop the merge there: the merge request stays open and root has the three values as data.
2. Gate: run `step-status` on the merge branch (its proofs plus the tests reaching the change, through the one runner). Results are reused by reach digest, so an unchanged merge result never reruns.
3. If the program partition changed: write back to files and make one git commit (the files hold the merged result first; boot derives default's program from files, so a crash after this point converges, see §4, case 1).
4. Transact `[:db.fn/call seon.merge/merge-tx assignment C expected]` on main. Inside the writer, on its own db value, it does three things. It checks that every touched coordinate and every definition reached by the gated tests still has, on main, the digest it had at B (`expected` is that map; the cost is O(|touched| + |reached|) index reads; this is git's `update-ref new old` per coordinate). It writes the merge result, `:seon.task/merge-base C`, `:outcome :merged`, `:merge-commit` and `:git-commit`, all in that one transaction. If any digest moved, it returns `[]`. The main transaction that moved it also woke `db<->flow`, which re-derives from the new main, so there is no retry loop.

**Merge base advance:**

- When a merge request is assigned, the base is the main head it forks from.
- After a merge into main, the base is C.
- After a merge of main M into the source branch, the base is M.

Every value lies on the agreed state of both sides, as the experiment shows. **Fast-forward:** if main has not moved since B and nothing is unmerged, step 4 passes trivially. The merge result *is* the tested commit, so there is no separate path.

**Merging main into the source branch.** The trigger: the gate on the merge result is not done and the source is done, which means main moved under the change. Root then runs the same three-way with source = main and target = the agent's branch, and sets the base to M. Unmerged coordinates go to the agent's branch for the agent. The agent's listen route wakes it, and its first pass reruns the proofs whose results are pending at the head's reach digests (generalizing prove-first from "keyed by attempt" to "pending at this program").

**Forking.** Every merge request forks **main** once all its `:seon.task/needs` are merged. Joins are therefore ordinary: no octopus and no predecessor branch kept. A Prove step's product (red by design and tagged with its successor's open task) merges into main, admitted by the Prove rule on the merge result. Main already holds designed reds for every assignable task that root files.

## 4. Every edge case (dig §3, cases 1 to 34, then new ones)

| # | Design that makes it impossible or correct |
|---|---|
| 1 | Crash after the merge, before stop or delete: nothing is a marker. `next-actions` derives *stop* from `outcome ∧ graph in roster`, and *delete* from `outcome ∧ graph exited ∧ (head = base ∨ ¬done(head)) ∧ no unmerged`, at every pass and at the `::flow/resume` prime. A crash between the git commit and `merge-tx`: the rerun's write-back finds equal bytes and commits nothing. If boot adopted the files first, the three-way finds ours = theirs, so `merge-tx` only advances the base. |
| 2, 20, 23 | The base advances inside `merge-tx`, and a repeat at `head = base` writes nothing (experiment). `:add`/`add-count` are deleted and the count is the size of a set merged by union. |
| 3, 19 | The three-way's acceptance runs in the writer on its own db value (experiment). Root's later edit is ours: if the agent touched the same coordinate it is unmerged, otherwise it is kept. |
| 4, 24 | Conflicts are `:seon.merge/unmerged` entities holding the three values on the merge branch. The merge request stays open, and the merge branch is deleted only when it holds no unmerged entity. The `:root` default rule becomes "unmerged" (git's word). |
| 5, 17, 21, 22 | Successors fork main after their needs merge. No predecessor branch is kept, so a held successor holds nothing and `successor-waits?` is deleted. GC: the open merge requests' merge bases are roots (Q10). |
| 6 | `apply-plan!` and `db<->flow` lose their per-action catches. Each action is a transaction function or a Flow effect whose failure is an error value recorded by the entrant (the Flow proc). The facts are unchanged, so the next pass derives the same action, and a failed start is the merge request's `:error` close. |
| 7 | Already fixed; `detached!` is deleted with its last caller. |
| 8 | One starter: `plan` over merge requests of `:running` tasks. `agents-to-start` and the held arm are deleted in P5. |
| 9, 18 | Cancel: the intent `:cancelled` on main makes root close the merge request `:cancelled` in `close-tx` (no end written on main by `follow!`, no problem task), stop the graph with interrupt, await exit, then delete the branch. Stop: the intent `:stopped` releases the graph and keeps the branch and the open merge request. Cancel mid-merge: `merge!` steps are idempotent and `merge-tx` writes only if `outcome` is absent, so a cancel that wins writes `:closed` and a merge after it writes nothing. |
| 10 | Absent at the pinned Datahike (the dig verified it). |
| 11, 12 | `attempt-rows-tx` and `seed-attempt!` are deleted: the merge request forks main after its entity is written, so the branch already holds it. |
| 13 | Out of scope (driver lane); the runner's terminal event is the only completion. |
| 14, 29 | The `:implement` rule requires red at the base. Green-before is closed `:satisfied` (all) or `:invalid` (some). A task with no proofs is not a leaf the plan launches (`basis-valid?` already reads obligations at the base). |
| 15 | Pending is never green, so a timed-out proof keeps the merge request open. The gate's red is stored by reach digest, so a flaky red waits for a change and never loops. Root sees it in `merge-requests`. |
| 16 | A sibling's merge that turns a fork-era green red fails the gate on the merge result, so root merges main into the source branch. The agent's proofs rerun at the new program and done re-derives. |
| 25 | Two merge requests touching one program identity: the second one's `merge-tx` sees the moved digest and returns `[]`. Its re-derived merge finds the conflict and leaves it unmerged on its merge branch for root. |
| 26 | Root has no merge request: it works on main, its edits are ours, and `my.root/merge!` calls the same `seon.merge/merge!`. |
| 27 | One merge carries both partitions, whole; there is no partial merge (§5 Q2). |
| 28 | A Prove step merges into main like any other merge request; its successor forks main. |
| 30 | Problem tasks and unmerged entities are named by `seon.id/id` over key data, so a duplicate is an upsert. |
| 31 | A message is a fact on the sender's branch. Root delivers it by one offer, recorded on main by message identity *before* the offer: at most once, loss in flight accepted (R-4). An undeliverable message is that record with no recipient. It reaches history with the whole merge. |
| 32 | A send-back closes the merge request `:sent-back` with its target. Relaunch is a new merge request (number n+1) forking main. |
| 33 | The base is `:seon.task/merge-base`, read through `commit-as-db` and cached by commit id. |
| 34 | The plan writes a merge request only when absent; `merge-tx` and `close-tx` are no-ops when `outcome` is present. |
| new A | Disjoint additions to a set conflict today (experiment): fixed by the member-level merge. |
| new B | Post-merge writes on the branch: `head ≠ base ∧ done(head)` merges again (outcome stays `:merged`); otherwise those writes are discarded at deletion. |
| new C | An agent that never finishes: the call cap closes it `:capped`. An idle outside agent stays open (one shared-index branch plus a retained base) and is shown with its age. There is no timeout. |
| new D | `merge-tx` stale because main moved: returns `[]`, and the move's own wake re-derives. |
| new E | Merge branch left after a crash: deleted when `outcome` is present and it holds no unmerged entity. |

## 5. The ten undecided questions

1. **Join shape.** Neither: successors fork main after every need merges. *Guarantee:* the fork is a main commit, always retained. *Cost:* the Prove product (designed red) is visible on main. *Gives up:* R-6's decision F (forking the predecessor's end commit), which the owner must revisit.
2. **Merge base home.** `:seon.task/merge-base` on the merge request, advanced in `merge-tx`. Merges are whole, so one base suffices. *Gives up:* AGENTS.md's "auto-merges rule-trusted parts as they change". Partial merges need one base per partition and lose an unmerged coordinate's stage when the base advances (the experiment's fixed-versus-advanced result). What must act before done is a root reaction by identity instead: occurrence evidence recorded by signature, message delivery. *Owner to confirm.*
3. **Already green.** Derive it at completion: red at the base is required, with `:satisfied`/`:invalid` closes. *Cost:* one proof run at the base (it exists already: prove-first).
4. **Sibling merge.** The gate on the merge result catches it, then root merges main into the source branch. *Cost:* one gate run per main move that touches reached code.
5. **Held successors.** Moot: no predecessor branch is kept.
6. **Cancel.** Close `:cancelled`, delete the branch, no problem task. *Gives up:* the audit branch. History on main keeps the merge request entity.
7. **Words.**
   - attempt → **merge request** (forge word; opened as a draft at assignment); identity key `:seon.task/assignment` kept.
   - end → `:seon.task/closed-as`; done stays a derived `:seon.task/status`.
   - step stays the owner's word.
   - problem task → task assigned to root.
   - `:root` rule → unmerged.
   - land/copy/settle → merge/delete-branch.
   - `seon.cluster.squash` → `seon.merge`.
8. **Auto-merge.** Merge automatically when: done on the source, the gate is done on the merge result, nothing is unmerged, and `merge-tx`'s check passes. Delete `:seon.config.root/land?`. Root reviews only what is unmerged. This supersedes R-10 per R-1. *Gives up:* review of every clean diff.
9. **Identity.** One per assignment (the assignment entity).
10. **GC roots.** Make the fork change: `datahike.gc/gc-storage!` takes `:datahike.gc/roots`, a set of commit ids that Seon derives from open merge requests' merge bases. That retires the `:base/<branch>` ref and `registry/advance-base!`. Until it lands, the ref is moved to the stored base by `force-branch!` with `:expected-current-commit` after `merge-tx`, and the remaining window is the snapshot window. That ordering is the one temporary gap, and the fork change exists to remove it.

## 6. Deleted, replaced, net lines

| Deleted (lines today) | Replaced by |
|---|---|
| `seon.cluster.agent/unlanded-ends` 21, `land-ends!` 41, `record-error-end!` 24, `ended-problem!` 15, `settle-kept-branches!` 23, `successor-waits?` 19, `detached!` 29, `branch-end` 11, `seed-attempt!` 18, the predecessor arm of `fork-attempt!` ~12 | `seon.merge/next-actions` (pure, ~45) and `apply-actions!` (~25) inside `db<->flow` |
| `seon.cluster.squash/copy!` 31, `add-count` 7, `conflict-task!` 15, `squash-merge!` 11 | `seon.merge/three-way` (today's `squash-merge` plus member-level sets, +10), `merge-tx` (~35), `merge!` (~40, absorbing `source/land-held!` 42) |
| `seon.turn/end-task!`'s merge arm ~25, `land-end!` 25, `land-ended!` 14, `end-merge-request` 21, `landing-tx` 15; `my.program/request-merge!` 42; `seon.task/request-merge!` 25, `red-first` 21, `attempt-rows-tx` 21; `registry/advance-base!` 6 | `close-tx` (~20), the `step-status` red-at-base rule (+8), `seon.merge/update!` (~20) |
| Schemas: `:seon.task/merge-request-row`, `:seon.squash/attempt`, `:seon.task.attempt/fork`/`commit`, `:merge` kind; the `:seon.config.root/land?` dial | the §1 attributes |

Source lines: about −480 deleted, +225 added, **net about −255**. Tests of the deleted machinery go with their subject (the `request-merge!` callers in `test/my/root_test.clj` and `test/seon/turn_continue_test.clj`; the four `seon.flow/an-ended-attempt-reaches-main…` and `a-refinement-on-a-branch-reaches-main-only-at-the-end` deftests are converted into the proofs below).

## 7. Phases (about 15 minutes each; HEAD boots after each; every proof is red before its phase)

| Phase | Change, and the deletion it carries | Red proof (deftest: assertion on data) | Cost bound (`seon.profile/measure`) |
|---|---|---|---|
| P1 | The `step-status` `:implement` rule requires red at the base, and reports `:seon.context/green-before` | `seon.context-test/a-proof-green-before-the-work-is-not-done`: `(= :working (:seon.task/status s))` and `(= #{'a/t} (:seon.context/green-before s))` | `step-status` < 1 ms |
| P2 | Member-level set merge; delete `add-count` and `:add`; occurrence count derived | `seon.cluster.squash-test/disjoint-set-additions-merge-as-their-union`: `:conflicts []`, and main's set becomes `#{"x" "y" "z"}` | three-way on one changed task < 10 ms |
| P3 | `:seon.task/merge-base`; `merge-tx` for the cluster partition replaces `copy!` inside `land-ends!` | `a-repeated-merge-adds-nothing` (second tx-data `[]`); `a-root-edit-after-capture-is-unmerged-not-overwritten` (main keeps "root edited"); `a-reverted-branch-value-reverts-on-main` | `merge-tx` writer time < 20 ms |
| P4 | Unmerged entities on the merge branch; delete `conflict-task!` | `a-conflict-keeps-its-three-values-on-the-merge-branch`: the `:seon.merge/base`/`ours`/`theirs` values equal the experiment's | < 50 ms |
| P5 | `next-actions` replaces the marker: delete `:seon.squash/attempt`, `unlanded-ends`, `settle-kept-branches!`, `record-error-end!`, `ended-problem!` and the catches | `a-merged-request-whose-graph-survived-a-crash-is-stopped-and-deleted`: after one pass, the branch is absent from the roster and the graph from the Flow roster | one pass over 10 merge requests < 50 ms |
| P6 | Root merges done merge requests with no agent verb: delete `request-merge!` (both), `end-merge-request`, `land-end!`, `land-ended!`, `landing-tx`, `:seon.task/merge-request-row`, the `:merge` kind and `:seon.config.root/land?` | `a-done-branch-merges-with-no-merge-request-written-by-the-agent`: `(= :merged (:seon.task/outcome mr))`, and the agent's branch holds no merge facts | detection < 5 ms per changed branch |
| P7 | The program partition goes through `merge!`: the gate is `step-status` on the merge branch, write-back before `merge-tx`, `accept-merge!` loses `:parents` and checks reached digests | `a-merge-whose-reached-definition-moved-writes-nothing`: tx-data `[]` and outcome absent | gate reuse with an unchanged result < 100 ms |
| P8 | Fork main after needs merge: delete `:seon.task.attempt/fork`/`commit`, `successor-waits?`, `attempt-rows-tx`, `seed-attempt!`, the predecessor fork | `a-join-forks-main-after-both-needs-merge`: both needs' facts are present in the successor's fork db; `a-prove-steps-red-product-merges-into-main` | fork < 20 ms |
| P9 | Merging main into the source branch on gate-not-done | `a-sibling-merge-that-breaks-a-proof-undoes-done`: `(= :working (:seon.task/status …))` on the source after the merge from main | `update!` < 50 ms |
| P10 | Close derivations (send-back, cap, cancel, error, satisfied, invalid); `follow!` writes nothing on main | `a-cancelled-merge-request-closes-without-a-task-for-root`: `(= :cancelled (:seon.task/closed-as mr))` and zero new root tasks | `close-tx` < 10 ms |
| P11 | Datahike fork `:datahike.gc/roots`; delete the `:base/<branch>` ref and `advance-base!` | `a-merge-base-older-than-the-window-survives-collection`: `commit-as-db` of the base answers after `gc-storage!` | GC unchanged within 10 % |
| P12 | Scripted vocabulary sweep (§5 Q7), one commit, lint zero | the existing suite; `seon.vocabulary-test` finds no retired word | — |

P3 to P5 are the ordering constraint: `copy!` is deleted in P3 because `merge-tx` takes over its last caller there, and the marker is deleted in P5 together with its last reader.
