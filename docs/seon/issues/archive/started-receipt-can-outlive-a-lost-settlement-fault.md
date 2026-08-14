---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, flow, agent, class/n10, wave/settlement]
---

# Make every started receipt end in a durable terminal fact

## Problem

An agent turn can start a `:seon.cluster.eval` receipt, fail before the sole
terminal writer enters, and then lose the resulting core fault when the fault
committer itself faults before committing. The run remains open and
process-held, the receipt remains permanently `:running`, and the database
contains neither the settlement nor the reason it was lost.

The current 10-minute turn-completion backstop does not observe this state. It
exists only while another episode waits for a permit or while explicit disarm
waits for the turn to stop. A running, quiescent graph with a failed transform
has no active timeout.

## Evidence

The preserved 2026-08-14 specimen is documented in
[Lost settlement diagnosis](../../prds/sci-execution-runtime/research/lost-settlement-diagnosis-2026-08-14.md).
Run `48d2dc66-ec7d-4668-9e00-b495ed0e45ba` retained receipt ordinal 0 with
only its id, run ref, ordinal, and start instant. Its history contains no
terminal assertion. `work/next-agent-work` still derives `:resume` ordinal 0.

The work launcher retained a complete ordinal-0 evaluation report, so compute
completion was not lost. The fifth agent mailbox delivery was accepted, but
the turn's successful Flow count remained four. Its error channel was consumed
and the central fault channel was consumed.

The fault committer's own unjoined error channel retained the only complete
explanation:

1. `resume-turn` carried a failed `gate-function-install` phase as
   `:seon.sci.eval/evaluation` into `settle!`.
2. Instrumentation rejected the malformed settle request before its body and
   terminal transaction.
3. The fault committer took that Flow fault, then
   `read-core-error-mode` called `config/effective` without the required schema
   projection.
4. `:seon.config/missing-projection` escaped before `commit-fault!`; Flow put
   the second-order fault on the committer's own error channel, where no
   consumer or durable sink exists.

There were zero effect receipts, zero run-associated error facts, and zero
global error facts after the receipt start. Ten minutes past both shipped
600000 ms defaults produced no terminal observation.

This is not
[Settle a receipt for every recorded run form](a-runs-last-form-can-close-without-a-receipt.md).
That issue owns closed runs with comment/prose-only form rows and no receipt
entity. This issue owns open held runs with a started receipt whose settlement
and fault both disappear.

## Owner

The existing run-loop settlement choke point in `src/seon/cluster/loop.clj`,
the agent turn completion boundary in `src/seon/cluster/agent.clj`, and the one
fault committer assembled by `src/seon/cluster.clj` and
`src/seon/flow.clj`.

## Acceptance

- Every phase result after a receipt starts is classified before the
  `settle!` contract: only a declared evaluation reaches the evaluation arm;
  every flat error reaches the failure arm.
- A failure after receipt start produces exactly one terminal receipt fact, or
  a durable core fault that names why the terminal transaction could not be
  recorded. No such failure survives only on a process-local channel.
- The fault committer receives the config value or projection it needs as
  carried data and can commit from a thread with no ambient projection.
- A fault in the fault committer itself is fail-loud and bounded; its own
  unjoined error channel cannot be the final sink.
- The declared turn-completion backstop is active for an entered turn and
  fires when neither terminal completion nor stop arrives, without requiring
  a later episode or operator disarm.
- One class regression drives the exact sequence: successful compute eval,
  post-eval phase failure, instrumented settlement, fault-committer config
  read, then asserts a terminal receipt or durable fault before the bound.

## Related resolved issues

- [Instrumentation preempts the terminal settlement core fault](archive/instrumentation-preempts-terminal-settlement-core-fault.md)
  repaired hostile refusal construction; this specimen reaches a different
  instrumented contract from an ordinary live gate phase.
- [Supply the handed schema projection throughout fault encoding](archive/fault-encoding-lost-the-handed-schema-projection.md)
  repaired transaction encoding below `commit-fault!`; this specimen fails in
  the config-mode callback before `commit-fault!` is invoked.

## Resolution — 2026-08-14

Fixed by the settlement wave `bea9c068d`/`5f1601d2f`/`6a99a7a13`/`2500a7f0a`:
install-gate failures settle as typed terminals; the fault committer is
total (handed projection, joined error channel, bounded stderr
fallback); every active turn transform arms the completion backstop.
Live isolated-root proof: the exact reproduction settles the receipt
(`:seon.cluster.loop/phase-failed`) and commits the durable error fact.
