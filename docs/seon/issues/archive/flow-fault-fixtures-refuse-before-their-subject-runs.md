---
type: issue
status: resolved
severity: friction
tags: [issue, flow, test, wave/test-fixture]
---

# Flow fault fixtures refuse before their subject runs

## Evidence

2026-09-08, issues-sweep: armed `bin/test-fast seon.fn.analyzer-test
seon.flow-test` ran 28 tests / 226 assertions with 23 failures and two errors.
The fault-normalization tests replaced `config/effective` with two facts,
omitting the required `:seon.config.error/max-evidence-bytes`; the armed
`seon.error/prepare` refused before normalizing. The fanout-stop observation
wrapper implemented `ReadPort` but not its declared `Channel` input contract,
so its future refused before publishing the awaited read event.

## Repair and acceptance

`test/seon/flow_test.clj` now installs sparse test dials through the real
`config/apply!` into the canonical fixture database and lets production read
the complete effective configuration. The observation wrapper forwards the
real channel's close state as well as its reads. Existing class regressions
must pass armed, including durable fault recurrence, overflow settlement, and
the stop join. Iteration and isolated Flow gate are pending; no production
contract was weakened.

## Verified resolution — 2026-09-08

The isolated paths-only Flow/cache gate at HEAD `b7e8a9143` passed 22 tests
with 201 assertions and zero failures/errors. All 21 Flow tests ran, including
the command-protocol refusal and fault lifecycle classes. The gate removed its
successful root `tmp/test-runs/run.AY96Oc`.
