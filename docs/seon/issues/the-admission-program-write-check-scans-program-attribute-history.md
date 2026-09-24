---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, test-runner, datahike, change-tracking]
---

# The admission program-write check scans program-attribute history

Seen 2026-09-24 by lane fix-test-run-cost on default (pid 90471), datahike `c79cd03a`.

## Problem

`seon.test.runner/program-written-since?` (`src/seon/test/runner.clj:930`) answers whether
any datom of the 190 program-digest attributes was written after `basis-t`. It runs a
`[?attribute ...]` query over `(db/since (db/history db) basis-t)`. Datahike's since
filter has no tx-ordered index, so the cost is O(the history of those attributes) whatever
the size of the window. Warm measurements: 119 ms / 257 MB on the head, 122–145 ms inside
`admit-run` in four one-test requests, and 104–110 ms / 118–123 MB with one grounded AEVT
seek per attribute. The research report's 1,596 ms
(`docs/research/agent-platform/agent-deftest-and-test-forks-2026-09-24.md` §1) was not
reproduced warm. It is presumably the cold read of history nodes, which is unverified.

## Why a runner-only fix is unsound

- **Basis equality.** `(= (db/basis-t db) basis-t)` is not enough. Inside a transaction the
  in-transaction value keeps the basis `:max-tx` after earlier operations have already
  written program datoms. This was probed with `d/with` over `[:db/add … :seon.fn/doc]`
  followed by a `:db.fn/call`: the basis stayed 536872680, and the probe saw one extra
  speculative attribute revision.
- **Attribute revisions alone.** Datahike's `:datahike.cache/attribute-revisions`
  (`reference-code/datahike/src/datahike/db.cljc:423`, `:444`) are process-local. A
  connection opens with none (`connector.cljc:377-381`). A missing revision therefore
  proves no write only since the connection opened, and the check has only `basis-t`.

## Wanted (dependency seam)

Capture `runner/program-revisions` of the tested value in its provenance. The admission
writer then compares it with the in-transaction value's revisions, under the same rule
Datahike's query cache uses (`source-context-unchanged?`, `query.cljc:2999-3017`): equal
connection id, generation, conservative revision and per-attribute revisions mean no write.
Touched attributes get fresh speculative revisions (`speculative-cache-context`), so a
write earlier in the same transaction is seen. The cost is O(program attributes). The
history scan stays only for a value with no revisions, such as a stored run from an
earlier JVM (`src/seon/cluster/source.clj:826`).

This needs a declared provenance key (`resources/seon/schemas/seon.test.run.edn`), the
caller in `src/seon/test.clj:1038`, and `src/seon/test/runner.clj`. It is about 20 lines.
