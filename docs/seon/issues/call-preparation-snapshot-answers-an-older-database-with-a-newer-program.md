---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [call-preparation, correctness]
---

# Call preparation answers an older database value with a newer program's supplied defaults

## Source reading (HEAD 5f98ca9b3; not yet reproduced)

`seon.call-preparation/current-snapshot` (`src/seon/call_preparation.clj:593`) holds one
snapshot per call-state atom. The atom is created by `state` (`:78`) and attached to a cluster
context by `install`, which `sci/eval.clj:3415,3475` call; every `sci/fork` of that context
shares the atom by identity. `current-snapshot` returns the held snapshot whenever
`(>= checked-through-t (basis-t database))` (`:612`), and `adopt` keeps only the newest
checked-through basis (`:582`).

So a call whose database value is OLDER than the held snapshot's basis is answered by the newer
snapshot. That covers:
- an `as-of` or captured earlier value;
- a branch whose connection has a lower basis, sharing one cluster context's call-state.

The newer snapshot carries the newer program's `supplied-defaults`, `prepared-symbols` and
`validators`. `hook` then uses it to decide whether a callee is prepared and which supplier fills
which slot. `plan` (`:866`) caches plans against that snapshot's derivation, and caches only when
`(= (basis-t database) checked-through-t)` (`:884`), so an older value recompiles a plan from its
own rows but joins it to the newer snapshot's supplied defaults.

Expected: a supplied-default answer is a function of the database value it was asked about.
"A newer basis is not a valid answer for an older captured database"
(`docs/research/agent-platform/atom-audit-2026-09-26.md`, A009).

## Reproduction plan (needs SCI; default was degraded when this was filed)

1. On a disposable branch of default, capture `db0`. Transact a supplied-default row for a new
   key `K` and a contracted test function `f` whose first slot is `K`'s schema. Capture `db1`.
2. `(def cs (seon.call-preparation/state))`, then
   `(seon.call-preparation/current-snapshot cs db1 (seon.db/carried-projection db1))`.
3. `(seon.call-preparation/current-snapshot cs db0 (seon.db/carried-projection db0))`. Expected:
   `f` is not in `:seon.call-preparation/prepared-symbols` and `K` is absent from
   `:seon.call-preparation/supplied-defaults`. Predicted from source: both are present, because
   the db1 snapshot is returned.
4. Branch case: fork two branches from one commit, advance one, and drive calls from both through
   one cluster context's hook. Observe which supplier fills `f` on the lower-basis branch.

## Fix (lands once SCI is healthy)

Delete the "latest" call-state atom (atom audit R4, A008/A009). Answer `current-snapshot` from the
projection-held snapshot memo (`snapshot-memo`, C01), keyed by the value's commit identity first.
Call its internals unarmed on the hook path. Hold plans in a projection-held LRU keyed by the
snapshot's revision key plus symbol. Measured on default (jvm, 20,000 iterations): the atom path
costs 0.50 µs per call and the current memo path 2.94 µs, so the hook (about 8.5 µs per SCI Var
call) must be proven within 20% before landing. Consumer conversion sits outside
`call_preparation.clj`: `install` in `sci/eval.clj:3415,3475`, `hook` in `sci/kernel.clj:642` and
`sci/eval.clj:293`, and the reuse of `:seon.call-preparation/state` as `:seon.render/cache`'s type
in `seon.render.edn:1,44,51`. The regression is the reproduction above, asserting the older
value's own snapshot and the branch's own supplier.

## Evidence 2026-09-26: the hook supplies the connection's head, not the evaluation's value

This was observed live by design lane design-repl-entry-renders
(`docs/research/agent-platform/repl-entry-renders-design-2026-09-26.md` §0 D7 and §2.3), on
archive 052552039, from a JVM scratch namespace. The test forked a branch agent's held context
with `seon.sci.eval/fork-for-turn`, removed `:seon.db/connection` from both the fork's
`::custody` and its environment, and ran `seon.turn/evaluate-sources` with an explicit `:seon.db/db`:

```
(seon.db/q '[:find (count ?n) . :where [?n :my.note/id]])   ;=> 3   (explicit read works)
(my.agent/identity {})  ;=> my.agent/identity refused request at [:seon.db/db]: … missing :seon.db/db
                        ;   … refused request at [:seon.agent/id]: … missing :seon.agent/id
```

`hook` (`src/seon/call_preparation.clj:1201-1205`) derives `database` only from
`(:seon.db/connection environment)`, as `(db/db connection)`. It never reads the scoped
environment's `:seon.db/db`, which `evaluate` sets from the request (`sci/eval.clj:3977`). So:

1. With a connection present, a call is prepared and supplied against the connection's **current
   head**, not the value the evaluation holds. That is the same wrong-value class as above, now
   from the live hook.
2. With no connection (a read-only fork), nothing is supplied, not even `:seon.agent/id` from the
   turn layer.

A read-only render or preview therefore needs the fix here: supply `:seon.db/db` from the
environment's turn-layer value, with the connection only where a callee declares
`:seon.db/connection`.
