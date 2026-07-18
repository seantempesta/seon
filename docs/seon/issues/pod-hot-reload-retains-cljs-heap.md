---
type: issue
status: open
severity: high
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
MiB after collection. Re-registering the agents is therefore not the retained
growth owner by itself. The remaining suspects are old hot-reload code
generations and program publication/build data retained across reload.

## Acceptance

- A clean pod start and at least five identical source reloads record JSC heap,
  object counts, RSS, program publication size, and agent-interest counts.
- Heap snapshots identify the retaining roots rather than inferring them from
  process RSS.
- Repeated converged reloads return near the clean steady-state heap after
  collection without weakening hot code publication or agent wake handling.
- Production memory is reported separately from the Shadow watcher and from
  this development-only reload slope.
