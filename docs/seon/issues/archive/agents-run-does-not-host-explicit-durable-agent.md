---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow, pod]
---

# Persist explicit-agent task input before ensuring its runtime host

## Problem

`POST /agents/run` accepts an optional existing agent ID. Retained branches are
intentionally non-autonomous, so inherited agents exist in the database but do
not have process-local wake listeners. The original handler validated the
entity and committed a message without resuming it, causing the request to
consume its timeout while no run opened. Its first repair reversed those
operations too far: it resumed the inherited agent before committing the new
message. A reused root could therefore open a run from its previous plan and
receive the requested task only after that work was already underway.

## Acceptance

- The task message commits before `seon.agent.runtime/resume!` ensures the
  explicit agent's runtime host.
- A resume refusal returns an error but leaves the durable task available for a
  later successful resume.
- A retained non-autonomous branch can drive root through `/agents/run` without
  a manual REPL resume.

## Current evidence

The failed live root row had no open database run and a healthy pod heartbeat.
A direct `agent.runtime/resume!` returned `resumed? true`; the queued message
then opened a turn immediately. Commit `1b0bf91d` added explicit hosting. A
later exact live repair row exposed the ordering error: root spent two bounded
phases querying three agents from its previous plan and never evaluated
`agent/delegate!`, even though the new task named that function directly.

Commit `6f005b1c` now commits the message before the idempotent resume. The
regression proves this order even when hosting is refused, and the focused web
gate passes 24 tests/93 assertions. Exact rebuilt live proof remains the next
integration gate.
