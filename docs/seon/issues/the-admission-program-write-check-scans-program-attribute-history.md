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

## 2026-09-24 finding (lane fix-unbounded-failure-text): the writer's chain never carries committed revisions

I implemented the design above (patch `tmp/fix-unbounded-revisions-slice.patch`) on a
scratch root built from `f3788c020`. It was not committed. It is correct against pure
`d/with` values: equal revisions answered `false` in 0.13–0.26 ms / 0.08 MB, where the
history scan took 379–565 ms / 250 MB. A touched `:seon.fn/doc` answered `true`, which
agrees with the scan. In a real `seon.test/run`, however, the admission still scanned
(`program-written-since?` 691 ms). The cause is in Datahike.

- The commit loop publishes `commit-db` with `advance-query-cache-context`, which uses the
  commit id as the revision (`reference-code/datahike/src/datahike/writer.cljc:246-253`,
  `query.cljc:2582`).
- The transaction thread applies the next transaction to its own chain value. That value
  keeps the speculative context of the previous report, where every touched attribute
  has a `random-uuid` revision (`db/transaction.cljc:1064-1070`, `db.cljc:448-463`).
- Probe: one `[:db.fn/call …]` transaction, with no other writes pending. The in-transaction
  context and `(:db-before report)` agree with each other, and both differ from `@conn`
  on 130 attribute revisions at the same `:max-tx`. Every attribute written since the
  writer started therefore compares unequal. The comparison is sound because a mismatch
  scans, but in production it never matches.

The owner is the Datahike fork writer. After a commit, the chain value that the next
transaction bases on should adopt the committed context when no later report is pending,
or the committed revisions should be carried forward onto the chain. Once that seam exists,
the Seon slice above is about 25 lines.
