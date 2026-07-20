---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Yield hot committed-report sources between bounded batches

## Resolution

Datahike `d9765276cd8d0778f39e93046c2d59b8c2fa8ff2` adds bounded,
nonblocking `poll-batch!` and identity-fenced, idempotent `requeue-ready!` to
the existing committed-report source. Polling never schedules; the Seon owner
requeues only after its serialized delivery job, or immediately when admission
rejects before any report is consumed.

The relevant dependency proof passes 102 tests and 840 assertions. It covers
hot-A/cold-B tail fairness, exact batch limits, rejected admission, one-token
idempotency, gap persistence, close/reopen ABA fencing, release, and shutdown.
Datahike creates no thread, Future, callback, or sleep for the handoff.

## Original problem

Taking a ready Datahike report source transferred its sole readiness token. A
consumer that drained the source completely could be monopolized by one
continuously written database; a consumer that stopped after a bounded number
of reports could strand the remaining reports without another readiness
transition.

## Evidence

`take-ready!` removes the source token, while ordinary `poll!` only returns the
source to an empty state after its last report. Neither operation expressed a
bounded ownership handoff that could yield a still-ready source to the global
tail.
