---
title: "Card RT-4 A task names proofs in its data"
type: reference
status: active
created: 2026-10-08
---

# RT-4 A task names proofs in its data

Ledger §3.28 RT-4 (group E). Lane: Sonnet.

## What becomes true
A task's proofs are the deftests tagging it (`:seon/tasks`) plus the deftest symbols the task itself names in
`:seon.task/proofs`. Generated tasks name their proofs this way: a merge request names its source task's proofs
(RT-9), a question names `seon.task-question-test/root-decided` (RT-7). Ingestion (TASK-CHECK) reports a named proof that
resolves to no deftest as a problem on the task.

## Functions
- CHANGE `resources/seon/schemas/seon.task.edn`: the installed `:seon.task/proofs` (`[:set :symbol]`, today only a
  derived shape) becomes an optional member of the task entity; publish and adopt on a live-store branch.
- CHANGE `seon.task/task-edges`: `:seon.task/proofs` is the union of the tagged query and the task row's
  `:seon.task/proofs` (one pull by `:seon.task/sym`).

## Deleted
Nothing.

## Proof
`seon.task-ingestion-test/a-task-names-proofs-in-its-data` (new): a runtime task created with
`:seon.task/proofs #{x.y-test/p}` and no tagging deftest: `task-edges` lists `x.y-test/p`; `assign!` accepts it while
`p` is red and refuses `:proofs-green` once `p` is verified.

## Cost
One pull more per `task-edges` call.

## Dependencies
RT-2 (same file, in series). Commit with `--test seon.task-ingestion-test`.
