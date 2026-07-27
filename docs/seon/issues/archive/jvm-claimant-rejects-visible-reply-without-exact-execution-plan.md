---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, runtime]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Plan a visible cluster JVM reply on an inspected tier

## Problem

The cluster JVM can persist a successful model response containing valid
agent forms, then reject the parsed reply because no inspected tier yields an
exact execution plan. The run closes visibly and releases custody, but no eval
receipt is admitted.

## Evidence

The source-frozen claimant2 agent `smooth-apes-relate` produced run
`dwvphar4i9yf`, turn `cw9vv09zcp8x`, and attempt `sj29e811vgsg`. JVM host
workload PID `50645` persisted DeepSeek HTTP 200 and a 163-byte reply containing
the requested message and completion forms. The attempt is `:success`; the
turn then closed `:error` with:
`The parsed reply has no exact execution plan on an inspected tier.`

No eval receipt exists. The run is closed and run-holding process/current-run custody is
absent, so this is not a wedge. Exact database values, claim/phase histories,
and verbatim reply evidence are in
`tmp/orchestrator/claimant2-gate.log`.

The source-frozen `planschema` run `q5ddb6i4pp4z` narrows the remaining class.
The same cluster JVM successfully executed six preceding forms, including
`my.plan/plan!`, three schema registrations, a transaction, and its read-back.
The final single registered form
`(seon.agent.lifecycle/complete "PLANSCHEMA_ALIVE")` alone produced the same
no-exact-execution-plan refusal. The turn became `:published`/`:error`, the run
closed, and custody was absent, so this remains a placement/projection defect
rather than a wedge. Evidence is in
`tmp/orchestrator/planschema-gate.log`.

## Owner

The derived `plan-execution` acquisition/enforcement boundary in
`seon.agent.driver.host` owns the inspected-tier disposition. It must consume
the canonical program graph and artifact inventories; do not add a symbol
allowlist or bypass for lifecycle/message forms.

## 2026-07-24 status

The no-roots class fix deliberately does not weaken this exact-plan boundary.
A reply with no executable roots is classified by `plan-execution` as
`:no-roots` and receives the explicit `:no-dispatch` disposition; the former
driver-local pre-classifier is deleted. A reply containing an unresolved
executable root still returns the existing steering error before phase or
receipt writes.

This closes the adjacent formless-reply classification defect, not this
issue's registered-form defect. The two visible lifecycle forms in Acceptance
still need one exact JVM execution plan and two successful receipts. Focused
portable planner coverage is green; the current-artifact writer gate and the
source-frozen live re-drive remain pending.

## 2026-07-24 live re-drive: planner exception and open-run wedge

The source-frozen default-cluster re-drive at HEAD `ab0913794` found a stronger
failure in the same exact-plan boundary. Agent `bright-candies-relax` persisted
the requested plan and two schema registrations over four terminal `:done`
turns. Its fifth DeepSeek attempt, `va7r5y0r4qnx`, succeeded with HTTP 200 and
left turn `m7mia62w9xhq` at `:reply-ready`.

That reply contained a prose lead-in followed by the valid form
`(seon.schema/register! :my.lifecycle.recovery3.memory/finding :string)`.
While specializing the parsed roots, `seon.program.edge/resolved-target`
passed an unresolved unqualified prose symbol through
`canonical-target`. `canonical-target` called `clojure.core/namespace` on nil,
raising an uncaught `NullPointerException`. The exception escaped the run-holding process
virtual thread instead of becoming the existing flat steering error.

Exact retained datoms at basis transaction `536874862`, before the request
deadline:

- run entity `8929`, id `bajoa6encx81`, was `:open`, claimed at epoch 10;
- turn entity `8966`, id `m7mia62w9xhq`, was `:running` at
  `:reply-ready`;
- attempt entity `8967`, id `va7r5y0r4qnx`, is `:success` with response status
  200; and
- the agent had a current-run ref to entity `8929`.

The run-holding process wrote no error or later receipt after the exception. At the
900-second `/agents/run` deadline, transaction `536874863` eventually closed
the run `:superseded`, retracted run-holding process/current-run custody, and published
the turn `:interrupted` with error
`The run closed :superseded before the active turn published.` This leaves no
permanent process residue, but the process was stuck until the outer request
deadline and never returned the existing steering value. That directly
falsifies Acceptance. Full evidence is appended to
`tmp/orchestrator/lifecycle-redrive-gate.log`.

## 2026-07-24 run-holding process NPE class correction

The analyzer now keeps an absent unqualified resolution as nil and classifies
an unresolved value symbol as the existing `:unresolved-symbol` edge
uncertainty. Thus the real parenthetical prose shape `(not forms)` remains a
parsed list—there is no prose regex or second classifier—but
`plan-execution` returns the ordinary unplannable result that the run-holding process
maps to flat steering data.

The portable driver now also catches any throw or rejected async result at the
claimed `execute-step!` call itself, converts it to a flat core-bug value, and
passes that value through the existing terminal-or-displaced and fenced
phase-error settlement owner. The serialized-writer regression makes an eval
phase throw from `:reply-ready` and observes, before `drive-claim!` returns,
the turn at `:published/:error`, the run closed `:error`, run-holding process/current-run
custody absent, and one core fault datom.

