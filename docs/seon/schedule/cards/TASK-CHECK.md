---
title: "Card TASK-CHECK: Ingestion reports what makes a task unworkable"
type: reference
status: active
created: 2026-10-08
---

# TASK-CHECK: Ingestion reports what makes a task unworkable

Depends on: REF-1 (`seon.ref/references`), CS-01c3 (paths and anchors resolve). Group D. Model: Sonnet.

Ledger §5 row; design in
[task-census.md §7](../../../research/agent-platform/2026-10-08/task-census/task-census.md). Per task, as problems on
the task and never a refusal (a task may name what it will create): (a) no tagged proof, (b) a reference resolving to
nothing, a doc reference whose file or `#heading` does not exist included, (c) every subject deleted. Absorbs
`seon.task/task-text-references-are-never-resolved`. Not absorbed: `seon.task/open-tasks-with-all-proofs-green-are-not-flagged`
(the ledger row names it, but a green verdict reads test results, which CS-01b's proof status owns; it stays its own
task, listed in the ledger row as not covered here).

## Files

- `src/seon/task.clj`
- `resources/seon/schemas/seon.task.edn`
- `test/seon/task_ingestion_test.clj`

## What is true at HEAD

- `seon.task/index-tasks!` (`src/seon/task.clj:304`) reconciles task files and answers `:seon.task/report`
  (`seon.task.edn:144`: `:seon.task/changed?`, optional transaction report).
- `seon.task/task-findings` (`src/seon/task.clj:349`) answers `:seon.task/dangling` (tags naming no task) and
  `:seon.task/triage` (tasks with no subject or proof), over the whole database.
- Publication calls both: `seon.cluster.source/index-tasks!` (private, `src/seon/cluster/source.clj:516`) indexes,
  then refuses on the first dangling tag (`:533`-`:540`); nothing reports a task's own references.
- The census measured 917 must-reads plus 641 spans resolving in 150 ms on default
  (`references/references.md` "Ingestion validation").

## Functions

```clojure
(defn task-problems
  "Each task's problems in `symbols`: no tagged proof, references resolving to nothing, every subject gone."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.task/sym]]
                  [:or :seon.task/problems :seon.db/invalid-read-error]]}
  [database symbols] ...)
```

Per task: pull `[:seon.task/sym :seon.task/intent :seon.task/must-read :seon.task/evidence]`; `edges` =
`(task-edges database sym)` (`src/seon/task.clj:322`, one query per task):

- `:seon.task/proofless` when `(:seon.task/proofs edges)` is empty.
- `:seon.task/subjects-gone` when the task has must-reads and none resolves (every subject deleted).
- `:seon.task/references` = the problems among
  `(seon.ref/references database {:seon.ref/text intent :seon.ref/names must-read})`, plus each `:seon.task/evidence`
  string read as one path candidate through the same function (`{:seon.ref/text (str "`" e "`")}`: the evidence is a
  based path, so it takes the code-span rule; no second parser). A missing doc file is `:unresolved`, a missing
  heading `:anchor-missing` (CS-01c3).

A task with none of the three is absent from the answer.

`index-tasks!` answers, beside `:seon.task/changed?`, `:seon.task/problems` for the tasks of the files whose
declarations changed in this call (the reconciled delta's symbols), so cost follows the change. The whole-set answer is
`(task-problems db (set all-task-symbols))`, which REF-2's sweep calls. Nothing is stored: the block (CS-01c1..c3)
derives the same problems at render from the same function, so there is no mirror to drift.

## Schemas (`seon.task.edn`)

```clojure
:problem [:map [:seon.task/proofless {:optional true} [:= true]]
          [:seon.task/subjects-gone {:optional true} [:= true]]
          [:seon.task/references {:optional true} [:vector :seon.ref/problem]]]
:problems [:map-of :seon.task/sym :seon.task/problem]
:report [:map [:seon.task/changed? :seon.task/changed?] [:seon.db/transaction-report {:optional true} :seon.db/transaction-report]
         [:seon.task/problems {:optional true} :seon.task/problems]]
```

## Returned shape

```clojure
{:seon.task/changed? true
 :seon.task/problems
 {tq.a/proofless #:seon.task{:proofless true}
  tq.a/dangling #:seon.task{:references [{:seon.ref/problem-kind :unresolved :seon.ref/name tq.a/missing :seon.ref/line 1}
                                         {:seon.ref/problem-kind :anchor-missing :seon.ref/path "AGENTS.md"
                                          :seon.ref/anchor "no-such-heading" :seon.ref/line 1}]}
  tq.a/orphan #:seon.task{:subjects-gone true :proofless true}}}
```

## Proof

`seon.task-ingestion-test/ingestion-reports-what-makes-a-task-unworkable`, with the file's existing helpers
(`with-database`, `admit!`, `ingest!`): one fixture task file with a proofless task, a task whose intent names
`` `tq.a/missing` `` and `` `AGENTS.md#no-such-heading` `` and evidence `"docs/no-such.md"`, a task whose only
must-read `tq.a/gone` is never admitted, and one proven task naming `` `AGENTS.md#data-and-contracts` ``. Assert
`:seon.task/problems` as data: the three problem tasks exactly as above, the proven task absent. Re-ingesting the same
text answers `{:seon.task/changed? false}` with no problems computed (armed-calls: zero `task-problems` calls).

`--test seon.task-ingestion-test --test seon.ref-test`.

## Cost

Per changed task: one `task-edges` query, one pull and one `references` call (a lookup per name, a file read per path,
parses cached by content). Publication of an unchanged task set computes nothing. Time the 551-task sweep on default
and report it; the census measured 150 ms for the reference half.

## Deleted

Nothing. The absorbed task `seon.task/task-text-references-are-never-resolved` is not deleted by hand: the proof
deftest carries it in `:seon/tasks` (`^{:seon/tasks #{seon.task/task-text-references-are-never-resolved}}` before the
name), so the proof completes it.

## Commit

`bin/lane commit task-check -m "TASK-CHECK: ingestion reports what makes a task unworkable" --test seon.task-ingestion-test --test seon.ref-test src/seon/task.clj resources/seon/schemas/seon.task.edn test/seon/task_ingestion_test.clj`
