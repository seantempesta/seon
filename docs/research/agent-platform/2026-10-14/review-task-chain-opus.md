---
title: "Review of TASK-CHAIN (draft 2026-10-14): reviewer opus"
type: review
status: complete; read-only review, no source edited
basis: "HEAD fdc34a9ba plus the working-tree TASK-CHAIN.md; read-only; one JVM-mode probe on default (no writes)"
---

# Review of TASK-CHAIN draft 2026-10-14 (opus)

Read: CLAUDE.md, the spec end to end, the five 2026-10-14 evidence files, the six 2026-10-13 context maps, and the
owners in `src/seon/{task,turn,context,merge,cluster/agent,cluster/source,db}.clj`, `src/my/{root,task}.clj`,
`resources/seon/schemas/seon.task*.edn`. "(inferred)" marks a reading of code I did not run.

**Verdict.** The data model is right and small. The flow (§2) is short because it does not yet say how root's
lifecycle works when the tasks and attempts live on a branch. Today every derivation root runs reads main only. Eight
findings below would stop a live chain at S3. Each has a fix of a few lines in the spec. S1 and S3 are each too big
for one lane.

## 1. Gaps (most important first)

### G1. Root loses sight of the chain between tasks: the pointer resolves only through an open connection

- Scenario: task A ends. `seon.cluster.agent/assignment-actions` emits `:stop`, and `apply-lifecycle!` `:stop` calls
  `release-branch!`, which releases every owning connection on the branch. The next pass then resolves the pointer
  with `seon.db/branch-connection` (spec §2), which is `connections/active-connection`: nil when no connection is open.
  The same happens after any restart.
- Probe (JVM, default, read-only), on a branch nobody holds (`agent-4a075d283b90`):
  `(seon.db/branch-connection db b)` gives nil, and `(seon.db/branch-database db b)` gives a value (13 ms in total).
- Failure: B is never seen as `:running`, the chain stalls silently, and the pointer reads like a retired chain
  (`cross-branch-references.md` §3: "retired: resolves to nil").
- Fix: root reads a chain branch with `seon.db/branch-database`, a store read of the head commit that needs no
  connection. That is what `seon.cluster.agent/branch-end` already does. `datahike.reference/resolve-reference` needs a
  live connection, so the spec should not name it as the read path (see O1).

### G2. Nothing wakes root after creation, after root's own advance, or after a hand `:running`

- Scenario: `chain!` writes the tasks on a new branch that has no listen route (routes are registered only by
  `seon.cluster.agent/start-branch-flow!` and dropped on `:stop`). It then writes the pointer on main. `:dh.ref/*` is
  not a `:seon.wake/starts` attribute, so the pointer wakes no pass. The same holds for root's advance (it writes
  `:running` on B, on a branch whose route is gone) and for §2.7's "set the task `:running` again".
- Failure: these writes start nothing until some unrelated wake arrives. This is the same class as O10 and gap G3 in
  reactive-scheduling.
- Fix: (a) declare the main-side chain attribute `:seon.wake/starts true`. (b) State that the advance happens inside the
  lifecycle phase of `seon.cluster.agent/run-pass!`, and that the plan phase of the same pass reads the branch after
  it. `run-pass!` already re-derives after `:stop` and `:merge` for this reason, so `:advance` joins that list.
  (c) Root's hand `:running` goes through one root function, `my.root/run!`, that writes the branch and the main-side
  chain fact in one call. The main-side write is the wake.

### G3. The agent never opens a model turn: execution is read on main

- `seon.turn/attempt-running?` reads `(task/execution (or (task/main-head database) database) handle)`. That is commit
  cde025f68's ruling: "Root decides execution on main; the branch's copy is never read." In the new design the task
  does not exist on main, so the read is nil, `next-agent-work` never derives `:prove` or `:open`, and the agent idles
  forever. The spec does not mention this.
- Fix (S3): `attempt-running?` reads the chain task's execution on the agent's own branch. In the same commit,
  rewrite the cde025f68 ruling where it lives: root is the only writer of a chain task's `:seon.task/execution`, on
  the chain branch. `my.task/refine!`'s request schema already admits no execution.

