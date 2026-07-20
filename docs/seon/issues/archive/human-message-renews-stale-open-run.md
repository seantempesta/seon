---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow, pod]
---

# Human messages renew stale open runs instead of superseding them

## Problem

A retained branch can resume an inherited open root run. When a new human
message arrives, the running wake path only renews that run's lease. Work from
the prior task can therefore continue appending turns after the new instruction
instead of starting the ordinary message-caused run.

## Acceptance

- A human message closes the currently owned run as `:superseded` and opens a
  message-caused run with that message as `:seon.agent.run/cause`.
- Agent-to-agent messages continue renewing an open run.
- Run fencing prevents the superseded run from committing later work.
- A live reused-root task follows the new human instruction rather than its
  inherited plan.

## Evidence

The coordinated reuse/repair diagnostic kept the watcher, writer, and isolated
branch stable through two 90-second phases. Root queried three agents from its
previous plan and never called `agent/delegate!`. The retained eval sequence
showed the stale trajectory continued after `/agents/run` had committed the new
human message. The focused loop regression proves the replacement run sequence.

Commit `1b2e90f6` then made the compiled prompt preserve the current run ID and
made the plan block anchor the step linked to that run's cause message. A rebuilt
execution child selected the exact 9c21 task instead of the older 7f4a task, but
a direct live product request still ignored its instruction for 13 turns. Its
first retained prompt exposed the remaining cause: the new turn was timestamped
after its opening message, and the bounded transcript used that turn as the
message cutoff. The prompt therefore omitted the human message itself and ended
in stale eval history. The current repair makes the bounded message query admit
the exact `:seon.agent.run/cause` independently of that timestamp cutoff. Focused
transcript proof passes 7 tests/27 assertions; rebuilt live task-priority proof
remains the acceptance gate.

The rebuilt request proved the query repair alone was insufficient. The exact
cause message was present in the bounded query result, then
`ordered-events` associated its old timestamp with turn zero and the ordinary
50/25 transcript rotation removed it. Prompt acquisition now pulls the current
run's existing `:seon.agent.run/cause` ref. The event projection associates
that exact message with the current turn before the shared clipping and settled
budget passes, without retaining another message list or bypassing transcript
bounds. Focused transcript and compiled-prompt gates pass 8 tests/29 assertions
and 15 tests/77 assertions.

The current immutable execution artifact then received
`CURRENT-RUN-CAUSE-PROOF-4` through the real `/agents/run` boundary. Its first
captured prompt contained the complete new request, selected its derived plan
step as the next ready work, and the agent acted on that step before returning
to any inherited plan. The run later timed out because the model marked the
step done without closing its run and resumed unrelated plan inspection. That
is remaining trajectory/lifecycle graduation evidence, but it no longer
falsifies current-message selection: the new human request survived clipping
and displaced the inherited work at the first decision boundary.
