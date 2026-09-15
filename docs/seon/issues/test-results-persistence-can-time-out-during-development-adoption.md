---
type: issue
status: open
severity: friction
tags: [issue, test, operator, wave/schema-audit]
---

# Successful isolated test results can fail to persist in the shared operator

Observed 2026-09-15 in the schema-audit path-limited gate: 152 tests,
1,373 assertions, zero failures/errors, exit 0. The runner then printed:

```
! operator event silence backstop fired: prepl response was silent for 30000 ms; config=:seon.config.operator/event-silence-backstop-ms
bin/test: persistent results NOT recorded: :seon.fresh-operator/prepl-response-silent The prepl response went silent for 30000 ms.
```

Development adoption was running concurrently. This is timing evidence,
not a proven cause. The isolated successful root `run.QYAZCA` was removed
by the runner. The gate's assertions passed, but its shared durable result
facts were explicitly unavailable. The test-provenance lane owns the
currently edited runner and source publication paths; schema-audit did not
modify them.

## Acceptance

A successful gate either confirms its durable result facts or retains
replayable result evidence with a typed persistence failure. Verify this
while development adoption is active; elapsed silence is not confirmation
that a write succeeded or failed.
