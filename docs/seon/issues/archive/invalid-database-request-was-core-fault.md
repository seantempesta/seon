---
type: issue
status: resolved
severity: reliability
tags: [issue, database, agent]
---

# Invalid database request was a core fault

## Problem

An agent that passed the unresolved Promise from `seon.db/db` as the first
argument to `seon.db/query` produced a locally invalid protocol request. The
client rejected it before transport but labeled the resulting ordinary value
`:core-bug`, allowing an agent-authored call mistake to enter core-fault policy.

## Resolution

Local request validation now returns `:user-input`. Invalid authority responses,
transport failures, missing sessions, and impossible database histories remain
core faults. A public-facade regression evaluates the observed bad call shape,
asserts the error classification, and proves that no request reaches the writer.

Resolved by the commit that added this note. Live evidence on 2026-07-19 also
proved that the execution child remained alive and completed a later valid
query after the rejected call.
