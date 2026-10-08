---
title: "Card TX-4: Every recording names its agent, operation, eval request and basis"
type: reference
status: active
created: 2026-10-08
---

# TX-4 Every recording names its agent, operation, eval request and basis

Ledger row TX-4 (group B). Census finding §0.4: 156 `seon.turn/record-evaluated-call` commits in 6 h on default carry
`:seon.db.transaction/operation` and no `:seon.db.transaction/agent`. Design: `redo/pass-and-recording.md` §2.1
("The recording passes all four values in its request map"). Lane: Sonnet. Cited at HEAD `22910a03a`.

## What was measured (default, agent `cards-recording`, 2026-10-08)

The last transactions on the agent's branch, `[tx operation stamp]`:

```edn
[536872141 seon.turn/record-evaluated-call nil]                                  ; the JVM eval request's recording: no agent
[536872142 seon.db/transact! {:seon.db.transaction/agent "cards-recording" :seon.db/receipt [...]}] ; agent write inside SCI
[536872143 seon.db/absent-call {:seon.db.transaction/agent "cards-recording"}]   ; an SCI recording, named after the run-open intent
```

Two defects with one cause: the recording runs **after** the fold's per-form `with-world` binding
(`src/seon/turn/world.clj:111-127`), so `stamp-transaction` sees no world; and the SCI recording's first
`:db.fn/call` is `db/absent-call` (the run-open intent `*open*`, prepended at `src/seon/db.clj:3662`), so the operation
is named after it, not the writer. It is not root-specific: every agent's JVM-mode recording lacks its agent.

## Files

- `src/seon/turn/batch.clj` (`settle`)
- `src/seon/turn.clj` (`settle-batch!`)
- `test/seon/db_test.clj`

## Functions

- CHANGE `seon.turn.batch/settle` (`:729`), contract unchanged: `commit!` (`:749`) hands the writer a request map
  instead of the vector:

  ```clojure
  {:tx-data tx
   :seon.db.transaction/operation 'seon.turn.batch/settle
   :seon.db.transaction/agent (:seon.agent/id world)              ; omitted when absent
   :seon.db.transaction/eval-request (:seon.turn/id world)        ; omitted when absent
   :seon.db.transaction/basis-t (:seon.db/basis-t world)}          ; omitted when absent (TX-0 sets it)
  ```

  built once with `cond->` so no key is nil (CLAUDE.md "absence is no key"). The writer contract (`:seon.db/writer`,
  `resources/seon/schemas/seon.db.edn:328`) takes `:seon.store/transaction`, which admits the map; `seon.db/transact!`
  already reads `operation` and `agent` from a request map (`src/seon/db.clj:3647-3604`, docstring `:5913-5917`).
  The empty-transaction branch (`(empty? tx)` → no commit) is unchanged.
- CHANGE `seon.turn/settle-batch!` (private, `:3542`; deleted later by EN-8b, which moves model and SCI recording onto
  `settle`): the `db/transact!` call at `:3626-3627` adds the same four keys to its request map:
  `operation 'seon.turn/settle-batch!`, `agent` and `eval-request` from the first request
  (`(:seon.agent/id (first requests))`, `(:seon.turn/id (first requests))`: one eval request's forms share both), and
  `basis-t (db/basis-t (:seon.batch/basis program-request))` when `program-request` holds a basis.

## Data (an SCI eval request's recording after this row)

```edn
{:db/txInstant #inst "…" :seon.db.transaction/operation seon.turn/settle-batch!
 :seon.db.transaction/agent "cards-recording" :seon.db.transaction/eval-request "a1b2c3d4e5f6"
 :seon.db.transaction/basis-t 536872138}
```

## Proof

`seon.db-test/every-recording-names-its-agent-operation-eval-request-and-basis` (new), table over `"sci"` and `"jvm"`
modes, through `seon.mcp/call` (`support/with-running-agent`, the p3 idiom):

```clojure
(doseq [mode ["sci" "jvm"]]
  (call! "(+ 1 2)" agent-id running mode)
  ;; read in SCI: the newest call row of this agent and its transaction's stamp
  (let [stamp (:seon.repl/value
               (reply (str "(let [db (seon.db/db)
                                  [id tx] (last (sort-by second (seon.db/q '[:find ?id ?tx :in $ ?a :where [?c :seon.turn/agent ?ae] [?ae :seon.agent/id ?a] [?c :seon.turn/id ?id ?tx]] db \"" agent-id "\")))]
                              (assoc (seon.db/pull db [:seon.db.transaction/operation :seon.db.transaction/agent :seon.db.transaction/eval-request :seon.db.transaction/basis-t] tx) ::id id))")
                      agent-id running))]
    (is (= agent-id (:seon.db.transaction/agent stamp)))
    (is (= (::id stamp) (:seon.db.transaction/eval-request stamp)))
    (is (not= 'seon.db/absent-call (:seon.db.transaction/operation stamp)))
    (is (int? (:seon.db.transaction/basis-t stamp)))))
```

The reading eval request is itself recorded after its read, so "newest call row" is the one before it. Add a third row
with agent `"root"` on the cluster branch if `with-running-agent` exposes root through MCP; the census's 156 defaults
were root's.

## Reaching tests (run in `--test`)

`seon.db-test`, `seon.turn-test`, `seon.mcp-entrance-test`, `seon.cluster.turn-test`.

## Cost

Expected from the work: none beyond TX-0's stamp; the map is built once per eval request. The recording stops being
named `seon.db/absent-call`, which corrects the census's operation counts.

## Deleted

Nothing.

## Commit

`bin/lane commit <lane> -m "TX-4: every recording names its agent, operation, eval request and basis" --test seon.db-test --test seon.turn-test --test seon.mcp-entrance-test --test seon.cluster.turn-test src/seon/turn/batch.clj src/seon/turn.clj test/seon/db_test.clj`

## Dependencies

TX-0 (the attributes and the world's `:seon.db/basis-t`). Group B files: serialize with RP and EN rows on
`turn/batch.clj` and `turn.clj`.

## Reset needs

None.
