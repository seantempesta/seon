---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [sci, evaluation, deadline, instrumentation, gate, flake]
---

# The evaluation deadline latches around a ten-millisecond refusal

## Problem

`seon.sci.eval-test/an-instrumented-multi-arity-miss-reads-like-clojure`
evaluates `(seon.db/as-of)` under a 2000 ms `:seon.sci.eval/time-limit-ms`
and reads the instrumented arity refusal. It failed in both cold gates of
2026-09-17 — and in neither case was the refusal wrong:

- `batch-120.log`: `:seon.sci.eval/evaluation-failed`, shown text
  `The renderer seon.error/render-ai did not return: time-limit.`,
  `:seon.render.unknown/refusal :seon.sci.kernel/time-limit`.
- `batch-119.log`: shown text carries
  `Fix: Wrong number of args (0) passed to: seon.db/as-of`, which is
  `seon.instrument/minimal-violation`'s fallback — reached only when
  `violation`'s `(catch Throwable …)` (`src/seon/instrument.clj:433`)
  catches something. A deadline interrupt raised inside the refusal
  construction is exactly that something.

Measured in a candidate SCI context (canonical fixture, real fork, armed
contracts), 20 iterations: the JVM-direct refusal is **0–2 ms**, the whole
SCI evaluation including the render is **98 ms first, 8–12 ms after**, the
render alone is **<1 ms**, and the evaluation's own record says
`:seon.eval/fn-entries 0, :seon.eval/duration-ms 5`. A 2000 ms bound around
10 ms of work is a 200× margin, so "raise the limit" is not the answer and
the path is not slow.

## Why it matters

Two things read as absence of signal here. A deadline that latches for a
reason unrelated to the work it governs turns a 10 ms path into an
intermittent red that costs every reader the time to disprove a message
drift (this lane's whole first half). And `violation`'s `catch Throwable`
converts a bound firing into a DIFFERENT diagnosis: batch-119 reports a
contract violation where the truth is a deadline, which is precisely the
class AGENTS §2.3 names — a bound firing must be a bug report naming what
never arrived, never something else's error.

## Where to look

`seon.sci.kernel/arm` (`src/seon/sci/kernel.clj:276`) installs the arm in a
ThreadLocal and `own-arm`'s `::stop!` removes it — unless the owning
evaluation leaves without calling it. An inherited arm carries the previous
evaluation's `::deadline-nanos` and its already-set `::reached` latch, so the
next evaluation on that pool thread for the same interpreter would interrupt
at its first interpreted entrance. This is a HYPOTHESIS: it was not
reproduced. It was not reproducible in `bin/test-fast` at all (the test is
green at 52–62 ms every run), so the reproduction needs the gate's parallel
workers.

## The class, not the instance

A refusal path must not be able to report someone else's cause. Independently
of the deadline's origin, `seon.instrument/violation`'s catch should re-raise
SCI's interrupt (`seon.sci.kernel/interrupted?`) rather than fall back to
`minimal-violation`, so a deadline is always reported as a deadline. That
change is not in this lane's owned paths for the arity sentence and is filed
here rather than made in passing.
