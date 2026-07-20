---
type: issue
status: resolved
severity: blocker
tags: [issue, pod, cljs, health]
---

# Pod hot reload retains ClojureScript heap

## Problem

The long-running Bun pod retains substantially more JavaScript heap across
Shadow hot reloads. This makes development memory materially higher than a
clean production process and can eventually make modest-hardware development
uncomfortable.

## Evidence

On 2026-07-18 the pod reported about 145 MiB used JavaScript heap and 897 MiB
RSS before two later hot reloads. After those reloads, which each republished
the program and rehosted 95 idle agents, it reported 353 MiB live JSC heap and
1.90 GiB RSS after an explicit full `Bun.gc(true)`.

Native `bun:jsc` evidence reported 4.93 million live objects, including 2.49
million objects, 811 thousand arrays, 281 thousand functions, and 184 thousand
lexical environments. The pod had 95 wake inputs, 97 database interests, zero
execution children, and zero running agent loops.

One additional explicit rehost of all 95 agents changed live heap by only 0.18
MiB after collection. Re-registering the agents was therefore not the retained
growth owner.

Native heap inspection isolated the retained path to Malli instrumentation.
`seon.db/query`'s CLJS variadic accessor had an eight-link
`malli$instrument$original` chain. Malli's variadic unstrument path restores
only the immediately preceding wrapper, so each complete program publication
retained another compiled schema generation. Two result-discarding publication
probes grew the fully collected heap from 609,206,858 to 660,819,694 and then
710,303,457 bytes, about 50 MiB and 820 thousand objects per publication.

The instrumentation owner now collapses every Malli CLJS arity accessor to its
original callable before applying the next generation. The first live
publication reduced the fully collected heap from 710,303,457 to
511,623,118 bytes and reduced `seon.db/query`'s accessor depth from eight to
one. A subsequent complete result-discarding publication remained flat at
511,927,139 bytes and depth one. Focused proof passes 11 tests and 129
assertions.

## Resolution

- Heap snapshots and live wrapper inspection identified the retained accessor
  chain rather than inferring it from RSS.
- Repeated converged publication returns to the same collected heap while
  preserving hot code publication and instrumentation.
- The remaining production steady-state memory and RSS measurement stays in
  the database-authority graduation load gate; it is separate from this fixed
  development reload slope.
