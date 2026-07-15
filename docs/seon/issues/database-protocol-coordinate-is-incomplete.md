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
- Datahike `as-of` values do not expose the stored commit id. Replay must pin a
  real containing commit rather than infer identity from `t`.
- Transaction replies, recovered receipts, feed events, replay pages, replica
  progress, and own-write correlations now use the canonical coordinate.
  Replay freezes one containing commit and proves the initial cursor commit is
  an ancestor; the full JVM and CLJS gates pass.
- `seon.db/at-coordinate` now resolves a complete point without guessing from
  t: CLJS asynchronously loads the named retained commit, proves the current
  database/branch attachment, validates the cut inside that container, and
  returns structured errors for partial, missing, mismatched, or out-of-range
  coordinates. The focused proof passes 2 tests/11 assertions.

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

## Remaining

Turn capture, autocomplete exports, error capture/reproduction, and historical
web feeds now carry and resolve the complete coordinate. Turn proof passed 11
tests/73 assertions; error proof passed 17/116; web proof passed 36/180; and the
combined downstream proof passed 64/369. Rebuilt live turn `ep2np287dio2` and
error eid `3097` each stored all four facts, and resolving either point returned
its pre-record value while excluding the later capture datom. A rebuilt exact
historical feed echoed default point `54b5b7e7-51fb-3220-b079-81a81914d86f`/
`:db`/`6a56e443-1025-554f-80b6-e81e9793e0ca`/`536870968` and returned a frozen
gzip Datastar frame; a t-only selector returned structured 422 instead of live
data. The reconcile result, config-view cache, and native branch lifecycle still
need the same coordinate; the issue remains open until those identity copies
are removed.
