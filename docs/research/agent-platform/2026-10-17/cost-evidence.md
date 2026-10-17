---
title: "Cost evidence: every function's true cost, declared growth, immediate rejection"
type: plan
status: wave 1 and wave 2 prototype in flight (2026-10-17)
---

# Cost evidence

**Phase status** (each implementing commit updates this line): A (operation table) done c6777da6b · wave 1 (live
per-function cost) done ed12f110a (pain ranks self overrun; health's live ranking is the pain ranking) · wave 2 (generative profile prototype) in flight, lane cost-wave2 ·
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

## Wave 1 evidence (2026-10-17, after ed12f110a)

Check JVM booted from HEAD ed12f110a plus a disposable probe, after boot and the workload of `seon.sci.documentation-test`,
`seon.sci.branch-execution-test` (long tests included), `seon.profile-test`, `seon.cost-estimate-test` and
`seon.health-test` (345 pass; the 4 reds are the same at ca69b7fed: the estimate table's open overruns and
`docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md`). The database value counts as
one in N. 759 of 3,544 cells had sampled sizes (size period 64).

Per unit of N, across the sampled cells:

| figure | p50 | p90 | p99 | max |
|---|---|---|---|---|
| mean touched size N | 10.0 | 49.8 | 4,123 | 22,820 |
| steps / N (inclusive) | 0.27 | 30.0 | 319 | 4,693 |
| self steps / N | 0.09 | 1.0 | 5.0 | 36.2 |
| reads / N (inclusive) | 0.0 | 0.27 | 6.0 | 51.2 |
| self reads / N | 0.0 | 0.0 | 0.5 | 2.0 |
| bytes / N (inclusive) | 392 | 79,032 | 4.8 M | 27.3 M |
| self bytes / N | 108 | 1,246 | 44,841 | 490,017 |

Self figures sit well inside the shipped dials (steps 8 × N + 32, reads 2 × N + 4, bytes 4,096 × N + 65,536) except
allocation; inclusive figures exceed them only at entrances, which carry their callees' work.

Self-pain top 10 (`seon.profile/pain`, by summed self share; overrun is self spent over allowed):

| # | definition | calls | share | self overrun | self per N | inclusive overrun |
|---|---|---|---|---|---|---|
| 1 | `seon.db/vector-query-call` | 4,759 | bytes 0.491 | bytes 5.32 | 43,485 B | bytes 5.73 |
| 2 | `seon.turn.batch/record-form` | 32 | steps 0.371 | steps 3.13, bytes 10.63 | 36.2 steps, 120,907 B | steps 27.6, bytes 587 |
| 3 | `seon.sci.admit/semantic-value` | 72 | steps 0.319 | steps 2.12, bytes 2.8 | 29.3 steps | steps 7.14 |
| 4 | `seon.db/transact!` | 134 | steps 0.304 | steps 1.42 | 16.4 steps | steps 8.17 |
| 5 | `seon.program/without-docstring` | 3,483 | bytes 0.296 | bytes 7.01 | 181,727 B | bytes 7.01 |
| 6 | `seon.program/source-resolver-names` | 3,731 | bytes 0.101 | bytes 1.92 | 13,173 B | bytes 2.03 |
| 7 | `seon.fn.analyzer/invoke-kondo` | 40 | bytes 0.024 | bytes 36.4 | 490,017 B | bytes 36.4 |
| 8 | `seon.db/write-owned-values-error` | 160 | bytes 0.023 | bytes 3.08 | 15,269 B | bytes 3.96 |
| 9 | `seon.fn/runtime-analysis-batch` | 32 | bytes 0.019 | bytes 35.9 | 483,213 B | steps 26.6, bytes 644 |
| 10 | `seon.profile/transaction-observation` | 116 | bytes 0.013 | bytes 5.47 | 43,465 B | bytes 13.4 |

No definition overran the self reads contract in this workload: the lazy rescan shows as inclusive reads at entrances,
not as a producer's own reads. The bytes dial (4,096 × N) ranks allocation-heavy text producers (`without-docstring`,
`invoke-kondo`) whose N counts a string as one; sizing a string's extent by its length is the next step before the
bytes figure can be trusted.
