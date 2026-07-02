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

## Follow-up: migrate the pod feed to the pub socket — DONE (2026-07-02)

Shipped as planned (commit `a24b172f`). The pod feed is ONE persistent
pub-socket connection (`seon.store.internal.wire-node/connect-pub`, a
streaming length-framed Transit reader) feeding `handle-feed-event!`
directly; the poll pump, its 2s-retry re-subscribe dance, and the ~18
conn/sec req-socket churn are GONE from the pod. Details:

- **Gap recovery**: on every (re)connect the adapter calls the new
  req-socket `replay-tx` op (`seon.server.boot`) with its basis-t
  watermark; the reply carries the missed events DIRECTLY (no queue/handle),
  applied ahead of live frames buffered during the replay — DE-2 lossless
  wake unchanged, overlap deduped by the watermark. Reconnects are
  feed-generation-guarded so a drop and a failed connect can never race two
  loops.
- **db-name demux**: the pub stream is db-agnostic; the `replay-tx` reply
  carries the resolved db-name and the pod filters frames client-side.
- **Live-proven** (default cluster, 2026-07-02): foreign tx pushed → native
  listener fired exactly once, watermark advanced with zero polling;
  SIGSTOP'd pod + wire-server restart + foreign tx committed in the gap →
  on resume ONE reconnect, `replayed 4`, the gap tx's listener fired
  exactly once, watermark = server basis-t.
- **NOT deleted**: the server-side `subscribe-tx`/`next-tx-event`/
  `unsubscribe-tx` poll ops + bounded queues stay — the deletion condition
  ("once no client polls") is not met: the `seon.dev.replica-peer` Stage A/B
  regression harness (`clj -M:replica-peer-jvm`) still polls them.

## Follow-up: transact timeout ≠ server rollback — DONE (2026-07-02)

Fixed in the same commit. A transact whose rpc fails BEFORE a reply is read
(timeout / transport / closed) now has DEFINED semantics
(`seon.store.wire/transact-rpc-failure`):

- The error envelope names commit-or-not in the message and carries
  `:seon.store.wire/committed?` + `:seon.store.wire/basis-t` +
  `:seon.store.wire/write-id` — resolved by a LOCAL store query for the
  committed write-id tx-meta datom (reads are follow-the-store; no wire
  round-trip).
- The write-id leaves the echo-suppression set, so a landed-but-unacked tx
  still fires the conn's native listeners: via the feed (as foreign) if the
  event hasn't arrived yet, or synthesized from local history
  (`fire-own-tx-listeners!`) if the feed already own-skipped it.
- Live-proven with a black-hole UDS server (→ "NOT observed … Safe to
  retry", committed? false) and a reply-swallowing proxy to the real
  wire-server (→ "COMMITTED at basis-t N … Do NOT re-send", committed? true,
  listener fired once for the committed tx).

## Turn-6 empty recall: pump suspicion FALSIFIED

The flagged flake (deepseek-preflight-drives-2026-07-02.md §7 — a `/solve`
agent's recall returned `#{}` after successful `remember` calls) **cannot** be
this bug: `/solve` scratch agents run against a fresh local `:memory` conn
(`seon.web.serve` solve-deps / `seon.client/open-agent-conn!`) and never touch
the wire store or its tx feed. That read-visibility question (scratch-conn
attr-install timing vs `db/*conn*` root) still deserves its own scoped look —
it is a different mechanism.
