---
type: issue
status: active
tags: [issue, runtime]
---

# Web-render record survives isolated operator down

## Evidence

On 2026-07-23, fresh `predfix` resets repeatedly reached pod readiness and
then failed while reconciling web-render:

```text
Refusing to replace a managed process without clean-or-force evidence.

```

The retained reset transcript is `tmp/orchestrator/predfix-up.log`. Before the
final proof, `bin/seon down` reported pod, host, and writer absent, but a
web-render owner/anchor from an earlier interrupted reset remained live and its
record was later classified as `:seon.dev.process.status/managed-process-present`.

This is downstream of the accepted schema/client reconstruction proof and did
not prevent the fresh pod from reaching `auto-boot ready`.

## Expected owner

The operator lifecycle owner must include web-render in the same recorded
clean-or-force recovery closure as pod, host, and writer. Do not bypass the
managed-process safety check or kill the child directly.

## Acceptance

- An interrupted reset followed by `bin/seon down` leaves no live or recorded
  web-render generation.
- A subsequent isolated reset can reconcile web-render after pod readiness.
- A recurring operator lifecycle regression proves the interruption boundary.
