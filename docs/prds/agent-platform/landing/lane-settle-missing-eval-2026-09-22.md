---
type: research
status: complete
created: 2026-09-22
---

# Settlement missing-evaluation repair — landing evidence

**Fixed at the database transaction-provenance owner in `e02604e44`. RESTART
NEEDED.** This lane did not start, inspect, or mutate `default`; it remained down.

## Incident evidence and cause

`data/clusters/default/logs/seon.log:23308-23330` contains eight distinct
`:entity-id/missing` evaluation ids and eight paired writer failures. Every writer
failure names branch `:cluster-default` and commit
`6ab25303-db97-5cfe-bbf4-cd7df73459d1`; no failed write advanced the head. The ids
are `099ee480d55d`, `91a6b3ef4131`, `63b3dd6a7f36`, `3e6c9757d69f`,
`bc0820ec357a`, `4c9fe8b4bcc9`, `d619fc19cbcc`, and `9ef98d01e193`.

The raw log corrects the incident summary's time boundary: the first pair is at
10:12:22Z, not 10:19Z. The remaining pairs are at 10:13:25Z, 10:14:42Z,
10:16:12Z, 10:17:53Z, 10:19:52Z, 10:22:07Z, and 10:24:49Z. The 10:25:52Z
writer failure is a different `:malli.core/invalid-schema` defect and is not
counted in this class.

The failing stack reaches `reference-code/datahike/src/datahike/db/transaction.cljc:794`.
That line resolves the **value** of a ref attribute with `entid-strict`; it is not
the entity-position resolution at line 792. `db/utils.cljc:109-148` resolves a
lookup ref through AVET and throws `:entity-id/missing` when absent.

`seon.db/stamp-receipt` supplied `:seon.db/receipt` as Datahike `:tx-meta`.
Datahike turns metadata into add operations at `transaction.cljc:903-922`, then
places those operations before ordinary transaction data at
`transaction.cljc:1249-1254`. Therefore a write that both created an evaluation
receipt and carried its receipt lookup ref tried to resolve the provenance ref
before the receipt row existed. The immutable transaction refused atomically.

Commit `e23b8105a344a32d43ec6bb1ef26abd166a09b75` introduced this ordering on
2026-08-17 in `seon.db/stamp-receipt` by adding the lookup ref to `:tx-meta`.
The defect was latent until a receipt-creating transaction ran under receipt
custody. This is the owner and introducing commit; no retry, guard, or turn-site
tempid conversion is involved.

## Hypotheses

- Same-transaction lookup resolution: **verified, with a narrower subject than
  proposed.** The missing ref is the transaction entity's
  `:seon.db/receipt` value. The receipt is created by the same ordinary
  transaction data, while Datahike processed transaction metadata first.
- Kind-cut settlement identity loss: **falsified.** The requested diff changes
  error discriminators/contracts, not `receipt-row` identity production. Current
  virtual turns found their receipt before later settlement, and the class
  regression fails below without any kind-cut settlement projection change.
- Mid-turn Var adoption: **falsified for this repeated class.** No adoption is
  required to reproduce it. A deterministic same-write transaction fails on the
  old owner implementation and succeeds on the repaired implementation. This does
  not claim that arbitrary historical adoption is impossible; it establishes
  that adoption is unnecessary and does not explain the observed line-794 shape.

## Repair

`src/seon/db.clj:3370-3378` still stamps the transaction entity with the receipt
lookup ref, but appends
`[:db/add "datomic.tx" :seon.db/receipt receipt-lookup-ref]` to `:tx-data`.
Datahike now executes receipt creation first and provenance assertion afterward.
Transactions whose receipt is still absent continue to refuse honestly. Map
transactions retain their other members; sequential transactions remain wrapped
as transaction data.

Changed implementation slice in `e02604e44`: `src/seon/db.clj` (4 insertions,
4 deletions) and `test/seon/settlement_receipt_provenance_test.clj` (43 lines).

