---
type: issue
status: closed
severity: high
tags: [issue, database, flow]
---

# Reserve realistic bytes for addressed session fanout

## Problem

Admission-only JVM session delivery reserves twice the maximum protocol frame
before Transit encoding, then shrinks to the exact frame. This safely bounds
the payload-to-frame copy, but the default 256 MiB authority ceiling can appear
full after roughly 32 concurrently admitted events even when every final event
is tiny. A rapid broad-interest fanout may therefore close healthy sessions
because conservative accounting, not retained bytes, exhausted capacity.

## Evidence

`reserve-output-allowance-result!` charges
`2 * (4 + protocol/maximum-frame-bytes)` for every admitted response. Encoding
is asynchronous, so a delivery loop can admit many sessions before codec
workers call `shrink-output-reservation!`. The risk is structural and requires
a deterministic 64-session small-event falsifier rather than worker-count
tuning.

## Owner

The native UDS transport owns both codec concurrency and retained output bytes.
Measure a seam where bounded codec workers limit transient payload/frame copies
and exact framed bytes are reserved after encoding, without creating an
unbounded encoded queue or blocking the delivery worker. Preserve per-session
order, authority/session pressure distinction, and shutdown evidence.

## Acceptance

- At least 64 small addressed events admit without maximum-frame false pressure.
- Maximum-size concurrent encodes and slow readers stay within explicit global
  and per-session memory bounds.
- Exact output bytes are released on write, close, encoding failure, and forced
  shutdown.
- One slow or oversized session cannot delay healthy sessions or control entry.

## Resolution

The transport now admits by the existing bounded response slots, encodes on the
fixed codec-worker pool, and reserves only the exact framed bytes before the
selector retains them. Retained frames remain bounded by the configured global
and per-session output ceilings. Temporary encode allocation is independently
bounded by at most eight codec workers; it is no longer charged once per
unencoded response.

Focused proof passes 32 tests and 153 assertions. It holds encoding blocked
while 64 physical sessions admit small addressed events with zero retained
output bytes, then proves all 64 complete in order. A separate exact-byte
pressure fixture proves an oversized session completes with session pressure,
releases all bytes, and closes without delaying a healthy sibling.
