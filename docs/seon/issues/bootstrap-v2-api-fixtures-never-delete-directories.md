---
type: issue
status: open
severity: cleanup
tags: [issue, testing, database, storage]
---

# Bootstrap v2 API fixtures never delete their Datalevin directories

## Problem

The deleted bootstrap-v2 tests created paired embedded Datalevin directories,
closed their connections, and never removed the directories on success or
failure.

## Evidence

At commit `c07d321e4`, `src/seon/test/bootstrap_v2.clj:801-840` creates
`tmp/bootstrap-v2-api-*` and `tmp/bootstrap-v2-api-graph-*`; its `finally`
contains only two `d/close` calls. The shared fixture at lines 602-622 has the
same omission for `bootstrap-v2-domain-*` and `bootstrap-v2-graph-*`. The
emergency transcript recorded dozens of API/graph pairs before deletion.

## Owner

Deletion of the obsolete test quarry, and any fresh embedded file-database
fixture derived from its lessons.

## Acceptance

- After listener shutdown and connection close, the fixture deletes both exact
  claimed directories in its outer `finally`.
- An exception at every setup/body/teardown boundary leaves neither member of
  the pair.
- No future embedded database fixture creates an unclaimed timestamp-named
  directory.
