---
type: issue
status: resolved
severity: friction
tags: [issue, runtime, flow, test, class/p3, wave/schedule-fixture]
---

# Construct the schedule graph test from a real environment-bearing handle

## Resolution verified — 2026-09-15

`2fa2e1e17` replaced the partial handle with `with-database`, the canonical
`cluster-handle`, a real SCI context, and the environment constructor. Current
`test/seon/schedule_test.clj:348` still invokes the production graph definition
with that value and checks its mailbox, turn, and schedule procs. No production
fallback or new fixture was added in this lane.

The real constructor regression passed under armed contracts in the
three-worker `run.0j9ayQ` gate in 1,204 ms. Every schedule test passed; the
combined gate reported 36 tests / 327 assertions / zero failures or errors,
exit 0 with persistent result recording. This is a live constructor proof in
the canonical database fixture, not a static schema-only check. See the
[landing note](../../../prds/context-generation/research/fixtures-events-2026-09-15.md)
for the final platform and default boundaries.

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
