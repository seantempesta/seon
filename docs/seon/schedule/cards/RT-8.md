---
title: "Card RT-8 Root's common tasks are templates of data whose references teach the job"
type: reference
status: active
created: 2026-10-08
---

# RT-8 Root's common tasks are templates of data whose references teach the job

Ledger §3.28 RT-8 (groups E and V). Owner (2026-10-08): "no kinds — we have tasks that have data that has references
that we pull in context for. Root just has a harder job than most but we can still spec out and generate a lot of
common tasks so even it is on rails most of the time." One `(help)` for every agent; no root help, no role switch.
Lane: Sonnet.

## What becomes true
- `seon.task/templates` is one data map, keyed by the producer's fully qualified symbol, each value the task data a
  producer fills in: `:seon.task/intent` (short, with the subject's identity appended as data, never printed),
  `:seon.task/must-read` (the `my.*` and `seon.*` functions the job uses), `:seon.task/evidence` (living-doc
  sections, `docs/seon/architecture/root-work.md#<heading>`), and either `:seon.task/proofs` (a generated proof,
  RT-4) or `:seon.task/answer-schema :seon.task/decision` with the generic proof (RT-7). No kind: a template is data,
  the generated task is an ordinary task whose references the block pulls in like any other (CS-01c2, REF-1, CS-01c5).
- `seon.task/generate-tx` `[database template-key subject]` → tx-data: the template merged with the subject's data
  (`:seon.task/about`, the request's proofs), the handle `seon.id/id` over `[ns name]` derived from the subject
  (existence is one lookup), and `assign-tx` to the agent the producer names. RT-7's `ask-tx` is
  `(generate-tx db :seon.task/specify subject)`.
- The templates (one entry each, about 6 lines of data):

| Key (producer) | Intent | References | Proof |
|---|---|---|---|
| `my.program/request-merge!` | merge the request | `my.program/diff`, `merge!`, `checkout!`, `abort-merge!`, `close-merge-request!`; `root-work.md#merge-a-request` | the source task's proofs on the target (RT-9) |
| `my.program/merge!` (an unclassified stop) | decide what the stopped merge needs | the above; `root-work.md#a-stopped-merge` | `root-decided` |
| `seon.error/commit-call` (a new signature) | triage the error: file its owner's task with a red reproduction | `seon.task/create!`, `seon.task/assign!`, `my.agent/answer!`; `root-work.md#triage-an-error` | `root-decided` |
| `my.task/complete!` (given back or capped) | decide: revise, reassign or retire | `seon.task/assign!`, `seon.task/create!`; `root-work.md#a-given-back-task` | the task's own proofs (RT-13) |
| `:seon.task/specify` (anything unplanned) | specify this task: investigate, then file tasks with red proofs or decide no action | `my.message/send`, `my.agent/answer!`, `seon.task/create!`; `root-work.md#specify-a-task` | `root-decided` |

- NEW living doc `docs/seon/architecture/root-work.md` with exactly those five headings, each a short how-to in the
  forms that exist (written in this card; cross-checked by REF-2's hook when it lands). Cross-cutting knowledge lives
  there, not in help.

## Functions
- NEW `seon.task/templates` (data), `seon.task/generate-tx`; CHANGE RT-7's `ask-tx` to call it (or delete `ask-tx`
  if RT-7 lands with this card).
- NEW `docs/seon/architecture/root-work.md`.

## Deleted
Root's injected startup form `(seon.cluster.status/agents {})` in `seon.cluster.agent` (an instruction by role), if
CX-3 has not deleted it.

## Proof
`seon.task-ingestion-test/a-generated-task-carries-its-templates-references` (new): `generate-tx` for each template key
writes a task whose `:seon.task/must-read` and `:seon.task/evidence` resolve (REF-1's resolver, or a Var and file
lookup until it lands) and whose proofs are as the table says; `seon.context-test/a-generated-tasks-block-shows-its-references`
(after CS-01c5): root's block for a held merge request shows the `merge-a-request` section.

## Cost
Templates are a constant; `generate-tx` is RT-7's one transaction.

## Dependencies
RT-4, RT-7; the block's reference rows (CS-01c2, REF-1, CS-01c5) for the second proof. Every producer card (RT-9,
RT-11, RT-12, RT-13) uses its template.