Focused JVM proof is green: portable edge/planner coverage is 17 tests and 82
assertions; the run-holding process writer namespace is 11 tests and 56 assertions. The
correction is commit `7f49d4674`; the default cluster was not touched. The
source-frozen live re-drive remains the acceptance gate for the two registered
lifecycle forms.

## 2026-07-24 final alive gate: disposition envelope mismatch

The source-frozen default cluster at HEAD `f906ccc28` reached the exact JVM
execution-plan success case and exposed a separate integration defect before
the first form could run. Fresh agent `wacky-paths-flash` opened run
`wxd2qdjx9q5t`, turn `ezvkk5biss3y`, and DeepSeek attempt `av87rvaprh9j`.
The attempt persisted `:success` with response status `200`; its reply contained
the requested `my.plan/plan!` form.

`seon.agent.driver.host/parsed-reply-plan` stores the complete
`execution-plan-disposition` map as the value of
`:seon.agent.driver/disposition`. `eval-step!` then binds that complete map to
`disposition` and passes it directly to `case`, whose arms expect the keyword
`:execute`, `:release`, `:steering`, `:core-fault`, or `:no-dispatch`. The exact
value that reached `case` was:

```clojure
{:seon.agent.driver/disposition :execute
 :seon.execution/selected-tier :jvm}
```

The run-holding process therefore recorded the core fault
`No matching clause: {:seon.agent.driver/disposition :execute,
:seon.execution/selected-tier :jvm}` instead of executing the valid form.
Transaction `536871128` closed the run `:error`, retracted both run-holding process and
agent current-run custody, and terminalized the turn
`:published`/`:error`. No eval receipt, requested plan, memory fact, or final
message exists. Full request/response and datom evidence is in
`tmp/orchestrator/alivegate-gate.log`.

## 2026-07-24 disposition contract correction and short live proof

Commit `a8555f257` restores the existing contract: `parsed-reply-plan` returns
an envelope whose `:seon.agent.driver/disposition` value is the complete
disposition map, and `eval-step!` cases on that map's
`:seon.agent.driver/disposition` keyword. The local is now named
`disposition-map`; steering errors and selected-tier evidence continue to come
from the same map.

The existing exact-plan writer test now passes real `parsed-reply-plan` output
through `eval-step!`, observes the `:execute`/`:jvm` plan reaching the eval
batch, and asserts the durable `:reply-ready → :evaling` transition. It no
longer stubs across the producer/consumer shape boundary. The run-holding process writer
namespace passes 11 tests and 63 assertions against the current v14 compiled
program rows. The ordinary `bin/test-writer` wrapper remains blocked by the
separately recorded stale fixture check that accepts only artifact v11.

The source-frozen default-cluster proof used fresh agent
`slimy-camels-stop`. At database value `536871243`, commit ID
`6a640709-ea78-59af-8337-139e62d00c55`:

- execute run `i8m9ed6dqn82` produced turn `bm2lxa1poeu6` at
  `:published`/`:done`;
- eval `b81a6e889u8d` is `:done`, `:seon.eval/ok? true`, and retains the exact
  `seon.agent.message/user` source plus result message ID `q73j53g56vho`;
- message entity `6323` was delivered from `slimy-camels-stop` to `user` in
  transaction `536871145`; the turn advanced through `:evaling` at
  transaction `536871142`, `:evaled` at `536871147`, and
  `:published`/`:done` at `536871150`;
- isolated no-dispatch run `vxb58jqeb91u` closed `:no-forms`, with run-holding process
  absent and the agent's current-run ref absent;
- formless turns `tzc1rmbkgxnk`, `zxa4zjrffh3c`, and `bi3ph8h3eqpv` are all
  `:published`/`:done` with no eval refs; and
- history joins the exact formless message deliveries to their
  `:reply-ready → :evaled` transactions:
  `[6362 "tzc1rmbkgxnk" 536871185]`,
  `[6377 "zxa4zjrffh3c" 536871228]`, and
  `[6381 "bi3ph8h3eqpv" 536871239]`.

The execute run itself later closed `:error` after DeepSeek invented an
unrequested third-turn inspection with an unresolved `ns/functions` root.
That later model deviation does not alter the successful execute disposition,
receipt, delivered message, or terminal `:done` turn above. The full staged
plan plus three-fact cross-turn memory proof remains the next integration
lane's acceptance work; this short proof closes the disposition-map regression
and both required disposition settlements.

## Acceptance

- The same two visible registered forms derive one exact JVM execution plan.
- The run-holding process writes two terminal successful eval receipts and completes the
  run.
- `parsed-reply-plan` and `eval-step!` agree on one disposition shape; an
  end-to-end run-holding process regression drives the real `:execute`/`:jvm` result
  through `eval-step!` and records a successful receipt.
- A genuinely unavailable or unresolved function still returns the existing
  flat steering error and releases custody.
- The staged alive gate persists its plan, writes and later reads the three
  schema-backed memory facts, delivers the formless synthesis as a transcript
  message on a `:done` turn, and closes without retained custody.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
