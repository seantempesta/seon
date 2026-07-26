---
type: issue
status: open
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

One emitted report and one emitted error each reach the application consumer
and every attached monitor exactly once; a stopped or slow monitor cannot
delay, steal, or close the application's delivery.
