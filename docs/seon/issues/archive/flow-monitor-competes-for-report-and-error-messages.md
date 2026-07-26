---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, runtime, flow]
---

# Give Flow Monitor non-competing report and error taps

## Problem

Flow Monitor consumes the graph's report and error channels directly, so an
application consumer and the monitor can steal messages from each other.

## Evidence

`reference-code/core.async.flow-monitor/src/clojure/core/async/flow_monitor.clj`
resolves the graph's exact report and error channel objects in `start-server`
and passes them to `report-monitoring`, whose `alts!!` loop takes each message.
The running testbed in
`test/seon/flow_test.clj` proves that the released monitor otherwise attaches
and publishes topology, fixed-buffer, and wedge state over its WebSocket.
Detailed evidence is in
`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md`.

## Owner

The production Flow graph's report/error fan-out boundary, before
`clojure.core.async.flow-monitor/start-server`.

## Acceptance

One emitted report and one emitted error within their configured tap bounds
each reach the application owner and Flow Monitor exactly once. A stopped or
slow monitor cannot delay, steal, or close fault commitment. Bounded monitor
taps may discard old presentation messages on their own overflow; they are not
a durable fault authority.

## Resolution

Resolved by the Flow testbed error-fan-out change that archives this note.
`seon.flow/start-error-fanout!` owns a `mult` over each source channel. The
fault-committer proc consumes the error tap and commits through a supplied
database function; Flow Monitor receives independent report/error sliding taps
through a datafiable graph that delegates the public Flow API to the source
graph.

The recurring `core-fault-fanout-commits-and-copies-without-competition` test
attaches released Flow Monitor code, throws from `:eval`, and observes the same
fault as a Datahike fact and a monitor error naming `:eval`. A following report
reaches both the application report tap and monitor. The capacity regressions
prove six faults at capacity six lose nothing, and five faults at capacity two
commit two fault facts plus a durable drop count of three.

The former acceptance sentence was too strong for a bounded operations UI:
exactly-once delivery to an indefinitely slow monitor and bounded,
non-backpressuring delivery cannot all hold. Durable fault facts and the loud
drop counter are authoritative; monitor delivery is bounded presentation.
