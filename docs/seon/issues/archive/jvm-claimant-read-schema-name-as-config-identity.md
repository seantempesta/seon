---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, database]
---

# Read claimant limits from the config singleton identity

## Problem

The JVM eval path pulled configuration from
`[:seon.config/id :seon.config/singleton]`. The schema is named
`:seon.config/singleton`, but the singleton entity's maintained identity value
is the string `"cluster"`. Every post-LLM eval therefore failed as if its two
claim-driver limits were absent.

## Evidence

The first claimant2 transport-success run persisted a status-200 attempt and
reply blob, then faulted:
`The JVM claimant limit :seon.config.claim-driver/invocation-deadline-ms is
unavailable`. A database query found the only config identity as `"cluster"`.
The source pull used the schema name as the lookup value.

## Owner

`seon.agent.driver.host/invocation-configuration!` owns the JVM claimant's
per-eval config acquisition.

## Resolution

Commit `fdba88aad` uses the established lookup ref
`[:seon.config/id "cluster"]` and adds a regression over the exact pull
request. The rebuilt claimant2 drive passed this acquisition and reached the
later exact-execution-plan boundary. The combined focused writer proof is
12 tests / 52 assertions.

## Acceptance

- The JVM claimant reads both required limits from the config singleton.
- Missing limits still return the existing loud flat error.
