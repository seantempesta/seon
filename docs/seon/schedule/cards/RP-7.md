---
title: "Card RP-7: The recording never pushes back; a refused recording keeps its values and rows"
type: reference
status: active
created: 2026-10-08
---

# RP-7 The recording never pushes back; a refused recording keeps its values and rows

Ledger row RP-7 (MS-opus S1, group B). Design: `redo/pass-and-recording.md` §2 ("What the recording may and may not
refuse"), owner question 1 (recommended and adopted by the "agent branches do anything" ruling: the agent's final state
is stored with `[:overwrote …]`); agent-loop.md §3.6 (the failure table). Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
`seon.turn.batch/program-facts` (`src/seon/turn/batch.clj:599`) first asks `changed-since-basis` (`:563-597`): a
definition another writer changed on the branch after the eval request's basis refuses the whole program
(transaction function throw via `program-facts-call` `:615`). `settle` (`:729-774`) then `rebase`s (`:722`) →
`revalidate`s (`:696-720`) each form on the head and drops the refused forms' facts as `:seon.batch/facts-error`.
`seon.turn/not-admitted` (`src/seon/turn.clj:3317`) stamps them. `seon.turn/settle-batch!` (`turn.clj:3542`) does the
same for the SCI/model path until EN-8b deletes it.

## The change
1. **Another writer's change is overwritten with a finding.** `settle` reads `changed` on its pre-commit head with the
   body of `changed-since-basis` (definitions and schema keys whose stored digest/form on head differs from the basis)
   and commits the agent's final state anyway; each changed identity becomes
   `{:seon.program.finding/type :seon.program.finding/overwrote :seon.program/identity lookup :seon.program/definition-digest <head's digest>}`
   (schema keys: `:seon.schema/form` text instead of the digest) on the commit's `:seon.batch/findings`, which
   RP-6a2's FINISH attributes to the defining form. The head can move again between this read and the commit; the
   finding is then missed, never wrong data (the writer still serializes).
