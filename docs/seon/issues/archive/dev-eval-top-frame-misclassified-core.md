---
type: issue
status: resolved
severity: friction
tags: [issue, agent]
---

# Development eval failures may be misclassified as core faults

## Problem

A failure whose top stack frame points at a core namespace may crash the pod
even when the actual cause is a development eval.

## Evidence

The archived dual-path audit's C60 row records an observed crash and the safety
tradeoff. The current fail-hard development policy is intentional, so the
classification must be reproduced before changing it.

## Owner

The runtime fault-classification boundary that distinguishes core publication
faults from agent/development eval failures.

## Acceptance

A repeatable driver proves the classification from structured execution
provenance rather than the top stack frame, while genuine core faults still
fail hard in development.

## Resolution

Resolved by the structured `seon.error/dev-eval!` scope and
`seon.instrument/wrapper-fault` classification matrix. Classification no
longer relies on the top stack frame. `seon.error-record-test` passed in the
focused four-namespace checkpoint on 2026-07-14 (29 tests/169 assertions
total).