### G4. The done judge named in §2.4 refuses every intermediate task

- `seon.cluster.source/results-verdict` refuses: any red member except designed reds among `:seon.task/product`
  (deleted by this spec); every added or replaced function not reached by a green member or a designed red
  (`uncovered`); contract gaps; and task names not admitted on main (`seon.task/candidate-dangling`).
- Design writes contracted stubs, which no green test reaches, so they are `uncovered`. Prove's tests are red, and
  with product gone none of them counts as designed. Both tasks therefore refuse forever. That is a Run-21-class
  live-lock.
- The rule the spec states in prose, "proofs green; nothing green on main red here", is not `results-verdict`'s rule.
- Fix: write the done rule as its own few-line function over the same judged input, in `seon.cluster.source` beside
  `results-verdict`:

  ```
  seon.cluster.source/done-refusals [candidate main judged] -> :seon.source/refusals
    refuses: a proof not a member; a proof not green; a member red on candidate that main holds green at its reach
  ```

  `results-verdict` stays the judge of the final merge only, with its `product`/`designed` clause deleted. At the end
  every branch-defined test must be green, which §2.6 already says.
- `seon.context/step-status` and `seon.turn/task-end` call `done-refusals`. Root's advance calls it once, over the
  value at the attempt's `:seon.task.attempt/commit` (the end commit), not the moving head.

### G5. The final merge assumes one assignment that lives on main

`seon.merge/merge-onto` breaks on a chain branch in four places:
- `assignment-row` refuses "Assignment … is not on main." when the row lives on the branch.
- `assigned-task` resolves the task symbol on main, where it is nil.
- `prepare-merge!` gets `tested-task-basis`, whose `obligations` run over main and base, which do not hold the task.
- `merge-tx` closes only that one assignment `a` with `:merged`.

The squash then carries every earlier attempt row of the chain onto main with `:end :done` and no outcome. These rows
show up as open merge requests in `my.root/merge-requests` (`(not [?a :seon.task/outcome])`). `lifecycle-facts` and
`assignment-actions` also see them as ended `:done` and emit `:merge` against a retired branch, which gives
`failed-pass!` problems (inferred).

Fix:
1. Root's advance writes `:seon.task/outcome :done` on the finished attempt row, the merge request it closes. The
   squash then lands those rows closed.
2. `merge!` takes the last task's attempt key and reads its row, the end and the task's proofs on the branch head.
   `tested-task-basis` reads the proofs on the candidate only, since main has no prior basis for a chain-born task.
3. `task-basis-held-tx` compares on the candidate.
4. Say which tasks the merge writes back. `seon.cluster.source` writes merged tasks into
   `resources/seon/tasks/<ns>.edn` (its docstring covers "A namespace with no task file yet"). That means three
   permanent entries per chain unless chain tasks carry `:seon.task/created-by`. Decide (recommend `created-by` root:
   runtime tasks, kept in the database and its history, never in files).

### G6. Moving attempt rows onto the branch means reading root's lifecycle per branch, and the spec never says so (Q3)

Readers of attempts on main today:
- `seon.cluster.agent/lifecycle-facts`, `plan-facts` (open-attempt query, `attempts-made`), `plan`,
  `write-attempt!`, `seed-attempt!`, `write-merge-base!`, `close!`, `failed-pass!` (writes `:error` by lookup ref on
  main), `allowance-left`;
- `seon.task/ready` (`leaf-done`, blocking ends);
- `seon.merge/merge-onto` (G5);
- `my.root/merge-requests` and `open-assignment`; `my.root/ended` through `seon.context/ended` (reads the task on
  main, inferred).

Smallest fix: every one of these already takes a database value, so run them over each chain branch value. In
`run-pass!`: `(doseq [b live-chain-branches] (lifecycle-facts (db/branch-database main b) …))`, and plan the same way,
writing each action through that branch's connection (open one with `seon.cluster.registry/active-branch-connection`,
else `store/open-branch!`).

