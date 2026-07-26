---
type: research
status: active
tags: [research, agent, runtime, database]
---

# Effect identity contract, revision 2 — the pilot's sealed design

Applies the session-2 rulings (plan README): op-id = (run, form-ordinal,
effect-ordinal); the closed `::family` enum dies; receipt-before-dispatch
for ledgerless owners; default-deny redispatch. Authored WITH the
crash-scenario walk this time — revision 1 was sealed without one and
shipped two identity defects
([[../../../seon/issues/effect-operation-id-collides-within-one-form]],
[[../../../seon/issues/effect-operation-id-changes-on-run-recovery]]).

## Grounding (what the tree actually does at `f9a4ee24c`)

- The writer already owns replay: `seon.db.writer/committed-transaction`
  finds the transaction carrying `:seon.db.protocol/request-id`;
  `replayed-response` compares the retry's `logical-transaction-hash`
  with the stored one — equal → `recovered-response` (the original
  result, allocated ids recovered from tempid receipts), different →
  `request-conflict` (`writer.clj:1364-1379`).
- The hash covers `::transaction-data`, `::transaction-meta`,
  `:seon.db/expected-db`, and `::generated-candidates`
  (`protocol.cljc:2077-2089`).
- `seon.agent.message/message!` allocates its message id through
  `seon.db.id/allocate!` with a RANDOM candidate each call
  (`message.cljc:515-525`, `id.cljc:1029-1031`). A crash-retry with the
  same op-id therefore carries a different candidate → different hash →
  the writer correctly refuses — as a CONFLICT, not a recovery. The
  double-send is already prevented; the recovery result is wrong.
- The run loop derives one op-id per FORM from
  `receipt/receipt-id (run, ordinal, claim-epoch)`
  (`driver.clj:672-696`) — both identity defects live in that one line.

## The revised contract

### Identity

`seon.effect/op-id` — `(pr-str [run-id form-ordinal effect-ordinal])`.

- `run-id` + `form-ordinal` come from the executing receipt's coordinates
  (injected by the run loop; agent code never sees them).
- `effect-ordinal` counts effect requests within one execution of the
  form, starting at 0 — an invocation-local counter in the request
  context (an atom; process-local coordination, the sanctioned use).
- **Claim epoch appears nowhere in the identity.** It remains the run
  fence: a displaced process's *transactions* fail the run fence; its
  effect identity was never epoch-dependent, so the surviving process's
  re-execution derives the SAME op-ids and replays.
- Re-execution determinism: the form re-runs from the top; effects
  0..k-1 replay from their owners (same op-id → recorded result), so the
  counter re-derives the same values up to the first genuinely new
  effect. Nondeterministic agent code that produces different effect
  content under an already-used op-id hits the writer's hash conflict —
  a loud error value, the honest at-least-once ceiling (L10).

### The envelope — owner symbol, not enum

`request!` takes one map:

- `::owner` — the qualified symbol of the one owning function
  (`'seon.agent.message/message!`, `'seon.db/transact!`). Identity for
  receipts and provenance; NEVER a dispatch key.
- `::args` — the owner's own request map (validated
  `ordinary-request-value?` — realized finite data).
- `::invoke` — a thunk of one argument (the derived op-id) closing over
  the owner call. The `my.*` surface adapts its owner's shape here;
  `request!` performs admission, identity, the receipt bracket, and
  result validation. No `case`, no family registry, no context fn-map.
- `::receipted?` — true when the owner is LEDGERLESS (fs, shell, web,
  llm): `request!` commits the open effect receipt BEFORE invoking and
  terminalizes it after. Ledgered owners (db, message) omit it — the
  writer's request receipt IS their ledger. This is the family core
  stating its own replay contract, not a taxonomy.

The `::family` enum, the `:multi` join, and the per-family arms in
`seon.effect` are deleted. `my.*` code carries no effect vocabulary; an
agent sees ordinary functions with schemas.

### Allocation replay (the message defect's root fix)

`seon.db.id/allocate!`, when its request carries
`:seon.capability/op-id`:

