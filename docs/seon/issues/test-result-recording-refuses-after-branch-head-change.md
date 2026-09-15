---
type: issue
status: open
severity: friction
tags: [issue, test, database]
---

# Test result recording refuses after a branch head changes

## Problem

The platform gate completed its tests but could not record persistent results.
The recording boundary returned a branch-head precondition failure.

## Evidence

2026-09-15, snapshot HEAD `466f562e6`, command:
`bin/test --paths src/seon/db.clj test/seon/db_test.clj --platform`.
The terminal output includes:

```text
bin/test: persistent results NOT recorded: :seon.test.runner/persistent-results-recording-failed Branch head changed before force-branch!.
```

Exit was 1. The independent six fixture failures are recorded in
[test-launcher-fixtures-omit-required-helpers.md](test-launcher-fixtures-omit-required-helpers.md).
The responsible competing operation has not been identified; this is evidence
of a recording refusal, not an attribution to another process or lane.

A subsequent final database gate on the same HEAD passed 45 tests / 326
assertions / 0 failures / 0 errors but reported a different unavailable
recording observation:

```text
! operator event silence backstop fired: prepl response was silent for 30000 ms; config=:seon.config.operator/event-silence-backstop-ms
bin/test: persistent results NOT recorded: :seon.fresh-operator/prepl-response-silent The prepl response went silent for 30000 ms.
```

That launcher exited 0. Its protected runner implementation still allows
recording failures to leave the exit code successful; the test-provenance
lane's uncommitted change requires durable recording. The timeout and head
precondition failure are distinct observations; a shared underlying cause
has not been established. The completed assertions are not proof that the
results were published.

## Owner

`seon.test.runner` result recording and the publication operation it invokes.
These files are protected by the concurrent test-provenance lane.

## Acceptance

Reproduce against the canonical published fixture with controlled concurrent
head advancement; preserve the expected-head check and commit successful
test evidence through the existing writer mechanism. A recording failure
must remain a failed gate, never successful absence of result facts.