Changes in `seon.cluster.agent/assignment-actions`:
- `:done` with a `next` → `[:stop :advance]`;
- `:done` with no `next` → `[:stop :merge …]`;
- capped, given-back, sent-back, stalled or error → `[:stop :problem]` with no `:close`, so the branch is kept.

Delete `seed-attempt!`: the row is written on the branch, so there is nothing to seed. `max-running` counts open
attempts across all chain branches. Pass cost then grows with live chains, not tasks. A fresh head's schema
derivation costs about 40 ms (docstring of `seon.db/undecoded-datoms`), so cache by head commit id.

### G7. Orphan branches never retire (Q4)

- §8.4 assumes an orphan branch is "retired like any idle branch". Nothing retires a branch that main holds no fact
  about: `seon.cluster.agent/taskless-agents` lists only agents whose row is on main. This is hypothesis H4 of O10 in
  context-root-flow.
- Fix: write the main-side chain fact first, naming the branch and the first task's handle as values. Then fork and
  transact. Make `chain!` idempotent (fork only when absent, as `fork-attempt!` does; tasks upsert by `:seon.task/id`).
  A crash leaves a fact naming a missing branch, which root shows as a typed unknown, and repeating the call finishes
  the chain.
- An eid selector cannot be written first, because the eid exists only after the branch transaction. That is one more
  reason for O1.

### G8. Template default proofs have no task when they run on main

- With tags deleted, `seon.test/task` falls back to nothing when no agent task is bound. Today it falls back to
  `seon.test.runner/single-tags`. `seon.step-test/design-handoff` then runs unbound whenever a change reaches it: in the
  bulk tier, and on the merge branch of `prepare-merge!`, where no agent is bound. It is red there by construction
  ("red on an empty task", S4).
- Failure: a permanent red on main. A merge whose change reaches `seon.ref` runs it red on the candidate (inferred).
- Fix (S4 text): the step deftest is a table of hand-built red and green task values. When `(seon.test/task)` is
  bound, it also asserts its handoff predicate on that task (a conditional the owner ruled fine). Each handoff
  predicate is one contracted function in `seon.step` (`design-handoff?`, `prove-handoff?`) over a task value plus the
  branch value. The deftest calls it, so the same rule serves the table and the proof.

### G9. Smaller gaps

- **Ends:** §2.7 omits `:stalled` and `:cancelled` (`:seon.task.attempt/end` enum). Say: `:cancelled` ends the chain
  with nothing for root; `:stalled` behaves like `:capped`.
- **`next` as a set:** this admits fan-out and fan-in. §2.5 then starts a fan-in successor on its first finished
  predecessor, and §2.6 merges when the first leaf finishes. Fix: `:seon.task/next` cardinality one (the evidence
  probe used one). Chains are linear.
- **Two chains, one name:** identity is `[ns name]`, so two chains naming the same task collide at merge.
  `seon.merge/squash-merge` `collision-remap` upserts onto the first chain's task. Fix: a chain's task names are
  derived from the first task's name, `"<template>-<first name>"` (as the evidence probe did). Two `chain!` calls on
  one first name are the same chain, which the idempotent `chain!` of G7 gives.
- **A chain on a filed task's own name** (Run 25's O8): on the forked branch the template merge upserts onto the filed
  task by identity. That is fine, and better than O8's refusal, but say that main's copy stays `:held` until the merge.
- **Advance as `(merge finished next)`, next's own members winning (step 5, revised during this review).**
  Three failures:
  1. A rerun freezes the plan. Root sends work back and sets Design `:running` again, and Design improves the plan.
     Prove already holds a plan from the first advance, so "its own wins" and the revised plan never flows. This is
     the send-back path §2.7 offers.
  2. If `chain!` writes `plan` on every task ("each task is `(merge defaults template overrides)`" with the caller's
     plan in the overrides), no plan ever flows.
  3. Every member a template leaves unset flows by accident: `:seon.task/refusal`, `created-by`, `cron`/`zone`/
     `function` and `references`. References must be re-derived from the result, never copied.

  Fix, smaller and exact: the advance writes the finished task's `:seon.task/plan` onto each `next` and
  `:execution :running`, and nothing else. `chain!` writes `plan` on the first task only. If more members must flow
  later, name them in one set beside `declaration-members`; do not merge everything.
