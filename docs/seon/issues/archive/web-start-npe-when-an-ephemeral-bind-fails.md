---
type: issue
status: resolved
severity: friction
tags: [issue, render, web]
---

# A failed ephemeral bind NPEs instead of saying what happened

## Problem

`seon.render.web/start!` handles a taken port by rebinding on port 0, but the
fallback is guarded by `(when-not (zero? wanted) …)`. When `wanted` is already
0 and the OS refuses the bind, that `when-not` yields nil, the `[server
fell-back?]` destructuring binds `server` to nil, and the next line calls
`(http/server-port nil)` — so a bind failure surfaces as a
NullPointerException with no mention of a port.

The taken-port path is deliberately loud and honest about both numbers; this
one path throws away the one fact a reader needs.

## Evidence

`src/seon/render/web.clj`, `start!`:

```clojure
[server fell-back?]
(try
  [(bind! wanted) false]
  (catch java.net.BindException _
    (when-not (zero? wanted)
      [(bind! 0) true])))
bound (http/server-port server)
```

Pre-existing; unrelated to the F2 render conversion, which changed the service
map this function receives but not its binding logic.

## Acceptance

- an ephemeral bind that fails throws an ex-info naming the attempted port and
  carrying the BindException as its cause, never an NPE;
- the taken-port fallback behaviour and its two-number report are unchanged.

## Triage 2026-07-29

**DRAFT-SURFACE — render walk.** The nil destructuring remains in current
`seon.render.web/start!`, but the afternoon ruling forbids hardening or
test-fencing the draft render family; carry this failure mode into the eventual
web-render design.

## Schedule 2026-07-29

**RUNNING — `small-correctness-batch`.** The final owner schedule explicitly
pulls this data-losing failure classification into the bounded correctness
batch despite the earlier draft-surface hold.

## Resolution

Resolved by `365ad9489` (`Make render readiness and bind failures explicit`).
When a requested ephemeral bind fails, `start!` now shuts down the worker
executor and throws `ExceptionInfo` with
`:seon.render.web/attempted-port 0`, preserving the original
`BindException` as its cause. The nonzero taken-port path still retries port 0
and reports both requested and bound ports.

`failed-ephemeral-bind-preserves-the-bind-failure` injects the bind exception
at http-kit's boundary and proves the failure is the named ex-info rather than
a `NullPointerException`. The existing real-socket
`a-taken-port-serves-anyway-and-says-so` regression continues to prove the
fallback path.

Focused proof on 2026-07-29:

```text
bin/test seon.render.web-test
Ran 32 tests containing 125 assertions.
0 failures, 0 errors.
```
