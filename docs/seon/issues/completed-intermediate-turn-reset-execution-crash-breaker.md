---
type: issue
status: open
severity: blocker
tags: [issue, agent, cljs, database]
---

# Intermediate turn reset the execution crash breaker

## Evidence

On 2026-07-18, two concurrent agents completed normally through separate Bun
execution children in 7.72 and 7.90 seconds. A third agent deliberately ran
`(js/process.exit 17)`. Its child exited without taking down the pod, but the
same request then created five turns and executed the crashing form three
times before the 30-second request deadline. A subsequent request for that
same database-backed agent started a replacement child and completed normally
in 7.71 seconds.

The architecture permits one automatic recovery run, then requires a repeated
crash for the same eval source and execution artifact to leave the agent idle
and surface its evidence. `automatic-run-after-recovery?` instead allowed
another recovery after any completed intermediate turn. The harmless
`plan/active!` turn between two exits therefore reset the breaker even though
no human or agent had provided new input and the same form was about to run.

## Owner and acceptance

`seon.runtime.recovery` owns the durable recovery transition and its derived
automatic-run decision. Each recovery anchor must connect to the exact
interrupted eval and execution artifact digest. A prior recovery for the same
eval source and digest permits no second automatic run unless a later inbound
message deliberately contacts that agent. Focused history-query tests and a
real source-free Bun package must prove one automatic replacement, a second
same-form crash leaving the agent idle, root-visible evidence, pod survival,
and successful recovery after new input.
