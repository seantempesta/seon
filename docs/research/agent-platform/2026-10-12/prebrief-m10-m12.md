---
title: "Pre-brief: M10 (S6a, S6b) and M12 (S1, S1b, S2), the lifecycle needs no hands"
type: research
status: prebrief, 2026-10-12. Read-only; no source, test or resource was edited. VERIFIED = read in the code or the tests at HEAD this session (symbol and file named); UNVERIFIED = not run on a JVM, or read from a note.
basis: HEAD 8d14faeee. Lane ledger read at the start: task-writeback-impl holds 15 paths (listed in section 3).
supersedes: the M10 and M12 parts of prebrief-item2.md (635e555e2) and prebrief-m6-m8-m10.md. This brief re-derives them after S3, S4, S5, M8 (e8d0a41c9, fa0b0b6cb), M13, 214c61330, 4fded61c0 and 8d14faeee.
---

# Pre-brief: M10 and M12

Two goal slices, both on the lifecycle path db<->flow walks: `seon.cluster.agent/plan` and `apply-plan!` create attempts, `seon.task/ready` admits leaves, and `my.root` writes root's facts.

- **A, M10 (S6a + S6b):** every assignment forks main after its needs merged.
- **B, M12 (S1 + S1b + S2):** a terminal leaf is relaunched by root's fact; the relaunch is a new assignment with a new branch, forking the named commit when one is named.

## 0. What tonight's commits changed under these symbols (VERIFIED by reading the commits and HEAD)

| Commit | Change that matters here |
|---|---|
| S4 `c264da25e` | `seon.task/attempt-tx` writes `:seon.task/merge-base` beside the row, **only when `registry/base-commit-id` answers for the branch** (`base` in attempt-tx). `seon.task/attempt-rows-tx` copies it onto the branch row. `seon.turn/fork-commit` reads the row's merge base, no fallback. Its own message says `seon.cluster.agent/write-merge-base!` "stays until S6a deletes it". |
| S5 `2736c187d` | `seon.task/ready-rules` `leaf-done` reads `:seon.task/outcome :merged` on an attempt (a `:done` end whose merge reached main), not the `:done` end alone. `ready` therefore admits a leaf only after its needs' merge is on main. This is the M10 needs gate. |
| M8 `e8d0a41c9`, `fa0b0b6cb` | Agent merge verb deleted; root's `seon.merge/merge!` gates on the assignment's proofs. Not on the fork path. |
| M13 `99aa4953f` | Taskless ended agents' branches deleted via `seon.cluster.agent/taskless-actions`. Not on the attempt path. |
| 214c61330 | `registry/base-commit-id` answers only for a base ref the store's branches hold. A new branch name therefore answers nil until its fork exists. This is the reason S4's merge base is nil at `attempt-tx` for a fresh attempt (section 1, risk A1). |
| 4fded61c0, 8d14faeee | Task identity is `[ns name]`; handle `seon.id/id` over that. No change to attempt numbering or `:seon.task.attempt/*` attributes. |
| S3 `07404a3c0` | A cancel stops the graph; the next pass's `close!` writes end `:cancelled`. Relevant only as a terminal end class. |

## 1. Slice A, M10 S6a + S6b: every assignment forks main after its needs merged

### A.1 Target behavior (data in and out)

