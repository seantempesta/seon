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
