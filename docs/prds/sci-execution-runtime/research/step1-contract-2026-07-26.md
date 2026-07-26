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

## Rulings applied (owner, 2026-07-26 late)

1. **Datahike's names, Datahike's args.** `my.db/q` `(q query & inputs)`
   and `my.db/transact` `(transact {:tx-data […]})` mirror `datahike.api`
   exactly; the one shortcut is db-input injection (most recent database
   value) when none is supplied. The same principle renames the message
   tool to the owner's name: `my.message/message!`.
2. **Messages are prose communication only.** Mechanical results
   (receipts, faults, code-generation outcomes) are facts with their own
   derivations, never messages.
3. **ACK is derived, never stored.** An inbound message stays in the
   recipient's derived context until a fact answers it (reply, plan
   citation, or dismissal fact); "unanswered for N turns" is a query the
   sender can escalate on. Wiring that derivation into the context owner
   is a named step-1 follow-up, not part of the request!/family lane.
4. **Replay is automatic.** Agents never mark anything; the run loop
   derives `:seon.capability/op-id` from (run, ordinal, epoch);
   `:seon.capability/replayed?` is observability, not an option.
   (The `:seon.capability/*` → `:seon.db/*` attribute renames ride the
   standing rename wave.)
5. **Effect classification refined (owner, same night).** The attribute
   is `:seon.code.fn/effect` — a program-graph fact colocated with the
   fn facts; the runtime mechanism stays `seon.effect`. Functions are
   untagged by default and carry ONLY their Malli contract; purity is
   never assumed and never declared — it is DERIVED by reachability
   (pure iff the call graph reaches no tagged leaf and no unknown
   target; unknown fails closed). Only world-touching LEAVES are
   tagged, and the tag is the family. The real axis is externality,
   not read-vs-write: database reads are not effects at all (a pointer
   into an immutable value — replay-free, no identity); database
   writes carry the op-id; external reads (web, llm) are effects
   despite being reads — cost, limits, and nondeterminism under
   replay, not mutation, are what they owe attribution for.
