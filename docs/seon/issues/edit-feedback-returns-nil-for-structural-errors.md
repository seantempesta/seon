---
type: issue
status: open
severity: friction
tags: [issue, tooling, testing]
---

# Return structural edit feedback instead of nil

## Problem

The edit-feedback boundary returns nil for two structural-error cases. The
tests then produce three cascading `clojure.string/includes?` NPEs instead of
one useful mismatch naming why feedback was absent.

## Evidence

The bare 2026-08-05 gate failed:

- `seon.dev.edit-feedback-test/post-edit-reports-valid-sibling-findings-after-a-syntax-error`:
  `feedback` was nil for both the syntax finding and the valid sibling's
  unused-binding warning;
- `seon.dev.edit-feedback-test/pre-edit-exact-reconstruction-uses-structural-edit-refusals`:
  expected `(:decision response)` to be `"block"`, received nil, then called
  `str/includes?` on a nil reason.

The same two vars produced the same nils and NPEs at pre-rename commit
`401fd300e`. The focused baseline's long explicit namespace also showed the
same class in `split-schema-edits-run-admission-before-publication`.

## Owner

The structured pre-edit and post-edit response owner in
`script/seon/dev/edit_feedback.clj` and `bin/seon-hook`.

## Acceptance

Both edit modes always return their declared structured response. A syntax
error and a valid sibling finding coexist in post-edit output; exact
reconstruction returns a structural block with a non-nil reason. Tests do not
turn missing responses into secondary string NPEs.
