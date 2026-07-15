---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Native branch open runs the writing main initializer

## Problem

Every new registry entry calls the same writer connection initializer. For a
non-main native branch, that initializer validates protocol schema, installs a
listener, and calls the ordinary database initializer. When `SEON_EMBED` is
enabled, the database initializer calls `seon.embed/install!`, which always
transacts embedding attribute schema, then may transact backfill. Opening a
branch can therefore advance it before its exact fork coordinate is published.

That violates the branch lifecycle contract: the first advertised target point
must be the selected source commit with only its branch changed. Initialization
must not insert an unobserved generation between branch read-back and registry
publication.

## Evidence

- `src/seon/db/registry.clj` calls `initialize-connection!` for every newly
  opened entry without attachment-specific intent.
- `src/seon/db/writer.clj` composes that callback through
  `initialize-connection!`, which always invokes the database initializer.
- `src/seon/embed.clj` documents and implements an unconditional schema
  transaction in `install!` when embeddings are enabled, followed by possible
  backfill transactions in `initialize-database!`.
- Current branch routing tests run with embeddings disabled, so they do not
  expose the coordinate change.

The implementation plan and failure proof are in
[[../../prds/database-lifecycle-recovery/research/native-branch-create-delete-implementation-plan-2026-07-14]].

## Owner

The existing `seon.db.writer` connection initializer and
`seon.db.registry/open-entry!` boundary. Do not add a second registry or a
parallel branch boot path.

## Acceptance

- The one initializer receives explicit attachment/open intent.
- Main boot retains its existing schema/embedding initialization behavior.
- A non-main open validates inherited protocol schema and every declared
  secondary instance without a database transaction.
- The target complete coordinate is identical before and after initialization.
- An initializer that changes the target head causes release/cleanup and no
  registry publication.
- Focused tests exercise a declared live Proximum index, not only the default
  embeddings-disabled path.

## Resolution

Resolved by `3649c6b1` and `1a46d3c5`. The existing registry initializer now receives one
namespaced request containing the connection, logical route, exact attachment,
and main/branch open intent. Main retains database initialization. A non-main
open validates inherited protocol schema and every declared secondary instance,
installs the existing transaction listener, and performs no database
initialization. The registry compares complete coordinates around that callback;
a writing callback is released and never published.

The executable pre-edit probe advanced a branch by one transaction. Its
regression now proves that mutation is rejected without a registry route.
Focused registry tests pass 12 tests/67 assertions. A writer/UDS test creates a
real file-backed Proximum index, branches its exact commit, and proves the
target reopens declared and live at the unchanged coordinate while the writing
initializer runs only once for main. Review then injected failure into release
of a rejected connection. That exact resource now remains in the one registry
as `cleanup-required`, is excluded from route lookup/resolution, and retains
both initialization and release errors; neither retry nor deletion can mistake
unproved cleanup for success. The writer integration gate passes 6/51, the
registry-routing gate passes 5/40, and the complete writer checkpoint passes
71/409.
