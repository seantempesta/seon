---
type: issue
status: resolved
severity: friction
tags: [issue, flow, error, render]
---

# Collapse repeated identical core faults into one bounded record

## Problem

One core cause could be printed and committed repeatedly as giant identical
stack traces even though `:seon.error/signature` already content-addressed the
same failure. The dead-writer incident rendered the same cause repeatedly.

## Evidence

The fault path enters `seon.flow/fault-committer-proc` and the cluster fault
handler in `src/seon/cluster.clj`. The recorded incident contained 878
identical panic lines plus repeated writer-shutdown failures for one signature.

## Owner

The existing Flow fault committer and cluster fault-recording boundary. Repeat
collapse derives from `:seon.error/signature`; it is not a new counter or
second error path.

## Acceptance

One signature produces one bounded durable fault record and one bounded human
face; repeated delivery creates no duplicate record or stack dump. A distinct
signature still records independently, and focused recurring proof exercises
both cases.

## Resolution — 2026-08-03

Commit `7f09f6569` gives the fault-committer proc a disposable set of observed
signatures, normalizes the signature before any write, and queries the durable
signature after a proc rebuild. Follow-up commit `89c62e119` makes the proc
treat that durable `:seon.flow/already-committed` outcome as already reported,
so a fresh proc does not print the same signature again. The cluster fault
boundary bounds both the durable message and the one-line development face
from the declared blob inline ceiling. A dead writer therefore cannot turn
retries into repeated stack traces or transaction attempts.

The pre-fix direct probe observed one panic from a fresh proc for an already
durable signature; the same probe after `89c62e119` observed zero panics and
zero increment to the proc's panic count while retaining the signature in its
disposable state. Commit `9b1b0c766` extends the database-backed rebuild
regression: the durable duplicate adds no fact and no stderr output, while the
next distinct successful commit adds one fact and exactly one bounded panic
face. `bin/test seon.flow-test` passed 25 tests / 234 assertions. The combined
`seon.flow-test`, `seon.test-runner-test`, and `seon.cluster.mcp-test`
checkpoint passed 36 tests / 282 assertions with zero failures or errors.
