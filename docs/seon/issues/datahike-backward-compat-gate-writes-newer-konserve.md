---
type: issue
status: open
severity: friction
tags: [issue, database, testing]
---

# Pin the backward-compatibility writer below the maintained Konserve version

## Problem

Datahike's complete `bb test` gate creates its backward-compatibility fixture
with the latest released Datahike, whose Konserve version can be newer than the
maintained fork's selected version. The gate then asks the maintained fork to
read a database that its version fence correctly rejects.

## Evidence

On 2026-07-23, the gate cloned the latest released Datahike and wrote the
fixture with Konserve `0.9.363`. The maintained fork selects
`0.9.359-seon.1`, so `datahike.connector/version-check` returned
`:db-was-written-with-newer-konserve-version` before the compatibility read.
The preceding complete Clojure, specification, and Kabel matrices passed
1779 tests / 9332 assertions, 874 / 4609, and 16 / 81 respectively. The
retained transcript is `tmp/orchestrator/querycache-gate.log`.

## Owner

`reference-code/datahike/bb/src/tools/test.clj` owns selection of the released
writer used by the backward-compatibility fixture. It must select a release
whose persisted format is no newer than the maintained dependency set, rather
than weakening `datahike.connector`'s version fence.

## Acceptance

- The backward-compatibility fixture records the exact released Datahike and
  Konserve versions that wrote it.
- The maintained fork reads that genuinely older fixture successfully.
- A fixture written by a newer Konserve version remains rejected.
- The complete `bb test` gate passes without changing the maintained Konserve
  selection as a side effect of the test.
