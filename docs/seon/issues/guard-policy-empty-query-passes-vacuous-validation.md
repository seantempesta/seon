---
type: issue
status: open
tags: [agent, runtime, issue]
severity: blocker
---

# Guard policy empty query passes vacuous validation

## Problem

The JVM guarded door accepts a missing guard-policy query result as a complete
policy. `every?` over the empty `vals` sequence returns true, so invocation
execution reaches the guard with nil fuel and fails before any authored or
compiled function can run.

## Evidence

- `src/seon/host/sample.clj` builds `policy` only for a five-column query row,
  then validates only `(every? pos-int? (vals policy))`.
- The U2 full writer checkpoint at
  `tmp/orchestrator/u2-gate-writer.log` produced the same
  `Cannot invoke "Object.getClass()" because "x" is null` envelope across host
  invocation families: 279 failures and 13 errors.
- The failing fresh writer fixtures intentionally lack the new guard config
  row, making the empty-query branch a direct falsifier.

## Owner

The guard-policy acquisition boundary in `seon.host.sample` owns the fix. It
must reject an absent or incomplete row as the existing configuration error;
the guard hot path must never receive a partial policy.

## Acceptance

- An absent guard-policy row returns the loud existing configuration error
  rather than nil policy data.
- A complete five-field positive policy remains accepted.
- The focused host invocation regression no longer fails through nil guard
  fuel.
