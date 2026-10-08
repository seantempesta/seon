---
title: Implementation cards (agent-loop ledger rows)
type: reference
status: active
created: 2026-10-08
---

# Implementation cards

One card per ledger row: exact files, functions with contracts, seams with `file:line` (verified at HEAD `65cf8c26d`
plus led-cs01a's uncommitted `seon.context/current-state`), the returned shape, the proof, the `--test` list, the
expected cost, what is deleted, the commit line and the dependencies. A card marked **blocked** waits on a question
below; nobody implements it until the owner (or root, citing authority) answers in this file.

## Owner questions (blocking)

**Q-A. How is an arbitrary EDN answer stored? (blocks A1, then F6)** The competency spec §4.5 names
`seon.blob/store-faithful-edn` (`src/seon/blob.clj:43`); it does not exist. Blobs are UTF-8 text only
(`seon.blob/put!` `blob.clj:325`, `seon.blob/get` `blob.clj:378`), so storing a map needs a serializer, and the
law "no printing to store a copy" forbids `pr-str`.
1. **(recommended)** konserve stores the value as data with its own serializer (Fressian): one new pair
   `seon.blob/put-value! [connection value] → digest` / `seon.blob/get-value [connection digest] → value`, keyed by
   `seon.schema/sha-256` of the value's hasch digest; an unserializable value (fn, atom) refuses
   `:seon.blob/unfaithful-error`. Uses the library; nothing printed.
2. `clojure.edn` round trip: `pr-str` into `put!`, accepted only when `(= value (edn/read-string text))`. Simple,
   but it is a printed stored copy.
3. Restrict the answer to Datahike value types (string, keyword, number, vector cells of those): no blob, no
   serializer; maps are not answers.

**Q-B. What triggers the automatic test step? (blocks F4a, then F4b-F4d)** The spec says "a pass whose effect
evidence shows a program, answer or data write". No stored fact today says a pass wrote beyond its recording: the
settlement commits the agent's defs with its receipt (`seon.turn/settle!` `turn.clj:3948`), and `transact!` /
`register!` commit separately.
1. **(recommended)** Run the step after every eval request that evaluated at least one form (test members never pass
   through the agent proc, so they never trigger it). An unchanged program costs one reach-digest lookup per proof
   (the request answers green members from the record, `seon.test/run` `test.clj:2236`). No detection code.
2. Detect a write: the branch advanced by more than the settlement's one transaction between the step's pinned
   value and the settlement basis, or the settlement asserted a program declaration. Needs a settlement flag on the
   turn report.
3. Compare `seon.test/changes` (`test.clj:1813`) before and after: exact, but whole-program digest work per pass.

**Q-C. The step's time bound default (blocks F4c).** A new dial `:seon.config.test/step-time-limit-ms`. The only
existing test bound is `:seon.test/check-time-limit-ms` 120000 (`config/default.edn:400`).
1. **(recommended)** 10000: CLAUDE.md's 10 s line; a pass never waits longer than that on tests.
2. 120000: the request bound as it is today.
3. No new dial: the step passes `:seon.test/check-time-limit-ms` through.

**Q-D. Unread items: a new `my.agent/inputs` or the existing inbox? (blocks S4-05a, then CS-01c4)**
`my.message/inbox` (`src/my/message.clj:6`, owner `seon.cluster.message/inbox` `cluster/message.clj:672`) already
answers "addressed to me, no `:seon.message/received-tx`". The spec's unread is different: delivered to the agent
and not yet shown by a committed model pass (delivery recorded on the call row), and it includes errors.
1. **(recommended)** Extend `seon.cluster.message/inbox` to the spec's rule (unread = not delivered by a model call
   row) and add errors delivered to the agent; `my.agent/inputs` is not created; the block's operation is
   `(my.message/inbox)`. One reader, no duplicate.
2. New `my.agent/inputs` composing `inbox` plus errors, keeping `received-tx` semantics.
3. Defer errors: unread is messages only (inbox as is) until the errors cut lands.

**Q-E. After `my.turn/complete` is deleted, how does an agent reply to a requester? (blocks F7)**
`seon.run/complete` (`run.clj:107`) is today the reply-delivering disposition (`:completed`, see
`turn.clj:5476-5497`, `cluster/message.clj:96`); 37 files reference the three functions.
1. **(recommended)** Replies are messages (`my.message/send`) followed by `(my.turn/wait …)`; `:completed` leaves
   `:my.turn/disposition`. F7 splits into F7a (add `my.task/complete!` bail), F7b (convert callers and tests by one
   script), F7c (delete the three functions and the `:completed` arm).
2. Keep `my.turn/complete` as the reply form; delete only `my.agent/done` and `seon.run/complete`'s duplicate.

**Q-F. Does assignment refuse an agent's own proofless task? (blocks S6-06a)** `my.task/plan!` and `step!` (steps are
shelved, not deleted) mint and self-assign a proofless `:work` task through `seon.task/assign!`
(`src/my/task.clj:108-126`); CS-01a's own test and `seon.cluster.problem-routing-test`, `seon.cluster.host-test`
assign proofless tasks too.
1. **(recommended)** `assign!` refuses every proofless or all-green assignment; the tests are given a proof in their
   fixture in the same commit; `plan!`/`step!` answer the refusal (shelved steps stop working, which the shelving
   allowed: "no current row builds on them").
2. Refuse only when the assigner is another agent (root's assignments and `claim!`); self-assignment by `own-task`
   stays proofless.
3. Delete steps first (F8, now shelved), then option 1.

## Cards

| Row | Card | State |
|---|---|---|
| CS-01b | [CS-01b](CS-01b.md) | ready after CS-01a |
| CS-01c1 | [CS-01c1](CS-01c1.md) | ready after CS-01a |
| CS-01c2 | [CS-01c2](CS-01c2.md) | ready after CS-01c1 |
| CS-01c3 | [CS-01c3](CS-01c3.md) | ready after CS-01c2 |
| CS-01c4 | [CS-01c4](CS-01c4.md) | blocked (Q-D) |
| CS-01d | [CS-01d](CS-01d.md) | ready after CS-01b, F4b |
| CS-02 | [CS-02](CS-02.md) | ready after its deps (PR-11) |
| S4-05a | [S4-05a](S4-05a.md) | blocked (Q-D) |
| F4a | [F4a](F4a.md) | blocked (Q-B) |
| F4b | [F4b](F4b.md) | ready after F4a |
| F4c | [F4c](F4c.md) | blocked (Q-C) |
| F4d | [F4d](F4d.md) | ready after F4c |
| S6-06a | [S6-06a](S6-06a.md) | blocked (Q-F) |
| A1 | [A1](A1.md) | blocked (Q-A) |
| F6 | [F6](F6.md) | blocked (Q-A through A1) |
| F7 | [F7](F7.md) | blocked (Q-E); split F7a/F7b/F7c |

## Common to every card

- Proof tests go through `seon.mcp/call` (`src/seon/mcp.clj:383`) with `test-support/with-running-agent`
  (`test/seon/test_support.clj:1194`) and read the value back with `test-support/reply` (`:757`), as
  `seon.context-test/block` already does (`test/seon/context_test.clj`, led-cs01a).
- Error branches return the installed constructor's value (`seon.error.refusal/diagnostic`), as the neighbouring code
  does, until the errors cut converts them.
- Before commit: `clj-kondo --lint src script test --cache false` with zero findings; `bin/lane commit` runs the
  check.
