---
title: "Card TX-5: Seeding a cluster's facts is one transaction"
type: reference
status: active
created: 2026-10-08
---

# TX-5 Seeding a cluster's facts is one transaction

New row (group M, E). Census: a test request's checkpoint branch gets four transactions from `prepare-checkpoint!`
(`src/seon/test.clj:1773-1798`): config reconcile 120, process 5, cluster/toolkit 18, source commit 4 datoms
(`tx-census-astra.md` "First check"; Opus §2: `reconcile-call` 120, then 5, 18, 4). A warm cluster boot writes the same
family (Astra: t925-t930, six transactions, four of them tiny). Owner: `seon.cluster/seed-cluster-facts!`
(`src/seon/cluster.clj:3084-3097`) = `config/apply-compiled!` (`src/seon/config.clj:750`) + `ensure-cluster-entity!`
(`cluster.clj:3005-3082`, which writes the process row first in its own transaction because "transaction metadata
cannot resolve a lookup ref introduced by that same transaction", `:3013-3027`). Lane: Sonnet. Cited at HEAD
`e7f120672`.

## Step 1 (probe, 5 minutes, decides one branch of the change)
On a disposable branch (JVM mode, root's own scratch branch via `my.agent/create-branch!`), `d/with` a transaction
holding `{:db/id "p" :seon.db.process/id "probe-proc"}` and `{:db/id :db/current-tx :seon.db/process "p"}` (a tempid
ref from the transaction entity, in tx-data, not `:tx-meta`). If the pulled transaction entity's `:seon.db/process`
resolves to the new row, the process row joins the one transaction (below); if Datahike refuses, the process row keeps
its own transaction **only when it is new** (once per process per branch) and everything else joins. Write the
outcome into this card's commit message.

## Change
- ADD `seon.config/apply-compiled-tx`
  `{:malli/schema [:=> [:cat :seon.db/database-value :seon.config/compiled] :seon.store/transaction-data]}`
  `[database compiled]`: the transaction data `apply-compiled!` commits today after `accrete-entity-attributes!`
  (the reconcile request's `[:db.fn/call … reconcile-call …]`; read `apply-compiled!` `:750-830` for the exact call it
  transacts). `apply-compiled!` becomes accrete + transact of it.
- ADD `seon.cluster/cluster-entity-tx`
  `{:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster/name] :seon.store/transaction-data]}`: the
  `desired`/retract data `ensure-cluster-entity!` builds (`:3028-3081`), `[]` when current.
- CHANGE `seed-cluster-facts!`: `accrete-entity-attributes!` (schema only, commits only when attributes are missing)
  then ONE `db/transact!` of `(concat process-row-tx (apply-compiled-tx db compiled) (cluster-entity-tx db cluster-name) extra-tx)`
  with `:tx-meta`-free provenance per step 1; arity gains `extra-tx` (`[:sequential :seon.schema/value]`, default `[]`).
- CHANGE `seon.test/prepare-checkpoint!` (`test.clj:1773`): passes its source record
  `[{:seon.cluster/name cluster :seon.source/commit-id base}]` as `extra-tx` (and EN-7's overlay rows when EN-7 lands).

## Proof
`seon.test-test/a-checkpoint-is-prepared-in-one-transaction` (new): a check that builds a new checkpoint; the
checkpoint branch's history after its fork point holds exactly one transaction (two if step 1 kept the process row
apart), carrying the config row, the cluster entity and `:seon.source/commit-id`. Boot's own proof:
`seon.cluster.boot-test` (existing) stays green.

## Files / commit
`src/seon/config.clj`, `src/seon/cluster.clj`, `src/seon/test.clj`, `test/seon/test_test.clj`.
`bin/lane commit <lane> -m "TX-5: seeding a cluster's facts is one transaction (checkpoints and boot)" --test seon.test-test --test seon.cluster.boot-test --test seon.config-test src/seon/config.clj src/seon/cluster.clj src/seon/test.clj test/seon/test_test.clj`

## Cost
A first check on a new program: 4 checkpoint commits → 1 (or 2); a warm cluster boot loses 2-3 small commits. Each
~41 KB + 29 KB head on default.

## Dependencies
None (EN-7 then adds its overlay to the same transaction). Reset: none.
