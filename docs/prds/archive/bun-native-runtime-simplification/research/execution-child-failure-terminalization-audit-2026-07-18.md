---
type: research
status: complete
tags: [research, agent, flow]
---

# Execution-child failure terminalization audit

## Finding

Process containment is exactly-once, but durable agent recovery is not yet
connected to it.

`seon.execution.host` already settles one active invocation once, generation-
fences late child messages, retires the child on deadline/cancel/exit, and lazily
reconstructs the next generation. `seon.eval` already starts a durable
`:running` receipt before executing each form and terminalizes it with a leading
CAS; `record-eval!` treats a competing `:done`, `:error`, or `:interrupted`
terminal state as authoritative and never reruns the form.

The missing contract is narrower: an execution-child deadline or abnormal exit
does not invoke a database terminalization owner. `exit-child!` and the parent
deadline return an ordinary host error only. `agent.turn/eval-parsed!` converts
that response to an error, `close-turn!` normally records the turn `:error`, and
the loop closes the run `:error`; however, a receipt committed `:running` inside
the child before it wedged remains running until the whole autonomous runtime is
restarted. `seon.runtime.recovery/recover!` repairs it exactly once, but its only
production caller is cold startup in `seon.client/start-runtime!` and it scans
all nonterminated agents with run pointers.

## Earliest missing contract

After the host has conclusively retired one execution-child generation, one
agent-scoped authority operation must atomically terminalize only the durable
work owned by that failed invocation:

- leading CAS that the agent still points at the invocation's run;
- retract that exact run pointer;
- close the still-open run `:crashed` with `closed-at`;
- mark its still-running turn `:interrupted`;
- CAS every still-running eval receipt under that turn to `:interrupted`; and
- assert one recovery anchor for derived user-visible notice evidence.

Duplicate deadline, `proc.exited`, late IPC, cold startup recovery, or a late
successful eval record must converge on the same terminal facts. A loser
returns the already-terminal state as data and performs no second notification
or replacement transaction.

The host cannot safely infer which turn/eval started from process-local state.
The invocation already carries `::execution/run-fence`, agent ID, invocation ID,
and pinned database value. The missing operation should acquire current durable
facts by that agent/run fence and derive running turn/eval IDs from the database.

## Existing owners to reuse

- `seon.runtime.recovery/compile-recovery` already builds the one atomic shape:
  pointer CAS/retraction, open-run `:crashed` close, running-turn
  `:interrupted`, eval terminalization, and recovery anchor.
- `seon.eval.internal/terminal-tx-data` owns the receipt's `:running` CAS and
  derives `:seon.eval/ok? false` for `:interrupted`.
- `seon.eval/start-eval!` commits the running receipt before form execution.
- `seon.eval/record-eval!` owns normal terminal outcome plus program tee and
  already recognizes a competing terminal receipt without retrying execution.
- `seon.agent.run/close-tx-data` owns ordinary run close and pointer fencing,
  but calling `close-run!` separately is insufficient because turn/eval
  interruption must commit atomically with it.
- `:seon.agent.turn/status` already includes `:interrupted` and
  `:seon.agent.run/closed-reason` already includes `:crashed`.
- `seon.runtime.recovery/pending-notices` already derives notices from the
  anchor transaction and hides them after a later run; no acknowledgement or
  stored render is needed.
- `seon.execution.host/exit-child!`, `cancel!`, and the parent deadline are the
  process evidence owners. Their generation and active-invocation identity must
  select the recovery call; recovery must not be initiated by the child.

## Existing proof to preserve

- `execution.host-test/active-child-exit-settles-once-and-next-call-reconstructs`
  proves one settlement and generation replacement.
- `execution.host-test/parent-deadline-retires-a-non-settling-child` proves the
  external deadline.
- `execution.host-test/one-agent-deadline-does-not-block-another-agent-child`
  proves per-agent containment.
- `runtime.recovery-test/incomplete-runs-turns-and-evals-compile-one-fenced-transaction`
  proves the atomic recovery transaction shape.
- `runtime.recovery-test/concurrent-write-stale-fence-is-terminal-data` proves
  a stale fence does not retry.
- `eval.receipt-test/terminal-data-leads-with-running-cas-and-derives-ok` proves
  receipt terminalization.
- The `record-eval!` receipt tests prove normal terminal writes and competing
  settled-status handling; that competing-recovery assertion should be made
  explicit if it is not currently named as its own test.

## Shortest falsifier

Start one real eval whose receipt commits `:running`, then synchronously wedge
the execution child. Let the parent deadline kill it while a second agent
remains responsive. Before any pod restart, query the authority and assert:

- exactly one recovery transaction retracts the failed agent's exact run
  pointer;
- run is `:closed/:crashed`, turn and receipt are `:interrupted`;
- a duplicate exit callback/recovery request is write-free;
- a fabricated late `record-eval!` loses the receipt CAS and cannot publish its
  result/program tee; and
- a replacement child loads current source and can open a later run, causing
  the derived pending notice to disappear.

If the receipt remains `:running` until cold restart, the contract is still
missing. If a valid later run is closed or its pointer retracted, the fence is
wrong.

## Minimal implementation boundary

Strengthen `seon.runtime.recovery` in place with an agent/run-scoped request,
rather than creating a second recovery namespace. Factor the current pure
compile step so cold startup supplies its acquired target set and execution
failure supplies one fenced agent/run target. Both paths must emit the same
transaction vocabulary and anchor.

At the parent boundary, call this operation only after the child is irrevocably
retiring and only for an active invocation carrying a run fence. Deduplicate by
the existing generation/active identity; database CAS remains the correctness
authority. Resolve the invocation with the durable recovery outcome or bounded
recovery failure evidence, then remove the child. Startup failure, idle
retirement, source-reload retirement, and user cancellation without an active
run are not agent crashes and must not create recovery anchors.

The implementation should touch only the existing recovery owner, execution
host integration seam, and focused tests. It needs no new status, receipt shape,
notification entity, replay log, database rollback, or runtime-session concept.

## Source read-map

- `src/seon/execution/host.cljs` — deadline, cancellation, exit settlement,
  generation fencing, and replacement.
- `src/seon/agent/turn.cljs` — compiled eval invocation, turn open/close, and
  host-error conversion.
- `src/seon/eval.cljs` and `src/seon/eval/internal.cljs` — receipt start,
  terminal CAS, frozen result recording, and competing terminal state.
- `src/seon/runtime/recovery.cljs` — atomic cold recovery and notice projection.
- `src/seon/agent/run.cljs` — run close vocabulary, pointer fence, and crash
  outcome behavior.
- `src/seon/client.cljs` — the sole current production `recover!` call at
  autonomous startup.
- `test/seon/execution/host_test.cljs`, `test/seon/runtime/recovery_test.cljs`,
  `test/seon/eval/receipt_test.cljs`, and `test/seon/agent_loop_test.cljs` —
  current isolated proofs and the missing integration seam.
