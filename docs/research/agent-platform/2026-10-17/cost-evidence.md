---
title: "Cost evidence: every function's true cost, declared growth, immediate rejection"
type: plan
status: wave 1 and wave 2 prototype in flight (2026-10-17)
---

# Cost evidence

**Phase status** (each implementing commit updates this line): A (operation table) done c6777da6b · wave 1 (live
per-function cost) in flight, lane cost-wave1 · wave 2 (generative profile prototype) in flight, lane cost-wave2 ·
wave 3 (refuse at `defn`) pending · task estimates (`task-estimates.md`) pending owner

## Owner rulings, 2026-10-17

- "I want the estimates for the tasks put up front and it's a failing test if any thing exceeds them."
- "A design lane or root puts in the costs up front and design adapts."
- "Agents are pretty lazy and will rescan an entire index for no reason multiple times. We need immediate feedback
  and rejections. If an agent writes a function and we generatively test it with random inputs we have a pretty good
  idea of the runtime so we can enforce it there too."
- "We basically have the runtime: we can measure things as they are running, and the generative testing for profiling
  functions we haven't run with real inputs."
- "Get it working, then make it fast"; harder problems are iterated in waves.

## The model

Two sources of evidence, one judgement:

1. **Live runtime.** Every instrumented call records calls, allocated bytes (`ThreadMXBean`), datoms read and ms in its
   function's profile cell, keyed by definition digest. The reply lists a request's top functions; `doc` shows each
   function's per-call p50/p95.
2. **Generative profile.** At `defn`, the contract's generator drives the function at two input sizes and against the
   database padded on untouched and on touched attributes. Counts (never wall time) give the growth class per
   dimension and the constant per unit.

**The agent declares what a function's cost grows with** (its input, the changed forms, the result), never a
constant. **The system measures the constant.** A growth class the declaration does not allow (reads grow with the index
while the input is fixed: the lazy rescan) is refused at `defn` once wave 3 lands; a later call over the measured
bound is a finding in its reply and a red test. Big O states the growth (usually worst case); the bound a test checks
is the declared growth evaluated at each call's actual N, with the measured constant, so it never flakes.

## Waves

| Wave | What | Proof |
|---|---|---|
| A | End-to-end estimate table for 8 ordinary operations; overruns red in the table, regressions red on every `src` commit (`bin/lane`) | `seon.cost-estimate-test` (c6777da6b) |
| 1 | Live per-function calls, allocation, reads, ms; per-call overhead measured first; shown in the reply and `doc` | lane cost-wave1 |
| 2 | REPL prototype of the two-size generative profile on six real functions; coverage count; design of wave 3 priced | lane cost-wave2, `cost-probe.md` |
| 3 | At `defn`: probe runs, mismatched growth refused (entry policy, like schemas), measured constants become test bounds | after wave 2 |
| T | Tasks carry their estimate up front (`task-estimates.md`, owner decision) | after wave 3 |

The eval path's current overruns are fixed in parallel by lane eval-trace (`eval-proportionality.md`).
