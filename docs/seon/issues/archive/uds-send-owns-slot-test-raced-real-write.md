---
type: issue
status: closed
severity: friction
tags: [issue, database]
---

# UDS send-slot test raced the real encode and write

## Problem

`physical-session-send-owns-one-event-until-full-write`
(`test/seon/db/transport_uds_test.clj:592`) failed deterministically on a
fast machine: the second `send!` returned `send-accepted` where the test
expected `send-session-full`.

## Root cause

Test defect, not a runtime defect. The runtime contract in
`src/seon/db/transport/uds.clj` holds the one event slot from
`admit-event!` (line 610) until `finish-event!` fires after the frame is
fully written (line 898). The test sent a small event and immediately
asserted `session-full` on a second `send!` with no mechanism holding the
first event in flight. A small frame encodes on the worker thread and
writes fully into the OS socket buffer without any reader, so
`finish-event!` cleared the slot before the test thread's second `send!`.
The assertion depended on losing a race that a fast machine always wins.

## Fix

Made the slot ownership observable deterministically using the sibling
pattern already in the namespace
(`small-fanout-does-not-reserve-maximum-frame-bytes`): redef
`message-frame` to await a `CountDownLatch`, holding the first event in
the `::encoding` phase while asserting `send-session-full`, then release
the latch and complete the original read/completion/second-send arc.

## Proof

`bin/test-writer seon.db.transport-uds-test`: 32 tests, 177 assertions,
0 failures (twice). Full `bin/test-writer`: 231 tests, 1891 assertions,
0 failures, 0 errors.
