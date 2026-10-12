---
type: spec
status: superseded by docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md (owner answers 2026-10-12); formerly approved in part (2026-10-08): git vocabulary, one merge!, target branch, norms, merge when every test is green
revised: 2026-10-08
tags: [spec, merge, root]
---
# Merge: root reviews and merges an agent's branch

What exists: an agent opens a merge request for its branch (today `my.program/request-merge!`), root tests the merge on
a fresh merge branch off the target (today `prepare-merge!`), then merges exactly what was tested (today
`accept-merge!`); the writer checks the replaced rows' digests. Data flow: the merge request at the task home →
three-way digest comparison (merge base, ours, theirs) → one transaction onto the merge branch → test run on the merge
branch → one guarded merge commit onto the target branch. The approach stays: functions of database values, the
three-way value as the diff, and Datahike commits with merge parents. This spec names everything in git's words, folds
prepare and accept into one `merge!`, and adds review, resolution, norms and automatic merging.

Root is whoever holds a merge request: the orchestrator through MCP or a Seon model agent. It is an ordinary task done
through the ordinary REPL in root's own context. Nothing here is a second entrance, and a Seon agent must find root's
job easy. The competency suite (`docs/research/agent-platform/2026-10-08/competency/competency-spec.md` §1-2) uses
this mechanism; this spec owns it. Order and status of the slices: [the ledger](../schedule/agent-loop-ledger.md)
§3.10 (merge), §3.16 (git renames) and §3.15 (no kinds).

