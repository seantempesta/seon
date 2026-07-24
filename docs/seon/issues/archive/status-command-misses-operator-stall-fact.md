---
type: issue
status: resolved
severity: blocker
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

## Resolution

Resolved by `8953b45e3`.

`status` passed the unresolved result of `seon.dev.config/load!` directly to
the process status derivation. `up` and `cluster reset` first called
`seon.dev.config/select-manifest`, which selects the retained applied manifest
for an existing database and attaches its resolved configuration and launch
envelope. The retained manifest contained the operator stall fact; status
simply never read it.

`seon.dev.cli/status!` now enters the same `select-config` path as `up`, with
the same implicit manifest selection. The
`up-and-status-use-the-same-resolved-configuration` regression proves both
commands pass the identical resolved configuration to their downstream
operations.

The focused operator gate passed 44 tests and 122 assertions. Immediately
afterward, `bin/seon status` read the default cluster successfully with exit
zero. The retained transcript is
`tmp/orchestrator/statusfix-gate.log`.
