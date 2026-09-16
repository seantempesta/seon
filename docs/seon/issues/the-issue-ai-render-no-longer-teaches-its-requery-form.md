---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, render, test-fixture, wave/render-producers]
---

# The issue AI render no longer teaches its requery form

## Problem

`seon.issue/render-ai` (`src/seon/issue.clj:698`) delegates entirely to
`status-text (status-view unit)`. For an issue carrying no detector and no
tests it emits only `"Issue probe-member: still open.\nMember…"`, with no
executable form. `test/seon/issue_test.clj:65` still asserts unconditionally
that the rendered text contains `"(my.issue/status"`, and that assertion is
red at HEAD.

The question this note asks the render owner: is an issue with no done
condition supposed to teach the agent nothing runnable, or should the status
render always name the one form that requeries it? `my.issue/status` is not
emitted anywhere in `src/seon/issue.clj` any more — `grep` at `2955a0755`
returns nothing — so the expectation cannot be satisfied by any issue shape.

## Evidence

Commit `65986edf7` ("Derive the issue status view in the AI render, not only
in HTML") rewrote `render-ai` and `render-html` onto the shared `status-view`
and did not update this expectation; its diff touches only `src/seon/issue.clj`
and its own research note.

Observed under `bin/test-fast --paths test/seon/issue_deletion_test.clj
test/seon/issue_test.clj test/seon/issue_settlement_test.clj
resources/seon/schemas/seon.test.check.edn -- seon.issue-deletion-test
seon.issue-test seon.issue-settlement-test` at HEAD `61f0332e6`:

```
FAIL in (indexed-issues-replace-facts-and-retract-removed-notes) (issue_test.clj:65)
expected: (clojure.string/includes? (seon.issue/render-ai member) "(my.issue/status")
actual: (not (clojure.string/includes? "Issue probe-member: still open.\nMember" "(my.issue/status"))
```

The same run shows the form IS taught when a done condition exists: the
settlement fixture's shown text carries
`Done condition: (my.test/check {:seon.test/changed […]})`.

## Boundary

Found by the `edges-are-symbols` continuation, which holds
`test/seon/issue_test.clj` for an unrelated deletion hunk. It did not weaken
the assertion: whether the render must always carry a runnable form is the
render owner's design call, and rewriting the expectation to match whatever
the code now does would be exactly the stale-expectation move this project
treats as a defect. The deletion regressions in the same file and in
`test/seon/issue_deletion_test.clj` are green.
