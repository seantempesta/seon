---
title: "Card RT-7 A question to root"
type: reference
status: active
created: 2026-10-08
---

# RT-7 A question to root

Ledger §3.28 RT-7 (group E). Lane: Opus (a generic proof over data). Owner: "wake it up and ask it what it thinks
should be done for things we haven't planned for"; "for unspecified tasks it has to specify it itself".

## What becomes true
- A producer that has work and no proof to generate asks: in its own home transaction it writes one **question task**
  and assigns it (RT-2) to root, or to the steward of the function the work is about when one is held. The question is
  an ordinary task: `:seon.task/answer-schema :seon.task/decision` (A1's member), `:seon.task/proofs
  #{seon.task-question-test/root-decided}` (RT-4), `:seon.task/about` (new: refs to the entities it is about: a
  recorded error, a merge request, an agent, a task), and a short fixed intent naming the producer's operation.
  Its handle is `seon.id/id` over `[ns (str "ask-" (seon.id/id about 12))]`, so the same subject asked twice is the
  same task (no second write, no scan).
- The question is unspecified work, so its job is to specify: root reads the `about` entities, queries the system,
  messages the agents involved, then answers (`my.agent/answer!`, RT-6) a decision:
  `:seon.task/decision [:map [:seon.task/filed {:optional true} [:set :seon.task/handle]]
  [:seon.task/covers {:optional true} [:set :seon.task/handle]] [:seon.task/reason [:string {:min 1}]]]`: the tasks it
  filed (each created with `seon.task/create!` and a red tagged proof, usually assigned), other questions this decision
  also settles, and why. No filed task means no action, and the reason says why.
- `seon.task-question-test/root-decided` reads the question from its member world (RT-5): green when the recorded
  answer validates against `:seon.task/decision` and every filed task exists with at least one proof. It is red when
  the question is asked (no answer), so `assign-tx` accepts it; its green completes the question (RT-3 retracts it).
  A question named in another decision's `covers` is complete when that decision is.

## Functions
- NEW `seon.task/ask-tx` `[database {:seon.task/ns ns :seon.task/about refs :seon.task/intent intent :seon.task/agent agent}]`
  → tx-data: the task row (if absent) and `assign-tx`'s data.
- NEW schema members in `seon.task.edn`: `:about [:set :seon.db/ref]`, `:decision` (above), `:filed`, `:covers`.
- NEW `test/seon/task_question_test.clj`: `root-decided`, contracted, reading `:seon.task/id` from its world.

## Deleted
Nothing here; RT-11, RT-12, RT-13 route their fallbacks through it.

## Proof
`seon.task-ingestion-test/a-question-is-asked-once-and-done-by-a-decision` (new; through `seon.mcp/call` as root):
`ask-tx` twice about the same entity writes one task held by root; `root-decided` is red; an answer with no filed task
and a reason makes it green and root no longer holds it; an answer filing a task with no proof stays red.

## Cost
One home transaction (about 8 task datoms and 5 assignment datoms) at the producer; existence is one handle lookup.

## Dependencies
RT-2, RT-3, RT-4, RT-5, RT-6 (A1). Commit with `--test seon.task-ingestion-test --test seon.task-question-test`.