2. **The failure table** (agent-loop.md §3.6), in `settle`:
   - a commit answer with `:db-after` → done;
   - a **definite** refusal (`:seon.db/transaction-refused` without `:seon.db/transaction-outcome-unknown`) → one second
     attempt with `record-facts` only (no program facts), the first refusal as `:seon.batch/writer-refusal`;
   - that second attempt refused → a third with the call row alone: `record-facts` over zero entries (see
     `record-evaluated-call` below); the refusals reach the fault stage as today's diagnostics do (`evaluate-batch`
     `src/seon/batch.clj:745-760`), which records them;
   - an **unknown** outcome → no further attempt: answer `{:seon.batch/outcome-unknown <the error>}`; the pending
     transaction and its blob permit stay with their owner (S5-01's pending completion);
   - a throw → `:seon.batch/storage-error` as today.

## Files
- `src/seon/turn/batch.clj`
- `src/seon/turn.clj` (`record-evaluated-call`'s empty guard; `not-admitted` deleted with its callers)
- `resources/seon/schemas/seon.program.finding.edn` (`:overwrote`), `resources/seon/schemas/seon.batch.edn` (commit)
- `test/seon/repl_differential_p8_test.clj`, `test/seon/turn_test.clj`

## Functions
- RENAME/CHANGE `seon.turn.batch/changed-since-basis` → `seon.turn.batch/overwritten`
  `{:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:vector :seon.program/row] [:set :qualified-keyword]] [:vector :seon.program.finding/any]]}`
  `[request head rows schema-keys]`: the same comparison, answering findings (empty when `identical?` basis/head or
  nothing changed).
- CHANGE `seon.turn.batch/program-facts` (`:599`): contract
  `[:=> [:cat :seon.batch/request :seon.db/database-value :map] :seon.store/transaction-data]`; it no longer consults
  the basis.
- CHANGE `seon.turn.batch/program-facts-call` (`:615`): no refusal branch remains; it answers `program-facts`.
- CHANGE `seon.turn.batch/settle` (`:729`), contract unchanged except the commit schema's new members
  (`:seon.batch/outcome-unknown`, `:seon.batch/findings`): compute `overwritten` on `head` once; attempts per the table;
  delete `rebase`, `revalidate`, `measuring` stays until S5-02.
- CHANGE `seon.turn/record-evaluated-call` (`turn.clj:1317`): the guard `(seq sources)` (`:1357`) admits zero
  sources when zero evaluations are given (a call row alone); everything else unchanged.
- DELETE `seon.turn.batch/revalidate`, `seon.turn.batch/rebase`, `seon.turn.batch/apply-facts` (its last caller was
  `revalidate`), `seon.turn/not-admitted` and its two callers' arms (`turn.clj:3650` inside `settle-batch!` and
  `:5034` inside `evaluate-sources`; if EN-8b has not yet deleted `settle-batch!`, its refused branch keeps only the
  `settle-batch-refusal!` path), and the test helper use at `test/seon/turn_test.clj:2267`: the deftest
  `a-definition-changed-since-the-run-read-it-is-refused-at-the-writer` (`:2240`) is replaced by the new proof below
  (it asserts the behaviour this row reverses).

## Proof
- `seon.repl-differential-p8-test/another-writers-change-is-overwritten-with-a-finding`: the agent defines
  `rp7.a/f` in eval request 1; root (JVM mode) writes another definition of `rp7.a/f` on the agent's branch connection
  (`(seon.db/transact! <agent branch connection> [(support/program-fn-entity …)])`, as the replaced turn-test does at
  `:2258-2262`); the agent's redefinition must run on a basis taken before root's write, which one MCP call cannot arrange, so the
  proof uses the turn-test fixture (`support/code-request` + `seon.batch/evaluate-batch` with `:seon.batch/settle` kept,
  the request's `:seon.batch/basis` captured before root's `support/transacted!`, as the replaced test does), and assert: the stored `f` has the agent's digest, and
  `(:seon.batch/findings (:seon.batch/commit batch))` holds one `:overwrote` naming `[:seon.fn/sym rp7.a/f]`.
- `…/a-refused-recording-keeps-values-and-rows`: a world whose `:seon.db/writer` refuses only transactions carrying a
  program fact (`(fn [conn tx] (if (some #(and (vector? %) (= :db.fn/call (first %)) (= #'turn.batch/program-facts-call (second %))) (:tx-data tx)) {:seon.error/message "refused" :seon.db/transaction-refused true …} (db/transact! conn tx)))`,
  an injected external effect as a value); the eval request's values are printed, its evaluation rows are stored,
  `(:seon.batch/writer-refusal commit)` is present.
- `…/an-unknown-recording-outcome-is-not-retried`: a writer answering
  `{:seon.db/transaction-outcome-unknown true :seon.db/transaction-refused true :seon.error/message "unknown"}` once,
  counting its calls in a local `volatile!`: exactly 1 call; the commit answers `:seon.batch/outcome-unknown`.

## Reaching tests
`seon.repl-differential-p8-test`, `seon.turn-test`, `seon.turn.batch-test`, `seon.mcp-entrance-test`,
`seon.cluster.host-test`, `seon.cluster.evaluate-source-test`.

## Cost
Expected: one pull per defined identity on head before the commit (the comparison `changed-since-basis` already did);
no re-derivation on refusal (today's `revalidate` re-derives every form: deleted).

## Deleted
`changed-since-basis` (renamed and repurposed), `revalidate`, `rebase`, `apply-facts`, `not-admitted`, the
push-back test.

## Commit
`bin/lane commit <lane> -m "RP-7: the recording overwrites with a finding and follows the failure table; revalidate and rebase deleted" --test seon.repl-differential-p8-test --test seon.turn-test --test seon.turn.batch-test --test seon.mcp-entrance-test --test seon.cluster.host-test src/seon/turn/batch.clj src/seon/turn.clj resources/seon/schemas/seon.program.finding.edn resources/seon/schemas/seon.batch.edn test/seon/repl_differential_p8_test.clj test/seon/turn_test.clj`

## Dependencies
RP-6a, RP-6b, RP-11c (ledger; `turn.clj` serialization). S5-01 for the unknown-outcome pending completion (if S5-01
has not landed, the unknown arm answers the error and holds nothing extra). Group B.

## Reset needs
None.
