---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, web]
---

# Agent birth used root count as the pull result budget

## Problem

The known-agent, initial-agent, and spawned-agent acquisitions set
`seon.db/max-results` to the number of requested entity refs. Datahike charges
that budget while retaining nodes in each pull result, so an ordinary
multi-attribute configuration entity exceeds a limit of one or three before
agent creation can transact.

## Evidence

After the complete maintained test gate and a clean supervised restart,
`POST /agents` returned HTTP 500 in 7 ms. The pod log reported
`datahike query-results budget exceeded` with two observed results and one
allowed result. `reference-code/datahike/src/datahike/resource.cljc` shows that
`charge-result-node!` increments the `max-results` budget during pull result
construction. A completed query-cache value can instead be certified by its
top-level count, which can hide the cold-path defect.

## Owner

The three agent-birth acquisitions in `seon.agent` own their bounded pull
requests. They share one conservative result limit and retain their independent
one-MiB shallow result-weight limit.

## Acceptance

- Known-agent reconciliation, initial-agent reconciliation, parentless birth,
  and delegated child birth use a bounded pull result allowance rather than the
  number of root refs.
- Focused tests assert the request limit at the existing creation and
  delegation boundaries.
- A real uncached `POST /agents` creates and resumes one agent, whose page and
  database facts are observable.
- Fresh and converged initialization remain idempotent and race-free.

## Resolution

Commit `5473d66d` gives all three birth acquisitions one bounded Datahike pull
result allowance while retaining their existing result-weight bound. Focused
multiagent proof passes 5 tests / 38 assertions. A real uncached `POST /agents`
created `solid-worms-punch` in 287 ms; its database-derived page and gzip feed
rendered after the separately tracked renderer correction.
