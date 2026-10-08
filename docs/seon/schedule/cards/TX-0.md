---
title: "Card TX-0: Every transaction names its eval request and basis"
type: reference
status: active
created: 2026-10-08
---

# TX-0 Every transaction names its eval request and basis

Ledger row TX-0 (group E). Design: `redo/pass-and-recording.md` §2.1 (the census fold-in); evidence
`tx-census/tx-census-opus.md` §4. Lane: Sonnet. Cited at HEAD `22910a03a`. First of the transaction rows: RP-14,
RP-15a, EN-1, EN-6, TX-3, TX-4, TX-6 and TX-8 read what it writes.

## What changes, in one paragraph

`seon.db/stamp-transaction` (`src/seon/db.clj:3626`), the one owner of transaction metadata, already stamps
`:seon.db.transaction/operation` and `agent` from the request map or the eval request's world
(`seon.batch/*world*`) and installs a missing stamp attribute inline (`:3634-3646`). It now also stamps
`:seon.db.transaction/eval-request` (the eval request id: the world's `:seon.turn/id`, the id of the call row the
recording writes) and `:seon.db.transaction/basis-t` (the t of the value the eval request read first: the world's new
`:seon.db/basis-t` member). Both are unindexed values. Probed on default: an agent's `(seon.db/transact! [])` inside
an SCI eval request commits a transaction carrying `:seon.db.transaction/agent` and `:seon.db/receipt` (so the world is
bound at the stamp), and the JVM-mode world holds `:seon.turn/id` (`"f3ded869bed9"`, keys printed by
`(sort (keys seon.batch/*world*))`). The recording owners pass the values explicitly in TX-4.

## Files

- `resources/seon/schemas/seon.db.transaction.edn`
- `resources/seon/schemas/seon.batch.edn` (`:world` gains one optional member)
- `src/seon/db.clj` (`stamp-transaction`)
- `src/seon/turn.clj` (`evaluate-sources` world, `:4978`)
- `src/seon/turn/world.clj` (`agent-world`, `jvm-world` accept the member)
- `src/seon/mcp.clj` (`jvm-run`, `:265`)
- `test/seon/db_test.clj`

## Schema

`seon.db.transaction.edn` gains:

```edn
:eval-request [:string {:min 1 :description "The eval request whose evaluation made this transaction: the :seon.turn/id of the call row its recording writes. A value, never a ref: transaction metadata is written on branches that hold no call row."}]
:basis-t [:and {:description "The t of the database value the eval request read first: a model eval request's presented context basis, an MCP eval request's branch head when it started."} :seon.db/basis-t]
```

No `:seon.db/index`. `seon.batch.edn` `:world` (`:2-25`) gains
`[:seon.db/basis-t {:optional true :description "The t of the value this eval request read first (seon.db.transaction/basis-t)."} :seon.db/basis-t]`.
`:seon.db/basis-t` is NOT added to `seon.batch/world-members` (`src/seon/batch.clj:46`): it is stamped, never
supplied to a function's omitted input.

## Functions

- CHANGE `seon.db/stamp-transaction` (private, `:3626`), contract unchanged
  (`[:=> [:cat :seon.db/database-value :seon.store/transaction [:maybe :map]] :seon.store/transaction]`):
  - `attributes` (`:3636`) gains `:seon.db.transaction/eval-request` and `:seon.db.transaction/basis-t`, so an old
    store installs them inline in the first transaction that writes them (`:3637-3645`).
  - `eval-request (or (:seon.db.transaction/eval-request request) (:seon.turn/id world))`,
    `basis-t (or (:seon.db.transaction/basis-t request) (:seon.db/basis-t world))`.
  - `stamp` gains `eval-request (assoc :seon.db.transaction/eval-request eval-request)` and
    `basis-t (assoc :seon.db.transaction/basis-t basis-t)`.
  - Docstring: one sentence, "Inside an eval request the transaction also names the eval request and its basis."
- CHANGE `seon.turn/evaluate-sources` (`:4918`): the `world` map (`:4978-4986`) gains
  `:seon.db/basis-t (db/basis-t basis)`, the `basis` already bound at `:4940` (the value this turn read; for a model
  eval request, the snapshot its context presented).
- CHANGE `seon.turn.world/agent-world` and `seon.turn.world/jvm-world` (`src/seon/turn/world.clj:40-95`): destructure
  an optional `:seon.db/basis-t` from `opts` and `(assoc :seon.db/basis-t …)` when present. Contracts unchanged (they
  answer `:seon.batch/world`, which now allows the member).
- CHANGE `seon.mcp/jvm-run` (private, `:265`): pass `:seon.db/basis-t (db/basis-t (db/db connection))`, read once
  before `evaluate-batch`, in the `jvm-world` opts map (`:276-281`).

## Data (the stamp of an agent's write inside an SCI eval request)

```edn
{:db/id 536872142
 :db/txInstant #inst "2026-10-08T06:48:48.636Z"
 :seon.db.transaction/operation seon.db/transact!
 :seon.db.transaction/agent "cards-recording"
 :seon.db.transaction/eval-request "0f0128e5aa11"
 :seon.db.transaction/basis-t 536872138
 :seon.db/receipt [:seon.cluster.eval/id "0f0128e502df"]}   ; until RP-14 deletes it
```

## Proof

`seon.db-test/an-eval-request-commit-names-its-eval-request-on-the-transaction` (new), the
`seon.repl-differential-p3-test` idiom (a private `reply` over `seon.mcp/call`, `support/with-running-agent`):

