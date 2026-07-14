---
type: issue
status: resolved
severity: friction
tags: [issue, agent, flow]
---

# Reference repository edits widen into the complete pod test gate

## Problem

The root changed-test operation treated Clojure files inside independent
`reference-code/` repositories as first-party Shadow inputs. Every maintained
dependency edit waited for a manifest entry that could never exist and then
ran the complete pod suite.

## Evidence

Editing `reference-code/datahike/src/datahike/query.cljc` on 2026-07-14 waited
for the root Shadow manifest, widened as an unknown host resource, and ran
1,301 tests/6,159 assertions. A simultaneous queued edit also produced corrupt
Shadow cache reads, demonstrating why dependency-local and root suites must not
be launched implicitly from the same shared-tree patch cadence.

The Datahike checkout is an independent Git repository with its own focused
tests. Seon's root graph becomes responsible when `deps.edn` advances to the
new Datahike commit.

## Owner

`seon.dev.changed-test` and the existing root edit hook.

## Acceptance

An edit below `reference-code/` returns a bounded no-root-tests decision with
an explicit independent-repository reason. Datahike's own focused tests run in
its repository, and changing Seon's dependency SHA selects the retained root
boundaries exactly once.

## Resolution

Resolved on the runtime-reliability branch. `seon.dev.changed-test` now
classifies `reference-code/` as independent dependency repositories before it
consults the root host or Shadow graphs. The original Datahike path now returns
`no-affected-tests` with an `independent-reference-repository` reason in 0.64
seconds. Focused operator proof passes 14 tests/34 assertions; advancing
`deps.edn` remains the point where Seon's retained boundaries run.
