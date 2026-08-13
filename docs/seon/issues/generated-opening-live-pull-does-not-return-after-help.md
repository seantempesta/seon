---
type: issue
status: open
severity: blocker
tags: [issue, agent, render, performance, class/n9, wave/prefix-drift-bootstrap]
---

# Generated opening live pull does not return after help

## Problem

After clearing the two request-shape blockers process-locally, deriving the
second generated opening entry did not return and consumed sustained compute.
The live opening remained at one form and one receipt.

## Evidence

On an isolated operator root at commit `16f022fc9`, a direct
`seon.bootstrap/next-entry` call for `bootstrap:explorer3` ran for approximately
27 seconds before the prepl connection ended without a return envelope. The
JVM then showed about 297% CPU, 2.2% memory, and a 1.22 GiB isolated database
footprint. The same run still had exactly one form and one receipt. The JVM was
stopped through `bin/seon --root ... down`.

The call used the landed live pull with distance 3 and the actual settled help
receipt. This is distinct from the earlier contract refusals: both were
removed by live Var wrappers before this measurement.

## Owner

`seon.bootstrap/pull-result` and `seon.render.walk/root-acquisition` own the
candidate pull and expansion cost. Diagnosis must begin with a virtual-thread-
aware dump or bounded source-level counters; the observation does not yet
attribute the compute to a specific inner function.

## Scheduling note — 2026-08-12

Skipped by the evolving-session defect-clear wave. `src/seon/bootstrap.clj`
is held by the prefix-drift lane, and the report deliberately does not yet
attribute the compute to a specific inner owner. Resume with the required
virtual-thread-aware dump or bounded counters after that held owner is free;
do not patch the observed stall at a neighboring render seam.

## Acceptance

- The second live generated entry returns with a complete prepl envelope.
- Counters name pull acquisition, candidate rendering, and fixed-point work.
- The result reaches the next dependency-ready form without unbounded CPU or
  database growth.

## Evidence — 2026-08-13 live-pull attribution

The isolated HEAD probe in
[the dated attribution report](../../prds/sci-execution-runtime/research/live-pull-attribution-2026-08-13.md)
called `seon.bootstrap/next-entry` after a settled `help` receipt. It returned
`(dir (quote my.run))` normally in 2,801 ms on a 68,885,520-byte database with
37 acquisition members. Root acquisition consumed 2,768 ms, of which Datahike
`pull-spec` consumed 2,650 ms; candidate expansion, `ordered-episode`, and the
final candidate choice together consumed tens of milliseconds.

The historical approximately 27-second non-return at `16f022fc9` therefore
does not reproduce at HEAD under these conditions. The dominant inner owner is
Datahike `pull-pattern-frame` × `pull-attr` execution of the schema-wide
selector. Phase 1 should acquire/expand once per generation invocation and
carry the immutable result through one-entry-at-a-time generation state,
rather than rerunning this seconds-long acquisition after every settled form.
The issue remains open pending that structural change and an interactive
single-acquisition result.