1. **Pre-check**: query the committed transaction carrying that
   request-id (the writer's own `committed-transaction` rule, exposed
   through the protocol). Found → return the recorded result with
   `:seon.capability/replayed? true`; generate nothing.
2. Absent → allocate candidates and transact as today.
3. On a request-identity conflict whose stored request-id equals ours
   (the concurrent-retry race): re-run the pre-check and return the
   winner's recorded result.

One mechanism at the one allocation owner; fixes every allocating
effect, not just messages. The writer's hash conflict remains the guard
against one identity reused for genuinely different work.

## Crash-scenario walk

Kill points for one form whose execution performs
`message! → transact → fs/write!`. Recovery = the run loop re-executes
the form from ordinal's first missing terminal receipt; every effect
call re-derives its op-id (0, 1, 2).

| # | killed at | world state | recovery behavior | proof obligation |
|---|---|---|---|---|
| 1 | before any effect | nothing happened | full re-execution; effects fire first time | trivially covered by resume tests |
| 2 | after `message!` commit, before `transact` | message committed with request-id `[run f 0]` | effect 0 pre-check finds the committed tx → recorded result, `replayed? true`; effect 1 fires fresh | ONE message entity; both bases equal across executions |
| 3 | mid-`transact` (writer died after commit, caller never saw the report) | write committed with request-id `[run f 1]` | effect 0 replays; effect 1's writer replay returns recovered response | ONE write; recovered basis = original |
| 4 | after fs receipt commits, before the fs bytes are written | receipt `[run f 2]` open, file untouched — INDISTINGUISHABLE from fired | effects 0-1 replay; effect 2 finds its open receipt: untagged `write!` → may-have-happened steering error; `read` (redispatch-on-crash) → refires | steering error carries the frozen request; no second write attempt; read refires exactly once more |
| 5 | after fs bytes, before the terminal receipt | file written, receipt open | same as 4 — that is WHY the default is deny: 4 and 5 are one observable state | same as 4 |
| 6 | after fs terminal receipt, before the form's terminal receipt | everything happened, form not settled | effects 0-1 replay; effect 2's TERMINAL receipt found → recorded result returned, no refire | terminal receipt is the ledgerless owner's replay source |
| 7 | after the form's terminal receipt | settled | resume skips the form entirely (`next-ordinal`) | existing resume coverage |
| 8 | two processes race one run (stale holder finishes an effect after takeover) | new epoch re-executed effects; stale holder's effect shares the SAME op-id | stale ledgered effect = replay or hash-conflict, both harmless; stale receipt/terminal transactions fail the run fence | the fence rejects the stale TRANSACTION while identity stays shared — epoch's only job |

Row 4/5 is the ruled design speaking: for a ledgerless effect the
pre/post-dispatch distinction is unobservable, so safety cannot depend
on it — deny is the default and `redispatch-on-crash` is the one opt-in.

## Properties (the activation targets)

1. **message replay** — generated (content, recipients); execute the
   same sending context twice; ONE committed message, equal concise
   results, second marked replayed.
2. **transact replay** — generated tx-data; same op-id twice; one
   write, equal bases, `replayed? true`.
3. **intra-form distinctness** — one form performing N generated
   effects derives N distinct op-ids; all commit exactly once.
4. **recovery identity stability** — claim at epoch e, effect, kill,
   reclaim at e+1, re-execute: op-id unchanged, `replayed? true`, one
   world effect, both epochs recorded.
5. **fs deny/allow** — open receipt + untagged write! → steering error,
   file untouched by recovery; open receipt + read → refires.

## Weirdness acceptance (owner: "minimum weirdness")

The agent-visible reply for the full pilot scenario must read as
standard Clojure: `(my.message/message! {...})`,
`(my.db/transact {:tx-data [...]})`, `(my.fs/write! {...})` — no
envelope, no identity argument, no effect vocabulary, no annotation.
Count of agent-visible non-standard constructs beyond `:malli/schema`:
target 0, reported honestly.
