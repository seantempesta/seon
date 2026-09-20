---
type: issue
status: open
severity: friction
tags: [seon.test.runner, seon.test-support, diagnostics, class/absence-as-health]
opened: 2026-09-17
---

# A fixture refusal loses its diagnostic at the test reporter

## Problem

`seon.test-support/checked-fixture-result` (`test/seon/test_support.clj:277-281`)
throws `(ex-info "Fixture setup was refused." result)`, carrying the complete
flat `:seon.error` value as ex-data. The runner's error face prints the class,
the message and the frames (`seon.test.runner/throwable-text`,
`src/seon/test/runner.clj:112`) and never the ex-data, so a gate log shows
88 identical lines "Fixture setup was refused." with no kind, layer, member or
offending value (batch 102, 2026-09-17, `tmp/orchestrator/gate-results/batch-102.log`,
retained root `run.o1y17G`; the worker's stderr and dispatch logs carry
nothing more). The diagnostic the fixture retained "complete" is dropped at
the one place a reader looks.

## Wanted

When a reported throwable carries ex-data that is a `:seon.error` value, the
error face renders that value through the error render pair (bounded by the
declared print length like the frames), so the refusal names what was
refused. The failure component stores it as `:seon.test.failure/throwable`
data, not only its message.

2026-09-20 results-reuse probe: a malformed `:seon.test/long true` declaration
was correctly refused (the declaration requires a reason string). The fixture's
recovery at `test/seon/test_support.clj:515` then called `seon.error/diagnostic`
without `:seon.error/at`, `/layer`, or `/operation`, so the construction thread
failed before delivering its completion. Evidence:
`tmp/results-reuse-everywhere/host-readers-final-fast.log`, snapshot
`run.KYcKCd`, HEAD `c79167f9e89782143772c9579ae55a20b2bf20c6`.
The declaration was corrected by the results lane; fixture recovery remains
outside that slice. Its diagnostic must preserve the original refusal and
settle the completion even when construction fails.
