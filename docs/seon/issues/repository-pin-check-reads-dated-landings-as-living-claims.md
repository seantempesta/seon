---
type: issue
status: open
severity: defect
created: 2026-09-25
tags: [issue, tooling, docs]
---

# The repository pin check reads dated landing notes as current claims

## Problem

`seon.dev.markdown/validate-repository-pins` reports 66 stale pins across 46
files. That makes `seon.dev.markdown-test/repository-dependency-pins-are-current-test`
red. Most of the hits are dated point-in-time evidence: landing notes
(`type: landing`), Astra reviews and PRD wave specs that cite the pin they
measured against. For example,
`docs/research/agent-platform/astra-review-constructor-cost-2026-09-23.md` has 6
hits and `docs/prds/agent-platform/landing/lane-base-export-timeout-2026-09-22.md`
has 2. The currency rule exempts only `type: research` and `type: issue`
(`script/seon/dev/markdown.clj`, the `run-rules` guard and the matching guard
in `validate-repository-pins`). So a landing, or a review without that type,
counts as a living claim.

Until 2026-09-25 the edit hook ran this walk on every Markdown edit: 1.55 s of
a 1.96 s event. The 66 foreign pins then crowded the edited file's own findings
out of the 300-token feedback budget. Lane verify-edit-hook (`af3246bde`) moved
the hook to per-file pin checks. The repository-wide test is still red.

## Evidence

The measurement ran in Babashka on 2026-09-25 with
`(md/validate-repository-pins {::md/repository-root "."})`. It returned
`pins ms 1554 violations 66 files 46`.

## Done when

Either the exemption is derived from what the document is (a dated
landing/review is historical evidence), or the living documents among the 46
cite current gitlinks. The rule should decide this by frontmatter, not by a
path list. `repository-dependency-pins-are-current-test` then passes.
