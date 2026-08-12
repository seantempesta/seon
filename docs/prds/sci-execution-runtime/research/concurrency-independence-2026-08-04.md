---
type: research
status: blocked
tags: [research, concurrency, agent, sci, datahike]
---

# Concurrency independence stress proof — 2026-08-04

## Verdict

The N-way fact harness reached the protected SCI installation boundary and
reproduced a blocker: five agents defining five distinct contracted symbols
concurrently emitted `:seon.sci.eval/install-source-mismatch` with the message
"Committed declaration source does not match install request." The same
failure had previously been observed for a same-symbol bootstrap
redefinition; this run proves the defect is broader than redefinition.

Per the lane stop rule, I did not edit `src/seon/sci/eval.clj`,
`src/seon/cluster/run.clj`, or another lane's session. The existing blocker is
updated at
[bootstrap-redefinition-fences-agent-runs](../../../seon/issues/bootstrap-redefinition-fences-agent-runs.md).

The run did prove substantial fact-space behavior before the blocker, listed
below. It did **not** graduate the requested end-to-end claim because the core
fault is real and the first ring construction also caused automatic completion
replies, violating the zero-model-call acceptance condition.

I read the requested session-curation PRD, both namespace-semantics reports,
`src/seon/bootstrap_drive.clj`, and `src/seon/bootstrap.clj` end to end before
designing or running the proof. I also read the complete active program plan,
unsettled ledger, localized runbook, architecture owner, and the applicable
data-oriented Clojure, Datahike, Clojure-testing, and REPL skills.

## Dependency ledger

- Seon source frozen by the focused test runner: Git
  `f98b159c4b20`. The shared checkout advanced
  while the isolated test ran; conclusions here belong to the runner's frozen
  copy, not later in-flight edits.
- Datahike/Proximum pin: `574c5f0f0db9`,
  especially its serialized writer and history query semantics under
  `reference-code/datahike/src/datahike/writer.cljc` and
  `reference-code/datahike/src/datahike/writing.cljc`.
- SCI pin: `2db3358cba91`, especially
  `reference-code/sci/src/sci/core.cljc` fork/Var behavior.
