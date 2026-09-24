---
type: research
status: read-only findings; recommendation not implemented
created: 2026-09-24
scope: agent custody, merge, write-back, per-task delivery functions
---

# Write-back, custody and per-task merge functions — 2026-09-24

Read-only research. Tree at `295df8e3b`; Datahike reference at `c79cd03a4`.
Question (owner): the agent writes to its own branch conn by default; a merge
function receives the source branch db and an injected target conn and writes
back. Should a task name its own merge function, since delivery is often not a
static copy?

Short answer: yes to a per-task function, but make it **pure** —
`(fn [{base source target task}] -> tx-data | error)` — and let the existing
root writer transact it with Datahike's basis fence and merge parents. Two
premises in the question differ from what is installed (§1).

## 1. Custody today

**Where the connection lives.** An agent's SCI ctx holds its connection as a
value in `::custody`, fixed when the ctx is built or forked:

- `seon.sci.eval/cluster-ctx*` sets `::custody {:seon.db/connection conn}`
  (or `{:seon.db/db db}` when there is no conn) — `src/seon/sci/eval.clj:2712`.
- `fork-cluster-ctx` rebuilds custody for the receiving branch, so siblings
  never share it — `src/seon/sci/eval.clj:2786`.

**Per-evaluation binding.** `evaluate` reads that ctx value
(`src/seon/sci/eval.clj:3274`) and binds the compiled dynamic Var
`seon.db/*conn*` for the length of one evaluation through
`db/call-with-custody` (`src/seon/sci/eval.clj:3297`, `src/seon/db.clj:369-391`).
It also binds `*read-database*` to the turn's basis (`eval.clj:3304`). The
elided arities (`(db/db)`, `(db/transact! tx)`) resolve `*conn*`
(`src/seon/db.clj:336-340`, `2030-2045`, `4508`). The value is bound again on
every evaluation, but it always comes from the ctx: an evaluation cannot pick a
different connection.

**Which branch by default.** The live cluster branch, not a private one:

- Creating an agent sets `:seon.agent/branch` to `(or branch (registry/cluster-branch cluster))`
  — `src/seon/cluster/agent.clj:205`.
- `acquire-context!` reads that branch. An agent on the live branch borrows the
  parent connection (`src/seon/cluster/agent.clj:806-812`, `839`). A fresh
  `agent-<id>` branch is created from the captured commit only when the caller
  passes `:seon.agent/isolate? true`, or when the stored branch is not live
  (`:808`, `825-836`). That fresh branch is then written back to the agent row
  (`:855-861`).
- `my.agent/branch` reports `:live` or `:isolated` (`src/my/agent.clj:60-73`).
- Live probe (JVM, 3 ms): `default` has 1 agent, with `:seon.agent/branch :cluster-default`,
  which is also the live branch.

So "the agent writes to its own independent branch by default" is not true
today. It is true for test bodies (`src/my/test.clj:9,52`), for candidates
(`src/seon/cluster.clj:2697`) and for an explicit isolate request.

**Writing to another branch from inside an evaluation is refused.**
`seon.db/transact!` with an explicit connection checks it against the bound
`*conn*`. A different Datahike connection id is refused with
"A cluster writes only its own branch" — `src/seon/db.clj:466-499`, called at
`:4528`. So an agent-side merge function that receives a target conn cannot
write through it. Only a caller with no custody bound can (root/host code, or
`call-without-custody`, `db.clj:393-416`).

## 2. Merge today

The owner is `seon.cluster.source` (`src/seon/cluster/source.clj:698-834`). It
has no production caller: only `test/seon/cluster/merge_test.clj:42-113` calls it.

`prepare-merge! [source store candidate issue]` (`:736`):
1. Finds the merge base B by walking parents from candidate C and head H
   (`merge-base`, `:704-726`, bounded at 10,000 commits).
2. Finds changed identities since B (`program/changed-identities`,
   `src/seon/program.cljc:578-619`). **Function and test declarations only**:
   any other family refuses (`:612-614`).
3. Builds scoped digest maps for B, C and H and classifies them with
   `program/three-way` (`program.cljc:621-661`): unchanged, changed on branch,
   changed on head, conflict (all three digests kept), added, retracted.
4. **Refuses additions and retractions**: "Only replacements of existing
   functions and tests merge here." (`source.clj:767-768`). This is the known
   defect behind "the merge owner refuses added definitions". It also refuses
   an empty delta (`:769`). A conflict returns the three-way value (`:766`).
5. `replacement` (`:728-733`) is already a pure function
   `(from-db, onto-db, delta) -> tx-data`, built from `fn/published-index-rows`
   and `fn/reconcile-tx`.
6. Creates a fresh intermediate branch `merge-<id>` from H, transacts the delta
   there, and runs the issue's tests plus the tests reaching the delta through
   `seon.cluster/candidate-gate!` (`source.clj:774-782`,
   `src/seon/cluster.clj:2669-2718`). It returns a proposal:
   `{candidate S, test-run id, passed?, expected-basis-t (H), parents #{C}}`.

