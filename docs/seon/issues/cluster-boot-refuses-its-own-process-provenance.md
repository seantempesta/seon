---
type: issue
status: open
severity: blocker
tags: [issue, database, boot, datahike]
---

# Seed the cluster's process row before naming it as provenance

## Problem

**No cluster can boot from a clean operator root.** `seon.cluster/seed-cluster!`
commits the process entity and the cluster entity in ONE transaction while
naming that same process as the transaction's provenance:

```clojure
{:tx-data [{:seon.db.process/id process} desired]
 :tx-meta {:seon.db/process [:seon.db.process/id process]}}
```

Datahike resolves tx-meta datoms **before** tx-data: `transact-tx-data`
prepends `meta-entities` to the entity list on a history-keeping database, so
`transact-add` calls `entid-strict` on `[:seon.db.process/id …]` while that
entity still does not exist. The transaction aborts, `require-committed!`
refuses, and the tower dies between config apply and the root-agent seed.

Every other site does this correctly — the process row is committed by an
earlier transaction (`missing-process-rows` for the boot and config-managing
identities, `seon.render.web:1106-1108` for the web process) and only then
named as provenance. `seed-cluster!` is the one place that inverted it.

The tower is otherwise sound: with the ordering corrected in-process, the same
HEAD boots the complete tower (store → fork → schema accretion → recovery →
config → cluster seed → root agent → work launcher → agent arm → web) and runs
real turns end to end.

## Evidence

- `src/seon/cluster.clj:782-816` — `seed-cluster!`, introduced whole by
  `c189a3d12` ("Add cluster-owned instruction facts", 2026-07-31 13:06). The
  defect is as old as the function; nothing regressed into it.
- `reference-code/datahike/src/datahike/db/transaction.cljc:1232-1237` —
  `meta-entities` are `concat`'d ahead of `initial-es'` when
  `-keep-history?`; `:1205` is the enclosing `transact-tx-data`.
- `reference-code/datahike/src/datahike/db/utils.cljc:141-146` —
  `entid-strict` raises `:entity-id/missing`.
- Live boot at clean HEAD `24aaacbac`, isolated operator root
  (`tmp/preflight-head`, its own process-root store, no other cluster
  involved):

  ```text
  :error datahike.db.utils Nothing found for entity id
    [:seon.db.process/id "86232-1785524780056"]
  Execution error (ExceptionInfo) at seon.cluster/refused! (cluster.clj:84).
  The cluster population transaction was refused.
  ```

  Full log: `tmp/preflight-head/data/clusters/preflight-mvp/logs/seon.log`.
- The two-transaction workaround that makes the same HEAD boot:
  `tmp/preflight-drive.clj` (process row first, then the cluster entity with
  provenance).
- Step-by-step chain evidence:
  `docs/prds/sci-execution-runtime/research/turn-loop-preflight-2026-07-31.md`.

## Owner

`src/seon/cluster.clj` (`seed-cluster!`).

## Acceptance

`bin/seon start <name>` stands the tower on an operator root that has never
booted before, and a recurring test covers the class rather than this one call
site: a boot-path assertion that every transaction naming
`:seon.db/process` provenance resolves that entity at `db-before`. The current
`seon.cluster.boot-test` suite did not catch this, so the replacement proof
must exercise the real `start!` population path.
