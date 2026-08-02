---
type: reference
status: active
tags: [reference, web]
---

# Datastar web UI reference

Fresh Seon's web UI has one current implementation owner:
`src/seon/render/web.clj`. It uses the vendored Datastar Clojure SDK's http-kit
adapter, one SSE connection per browser tab, and element morphs. There is no
`seon.web.sse`, `render-handler`, `refresh-all!`, atom watch, polling fallback,
or direct-response rendering path.

## Update path

1. Database `listen!` observes a committed transaction and offers a
   payload-free interest wake.
2. One `core.async.flow` render proc pins one immutable database value and
   derives complete page snapshots for watched agents.
3. The proc suppresses snapshots equal to the previous complete snapshot and
   publishes changed complete snapshots through one `mult`.
4. Each tab owns a `(sliding-buffer 1)` tap and a virtual-thread writer. The
   tab compares the newest complete snapshot with the last one it delivered
   and sends one Datastar outer-element morph per changed block.

The sliding buffer may discard an intermediate snapshot because every value is
complete. A slow browser therefore converges on the newest page without
backpressuring rendering or losing durable state. Reconnect performs a fresh
paint from the current database value.

## Render unit

A block is one render function's identified output: its stable element id and
current bytes. `seon.render.web/block-fragment` wraps each HTML projection in
the element that Datastar morphs. Blocks may be present in the AI projection,
the HTML projection, or both; omission is derived from the render result, not
from a list.

The render proc publishes complete maps shaped as agent id to surface id to
HTML. Per-tab diffing turns that complete value into the changed morphs for the
browser. Do not introduce a second delta cache or store rendered HTML as
database facts.

## Routes

`src/seon/render/route.clj` owns one Reitit route table and the pure
`seon.render.route/path` reverse router. Canonical namespace pages are
`/ns/{namespace}`; `/` aliases the root namespace page. Agent, debug, feed,
data, and static-resource routes are named in that table. Add or consume routes
through that owner rather than concatenating paths.

## Datastar boundary

`seon.render.web/feed-response` calls the SDK's
`starfederation.datastar.clojure.adapter.http-kit/->sse-response`, registers
interest before painting, taps the pages `mult`, and closes and untaps on every
terminal path. `write-patches!` sends the changed stable elements through the
SDK. The exact event encoding belongs to the vendored SDK under
`reference-code/datastar-clojure/`; Seon code does not hand-build SSE frames.

## Verification map

- `src/seon/render/web.clj` — current render proc, snapshot fan-out, tab writer,
  and HTTP handlers.
- `src/seon/render/route.clj` — current routes and reverse routing.
- `src/seon/render/block.clj` and `src/seon/render/walk.clj` — block identity
  and the two-projection walk.
- `reference-code/datastar-clojure/libraries/sdk/` and
  `libraries/sdk-http-kit/` — exact SDK and adapter APIs.
- `deps.edn` — pinned Datastar local roots and Reitit version.

The dated Datastar and Hyperlith pages in this directory are dependency
research only. They are not Seon API guidance.
