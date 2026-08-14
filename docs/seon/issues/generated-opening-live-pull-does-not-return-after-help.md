---
type: issue
status: open
severity: blocker
tags: [issue, agent, render, schema, performance, class/p1, class/n9,
       wave/prefix-drift-bootstrap]
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

## Evidence — 2026-08-14 codec clearance exposes prefix drift

After commit `8ec96cbf1` repaired the EDN-backed receipt codec, a freshly
published isolated root decoded the complete ordinal-0 `bootstrap:root`
receipt under the deliberately opposing `*print-namespace-maps*` binding: 23
read-evidence components, including 22 call-preparation read requests and 23
dependency plans, with no read error. Generation therefore crossed the prior
codec stop boundary.

The run then closed at the next boundary with `"A stored generated form is
outside the pull."` from `seon.bootstrap/next-entry`
(`src/seon/bootstrap.clj:568-580` at that commit). The assertion means a
settled receipt source was absent from the newly derived candidate pull. It is
the same post-`help` generated-opening owner and acceptance boundary tracked by
this issue, now observed as a prompt, typed prefix-drift failure rather than an
unbounded non-return. The database-codec repair did not alter this owner.

## Evidence — 2026-08-13 root cause: the per-render projection rebuild

[The dated after-help diagnosis](../../prds/sci-execution-runtime/research/live-pull-after-help-diagnosis-2026-08-13.md)
reproduced the failure on a pinned export of `30ccf1ff2` and named the owner.
It is **slow, not hung**: every derivation returns.

Sharpened reproduction conditions — the earlier report's fixture missed this
because it did not instrument `seon.config/effective`:

- The "second live pull" is the **first** `seon.bootstrap/next-entry`
  invocation. `seon.bootstrap/seed-tx` writes the ordinal-0 `(help)` source at
  agent creation (`src/seon/bootstrap.clj:788-792`), so the run loop never
  derives `(help)` and its first derivation is already the post-`help` one.
- The cluster entity and its config dials must be present. With them,
  `seon.render/request-profile` (`src/seon/render.clj:63-81`) falls through to
  `seon.config/effective`, which unconditionally rebuilds the whole Malli
  schema projection (`src/seon/config.clj:530-534`) — about 851 ms per call,
  ~2.5 calls per render call, ~99 render calls per derivation.
- `seon.bootstrap/next-entry` establishes no projection extent, so `seon.db`
  reads outside `root-acquisition`/`neighborhood` rebuild it too
  (`src/seon/db.clj:497-501`).

Controlled A/B on one 72.8 MB, 172,848-datom published database, one changed
request key: the post-`help` derivation took **276,262 ms** without
`:seon.render/profile` on the request and **6,953 ms** with it (39.7×).
`render-call` fell from 269,126 ms to 70 ms across the same 99 calls;
`seon.config/effective` fell from 246 calls to zero. Datahike `pull-spec` cost
4.3–5.4 seconds in **both** arms, so it is 1.6% of the failing derivation.

This is the same class as
[[walk-neighborhood-under-history-can-wedge-cluster-stop]]: `walk/history`
calls `neighborhood` twice (`src/seon/render/walk.clj:915,917`) and neither
carries a profile; one uncarried `neighborhood` measured 91,252 ms, and the
wedge's retained dump shows the identical `walk.clj:590,566,546` frames. Fix
the class once.

## Evidence — 2026-08-14 prefix reconciliation resolved

Commits `0c3c289d7` and `dd7eb92b8` dissolved the independently seeded
ordinal-0 source. A generated run now opens with zero forms in `:generate`,
and the live generator derives and appends `(help)` through the same path as
every successor. The follow-up keeps the initial situation and starting
namespace in `open-call`'s one entity assertion; raw datoms after that
transaction function could not address its newly created tempid and had left
agent creation incomplete.

`bin/test seon.bootstrap-test` passed after the correction. A fresh isolated
root published from `dd7eb92b8` then created `prefix-proof-agent`; a bounded
Datahike listener observed these ordered source facts:

- ordinal 0: `; A new run just opened. Why am I awake — do I have messages?\n(help)`
- ordinal 1: `(dir my.run)`

Ordinal 0 had a settled result and no receipt error, the run remained in
`:generate`, and the live query found no `seon.bootstrap/prefix-drift` fault.
This resolves the prefix-reconciliation boundary. The issue remains open for
its distinct single-acquisition/per-render projection cost acceptance.
