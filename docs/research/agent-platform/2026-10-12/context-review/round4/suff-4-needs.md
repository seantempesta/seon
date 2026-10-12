---
title: "Sufficiency review (needs-task): suff-4 on root-offers-a-sent-message"
type: research
status: complete
created: 2026-10-12
---

# Sufficiency review, lens needs-task (lane suff-4)

Task: `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record`. Read only the first-call context file `4-needs-root-offers-sent-message.md`. No Seon source or docs.

## 1. Plan to done

1. `(in-ns 'seon.cluster.message)`. KNOWN: the step's start form.
2. `(my.program/source 'seon.cluster.agent/db<->flow)`. KNOWN form. The context cuts its body at `+4611`, and the offer and the recording both sit in that cut part.
3. `(my.program/source 'seon.cluster.message/undeliverable)` and `(my.program/source 'seon.cluster.message/prepared-message)`. KNOWN forms. Their bodies show as `…1`.
4. `(my.program/tests-reaching 'seon.cluster.message/send!)`. KNOWN form. It finds which tests reach `send!`, but not the red proof names. GUESS: the tagged deftest is in `seon.cluster.message-test`, under a name I cannot see.
5. `(my.program/source 'seon.cluster.message-test/<name>)`. GUESS on `<name>`; I would try `(my.program/search :seon/tasks)` or the intent's quoted test name.
6. `(defn send! {:malli/schema [:=> [:cat :my.message/message :seon.db/connection :seon.agent/id] [:or :seon.message/accepted-result :seon.error/value]]} [request connection agent-id] ...)`. The contract is KNOWN from the context. The body is a GUESS: candidate, prepared-message, then the transaction through `send-call`, with no `async/offer!`.
7. `(in-ns 'seon.cluster.agent)` and a `db<->flow` that records the message by `:seon.message/id` and then makes one `offer!` to the recipient's channel. GUESS: the body is cut, and the intent names `:seon.agent/inbox`, which does not resolve. I would take `seon.cluster.agent/channels` as the map.
8. `(defn undeliverable ...)` recording `:seon.message/undeliverable` on main. GUESS: the `cause` member is elided from its schema-form.
9. The proofs run by themselves. Their status is read from `:seon.context/proof-status`, which is not in this context.

## 2. Calls needed

About 8 calls before the first fix, and about 5 of them exist only because of context gaps: the cut `db<->flow` body, the elided `undeliverable` and `prepared-message` bodies, the missing proof names and status, and the unresolved `:seon.agent/inbox`.

## 3. Blocking gaps

- **Proofs block absent.** The help text promises `:seon.context/proofs` with `:seon.test/source` and `:seon.context/proof-status`, but the first call has no proofs key. The intent names the red deftests only in prose ("the deftest tagged to this task", `an-undeliverable-message-is-answered-as-data`), with no var names. Should appear: one entry per tagged deftest, with its source and status. I cannot run red to green without them.
- **Needed task status.** The intent says "After M13", i.e. `seon.turn/a-held-task-opens-no-model-turn`. Its must-read entry shows only its intent as filed, with no status, proof status or evidence. Should appear: M13's task status and its proof status in that entry.

**What the M13 entry tells me.** It tells me the target state: delete `seon.cluster.agent/agents-to-start`, the graph start in `seon.cluster.host/start!`, and the held clause. Those symbols still resolve in this context, which is main at HEAD 8d14faeee, so the deletions are not on main. It does not tell me whether M13 is finished, whether its proof is green, or whether its branch can merge. I can rely on the first two points. I cannot rely on the rest.

Not blocking: `:seon.agent/inbox` is unresolved, but `seon.cluster.agent/channels` gives the same map. The `wake/` alias references fail to resolve, but the fully qualified `seon.cluster.wake/route!` and `delivery` resolve. A bare alias is therefore a resolver defect, not a gap for this task.

## 4. Wasted

- `agent.clj` section refs (`seon.flow/var-process`, `seon.turn/turn`, `seon.flow/submit!!` and unresolved `:seon.agent/*` attributes): about 400 tokens, not needed for the change.
- `message.clj` section refs (`:seon.render/html`, `str/blank?`, `my.message/inbox`): about 400 tokens, unrelated to the intent.
- `seon.cluster.agent/mailbox-step` with its schema cut at `+1548`: about 650 tokens, not in the intent's files.
- `seon.db/db`, `seon.db/transact!`, `seon.env/of`, `seon.fault/fault!`: about 200 tokens. Not needed to write `send!`.
- Help text "Reading your context": about 350 tokens. It describes proof blocks that are absent.

Total: about 2,000 of 8,157 estimated tokens.

## 5. Verdict

CAN DO WITH SEARCHING. The intent, `send!`, `send-call` and the `db<->flow` references are enough to write the change. The proof names and their status are missing, and M13's status is unknown, so about five extra calls are needed to find the proofs and check the precondition.
