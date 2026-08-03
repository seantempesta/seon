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
signature after a proc rebuild. The cluster fault boundary bounds both the
durable message and the one-line development face from the declared blob
inline ceiling. A dead writer therefore cannot turn retries into repeated
stack traces or transaction attempts.

`bin/test seon.flow-test` passed 25 tests / 224 assertions. The regression sent
the same signature twice and a distinct signature once: it observed two write
attempts, two bounded lines, and two signatures. The writable case retained
one fact per distinct signature.
