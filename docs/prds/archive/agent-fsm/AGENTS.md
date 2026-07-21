---
type: orchestrator
status: completed
tags: [orchestrator, prd, agent, architecture]
---

# Agent FSM — completed chunk index

## Current state

Completed and merged 2026-07-02. This PRD established the loop/run/turn model,
bounded retries, database work fences, schedule/message triggers, capability
function conventions, and the first durable planning/context foundation. Later
chunks changed names and implementations; active source plus
`docs/seon/architecture/` are authoritative.

## Durable findings

- One frozen database value is threaded through a turn.
- Run identity and the in-transaction fence prevent superseded work from
  committing.
- Timeouts and provider failures become values; nothing may wedge the loop.
- Big text lives in content-addressed blobs; queryable facts and refs live in
  the database.
- Planning is the current `my.plan` tree, not the removed todo or markdown
  reconciliation paths.
- The web UI route table is database data. `/` is root, `POST /agents` creates
  an agent, and `/agent/{id}` is its page. There is no GET `/agents` roster.
- Inspect AI is the agent/model harness. The retired gym is historical only.

## Entry points

- `roadmap.md` — historical shipped evidence; its old active label is retired.
- `docs/seon/architecture/agent-runtime.md` — current runtime contract.
- `docs/seon/architecture/data-model.md` — current durable facts.
- `docs/prds/runtime-reliability/roadmap.md` — current work ledger.