Input: a leaf `ready` admits (needs' assignments `:merged` on main). Output: `apply-plan!` writes the attempt row on main, forks `attempt-<key>` from main's head, and the branch's world holds the row and agent. No `:seon.task.attempt/fork` attribute is written or read. No predecessor branch is consulted.

### A.2 Symbols to delete or change, every caller (VERIFIED by grep over src, test, resources)

| Symbol or attribute | Current role | Callers and mentions | Change |
|---|---|---|---|
| `seon.cluster.agent/fork-attempt!` (private) | forks the branch; **predecessor clause** reads `:seon.task.attempt/fork` on the row, finds the parent branch by `[?a :seon.task.attempt/commit ?c] [?a :seon.task/branch ?b]`, and **throws** `"The attempt's predecessor branch is gone, so its fork point cannot be read."` when the parent branch is not in `registry/branches`. | `start-attempt!` only | Delete the predecessor clause and its throw in the same commit. Keep the main fork (`registry/fork!` from `main-branch`). Under S1b it also takes the relaunch commit from the action (section B.2). |
| `seon.cluster.agent/seed-attempt!` (private) | writes the agent and row onto the branch when the branch's world lacks them | `start-attempt!` only | Delete after S6a's fork is always from main **after** `write-attempt!` wrote the row and agent. Not deletable alone: S1b (a commit fork) needs the same seeding (risk B1). |
| `seon.task/attempt-rows-tx` (public, `src/seon/task.clj`) | copies the row onto a branch; reads `:seon.task.attempt/fork` in its pull | `seon.cluster.agent/seed-attempt!`, `seon.task/assign!` (around the `own` binding) | Remove `:seon.task.attempt/fork` from its pull. Keep the `assign!` use (root's manual path copies rows onto a branch it did not fork). |
| `seon.cluster.agent/write-merge-base!` (private) | writes `:seon.task/merge-base` on main from `registry/base-commit-id` of the branch, skipped when the row has one | `start-attempt!` only | **Do not delete it in S6a as written (risk A1).** The merge base of a chain attempt is nil at `attempt-tx` (section 0), so this write is the only writer on the chain path. It may be reshaped to write the fork's own commit, but it must still run after the fork. |
| `:seon.task.attempt/fork` attribute (`resources/seon/schemas/seon.task.attempt.edn` line 11; `resources/seon/schemas/seon.task.edn` line 108 in `:seon.task/assignment-row`) | recorded by `write-attempt!` and read by `fork-attempt!`, `kept?`, `attempt-rows-tx`, `seon.turn/fork-commit` (no longer reads it: S4) | `src/seon/cluster/agent.clj` (`write-attempt!` 1859, `fork-attempt!` 1879/1885, `kept?` 2052), `src/seon/task.clj` (715), `test/seon/cluster/db_flow_test.clj` 849 | Delete the attribute, its schema entries and every reader in one commit. Task entries that quote it by name: `resources/seon/tasks/seon.turn.edn` (P3 intent, only mentions `:seon.task/merge-base` now), `seon.cluster.agent.edn` (the `a-join-forks...` intent, which names it for deletion). |
| `seon.cluster.agent/kept?` `forked?` and `waiting` clauses (2034) | the held-successor pin: keeps a merged branch while a successor forks it | `lifecycle-facts` (`:seon.flow.plan/kept?`) | Delete both clauses; `kept?` keeps only `(true? keep-ended-branches?)`. The `:seon.flow.plan/kept?` schema description (`resources/seon/schemas/seon.flow.plan.edn` line 50) names the successor fork; update it. |
| `seon.cluster.agent/plan-facts` basis query (`:seon.flow.plan/basis`, the `:done` ends' `:seon.task.attempt/commit`) and the `basis-by-task` / `owed` use of `(first (sort ...))` | gives each leaf a fork commit | `plan-facts`; `plan` (fork-point) | Delete the basis query. `basis-valid?` (1705) keeps its obligations check against main; drop its `commit` argument, since it only served the basis. |
| `seon.cluster.agent/plan`'s `:seon.flow.plan/fork-point` (`(or (not-empty (get basis handle)) :main)`) | attempt action's fork point; `write-attempt!` takes `(first (sort fork-point))` (octopus) | `plan`, `write-attempt!`, `seon.flow.plan.edn` line 43 | Under S6a it is always `:main`. Under S1b it is the relaunch commit or `:main` (section B.2). Delete the octopus pick with the basis. |
| `seon.turn/fork-commit` (`src/seon/turn.clj` 5386) | the commit a step is judged against: the open attempt's `:seon.task/merge-base` | `record-broken!` (5421); `seon.context` 238 | No change in S6a (already reads merge base since S4). Its nil return (no merge base) is the silent path in risk A1. |
| `seon.cluster.agent/successor-waits?` | task-intent text only | none in code | Remove its name from `seon.flow.edn`'s intent (VERIFIED: no code caller). |

### A.3 Red proofs

| Proof | Status (VERIFIED by grep of `test/`, `src/`, `resources/`) | Exact data assertion (proposal) |
|---|---|---|
| `seon.cluster.db-flow-test/a-join-forks-main-after-both-needs-merge` | **absent**; named in `resources/seon/tasks/seon.cluster.agent.edn` (`a-join-forks-main-after-both-needs-merge`) and in `kept?`'s docstring | Chain world with needs `a` and `b` both merged on main (after `end-on-branch!` `:done` and the merge). The successor `c`'s row has no `:seon.task.attempt/fork`, `(db/pull ... [:seon.task.attempt/fork])` is `{}`; its branch's `(db/q '[:find ?a . :where [?a :seon.agent/id "..."]])` holds; `:seon.task/merge-base` on its row equals the commit `(registry/base-commit-id main "attempt-…")` answers after the fork, and that commit is main's commit at fork. |
| `seon.cluster.db-flow-test/a-prove-steps-red-product-merges-into-main` | **absent**; named only in `seon.cluster.agent.edn` | Its red deftest and stub are on main after the merge; the Implement successor's fork db holds them (`(db/q '[:find ?s . :where [?t :seon.task/sym ...]])` non-nil). |
| **converted, not new:** `seon.cluster.db-flow-test/a-successor-forks-its-predecessors-end-commit-recorded-on-its-attempt` (exists, db_flow_test ~809; tag `seon.cluster.agent/two-plan-passes-start-one-attempt`) | exists; **red after S6a** | Line ~849 asserts `(= end-commit (:seon.task.attempt/fork ...))`; it must flip to `(nil? (:seon.task.attempt/fork ...))`. Its marker assertion (`tq-fork-marker` on `branch-b`) flips too: the marker lives on A's branch, so B's branch holds it only after A merged. Convert the test to the main fork in the same commit as the attribute's deletion. |
| `seon.cluster.db-flow-test/a-successor-whose-proof-its-predecessor-wrote-starts` (1002) and its second deftest (1068) | exist; tagged the same task | Both fork a successor from its predecessor's end commit (proof on the predecessor's branch). Under S6a the successor waits for the merge: its start moves to the merge pass. Expect these to change, not to stay green. |
| `seon.turn-test/a-branch-judges-its-step-at-its-merge-base` (`test/seon/turn_test.clj` 2124) | exists, green (S4) | Covers the **assign!** path only (the branch exists before its row). It does not cover the chain path. A chain-path proof is required (risk A1). |

### A.4 Files, with lane collisions (`bin/lane status task-writeback-impl`, VERIFIED)

| File | Claimed by task-writeback-impl | Slice A touches |
|---|---|---|
| `src/seon/cluster/agent.clj` | **yes** | `fork-attempt!`, `seed-attempt!`, `write-merge-base!`, `write-attempt!`, `kept?`, `plan-facts`, `plan` |
| `src/seon/task.clj` | **yes** | `attempt-rows-tx` (715) |
| `src/seon/turn.clj` | no | none (`fork-commit` already reads merge base) |
| `test/seon/cluster/db_flow_test.clj` | **yes** | 809-850 fork test; 33-150 `plan` fixtures using `:seon.flow.plan/fork-point` and `:seon.flow.plan/basis`; 1002, 1068 successor tests |
| `test/seon/turn_test.clj` | no | none |
| `resources/seon/schemas/seon.task.edn` | **yes** | line 108 |
| `resources/seon/schemas/seon.task.attempt.edn` | no | line 11 |
| `resources/seon/schemas/seon.flow.plan.edn` | no | `fork-point` (43), `basis` (21), `kept?` (50) |
| `resources/seon/tasks/seon.cluster.agent.edn` | no | `a-join...` entry and E6 entry |
| `resources/seon/tasks/seon.turn.edn`, `resources/seon/tasks/seon.task.edn` | seon.task.edn **yes** | intents only |

The lane holds `src/seon/cluster/agent.clj` and `src/seon/task.clj` with uncommitted edits (adoption and merge write-back, not attempt code: `git diff` shows no hunk in `fork-attempt!`, `write-attempt!`, `attempt-tx`, `ready`). Slice A is blocked by the claim, not by a content conflict. Root holds it or files a patch.

### A.5 Smallest slice order

1. **A0 (test first, no source):** convert `a-successor-forks-its-predecessors-end-commit-recorded-on-its-attempt` and add `a-join-forks-main-after-both-needs-merge` as red. Red before green.
2. **A1 (plan side, one commit):** `plan-facts` drops the basis query; `plan`'s `fork-point` is `:main`; `write-attempt!` drops the fork write. Delete the octopus pick. Proof: the fixtures at db_flow_test 33-150 updated; A0 still red (the fork is still the predecessor clause).
3. **A2 (fork side, one commit):** delete `fork-attempt!`'s predecessor clause and its throw; `kept?`'s `forked?` and `waiting` clauses; `:seon.task.attempt/fork` attribute and schema members; `attempt-rows-tx` pull entry. A0 goes green.
4. **A3:** delete `seed-attempt!` and its call (valid only after A2: the fork is from main after the row). Keep `assign!`'s use of `attempt-rows-tx`.
5. **A4:** `write-merge-base!` is reshaped, not deleted: it writes the fork's commit after `registry/fork!` and no longer reads its own skip (the row has none on the chain path). Chain-path proof: a judged red at its merge base during a run (risk A1). Cost: fork under 20 ms (prebrief-m6-m8-m10 bound), measured on default before and after.

### A.6 Three risks

- **A1: `write-merge-base!` is not redundant on the chain path.** `apply-plan!` runs `:attempt` (`write-attempt!`, which calls `task/attempt-tx`) before `:start-flow` (`start-attempt!`, which forks). At `attempt-tx`, `registry/base-commit-id` for the new `attempt-<key>` name answers nil (214c61330), so the row has no `:seon.task/merge-base` until `write-merge-base!` runs after the fork. Deleting it leaves `seon.turn/fork-commit` returning nil during the run, and `record-broken!` then judges every red as `(zipmap reds (repeat true))`, that is, "already red at the fork point": a silent wrong verdict, not an error. The S4 proof only covers `assign!`, where the branch exists first. **Mitigation:** the chain-path proof in A.3 must fail on HEAD-with-write-merge-base-deleted and pass with it kept. (VERIFIED by reading; not run.)
- **A2: the predecessor clause, the `kept?` pin and the attribute must go in one commit.** Deleting the attribute alone makes `kept?`'s `forked?` read nothing (`(some (complement forked?) waiting)` is then true for every waiting end), so `kept?` returns the wrong answer and a merged branch a successor still needs is deleted. Deleting only the clause leaves a writer of an attribute nobody reads, and a throw that no path reaches. Keep A2 as one commit.
- **A3: a successor forks main after its need's merge, but the validity read happens on an earlier snapshot.** `plan-facts` judges `basis-valid?` on the snapshot it read; `start-attempt!` forks main's head at a later time, so the fork is never older than the judgement (main only moves forward). The reverse (a fork older than its judgement) cannot occur, but a test that forks from a copied database value can hide it. Low risk; the proof should fork the live main, not a captured value.

## 2. Slice B, M12 S1 + S1b + S2: relaunch by root's fact

### B.1 S1: `seon.task/ready` re-admits a leaf whose terminal end has a later relaunch fact

**Current code (VERIFIED):** `src/seon/task.clj` `ready` (around 920). Its `eligible` query uses `(not-join [?t] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end ?e] [(contains? #{:given-back :capped :stalled :cancelled :error} ?e)])`. A leaf with any such attempt is excluded forever. Nothing reads `:seon.task.attempt/relaunch` in `src/`, `my/`, `script/` (VERIFIED by grep). `:seon.task.attempt/relaunch` is in the schema with `:seon.wake/starts true` (`resources/seon/schemas/seon.task.attempt.edn` line 5). `ready`'s docstring says a terminal `:error` end is terminal too.

**Proposed rule:** the not-join inner condition takes the end's transaction and asks for a later relaunch on the same attempt:

```
(not-join [?t] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end ?e ?etx]
  [(contains? #{:given-back :capped :stalled :cancelled :error} ?e)]
  (not-join [?a ?etx] [?a :seon.task.attempt/relaunch _ ?rtx] [(> ?rtx ?etx)]))
```

Precedent in the same file: `leaf-done` compares `?tx` values with `(> ?tx2 ?tx)` in a nested `not-join`. (VERIFIED, `ready-rules`.) Nested `not-join` inside a `not-join` is UNVERIFIED on Datahike; confirm on a JVM before writing.

**Red proof:** `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact` (**absent**; the name is in `resources/seon/tasks/seon.cluster.agent.edn`, and chain experiment 7 P1 calls it "existing", which is wrong: VERIFIED by grep). Build it from the existing chain-world fixture of `test/seon/cluster/db_flow_test.clj` ~636 (`a-capped-attempt-whose-branch-changed-no-program-gets-a-problem-task-without-a-merge-offer`, which uses `end-on-branch!`, `pass!`, `attempts`). Exact assertions:

1. after `(end-on-branch! handle connection "e" :capped)` and `(pass! handle routing)`: `(task/ready (db/db connection))` does not contain `(chain-handle "e")`; `(attempts connection "e")` has one row; the problem task `attempt-ended-<key>` exists.
2. `(test-support/transacted! connection [[:db/add [:seon.task/assignment key] :seon.task.attempt/relaunch "The proof asks again."]])`, then `(task/ready (db/db connection))` contains `(chain-handle "e")`.
3. `(pass! handle routing)`: `(attempts connection "e")` has two rows; the second's key is `(id/id [handle 2])`, not the first's key.

Also add the P2 proof to S1 (B.3): the second row's branch name differs from the first's.

**Callers of `ready`:** `seon.cluster.agent/plan-facts` (1726, the `ready` read that gives `leaves`); `seon.task/done?`'s and `resolution`'s `ready-rules` reads are unaffected. Tests: `test/seon/task_test.clj` lines ~197-229 (`the-ready-leaves-are-those-whose-needs-are-done`, `an-ingested-task-is-held-and-not-ready`), `ready-answers-in-under-10-ms-for-fifty-leaves` (cost gate: the rule's new `not-join` runs per leaf and must stay under 10 ms for fifty leaves; measure before and after). Task text in `resources/seon/tasks/seon.task.edn` (S2 intent) quotes the rule.

**Files:** `src/seon/task.clj` (**claimed by task-writeback-impl**), `test/seon/cluster/db_flow_test.clj` (**claimed**), `test/seon/task_test.clj` (**claimed**), `resources/seon/tasks/seon.cluster.agent.edn` (free; its retirement is in S1's commit).

**Must already be merged:** none for the rule itself (the `:error` end and `ready`'s terminal set are at HEAD).

### B.2 S1b: a relaunch with `:seon.task.attempt/relaunch-fork` forks that commit

**Current code (VERIFIED):** `:seon.task.attempt/relaunch-fork` is in the schema (`seon.task.attempt.edn` line 6: "the commit root explicitly chooses for the next attempt instead of this attempt's fork point"). No reader in `src/`. `seon.cluster.agent/fork-attempt!` reads the **row's** `:seon.task.attempt/fork` and the predecessor's branch, not the relaunch. `registry/branch!` accepts a commit UUID as `::from` (`src/seon/cluster/registry.clj` 211-217, VERIFIED): a commit fork is supported. `seon.db/commit-database` (`src/seon/db.clj` 397) answers nil when the store lacks the commit (its schema says `[:maybe ...]`; the nil path is UNVERIFIED on a JVM).

**Proposed:** `plan-facts` carries, per startable leaf, the relaunch commit when the terminal attempt named one (a fact read on main: the leaf's latest `:seon.task.attempt/relaunch-fork` on an attempt with a relaunch after its end; key name `:seon.flow.plan/relaunch`, new). `plan`'s `launches` puts it in the `:seon.flow.plan/fork-point` (a single commit instead of `:main`). `fork-attempt!` forks `(db/commit-database main commit)` with `:seon.cluster.registry/from commit`; a nil commit database refuses loudly by name, never falls back to main.

**Red proof (absent):** `seon.cluster.db-flow-test/a-relaunch-naming-a-commit-forks-that-commit`: its new branch's commit at fork equals `:seon.task.attempt/relaunch-fork`. Build from the same capped fixture as B.1; the relaunch names the end commit `(:seon.task.attempt/commit ...)` of the capped attempt (kept by `:close`, see B.4), so the proof needs no synthetic commit.

**Files:** `src/seon/cluster/agent.clj` (**claimed**), `test/seon/cluster/db_flow_test.clj` (**claimed**), `resources/seon/schemas/seon.flow.plan.edn` (free; add `:seon.flow.plan/relaunch`).

**Must already be merged:** A2 (the fork reads the action's `fork-point`, not the row). Otherwise `fork-attempt!` reads two sources (prebrief-item2 says the same).

### B.3 Chain-7 P2: a relaunch gets a new assignment id and branch (part of S1's proof)

**Current code (VERIFIED):** the number is `(inc ended)` in two places: `seon.task/next-attempt` (private, `src/seon/task.clj` 671: `(inc (or ended 0))` with `ended` = count of attempts with `:seon.task.attempt/end`) and `seon.cluster.agent/plan`'s `launches` (`number (inc (get ended handle 0))`, where `ended` comes from `plan-facts`' `count`). `seon.task/attempt-tx` and `assign!` use `next-attempt`; `plan` duplicates it. The branch is `(keyword (str "attempt-" key))` with `key (id/id [handle number])`. A removed ended attempt therefore re-mints the old number, id and branch name, and `registry/fork!` answers the existing branch without forking (`(when-not (contains? branches branch) ...)`), so the old branch and its stale end come back (chain 7 P2, P4).

**Two fixes, one decision for Opus:**
- (a) **max plus one** over the task's attempt rows' `:seon.task.attempt/number`: one function, both callers use its result. It still re-mints a number when the last row is removed.
- (b) **a monotone counter on the task**, a new attribute `:seon.task/attempts-made` written in `attempt-tx`'s transaction. Removing rows never lowers it. This is the design that makes reuse impossible; it adds one attribute and one writer.
Recommendation: (b), with `plan` reading its value through `plan-facts` rather than computing its own. Schema doc of `:seon.task.attempt/number` (`seon.task.attempt.edn` line 9) says "one more than the task's ended attempts": it must change in the same commit.

**Red proof:** B.1's assertion 3 extended: the second attempt's `:seon.task/branch` differs from the first's and from every branch in `registry/branches` before the relaunch; and a hand removal of the first row (a test-side retraction) does not make the next relaunch reuse its key. (The second half is the chain-7 failure; it needs the row removal in the test.)

### B.4 S2: `my.root/relaunch!` writes the relaunch fact only

**Current code (VERIFIED):** `src/my/root.clj` defns are `instructions`, `merge-requests`, `merge!`, `close!`, `ended` (plus private `offered`, `with-branch-end`, `open-assignment`). No `relaunch!`. Its request schemas live in `resources/seon/schemas/my.root.edn` (`:merge-request-request` and `:close-request` at lines 11-15 are the pattern: `[:my.program/context ...]`, `[:seon.task/assignment ...]`, `[:seon.task/reason ...]`). A new `:my.root/relaunch-request` schema is needed; no existing name fits. `my.root/close!` checks `open-assignment` (branch present, no outcome) and calls `task/close!`.

**Proposed:** `my.root/relaunch!` takes `{:my.program/context … :seon.task/assignment key :seon.task.attempt/relaunch reason :seon.task.attempt/relaunch-fork commit?}`; it refuses unless the assignment has a terminal end **on main** (`:seon.task.attempt/end` in `:given-back :capped :stalled :cancelled :error`), then writes `:seon.task.attempt/relaunch` (and the optional `-fork`) in one transaction, and answers the assignment key. Nothing else: no task created, no end rewritten. Its `laws` text and `instructions` name it.

**Red proof (absent; name is a proposal):** `my.root-test/relaunch-writes-the-root-fact-and-nothing-else`, in `test/my/root_test.clj` (exists, free). Assert: after `(my.root/relaunch! {... :seon.task.attempt/relaunch "Try again."})` on a capped row, the row's `:seon.task.attempt/relaunch` is "Try again.", `:seon.task.attempt/end` is still `:capped`, and `(count (db/q '[:find ?t :where [?t :seon.task/id _]]))` is unchanged. Refusal case: an assignment with `:done` end returns a refusal with `:seon.error/member :seon.task.attempt/end`.

**Files:** `src/my/root.clj` (free), `resources/seon/schemas/my.root.edn` (free), `test/my/root_test.clj` (free). No claim collision. S2 can land first (section 4).

### B.5 Slice order for B

1. **S2** (`my.root/relaunch!`, schema, test): free files, no reader yet, so no behavior change. The fact has `:seon.wake/starts true`, so writing it wakes db<->flow and nothing reads it: harmless.
2. **S1 + B.3 (a)-or-(b)** after the lane releases `src/seon/task.clj`: the not-join rule and the numbering owner. The proof is B.1 plus B.3.
3. **S6a (A2 and A3) before S1b**: the fork reads the action.
4. **S1b** (plan fact, `fork-attempt!` commit fork, seeding per B1).

### B.6 Three risks

- **B1: a commit fork needs the row and agent seeded on the branch.** S6a deletes `seed-attempt!` because the fork is from main after `write-attempt!`. A relaunch forks an **older** commit that predates the new row, so the branch's world lacks the assignment and the agent reads none. S1b must bring `seed-attempt!` (or `attempt-rows-tx`) back for commit forks, or the relaunched agent stalls with no assignment. The S6a commit must not delete `seed-attempt!` unless S1b's seeding is in the same commit or S6a keeps it for the commit case. **Proof:** a relaunch naming an old commit, read by the new agent's first pass: its `:seon.task/assignment` resolves on its branch.
- **B2: the relaunch commit must survive retention.** A capped branch is kept (the `:close` path copies the end and does not delete the branch: VERIFIED in `seon.cluster.agent/close!` and `ended-problem!`), but the merge path deletes branches (`kept?`, M13). A named commit of a deleted branch is reclaimed only by GC (`:datahike.gc/roots`, M17, pending). A relaunch naming a collected commit must refuse by name (the nil `commit-database` path), and that refusal is a root-visible error, not a fallback. UNVERIFIED: whether `d/commit-as-db` on a missing commit returns nil or throws.
- **B3: a same-transaction relaunch is silently not ready.** `ready` compares `?rtx > ?etx`. A relaunch written in the same transaction as the end (a test helper or a hand act) never satisfies it, so the leaf stays excluded with no problem task. `my.root/relaunch!` refusing an end not yet on main (B.4) prevents the root path; the rule's own test should assert the same-transaction case stays not ready, so the next author sees the reason.

## 3. Collisions summary (`bin/lane status task-writeback-impl`, VERIFIED)

task-writeback-impl claims 15 paths: `src/seon/cluster/source.clj`, `src/seon/merge.clj`, `src/seon/cluster/agent.clj`, `test/seon/cluster/merge_test.clj`, `docs/seon/specs/merge.md`, `src/seon/cluster.clj`, `src/seon/task.clj`, `resources/seon/tasks/seon.merge.edn`, `resources/seon/tasks/seon.task.edn`, `resources/seon/schemas/seon.task.edn`, `AGENTS.md`, `test/seon/task_test.clj`, `test/seon/task_ingestion_test.clj`, `test/seon/context_test.clj`, `test/seon/cluster/db_flow_test.clj`.

Blocked for both slices until it releases or patches: `src/seon/cluster/agent.clj`, `src/seon/task.clj`, `test/seon/cluster/db_flow_test.clj`, `test/seon/task_test.clj`, `resources/seon/schemas/seon.task.edn`.

Free: `src/my/root.clj`, `resources/seon/schemas/my.root.edn`, `test/my/root_test.clj`, `resources/seon/schemas/seon.task.attempt.edn`, `resources/seon/schemas/seon.flow.plan.edn`, `src/seon/turn.clj`, `test/seon/turn_test.clj`, `resources/seon/tasks/seon.cluster.agent.edn`, `resources/seon/tasks/seon.turn.edn`.

No file in the lane's diff is inside an attempt-owning function: the uncommitted hunks are merge write-back and adoption (`git diff` at HEAD 8d14faeee). Collision is by file, not by hunk.

## 4. Recommended slice order

1. **S2** now: free files, no behavior.
2. Root releases or patches `src/seon/task.clj`, `src/seon/cluster/agent.clj`, `test/seon/cluster/db_flow_test.clj`, `test/seon/task_test.clj`.
3. **A0** red (convert the fork test, add `a-join-forks...`).
4. **A1, A2, A3** (plan side; fork side; seeding). A4 (write-merge-base! reshape with the chain-path proof, risk A1) lands with A2 or before it: never a commit with `write-merge-base!` deleted and the chain-path proof absent.
5. **B-numbering** (B.3 decision (a) or (b)) with S1's proof.
6. **S1** (rule) and its proof.
7. **S1b** (plan fact, commit fork, seeding per B1).

Every commit: HEAD boots; each deletion lands with its last caller converted (`:seon.task.attempt/fork`, `forked?`, `seed-attempt!`); `bin/lane commit` with the named tests.

## 5. Three biggest risks overall

1. **A1 (silent wrong verdict):** `write-merge-base!` looks redundant (S4's message says it stays until S6a) but is the only merge-base writer on the chain path. Deleting it makes `seon.turn/fork-commit` nil and every red "already red", with no error. Keep it; prove the chain path.
2. **B1 (stalled relaunch):** S6a's `seed-attempt!` deletion is correct for main forks and wrong for the commit forks S1b adds.
3. **P2 (id reuse):** count-based numbering in two copies (`task/next-attempt` and `plan`); deleting an attempt row re-mints an old branch that `fork!` answers without forking. The monotone counter removes it at the owner; max plus one leaves a hole.

## 6. Unverified and wrong claims found

- Chain experiment 7 P1 and chain 2 G6 name `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact` and `seon.cluster.agent/a-capped-attempt-has-no-relaunch` as "existing". Neither deftest exists (VERIFIED by grep); the second is a task name in `seon.cluster.agent.edn`.
- S4's commit message says `write-merge-base!` is replaced; the chain path shows it is not (A1, VERIFIED by reading `apply-plan!` order and `attempt-tx`'s `base`).
- The S4 proof `a-branch-judges-its-step-at-its-merge-base` covers `assign!` only.
- The K3 claim "the capped branch is kept forever" holds (VERIFIED: `:close` copies the end and deletes nothing), but only because `:close` is not `task/close!`; the name collision between `seon.cluster.agent/close!` and `seon.task/close!` is itself a hazard for the next author.
- No JVM was run for this brief. Every "absent", "exists" and "reads" is a grep or a read of the code at HEAD; every behavior claim (fork timing, `commit-database` on a missing commit, nested `not-join`) is UNVERIFIED until a probe runs on a JVM in `tmp/` or a lane's branch.
