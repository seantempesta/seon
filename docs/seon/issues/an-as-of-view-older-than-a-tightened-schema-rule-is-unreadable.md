---
type: issue
status: open
severity: defect
tags: [issue, datahike, schema, as-of, performance]
---

# An as-of view older than a tightened schema rule is unreadable

## Problem

`seon.db/carried-projection` derives an as-of view's projection from that
view's own declaration rows (`src/seon/db.clj`, `as-of?` branch). Those rows
are rebuilt under today's registration rules. When a rule tightens, every
as-of view older than the change refuses every query.

- `e86043f81` (17:17Z 2026-09-24) made `register!` refuse `[:or :nil X]` and
  removed `:nil` from `:seon.flow/callback-result`.
- Since then, on default, `seon.db/q` against `(seon.db/as-of db t)` returns the
  refusal "schema/register! :seon.flow/callback-result: [:or :nil :map …]" for
  every one of root's 19 evaluation settlement transactions
  (536870928 … 536871170; head 536871897).
- The same reads returned results at about 17:00Z, before that commit.

So history stops being readable through `seon.db` once any rule tightens.
`seon.error/occurred-since` over an as-of view is one of the callers that
depends on it: evaluation responses read their errors through it (lane
errors-in-agent-responses). Today those responses show this refusal as a typed
unknown.

## Cost, the same seam

The as-of projection key includes the origin's commit id (`as-of-key`).
- Each new commit on a branch therefore re-derives the projection for every
  as-of time point, even though an as-of view at `t` of the same branch does
  not change.
- Measured on default: the first as-of read per new commit costs about 30–76 ms
  per time point; warm reads cost about 2.6 ms.

## Wanted

- An as-of view reads its old population under the rules that admitted it,
  or through the current projection when its declaration datoms are unchanged.
  It never refuses because of a later rule.
- Its projection is keyed so that later commits do not invalidate it.

The owner is `seon.db` / `seon.schema`.
