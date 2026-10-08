---
title: "UL-8: Batches of pulls use pull-many"
type: reference
status: ready
created: 2026-10-08
---

# UL-8: Batches of pulls use `pull-many`

Ledger row UL-8 (group M/E). Source: `reinvention/reinvention-astra.md` item 25. Model: Sonnet. Split in two: UL-8a is
an ordinary `my.*` read (REPL-first), UL-8b is store-owner collection code (throwaway check).

## Seams (verified at `1fcdbf47a`)

- `seon.db/pull-many` (`src/seon/db.clj:3059-3100`): `[database selector eids]`, contract returns
  `[:or [:vector [:or :nil :seon.db/pulled-entity]] :seon.db/invalid-read-error :seon.db/pull-budget-error]`; it
  calls Datahike's `pull-many` through `pull-call` with read evidence, like `seon.db/pull` (`:3011`).
- `datahike.pull-api/pull-many` (`reference-code/datahike/src/datahike/pull_api.cljc:541-556`, pinned `86c3301a`):
  "one input-aligned eager result for each entity ref. Well-formed missing refs return nil in their original
  positions. Malformed refs and lookup attributes without uniqueness remain errors." So a lookup ref
  `[:seon.fn/sym s]` naming no entity answers nil at its position, which is what the fallback below needs.

## UL-8a: `my.program/caller-data` reads its callers in one batch

`my.program/caller-data` (`src/my/program.clj:105-154`) today, per caller: one `db/pull` by `[:seon.fn/sym c]`, on
nil a second by `[:seon.test/sym c]`, then one `db/q` for that entity's `:seon.fn/call-arities`; and one `db/pull`
per declared arity entity of the subject. For `n` callers and `k` arities that is up to `3n + k + 2` reads.

- File: `src/my/program.clj`, `test/my/program_test.clj`.
- CHANGE `my.program/caller-data` `[database subject entity]`, contract unchanged:
  `[:=> [:cat :seon.db/database-value :seon.program/subject :int] [:map [:seon.program/callers {:optional true} :seon.program/callers] [:seon.program/call-sites {:optional true} :seon.program/call-sites]]]`.
  - `declared`: `(db/pull-many database [:seon.fn.arity/min :seon.fn.arity/max] arities)`, refusal thrown as today
    (`(throw (ex-info (:seon.error/message r) r))` on `:seon.db/invalid-read`, the file's existing shape).
  - sites: `sorted (sort callers)`; `functions (db/pull-many database [:db/id :seon.fn/form-span :seon.fn/call-arities] (mapv #(vector :seon.fn/sym %) sorted))`;
    the positions that answered nil are pulled once more by `[:seon.test/sym c]` with the same selector (one second
    `pull-many` over only those). `:seon.fn/call-arities` comes from the pulled entity (it is
    `[:find [?tuple ...] … [?entity :seon.fn/call-arities ?tuple]]` today, the same attribute), so the per-caller
    query goes. Check at the REPL that a pulled cardinality-many tuple attribute answers the same set the query did
    (`(= (set (:seon.fn/call-arities pulled)) (set queried))` on `seon.turn/open?`'s callers).
  - The site map is built as today from `[caller pulled]` pairs; order stays `(sort callers)`.
- Proof: existing `my.program-test/program-reads-name-the-referrers-and-propose-without-writing` green, plus one
  assertion added to it: every call site carries `:seon.fn/declared-arities` equal to the subject's pulled arities,
  and the site for a caller with a recorded call arity carries `:seon.fn/call-arity`.
- REPL-first: ordinary. On the lane's branch: `(my.program/callers {:seon.program/subject 'seon.turn/open?})` before,
  `in-ns 'my.program`, `defn` the new `caller-data`, call again, compare the two values with `=`; read both replies'
  timing. Then run the reaching tests there and write back. Throwaway check until RF-2.
- `--test my.program-test --test my.program-read-test`
- Cost: expected 2 to 3 batched reads plus the two `names-through` queries, instead of `3n + k + 2`; measure
  `callers` on `seon.turn/open?` on parent and change at the branch REPL and report both.
- Commit: `bin/lane commit <lane> -m "UL-8a: my.program/callers reads its call sites with pull-many" --test my.program-test --test my.program-read-test src/my/program.clj test/my/program_test.clj`

## UL-8b: `retention-cutoff` pulls each branch's configurations in one call

`seon.cluster.registry/retention-cutoff` (`src/seon/cluster/registry.clj:592-626`) pulls each configuration entity
with `d/pull` inside a `mapv` over a `d/q` result (a store owner: direct `datahike.api` is allowed here).

- File: `src/seon/cluster/registry.clj`.
- CHANGE the inner `mapv` to
  `(mapv #(assoc % :seon.store/branch branch) (d/pull-many database [:seon.config/cluster :seon.config.db/snapshot-window-ms] (d/q '[:find [?e ...] :where [?e :seon.config/cluster]] database)))`.
  Contract unchanged: `[:=> [:cat :seon.store/store] :inst]`. Nothing else moves.
- Proof: existing `seon.cluster.registry-test/implicit-retention-requires-declared-cluster-windows` and the
  maintenance tests that collect (`seon.maintenance-test/collection-reports-and-verifies-the-exact-store`) green.
- Throwaway check (collection is store-owner platform code).
- `--test seon.cluster.registry-test --test seon.maintenance-test`
- Cost: one pull per branch instead of one per configuration entity (one per cluster today, so the gain is small;
  the row exists for the one-batch rule).
- Commit: `bin/lane commit <lane> -m "UL-8b: retention-cutoff pulls each branch's configurations in one call" --test seon.cluster.registry-test --test seon.maintenance-test src/seon/cluster/registry.clj`

## Deleted

The per-caller `db/q` and the second per-caller pull in `caller-data`; nothing else.

## Out-of-scope finding

`retention-cutoff`'s refusal message builds text with `pr-str` (`registry.clj:~611`), which the one-printer law
forbids; the values already ride in the ex-data. Belongs to the errors cut (convert to the constructor with the
undeclared clusters as data); noted for root on this row.

## Dependencies

At `1fcdbf47a` neither file is claimed (led-nk1 and led-cacheb hold nothing). SR-ENUMa and SR-L also edit
`registry.clj`: UL-8b is three lines, land it before or rebase onto theirs.
