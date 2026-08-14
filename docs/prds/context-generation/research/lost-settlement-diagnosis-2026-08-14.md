---
type: research
status: complete
tags: [research, runtime, flow, agent]
---

# Lost settlement diagnosis — 2026-08-14

## Verdict

The preserved specimen did not lose a successful evaluation in the work
launcher and did not lose a self-wake. It exposed two failures in series:

1. `resume-turn` checked the evaluation returned by `submit-evaluation!!`, then
   replaced it with the result of `gate-function-install` under `phase` without
   checking that second result for `:seon.error/kind`. The gate phase failed.
   Its flat error value was subsequently supplied as
   `:seon.sci.eval/evaluation` to instrumented `settle!`. The settlement
   contract refused the malformed request before the sole terminal writer's
   body ran. The already-started receipt therefore acquired no terminal fact.
2. Core.async Flow caught that contract exception and published it on the
   agent graph's error channel. The error fan-out delivered it to the shared
   fault committer. Before calling `commit-fault!`, the committer called a
   callback that evaluated effective config without the required handed schema
   projection. That threw `:seon.config/missing-projection`. Flow published this
   second exception on the fault-committer graph's own error channel, which has
   no further fan-out or durable sink. That in-memory channel is the precise
   handoff where the terminal evidence evaporated.

The `:released` report from the earlier `:call` pass only reports that the
model reply was frozen into a plan. Reports are observational. The work
launcher independently proves that ordinal 0's compute evaluation returned a
complete value. Neither report is a settlement fact.

This is a `class/n10` transport-law violation: accepted work has a durable
start, but the only evidence of why it did not settle survived solely in a
process-local channel.

## Specimen and method

- Operator root: `tmp/accretion-graduation-c528efb0a`
- Cluster: `default`
- JVM: pid 59115, io-prepl port 55938
- Run: `48d2dc66-ec7d-4668-9e00-b495ed0e45ba`
- Agent: `accretion-graduate`

All live inspection used JVM-mode io-prepl with the explicit read-only
`(seon.operator/connection "default")`. No door evaluation, transaction,
channel take, wake, graph transition, or process signal was performed. Channel
contents were inspected through the live Flow values and non-removing buffer
inspection. The specimen remained running until this report and its issue were
committed.

`mcp__seon__runtime_status` was degraded by the same
`:seon.config/missing-projection` configuration-read defect; the sanctioned
`mcp__seon__eval_clj` JVM path remained available. That observer recurrence is
already recorded in
[Make the MCP wrappers preserve errors and make status one bounded derivation](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md),
so this report does not open a duplicate observer issue.

The retained dump
`tmp/orchestrator/graduation-wedge-vthreads.txt` contains 650 lines. It does
contain parked Seon infrastructure frames (Flow procs, the error fan-out,
projection executors, and the schedule timer), so “zero Seon frames” is too
strong literally. It contains no `resume-turn`, `settle!`, gate, work-launcher,
or agent `turn-step` frame. The relevant work was not blocked on a thread.

## Durable fact evidence

The run opened at `2026-08-14T06:24:53Z`, retained
`:seon.cluster.run/process`, retained its plan digest, and had no
`:seon.cluster.run/closed-at`, run error, interruption, or undisposed marker.
The frozen plan has three ordinary agent-authored forms. Ordinal 0 is the
contracted `largest` definition; ordinal 1 calls it; ordinal 2 reads its stored
`:seon.fn/sym`.

The receipt census returned exactly one receipt:

```clojure
{:db/id 30023
 :seon.cluster.eval/id
 "[\"48d2dc66-ec7d-4668-9e00-b495ed0e45ba\" 0]"
 :seon.cluster.eval/run 30016
 :seon.cluster.eval/ordinal 0
 :seon.cluster.eval/at #inst "2026-08-14T06:24:58Z"}
```

Its complete current and history datoms are exactly those four assertions, all
in transaction 536870976. There has never been a result EDN/blob, result size,
error, interruption instant, ending namespace, or accretion disposition on the
receipt. This is durable proof of “started,” not an inference from absence of a
separately expected entity.

There are zero `:seon.effect/run` receipts for this run, zero run-associated
`:seon.error` facts, and zero global error facts at or after the receipt's
start instant. The agent has only two inbound messages in the specimen and no
outbound or follow-up message: the bootstrap task at 06:16:22Z and the drive
trigger at 06:24:52Z.

The current derivation is consequently exact:

- `work/next-ordinal` treats an eval receipt as terminal only when it contains
  `:seon.cluster.eval/result-edn`, `/error`, or `/interrupted-at`
  (`src/seon/cluster/work.clj:97-134`).