**Words.** Git's, where git's meaning holds (map and reasons:
`docs/research/agent-platform/2026-10-08/git-vocab/git-vocab.md`, approved 2026-10-08). **Main** is a cluster's
branch (today `:cluster-<name>`, `seon.cluster.branch/cluster-branch`, renamed `main-branch`); "the tip of main" is its
latest commit. An agent's **branch** is where it works; its **checked-out branch** is the branch its context holds
(internally custody today; `with-branch` after its rename, ledger CN-15). A **merge request** asks to merge a
**source branch** into a **target branch** (GitLab's pair; GitHub's head and base already mean other things here).
The **merge base** is the newest common ancestor. The **merge branch** is where a merge is tested and resolved, off
the target. In a conflict, **ours** is the target and **theirs** is the source, as in `git merge`; Seon never rebases, so the words never swap. An **unmerged** identity is a conflict
not yet resolved. **Behind** lists what the target changed since the merge base. A **norm** (Datahike's word) is the
data change a merge carries. No invented short labels: never "S", "candidate", "head" or "proposal".

Owner rulings this spec applies (2026-10-08): an agent may make any REPL-valid change on its branch, conflicting ones
included (redefinitions, refactors, renames, schema changes); its branch never refuses them. Integrity is judged at
root's diff and merge. During work, the task's proofs and every test reaching the change run automatically on the
agent's branch ([agent-loop.md](agent-loop.md) §1.7, §3.5); at merge they run again on the merged program. `merge!`
commits when the merge is clean and every test on the merge branch is green, and otherwise stops with the merge branch
kept. A newer green commit updates the same open merge request, as a push does. A definition's pointer to the
evaluation that made it is dropped at merge: the source file becomes the record. Our own lanes write a branch back
to files without a merge (`my.program/write-branch!`, installed) and commit by branch evidence (`bin/lane commit
--branch-evidence`); that is the lanes' path into git, not a merge of the cluster.

## 1. The merge request

**What root holds.**
- The merge request at the task home: a task carrying `:seon.merge/source-branch`, `:seon.merge/source-commit`,
  `:seon.merge/target-branch`, `:seon.merge/proofs` (each proof with its definition digest), `:seon.merge/run` and
  `:seon.merge/program-digest`, plus optional `:seon.merge/norms`. A task is a merge request because it carries
  `:seon.merge/source-branch`, validated by a specific Malli schema composed over the task schema; there is no kind
  (ledger §3.15). Today: a task has no kind (its handle is `seon.id/id` over `[ns name]`), and a done branch is merged by
  root's db<->flow from its assignment, with a separate proposal row; the rename slice folds the proposal row into the request (ledger §3.16, merge request data).
- The source branch, read through `compare-branch` (src/seon/cluster/source.clj:824).
- The merge branch, created off the target by `merge!` and named on the request as `:seon.merge/merge-branch`. It is
  kept until the request closes (today `candidate-gate!` src/seon/cluster.clj:2642 creates it and nothing keeps it).
- The target branch, fixed when the request opens: by default the main the source branch was branched from, or a
  branch the agent names. A merge always lands on the request's target branch, never on whatever root has checked
  out. Today `accept-merge!` lands on root's checked-out branch (src/my/program.clj:547), which put a dogfood merge on
  a scratch branch while the home recorded it accepted (§9 item 4).

**Forms** (each answer offers the next forms as `:seon.render/operations`):

| Form | Git | Answers | Today |
|---|---|---|---|
| `(my.program/request-merge!)` / `{:seon.merge/norms [...]}` | `git request-pull`; open a merge request | the open request, assigned to root in the same transaction (ledger RT-9); the agent waits | `request-merge!` program.clj:416 |
| `(my.program/merge-requests)` | the forge's open list | open requests at the home | `proposals` program.clj:473 |
| `(my.program/diff {:seon.merge/request m})`, or with a symbol | `git diff main...branch [-- path]` | the diff value (§2) | `diff` is the agent's own branch only (program.clj:974); `declaration-diff` program.clj:991 folds in |
| `(my.program/merge! {:seon.merge/request m})` | `git merge` | merged, or stopped with the merge branch kept (§4) | `prepare-merge!` program.clj:513 and `accept-merge!` program.clj:547 |
| `(my.program/merge! {… :seon.merge/no-commit true})` | `git merge --no-commit` | stopped before committing, to review | — |
| `(my.program/checkout! {… :seon.merge/side :theirs :seon.program/identities #{id}})` | `git checkout --theirs -- path` | the identity copied onto the merge branch (§3) | target `take!`/`drop!` |
| ordinary forms (`in-ns`, `defn`, `my.program/ns-unmap!`) | edit and `git add` | the merge branch changed (§3) | — |
| `(my.program/abort-merge! {:seon.merge/request m})` | `git merge --abort` | the merge branch deleted; the request stays open | — |
| `(my.program/close-merge-request! {… :seon.merge/reason "…"})` | close a merge request | closed; the source agent is told | `seon.task/decide!` task.clj:679, no reason, agent not told |

**Evidence** (all stored facts, consumers named):
- the merge request's data above (consumers: `merge-requests`, `merge!`, the score);
- the run on the merge branch, `:seon.test.run/id`, with every member's result (consumers: `merge!`, the score);
- the merge commit on the target, with parents the target's tip, the merge branch and the source commit (an octopus
  merge, valid in git; consumers: write-back, history);
- the review's test decisions (§4 step 3: kept, folded, dropped; consumers: the score, suite growth);
- on the request: `:seon.merge/merge-commit` present means merged, `:seon.merge/close-reason` present means closed,
  neither means open (positive data replaces today's `:seon.task/outcome` enum); a stopped merge records why it
  stopped (unmerged identities, red tests, failed integrity) as data on the request; who merged
  (`:seon.merge/merged-by`, root or the automatic merge).

**Updating a request.** The agent requests a merge when its proofs are green on its branch and no test its change
broke is red (agent-loop.md §3.5). A newer green commit on the same source branch updates the same open request's
`:seon.merge/source-commit` and proofs, as a push updates an open merge request; Datahike history answers the earlier
versions. A `merge!` in progress on an older source commit stops as superseded and keeps its merge branch, so root's
resolutions are never overwritten. The agent goes to `:wait` when it requests; its branch is deleted only after the
merge and the agent's actual exit.

## 2. The diff value

One value, from `compare-branch` (source.clj:824) and `seon.program/three-way` (src/seon/program.cljc:704): every
compared declaration family (`:seon.fn/sym`, `:seon.test/sym`, `:seon.ns/name`, `:seon.schema/key`) classified by its
merge-base, ours and theirs digests. Target shape, keyed by identity, small and digest-only:

```clojure
{:seon.merge/source-branch :agent-…  :seon.merge/merge-base #uuid "…"  :seon.merge/target-commit #uuid "…"
 :seon.merge/added     [{:id [:seon.fn/sym a/f] :theirs "d1…"}]
 :seon.merge/modified  [{:id [:seon.fn/sym a/g] :base "d0…" :theirs "d2…"}]
 :seon.merge/deleted   [{:id [:seon.fn/sym a/h] :base "d3…"}]
 :seon.merge/behind    [{:id [:seon.fn/sym a/k] :base "d4…" :ours "d5…"}]   ; the target changed it since the merge base
 :seon.merge/conflicts [{:id [:seon.fn/sym a/g] :base "d0…" :ours "d6…" :theirs "d2…"}]
 :seon.merge/dangling  [{:caller a/k :calls a/h}]                          ; on the merged value
 :seon.merge/schema    [{:id [:seon.schema/key :a/line] :refused 70 :readers [a/f]}]
 :seon.render/operations [(my.program/diff {:seon.merge/request "…"} 'a/g)
                          (my.program/merge! {:seon.merge/request "…"})]}
```

Today's three-way keys rename with it (`changed-on-branch` → `:seon.program/modified`, `retracted` → `deleted`,
`changed-on-head` → `modified-ours`; ledger §3.16, the diff value).

