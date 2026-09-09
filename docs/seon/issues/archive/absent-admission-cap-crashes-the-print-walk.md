---
type: issue
status: resolved
severity: friction
tags: [issue, sci, schema, wave/schema-admission]
---

# An absent admission cap crashes the print walk instead of refusing

## Problem

`seon.sci.admit/admit*` reads `:seon.config.eval.result/max-nodes` straight into
a `long` cast (`src/seon/sci/admit.clj:585`). When the caps map handed to it does
not carry that key, the walk dies with

```text
java.lang.NullPointerException: Cannot invoke "java.lang.Number.doubleValue()"
  because "x" is null
    at clojure.lang.RT longCast RT.java:1302
    at seon.sci.admit$admit_STAR_ admit.clj:585
```

which reaches the caller as a Throwable escaping an evaluation boundary — the
one place AGENTS.md §2.4 says a flat `:seon.error` value must appear instead.
The diagnostic names `RT.longCast`, not the missing member, so the reader has to
walk back through `evaluate → admit → admit*` to learn that a config key was
absent.

Observed on 2026-09-07 by the `one-eval-point` lane: after the debug page's
private evaluator was replaced by `seon.cluster.loop/preview-sources`, a fixture
that handed `:seon.cluster.loop/cluster {}` reached a real evaluation for the
first time and produced exactly this crash
(`seon.render-simplification-test/authored-source-invocation-reuses-one-stored-run-across-presentations`).
The fixture was the defect and is fixed; the unguarded read is not.

## Why it is not just a fixture bug

Every other cap read in that walk has the same shape, so any caller that hands
an incomplete caps map — a new proc, a downstream repository, a partially
applied config row — gets a stack trace rather than a refusal naming
`:seon.config.eval.result/max-nodes`. Absence should be a typed diagnostic here,
and today it is a crash.

## What to do

Refuse at the seam that admits the work: `admit`/`admit*` validate the caps map
against its declared schema and return `seon.error/diagnostic` naming the layer,
the member, the expected shape and the offending value. One regression: admission
with a caps map missing one key returns a flat error value whose
`:seon.error/diagnostic-member` is that key, and never throws.

## Resolution (2026-09-07, the `storage-bound` lane)

Both halves are gone.

1. **The cap it read no longer exists.** `:seon.config.eval.result/max-nodes`
   and the other three display caps were deleted from `seon.sci.admit`
   entirely: elision happens only where AI context is generated, so the walk
   has no depth, width, string or node budget to read
   (`docs/prds/context-generation/research/storage-bound-landing-2026-09-07.md`).
2. **The one bound that replaced it refuses instead of casting.**
   `seon.sci.admit/admit*` validates that the caps map carries
   `:seon.config.eval.result/max-bytes` before anything is walked, and
   answers with a flat `:seon.error` value whose
   `:seon.error/diagnostic-member` is that key. Nothing is realized, nothing
   is thrown.

Regression: `seon.sci.admit-test/an-absent-storage-bound-refuses-and-names-the-key-it-wanted`
— admission with an empty caps map returns a flat error naming the member and
never throws.
