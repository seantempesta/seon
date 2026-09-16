---
type: issue
status: open
severity: friction
tags: [issue, render, wave/render-producers]
---

# Debug reference previews do not demonstrate the adopted entity pairs

## Problem

The P2 canonical issue-root walk selects function/test schema pairs, but
the live debug page still displayed their reference collections as generic
values, including stored test source. Complete development adoption was not
verified, so this observation does not yet identify a defect in selection.

## Evidence

2026-09-16, default PID 7595, Chrome observation of
`/agent/root/debug?subject=[:seon.issue/id "quoted-private-capability-symbol-was-not-indexable"]`:
`:seon.issue/functions` AI preview was a generic vector with elision;
`:seon.issue/tests` AI preview included the entire stored deftest source.
The page linked `seon.fs.jvm/read` and
`seon.fn-test/quoted-private-handler-symbol-is-indexed-as-the-runtime-symbol`.

Canonical `seon.render.entity-pairs-test` runs 51140 and 51142 passed
17 and 24 assertions, respectively. All four renderer Vars were armed.
The default source-marker query returned no value. The final publication
request waited behind other publications; no default lifecycle change was
performed by this lane.

## Boundary and acceptance

`src/seon/render/web.clj:1455` owns inspection selection;
`src/seon/render/value.clj` owns nested value rendering. First verify
publication/adoption convergence and the carried web projection, then
observe the same linked entities through their pairs on the debug page.
The pair owner must not add a second debug-only rendering mechanism.

See [P2 evidence and gate request](../../prds/steward-platform/research/entity-pairs-2026-09-16.md).
