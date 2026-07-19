---
type: issue
status: open
severity: blocker
tags: [issue, agent, flow, pod]
---

# Host an explicit durable agent before task intake

## Problem

`POST /agents/run` accepts an optional existing agent ID. Retained branches are
intentionally non-autonomous, so inherited agents exist in the database but do
not have process-local wake listeners. The handler validated the entity and
committed a message without resuming it, causing the request to consume its
timeout while no run opened.

## Acceptance

- An explicit existing agent is resumed through `seon.agent.runtime/resume!`
  before task message intake.
- A resume refusal returns an error and commits no message.
- The request timeout starts only after the durable agent is hosted.
- A retained non-autonomous branch can drive root through `/agents/run` without
  a manual REPL resume.

## Current evidence

The failed live root row had no open database run and a healthy pod heartbeat.
A direct `agent.runtime/resume!` returned `resumed? true`; the queued message
then opened a turn immediately. The HTTP task owner now performs that same
resume before intake. Focused web proof passes 22 tests/87 assertions. A fresh
live root row remains the archival gate.
