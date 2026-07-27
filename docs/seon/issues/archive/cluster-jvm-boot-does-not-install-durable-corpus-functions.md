---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, runtime, database, architecture]
---

# Install durable corpus functions into the shared SCI base

## Problem

Cluster-JVM boot no longer installs durable agent-authored corpus functions
into the shared SCI base. The capability disappeared when
`seon.host.graduate/install-nursery!` and `rebuild!` were deleted with the old
guarded door.

## Evidence

Commit `8dc8623ad5053d90f34e84803638735937778715` deleted
`src/seon/host/graduate.clj` rather than relocating its nursery/graduation
model. Git history is the reference for the removed owner.

## Owner

The surviving shared-base acquisition path must be designed fresh over corpus
facts. Select rows by presence of `:seon.fn/source`, then exclude first-party
boot rows through the same boot-process provenance join used by
`seon.db.program`. Do not restore `:seon.fn/execution-tier`, nursery,
graduation, or a retained per-agent context.

## Acceptance

- A cluster-JVM boot installs every durable corpus function selected by
  source-presence minus boot-process provenance into the one shared SCI base.
- The installed count matches the durable query result, and a forked eval can
  call one of those functions after restart.
- First-party boot rows remain available through their compiled owner and are
  not re-evaluated as authored source.
- `rg 'execution-tier|install-nursery!|graduate/rebuild!' src test` remains
  empty.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
