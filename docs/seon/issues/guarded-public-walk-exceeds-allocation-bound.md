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

## Error-family follow-up — 2026-09-20

Commit `59e51e221` plus its canonical SCI fixture corrections durably recorded
74 tests / 406 assertions / 2 failures / 0 errors, request `4a3ec3f506af`.
Both failures are this walk's unchanged assertions. The selected-render
failure is resolved: a redundant identity wrapper excluded the typed unknown
from its input. Removing that wrapper and asserting the declared unknown
error schema passed both the six-suite and focused SCI runs.

The scoped plan observation now falsifies the inference that the walk
owner's cache failed: all seven acquisitions returned plan identity
2144200547, selector identity 1133950800, and fingerprint 750175967. There
was one distinct selector. The broader compiler spy counted 16 calls.
Same-JVM JFR samples locate additional calls in `seon.schema/pull-selector?`
and `seon.schema/projection-with-pulled-form-in`, reached through
`seon.db/validate-pulled-value`. Those schema owners are held by bridge step 2.
This is a broader selector-validation/derivation reuse boundary; do not
replace the working root-plan cache or suppress input validation.

The guarded evaluation measured **4,453,384,176 bytes**. Deeper JFR stacks
separate projection-from-rows samples during `seon.sci.eval/cluster-ctx`
setup from the later guarded walk. No sampled projection-from-rows stack
contains `seon.render/walk`; this is not proof of absence from every
execution. Walk-stack samples include config dial scanning, instrumented
calls, pulled-value validation, render program-evidence pulls and identity
attribute reads. Their sampled weights do not establish a complete causal
allocation budget or justify raising the assertion's limit.

Evidence: `tmp/error-family-sci-fixtures.log`, `tmp/error-family-walk.jfr`,
and `tmp/error-family-walk-allocations.txt`. The test interval is
07:41:03.545729–07:41:06.501444 UTC. The recording was attached to the one
foreground test JVM (95807), with stack depth 256; that JVM exited.
[The extraction script](../../prds/steward-platform/research/error-family-walk-evidence-2026-09-20.py)
reproduces the bounded report. The error-family landing note brings the
remaining cross-owner performance scope to the owner; neither assertion was
changed and no held schema file was edited.
