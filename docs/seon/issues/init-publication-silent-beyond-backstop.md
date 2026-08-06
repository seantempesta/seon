---
type: issue
status: open
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
