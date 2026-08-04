---
type: issue
status: open
severity: friction
tags: [issue, database, diagnostics]
---

# Keep expected Datahike rejections from logging full writer exceptions

## Problem

An expected transaction refusal is returned to the caller as a useful flat
error value, but Datahike still logs its complete writer exception and stack.
This makes focused test and operator output noisy and exposes the same raw
datom tuples and entity IDs the agent-facing value now humanizes.

## Evidence

On 2026-08-04, `bin/test seon.db-test` exercised the intentional
`:transact/unique` regression. The suite passed, but Datahike emitted both the
transaction error and the full `:datahike/write-error` exception trace through
`datahike.writer/create-thread` before `seon.db` received the refusal.

## Owner

The Datahike writer logging boundary and Seon's database invocation policy own
the distinction between expected transaction refusals and unexpected writer
faults.

## Acceptance

An expected Datahike refusal remains a flat, structured `:seon.error` value
without printing a complete writer stack, while unexpected writer faults stay
loud and retain their diagnostic trace.
