---
type: issue
status: open
severity: friction
tags: [issue, test, flow]
---

# Flow monitor test preselects an unreserved port

## Problem

`seon.flow-test/free-port` asks the operating system for an ephemeral port,
closes the reserving socket, and only later gives that number to Flow Monitor.
Another process can bind the port between those operations, making the
recurring suite fail independently of Flow behavior.

## Evidence

`test/seon/flow_test.clj` constructs `ServerSocket(0)`, reads its local port,
and closes it before `clojure.core.async.flow-monitor/start-server` calls
HTTP-Kit's `run-server`. The dependency currently accepts a requested `:port`
but does not return the actual bound port as an ordinary field.

## Owner

The Flow Monitor test-server boundary in `test/seon/flow_test.clj`, with a
dependency change in `reference-code/core.async.flow-monitor` if the released
API cannot publish an operating-system-selected port.

## Acceptance

The test starts the monitor without a close-then-bind window, discovers the
actual bound port from the running server, and repeatedly passes while other
processes concurrently allocate ephemeral ports.
