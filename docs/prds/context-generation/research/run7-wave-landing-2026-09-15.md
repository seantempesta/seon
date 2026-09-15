---
type: research
status: active
tags: [agent, test, message, database]
---

# Run 7 wave — 2026-09-15

## Grounding and dependency ledger

The assignment is direct, without delegation. Default is never stopped,
reforked, or reseeded. Initial status: default PID 23729 alive, MCP health
answered; the live query returned no messages from Juniper and no
`:my.plan.item/done-query` declaration. The installed `my.message/send`
contract was `[:=> [:cat :my.message/message] [:or :my.message/message :seon.error/value]]`.

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1152`:
  `:db.fn/call` receives the current transaction database and splices its
  returned transaction data. Existing examples: `seon.note/add-note-call`,
  `seon.plan/complete-step-call`, `seon.cluster.message/delivery`.
- SCI `reference-code/sci/src/sci/core.cljc:309`: call preparation receives
  the executing context and evaluated arguments; declared defaults supply
  the connection and agent identity. Existing example: `my.note/add!`.
- Static analysis and the reply reader already own resolved call facts and
  parsed forms; the settlement owner is `seon.turn/evaluation-terminal-data`.
- Archaeology: `c98d61b01` introduced message identities and documented the
  returned-value limitation. This wave removes that limitation from the
  agent-facing writes while retaining the delivery transaction owner.

Initial unrelated residue preserved: `resources/public/css/output.css`,
`build/`, `workers/`, `config/virtual-turns.edn`, and
`docs/prds/context-generation/research/debug-turns-landing-2026-09-14.md`.

## Item 1 — immediate message writes

`my.message/send` writes the message and inbox edge together and returns the
stored `:seon.message/id`; `decline` uses the same writer. `done` and `wait`
remain reply dispositions. The help and refusal both include the exact bytes
`(my.agent/done) must be the last form of your reply`.

Fast gate: 22 tests, 871 assertions, zero failures/errors. The isolated run
`run.KKSbUc` ran the required six namespaces plus `seon.turn-loop-test`:
49 tests, 998 assertions, 20 failures and one error, all in that extra
namespace. Its assertions include `inst?` on transaction refs (actual
`#:db{:id 536870928}`), now-retired missing-disposition behavior, and old
provider-overlay assumptions. This is a verification boundary, not a claim
that this lane caused or repaired those failures. A separate baseline check
and final required gate follow below.

Writer-level query completion fast proof: 1 test, 17 assertions, zero
failures/errors. It records completion in the same transaction as its witness,
rejects a false query with the query and `#{}`, preserves the first completion
transaction, and refuses an invalid query without completing the step.

## Item 2 — completion at settlement

All new schema entries are optional. `:my.plan.item/done-query` accepts the
database query shape; its inputs are `$` and the optional subject ref.
`seon.plan/settle-call` evaluates every uncompleted query-backed step in the
writer's database after the settlement facts. Single evaluation, batch,
system-turn append, and close settlement use that same function. Query work
uses the existing evaluation deadline through Datahike's cancellation input.
False/nil/empty results do not complete; invalid queries refuse with their
query and returned error. The current step's AI render shows `done-query:`.

The seven queries cover the orders read evidence, contracted function row,
passing test result, original note, added order, fresh result plus comparison
note, and report about the original request. Their limits are factual: note
and report queries check the recorded content, not an independent external
truth oracle. The report criterion checks the reply; session closure is
separately verified by `done` in the loop proof.

Live proof on a fresh `run7-proof` cluster (same scratch JVM, independent
Datahike branch): all seven completed without `complete!`; the only call to
`complete!` was the intentionally refused report assertion. Completion
transactions: read 536870992; define 536871001; test 536871012;
save 536871018; add 536871024; again 536871030; report 536871037.
Report id `0c23d38b8ebb`, about `f8cf1e15`, content exactly
`Ada: original total 115; verified new total 155.`.

Fast gate: 47 tests / 1,098 assertions / zero failures or errors. Platform:
84 tests / 505 assertions / zero failures or errors. The first isolated
gate hit its 270-second worker-exchange bound in the test containing nine
independent continuation scenarios; confirmation passed in 263,109 ms.
Those scenarios now have separate declared tests, preserving every assertion
and the same canonical fixture without changing the runner's bound.

The unrelated turn-loop failures were reproduced in a pristine pre-change
worktree and recorded in
[the issue](../../../seon/issues/turn-loop-regressions-still-expect-retired-state-and-time-shapes.md).
Final isolated gate and cleanup are recorded below.