`accept-merge! [source store proposal]` (`:786-834`) refuses unless all of these hold:
- the run was recorded on S;
- every member is green;
- S's program is unwritten since the run;
- every replaced function reaches a green test (`:811-829`).

It then recomputes `replacement tested head delta` and transacts it into the
**source (cluster) connection** with `:datahike/expected-basis-t` and
`:parents (conj merged S)` (`:833`). The acceptance is named: only a caller
holding a green proposal advances the pointer. Nothing yet records *who*
accepted, and no `my.*` entrance exists.

## 3. Write-back to files today

Row-to-file write-back does not exist. The plan status lists it as "D1 s6–7 …
write-back/export — NOT STARTED"
(`docs/research/agent-platform/plan-status-audit-2026-09-23.md:94`). The stage
is specified in `docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md:288-336`.

What does exist is the agent's own file edit: `my.edit/form!`
(`src/my/edit.clj:35`) runs through the protected effect handler
`seon.edit.jvm` (`src/seon/edit/jvm.clj:1`), which is digest-fenced. The writer
records provenance: `seon.effect/write-back-adds` resolves file and declaration
refs from the reported span (`src/seon/effect.clj:270-300`). This is a file
effect from an evaluation, not export of accepted rows.

## 4. Datahike's merge seam, and `seon.db/diff`

- `d/merge-db` / `merge-db!`: "The caller provides the merged tx-data." They
  accept `{:parents :tx-data :tx-meta :datahike/expected-basis-t}` and share
  transact's basis fence — `reference-code/datahike/src/datahike/api/specification.cljc:1041-1070`.
  The writer runs ordinary `transact!` (the fence check is at
  `src/datahike/writing.cljc:871-880`) and then stamps `:datahike/merge-parents`
  (`:882-891`).
- Seon routes any `:parents` transaction to `d/merge-db!`
  (`src/seon/db.clj:4260`, documented at `:4489-4491`).
- `d/branch!`, `force-branch!` with `:expected-current-commit`
  (`specification.cljc:1029-1038`), and commit parents are what
  `merge-base` walks.
- **Datahike has no three-way merge or diff of branches.** It supplies the
  commit shape, lineage and fence. Deciding what to merge is left to the
  caller. This is the upstream answer to "can data alone describe it": the
  dependency itself expects a function.
- `seon.db/diff` (`src/seon/db.clj:2885-2889`) is unrelated. It is an
  editscript path diff of a previously shown value against the current one,
  used for re-rendering. It is not a database or branch diff. The branch diff
  is `program/changed-identities` + `digest-map` + `three-way`.

## 5. The proposal, evaluated

### Option A — as asked: `(fn [source-db target-conn task])` performs the write

- **Custody.** It cannot run in the agent's evaluation (§1: foreign-connection
  refusal). Run by root, it runs with no custody, so nothing forces it to use
  the basis fence or `:parents`. Each author must remember both.
- **Gating.** A side-effecting function writes wherever it is pointed. To gate
  it, root would call it twice: once against S's conn, then against H's. That
  is two executions of arbitrary effects, which may not match.
- **Testing and evidence.** The output is a side effect, so the proposal cannot
  retain it. That breaks "retain proposed data in bounded refusals" and "Put
  provenance on transactions; derive write bounds from it" (CLAUDE.md, data laws).
- **Law.** It makes the task author the writer. The law says "only named
  root/owner acceptance advances the cluster pointer" (CLAUDE.md, One JVM §Merge).

### Option B — pure: `(fn [{base source target task}] -> tx-data | :seon.error)`

- **Purity and testability.** It is a function of three immutable values, so
  it runs anywhere: in the agent's own evaluation (as a preview), in a
  `deftest`, or on root's thread.
- **Gating.** `prepare` calls it once with `target = H`, transacts the returned
  tx-data onto a fresh S forked from H, runs contracts and every test reaching
  the change, and **retains the tx-data in the proposal**.
- **Acceptance.** `accept` transacts that same retained tx-data onto H with
  `:datahike/expected-basis-t` = H's basis and `:parents #{C S}`. The fence
  guarantees the target is still the value the function saw. If H moved, the
  writer refuses and root re-prepares; no stale recomputation happens.
  (Today `accept-merge!` recomputes `replacement` at `:819`. With the retained
  tx-data plus the fence, that recomputation is unnecessary.)
- **Base argument.** Pass `base` as well: without B the function cannot tell a
  conflict from an edit on either side. `merge-base` already produces it.
- **Default function.** The default is today's `replacement` path, widened to
  additions and retractions: `three-way` plus `reconcile-tx` over the
  function/test/namespace/schema families. A task with no delivery function
  gets this default. That is the common, program-only case.
