---
type: research
status: complete
tags: [turn, loop, test]
created: 2026-09-10
---

# Session continuation — loop-continue

Implementation: `57f1a8c23`. Assigned issue resolved and archived in
`49aa05e88`; the separate query finding is `25de70550`.

## Grounding and dependency ledger

Read AGENTS.md, the assigned issue, the turn PRD sections 14, 16 and 18,
`src/my/agent.clj`, and `test/seon/loop_proof_test.clj` end to end. Read
the assigned turn derivations, disposition settlement and self-rewake;
the active roadmap and working edge; and the Clojure, REPL, testing,
Datahike and data-modeling skills. The prior wake change was inspected in
`595b0bf7c`: virtual replies answer wakes by freezing after opening,
whereas submitted system source freezes with its identity.

Dependency pins at HEAD `eaed5a41b`:

- Datahike `cdcb5792db8bd599487f099437265d18a31164a5`:
  `reference-code/datahike/src/datahike/db/transaction.cljc:1152` passes
  the current database to transaction functions. First-party settlement
  is `seon.turn/evaluation-terminal-data` and `settle-batch!`; queries
  remain under `seon.db` on one supplied immutable database.
- SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`:
  `reference-code/sci/src/sci/core.cljc:330` and `:345` provide reusable
  contexts and isolated forks. The existing turn evaluator and canonical
  fixture supply them; this change adds no evaluator.
- core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`:
  `reference-code/core.async/src/main/clojure/clojure/core/async.clj:345`
  documents event selection. The turn proc's existing sliding-1 self-wake
  consults `more-agent-work?`; no proc, channel, or graph change is needed.

## Live reproduction and verification boundary

The supported read-only MCP JVM form on default returned:

```clojure
{:seon.test/next nil :seon.test/left 29 :seon.test/unanswered []}
```

It ran in 6,176 ms. `bin/seon status` reported default PID 23557 alive.
MCP runtime status returned health/Flow unknown with `Read timed out`,
reproducing [the existing MCP issue](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md).
This is an unavailable health observation, not evidence of process death.
No default agent or lifecycle action was performed. The orchestrator owns
the live provider run after landing.

The later query probe used `datahike.api/with` only to create an immutable
hypothetical database value; no connection transaction occurred. Its full
form and exact result are committed in
[the transaction-input issue](../../../seon/issues/bound-transaction-input-selects-an-older-turn.md)
(`25de70550`). A bound transaction pattern selected an older reply after
the latest hypothetical turn ended; an explicit equality predicate
returned nil. The continuation query uses that verified predicate.

## Exact derivation

With no open turn, `next-agent-work` first applies `opening-deferred?`.
Under that gate it admits either unanswered wakes or continuation:
the latest closed turn by its identity datom's transaction has reply-size,
at least one provider attempt without an error, and no terminal disposition.
The existing `more-agent-work?` and proc self-wake then open the next turn.

The existing settlement transaction now retains the last evaluation's
completed/wait control as `:seon.turn/disposition`, reusing
`:my.turn/disposition`. This fact records what the evaluator received;
it is not a stored session-open decision. Reading presentation text could
lose this control under profile elision. No new transaction is added.

The optional disposition declaration can accrete through source adoption.
Old provider turns were not backfilled with dispositions; a session whose
terminal control predates this change needs fresh fixture history before
testing its stop behavior. The observed blocked reply had no terminal
control. The recurring proof uses new turns on canonical fixture branches;
this lane neither resets default nor claims to migrate old histories.

Virtual/no-provider and submitted system replies do not continue: no
successful provider attempt exists. Their distinct wake-answering rules
are unchanged. Provider refusal still defers until an outside wake;
continuation uses the same bound and never refills it. The PRD section 14
amendment states these rules.

## Regression and gates

