---
type: issue
status: open (the snapshot staleness is fixed; the hook's database source is still open)
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

## Reproduced and fixed (2026-09-26, lane fix-eval-schema-reds-opus)

Reproduced on isolated branches of default (pid 25654) with `probe.cp/f`
`[:=> [:cat :seon.db/database-value :int] :int]`, added between `db0` and `db1`.
With `current-snapshot` on one call-state, `db1` and then `db0`: `db0` was answered
with `f` prepared and checked-through 536874744, above its own basis 536874742.
A sibling branch without `f`, read through a call-state that had already answered
`db1`, also had `f` prepared. `snapshot` answered both correctly.

Fix: the latest-snapshot atom (`adopt`, `current-snapshot` and the state's
`:snapshot` member) is deleted. `snapshot` holds the completed snapshot in the
projection's memo under `[::snapshot commit-id]` (a commit fixes its basis), looks
it up first, and runs its callees unarmed on the hook's path. The hook calls
`snapshot`, and the call-state atom holds plans only. Per-call hook cost on
default (pid 33919, scratch copy against the installed hook, 20,000 calls each):
2.35-2.50 us against 2.75-4.59 us for a callee outside `prepared-symbols`.
Regression: `seon.call-preparation-test/an-older-value-and-a-sibling-branch-read-their-own-snapshot`.
The callers in `sci/eval.clj` and `sci/kernel.clj` need no change: `install` and
`hook`'s signature are unchanged.

Still open, the second evidence section below: the hook supplies against the
connection's head, not the evaluation's `:seon.db/db`. Changing it decides
read-your-writes inside one evaluation, which belongs to the repl-entry-renders
design (§2.3).

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

## Evidence 2026-09-26: the hook's plan basis is the connection, and without one it bypasses preparation

Corrected after Astra's review of `docs/research/agent-platform/repl-entry-renders-design-2026-09-26.md`
(§7 #5); an earlier version of this section wrongly said a supplied read receives the head. Probed on a
disposable branch (default f0efd9cc8 and a1b01c92a) by forking a branch agent's held context with
`seon.sci.eval/fork-for-turn` and running `seon.turn/evaluate-sources` with an explicit `:seon.db/db`:

- With a connection, the **supplier** answers at the held value: `(my.note/notes)` at an `as-of` value before
  a second note was written ⇒ one note; at the head ⇒ two. The database supplier prefers the scoped value
  (`src/seon/db.clj:2270-2295`).
- Without a connection in the environment, `hook` (`src/seon/call_preparation.clj:1111`) passes the raw
  arguments through: `(my.note/notes)` ⇒ `refused argument count … got an argument count of 0`, and
  `(my.agent/identity {})` ⇒ `missing :seon.db/db`, `missing :seon.agent/id`. The plan lookup derives its
  database only from `(:seon.db/connection environment)`.

So the defect is the hook's plan basis, not the supplier: acquire the plan from the scoped `:seon.db/db`
when present (the connection otherwise), keep supplied-argument precedence, and correct the docstring's
temporality. That is the first slice of the REPL-entry design (§5 item 3).
