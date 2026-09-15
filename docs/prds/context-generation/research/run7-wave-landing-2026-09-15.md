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

Read end to end: `AGENTS.md`, both assigned issue notes,
`explain_probe_run7_2026_09_15.edn` (including `:text`), and the stewards
self-improving PRD (including §8.4). The active roadmap and working edge
were read. The named message, note, plan, schema, fixture, help, advisory,
and turn settlement owners were inspected before their changes. Skills:
data-oriented-clojure, repl, data-modeling, datahike, clojure-testing,
seon-flow-architecture, and llm-providers.

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

Dependency pins: Datahike `cdcb5792db8bd599487f099437265d18a31164a5`;
SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`. Datahike's
`api/types.cljc` declares attribute dependencies as an attribute set or
`:all`; the read-step query checks that distinction before treating it as
a collection. The first fixture query failed on that marker; the corrected
fixture is exercised by the loop proof and the final live run.

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

## Item 3 — help, advisory, and the trial

Help includes these sentences:

> A function that can fail returns [:or <success> :seon.error/value]; a bare :maybe is refused.

> Auto-check calls your function with generated inputs including each collection's empty value.

> deftest and is are referred; use clojure.test/testing with its namespace.

The installation advisory now says:
`No example test gates <function>; add a deftest in your namespace that calls it, then (my.test/run).`
Help also explains automatic query completion and the last-form rule.

The existing trial harness made two requests, cheapest configured route
first. OpenRouter returned HTTP 402 (insufficient credits), recorded in
[attempt 1](help_trial_run7_wave_2026_09_15.edn). Only the scratch retry
cluster excluded that unavailable model row. Direct `deepseek-flash`
answered on the next priced route: 2,622 prompt tokens, 433 completion
tokens, 128 cached tokens, estimated $0.000634284 using its recorded price
rows. [Attempt 2](help_trial_run7_wave_2_2026_09_15.edn) retains the exact
prompt, reply, usage, model, and original score.

The model put each answer on the comment line following its numbered
question. The old scorer inspected only the question-header line and
reported 5/12. Its answer collector now includes continuation comments;
all existing negative checks remain. The exact captured reply is a
regression in `seon.help-trial-test`. Re-scoring the SAME provider bytes
gave **12/12**, with no further provider request:
[scored evidence](help_trial_run7_wave_scored_2026_09_15.edn).
Scorer fast gate: 2 tests / 23 assertions / zero failures or errors;
isolated gate: 2 tests / 25 assertions / zero failures or errors.

## Live reproduction and verification boundary

[The committed probe](run7_wave_probe_2026_09_15.clj) uses the existing
fixture installer, ordinary agent graph, call preparation, real SCI,
armed contracts, and the existing help-trial harness. Its `rescore!`
never calls a provider. Run it only on a disposable cluster.

The first two live proof attempts exposed probe mistakes: a sequential-only
contract was handed the query's set, then note writes omitted their required
ids. The two note steps stayed open, as required. Their exact evaluations
remain in [proof 1](run7_wave_live_2026_09_15.edn) and
[proof 2](run7_wave_live_2_2026_09_15.edn). The corrected sources accept
both sequences and sets and supply note ids.
[The final proof](run7_wave_live_final_2026_09_15.edn) completed all seven
steps; its only errors are the two intentional refusals. A final live read
confirmed all seven completion transactions and no next work after `done`.

The scratch cluster adopted publication
`6aa96f0b-8aa6-53b1-9192-9ace81bb60ec`; its stored source commit matched
the operator's converged publication. Later scratch clusters forked that
publication. **No RESET NEEDED** for these optional additions. Default was
read only: its installed send contract and new query declaration were
observed, but this note does not claim its concurrent source adoption had
finished. No browser-paint claim is made.

Commits: item 1 `af278535c`; item 2 `d31d31639`; item 3 is this commit.
The parallel isolated gates snapshot `af278535c` plus the explicitly named
lane paths. The final one-worker gate additionally includes the landed
schema-audit commit `0ca3c1d64` as its HEAD. Concurrent uncommitted
schema-audit edits remain outside those verification boundaries. The earlier pre-change check of
the unrelated turn-loop namespace used pristine `93883543d` and reproduced
20 failures / 1 error. No foreign files or sessions were repaired.

The final parallel gate reported 55 tests / 1,080 assertions / zero assertion
failures / one fixture-acquisition error. Its missing store key and passing
isolated confirmation are recorded in the
[existing published-base issue](../../../seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md).
The full requested gate passed with the supported `SEON_TEST_WORKERS=1`.
The nine separate continuation tasks all passed; their maximum isolated
duration was 43,475 ms. Their fast gate passed 9 tests / 162 assertions.

Scratch cleanup: operator `down` stopped PID 52792 and confirmed the flock
free. Both lane worktrees and their scratch roots were removed only after
the process table showed zero holders; symlinks were not followed. Default
remained PID 23729, alive, with no orphan Seon JVMs in the final status read.

Final one-worker gate: **55 tests / 1,099 assertions / zero failures or
errors**, coordinator-and-tests 322 seconds. It exercised
`seon.plan-completion-test`, `my.plan-api-test`, `my.plan-test`,
`my.message-test`, `seon.loop-proof-test`, `seon.turn-continue-test`,
`seon.help-trial-test`, `seon.repl-grammar-test`, `my.examples-test`,
`seon.help-test`, and `seon.core-functions-test` against HEAD plus this
lane's explicit paths. The runner removed its successful isolated root
`run.jNhN5i`. The parallel published-base acquisition defect remains open;
the single-worker pass does not claim to fix it.

## Files touched — dated inventory

```text
src/my/message.clj
src/seon/cluster/message.clj
src/my/plan.clj
src/seon/plan.clj
resources/seon/schemas/my.plan.item.edn
resources/seon/schemas/my.plan.edn
src/seon/turn.clj
src/seon/bootstrap.clj
src/seon/test/accretion.clj
test/my/message_test.clj
test/seon/context_blocks_fixture.clj
test/seon/plan_completion_test.clj
test/seon/loop_proof_test.clj
test/seon/turn_continue_test.clj
test/seon/turn_loop_test.clj
test/seon/help_trial_test.clj
```

Documents/evidence: this landing note; the
[trial harness](help_trial_2026_09_09.clj); the probe script and all six EDN
records linked above; the linked published-base and old-turn-loop issues;
and the assigned
[message issue](../../../seon/issues/archive/my-message-send-inside-a-compound-form-is-silently-lost.md)
and [completion issue](../../../seon/issues/archive/a-plan-step-can-be-marked-complete-without-its-done-when-being-true.md),
moved from `docs/seon/issues/` into its `archive/`. The owner maintains the
issue schedule; this lane did not edit its index.
