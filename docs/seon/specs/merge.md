---
type: spec
status: live for merge mechanics; WHEN and WHAT merges is owned by TASK-CHAIN §2.6 (a chain merges once, at its last task, under the done rule)
revised: 2026-10-14
tags: [spec, merge, root]
---
# Merge: how root tests and merges a branch

[TASK-CHAIN](../schedule/cards/TASK-CHAIN.md) §2.6 owns when and what merges: root's listener merges a chain branch
to main once, at the chain's last task, and a refused or failed merge goes to root (§2.7). This spec owns the merge
mechanics: the diff value, the merge branch, ours and theirs, conflicts, the writer's digest checks, norms and
publication. Sections that still say "request" describe the installed open assignment (`my.root/merge-requests`),
which the chain's terminal attempt replaces in TASK-CHAIN S3a.

What exists: `my.root/merge!` calls `seon.merge/merge!`, which tests the merge on a fresh merge branch off the target
(`seon.cluster.source/prepare-merge!`) and merges exactly what was tested (`seon.cluster.source/accept-merge!`); the
writer checks the replaced rows' digests. Data flow: the source branch → three-way digest comparison (merge base,
ours, theirs; `seon.cluster.source/compare-branch`, `seon.program/three-way`) → one transaction onto the merge branch →
test run on the merge branch → one guarded merge commit onto the target branch. The approach stays: functions of
database values, the three-way value as the diff, and Datahike commits with merge parents.

