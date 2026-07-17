---
type: issue
status: resolved
tags:
  - runtime
  - process
  - web
---

# Execution host was not configured at runtime start

## Failure

The Bun pod reached readiness without configuring the agent execution-child
host. Opening an agent feed therefore passed `nil` as the compiled execution
artifact digest. Instrumentation rejected the child invocation, and the
Datastar encoder serialized the returned Promise as `[object Promise]`.

The execution host tests configured their fixture directly, so they did not
exercise the missing application startup connection.

## Resolution

`seon.client/start-runtime-impl!` now configures the one execution-child host
from the already validated process launch descriptor before it resumes agents
or opens the web UI. The child runtime uses the same `SEON_JS_RUNTIME`
selection as the supervised pod and defaults to Bun.

## Acceptance

- Client initialization and execution-host tests pass under Bun.
- A clean supervised restart opens the root feed with rendered HTML from a
  digest-verified execution child; no Promise text or missing digest appears.
