---
title: "FK-DH: One Datahike fork batch (blob-reference fold in the collector, norm tx-data from a value)"
type: reference
status: ready
created: 2026-10-08
---

# FK-DH: One Datahike fork batch

Ledger row FK-DH (group E). Owner answers AU-Q16 option 2 (the collector finds blob keys) and AU-Q20 option 1 (a norm
seam inside the caller's transaction). Consumers: UL-22, MA-8c. Batched with the other Datahike fork rows (DH-4a/b,
DH-5 … DH-8, VEC-9) so each restart carries one gitlink bump. Model: **Opus** (the collector's mark decides what is
deleted from disk). Host-bound: throwaway check for the bump.

## Where the fork lives (verified at Seon `1fcdbf47a`)

- `deps.edn:32-36`: `org.replikativ/datahike {:local/root "reference-code/datahike" …}`, a **local root** submodule.
- `reference-code/datahike` at `86c3301a` on branch `seon/identity-join-first`, remote `origin` =
  `git@github.com:seantempesta/datahike.git`. Commit there, push to `origin`, bump the gitlink.

## Change 1: the collector folds declared attributes' values over every retained commit

Today: `datahike.gc/gc-storage!` (`src/datahike/gc.cljc:89-170`) marks every retained commit of every branch
(`reachable-in-branch`, `:22-88`, sharing `visited` commit keys and `seen` node addresses across branches), then calls
the caller's `:datahike.gc/reachable-extension` (`:160-170`) with the branches only. Seon's extension
(`seon.cluster.registry/referenced-blobs`, `src/seon/cluster/registry.clj:479-536`) re-walks Datahike's sorted sets
per branch **head and its history**, not every retained commit: a blob referenced only from a retained older commit
(a `:db/noHistory` attribute, a purged datom, a removed schema) is outside it (astra finding 1, unproven loss risk).

- New option `:datahike.gc/attribute-values` (a set of attribute idents). For each commit the mark claims
  (`visited`), when the option is given, read that commit's AEVT index restricted to each declared attribute
  (the same `with-storage` bound index `-mark` walks, `:55-76`) and collect the datoms' `:v`; leaves already folded
  (by address, a third shared set beside `seen`) are skipped, so a leaf shared by many commits is read once.
- The extension receives `:datahike.gc/attribute-values {attr #{value …}}` beside `:datahike.gc/reachable`, so the
  caller maps values to its own keys (Seon: blob digests to konserve keys).
- A vector cell attribute is the caller's business (SR-L declares which attributes hold digests, cell members
  included); the fold returns raw values.
- Fork tests (`test/datahike/test/gc_test.cljc`): (a) an attribute value present only in a retained older commit
  (asserted, then retracted, history kept) is in the fold; (b) with `:db/noHistory` on the attribute, a value written
  then overwritten before `remove-before` is in the fold while its commit is retained and absent after the commit
  ages out; (c) two branches sharing all commits fold each leaf once (count leaf reads with a counting store, or
  assert the fold's visited-leaf count equals the distinct leaves).

## Change 2: norm tx-data from a database value

Today `datahike.norm.norm/transact-norms` (`src/datahike/norm/norm.clj:142-157`) transacts each uninstalled norm
separately against a connection, and `tx-fn` takes the connection (`((var-get (requiring-resolve tx-fn)) conn)`).
MA-8c needs one guarded merge transaction holding the program rows and every pending norm.

- ADD public `datahike.norm.norm/pending-norm-tx-data` `[db norm-list]`: for each norm not installed in `db`
  (`norm-installed?`, `:31`), in order, validate it (`validate-norm`) and contribute
  `(concat [{:tx/norm norm}] tx-data ((var-get (requiring-resolve tx-db-fn)) db))` where `tx-db-fn` is a new optional
  norm key taking the **database value**; a norm declaring only the connection `tx-fn` is refused with
  `{:error :norm/needs-connection :norm norm}` (it cannot be computed from a value). Returns one vector. The
  `:tx/norm` attribute must exist in `db` (`ensure-norm-attribute!`'s schema): if absent, the result starts with that
  schema datom so the caller's single transaction installs it.
- `ensure-norms!` is unchanged.
- Fork tests (`test/datahike/norm/norm_test.clj`): two pending norms with `tx-data` and `tx-db-fn` give one vector
  that, transacted once, installs both (`norm-installed?` true for both); an installed norm contributes nothing; a
  connection-only `tx-fn` norm is refused by data.

## Seon side (the bump commit, in root's batch)

- Files: the gitlink `reference-code/datahike`. Nothing in Seon uses either option yet (UL-22, MA-8c do).
- Proof: Datahike's fork suite (the fork's own runner: read `reference-code/datahike/README.md`/`bb.edn`; the
  previous bump ran 943 tests) green; Seon: `seon.cluster.registry-test`, `seon.maintenance-test`,
  `seon.blob-publication-test`, `seon.db-test` green.
- `--test seon.cluster.registry-test --test seon.maintenance-test --test seon.blob-publication-test --test seon.db-test`
- Commit: `bin/lane commit <lane> -m "FK-DH: datahike fork folds declared attribute values over retained commits and computes pending norm tx-data from a value" --test seon.cluster.registry-test --test seon.maintenance-test --test seon.blob-publication-test --test seon.db-test reference-code/datahike`

## Cost

Collection: AEVT leaves of the declared attributes, each read once per collection (a fraction of the mark, which
already reads every node). Norms: none until MA-8c.

## Deleted

Nothing in this row; UL-22 deletes Seon's sorted-set walk in `referenced-blobs`.

## Dependencies

LR-CACHEb (the Seon cache kind on the current gitlink) before the bump; the other Datahike fork rows ready at that
time ride the same bump. UL-22 (with SR-L) and MA-8c after this.