- `form-settlement` calls this receipt `:running`
  (`src/seon/cluster/work.clj:304-342`).
- `fold-or-close` returns `:resume` with ordinal 0
  (`src/seon/cluster/work.clj:488-501`).
- `next-agent-work` therefore returns `:resume`, and `more-agent-work?` is true
  (`src/seon/cluster/work.clj:530-595`).

## Process-local Flow evidence

The live graph state makes the failure sequence observable even though no fact
does:

| Observation | Live value | Meaning |
|---|---:|---|
| agent mailbox deliveries | 5 | the post-`:call` self-wake was accepted |
| agent turn successful count | 4 | the fifth transform did not return |
| mailbox and episode buffers | 0 / 0 | no queued wake or episode remains |
| wake channels | live | no closed-channel rejection explains the stop |
| completion permit | 1 in fixed-1 | the transform's `finally` published readiness |
| agent error channel | one take, buffer 0 | the original fault was emitted and consumed |
| central fault channel | one take, buffer 0 | fan-out delivered the original fault |
| fault committer | running, committed 0, panicked 0 | it took the fault but returned no output |
| fault committer error channel | one buffered value | its own transform fault has no consumer |

The work launcher's non-removing report-buffer inspection found one matching
`:seon.flow/work-complete` for submission id
`["48d2dc66-ec7d-4668-9e00-b495ed0e45ba" 0]`. Its value contains the full
evaluation fields, including `:seon.cluster.eval/result-edn`, ending namespace,
defs, program row, admission record/value, read evidence, and print options.
The compute work therefore completed normally.

This matches the launcher ordering: `execute-work!` delivers the private
result promise before offering the observational completion report
(`src/seon/flow.clj:332-386`), while `submit!!` waits on that private promise
and either returns the value or throws the worker exception
(`src/seon/flow.clj:813-876`). A dropped observational report cannot explain
this specimen.

## Exact settlement and fault path

1. The `:call` pass froze the DeepSeek reply and returned `:released`. The
   agent transform then re-derived more work and offered a wake to its own
   sliding-1 mailbox (`src/seon/cluster/agent.clj:354-378`). The fifth mailbox
   delivery proves that offer was not lost.
2. The next pass derived `:resume` ordinal 0. `resume-turn` transacted
   `run/receipt-start-tx`, producing receipt 30023
   (`src/seon/cluster/loop.clj:1565-1574`).
3. `submit-evaluation!!` submitted bounded `:compute` work and returned the
   completed evaluation (`src/seon/cluster/loop.clj:394-412`). The preserved
   work report proves this stage returned normally.
4. `resume-turn` correctly checked this first evaluation value for an error
   (`src/seon/cluster/loop.clj:1601-1609`). It then rebound `evaluation` to
   `(phase #(gate-function-install ...))` (`:1610-1614`) but did not perform
   the same check again.
5. The gate phase threw. `phase` converted the throw to a flat error value.
   The next phase and terminal branch carried that value under
   `:seon.sci.eval/evaluation` (`:1615-1643`). The original gate exception was
   not separately retained, so the specimen proves the failed seam but cannot
   honestly name the gate's underlying cause.
6. Malli instrumentation rejected the `settle!` input as
   `:seon.instrument/contract-violated`: expected
   `:seon.cluster.loop/settle-request`, invalid input, missing required key.
   Its reduced offending projection was
   `[{:seon.sci.eval/evaluation {:seon.sci.admit/value nil}}]`. The loaded
   stack is `resume_turn` → instrumented `settle!` → `turn_step`. Because
   instrumentation runs before `settle!`'s body, the sole terminal writer at
   `src/seon/cluster/loop.clj:681-739` never prepared or attempted a
   transaction.
