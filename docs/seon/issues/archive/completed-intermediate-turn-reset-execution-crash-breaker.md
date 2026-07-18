---
type: issue
status: resolved
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

## Resolution

Commit `6fae602b` connects each recovery fact to the interrupted eval and the
execution artifact digest. Automatic recovery now derives whether an earlier
recovery has the same eval source and artifact. Completed intermediate turns
do not reset that decision; only a later inbound message permits another
automatic attempt.

The source-free release with application SHA-256
`62dfd2e233dc03fde08bc762e4079209fab0534afde537dcb78b17bda18d5d2e`
proved the boundary on 2026-07-18. The exact source
`(js/process.exit 1)` retired a Bun execution child, recovered once, and then
retired the replacement child. `/agents/run` returned with closed reason
`:crashed`; it did not execute the source a third time, and the Bun pod
remained available. A later inbound message launched a fresh execution child
and completed `(complete "replacement child recovered")` in one turn and
7.66 seconds. The relocated release inventory was byte-identical before and
after the drive, and the package operator subsequently drained both its Bun
pod and JVM writer cleanly.
