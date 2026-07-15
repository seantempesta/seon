---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Datahike force branch does not preserve secondary root

## Problem

Selected Datahike `417649383c65e13f15ea41d394fb1ed742477965`
`force-branch!` changes the Datahike config branch before `db->stored`, but it
does not move each live versioned secondary to the destination branch first.
Proximum therefore syncs the prepared-branch instance again while its returned
key map is labeled as destination branch `:db`.

The file-backed restore-admin fixture installs Proximum, writes one
deterministic 1536-float vector on the selected branch, prepares the target and
undo heads, and forces main. Fresh target and main reads have the same
coordinate time, parent, EAVT, declared secondary, and one-hit KNN result, but
their secondary Merkle roots differ. Seon correctly rejects success as
`:seon.db.protocol.error/restore-divergence`.

## Owner

Datahike `datahike.versioning/force-branch!`, grounded in
[[../../prds/database-lifecycle-recovery/research/datahike-force-secondary-falsifier-2026-07-15]].
Do not change the protected `reference-code/datahike` checkout or gitlink from
the Seon lane.

## Acceptance

- Force preflights every declared versioned secondary before mutation.
- It branches the exact selected secondary snapshot to the forced destination
  and flushes that destination owner exactly once.
- Failure closes every newly created secondary owner and leaves the old main
  head authoritative.
- A file-backed Proximum test with at least one vector proves equal target/main
  KNN results and equal audit roots after fresh reopen.
- Seon's restore-admin file fixture then returns applied, releases both
  observational connections, and converges to already-applied without another
  force call after result loss.