7. Core.async Flow catches a transform throwable, writes a fault to the proc's
   error channel, and leaves its successful count unchanged
   (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:300-320`).
   Because `cluster.loop/turn` threw, the rest of `turn-step` did not execute:
   no later self-rewake and no observational turn report were produced. Its
   `finally` did publish the completion permit.
8. `join-error-fanout!` took the agent fault and put it onto the cluster's
   central fault channel (`src/seon/flow.clj:1132-1157`). This handoff worked.
9. `fault-committer-step` calls `read-core-error-mode` before `commit-fault!`
   (`src/seon/flow.clj:988-999`). The cluster callback calls
   `(config/effective @connection cluster-name)` without a projection
   (`src/seon/cluster.clj:2325-2329`). It threw
   `:seon.config/missing-projection` with message “Effective config requires
   the projection handed to this operation.” No fault transaction had yet
   been attempted.
10. Flow caught that second exception and placed it on the fault committer's
    own error channel. That channel is not joined to a higher fault committer.
    Its one buffered value still contains the original agent fault under
    `:seon.flow/msg`; this is the last surviving settlement evidence and the
    exact process-local handoff at which durability ended.

The event did not evaporate at the work launcher's completion offer, the
agent's sliding self-wake, or the central error fan-out. The terminal
settlement transaction was never constructed, and the durable substitute—the
core fault—evaporated one layer later on the fault committer's own error
channel.

## Why neither 10-minute bound fired

The applicable semantic event is turn completion, not background effect
completion.

`:seon.config.effect.background/time-limit-ms` is 600000 ms
(`config/default.edn:34-40`), but it bounds detached `:io` effect work. This
run has no effect receipt. The ordinal-0 compute submission used the ordinary
evaluation time limit and completed, so the background-effect limit should not
fire.

`:seon.config.agent/turn-completion-backstop-ms` is also 600000 ms
(`config/default.edn:130-136`). It is the correctly named backstop for a turn
that never publishes its terminal event, but the implementation is not an
armed lifetime bound:

- `await-turn-permit!` creates the timeout only while a newly delivered
  episode is waiting to acquire the completion permit
  (`src/seon/cluster/agent.clj:271-291`). No sixth episode arrived after the
  failed fifth transform.
- `await-turn-completion!` creates the timeout only during explicit disarm,
  while shutdown waits for completion or `turn-stopped`
  (`src/seon/cluster/agent.clj:588-630`). The specimen was deliberately never
  disarmed.

After the fifth transform failed, no thread or proc was waiting under either
timeout. Ten minutes of quiescence therefore had no observer. The
turn-completion backstop's failure to cover a running proc whose held run has a
started nonterminal receipt is a second independent defect; silence after the
bound is current behavior, not evidence of health.

## Issue classification

I read
[Settle a receipt for every recorded run form](../../../seon/issues/a-runs-last-form-can-close-without-a-receipt.md)
end to end. It is not the same class. That issue concerns a **closed** run with
a recorded comment/prose-only form and **no receipt entity at all**. This
specimen is an **open, process-held** run with an existing started evaluation
receipt whose terminal settlement and subsequent fault evidence were lost.

The new blocker is
[Make every started receipt end in a durable terminal fact](../../../seon/issues/started-receipt-can-outlive-a-lost-settlement-fault.md).
It is a concrete member of
[Make accepted work require terminal evidence](../../../seon/issues/class-accepted-work-can-end-without-terminal-evidence.md).
It also recurs adjacent guarantees from the resolved instrumentation and
fault-projection issues, but through new live production paths rather than
their repaired mechanisms.

## Fix shapes, simplest first

### 1. Totalize the existing seams in place — recommended

After every value-returning phase in `resume-turn`, including
`gate-function-install`, immediately route an error value through the existing
failure arm of `settle!`; only the declared evaluation shape may enter the
evaluation arm. Hand the already-derived core-error mode or its schema
projection to the fault committer when the graph is armed, so its callback
does not fetch config without its world. Arm the existing turn-completion
timeout for each entered transform and cancel it only after terminal
completion is published.

Guarantee: a started receipt reaches the one terminal writer; if that writer
throws, the existing fault path can durably record why; if neither event
arrives by the declared bound, the existing backstop emits a fault. Cost: a
small in-place change across the run-loop, cluster/fault-committer arguments,
and current completion ownership, with no new fact family or proc. What it
gives up: it detects the in-process timeout from the turn boundary rather than
making overdue turns independently queryable after crash.

### 2. Derive overdue started receipts in existing scheduled maintenance

Keep the phase and projection repairs above, and add a root maintenance task
that queries process-held runs for started nonterminal receipts older than the
declared turn bound, then commits the ordinary core fault through the one
fault owner.

Guarantee: the database supplies the subject and age, so an overdue receipt is
observable even after the original transform has vanished. Cost: scheduled
machinery and a clock-based query; until the maintenance portfolio lands, a
new periodic owner would violate the one-mechanism law. What it gives up:
immediate detection at the turn seam, and a totally quiescent system still
needs the schedule event.

### 3. Persist a separate turn-attempt lifecycle

Record entered/completed turn attempts and let recovery or a schedule compare
them with receipt settlement.

Guarantee: strongest crash-forensic record of the transform itself. Cost: a
new durable fact family, reconciliation rules, and duplication of information
already represented by run custody plus evaluation receipts. What it gives
up: the derive-don't-store law and the smallest-real-thing constraint. This is
not recommended unless the simpler receipt-derived design is falsified.
