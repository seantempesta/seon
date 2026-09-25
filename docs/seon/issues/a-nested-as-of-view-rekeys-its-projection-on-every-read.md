---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, db, render, performance, agent-platform]
---

# A nested as-of view re-keys its projection on every read

`seon.db/as-of` over a value that is already an as-of view returns
`AsOfDB(AsOfDB(DB))`. Datahike does not flatten it
(`reference-code/datahike/src/datahike/api/impl.cljc:153-182`). `seon.db/as-of-key`
(db.clj:1383) reads `committed-value-identity` of the *immediate* origin, which is an
`AsOfDB` and has none. The origin also has no `:cache-context`, so `carried-projection`
(db.clj:1524-1529) falls through to `content-projection`. That rebuilds
`declaration-content-key` (every datom of the projection attributes, sorted) on every
call. `revision-source` (db.clj:843) likewise returns nil, so read evidence over the
view is never cache-eligible.

Measured on a turn-test fixture branched off default (lane fix-turn-hang-opus,
2026-09-25): `carried-projection` of the single as-of view took 1.5-2 ms after the first
call, and of the nested view 37-41 ms on every call. One prompt made ~500
`request-projection` calls, so it took 35.8 s nested against 13 ms warm (3.0 s cold)
with the observed value. The prompt text, contributions and budget report were
identical.

The producer inside the turn was fixed in 68eb9d310: `call-turn` passed the opening
value, and `render/acquire-context!` applied `turn/opening-db` again. Other callers of
`turn/opening-db` can still build a nested view if they are handed a view:
- `seon.render.transcript` :1174, :2107, :2180 (not audited);
- `seon.render.transcript` :392, `(db/as-of db tx)`.

Smallest owner fix (db.clj, `seon.db/as-of` via `database-view`): an as-of of an as-of
with integer time points is `as-of(origin, min t1 t2)`. The view then keys by its
committed origin, and both its projection and its read revision hit. Regression: nested
and flat as-of of one committed value answer `identical?` projections, and the second
call costs under 5 ms.
