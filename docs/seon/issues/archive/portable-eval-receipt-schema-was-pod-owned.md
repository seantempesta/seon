---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, schema, runtime]
---

# Portable eval receipt schema was pod-owned

## Evidence

The reset-boundary U12 run reached `:evaled` on the JVM while the pod was down,
but the restarted pod could not acquire the run for publication. The portable
driver's run selector failed against the fresh database because
`:seon.eval/progress?` was absent from installed schema. Eval receipt
attributes were registered only when the pod-only `seon.eval` namespace
loaded, although the JVM claimant now creates and reads the same receipts.

## Resolution

The complete eval receipt attribute schema now lives in the portable
`seon.eval.receipt` owner. The pod evaluator consumes that registration instead
of maintaining a second copy.

The focused JVM portable-driver gate passes 8 tests and 34 assertions. The
reset-boundary live proof remains the acceptance authority because it exercises
fresh schema installation on both tiers.

## Acceptance

- A fresh database installs every eval receipt attribute used by either tier.
- The JVM claimant can create and terminalize eval receipts while the pod is
  down.
- The restarted pod can read the same receipt and publish the turn.