Supplemental HEAD-only baseline at `237c4c572`: `bin/test-fast --paths
test/seon/turn_work_test.clj -- seon.turn-work-test` reports 12 tests,
71 assertions, 33 failures, zero errors. The same 33 failures occurred
with the continuation overlay. Fixture writes leave `run-1` missing and
later assertions expect that missing turn to answer wakes. This is the
existing [obsolete turn-consumer fixture issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md),
not a failure introduced by continuation. No foreign files were changed.

The focused fast gate passed **1 test / 65 assertions**, zero failures or
errors, with 951 contracts armed in panic mode. The combined scoped gate
passed **5 tests / 217 assertions**, zero failures or errors, including all
four unchanged `seon.loop-proof-test` tests. Its coordinator/test phase
took 93 seconds; the runner removed `tmp/test-runs/run.Gor0b2`.
The command was `SEON_TEST_WORKERS=3 bin/test --paths src/seon/turn.clj
resources/seon/schemas/seon.turn.edn test/seon/turn_continue_test.clj
docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md
docs/prds/context-generation/research/loop-continue-landing-2026-09-10.md --
seon.turn-continue-test seon.loop-proof-test`.
The added recurring regression uses the canonical
database, Juniper fixture, actual SCI and armed per-agent graph. Only
`seon.ai/complete` supplies fixture responses, following the existing
`seon.turn-loop-test` provider-boundary idiom. Attempt recording, prompt
construction, evaluation, settlement, and work derivation remain real.

The read source is exactly 109 UTF-8 bytes:

```clojure
(seon.db/q '[:find (sum ?amount) . :where [?order :example/customer "Ada"] [?order :example/amount ?amount]])
```

Its saved shown text is exactly `115` (3 bytes). The second provider
request must include the entire saved evaluation's `repl/render-ai`
output, including its actual result handle and timing. The test also
requires nonempty read evidence. Done is followed by an authored write
that must remain unexecuted, distinguishing the last evaluated form
from later planned forms.

The terminal wait requires the expected closed provider attempts and then
the proc's existing ready permit before inspecting next work. A system
turn may have committed while the proc is still opening the next ordinary
turn; absence of next work in that intermediate database is not completion.
The test returns the permit before disarming and restores the provider Var
only after that graph has stopped.

Platform gate: `SEON_TEST_WORKERS=3 bin/test --paths src/seon/turn.clj
resources/seon/schemas/seon.turn.edn test/seon/turn_continue_test.clj
--platform` passed **84 tests / 505 assertions**, zero failures or errors.
The runner removed its successful root `tmp/test-runs/run.jiD9Hm`.

One focused fast run overlapping that platform run passed read-then-done
but failed during graph cleanup: `seon.cluster.agent/await-turn-completion!`
reported no observable open turn and no stop completion within 10,000 ms.
The command exited; no lifecycle owner was edited and no higher timeout
was substituted. The final serial fast run passed after the test awaited
the expected terminal attempts and the proc completion event.

| Case | Provider attempts | Turns remaining |
|---|---:|---:|
| Read, then done | 2 | 1 |
| Immediate done | 1 | 2 |
| Completed reply | 1 | 2 |
| Provider refusal | 1 | 2 |
| Bound of two | 2 | 0 |

Each case asserted the outside-wake transaction remained unchanged.

## Files touched

- `src/seon/turn.clj`
- `resources/seon/schemas/seon.turn.edn`
- `test/seon/turn_continue_test.clj` (the existing loop proof is unchanged)
- `docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`
  (section 14 only)
- `docs/prds/context-generation/research/loop-continue-landing-2026-09-10.md`
- `docs/seon/issues/archive/the-loop-stops-after-one-accepted-reply.md`
  (resolved and moved from `docs/seon/issues/the-loop-stops-after-one-accepted-reply.md`).
- `docs/seon/issues/bound-transaction-input-selects-an-older-turn.md`
  (out-of-scope query defect, evidence and reproducible form only).

Protected fixture edits and other lanes' render/schedule edits were
preserved. Gates select HEAD plus owned paths only.
All owned command sessions exited. The runner removed every owned test
snapshot/root; the lane removed its nine scratch logs after recording the
evidence above. No scratch cluster was created.
