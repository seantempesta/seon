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
  remained responsive.
- macOS `vmmap` corrected the additive interpretation of those RSS values. An
  858,160 KiB-RSS child had a 336.1 MiB physical footprint and a 525.1 MiB
  peak. Its private cost was dominated by 297.5 MiB dirty WebKit/JSC memory;
  about 408 MiB of resident `__LINKEDIT` pages are shareable across Bun
  processes. Four children therefore do not consume 3.4 GiB of independent
  physical memory, but their roughly 300 MiB private compiler heaps still make
  parallel agents the dominant modest-hardware cost.
- All four children exited through the existing idle-grace owner. The host
  registry and OS process list returned to zero children, and the supervising
  pod's fully collected JSC heap returned within 6.1 MiB of its pre-load value.
  The retained cost belongs inside each active child rather than a parent-side
  leak or failed eviction.
- A direct ready-only child baseline separated bootstrap from first eval. Before
  the first memory cut it used 323.0 MiB physical memory and 293.0 MiB dirty
  WebKit/JSC memory. After two forms it used 392.3 MiB physical memory, 351.8
  MiB dirty WebKit/JSC memory, and 177.8 MiB JSC heap.
- Commit `72569d9a` keeps exact schema projection acquisition, validation, and
  activation in the one admission owner but omits pod-wide Malli function
  wrappers in execution children. The same ready-only measurement fell to
  218.8 MiB physical and 194.7 MiB dirty WebKit/JSC memory. A real two-form
  agent fell to 310.0 MiB physical, 270.6 MiB dirty WebKit/JSC, and 130.0 MiB
  JSC heap; it returned the exact requested terminal result in 10.8 seconds.
  Focused proof passes 67 tests and 321 assertions.

## Owner

The execution artifact and child bootstrap in `seon.execution.runtime`,
`seon.execution.host`, and the Shadow `execution` build. Measure retained
analyzer/compiler state and the compiled dependency closure before changing
the process boundary.

## Acceptance

- A repeatable cold/warm measurement separates pod, writer, child physical
  footprint/private dirty memory, and shared Bun/JSC image pages; raw RSS is
  not treated as additive.
- The child retains only state required for its persistent namespace and
  evaluation contract; shared immutable program/package data is not copied
  unnecessarily per child.
- Several simultaneous children fit the modest-hardware graduation profile
  without losing process isolation, hot code application, or first-turn tools.
- A warm ready child should use at most 200 MiB physical memory and an active
  evaluated child at most 300 MiB. The current measured values are 218.8 and
  310.0 MiB, so this issue remains open while the eager execution dependency
  graph is tested. Bundle size by itself is not an acceptance measure.
