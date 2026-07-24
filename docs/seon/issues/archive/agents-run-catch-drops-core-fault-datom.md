---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, web, runtime]
---

# Persist core faults caught by the agents-run boundary

## Problem

The `/agents/run` terminal catch logs an exception and returns HTTP 500, but
does not pass the failure through `seon.error/record!`. A core instrumentation
fault at final evidence is therefore console-only: the pod can stay ready, yet
the database has no fault datom for later diagnosis. This violates the
failures-as-data and core-fault persistence contract.

## Evidence

Before the 2026-07-24 `real-mails-fix` drive, the default database had four
historical `:seon.error/fault :core` entities, latest transaction `536871709`.
The agent was created at basis transaction `536871841`.

At 2026-07-24T05:31:15.736Z, the `/agents/run` catch logged a real
`:malli.core/invalid-output` from `seon.ai/config-pull-pattern`. At basis
transaction `536871857`, the core-fault count remained four and a query for
core-fault datoms after `536871841` returned empty.

Commit `762424f91` routes the terminal catch through
`seon.error/record!` with `:seon.error/fault :core` before logging and
constructing the HTTP 500 response.

Focused proof exercises the real injected persistence hook: the caught
rejection produced exactly one core-fault transaction projection before the
response assertions. `bin/test-cljs
--test=seon.web.serve-test/agent-run-core-fault-persists-before-http-500`
passed 1 test / 4 assertions with zero failures/errors. The full transcript is
`tmp/orchestrator/faultdatom-gate.log`. Per lane constraint, no cluster was
started.

Without a restart, all five processes retained their generations,
`/_seon/ready` returned HTTP 200, `/` returned HTTP 200, and a subsequent
`/_seon/operator/product-evidence` database request returned HTTP 200 at basis
`536871857`. Thus the UDS frame-ordering fix survived, but the required fault
datom did not persist. Full evidence is in
`tmp/orchestrator/redrive2-gate.log`.

## Owner

`seon.web.serve/handle-agent-run!` owns the external boundary. It must classify
and record caught core failures through the one `seon.error` mechanism before
returning the bounded HTTP error.

## Acceptance

- An injected instrumented final-evidence failure records exactly one
  `:seon.error/fault :core` entity before the HTTP 500 is observable.
- The fault retains the function symbol and Malli path without secret or
  unbounded values.
- The pod stays ready under the configured policy, and a subsequent healthy
  database request succeeds without frame desynchronization.
