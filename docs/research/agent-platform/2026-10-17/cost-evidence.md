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
- "Give estimates algorithmic cost (number of passes over data and number of steps). I think very few functions in
  the system are open ended (scale like a batch function) but if we can detect that then sure we can enforce those
  kinds of growth and test. I want a general solution that we can put in sane defaults and agents need to override
  them if something is truly painful for the system."

## The default cost contract

Every function has a cost contract without writing one: the default. Cost is algorithmic, counted, never timed:

- **N** is the size of what the call touches: its inputs plus its output (elements, datoms), counted where it is
  measured (the generative profile and sampled live calls), never on every call.
- **Steps**: instrumented calls (and interpreted steps under SCI) at most `k_steps × N + c`.
- **Passes**: datoms read at most `k_reads × N + c`, so a function reads each thing it returns or consumes a bounded
  number of times. A read count that grows while N does not (the index rescanned for one answer) is the defect class
  this exists for.
- **Allocation**: at most `k_bytes × N + c`.

`k` and `c` are config dials with sane defaults (a few passes, a small constant), sized from the live evidence of
wave 1, not guessed. A function that is truly open ended (a batch over the program, a whole-store walk) overrides the
default in its metadata, naming what it grows with and why, and that override is shown in its `doc` and counted, so
open-ended functions stay few and visible. A call over its contract (default or override) is a finding in its reply;
a test that drives it over is red; at `defn`, the generative profile refuses a definition whose growth breaks its
contract (wave 3).

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

## Before a crash (owner, 2026-10-17: "find a way to instrument and make us aware of things before we crash")

Default died of `OutOfMemoryError` three times on 2026-10-17 (16:38 a render printing a database value; 17:23 an
interrupted check's bodies printing a Datahike error; 18:14 one request generating inputs from 3,591 contracts), each
first seen as the crash. Three layers, each its own lane:

- **Contain** (lane eval-bounds-2): a per-request allocation dial; past it the evaluation is interrupted and its reply
  is an error naming the bound, the bytes and the form; every other agent keeps running.
- **Warn** (lane heap-watch): live heap after each GC observed by JVM notification (no polling); crossing a dial
  (warn, critical) or sustained growth records one observation (the in-flight requests by allocated bytes, the cache's
  weight, the top pain functions) shown in health and in the reply of every in-flight request at critical.
- **Explain** (lane heap-watch): JFR continuous recording as a bounded ring dumped on exit and on OOM, so a death still
  leaves the last minutes of allocation by stack.
