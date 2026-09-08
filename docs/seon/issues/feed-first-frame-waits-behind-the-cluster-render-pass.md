---
type: defect
status: open
severity: blocker
tags: [web, render, feed, class/availability, class/bounded-execution]
---

# The feed's first frame waits behind the cluster render pass, unbounded and silent

Observed 2026-09-08 15:40 on `default` under seven lanes' adoptions: `GET
/ns/my.agents.juniper/debug` answered 200 in 1.7 s, while the page's
`/feed/juniper?debug=true` returned headers in 4 ms and then ZERO bytes for
60 s. The left column stayed at "Loading the entity's attributes…" and the
browser reconnected in a loop (500 feed requests in the network log,
`retryMaxCount: Infinity`). On the quiet machine at 15:45 the same feed
delivered 257 KB immediately. So the page has two paths with different
costs: the GET renders on the request thread from the current database
value; the feed's first frame does not.

## Mechanism (`src/seon/render/web.clj`, `feed` / `fresh-fact-package`)

1. On open, the tab reuses the latest proc-owned package ONLY when its
   `:seon.render.package/basis-transaction` equals the connection's current
   `basis-t` (`fresh-fact-package`). Under write load (adoptions transact
   continuously) the basis moves every few seconds, so the cached package
   is never fresh and every open falls through.
2. It then offers `{::join true}` on the render proc's sliding-1 interest
   port and blocks on `(async/<!! tap)` — with NO bound. The render proc is
   ONE `:io` proc per cluster; each pass repaints EVERY registered page
   serially (a debug page is ~5 s quiet, far more at 200% CPU). A tab's
   first frame arrives only after a complete pass that started after its
   opening basis; a pass in flight is rejected and a second join requested.
3. When the wait exceeds the client's patience the socket closes with
   nothing written — no typed error reaches the client — and the browser
   reconnects at once, registering again. Silence read as "loading": the
   absence-of-signal class.

## Fix

- Paint the first frame on the connection's own virtual thread from the
  current database value, the way the GET does (the `datastar-web-ui`
  skill already describes this as the design: "paint the current keyframe
  from the current database value"), then tap the mult for deltas. The
  render proc owns deltas, never a new tab's first paint.
- Bound the tap wait with the declared backstop and WRITE the typed
  refusal to the client as a Datastar signal before closing; never close
  silently.
- Measure: first SSE event < 2 s with the basis moving (a write every
  second) and three debug tabs open.

## Measured again 2026-09-08 16:20 on the reforked `default` — it is the whole page, not only the feed

| request | time |
|---|---|
| `GET /ns/my.agents.juniper/debug` (warm) | 0.20 s |
| `GET /ns/my.agents.juniper` | 8.8 s |
| `GET /agent/juniper` | 9.9 s |

Virtual-thread-aware samples (`jcmd Thread.dump_to_file -format=json`,
four samples during the plain GET) show the request thread in
`page-response → current-page → page-refresh → seon.await/await!`, and the
render proc in `render-step → context-pass → walk/history`, while two agent
turns sit in `loop/turn → call-turn → prompt → render/acquire-context!`
waiting on the same proc's `::context` channel (`src/seon/render.clj:1389`).

So ONE serial `:io` proc per cluster derives, in turn: every agent's
context for its turn, every plain page GET (`page-refresh` is the proc's
own state function, `web.clj:2003`), and every feed's first frame. The
debug page is fast only because it renders on the request thread. A page
waits behind whichever agent is turning; an agent waits behind whichever
page is loading. That is the serialization point the owner asked about
("why does it take 2.4 seconds? We are doing something wrong").

## Fix, widened

Pages and agent context are pure functions of a database value and the
agent's SCI context: derive them on the caller's thread (request thread,
feed thread, turn proc). The render proc keeps exactly one job — publishing
deltas to open tabs after a wake — and never sits between a caller and its
own result. Measure: plain page < 1 s warm while an agent turn is in flight.
