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
