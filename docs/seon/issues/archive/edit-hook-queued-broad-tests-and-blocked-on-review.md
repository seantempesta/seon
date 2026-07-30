---
type: issue
status: resolved
severity: blocker
tags: [issue, tooling, testing]
---

# Keep edit feedback bounded and non-queueing

## Problem

The post-edit hook launched an asynchronous changed-test worker. Central edits
widened to dozens of namespaces, each run could consume the five-minute test
timeout, and newer generations accumulated while the current generation ran.
The same hook also called Gemini synchronously when its interval elapsed, so
an otherwise valid edit could wait up to 60 seconds for advisory review.

## Evidence

On 2026-07-30, changed-test worker PID `49013` had remained alive for roughly
40 minutes and its Java child PID `67007` consumed 158–178% CPU. One timed-out
generation was followed immediately by another accumulated generation. The
Seon operator PID `35516` was idle; the load belonged entirely to automatic
test feedback. Three synchronous Gemini-boundary edits had also taken as long
as 58 seconds.

## Resolution

Automatic changed tests and their hook worker lifecycle were deleted. The
retained changed-test selector runs only when explicitly invoked at a coherent
checkpoint. Post-edit feedback now performs bounded static checks and
`current-src` publication synchronously.

Gemini review is optional and asynchronous. Reviewable paths deduplicate into
one two-minute window owned by one recorded Babashka PID. The worker performs
one call with a 60-second timeout, consumes the snapshot even when Gemini is
missing or fails, and exits. Edits arriving during the call retain distinct
entry IDs and receive one later window; review workers never overlap.

Focused proof passed 8 tests and 28 assertions. The lifecycle regression sent
two edits through isolated state, observed the same worker PID and one pending
path, forced the provider to fail, then proved one provider call, an empty
batch, a removed PID record, and worker exit. A live production window likewise
produced one artifact and left no review, provider, or test process alive.