- **Prove writes the next task's proofs:** the spec names no writer. `my.task/refine!` writes `:seon.task/product` on
  the subject today. Fix: `refine!` takes `:seon.task/proofs` and writes it on the held task's `next`; delete the
  product and goal-owner branches.
- **Derived references:** §1 says "written by the writer", but the checked writer derives no references. The
  derivation is `seon.task/with-references` inside the tasks-tx path. `chain!` through a plain `seon.db/transact!`
  must derive them itself, by calling `with-references`, and so must `refine!` writing `plan`.
- **"All green at the start ends the task with no model call"** contradicts CLAUDE.md "a task already green proves
  nothing" (`assign!` refuses it). For a first task, send it to root with nothing merged rather than "done".
- **The tag sweep (347 test, 42 src):** measured 344 task symbols in test `#{…}` sets naming 247 distinct tasks, 39 of
  them fixture names inside test strings (`tq.*`, `rc.*`, `b/x`), and 33 symbols in src.
  - The src tags are subject edges, not proofs. The `:seon.task/tasks` schema says "anywhere else the tasks it is
    subject to". They cannot "become proofs": convert them to backticked `ns/task-name` references in the var's
    docstring (CLAUDE.md "Known issues reference their task"), or drop them.
  - The script must use rewrite-clj, not regex, and skip tags inside strings.
  - `:seon.task/needs`: 17 entries, 19 links (spec says "17 links").
- **Readers of `:seon.task/tasks` and tags that §6 does not list:**
  - `seon.cluster.agent/render-identity-html` (pulls namespace `:seon.task/tasks`);
  - `seon.fn.analyzer` (metadata capture and the refusal of a map after a name);
  - `seon.task` `task-findings`/`open-tasks` triage (lines 606-616) and `candidate-dangling` (630);
  - `seon.test.runner/single-tags`;
  - schema members in `seon.test.edn`, `seon.fn.edn`, `seon.ns.edn`; `:seon.task/annotation` and `:links`;
  - the help text in `seon.bootstrap` and the `:seon.task/assignment-view` description.
- **Laws and docs** that state tags or `intent`, which the rename and delete commits must convert in the same commit:
  CLAUDE.md "Write tasks as data" and "Documentation is data", the clojure-testing skill and the vocabulary.

## 2. Overengineering

- **O1. `datahike.reference` for the pointer.**
  - Store id: redundant, since there is one store.
  - Eid selector: forces pointer-last (G7).
  - Resolver: needs a live connection (G1).
  - Cost: a new schema file for `:dh.ref/*`.
  - Root only needs two values: the branch and the first task's handle. Use one main-side entity of two values,
    declared `:seon.wake/starts` and non-program, or reuse the `:seon.branch/*` fork-entity attributes
    (`seon.cluster.registry/branch-fork-entity`) on main.
  - If the owner wants the Datahike name, store `reference->tx-map` with a lookup selector `[:seon.task/id h]`
    (1.7 ms) and resolve it by `branch-database`. That is half of the library, which is a sign it does not fit.
- **O2. Guards in `chain!`:** "no cycle in `next`" and "ids equal `task-handle`" check `chain!`'s own construction.
  With `next` built from an ordered vector, a cycle is impossible, and `chain!` computes the id itself. Delete both.
  Move `symbol-name?` into the `:seon.task/name` schema, so the checked writer refuses a bad name for every writer and
  the separate check in `seon.task/create!` goes too.
- **O3. Two meanings of `:seon.task/outcome`.** It is an assignment attribute: how root closed the merge request. The
  spec adds `:done` on the task entity. Put `:done` on the attempt row (G5) and derive "task done" from its attempt,
  as `leaf-done` does with `:merged` today. One meaning stays.
