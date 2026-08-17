---
type: issue
status: open
severity: blocker
tags: [issue, test, flow, config, wave/contract-gate]
---

# Restore the platform tier before further integration gates

## Problem

The platform tier is red across six tests, so every reachability gate stops
before its bulk selection. This outranks feature work because the platform
tier is the suite's prerequisite and a red platform poisons every test that
forks it.

## Evidence

On commit `ba31f21396d2a254b2a4e3bb723960bebd0a9ef9`,
`bin/test --changed src/seon/db.clj` selected 684 tests. The platform tier ran
72 tests containing 321 assertions, reported one failure and five errors, and
did not run the 611 selected bulk tests.

The six failing identities were:

- `seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation`
  — cluster start refused while a reachability sweep was in progress.
- `seon.env-test/a-crossing-that-names-no-environment-is-refused-where-it-is-built`
  — the work launcher reported missing required config facts.
- `seon.env-test/a-submission-delivers-exactly-its-own-environment`
  — the work launcher reported missing required config facts.
- `seon.env-test/an-awaited-submission-carries-its-arm-and-a-detached-one-does-not`
  — the work launcher reported missing required config facts.
- `seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload`
  — `seon.flow/fault-graph-definition` was called with the wrong arity.
- `seon.test-support-test/a-canonical-database-is-the-production-source-population`
  — clock-free schema reconciliation advanced the basis transaction instead
  of remaining idempotent.

The independently focused receipt-carrier regression was green immediately
before this run: one test, 75 assertions, zero failures and zero errors. The
platform failures name cluster, Flow, environment, and fixture behavior
outside that transaction-metadata change.

## Owner

The platform-tier fixture and configuration seams owned by `seon.cluster`,
`seon.flow`, and `seon.test-support`; determine the common shared-tree change
before repairing individual expectations.

## Acceptance

Run the six tests together in one isolated `bin/test` invocation and observe
zero failures and zero errors, then run `bin/test --platform` and observe the
complete platform tier green so reachability gates can enter their bulk tier.
