---
type: issue
status: active
tags: [issue, runtime, operator]
---

# bin/seon status fails on the operator stall fact the reset consumed fine

## Evidence

Immediately after `bin/seon cluster reset default` completed to full
readiness (exit 0, 2026-07-23 late, tmp/orchestrator/ckpt-default-reset4.log),
`bin/seon status` failed:

```text
✗ Missing required operator limit :seon.config.operator/pod-boot-stall-timeout-ms.
```

The fact exists in config/system.edn:197 and the reset/up path resolved it.
The status path apparently resolves configuration differently (cached
manifest? different selection?), so it can disagree with the operate path —
a two-config-paths smell.

## Expected owner

`script/seon/dev/` config resolution — status and up must resolve the SAME
manifest by the SAME mechanism.

## Acceptance

- status succeeds after a green reset with no extra environment.
- One resolution path; a regression proving status and up read identical
  resolved configuration.