```clojure
(let [a (reply "(seon.db/transact! [])" agent-id running)
      handle (str (:seon.repl/result a))                     ; "result/e0f0128e502df"
      eval-id (subs (name (symbol handle)) 1)                 ; the evaluation id
      stamp (:seon.repl/value
             (reply (str "(seon.db/pull (seon.db/db) [:seon.db.transaction/eval-request :seon.db.transaction/basis-t :seon.db.transaction/agent] (:seon.db/tx " handle "))")
                    agent-id running))
      call-id (:seon.repl/value
               (reply (str "(get-in (seon.db/pull (seon.db/db) [{:seon.cluster.eval/run [:seon.turn/id]}] [:seon.cluster.eval/id \"" eval-id "\"]) [:seon.cluster.eval/run :seon.turn/id])")
                      agent-id running))]
  (is (= agent-id (:seon.db.transaction/agent stamp)))
  (is (= call-id (:seon.db.transaction/eval-request stamp)))
  (is (int? (:seon.db.transaction/basis-t stamp))))
```

The handle is `result/e` + the evaluation id (probed: handle `result/e0f0128e502df`, receipt
`[:seon.cluster.eval/id "0f0128e502df"]`). A second row, JVM mode (`"mode" "jvm"` in the arguments), runs
`(seon.db/transact! (:seon.db/connection seon.batch/*world*) [])` and asserts the same three keys on the report's tx.

### The receipt tests convert here (RED-1, commit `4e307ec54`)
Task `seon.cluster.host/a-hosted-forms-write-names-its-evaluation-receipt`
(`resources/seon/tasks/seon.cluster.host.edn:6`): a hosted form's write carries no `:seon.db/receipt` datom, and
`seon.receipt-write-carrier-test/evaluation-receipt-is-transaction-metadata` is red at HEAD
(`test/seon/receipt_write_carrier_test.clj:61`). `:seon.db/receipt` is deleted by RP-14; nobody repairs its stamp. This
row converts every test that asserts it to assert the transaction's `:seon.db.transaction/eval-request` instead (the
write's eval request id, the `:seon.turn/id` its world or request names):
- `test/seon/receipt_write_carrier_test.clj:68`, `:82` (`evaluation-receipt-is-transaction-metadata`: assert
  `[?tx :seon.db.transaction/eval-request "receipt-write-run"]` for the evaluated write; rename the deftest
  `an-evaluated-write-names-its-eval-request-on-the-transaction` and give it
  `^{:seon/tasks #{'seon.cluster.host/a-hosted-forms-write-names-its-evaluation-receipt}}`);
- `test/seon/cluster/host_test.clj:146` (the hosted run's write: the same assertion with the hosted run's id);
- `test/seon/settlement_receipt_provenance_test.clj:38`, `:73`, `test/seon/cluster/evaluate_source_test.clj:196`:
  the same conversion;
- `test/seon/db_test.clj:2274` reads `::db/receipt` from the stamp: convert to read
  `:seon.db.transaction/eval-request` with `seon.batch/*world*` bound to `{:seon.turn/id "x"}` in the test (a binding,
  not a Var redefinition).
Add these files to the commit line and `--test seon.receipt-write-carrier-test --test seon.settlement-receipt-provenance-test --test seon.cluster.host-test --test seon.cluster.evaluate-source-test`.

## Reaching tests (run in `--test`)

`seon.db-test`, `seon.turn-test`, `seon.mcp-entrance-test`, `seon.cluster.evaluate-source-test`
(`code-evaluates-as-one-ordinary-run-of-its-agent` is red on HEAD before this row, RED-3: classify, do not fix here).

## Cost

Expected from the work: two more unindexed datoms on the transaction entity of each write inside an eval request; the
census measured an unindexed tx attribute at about half the +15.5 KB / +5 objects of the indexed `operation` per commit
on default, so roughly +8-15 KB per agent-caused commit, recovered many times over by TX-8 (−10 attributes per eval
record). No new reads. Report the O probe (`seon.pass-probe/run-mcp` SCI and JVM) and the census window
(`docs/research/agent-platform/2026-10-08/tx-census/opus-evidence/tx-census-probe.clj`) bytes per eval request,
before and after.

## Deleted

Nothing (RP-14 deletes `:seon.db/receipt`; TX-8 deletes the per-row provenance this replaces).

## Commit

`bin/lane commit <lane> -m "TX-0: every transaction inside an eval request names the eval request and its basis" --test seon.db-test --test seon.turn-test --test seon.mcp-entrance-test resources/seon/schemas/seon.db.transaction.edn resources/seon/schemas/seon.batch.edn src/seon/db.clj src/seon/turn.clj src/seon/turn/world.clj src/seon/mcp.clj test/seon/db_test.clj test/seon/receipt_write_carrier_test.clj test/seon/cluster/host_test.clj test/seon/settlement_receipt_provenance_test.clj test/seon/cluster/evaluate_source_test.clj --test seon.receipt-write-carrier-test --test seon.settlement-receipt-provenance-test --test seon.cluster.host-test --test seon.cluster.evaluate-source-test`

## Dependencies

None in the ledger. File holders at writing: `src/seon/db.clj` led-cacheb. `bin/lane commit`'s check publishes and
adopts the two new attributes on a branch of the live store; that is the schema proof (CLAUDE.md "a fresh install is
adoption onto an empty store, the same path").

## Reset needs

None: new attributes adopt incrementally; old transactions simply lack them.
