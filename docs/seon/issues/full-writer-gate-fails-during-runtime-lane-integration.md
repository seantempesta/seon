---
type: issue
status: open
tags: [issue, database, testing]
severity: blocker
---

# Full writer gate fails during runtime lane integration

## Evidence

The U5 checkpoint on 2026-07-23 ran 387 tests / 2,981 assertions and reported
288 failures plus 14 errors. The first failures are in
`seon.db.writer-integration-test/expected-basis-is-enforced-inside-the-serialized-writer`;
later JVM host fixtures return nil results and
`Cannot invoke "Object.getClass()" because "x" is null`.

The complete transcript is
`tmp/orchestrator/u5-gate-writer.log`. The independently run U5 regressions
remain green: the real writer interest restore test passes 9 assertions and
the JVM `/data` feed test passes 12 assertions. The failing source owners
(`seon.db.writer`, `seon.db.executor`, and `seon.execution`) are protected by
concurrent U3/U4 lanes, so U5 did not modify or work around them.

The same checkpoint's complete operator gate ran 308 tests / 1,745 assertions
with zero failures and five errors, all in `seon.dev.cluster-test`: Babashka
attempts to `slurp` a `sun.nio.fs.UnixPath` for the repository `AGENTS.md`.
The U5-focused process and CLI namespaces pass 114 tests / 520 assertions.
The complete operator transcript is `tmp/orchestrator/u5-gate-operator.log`.

The frozen U2 checkpoint later ran 389 tests / 3,000 assertions and reported
279 failures plus 13 errors
(`tmp/orchestrator/u2-gate-writer.log`). U3's expected-basis work is now green;
the dominant remaining host failure is the U1 guard-policy integration. Fresh
host writer fixtures omit the five new guard facts. The acquisition boundary
also accepted an absent row vacuously and passed nil fuel to the guard; U2
fixed that separate runtime defect and added a focused policy regression. The
fixtures now need the same shared complete-policy treatment as the archived
value-sampling-policy repair, while production stays fail-closed.

## Acceptance

- The first expected-basis writer integration regression passes.
- JVM host invocation results retain their expected values and structural
  errors.
- `bin/test-writer` passes from one coherent source checkpoint.
- The five cluster operator tests accept the path representation used by the
  current shared operator config and the complete operator gate passes.
