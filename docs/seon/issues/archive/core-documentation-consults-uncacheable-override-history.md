---
type: issue
status: resolved
severity: friction
tags: [issue, sci, database, testing]
---

# Core documentation consults uncacheable override history

S3's documentation note queried historical first-party membership for every
function, including current core admissions. History evidence is deliberately
uncacheable, so unrelated writes caused core documentation to rerun.
The canonical `seon.rereads-test/failed-evaluations-are-not-promoted-and-documentation-follows-its-evidence`
exposes this at its unchanged-documentation assertion.

Owner: `seon.sci.eval/documentation-value` and `function-doc-map`. The current
row's admission settles core versus agent before historical membership is
needed. The selector carries that admission; only agent admissions consult
the existing override query. No new stored flag or alternate query owner.

Acceptance: the unchanged canonical rereads regression passes, and override
documentation continues to show its derived note. See [the residue landing](../../prds/steward-platform/research/acquisition-s3-residue-2026-09-17.md)
for iteration results and the live-adoption boundary.

The unchanged rereads regression and the complete documentation namespace
passed in the HEAD-plus-owned-paths fast worktree on 2026-09-17. Cold and
platform integration evidence remain the orchestrator's responsibility.
