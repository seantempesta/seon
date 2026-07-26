---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# Step 1 contract layer — Fable-authored, awaiting surface review

The spec-first package for step 1 (messaging + db first), per the ruled
division: Fable authors schemas/contracts/tests; a sol lane implements
until the pending properties activate and go green — without editing the
schemas or the tests.

## Owner surface-taste questions — rule these before implementation

1. **Tool names.** `my.message/send!` and `my.db/q` / `my.db/transact!` —
   bang on effects, bare `q` for the read. Alternative: `my.db/query`.
2. **Send result keys.** `send!` returns the message owner's concise
   result — `{:seon.agent.message/id … :seon.agent.message/hops …}`.
   Enough, or should the agent also see the basis `:seon.db/t`?
3. **No inbox tool.** Reading messages is context derivation (delivery is
   a derived view), so there is deliberately no `my.message/read`. Agree?
4. **`transact!` result.** `{:seon.db/t … :seon.capability/op-id …}` plus
   optional `:seon.capability/replayed?` on replay. Enough?

## The contract inventory

- `seon.effect` — `::family` (closed enum: db blob fs shell web message
  llm), `::request`/`::result` envelopes, `ordinary-request-value?` (the
  admission predicate: realized finite data; lazy seqs refused by
  construction), `request!` with `:=>` contract and an honest
  `:seon.effect/not-implemented` error-value stub. Request identity is
  `:seon.capability/op-id` — the db owner's own replay identity — and
  provenance is injected by the run loop from the executing receipt,
  never supplied by agent code.
- `my.message` — `::send-request` (content + recipients), `::sent`
  (concise result), `send!` with contract + stub. Message identity
  derives from the sending receipt (run, ordinal, epoch); the pending
  test carries the double-send property.
- `my.db` — `::query-request` (validated by datalog-parser's own
  grammar — the dependency is the validator), `::transact-request`
  (`::tx-datum` = entity map or operation vector), `q`/`transact!` with
  contracts + stubs. Replay identity property pending.
- `test/my/effect_contract_test.clj` — green now (4 tests / 10
  assertions): envelopes admit the ruled shapes, unknown family refused,
  lazy values refused, stubs are honest error values. Two
  `^:seon.contract/pending` tests assert today's stub truth and carry
  the activation properties for the implementation lane.

## Scars collected while authoring (already paid for)

- `rg -rn` fabricates evidence: `-r n` replaces every match with the
  literal string `n`. The fabricated `:seon.capability/n` survived into
  three files until schema admission refused to resolve it. Grep flags
  are part of the evidence chain.
- A named predicate needs BOTH the `[:fn {…} 'sym]` wrapping AND
  `schema/register-core-predicate!` — the sci-eval registration fix
  established the idiom; this package reused it.
- Referencing another owner's schema keyword requires loading that owner
  into the population (`seon.effect` requires `seon.db` for
  `:seon.capability/op-id`).

## Implementation lane acceptance (verbatim for the lane spec)

Activate the two pending tests (real properties in their comment
blocks), implement `request!` + the two family arms over the existing
owners (`seon.agent.message/message!`, `seon.db/transact!`/query), keep
every schema and test byte-identical except the sanctioned activation,
and go green under `bin/test-writer`. Friction with a contract is
reported, never resolved by loosening it.
