---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, flow, wave/runtime-boundary-refactor]
---

# Agent-facing host crossings can wait without a bound

## Problem

Three direct agent-turn crossings preserve their completion events but do not
add a loud last-resort bound. A render proc that never replies, a capability
handler stuck in a host call, or a shell capture thread that never terminates
can therefore park the agent forever.

## Evidence

- `src/seon/render.clj:635-639` blocks first putting a context request and then
  taking its promise-channel reply.
- `src/seon/effect.clj:358-378` adopts the SCI interrupt arm in a `FutureTask`
  but waits with un-timed `.get`; a host call need not re-enter SCI to observe
  the arm.
- `src/seon/shell/jvm.clj:94-117,299-305` joins stdout/stderr capture threads
  without a bound after the separately bounded child-exit path.

`src/seon/render.clj` was modified-uncommitted by another lane during the
2026-08-13 census, so the census lane did not edit through that boundary.

## Acceptance

Each crossing retains its exact completion event and applies a declared bound
at the admission seam. Expiry returns or commits a flat `:seon.error` naming
the agent/run, operation, member, and configured bound; it also interrupts or
closes only the exact owned task/channel. Regressions inject a never-replying
render proc, host handler, and capture task and prove every agent turn returns
within its declared bound.
