---
type: issue
status: open
tags: [web, runtime, testing, issue]
severity: blocker
---

# Agent-run settlement used wrong reactive database key

## Evidence

At frozen HEAD `15c8748d` on 2026-07-20, the first real
`POST /agents/run` Stage 1.6 probe returned HTTP 500 before the proof agent ran.
Malli rejected the closed `seon.reactive/observe!` request because
`seon.web.serve/await-agent-task-settlement!` passed the immutable database
value as `:seon.db/db`; the registered request owns that field as
`:seon.reactive/db` with value schema `:seon.db/db`.

The returned error names `:malli.core/extra-key` at `[0 :seon.db/db]`. Default
remained ready and no crash or data loss occurred. The exact frozen source
already passed 1,352 CLJS tests / 6,305 assertions and the real-child writer
gate 1 / 27; only the live checkpoint is invalidated.

## Expected owner and acceptance

The existing `/agents/run` settlement helper must pass the database under the
reactive request's owned key and retain the exact immutable value. Its focused
test must assert that key/value before simulating notifications.

Acceptance requires the focused serve gate, a new exact-HEAD restart, both
frozen code gates, and a successful real `/agents/run` response with an integer
rendered transaction before later Stage 1.6 observations count.

## Implementation progress

Commit `baa21cee` passes the database through `:seon.reactive/db` and asserts
the exact acquired value at the focused boundary. `seon.web.serve-test` passes
26 tests / 104 assertions with zero failures or errors. The issue remains open
only for the new frozen artifact and real `/agents/run` acceptance above.
