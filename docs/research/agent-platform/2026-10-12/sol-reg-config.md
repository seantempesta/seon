---
title: "C3 producer diagnosis — sol-reg-config"
type: research
status: evidence
created: 2026-10-12
---

# C3 producer diagnosis — sol-reg-config, 2026-10-12

Configuration already compares exactly. The nonempty second plan contains cost entities created by
`seon.db/cost-upserts`, appended by `seon.db/stamp-transaction` to the configuration transaction.
`seon.reconcile/first-assertion-transactions` and `seon.reconcile/process-by-transaction` consequently
attribute those identities to the configuration's managing process. The next plan retracts them as
absent from the desired population; the same write's accounting produces new cost entities again.
There is no differing config dial in the observed second plan. A config-side retention list, identity
filter, disabled accounting, or second cache would hide the producer defect.

## Reproduction and authority

HEAD: `5d7567be1`; default runs archive `e0bd37443`. Datahike pin and checkout:
`61bca1edd6bcef079f5c47e4f5f75df30549934d`. `datahike.db.transaction/flush-tx-meta`
stores the process metadata on the transaction; identity datoms emitted by its transaction functions
have that same transaction. The producer appends accounting after the requested data and before
the stamp; `seon.reconcile/plan-transaction-data` manages identities by their first assertion's process.

The task's existing tagged proof, `seon.reconcile-test/first-apply-writes-and-reapply-is-zero-writes`,
was refined to inspect the second plan before applying it, resolving planned retractions to their
cost identity. The input is unchanged: one compiled config entity, applied twice on the test member's
disposable branch through the actual writer, with its real call sink and instrumentation.

Check run `25a9d3df3281`: first application succeeds; the second plan consists of six retractions of
cost entities, with these keys (scope is `:seon.profile/host` for each):

- `seon.db/vector-query-call`
- `seon.schema.internal/entity-maps`
- `seon.schema.datahike/declared-storable?`
- `seon.schema.internal/entity-entries`
- `seon.schema/identity-attr?`
- `seon.schema.internal/entity-properties`

The basis moves from `536870933` to `536870934` on the second apply. The count varies with the observed
accounting samples; all observed operations are cost-entity retractions, not config-attribute changes.
This check's one test took 2,492 ms; pass 2, fail 3, error 0.

## Named checks

Run `1a73c5477f69`, candidate `0170e3768a2ec02cd2c0ea4ab64c1fca389ceba6`: 8 executed, 0 reused,
34 assertion passes, 22 failures, 0 errors, 7,253 ms total. All seven C3 Vars remain red:

- `seon.config-test/apply-compiles-once-and-round-trips-through-database-facts` (6 operations)
- `seon.config-test/config-writer-submits-only-the-fact-difference` (extra cost retractions, two writes)
- `seon.config-test/converged-apply-uses-carried-projection-and-remains-exact` (5 operations)
- `seon.config-test/initialization-entities-apply-query-and-converge-with-the-config-entity` (5 operations)
- `seon.index-reconcile-test/indexing-an-existing-population-uses-one-writer` (10 and 3 operations)
- `seon.reconcile-test/first-apply-writes-and-reapply-is-zero-writes` (5 cost retractions)
- `seon.reconcile-test/planning-pulls-only-the-provenance-managed-slice` (9 extra managed entities)

`seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm` passes in that run.
Its individual time was not exposed in the check summary. No C9 code change is justified by this evidence.

## Live probe and limits

An uninstrumented JVM probe on scratch branch `:agent-399fd6f53ccf`, explicit connection and custody,
returned a first apply with one operation and an empty next plan (166 ms, 207.2 MB allocation).
The instrumented disposable check is the positive reproduction; the host probe alone is insufficient.
The SCI attempt on that scratch branch returned a writer error referencing its not-yet-recorded
evaluation `[:seon.cluster.eval/id "01e5de188e82"]` (205 ms). Signature:
`cc2cfbbe865195b52a10d7541e99e72b42235e634f76b2242b072b7a0cbeab31`.
That separate recording problem did not prevent the disposable check reproduction.

No source fix, green after-measurement, or commit is claimed. `src/seon/db.clj` is the foreign ownership
boundary: `astra-cost-handoff` holds it. The config source and config tests are unchanged; the refined
red proof is eight added test lines. Root must arrange the producer handoff or give this evidence to
its owner. The repair must preserve accounting and transaction durability while making accounting's
ownership accurate, rather than exempting its identities in the reconciler.

Full working-tree lint (`clj-kondo --lint src script test --cache false`) completed in 6,963 ms:
0 errors, 1,541 warnings. The working tree includes other lanes' changes; this is not a clean
candidate lint or proof of their changes.
