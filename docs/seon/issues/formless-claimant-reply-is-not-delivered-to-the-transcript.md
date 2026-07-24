---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime]
---

# Deliver a formless claimant reply through the transcript

## Problem

The JVM claimant now advances an error-free reply with no dispatchable forms
directly from `:reply-ready` to `:evaled`, and publication closes its turn
`:done`. That reply is durable only as the turn's
`:seon.agent.turn/reply-blob`; it does not become a
`:seon.agent.message` entity and is absent from the ordinary transcript and
the `/agents/run` final reply.

The exact no-dispatch case therefore avoids the former exact-plan rejection
but cannot satisfy final-answer delivery. A terminal synthesis can be
`:published`/`:done` while still being invisible as a user-directed message.

## Evidence

- `seon.agent.driver.host/no-dispatch-reply?` recognizes the empty program,
  and `eval-step!` advances `:reply-ready` directly to `:evaled` without an
  execution plan or eval receipt.
- `seon.agent.turn/publish-phase!` advances the turn from `:evaled` to
  `:published` and writes `:seon.agent.turn/status :done`, but it does not
  transact a message for the reply.
- `seon.agent.ctx.transcript/ordered-events` constructs transcript events
  from message and eval rows; it does not project turn reply blobs.
- `seon.web.serve/final-agent-task-result` derives `.reply` only from
  agent-to-user `:seon.agent.message/content` rows.

The 2026-07-24 recovery drive created default-cluster agent
`old-islands-dig`, then was rejected before a model attempt because a
concurrent source lane made canonical program generation unavailable and
drained the pod. The interrupted gate is recorded in
`tmp/orchestrator/lifecycle-redrive-gate.log`. The live attempt did not
falsify the source contradiction above: the requested combination of a
formless final reply, a final message entity, and ordinary transcript
delivery has no producing path in the current source.

## Owner

The no-dispatch publication boundary shared by
`seon.agent.driver.host/eval-step!`, `seon.agent.turn/publish-phase!`, and
`seon.agent.ctx.transcript` must define one delivery path for successful
formless replies. Do not add a second transcript or side channel.

## Correction in progress

The 2026-07-24 no-roots class fix removes the driver's
`no-dispatch-reply?` pre-classifier. `plan-execution` remains the one program
classifier: its existing `:no-roots` result now maps to the
`:no-dispatch` disposition. The host retains the exact reply blob content and
uses `seon.agent.message/message-transaction-for` plus
`seon.db.id/allocate!` to transact the ordinary agent-to-user message in the
same fenced transaction that advances `:reply-ready` to `:evaled`.
Publication then performs its existing `:evaled` to `:published`/`:done`
transition. Unresolved executable roots still produce steering.

The portable planner and configuration selections pass 29 focused tests with
176 assertions. The focused `bin/test-writer` re-drive is pending because its
required current compiled program artifact is absent; this lane may not start
a cluster to produce one. The next source-frozen default rebuild must run the
writer regression and then repeat the live lifecycle drive. That live
re-drive remains required even after the writer regression passes.

## 2026-07-24 resumed live re-drive

The rebuilt default cluster carried `83fd9792d`, `87b7637bd`, and
`e74cae74a` at HEAD `ab0913794`. The first fresh agent failed closed before a
model call because the new claimant timeout fact had not been applied to the
config singleton. `bin/seon config apply config/system.edn` reconciled the
published configuration without a reset or restart, and a second fresh agent,
`bright-candies-relax`, began the clean acceptance run.

That run persisted the requested root plan plus all three children at basis
transaction `536874801`, then registered the memory id and ordinal schemas at
transactions `536874817` and `536874834`. All five DeepSeek attempts were
`:success` with response status 200. The fifth reply never reached evaluation:
the exact-plan analyzer threw an uncaught `NullPointerException` while
resolving a prose symbol before the valid schema form. Run `bajoa6encx81`
stayed open and claimed with turn `m7mia62w9xhq` `:running` at
`:reply-ready` until the 900-second request deadline. Deadline cleanup then
closed the run `:superseded` and made the turn `:published/:interrupted` with
an error, never `:done`.

The final memory write/read and bare synthesis were therefore never reached.
The exact final-content query returns no message, and no final
`:published`/`:done` turn exists. This issue stays open: the source-level
delivery correction is present, but the required integrated live proof is
blocked by
[[jvm-claimant-rejects-visible-reply-without-exact-execution-plan]]'s
planner exception and nothing-wedges failure. Evidence is appended to
`tmp/orchestrator/lifecycle-redrive-gate.log`.

## 2026-07-24 blocker correction

The intervening planner exception is corrected at its class boundary:
unresolved value symbols now become `:unresolved-symbol` steering data, and a
claimed phase throw now enters the one immediate fenced settlement path. The
focused portable planner gate passes 17 tests / 82 assertions, and the
claimant writer gate passes 11 tests / 56 assertions, including terminal
turn/run/custody/fault datoms in the same drive call.

This does not close the delivery issue. Its source correction still requires
the original source-frozen live re-drive to prove the final formless reply is
delivered once through the ordinary message/transcript path.

## Acceptance

- A successful plain synthesis with no dispatchable forms bypasses exact-plan
  enforcement and has no eval receipt.
- Its turn advances `:reply-ready` → `:evaled` → `:published`, never
  `:evaling`, and closes terminal `:done` without an error.
- The exact synthesis is delivered once as an agent-to-user message and
  appears in both the ordinary transcript and `/agents/run` final reply.
- The run closes without a current-run ref, claimant, running turn, or open
  attempt.
- A genuinely unresolved executable form remains fail-closed.