- **Data tasks** (answers, findings, child tasks).
  `docs/research/agent-platform/task-model-synthesis-2026-09-24.md:142` already
  rules that data delivery is `[attr value]` admitted by its data owner. It is
  not a wholesale branch-data merge, and child tasks go through
  `seon.task/create!`. A delivery function fits that ruling if its tx-data is
  built from owner transaction builders (for example, an owner's `*-tx` function
  like `seon.cluster.agent/creation-tx`, `src/seon/cluster/agent.clj:196-210`)
  rather than raw maps. The ordinary writer still validates schemas and refs.
  Turns, messages, errors and requirement rows never appear, because the
  function selects only what it returns.
- **Conflicts.** The default returns the three-way conflict value, as today
  (`source.clj:766`). A custom function returns a declared
  `:seon.error` naming the conflicting identity with its B/C/H values. Either
  way root receives one repair task.

### Option C — data only (a selector with no function)

This is the synthesis's `:seon.task.decision/deliveries` subject set
(`task-model-synthesis-2026-09-24.md:138`). It covers program rows and simple
`[attr value]` copies. It cannot express transformations: renaming across
entities, re-keying findings under a parent, or combining with the target's
current value. The owner is right that data alone is insufficient in general.
Keep the selector as the *default function's input*, not the whole contract.

### Recommendation: B

The task optionally names `:seon.task/delivery`, the qualified symbol of an
ordinary contracted program function. Root's prepare/accept owns the writes.

```clojure
;; The contract, declared once (Malli :=>).
;; [:=> [:cat :seon.merge/delivery-request] [:or :seon.db/tx-data :seon.error/value]]
;; :seon.merge/delivery-request = [:map [:seon.merge/base :seon.db/database-value]
;;   [:seon.merge/source :seon.db/database-value]
;;   [:seon.merge/target :seon.db/database-value] [:seon.task/id :seon.task/id]]

;; A data task's delivery function: promote the finding the agent wrote on its
;; branch into the target, under the parent task, through the note owner's
;; transaction builder.
(defn deliver-finding
  [{source :seon.merge/source target :seon.merge/target task :seon.task/id}]
  (let [finding (db/pull source [:seon.note/id :seon.note/text] [:seon.note/task task])]
    (if (db/pull target [:db/id] [:seon.note/id (:seon.note/id finding)])
      {:seon.error/at (java.util.Date.) :seon.error/message "Finding already delivered."
       :seon.error/offending (:seon.note/id finding)}   ; a declared error schema in practice
      (note/creation-tx (assoc finding :seon.note/subject [:seon.task/id task])))))

;; Root, not the agent. prepare = run f once on values, gate on S, retain tx:
(let [f    (or (some-> (:seon.task/delivery task) requiring-resolve) default-program-delivery)
      tx   (f {:seon.merge/base b :seon.merge/source c :seon.merge/target h :seon.task/id id})]
  ;; candidate-gate! writes tx onto S = branch of H, runs reaching tests  (exists)
  ;; accept: (db/transact! h-conn {:tx-data tx :datahike/expected-basis-t (db/basis-t h)
  ;;                               :parents #{c-id s-id}})               (exists, db.clj:4260)
  )
```

The names `note/creation-tx`, `:seon.note/task` and `:seon.task/delivery` are
illustrative: the task schema is not installed.

Because the function is itself program rows, it is contracted and tested, and
it arrives on the target by the same merge. The first task using a new
delivery function must deliver that function through the default program path.

## 6. What exists and what is missing

| | Exists (file:line) | Missing |
|---|---|---|
| Ctx-held custody, bound per evaluation | `eval.clj:2712,2786,3297`; `db.clj:369-391` | — |
| Cross-branch write refusal under custody | `db.clj:466-499,4528` | — |
| Default branch | live branch, `cluster/agent.clj:205`; isolate on request, `:808,825-861` | Tasks that choose isolated custody by default (if wanted) |
| Merge base, changed identities, three-way | `source.clj:704`; `program.cljc:578,621` | Namespace/schema families in `changed-identities` (`:612`) |
| Intermediate branch + gate | `source.clj:774-782`; `cluster.clj:2669` | — |
| Named accept with fence + parents | `source.clj:786-834`; `db.clj:4260`; Datahike `writing.cljc:871-891` | Record of who accepted; `my.*` entrance; any production caller |
| Pure row-copy function | `replacement`, `source.clj:728` | Additions/retractions (refused at `:767-768`) |
| Per-task delivery function | — | `:seon.task/delivery` attr, delivery-request schema, prepare calling f and retaining tx-data |
| Data delivery through owners | Ruled in synthesis `:142` | Owner `*-tx` builders for notes/findings; `seon.task/create!` |
| Conflict → root repair task | Three-way value returned, `source.clj:766` | Task writer for the conflict |
| Row → file write-back/export | Only `my.edit/form!` file effect + provenance, `effect.clj:270` | All of D1 stage 6 (`lane-d1…md:288-336`) |

Estimated size of the recommendation, on top of T5 (additions/retractions):
- one attribute and one request schema;
- about 20 lines in `prepare-merge!`: resolve f, else use the default, call it, keep `tx` on the proposal;
- `accept-merge!` transacts the retained `tx` instead of recomputing it, which is net neutral.

Well under the 100-line cap. The design freeze applies: no implementation until
the owner lifts it.
