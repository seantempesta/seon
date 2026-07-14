---
type: issue
status: open
severity: blocker
tags: [issue, database, flow, architecture]
---

# Carry one complete database coordinate through the protocol

## Problem

The authoritative writer resolves a database point as
`{database-id, branch, commit-id, t}`, but protocol responses, transaction
events, replay pages, replica progress, turn capture, and error/cache keys still
use a logical database name plus bare numeric basis values. Two branches can
therefore contain the same numeric `t` while the active protocol cannot state
which lineage produced a value or frame.

## Evidence

- A read-only CLJ MCP probe of the live default writer returned database id
  `54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit id
  `6a56b426-c836-5817-9f6b-20584f2e81d5`, and `t` `536870929`.
- The matching CLJS MCP probe of `seon.db.replica/status` returned the same
  database id, branch, and `t`, but no commit id.
- `seon.db.protocol/transaction-event-map`, transaction responses, and replay
  responses expose `basis-t` and `basis-t-before` without a lineage point.
- `seon.db.replica/connection-coordinate` carries database id and branch;
  `progress-coordinate` adds only a numeric basis.
- Datahike `as-of` values do not expose the stored commit id. Replay resolution
  must use the maintained commit graph rather than infer identity from `t`.

The source and dependency evidence is in
[[../../prds/database-lifecycle-recovery/research/database-lifecycle-source-audit-2026-07-14]].

## Owner

One portable `seon.db.coordinate` schema and the existing
`seon.db.protocol`/writer/registry/replica boundaries. Numeric basis values may
remain internal Datahike selectors, but they cannot remain public attachment,
progress, replay, bookmark, error, or cache identity.

## Acceptance

- One closed coordinate shape contains database id, branch, commit id, and
  `t`; one attachment projection contains database id and branch.
- Ensure, transact, replay, and publication responses carry resolved
  coordinates and reject partial or mismatched lineage.
- Replica replay/live overlap rejects a non-ancestor or different attachment
  even when numeric `t` collides.
- Turn/error capture, frozen caches, bookmarks, and diagnostics use the same
  coordinate rather than copying a bare basis.
- A same-`t` two-branch test remains distinguishable through writer, protocol,
  replica, and read-back; live CLJ and CLJS MCP probes report the exact same
  default head.
- Superseded database-name-plus-basis identity maps are deleted rather than
  retained as a compatibility path.
