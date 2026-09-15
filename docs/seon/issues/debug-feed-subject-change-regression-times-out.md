---
type: issue
status: open
severity: friction
tags: [issue, render, test]
---

# Debug feed subject-change regression times out

On 2026-09-14 the html-views lane's HEAD-plus-owned-path gates twice failed
`seon.render.web-test/canonical-debug-feed-repaints-when-the-subject-changes`.
The bounded `read-until!` did not receive `debug-live-subject-marker` after
the subject changed. Isolated confirmation also failed. No root cause is
established; this is a web/feed verification boundary, not an attributed
HTML-pair defect. The lane does not own `seon.render.web`.

Evidence: `tmp/html-views/final-gate-2.log:781` and
`tmp/html-views/focused-gate.log`; the latter ran 119 tests / 837 assertions,
with zero assertion failures and this one error. The earlier plan-only gate
including `seon.render.web-test` passed 78 tests / 515 assertions.

Acceptance: the existing canonical regression receives the subject-change
marker under armed contracts, in the isolated gate and its confirmation.
Do not weaken its bounded wait or substitute a mocked feed.
