---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, render, ai-projection, ugly-output]
---

# A generated issue's opening promises tests it does not have

## Problem

`seon.issue/render-ai` emits the same block for every issue:

```
;; My issue. Its tests define done; (my.test/check ...) runs them.
(my.issue/status {:seon.issue/id "003a87a35d7f"})
```

A generated issue (`:seon.issue/detector` present, landed `88b04b970`)
carries no `:seon.issue/tests`; its completion is decided by re-running its
detector. The comment therefore tells the agent to run tests that do not
exist, and names no detector. Observed live on `default` for all 63
generated issues (2026-09-16 12:55Z).

## Fix shape

The render function chooses forms from the data (vocabulary: render
function): when `:seon.issue/detector` is present and `:seon.issue/tests`
absent, the block names the detector and the form that re-evaluates it
(`(seon.issue/generate! …)` or the detector's own call), and states that
the issue resolves when the detector no longer yields the subject. One
regression on the canonical fixture: a detector issue's AI block names its
detector and never mentions `my.test/check`. The context trials in
`docs/prds/steward-platform/plan/issue-context-trials-2026-09-16.md` decide
the richer candidates; this is the floor's correctness only.

Related: `generated-issues-carry-no-tests-so-start-refuses-them`.
