---
type: issue
status: open
severity: friction
tags: [issue, runtime, flow, test, class/p3, wave/schedule-fixture]
---

# Construct the schedule graph test from a real environment-bearing handle

## Problem

The schedule graph proof calls `agent/graph-definition` with only a schedule
channel and agent id. The production constructor now scopes the environment
carried by the cluster handle, so the test fixture constructs an impossible
request and throws before checking the third proc.

## Evidence

At clean commit `48eb25ab7`,
`seon.schedule-test/schedule-remains-the-third-proc-in-the-agent-graph`
errored in `seon.env/scope`: “Scoping requires an environment; there is nothing
to narrow.” The test request at `schedule_test.clj:191-195` omits
`:seon.env/environment`; production `agent/graph-definition` immediately calls
`env/of` then `env/scope`. Evidence:
`tmp/full-gate-2026-08-10b.log:3962-4002`.

## Owner

Suspected owner: `seon.schedule-test` and the canonical environment-bearing
agent-handle fixture, not a fallback in `seon.env`.

## Acceptance

- The proof obtains its request from the same canonical handle constructor as
  production or a schema-valid minimal fixture.
- It still proves mailbox, turn, and schedule are the three agent procs.
- No production constructor accepts a missing environment for test convenience.