- **O4. `next` as a set** (G9). Cardinality one.
- **O5. A general merge over declared members at advance** (G9). More machinery than the one member that must flow,
  and wrong on rerun. Copy `plan` only.
- **Kept without a reason:** `:seon.task.attempt/target` (root decides now; keep it only as the send-back reason's
  pointer, or say so); `write-merge-base!`/`:seon.task/merge-base` on main for chain attempts; `seed-attempt!` (G6).
- **Missing from §6's deletions:**
  - relaunch remnants: `allowance-left`, `:seon.flow.plan/allowance-left`, `work-left?`, `:seon.flow.plan/relaunch`
    and the fork-point relaunch clause in `plan` and `fork-attempt!`, the `:relaunch` kind in `seon.task/end-kinds`,
    and `missing? :seon.task.attempt/relaunch` in `seon.task/ready`;
  - `basis-valid?`'s `:prove` successor branch and its commit-keyed cache (gone with `obligations`, so §5's "first
    cheap fix" mostly disappears).

## 3. Wrong claims

- **§2.1** "Measured: about 4 ms for three tasks" was a `seon.db/with` (3.59 ms) plus the build (2.74 ms), with no
  commit. A real `chain!` is a fork (53-56 ms, chain-on-one-branch §1), plus one committed transaction (about 22 ms
  measured for a one-row apply), plus the pointer transaction: about 100 ms.
- **§2.2** "acquire-context! … about 4 ms" holds only when another agent still holds the branch. After `:stop`
  releases it, the next task's acquisition is the "branch nobody holds" case: 22.7 ms (sci-reuse §1). Not material.
- **§2.** "resolve-reference with seon.db/branch-connection: about 0.05 ms" is true only while a connection is open;
  otherwise it returns nil (G1, probed).
- **§5** "355 ms warm and 1.3–2.3 s cold with 834 ready tasks" comes from a hypothetical `d/with` marking all 845
  parentless tasks `:running` (reactive-scheduling §2), not the live cluster. Say "hypothetical".
- **§6** "The 347 test and 42 source `:seon/tasks` tags become each task's `:seon.task/proofs`": the source tags are
  subject edges (G9). "17 links": there are 17 entries with 19 links.
- **§1** "`references` … never authored | the writer": the checked writer does not derive them (G9).
- **§8.4** "retired like any idle branch": nothing retires a branch main holds no fact about (G7).

## 4. Implementability by Sonnet

**S1 is three lanes.** It also orders deletions before their readers: `product`, `step`, `effective` and `goal-owner`
are read by `seon.task/obligations`, `seon.context/step-observation` and `results-verdict`, which S2 converts.
- **S1a: the `intent` → `plan` rename.** One rewrite-clj script over 101 task files (856 entries), about 40 src and
  test files, the schema, CLAUDE.md and the vocabulary. Test-free except existing reds. It is a rename touching most
  files, so per CLAUDE.md it lands only in root's restart batch. Sonnet.
- **S1b: schema.** `:seon.task/next` (ref, cardinality one, `:seon.wake/starts`), the main-side chain fact (O1), the
  Malli `:default :held` on `:seon.task/execution`, decoding in `seon.task/task-delta` and `held-tx`. Red: a
  `seon.task-test` row where `(m/decode :seon.task/task {} (mt/default-value-transformer {::mt/add-optional-keys true}))`
  gives `{:seon.task/execution :held}`. Sonnet.
- **S1c: new `chain!`.** Spell out the signature and the exact assertions:
  - Signature: `(seon.task/chain! connection {:seon.task/ns ns :seon.task/name n :seon.task/plan p} [template-syms] overrides-by-template)`
    → `{:seon.store/branch b :seon.task/handles [h…]}` or an error.
  - Red-test assertions (all on data):
    1. `(registry/branches store)` contains `b`;
    2. on `(db/branch-database main b)`, pulling `{:seon.task/next …}` from the first handle gives the three syms in
       order;
    3. each task has the template's `:seon.task/instructions` and `:seon.task/proofs`, its overridden plan, and
       derived `:seon.task/references`;
    4. execution is `[:running :held :held]`;
    5. main holds no `:seon.task/id` of the three, and holds the chain fact naming `b` and the first handle;
    6. a second identical call returns the same value and transacts nothing (G7).

  Deleting old `chain!`/`chain-copy` and their tests (`step_test`, `task_test`, `db_flow_test`, `write_back_test`)
  goes in this commit. Opus.

