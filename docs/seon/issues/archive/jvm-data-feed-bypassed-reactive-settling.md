---
type: issue
status: resolved
severity: blocker
tags: [issue, web, database, flow, architecture]
---

# Route the JVM data feed through reactive settling

## Problem

The JVM `/data/feed` renderer listens to every writer transaction directly.
Its depth-one socket mailbox bounds slow-client backpressure, but a fast client
still receives one complete morph per transaction. This bypasses the one
`seon.reactive` settle, newest-pending, and equality-suppression mechanism.

## Evidence

The accepted overnight capture committed 21 rapid transactions and observed
22 JVM frames, including initial paint. `seon.web.feed/start!` owned a direct
`seon.db.host/listen!` callback that rendered each `:db-after`; the pod feed
already registered the demanded view with `seon.reactive`.

Commit `b9439599d` promotes `seon.reactive` to portable `.cljc` and makes each
equivalent JVM view a consumer of that same scheduler. The depth-one mailbox
now begins after the settled render and remains only the latest-complete-state
socket boundary. Commit `fd96c2d71` gives the JVM scheduler a daemon-owned
process-local timer thread.

The focused JVM regression commits a 20-transaction burst, observes fewer than
20 resulting frames, and proves the final frame contains the newest database
basis. The pinned writer/Datastar run passed 2 tests / 20 assertions.

## Owner

`seon.reactive` owns transaction settling and demanded recomputation on both
tiers. `seon.web.feed` owns only Datastar framing, gzip selection, heartbeat,
connection capacity, and latest-complete-state socket delivery.

## Acceptance

- Pod and JVM feeds acquire the database-configured reactive policy.
- A rapid transaction burst produces fewer morph frames than transactions.
- The last morph contains the newest committed database value.
- Equivalent JVM views share one computation while retaining one transport
  mailbox per connection.
- Closing the final connection removes its reactive interest without carrying
  the drain thread's interrupt into the database connection.
