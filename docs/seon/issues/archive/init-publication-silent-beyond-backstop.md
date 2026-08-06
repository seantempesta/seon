---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, process, database]
---

# `bin/seon init` dies at the silence backstop because publication emits no events

## Problem

`bin/seon init` against a live anchor JVM fails with
`:seon.fresh-operator/prepl-response-silent` after 30 000 ms
(`:seon.config.operator/event-silence-backstop-ms`), observed 2026-08-06 on
the quiet-tree checkpoint. The JVM is healthy: the same publication form sent
directly over the prepl completed successfully in **70 248 ms**
(`seon.cluster/refresh-source!` → commit-id `6a749e6c-eb73-5850-95d2-f2dde7d3dfbd`,
digest `b0c50215…`, `built? true`), with the working thread RUNNABLE in malli
var registration throughout. The complete publication phase emits **zero**
prepl events until its terminal `:ret`, so any run longer than the backstop is
indistinguishable from a wedge and is killed while healthy.

## Cause

The per-phase silence backstop (`61cbb93ed`, correct by the event-driven
ruling) assumes each phase publishes events while it works. The publication
phase never got that treatment: `init-form`
(`script/seon/fresh_operator.clj:1960-2032`) evaluates one long silent form;
`prepl-eval!` (`:1204-1269`) reads with `SoTimeout` = backstop, so the first
event after 30 s of honest work is a `SocketTimeoutException`.

## Fix direction (event-driven, not a bigger clock)

The publication owner (`seon.cluster/refresh-source!` chain) prints concise
phase-progress lines (analysis started/N files, schema population, branch
publication) so the prepl streams `:out` events that reset the silence window.
Raising the backstop constant is the banned tuned-constant response. Secondary
question for the same lane: publication is now ~70 s wall (the 08-05 record
claimed 2.7 s for reset→republish→refork) — attribute the difference
(different measurement? real regression? live-JVM `:reload` overhead?) and
file separately if it is a genuine velocity regression.

## Acceptance

- `bin/seon init` completes green against a live anchor JVM whose
  publication takes longer than the backstop, with visible progress lines.
- A genuinely silent (wedged) publication still trips the backstop.

## Resolution

Resolved by `b465b4613` and `ddb648dc2`. The first commit forwards prepl
`:out` events and publishes the complete source phases. The second extends
that same callback through `seon.fn/index!`: contract derivation reports six
total-derived milestones, and each ordered program transaction phase is
divided into at most six vector batches on the unpublished scratch branch.
The branch head remains guarded and invisible until every phase and the final
source seal commit complete. The 30 000 ms backstop is unchanged.

Live proof on 2026-08-06 used the already-running default JVM, PID 47346; it
was not stopped or reset. `bin/seon init` completed green in 81.93 s and
printed bounded milestones through 5 657 contract rows, 5 379 declaration
rows, 16 981 keyword datoms, and 2 533 call rows before:

```text
● current-src: program rows complete
● current-src: branch publication complete
● :current-src commit 6a74b888-3103-5e0d-8e50-9b92138c94af digest 9284b9873e6ca6062522bd827b336467d099967dafff9645808b3237fb5b2288
```

The recurring genuinely-silent falsifier remains green with the unchanged
backstop. The non-long fresh-operator selection passed 22 tests / 99
assertions, including the PID-reuse fence and both progress/silence backstop
tests; `seon.fn-test` passed 18 tests / 121 assertions.

The 81.93 s complete publication is not the recorded 2.7 s
reset→republish→refork result. Current evidence demonstrates a real slow
complete-publication path, while the older record does not contain comparable
per-phase timing. Performance attribution and repair are deliberately split
into [Complete source publication takes ~70 s against the ten-second law](../complete-publication-takes-seventy-seconds.md).