- core.async pin: `dc35f3e0d7bc2eef502e77982f48641f025c8051`, especially
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` and
  `flow/impl.clj`.
- First-party caller-provided plan owner:
  `src/seon/cluster/run.clj` `system-run-tx`, reached by
  `src/seon/bootstrap.clj` and the new harness.
- First-party fold, custody, and durable receipt owners:
  `src/seon/cluster/loop.clj`, `src/seon/cluster/work.clj`, and
  `src/seon/cluster/run.clj`.
- First-party namespace/program owners: `src/seon/cluster/agent.clj`,
  `src/seon/sci/eval.clj`, and `src/seon/fn.clj`.
- Recurring proof surface:
  `test/seon/concurrency_independence_test.clj`, marked long/full-tier and
  selected explicitly by namespace during this run.

## Environment and method

- Live single-agent probe operator root:
  `tmp/concurrency-independence-probe`; cluster `concurrency-probe`.
- Focused recurring test command:
  `bin/test seon.concurrency-independence-test`.
- Retained isolated failed runner root: `tmp/test-runs/run.tXsWG5`.
- Test cluster: `concurrency-independence`, never `default`.
- Scenario portfolio: three N=5 rounds followed by three N=10 rounds.
- Forms per planned run: transact three complete `:seon.test.run` rows with
  `:seon.db/user` transaction provenance; query those identities; define one
  contracted function in the assigned namespace; call it; send to the next
  agent; complete.
- Assertions used database facts and history for receipt/run attribution,
  custody, transaction provenance, program rows, ring connections, and
  transcript inputs. A receipt-transaction/close-transaction comparison
  required every run to commit progress before the first run closed.
- Provider calls were replaced by a counting local stub, so no external model
  request or cost occurred. The count was required to remain zero.

## What is proven

The focused run executed 2,593 assertions. The following fact assertions held
in every N=5 and N=10 scenario:

- Every expected receipt identity was connected to exactly its expected run,
  and every run was connected to exactly its expected agent. There was no
  cross-attribution among the planned runs.
- History showed exactly one custody process per planned run. Every planned
  run closed and had no current `:seon.cluster.run/process` fact afterward.
- Every planned run committed at least one receipt before the first planned
  run in its scenario closed. This is the fact-space overlap proof, not an
  inference from logs or thread names.
- Each agent committed three complete `:seon.test.run` rows. History connected
  every row's transaction to that exact agent through `:seon.db/user`.
  Exact per-scenario counts were 15 rows at N=5 and 30 rows at N=10, with no
  missing or duplicate expected identity. Across all six rounds, all 135
  expected rows survived the one branch's concurrent writes.
- Every contracted definition existed as one `:seon.fn` program row with the
  expected fully qualified symbol, assigned `:seon.ns`, exact source,
  `:seon.schema.admission/source :agent`, and a retained contract.
- Each function-call receipt returned its agent-specific expected result.
  Each in-plan query returned exactly that agent's three row identities.
- All N explicit ring messages were present with the expected sender,
  recipient, and `:seon.cluster.message/caused-by` connection.
- Each rendered transcript contained all six of its own source forms and
  excluded every other agent's function symbol and committed row identities.
  Unrelated ring payloads were also absent.

The isolated live pre-probe additionally proved one caller-provided planned
run can commit an ambient database write, query it, install and call a
contracted definition, complete, and close without any model call. A separate
two-agent pre-probe showed both runs committing receipts before either closed
and both explicit ring rows fully attributed.

These are component proofs, not a claim that the complete acceptance matrix
passed.

## Failed assertions and platform blocker

### Blocker: concurrent distinct definitions cross installation requests

The focused N=5 scenario emitted this core fault:

```text
SEON CORE FAULT (dev panic): Committed declaration source does not match
install request. [signature
0708a7b745ed9db91eb8ef9813a245b59f8b69ffbdd99c9657a421d5d0a51e93]
```

The symbols were distinct, from
`my.agents.concurrency.s0-n5.a0/stress-f-s0-n5-0` through
`my.agents.concurrency.s0-n5.a4/stress-f-s0-n5-4`. The thrown owner in the
runner's frozen source is `src/seon/sci/eval.clj:683`. This is the exact
protected boundary owned by the other fix lane, so the stress lane stopped
without modifying or steering it.

This failure prevents a proof that durable program facts, the live cluster
program, and the installation request remain one coherent value under N-way
definition concurrency, even though the durable rows and call receipts above
were present.

### Harness correction required: completion replies extend the ring

The first ring construction preidentified each outbound message as the next
agent's run trigger. Once the send added `:seon.cluster.message/from`, the
recipient's later `my.run/complete` correctly derived an automatic reply.
Those `ordinal-5-message-0` rows were new unanswered triggers and caused
additional stubbed model episodes. Therefore:

- the exact two-message transcript-input assertion failed;
- the unanswered-trigger assertion failed; and
- the zero-model-call assertion failed, although no external provider was
  contacted.

This is a harness design error, not evidence against message delivery. The N
explicit send rows themselves passed their complete causal-ring assertion.
The next harness revision must fold the system-authored plans directly and
concurrently while the new agents are not auto-armed, so completion has no
trigger and the delivered ring rows cannot start follow-up episodes. That
revision remains unverified because this lane stopped at the protected
installation blocker.

### Harness diagnostic error

The receipt-failure Datalog predicate returned successful rows whose three
failure projections were all absent. It produced six false failure reports,
one per scenario, despite the separate exact receipt and result assertions
passing. The query must select the three actual failure arms separately rather
than use the current predicate expression.

## Timings

The focused run printed these seed-to-planned-run-close timings:

| Round | N | Elapsed ms |
|---|---:|---:|
| s0-n5 | 5 | 5,405.27 |
| s1-n5 | 5 | 4,880.03 |
| s2-n5 | 5 | 6,056.76 |
| s3-n10 | 10 | 9,952.84 |
| s4-n10 | 10 | 12,532.26 |
| s5-n10 | 10 | 15,409.14 |

These are recorded evidence, not accepted performance results. The completion
reply defect caused extra local-stub episodes during the run, and the core
installation fault was present. Fresh timings are required after both the
protected fix and the harness ring correction.

## Ugly output

The first receipt diagnostic printed every successful receipt tuple as a
failure, producing a very large and misleading test face. Transcript
assertion failures then embedded entire multi-kilobyte transcript strings in
the default `clojure.test` output. Both are defects in the recurring harness's
failure face: the repaired proof should report compact identity differences,
never whole transcript bodies.

The live pre-probe's MCP runtime status also returned a nested contract error
instead of cluster health. That already-tracked defect is
[dev-mcp-envelopes-misdirect-errors-and-sprawl-status](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md).

## Graduation boundary

The earliest unsettled contract is distinct-symbol concurrent installation:
five agents on one branch must commit and install five exact sources without
`install-source-mismatch`. The protected SCI lane owns that repair.

After it lands, the dependency-ready stress refill is the direct concurrent
fold correction described above, followed by the same three N=5 and three
N=10 rounds. Graduation requires all fact assertions, zero durable core
faults, zero provider calls, exactly N explicit ring messages, exact
transcript ownership, and fresh uncontaminated timings from the recurring
full-tier namespace.
