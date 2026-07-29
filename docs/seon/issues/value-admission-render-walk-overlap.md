---
type: issue
status: open
severity: cleanup
tags: [issue, architecture, render, sci]
---

# Unify the nested-data walk shared by admission and rendering

## Problem

`seon.sci.admit` and `seon.render.value` both traverse nested Clojure data.
They have different jobs—eval-boundary safety versus presentation—but their
collection descent and opaque-marker construction can drift.

## Evidence

`src/seon/sci/admit.clj` bounds total nodes, calls the SCI interrupt function,
and produces durable printable ordinary data. `src/seon/render/value.cljc`
preserves navigation identities, semantic field preference, presentation
elision counts, and the AI/HTML structural twins. Both use the same existing
admission caps as hard maxima and the same `:seon.eval/opaque` /
`:seon.eval/datom` marker vocabulary, but collection descent is still
implemented twice. Ruling 14 in the SCI runtime plan requires one walk
discipline or a named reason for the split; the reason during this port is that
admission must interrupt and enforce a total node budget before a value can be
retained, while rendering selects a smaller navigation-preserving
presentation after admission. `src/seon/render/walk.clj` is protected during
this port, so ref-edge unification remains outside this lane.

## Owner

The renderer/walk design session should extract one shared data-edge
discipline without weakening admission's safety boundary or presentation's
navigability.

## Acceptance

Admission and structural rendering share one tested nested-data traversal and
marker vocabulary; admission still owns interrupts and total safety caps,
rendering still owns presentation selection and elision, and the entity graph
walker applies the same discipline to ref edges without a second marker
vocabulary.