- **What blocks a clean merge.**
  - Same identity changed on both sides (three-way `:conflict`, installed).
  - A dangling caller: a declaration on the merged value whose `:seon.fn/calls` names a symbol with no declaration.
    Calls are symbol values, not refs (resources/seon/schemas/seon.fn.edn:49), so the writer cannot refuse it on the
    source branch; `merge!` checks it.
  - A schema the target's data violates: for each added, modified or deleted `:seon.schema/key`, the target's refused
    value count from `seon.db/registration-check` (src/seon/db.clj:4077) and the declarations reading the key. The
    check is complete, or it answers a typed unknown that stops the merge; a sample is never integrity.
- **Cost.** Proportional to the declarations changed since the merge base: one merge-base walk, digests of the changed
  identities only, one dangling query over the merged callers of deleted symbols. No whole-program work.
- **AI render.** Counts per section, then the entries above with one `diff` form each. No source text.
- **HTML render.** One row per identity: family, symbol, side, a status mark (added, modified, deleted, behind,
  conflict, dangling, schema refusal), and `checkout!` operations. Source pairs open on demand through `diff` with the
  symbol.

## 3. Resolving on the merge branch

- **The merge branch always exists after `merge!` starts.** It holds the target's program plus every non-conflicting
  change. Each conflicted identity keeps ours (the target's row) and is listed in `:seon.merge/unmerged`. Git writes
  conflict markers into files; Seon writes none, so never look for `<<<<<<<` in source.
- **Checkout** `(my.program/checkout! {:seon.merge/request m :seon.merge/side :theirs :seon.program/identities #{id}})`
  copies the source branch's rows for those identities onto the merge branch; `:ours` restores the target's row (or
  retracts an added one). It works on any identity, conflicted or not, like `git checkout <branch> -- <path>`. Today's
  `unselected-reach` refusal (source.clj:759) is deleted: a dangling caller is a finding in the diff value and `merge!`
  checks it.
- **Edit.** The holder of a merge request evaluates with the merge branch checked out, as a work task checks out the
  agent's own branch, so resolution is ordinary REPL forms (`in-ns`, `defn`, `my.program/ns-unmap!`). A conflict is
  resolved by writing the combined definition.
- Any write to an identity on the merge branch (a checkout or a `defn`) resolves it: it leaves
  `:seon.merge/unmerged`, as `git add` marks a path resolved. Any such write makes the last run stale
  (`program-written-since?` src/seon/test/runner.clj:1187); the next `merge!` runs the tests again.

## 4. `merge!`

One verb, as `git merge`: `(my.program/merge! {:seon.merge/request m})`.

1. **Start or continue.** With no merge branch, create it off the target's tip and apply the non-conflicting changes
   (§3). With a kept merge branch, continue on it. If the target moved since, merge the target's new tip into the kept
   merge branch (GitHub's "Update branch", never a rebase: no commit is rewritten), which keeps root's resolutions.
2. **Norms.** Run each norm the request names on the merge branch, in order, after the program changes, so the tests
   and the schema check see converted data.
3. **Test.** Run, on the merge branch: the task's proofs, every test reaching the final change, and root's chosen
   tests (`:seon.test/identities`). The selection and every result are stored. Reaching-test counts are shown, not
   gated: the installed `uncovered` gate (source.clj:996-998) is deleted.
   **Keep only new behavior** (owner 2026-10-08). A task's proof is preferably an existing test tagged with the task
   or a row added to an existing table test. For each new deftest the source branch carries, root decides in the
   review: keep it only if it guards a behavior class no existing test covers (one regression per behavior class),
   otherwise fold it into a table row or drop it on the merge branch, as an ordinary edit there (§3). The decision is
   recorded on the merge request as data (its keys are declared with the merge request data, ledger §3.16). Suite growth follows distinct behaviors, not task count.
4. **Commit or stop.** Commit when all hold:
   - `:seon.merge/unmerged` is empty;
   - every test of the run is green (unknown evidence never merges). A test already red on main before the source
     branch existed (red at the merge base) never blocks the agent's merge request, but it stops the automatic merge
     and asks root, who decides (owner 2026-10-08);
   - integrity: every carried function contracted, every task handle the program names exists on the target, no
     dangling caller, the schema check complete and holding, the merge branch's program unwritten since the run;
   - `:seon.merge/no-commit` is absent.

   Otherwise stop: the merge branch is kept, and the answer is the unmerged identities, the red tests, the failed
   checks and the forms to run next (`diff`, `checkout!`, `merge!`, `abort-merge!`).
5. **The merge commit.** One transaction onto the target branch, guarded by the target's tip at the run
   (`registry/head-guard-tx`, src/seon/cluster/registry.clj:157), carrying the program rows the merge tested, each
   norm's transaction data and the closed request. Each norm's transaction data is computed outside the writer from
   the target's value at that tip (no agent code on the writer thread); if the tip moved, `merge!` goes back to step 1
   once, then stops. The writer also refuses a carried row whose digest on the target moved (`declarations-held-tx`,
   source.clj:795). Program rows carry no pointer to the evaluation that made them: an agent definition's
   `:seon.program/defined-in` is dropped here, and write-back supplies the file. Parents: the target's tip, the merge
   branch and the source commit. Afterwards the merge branch is deleted. The source branch is deleted once its task
   is done or given back, its merge request is closed (merged or closed with a reason) and its agent has actually
   exited, so its history and blobs can be collected (owner 2026-10-08; ledger SR-BRANCH, MA-15).

**Norms** (Datahike's concept: `reference-code/datahike/src/datahike/norm/norm.clj` at e5453d96, `ensure-norms!`
:204, recorded as `:tx/norm` :142-158, never twice :31). A branch carries program, never the target's data: an agent
that splits `:user/name` into `:user/first` and `:user/last` fixes its own rows, but the target's rows stay old. A norm
is an ordinary contracted function `[:=> [:cat :seon.db/database-value] :seon.db/tx-data]`: pure, it reads a database
value and returns the transaction data that converts it. It is program, so it merges like any `defn`, and the agent's
proofs test it on its own rows. The request names its norms in order, `:seon.merge/norms [app.user/split-name]`. The
merge commit records each norm's symbol and definition digest, and `merge!` skips a norm the target already records.
Seon takes the concept and its rule, not `ensure-norms!` itself, which reads EDN resources and transacts each norm
separately; the merge must commit program and converted data in one transaction. The request's `:seon.merge/norms`
is stored as the installed vector cells (`[:vector :symbol]`), never by transaction order, which neither keeps the
order within one transaction nor survives a merge (`docs/research/agent-platform/2026-10-08/vectors/vectors.md`
§3.1). A norm is bounded like any operation (CLAUDE.md "Make ordinary work sub-second"): its scan, output and time on
the merge branch are measured and shown; over 1 s it is a finding naming the norm; over 10 s the merge stops and the
norm becomes its own root task.

## 5. Automatic merging

- **Configuration.** `:seon.config.merge/auto-merge` on the target cluster's configuration facts. Present: each new or
  updated merge request is merged automatically. Absent: every request waits for root. Root-owned: it lives on the
  target, never in `seon.config/defaults`.
- **Runner.** Root's ordinary serialized agent path, entered by a schedule handler with its own call row and no
  origin tag (root is an agent with a merge task): at start it derives the open requests at the home, then listens for
  new and updated ones, and calls `merge!` for each. Same
  function, same writer; no second path and no model needed, so a provider-less root merges too.
- **Stop.** A stopped automatic merge keeps the merge branch and records why on the request; the request stays the
  task root holds (it was assigned to root when it opened, ledger RT-9, RT-11), so the stop shows in root's
  current-state block, not its history, and no message is sent. A stop the flow cannot classify asks root a question
  about the request (RT-7). Root then reviews by hand (§1 forms).

## 6. Conflict cases

| Case | Theirs (the source branch) | Ours (the target, meanwhile) | Expected |
|---|---|---|---|
| Rename vs new caller | `f` renamed `g`, callers updated, `f` deleted | new `k` calls `f` | three-way clean; `:dangling [{:caller k :calls f}]`; `merge!` stops; root edits `k` on the merge branch to call `g`; `merge!` again commits |
| Contract change vs body change | `f`'s `:malli/schema` changed | `f`'s body changed | one declaration digest each side: conflict on `f`, unmerged; root writes the combined `defn` on the merge branch; `merge!` reruns and commits |
| Schema evolution vs target data | `:a/line` narrowed | stored rows using `:a/line` | without a norm, `:schema [{:refused n …}]` stops the merge; with the request naming a norm, the norm converts the target's rows (including rows written after the test) and the merge commits |
| Two agents, one namespace | A adds `f`, B adds `g` in `ns` | A merged first | per-declaration three-way: `f`, `g` disjoint, B clean (a definition keeps its namespace row since `76daa8fd5`) |
| Same function, two agents | both change `f` | A merged first | B: conflict on `f`, unmerged; root checks out one side or writes the combination |

## 7. What exists and what is missing

Exists (file:line at `81020da6a`, today's names):
- request and proofs gate and message: `my.program/request-merge!` program.clj:416, `seon.task/request-merge!` task.clj:655;
- open requests: `my.program/proposals` program.clj:473;
- comparison: `compare-branch` source.clj:824, `merge-base` source.clj:727, `three-way` program.cljc:704;
- the agent's own diff: `my.program/diff` program.clj:974, `declaration-diff` program.clj:991;
- test merge with selection and file proof: source.clj:847, program.clj:513;
- commit with its checks: source.clj:941, program.clj:547; write-back source.clj:1265;
- close: `seon.task/decide!` task.clj:679; branches: `my.agent/create-branch!` agent.clj:117, `retire-branch!`
  agent.clj:168 (renamed `branch!` and `delete-branch!`, Datahike's names).

Missing or broken, found by the dogfood (§9):

| # | Gap | Task |
|---|---|---|
| G1 | a new contracted function cannot arm on the merge branch: its proof is green on the source branch and red on the merge branch ("this context holds no Var to arm its contract on") | `seon.cluster.source/a-new-function-cannot-arm-in-the-merge-gate` |
| G2 | one registered schema on a branch makes `diff`, compare and the test merge refuse the whole comparison (`changed-identities` scope, program.cljc:700) | `seon.cluster.source/a-branch-schema-blocks-diff-and-merge` |
| G3 | the proof run cannot see a branch-registered schema, so the merge request fails | `seon.test/check-fails-on-agent-registered-schemas` |
| G4 | requesting and merging throw instead of returning their error | `my.program/merge-tools-throw-instead-of-returning` |
| G5 | a test-only new namespace throws entity-id/missing at the test merge | `seon.cluster.source/a-test-only-namespace-throws-at-prepare` |
| G6 | every `defn` rewrote its namespace row: false conflicts, ns doc stripped on merge (fixed `76daa8fd5`) | `seon.turn.batch/a-defn-rewrites-its-namespace-row` |
| G7 | root cannot diff a merge request from its own context | `my.program/root-cannot-diff-a-proposal` |
| G8 | merging cannot delete a hosted source branch (open fault) | `my.program/accept-cannot-retire-a-hosted-proposers-branch` |
| G9 | a red or refused test merge leaks its merge branch | `seon.cluster.source/a-red-prepare-leaks-its-candidate-branch` |
| G10 | the test merge costs seconds | `my.program/the-merge-prepare-costs-three-seconds-and-400k-calls` |
| G11 | no kept merge branch, `checkout!`, dangling, schema check, automatic merge, close with reason, target branch, norms | ledger §3.10 |

**Slices.** Rows of [the ledger](../schedule/agent-loop-ledger.md) §3.10 (merge), §3.16 (renames into these words) and
§3.15 (the merge request without a kind), with their proofs, order and status. Proofs assert data through MCP
`eval_clj`, on a disposable target branch or the `competency` cluster, never default's program.

## 8. Costs, measured 2026-10-08

Default at `81020da6a`, warm JVM, MCP `eval_clj` SCI, wall time by `System/nanoTime` around the call, one sample each
(today's function names). No arbitrary targets (owner 2026-10-08): each step is judged against what its work must do,
and 10× over that expectation is a defect to diagnose; AGENTS.md's 1 s and 10 s still apply.

| Step | Measured | Expected from the work |
|---|---|---|
| `proposals` | 39 ms, 212 calls | one query of the open requests at the home |
| `seon.task/assign!` | 41-54 ms (479 ms first call), ~820 calls | one checked write of the assignment |
| `request-merge!` (one proof run on the branch) | 0.46-1.05 s, 39k-159k calls | the proofs' own bodies plus one write of the request |
| compare (the diff, 4 identities) | 174 ms (JVM, `compare-branch`) | digests of the changed identities only (§2 Cost) |
| `prepare-merge!`, 1 proof | 1.7 s warm, 175k calls; 8.7 s first call (8.0 s in the run) | the tests' bodies plus one branch and one write of the changes |
| `prepare-merge!`, 1 proof + 2 reaching tests | 2.8 s, 602k calls | as above |
| `accept-merge!` refused (red proof) | 135 ms, 2.4k calls | a read of the run's results |
| `accept-merge!` accepted (3 identities) | 1.78 s, 6.2k calls | one guarded transaction of the changed rows |
| retire 10 branches | 558 ms | — |

The accept's calls are few but its wall time is 1.78 s: the time is outside armed functions (the writer's
transaction or the retire submission). Profile before ranking. Prepare's calls are dominated by
`seon.schema/projection-cache-value` (24k-89k) and storage-schema predicates, the same family task G10 names.

## 9. Dogfood record (2026-10-08)

Agents `mcp/merge-dogfood` to `…7` requested merges; reviewers `mcp/merge-root-dogfood…4` held disposable branches
off default as targets. Default's program was not changed. Every branch and merge branch was deleted; the open merge
requests were closed.

1. A branch with a registered schema could not request a merge: `request-merge!` threw `:malli.core/invalid-schema`
   (G3, G4), even after the contract stopped naming the schema.
2. A function and test in a new namespace requested green (1.05 s) and tested red on the merge branch (G1). The same
   with one function (G1 again).
3. A test-only new namespace threw at the test merge in 77 ms (G5).
4. A docstring change to `seon.task/step-summary` with a proof in `seon.task` requested (0.9 s), tested with the proof
   green and one unrelated reaching red shown (2.8 s), and merged onto the reviewer's checked-out branch (1.78 s). The
   home recorded the request accepted although the merge landed on a scratch branch: hence the target branch (§1).
   Deleting the source branch failed and recorded an open fault (G8).
5. Root's review needed JVM mode, `seon.operator.runtime/running-instances` for the store, and
   `seon.cluster.source/compare-branch` to see what a merge request changes (G7).
6. A reviewer branch made before a merge request does not see it: requests live at the home. Automatic merging and
   `merge-requests` read the home, not the reviewer's branch.
7. Test failures print to the run's `:out` through `clojure.test`'s reporter, a value printed outside the value
   printer.
