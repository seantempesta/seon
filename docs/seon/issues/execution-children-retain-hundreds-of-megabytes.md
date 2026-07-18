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
- A source-frozen four-agent load on 2026-07-18 completed all four isolated
  children concurrently in one turn, in 13.9--15.4 seconds. While executing,
  their RSS values were 856,704, 851,504, 859,392, and 855,216 KiB. The pod
  remained responsive, but the four children alone therefore required about
  3.4 GiB RSS.
- All four children exited through the existing idle-grace owner. The host
  registry and OS process list returned to zero children, and the supervising
  pod's fully collected JSC heap returned within 6.1 MiB of its pre-load value.
  The retained cost belongs inside each active child rather than a parent-side
  leak or failed eviction.

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
