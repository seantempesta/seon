---
title: "Root's work as tasks (RT cards)"
type: reference
status: active
created: 2026-10-08
---

# Root's work as tasks (RT-1 … RT-14)

Owner (2026-10-08): "everything needs to be task driven. If root is going to be resolving issues, merging, etc we need
to auto generate them"; "find ways to generate proofs for it to fix when possible but root is root so we can also just
wake it up and ask it what it thinks should be done for things we haven't planned for"; "for unspecified tasks it has
to specify it itself … roots instructions should be different". Rulings applied: a model turn only with an assignment,
root's only for an assigned root task (Q-CX3, [README-context](README-context.md)); a task is assigned only with a red
proof (`seon.task/assign!` refuses `:no-proofs` and `:proofs-green`, installed).

## What exists

Root's work arrives today as messages: on default (2026-10-08 evening, one query, 2.4 ms) root holds **10** messages
and **0** assignments; **9** messages are error notifications whose content is a printed `#:seon.error{…}` map
(`seon.error/commit-call`'s recipients, task `seon.error/error-notifications-to-root-are-printed-maps`), 3 of them sent
by root to itself, and 1 is "Hello". A merge request sends one more message (`my.program/propose-merge!`'s
`message/send!` "Please merge branch …"). Each message is a listened wake and, before CX-2, opens a root model turn.
Wakes are datoms on attributes declared `:seon.wake/listen true` whose value is the agent (`seon.wake`; today
`:seon.message/to`, `:seon.schedule.fire/…`, `:seon.effect/…`). An assignment (`:seon.task/agent`) is not one.

## The design in five lines

1. **An assignment is the wake.** `:seon.task/agent` becomes a listened attribute, so writing an assignment wakes its
   agent. Every producer of root's work writes, in its own home transaction, a task and its assignment to root through
   one writer function (`seon.task/assign-tx`, the checks `assign!` makes today), never a message.
2. **Proofs are generated from what the work already has.** A merge request's proofs are its source task's proofs,
   judged on the target (red there until `merge!` commits them). A given-back or capped task keeps its own red proofs.
   Everything else is a **question to root**: a generated task whose answer schema is `:seon.task/decision` and whose
   one proof, `seon.task-question-test/root-decided`, is generic: it reads the task it proves and is red until root's recorded
   decision validates and every task the decision files has a red tagged proof.
3. **A task names proofs in its data** (`:seon.task/proofs`, the installed key, stored on the task) beside the deftests
   tagging it, and **a proof reads the task it proves** (the proof request carries the task; the member records the
   digest of the task data it read, as F6 records an answer's), so one deftest proves every generated question.
4. **An agent may hold several assignments; holding is the assertion.** The assignments are components of the agent
   entity, which lives at the task home with everything about the agent ([README-agent-entity](README-agent-entity.md),
   AE-4). Root holds many at once; the block lists them
   oldest first. Completing a task (all proofs green) or giving it back retracts the assignment in the transaction that
   records it, so root's open work is one indexed read of its current assignments, never a walk of its history.
5. **Root is on rails through its tasks' references, not its own help** (owner: "no kinds"). Every agent reads the
   one `(help)`. Root's common tasks are templates of data (`seon.task/templates`: intent, must-read functions,
   living-doc sections in `docs/seon/architecture/root-work.md`, generated proof); the block pulls a generated task's
   references in like any task's. An unspecified task (a question) references the specification guide and is done by
   specifying: root investigates, then files owner tasks with red proofs (or decides no action, with a reason).

Nothing else wakes root: the error message, the merge-request message, the merge-stop message, the give-back and cap
messages and the `escalate-to` dial are deleted by the cards that replace them; system turns, the opening and the demo
seed are deleted by CX-3, CX-6 and CX-8.

## Each duty of root

| Duty | How it reaches root | Proof | Why |
|---|---|---|---|
| Merge request opened (F5) | generated: the request is a task; its opening transaction assigns it to root (RT-9) | the source task's proofs on the target (RT-4, RT-10) | real tests exist and are red on main until the merge commits |
| Automatic merge (MA-12) | the same held request; the schedule handler runs `merge!` on root's path before any model turn (RT-11) | as above | provider-less root merges; no second path |
| Stopped merge (unmerged, red, integrity) | not a new task: the request stays held, the stop is on it and in root's block (RT-11) | as above, still red | the stop is the same work, not new work |
| Stop the merge flow cannot classify (unknown evidence, a norm over 10 s, a refusal outside the stop reasons) | a question to root about the request (RT-11 via RT-7) | `seon.task-question-test/root-decided` | no proof can be generated |
| Seon's own error, new signature (TX-3) | a question to the function's steward if one is held, else to root, in the opening transaction (RT-12) | `root-decided`: the decision files the owner task with its red reproduction | a reproduction cannot be generated safely: errors hold references, not inputs, and replaying a form repeats its effects |
| Repeat of an open signature | nothing: the count only (TX-3a) | — | repeats only count |
| Given-back task (F7) | the assignment moves to the assigner in the give-back transaction (RT-13) | the task's own proofs, still red | the assigner decides: revise, reassign, or retire |
| Call cap reached | the same move, the agent and branch kept; root's re-assignment resumes (RT-13) | the task's own proofs | resumable exhaustion keeps identity |
| Given back with every proof green | a question to the assigner (RT-13 via RT-7) | `root-decided` | `assign-tx` refuses an all-green task |
| Finished agent's branch | derived, no task: the agent holds no assignment and has exited, so its proc's exit deletes the branch (SR-BRANCH) | — | no judgment; a failed deletion is a recorded error, which asks root |
| Health finding over bound (> 1 s, over a dial) | a recorded error like any other (its own signature), so RT-12 | `root-decided` | one recorder, one route |
| Anything not planned | a producer calls `seon.task/ask-tx` with the entities it is about | `root-decided` | "root is root": a bounded question, never a free-running wake |

## Cost

Generating a task is one home transaction at its producer, the transaction it already writes: the task row
(about 8 datoms) and the assignment (5 datoms) ride it; the producer's existence check is one indexed lookup of the
deterministic handle (`seon.id/id` over the subject's identity), never a scan. `assign!` measures 41-54 ms today
(merge.md §8), almost all outside the write; `assign-tx` inside a transaction is the same reads (one revision, one
edges query, one `verified?` per proof). Root's open work per pass: one indexed read of its current assignments (they
are retracted when done), then `verified?` per held proof: follows root's open work.

## Order (each card about 15 minutes)

1. **RT-1** an assignment is a wake (schema only; superseded by AE-4 under Q-AE1's recommended option).
2. **RT-2** `seon.task/assign-tx`: assignment checks inside any producer's transaction.
3. **RT-3** an agent holds several assignments; completing or giving back retracts one (after F5's completion).
4. **RT-4** a task names proofs in its data.
5. **RT-5** a proof reads the task it proves (after F6).
6. **RT-6** answers live on the assignment (amends A1 before it lands).
7. **RT-7** a question to root: `seon.task/ask-tx`, `:seon.task/decision`, `seon.task-question-test/root-decided`.
8. **RT-8** root's common tasks as templates of data, and `root-work.md` (after RT-7).
9. **RT-9** opening a merge request assigns it to root (after F5, GV-5).
10. **RT-10** `merge!` carries its proofs' green evidence onto the target (with MA-11).
11. **RT-11** the automatic merge works root's held request; a stop stays on it (with MA-12).
12. **RT-12** a new error signature asks its steward or root (after TX-3b).
13. **RT-13** give-back and the call cap move the assignment to the assigner (after F7a).
14. **RT-14** root is woken only by an assignment (the class regression and the sweep).

RT-1 … RT-4 are file-disjoint from the context track except RT-3's block line (`context.clj`, group D, after
CX-1). RT-5 and RT-6 follow F6 and A1. RT-9 … RT-13 ride the rows they amend and land in their order on §5's path.

## Owner questions

**Q-RT1 (RT-3). How an agent stops holding a task.** ANSWERED (owner 2026-10-08): holding is the assignment's presence; completion or give-back retracts it. F5 says a task's status is derived, never stored. Root accumulates
one assignment per merge request and question; deriving "open" over every assignment root ever held is lifetime work.
1. **(recommended)** Holding is the assignment's presence: the transaction that records a task complete (its proofs'
   green in the eval request's recording, the merge transaction) or given back retracts the assignment; history keeps
   it. Status stays derived from proofs; only "held" becomes a current fact. The same fact ends a worker's model turns
   (CX-2) and lets its branch go (SR-BRANCH).
2. Keep every assignment; derive open per pass from proofs and closing facts (merge commit, close reason). No write,
   cost grows with root's history.
3. A stored `:seon.task/status` on the assignment. Contradicts F5.

**Q-RT2 (RT-12). Every new error signature asks.** ANSWERED (owner 2026-10-08): one question per new signature; repeats only count. A question per new signature opens a root model turn each (8
signatures on default today; repeats only count).
1. **(recommended)** One question per signature, to the steward if any, else root; root may decide one owner task for
   several (its decision names the other signatures it covers, so their questions close too).
2. One standing triage task with a query proof ("no untriaged signature"), re-assigned when the first untriaged
   signature opens. Fewer tasks; needs a proof whose green expires on new data and a re-wake rule.
3. Per signature, but batched: an opening joins an open question about the same producer namespace instead of opening
   a new one.

**Q-RT3 (RT-8). Root's instructions.** ANSWERED (owner 2026-10-08): no kinds and no root help; root's guidance comes
through its tasks' references (templates of data, RT-8).

**Q-RT4 (RT-6). Where an answer lives.** ANSWERED (owner 2026-10-08): on the assignment, so root answers several questions at once. A1 (not landed) puts the answer on the agent (`:seon.agent/answer`), one per
agent; root answers several questions at once. 1. **(recommended)** On the assignment (`:seon.task/answer`, a
component of the assignment, which is agent × task); `my.agent/answer!` finds the held task as today. 2. Keep A1's agent
answer and allow root one open question at a time (questions queue). 3. One root-side agent per question
(`root/<handle>`), each holding one task: one task per agent everywhere, but merges then need several agents with
root's merge authority.
