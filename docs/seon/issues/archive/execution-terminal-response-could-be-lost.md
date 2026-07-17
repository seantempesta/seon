---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Execution terminal response could be lost

## Problem

The execution child looked up terminal-message validators in the authored
schema registry after installing the database program. Authored schema changes
could therefore invalidate the child protocol itself. The settlement path also
removed the active invocation before validating and sending its terminal
message. If sending threw, the fallback error no longer owned the invocation,
so the host waited until timeout.

## Resolution

The child compiles its fixed parent/child message validators from the packaged
schema population before authored program activation. Settlement validates and
sends the terminal message before removing the active invocation, so a failed
result send can still become one bounded error response.

## Evidence

Focused host tests cover startup errors and terminal settlement. The real
two-child process proof installs the database program, completes initial calls,
publishes replacement source, and receives terminal results from both fresh
children.

## Owner

`seon.execution` owns child protocol validation and invocation settlement.

## Acceptance

- Authored schemas cannot change the fixed child protocol validators.
- A failed result send can still produce the invocation's one error response.
- The host observes startup errors without waiting for process exit timeout.
