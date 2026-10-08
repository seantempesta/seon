---
title: "Card CC-8: Datahike branches committing together share the group"
type: reference
status: active
created: 2026-10-09
---

# CC-8 Datahike branches committing together share the group

Step 3 ([README](README-commit-cost.md)), integration proof. Lane: Sonnet in the Datahike fork.

## What exists
After CC-7, konserve groups barriered writes of one store. Datahike's writer (`datahike.writer/create-thread`) commits
each branch's head through `datahike.writing/commit!`; nothing proves two branches' commits land in one group.

## Change
A fork test only: one file store, 8 branches (`datahike.versioning/branch!`), one connection per branch, 8 concurrent
17-datom transactions per burst, 12 bursts; count distinct commit ids (8 per burst: histories stay separate), store
barriers (through CC-6's recording backing) and latency.

## Functions
None in production. NEW test in the Datahike fork's `test/datahike/test/versioning_test.cljc` or a new
`commit_group_test.clj`.

## Proof
`datahike.test.commit-group-test/branches-committing-together-share-barriers` (new): 8 commits per burst, each
report's own branch and `:db-after`; store barriers per burst under 2 × 8; failure in one branch's node write fails
only that branch's report. Report latency, commits, barriers and allocation per burst beside astra §3.6's numbers.

## Cost
Test seconds; the measured result is the saving reported for CC-7.

## Durability
Unchanged; asserts no report completes before its group's second barrier.

## Commit
Fork commit; gitlink bump batched with CC-7.

## Dependencies
CC-7. Gitlink bump: yes (test only).
