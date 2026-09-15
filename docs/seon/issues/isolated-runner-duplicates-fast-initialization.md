---
type: issue
status: open
severity: friction
tags: [issue, test, performance, class/n11, wave/contract-gate]
---

# Isolated runner duplicates the fast initializer

## Problem

`seon.test.runner/initialize-contracts!` duplicates the implementation in
`seon.test.arm`. Delegating only `arm-contracts!` left acquisition duplicated:
fixing the fast initializer does not change the isolated worker.

## Evidence

On 2026-09-15, default PID 69622 ran the real initializers through MCP JVM
probes. The unchanged runner constructed two packaged projections; the
revised arm initializer constructed one and carried its identical value.
Both installed wrappers for 994 of 994 armable program Vars. Exact numbers
and the verification boundary are in
[the landing note](../../prds/context-generation/research/slow-surfaces-plan-2026-09-15.md#landing).

The startup-and-hook-waste assignment explicitly limited `runner.clj`
changes to log lines. Its functional delegation is therefore a requested
scope extension, not part of that slice. No test JVM was launched.

## Owner

`src/seon/test/runner.clj` should call the existing
`seon.test.arm/initialize-contracts!`; no new initializer is needed.

## Acceptance

Extend `seon.test.runner-test/initialization-acquires-one-projection` to
exercise both launcher entry points. Each initializes through exactly one
packaged projection acquisition and covers every armable program Var.
Verify the isolated worker's own timestamped gate log.