**Words.** Git's, where git's meaning holds (map and reasons:
`docs/research/agent-platform/2026-10-08/git-vocab/git-vocab.md`). **Main** is a cluster's branch; "the tip of main" is
its latest commit. An agent's **branch** is where it works. A **source branch** merges into a **target branch**
(GitLab's pair). The **merge base** is the newest common ancestor. The **merge branch** is where a merge is tested and
resolved, off the target. In a conflict, **ours** is the target and **theirs** is the source, as in `git merge`; Seon
never rebases, so the words never swap. An **unmerged** identity is a conflict not yet resolved. **Behind** lists what
the target changed since the merge base. A **norm** (Datahike's word) is the data change a merge carries. No invented
short labels: never "S", "candidate", "head" or "proposal".

Rulings this spec applies: an agent may make any REPL-valid change on its branch, conflicting ones included; its
branch never refuses them. Integrity is judged at the diff and the merge. During work, the task's proofs and every
test reaching the change run on the agent's branch ([agent-loop.md](agent-loop.md) §3.5); at merge they run again on
the merged program. `merge!` commits when the merge is clean and every test on the merge branch is green, and
otherwise stops with the merge branch kept. A definition's pointer to the evaluation that made it is dropped at merge:
the source file becomes the record. Our own lanes write a branch back to files without a merge
(`my.program/write-branch!`) and commit by branch evidence (`bin/lane commit --branch-evidence`); that is the lanes'
path into git, not a merge of the cluster.

## 1. What root holds

- The source: installed, the open assignment (`:seon.task/assignment`, its `:seon.task/branch` and
  `:seon.task/merge-base`, no `:seon.task/outcome` yet); target (TASK-CHAIN §2.6), the chain branch and its terminal
  attempt. The outcome is the only record: `:merged` or `:closed` with `:seon.task/reason`.
- The merge branch, created off the target by `merge!`; kept while the merge is stopped, deleted after the merge.
- The target branch: main, the branch the source was forked from.

**Forms** (each answer offers the next forms as `:seon.render/operations`):

| Form | Git | Answers |
|---|---|---|
| `(my.root/merge-requests)` | the forge's open list | open assignments with their branch, merge base and end |
| `(my.program/diff ...)` | `git diff main...branch` | the diff value (§2); installed for the agent's own branch, root's view of a source branch is `seon.cluster.source/compare-branch` |
| `(my.root/merge! {:seon.task/assignment a})` | `git merge` | merged, or stopped with the first refusal as data (§4) |
| `(my.root/close! {:seon.task/assignment a :seon.task/reason "…"})` | close a merge request | closed; the source branch deleted, its agent closed (`seon.task/close!`) |
| `my.program/checkout!` (TARGET) | `git checkout --theirs -- path` | the identity copied onto the merge branch (§3) |
| `my.program/abort-merge!` (TARGET) | `git merge --abort` | the merge branch deleted |
| ordinary forms (`in-ns`, `defn`, `my.program/ns-unmap!`) | edit and `git add` | the merge branch changed (§3; TARGET: the merge branch is kept) |

**Evidence** (stored facts):
- the run on the merge branch, `:seon.test.run/id`, with every member's result;
- the merge commit on the target, with parents the target's tip, the merge branch and the source commit (consumers:
  write-back, history);
- the review's test decisions (§4 step 3: kept, folded, dropped).

**A terminated result carries, red included; unknown does not** (owner 2026-10-14). A test's result is reused
wherever the same test is asked of the same content: its identity is `[test reach digest, external-input digest,
evaluator]`, the key `seon.test/select` reuses by on one branch. The recorded runs are the durable source: a request on
any branch whose member has that identity reads the runs of main and of every other live branch, each branch head
read directly once (`seon.test/carried-results`, one batched query per branch, no copy to main), and records the
member on its own branch with `:seon.test.member/reused-run` naming the run that executed it, so that branch holds its
own evidence (`seon.test/verified?`, `seon.cluster.branch-evidence/branch-evidence`) and later requests reuse it there.
`seon.cache` holds each recorded outcome under that identity (`seon.test.runner/retain-results!`) as a pure speed-up.
An outcome is retained and reused only when the body terminated with at least one assertion: a timeout, an interrupt,
a recording failure, a boundary error or a member with no assertions is unknown and reruns. Reuse never converts an
outcome: a reused red is red and blocks as an executed red does; its failure reports are read from the run that
executed it. When one identity holds two outcomes the newest answers and a problem task is filed on the test naming
the identity. An explicit rerun executes. Full suites run only when asked for.

## 2. The diff value

One value, from `seon.cluster.source/compare-branch` and `seon.program/three-way`: every
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
 :seon.render/operations [(my.program/diff 'a/g)
                          (my.root/merge! {:seon.task/assignment "…"})]}
```

The installed three-way keys (`changed-on-branch`, `retracted`, `changed-on-head`) become `:seon.program/modified`,
`deleted` and `modified-ours` with this shape.

- **What blocks a clean merge.**
  - Same identity changed on both sides (three-way `:conflict`, installed).
  - A dangling caller: a declaration on the merged value whose `:seon.fn/calls` names a symbol with no declaration.
    Calls are symbol values, not refs (`resources/seon/schemas/seon.fn.edn`), so the writer cannot refuse it on the
    source branch; `merge!` checks it.
  - A schema the target's data violates: for each added, modified or deleted `:seon.schema/key`, the target's refused
    value count from `seon.db/registration-check` and the declarations reading the key. The
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
- **Checkout** (TARGET) `(my.program/checkout! {:seon.merge/request m :seon.merge/side :theirs :seon.program/identities #{id}})`
  copies the source branch's rows for those identities onto the merge branch; `:ours` restores the target's row (or
  retracts an added one). It works on any identity, conflicted or not, like `git checkout <branch> -- <path>`. The installed
  `seon.cluster.source/unselected-reach` refusal is deleted: a dangling caller is a finding in the diff value and `merge!`
  checks it.
- **Edit.** The holder of a merge request evaluates with the merge branch checked out, as a work task checks out the
  agent's own branch, so resolution is ordinary REPL forms (`in-ns`, `defn`, `my.program/ns-unmap!`). A conflict is
  resolved by writing the combined definition.
- Any write to an identity on the merge branch (a checkout or a `defn`) resolves it: it leaves
  `:seon.merge/unmerged`, as `git add` marks a path resolved. Any such write makes the last run stale
  (`seon.test.runner/program-written-since?`); the next `merge!` runs the tests again.

## 4. `merge!`

One verb, as `git merge`: `(my.root/merge! {:seon.task/assignment a})` (`seon.merge/merge!`).

1. **Start or continue.** With no merge branch, create it off the target's tip and apply the non-conflicting changes
   (§3). With a kept merge branch, continue on it. If the target moved since, merge the target's new tip into the kept
   merge branch (GitHub's "Update branch", never a rebase: no commit is rewritten), which keeps root's resolutions.
2. **Norms.** Run each norm the request names on the merge branch, in order, after the program changes, so the tests
   and the schema check see converted data.
3. **Test.** Run, on the merge branch: the task's proofs, every test reaching the final change, and root's chosen
   tests (`:seon.test/identities`). The selection and every result are stored. Reaching-test counts are shown, not
   gated: the installed `uncovered` gate is deleted.
   **Keep only new behavior** (owner 2026-10-08). A task's proof is preferably an existing test or a row added to an
   existing table test (installed: tagged with the task; TASK-CHAIN §1 makes proofs a field, S2c). For each new deftest the source branch carries, root decides in the
   review: keep it only if it guards a behavior class no existing test covers (one regression per behavior class),
   otherwise fold it into a table row or drop it on the merge branch, as an ordinary edit there (§3). The decision is
   recorded on the merge request as data. Suite growth follows distinct behaviors, not task count.
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
   (`seon.cluster.registry/head-guard-tx`), carrying the program rows the merge tested, each
   norm's transaction data and the closed request. Each norm's transaction data is computed outside the writer from
   the target's value at that tip (no agent code on the writer thread); if the tip moved, `merge!` goes back to step 1
   once, then stops. The writer also refuses a carried row whose digest on the target moved (`seon.cluster.source/declarations-held-tx`). Program rows carry no pointer to the evaluation that made them: an agent definition's
   `:seon.program/defined-in` is dropped here, and write-back supplies the file. Parents: the target's tip, the merge
   branch and the source commit. Afterwards the merge branch is deleted. The source branch is deleted once its task
   is merged or closed with a reason and its agent has actually exited, so its history and blobs can be collected
   (TASK-CHAIN §2.6: a chain branch is retired after publication).
6. **Publication.** A merge commit that moved declarations is due work for the next pass
   (`seon.cluster.agent/publication-due?`): `:seon.task/merged-declarations`, written once beside
   `:seon.task/merge-commit` on both paths (facts only and gated), counts the identities
   `seon.cluster.source/merge-accepted` derives, program declarations and tasks alike. A task entry is a declaration
   whose task file is its write-back: `seon.merge/publish!` writes a merged task (`[:seon.task/sym ns/name]`) into
   `resources/seon/tasks/<ns>.edn` by rewrite-clj (`seon.cluster.source/proven-task-file`), every other entry
   byte-identical, proven by reading it back through `seon.task/file-declarations`, and commits it with the merge's
   other paths. Adoption merges tasks three-way from the prior adopted source commit
   (`seon.task/adopt-tasks-call`): only the tasks the publication changed are reconciled, and one main revised too
   (a merged refinement whose publication still waits) keeps main's value and writes root's problem task.

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

## 5. Automatic merging (TARGET)

Not installed. TASK-CHAIN §2.6 has root's listener merge a finished chain; whether a dial gates that merge is an open
owner question.

- **Configuration.** `:seon.config.merge/auto-merge` on the target cluster's configuration facts. Present: each new or
  updated merge request is merged automatically. Absent: every request waits for root. Root-owned: it lives on the
  target, never in `seon.config/defaults`.
- **Runner.** Root's ordinary serialized agent path, entered by a schedule handler with its own call row and no
  origin tag: at start it derives the open requests at the home, then listens for new and updated ones, and calls
  `merge!` for each. Same
  function, same writer; no second path and no model needed, so a provider-less root merges too.
- **Stop.** A stopped automatic merge keeps the merge branch and records why on the request; the request stays
  open, so the stop shows in root's current-state block, not its history, and no message is sent (TASK-CHAIN §2.7: a
  refused merge goes to root). Root then reviews by hand (§1 forms).

## 6. Conflict cases

| Case | Theirs (the source branch) | Ours (the target, meanwhile) | Expected |
|---|---|---|---|
| Rename vs new caller | `f` renamed `g`, callers updated, `f` deleted | new `k` calls `f` | three-way clean; `:dangling [{:caller k :calls f}]`; `merge!` stops; root edits `k` on the merge branch to call `g`; `merge!` again commits |
| Contract change vs body change | `f`'s `:malli/schema` changed | `f`'s body changed | one declaration digest each side: conflict on `f`, unmerged; root writes the combined `defn` on the merge branch; `merge!` reruns and commits |
| Schema evolution vs target data | `:a/line` narrowed | stored rows using `:a/line` | without a norm, `:schema [{:refused n …}]` stops the merge; with the request naming a norm, the norm converts the target's rows (including rows written after the test) and the merge commits |
| Two agents, one namespace | A adds `f`, B adds `g` in `ns` | A merged first | per-declaration three-way: `f`, `g` disjoint, B clean (a definition keeps its namespace row since `76daa8fd5`) |
| Same function, two agents | both change `f` | A merged first | B: conflict on `f`, unmerged; root checks out one side or writes the combination |

## 7. Missing

Not installed (target): a kept merge branch with `my.program/checkout!` and `abort-merge!`, the dangling-caller and
schema checks in `merge!`, norms, the target branch fixed at the open, and automatic merging. The 2026-10-08 dogfood
record, the gap list G1-G11 and the measured costs are dated evidence in
`docs/research/agent-platform/2026-10-14/merge-dogfood-2026-10-08.md`.
