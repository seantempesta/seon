---
type: issue
status: open
severity: blocker
tags: [issue, agent, architecture, pod]
---

# Reduce retained memory in each execution child

## Problem

Each idle per-agent Bun execution child retains hundreds of megabytes. This
prevents many simultaneous agents or clusters from running on modest hardware
even though process isolation and parallel execution work correctly.

## Evidence

- After one compiled root prompt and no active invocation on 2026-07-18, the
  execution child retained 650,224 KiB RSS.
- After killing it and reconstructing the same prompt in a replacement, the
  new idle child retained 646,624 KiB RSS.
- The supervising Bun pod retained 906,096 KiB RSS before the child was
  killed, so one cluster with one warm child was already around 1.5 GiB before
  including the JVM writer.

## Owner

The execution artifact and child bootstrap in `seon.execution.runtime`,
`seon.execution.host`, and the Shadow `execution` build. Measure retained
analyzer/compiler state and the compiled dependency closure before changing
the process boundary.

## Acceptance

- A repeatable cold/warm measurement separates pod, writer, and each child RSS.
- The child retains only state required for its persistent namespace and
  evaluation contract; shared immutable program/package data is not copied
  unnecessarily per child.
- Several simultaneous children fit the modest-hardware graduation profile
  without losing process isolation, hot code application, or first-turn tools.
