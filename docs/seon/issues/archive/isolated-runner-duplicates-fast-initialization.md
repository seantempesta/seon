---
type: issue
status: resolved
severity: friction
tags: [issue, test, performance, class/n11, wave/contract-gate]
---

# Isolated runner duplicates the fast initializer

Resolved by `db7e653ca`: the worker delegates to the existing arm initializer,
and its unused arming-decision copy is deleted. The regression exercises both
entry points using the real arming owner and instrumentation-preservation
fixture. Canonical gate verification is requested from the orchestrator.

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
[the landing note](../../../prds/context-generation/research/slow-surfaces-plan-2026-09-15.md#landing).

The initial assignment limited `runner.clj` to log lines; the owner then
explicitly authorized delegation. On default PID 69622 at **21:31:53Z**,
both real entry points constructed **1** packaged projection, returned the
identical acquired object, and covered **998/998** armable Vars with **0**
unarmed. The worker's private initializer was hot-reloaded from its edited
source for this JVM probe. Entering wrappers and the Malli registry were
restored afterward. No test JVM was launched; the cold-worker gate log
remains a verification boundary, not a claimed pass.

## Owner

`src/seon/test/runner.clj` should call the existing
`seon.test.arm/initialize-contracts!`; no new initializer is needed.

## Acceptance

Extend `seon.test.runner-test/initialization-acquires-one-projection` to
exercise both launcher entry points. Each initializes through exactly one
packaged projection acquisition and covers every armable program Var.
Verify the isolated worker's own timestamped gate log.
