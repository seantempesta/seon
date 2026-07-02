---
type: issue
status: completed
tags: [issue, database, agent]
---

# tx-feed pump timeouts — spurious wall-clock rpc timeouts under pod event-loop stalls

## Symptom

`ERROR [seon.store.wire] tx-feed pump failed (wire rpc timeout) — re-subscribing
in 2s`, self-healing ~2s later. Fired on every fresh-boot core-seed window and
recurred during `/solve` drives, on BOTH the acme pod (7980) and the default
pod (7890) — systemic, not cluster-specific.

## Root cause (fixed)

`seon.store.internal.wire-node/rpc` armed a **wall-clock** `setTimeout` (5s
default) racing the reply's socket `data` event. The pod is single-threaded;
its heavy synchronous windows (self-host seed eval, instrumenting ~550 fns,
agent-turn ctx renders/compiles) block the event loop for multiple seconds.
Node runs the **timers phase before the poll phase**, so when the loop
unblocked, the expired timer rejected and `.destroy`ed the socket before the
reply — which had been sitting fully-buffered in the kernel since ~55ms after
the poll — could ever be read.

Live proof (2026-07-02, acme cluster):

- Server is fast: ping RTT 2ms; `next-tx-event` RTT 50-56ms over 20 polls
  (the server's bounded wait is 50ms — `seon.server.boot` `"next-tx-event"`).
- Isolated Node probe with the identical rpc code: issue a poll, synchronously
  block the loop 1500ms with a 300ms timeout → `wire rpc timeout
  (wall=1500ms)` even though the reply was buffered at ~55ms. Race reproduced.
- Every logged failure timestamp coincided with a pod heavy-sync window
  (world-block install, instrumentation, `/solve` turns), never with
  wire-server distress.

## Fix

`rpc` now measures its timeout in **event-loop-alive time**: a repeating
250ms `setInterval` accumulates the budget; Node coalesces all interval fires
missed during a synchronous stall into ONE, so a stall extends the deadline by
its own duration instead of expiring it. A genuinely absent reply still
rejects after ~`timeout-ms` of live loop (probe: 1000ms budget → rejects at
1004ms wall against a never-replying server). The timeout error message now
carries both alive and wall elapsed (`wire rpc timeout (alive Xms, wall
Yms)`) — `wall ≫ alive` in a future log is the stall signature.

One mechanism, all callers: every wire op (`transact`, `q`, `pull`, the pump's
`next-tx-event`, ping) routes through this one `rpc`, so the same fix covers
the 30s transact timeout racing long stalls too.

## No datom loss during a pump gap (verified by reading the source)

Even pre-fix the gap was visibility-DELAY only, not loss: reads are
follow-the-store derefs (fresh branch-root read per `@conn`), and the
re-subscribe passes the `last-applied-t` watermark as `since-t` so the
wire-server replays every missed tx in commit order
(`seon.server.wire/replay-tx-events`), idempotent on the watermark. What a gap
delayed was listener WAKES (triggers, SSE), by up to the gap length.

## Follow-up: migrate the pod feed to the pub socket (push, not poll)

The timeout question only exists because the feed is POLL-based: the pump
opens a fresh UDS connection every ~55ms (~18 connections/sec per pod,
forever), and each connection spawns a JVM handler thread
(`seon.server.wire/start-req-server!`'s thread-per-connection accept loop).
The wire-server already has the push channel:
`seon.server.broadcast/start-pub-server!` maintains a pub socket that writes
every broadcast event to each connected subscriber — the pod just doesn't use
it (it polls the req socket via `next-tx-event`).

Plan:

- **Pod side** (`seon.store.wire` + `seon.store.internal.wire-node`): replace
  the pump's poll loop with ONE persistent pub-socket connection; a streaming
  frame reader feeds `handle-feed-event!` directly. Reconnect-on-drop keeps
  the existing `since-t` replay for gap recovery — the DE-2 lossless-wake
  contract is unchanged (the watermark idempotency already dedupes the
  replay↔live overlap).
- **Server side**: pub events go to ALL socket subscribers today (no
  per-subscriber db-name routing on the pub socket; `broadcast!` tags each
  event with `:seon.store.wire/db-name`), so the pod filters by db-name
  client-side, or the pub server grows a subscribe handshake. The `since-t`
  replay stays on the req socket (a "replay request" op) or is pushed down
  the fresh pub connection ahead of live frames.
- **Deletion targets**: the pump's poll loop + 2s-retry re-subscribe dance,
  the per-poll rpc timeout semantics for the feed, the ~18 conn/sec churn and
  its per-conn JVM handler threads, and (once no client polls) the
  `next-tx-event` op + per-handle bounded queues + `max-queued-events`
  drop-oldest backstop in `seon.server.boot`.
- **Scope**: a two-sided protocol touch (pod wire layer + wire-server
  boot/broadcast) plus reconnect/replay integration and tests on both sides —
  roughly a focused multi-day unit, not a patch. Do it as its own task on the
  post-merge branch.

## Follow-up: transact timeout ≠ server rollback (agent-facing ambiguity)

Pre-existing, surfaced during this diagnosis: a client-side `transact` rpc
timeout (30s budget) does NOT undo the server's commit — the write can land
while the agent sees an error. The write-id echo-suppression set prevents
double-apply, but the agent-facing ambiguity ("my write failed" when it
actually committed) deserves its own scoped look — e.g. a timed-out transact
could re-check the store for its write-id before reporting failure. Separate
mechanism from the pump; entry point: `seon.store.wire/SeonWireWriter`
`-dispatch!` calling `seon.store.internal.wire-node/rpc`.

## Turn-6 empty recall: pump suspicion FALSIFIED

The flagged flake (deepseek-preflight-drives-2026-07-02.md §7 — a `/solve`
agent's recall returned `#{}` after successful `remember` calls) **cannot** be
this bug: `/solve` scratch agents run against a fresh local `:memory` conn
(`seon.web.serve` solve-deps / `seon.client/open-agent-conn!`) and never touch
the wire store or its tx feed. That read-visibility question (scratch-conn
attr-install timing vs `db/*conn*` root) still deserves its own scoped look —
it is a different mechanism.
