---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, flow, agent, turn, observability]
---

# The turn proc cannot answer ping or stop during a model call

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D10, probe P2 on `default` pid 70720).**
The provider call (`ai/complete`) and every SCI evaluation run inline in the `:io` turn
transform (`src/seon/turn.clj`, audit rows 6 and 8). `(flow/ping-proc graph
:seon.agent/turn :timeout-ms 3000)` returned nil after 3,009 ms while turn
`355fab5ff381` had been open 51 s, so oversight shows `:unknown`, and an orderly stop
waits for the whole call.

Distinct from [a-parked-turn-proc-pings-unknown-on-a-healthy-agent](a-parked-turn-proc-pings-unknown-on-a-healthy-agent.md)
(a proc between turns) and a consequence of
[turn-evaluations-bypass-work-submission](turn-evaluations-bypass-work-submission.md).

**Wanted.** The model call as a launcher io submission whose completion re-enters the turn
proc on its own in-port (audit row 6), evaluations through `submit-evaluation!!` (row 8).
Regression: a turn proc answers ping within its window during a slow provider call.

**Owner.** `src/seon/turn.clj`. Audit §5 steps 11-13; the owner is discussing that scope.
