---
type: issue
status: resolved
severity: friction
created: 2026-09-20
tags: [issue, publication, wave/publication-velocity]
---

# Unchanged publication identity facts still advance the branch

The fast publication pass reproduced a no-op `source/upsert!` moving
`current-src`: the third repeated B seal in the evidence/race regression
conflicted, causing four recording attempts instead of three. Raw evidence:
`tmp/publication-dissolution/owned-regression-final-fast.log`, original
`seon.cluster.source-test/latest-test-evidence-survives-rebuilding-from-an-older-base`,
failures at source_test.clj:781 and :789 in that snapshot.

`activation-seal-tx` correctly returned no changes. `upsert!` then unconditionally
appended the same source/input identity map, transacted it, and advanced the
branch because its basis changed. The correction compares those identity facts
on the privately owned candidate connection and appends them only when changed.
No other writer can change that scratch connection between this derivation and
its transaction; the final published-head comparison remains at Datahike's writer.

The existing evidence/race regression is moved, not copied, to
`test/seon/cluster/source_evidence_test.clj` so this expensive case can run without
repeating unrelated full-publication fixtures. Its platform declaration is retained
and its execution bound is explicit. Acceptance: repeated identical identity facts
leave the head unchanged and create no extra record retry; a changed digest still
advances it. Both focused source-evidence tests passed in recorded fast run `f8e9a506d7fd`.
That complete request was red elsewhere (one fixture mistake and two missing
observation fields), so no whole-request green is claimed.

The same run also exposed a stale test expectation that an empty result request
records nothing. `seon.test.runner/record-latest-tx` now emits its run event even
with zero results; the updated test positively queries that event and checks the
advanced basis. This does not permit a no-op publication to mint a new event.
