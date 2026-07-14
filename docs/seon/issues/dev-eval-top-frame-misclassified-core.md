---
type: issue
status: open
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
