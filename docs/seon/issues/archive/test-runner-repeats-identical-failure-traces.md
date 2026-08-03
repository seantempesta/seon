---
type: issue
status: resolved
severity: friction
tags: [issue, test, error, render]
---

# Collapse repeated identical test-runner failures

## Problem

The test runner could print the same refusal trace repeatedly until one cause
produced tens of megabytes of output instead of one bounded report.

## Evidence

The `my-web` lane observed approximately 23 MB of repeated identical stack
traces for one refusal. Failure events are reported through
`src/seon/test/runner.clj`; the maintained runner previously had no repeat
collapse at that reporting boundary.

## Owner

The existing test-runner failure-report projection. It derives identical
failure identity from error content rather than counting or listing repeats by
hand.

## Acceptance

One repeated cause emits one bounded report, distinct causes remain visible,
and a focused discovered runner regression asserts the complete output bound.

## Resolution — 2026-08-03

Commit `f564dc1ff` derives a signed failure from the event or its Throwable
cause and renders each signature once. Distinct signatures remain visible;
unsigned failures stay separate and bounded. Result counters still include
every failure. Print depth, collection length, and inline bytes derive from
the existing config defaults.

`bin/test seon.test-runner-test` passed 5 tests / 15 assertions. The recurring
fixture emits seven identical signed errors and one distinct error: maintained
default reporting produced 90,636 bytes and eight faces; the repaired runner
produced 4,462 bytes and two signature faces while retaining all eight errors.
