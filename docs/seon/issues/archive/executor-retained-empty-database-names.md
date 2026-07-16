---
type: issue
status: resolved
severity: high
tags: [issue, database]
---

# Executor retained empty database names

## Problem

Each work class appended a database name to its round-robin vector the first
time it received work, but never removed the name or its empty queue. Selection
therefore scanned historical database names after cluster churn while public
executor evidence correctly reported no queued or running work.

## Resolution

Each class now owns a ready-database queue. A database appears once while it
has queued work, moves to the tail only when more work remains, and disappears
with its final queued job. Cancellation rebuilds the same ready-only state.

## Proof

The retained regression completes work for 512 distinct database names and
proves both the ready queue and per-database queue map return to empty along
with public queued/running evidence.
