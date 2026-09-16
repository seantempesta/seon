---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, operator, database, test]
---

# Newer issue assignment and test evidence disappeared during development work

## Problem

Default's live facts lost a worker assignment and returned to an older red
test result while source adoption and concurrent in-process checks were
active. The exact writer responsible has not been established. Do not infer
that issue indexing caused the test-evidence replacement from timing alone.

## Evidence

Issue-family used default PID 7595 without restarting it. History for issue
entity 43695 records agent 55159 asserted at transaction 536871299 and
retracted at 536871691, whose txInstant is 2026-09-16T02:57:33.987Z. The latter
transaction had no process or adoption metadata in the explicit pull. The
note moved to the archive and now has superseded lifecycle; the worker
entity still exists. A transient absence from a publication snapshot is one
hypothesis, not a verified cause.

The source-publication regression returned 21/0/0 in-process at run entity
68457, basis 536871714 (03:01:13Z), but a later live issue status returned its
older 8/12/0 result, run 0fd21830cbfa, basis 536870922. The complete returned
green result survives in
`docs/prds/steward-platform/research/issue-family-publication-proof-2026-09-16.edn`.
This is different from the source branch rebuild regression, which passed.

## Owning boundaries

`src/seon/cluster.clj` development refresh first reconciles the program and
then calls seon.issue/adopt!. `src/seon/issue.clj` adopt-tx preserves worker
attributes for present notes and removes facts for absent notes. Program
reconciliation and test-result recording are separate owners. Probe the
transaction that replaces test evidence and the snapshot that omits an issue
before choosing a fix. Do not change another lane's active files on this note's
unproven attribution.

## Acceptance

A canonical adoption regression records a newer local test result and a
started issue, adopts the same indexed note after a path move, and verifies
that both local observations survive. Explicit note removal has a separately
stated lifecycle; a temporary publication input absence must not silently
masquerade as an intentional removal. Record the responsible writer and
before/after transaction evidence.
