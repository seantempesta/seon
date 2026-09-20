---
type: issue
status: open
severity: friction
created: 2026-09-20
tags: [issue, render, sci, performance, wave/render-acquisition-performance]
---

# Guarded public walk exceeds its allocation regression bound

The bridge step-1 HEAD-plus-owned-path fast snapshot at
`fe1aaad68bdfa3e491e56439bbab2df9bbe5bad4` records **4,228,637,320 bytes**
for `seon.sci.eval-test/public-walk-is-callable-through-an-agent-sci-eval`,
against its unchanged 1 GiB assertion (`test/seon/sci/eval_test.clj:1888`).
Its value assertions pass; its pull-plan reuse assertion at `:1885` also
fails. The prior iteration measured 4,204,543,504 bytes. No comparable
HEAD-only walk measurement exists in this lane, so attribution to the
registry change remains unproved.

JFR allocation samples came from the same foreground test JVM (PID 38124),
covering the test's 05:47:30.645–05:47:33.278 UTC interval. Samples include
`seon.instrument/compiled-wrapper` (`src/seon/instrument.clj:739`),
`supplied-projection` (`:604`), and
`seon.schema/projection-from-rows` (`src/seon/schema.clj:2516`). This
locates observed work, not its causal share of the measured allocation.
Printing the captured stacks to depth 64 also shows DB identity acquisition
at `src/seon/db.clj:1140` called from `src/seon/render.clj:164`, and projection
fingerprint work at `src/seon/schema.clj:742`. The test interval includes
direct/web acquisition after the SCI evaluation, so sampled weights over
that interval must not be equated with the SCI allocation counter.
The dated extraction is reproducible with the `--allocations` mode of
[the evidence script](../../prds/steward-platform/research/bridge-step1-evidence-2026-09-20.py).
The raw recording is `tmp/bridge-step1-walk.jfr`; the executed log is
`tmp/bridge-step1-predicate-custody-fast.log`.

The same run's selected-render regression encounters an array-length
`OutOfMemoryError` while Clojure prints a nested map
(`test/seon/sci/eval_test.clj:2144`). Its cause is not established by the
truncated reporter stack. Do not fix either observation by increasing a
bound or suppressing instrumentation.

Acceptance: compare the same canonical walk at a coordinated baseline,
locate allocation in the owning function, retain the unchanged allocation
and pull-plan behavior assertions, and prove the selected-render failure
without printing an unbounded runtime object. The owner ruled this an open measurement, not a step-1 landing condition.
The registry construction and call-site cut lands independently; see
[the step-1 note](../../prds/steward-platform/research/bridge-step1-registry-2026-09-20.md).
