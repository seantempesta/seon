---
type: issue
status: open
severity: cleanup
tags: [issue, testing, storage]
---

# Blob test roots depend on a framework after-hook

## Problem

The deleted CLJS blob suite created a PID-scoped `tmp/blob-test-*` directory
whose only deletion owner was the test framework's after-hook. Process or suite
interruption bypassed that hook.

## Evidence

At commit `10cc8fd11^`, `test/my/blob_test.cljc:16-18,61-72` creates the root,
configures it as the writable blob directory, and deletes it only in
`use-fixtures :after`. The earlier creator at commit `d701afeae`,
`test/my/blob_test.cljs:36-48`, has the same shape. The emergency transcript
recorded dozens of `blob-test-*` roots before deletion.

## Owner

Any fresh blob filesystem fixture that replaces the deleted pod suite, plus the
owning suite process lifecycle.

## Acceptance

- The asynchronous fixture body restores the storage view and deletes its
  exact root in its own promise `finally`.
- The suite exit owner has the same declared root claim and reaps it after all
  children stop when framework teardown cannot run.
- Interrupted and failed focused-suite proofs leave no unclaimed
  `blob-test-*` root.
