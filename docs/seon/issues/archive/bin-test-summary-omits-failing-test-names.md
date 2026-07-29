---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, testing]
---

# Name failing test vars in the fresh test gate

## Problem

`bin/test` ended a failing run with only aggregate failure and error counts.
The orchestrator then had to search the preceding output to discover which test
vars failed.

## Evidence

The existing `seon.test-runner-failure-fixture/failing-example` gives the gate
one deterministic failure. Before the fix, its final summary contained the
count but no compact test-var inventory.

## Owner

`bin/test`, the one fresh correctness gate.

## Acceptance

A failing selected namespace exits nonzero and ends with a sorted, deduplicated
list of its failing test vars, while passing and intentionally nested fixture
events do not appear in that list.

## Resolution

Commit `9e79b77e9` binds a reporting wrapper around `clojure.test/report`,
collects `:fail` and `:error` events from the selected namespaces, delegates
every event to the standard reporter, and prints the compact inventory after
the ordinary summary.

## Proof

`bin/test seon.test-runner-failure-fixture` exited 1 and ended with
`seon.test-runner-failure-fixture/failing-example` under `Failing tests:`.
The namespace filter excludes the same deliberate fixture when another test
namespace invokes it as data.
