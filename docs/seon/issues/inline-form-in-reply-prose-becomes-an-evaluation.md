---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, agent, reader]
---

# An inline form in reply prose becomes a spurious evaluation

## Problem

The reply reader admitted an inline map from the model's explanatory prose
as one evaluation and the remaining prose as an unmatched-delimiter error,
before successfully reading the two intended fenced forms.

## Evidence

Default's DeepSeek turn 36e029636c82 (issue-family worker 856c73b784fb)
closed at transaction 536871861. Its reply said that the operator requested
`(my.issue/status {:seon.issue/id "d1f11894d81f"})` and then emitted that
form and `(my.agent/done)` in fences. The first prose line became ordinal 0
ending in the inline map; ordinal 1 failed with `Unmatched delimiter: )`.
Ordinals 2 and 3 successfully read issue status and returned wait disposition.

The exact reply, source spans, shown results, and error are in
`docs/prds/steward-platform/research/issue-family-paid-2026-09-16.edn`.
The captured prompt and explain probe sit beside it. This is not a claim
that Markdown fences alone fail: the intended fenced forms succeeded.

## Owner and acceptance

The ordinary reply reader in `src/seon/cluster/reply.clj` owns source/prose
boundaries. Preserve this real reply as a canonical reader regression and
verify that prose containing inline forms cannot create extra evaluations;
the two intended source forms must retain their exact spans and ordering.
The older fenced-reply defect is recorded separately in
`archive/fenced-replies-read-as-prose-and-no-forms-is-a-core-fault.md`.
