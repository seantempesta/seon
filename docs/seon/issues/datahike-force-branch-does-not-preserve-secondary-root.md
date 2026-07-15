---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Datahike force branch does not preserve secondary root

## Problem

The prior selected Datahike `417649383c65e13f15ea41d394fb1ed742477965`
`force-branch!` changes the Datahike config branch before `db->stored`, but it
does not move each live versioned secondary to the destination branch first.
Proximum therefore syncs the prepared-branch instance again while its returned
key map is labeled as destination branch `:db`.

Branching first is necessary but is not itself crash-safe. The prior selected
Proximum `0.1.25` rejects `branch!` when the destination already exists
(`proximum.versioning`, lines 83-88), while a post-secondary/pre-primary crash
necessarily leaves exactly that native destination artifact. Its
`load-commit` also prefers the snapshot's recorded branch over the requested
branch (`proximum.writing`, lines 225-227), so relabeling a Datahike key map
cannot convert a prepared-branch snapshot into a destination-owned `:db`
snapshot.

The file-backed restore-admin fixture installs Proximum, writes one
deterministic 1536-float vector on the selected branch, prepares the target and
undo heads, and forces main. Fresh target and main reads have the same
coordinate time, parent, EAVT, declared secondary, and one-hit KNN result, but
their secondary Merkle roots differ. Seon correctly rejects success as
`:seon.db.protocol.error/restore-divergence`.

The dependency repair is now publicly reachable at Proximum
`9846d3e79e1aee48474bc876d3d563d7137209c6` and Datahike
`9ada755087228e10cfb179fa5779ce227a6ed220`. Proximum supplies generation-
addressed mmap publication, guarded destination replacement, and cold Git
preparation on upstream `v0.1.26`. Datahike preflights every secondary before
primary publication, adopts an already-published generation on retry, migrates
the historical Datahike `"db"`/native Proximum `:main` shape, and preserves a
prepared source branch after later destination writes and cold reopen. Its
complete focused gate passes 108 tests and 570 assertions across `specs`,
`clj-pss`, and `clj-hht`. The issue remains open only until Seon's cold root
cutover and file-backed restore fixture prove those selected public artifacts.

## Owner

Proximum native versioning first, then Datahike
`datahike.versioning/force-branch!`, grounded in
[[../../prds/database-lifecycle-recovery/research/datahike-force-secondary-falsifier-2026-07-15]].
Do not change the protected `reference-code/datahike` checkout or gitlink from
the Seon lane.

## Acceptance

- Force preflights every declared versioned secondary before mutation.
- Proximum exposes a guarded native force/replace that stages the exact source
  mmap and graph, preserves the old destination until atomic publication,
  returns a genuinely destination-attached owner, and rejects a stale expected
  destination.
- Repeating the same native request after lost response exactly adopts or
  converges; a different source fails closed.
- Datahike moves the exact selected secondary snapshot to the forced
  destination and flushes that destination owner exactly once through its
  existing stored-DB mechanism.
- Failure closes every newly created secondary owner and leaves the old main
  head plus its old destination secondary readable and authoritative.
- A failure after native secondary publication but before primary head
  publication followed by the same force request converges without a second
  divergent native branch.
- A file-backed Proximum test with at least one vector proves equal target/main
  KNN results and equal audit roots after fresh reopen, forcing a prepared
  branch onto an already-existing `:db`.
- Seon's restore-admin file fixture then returns applied, releases both
  observational connections, and converges to already-applied without another
  force call after result loss.