## Reproduction and proof

The canonical fixture regression is
`seon.settlement-receipt-provenance-test/receipt-created-by-the-write-is-resolved-before-provenance`.
It opens a real turn, proves the deterministic receipt identity is absent, binds
the same receipt custody used by `seon.db`, submits `turn/receipt-start-tx`, and
then requires both the receipt row and the transaction's receipt provenance ref.

Fails-before command, HEAD source plus only the new test path:

`bin/test-fast --paths test/seon/settlement_receipt_provenance_test.clj -- seon.settlement-receipt-provenance-test`

Run `825dd0e49dda` refused lookup ref `879cf3e9339d` at Datahike line 794 while
attempting exact transaction data
`[[:db.fn/call #'seon.turn/receipt-start-call {:seon.turn/id
"same-write-receipt-run", :seon.cluster.eval/ordinal 0,
:seon.cluster.eval/at #inst "2026-09-22T10:19:00.000-00:00"}]]` at basis
536870924. Result: 1 test, 2 failures, 0 errors.

Pass-after command:

`bin/test-fast --paths src/seon/db.clj test/seon/settlement_receipt_provenance_test.clj -- seon.settlement-receipt-provenance-test`

Run `601a4a37ef39`: **1 test, 3 assertions, 0 failures, 0 errors**.

The only live-system work used scratch root
`tmp/settle-missing-eval-root`, cluster `settle-missing-eval`, seeded with the
Juniper fixture. It booted pid 36497, start instant
2026-09-22T11:16:23.632Z, prepl port 54851, readiness 89.310 seconds, source
commit `6ab263e5-2b9a-5ddf-9e15-1273b331f254`. This owner-authorized cold start
exceeded ten seconds; no inner phase timing was emitted.

Before the owner proof, the scratch cluster completed three no-paid-call checks:
a canonical virtual `(+ 1 2)` turn; a direct provider-route turn with
`seon.ai/complete` locally returning `(+ 20 22)`; and a virtual effect turn adding
note `settle-effect-proof`. Their receipt ids existed before their later settlement
writes and settled successfully. These checks falsify a general current
settlement-projection loss; they do not cover same-write provenance ordering.

After hot-loading the repaired `src/seon/db.clj`, the exact same-write probe
recorded basis 536871471, receipt-before `nil`, receipt id `1bcdc97cc9ba`,
`:committed? true`, receipt-after db id 140626, and provenance result
`["1bcdc97cc9ba" 0]`. Its transaction data was the single canonical
`receipt-start-call`, so the receipt was created by the transaction that stamped
its provenance.

The requested combined command was run exactly:

`bin/test-fast --paths src/seon/db.clj test/seon/settlement_receipt_provenance_test.clj -- seon.cluster.turn-test seon.turn-test seon.settlement-receipt-provenance-test`

Its executable phase ran 94 tests / 606 assertions and reported 94 failures /
35 errors across the two existing turn namespaces. Failures included pre-existing
symbol/string fixture mismatches, undeclared error facets, SCI installation and
rendering differences, and duration-bound failures. Result recording then refused
with run id `3f2b6366e781`, so namespace progression aborted before the new third
namespace. This is a red shared-HEAD boundary, not a pass and not evidence against
the isolated green regression above.

HEAD load command
`clojure -M -e "(require 'seon.db :reload) (println :seon.db/loaded)"` exited 0
and printed `:seon.db/loaded`.

The required MCP `runtime_status` and `eval_clj` tools were unavailable in this
session. Scratch evidence therefore used the cluster's running prepl with explicit
root and cluster custody; unavailable MCP evidence is not reported as passing.

The two pre-existing modified documentation files, the operator lane's files, and
all unrelated untracked paths were preserved. The exact scratch JVM was downed,
holder absence checked, and its root deleted before final reporting.

**RESTART NEEDED.** The orchestrator owns restarting `default` and the cold gate.
This lane did not run a platform gate, publish to `default`, or claim browser paint.