**S2 is specified at the level of "delete the aliases".** Sonnet would guess:
1. The one task binding: name the request key that survives (`:seon.test/task`), the reader `seon.test/task`, and the
   fact that the reach digest keys only tests whose closure reads it (`seon.test.runner/reach-digests` with a task
   argument at both readers, `seon.context/current-results` included). This is the Run 25 root cause, so say it
   outright.
2. Selection: `seon.context/step-observation` adds the `:seon.test/sym` coordinates of `change` to members (Q2).
3. The new done judge (G4, `done-refusals`).
4. The sweep script's input and output (G9).

Split:
- S2a: binding and digest. Red: `a-tasks-proofs-are-its-field`, asserting that `(seon.context/current-results db #{t})`
  under the bound task returns the result the runner recorded for that task.
- S2b: done judge plus selection. Red: a table over `done-refusals` with rows "proof red", "branch-new test red"
  (accepted), "test green on main now red" (refused).
- S2c: sweep, then delete tags, `:seon.task/tasks`, `obligations`, `single-tags`, the turn wrappers. A mechanical
  Sonnet lane after S2a/S2b.

**S3 is the largest and least specified.** It needs G1, G2, G3, G5, G6 written into §2. Split:
- S3a (Opus): per-branch lifecycle in `run-pass!` (G6), the `:advance` op, and `attempt-running?` on the branch (G3).
  Red: a two-task scripted chain reaches B's first model call with zero hand acts.
- S3b (Opus): the final merge of a multi-attempt branch (G5). Red: after the merge, `my.root/merge-requests` is empty,
  main holds three attempt rows each with an outcome, and `publish!` writes the agreed task files.
- S3c (Sonnet): bail to root. Red: a `:capped` end leaves the branch in `registry/branches`, main gains one problem
  task, and nothing is `:running`.

**S4** is fine once G8's text is in: name the predicates `seon.step/design-handoff?` and `seon.step/prove-handoff?`
with their contracts `[:=> [:cat :seon.db/database-value :seon.task/task] :seon.source/refusals]`, and list the table
rows (empty task; sections present but a reference unresolved; no schema changed and none cited; all satisfied).

## 5. Answers to §8

1. **No, not with `results-verdict` as the judge** (G4). Yes, with a separate `done-refusals` (proofs green; nothing
   main holds green is red) for each task, and `results-verdict` minus its product clause for the final merge. No
   pending or product concept is needed: Prove's new tests are exempt because main does not hold them green, and
   `prove-handoff?` demands they are red by design.
2. **S2 adds it.** `seon.context/step-observation` keeps only `:seon.fn/sym` coordinates of `change`; tests defined on
   the branch that reach no changed function are not members. Add the `:seon.test/sym` coordinates. `change` is
   already measured from the attempt's merge base, which is the chain's fork commit, so "since it forked" holds
   unchanged.
3. **The readers that move are listed in G6** (`my.root/ended` reads through `seon.context/ended`, inferred). Reading
   through pointers stays cheap only if it uses `seon.db/branch-database` cached by head commit; cost is proportional
   to live chains (about 1 to 40 ms per head). Recommend moving them, with the per-branch loop of G6, rather than
   keeping rows on main: rows on main need `seed-attempt!`'s second copy and an `attempt-of` that dangles.
4. **Write the main-side fact first, with the branch and the first handle as values, and make `chain!` idempotent**
   (G7). An orphan branch is not retired by anything today.
5. **O1-O5:** a plain two-value chain fact instead of `datahike.reference`; delete the cycle and id checks; one meaning
   of `outcome`; `next` cardinality one; the advance copies `plan` only, not a general merge. Plus the relaunch remnants §6 misses.
