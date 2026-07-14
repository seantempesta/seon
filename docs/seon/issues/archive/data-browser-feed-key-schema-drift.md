---
type: issue
status: resolved
severity: blocker
tags: [issue, web, schema, flow]
---

# Data browser feed key drift made every live view return 500

## Problem

`GET /data` returned its cheap shell, but the shell's `/data/feed` request
failed with a Malli instrumentation error in both default and ACME. The data
browser was therefore unusable after a full reset.

## Evidence

The live gzip request produced a five-slot subscription coordinate:

```clojure
[:seon.web.feed/data nil nil nil false]
```

`seon.web.datastar/feed-key` still accepted the retired four-slot
namespace/page/system coordinate. The data browser had evolved to namespace,
attribute, cursor, and system projections without evolving the shared key
schema or its first-paint regression.

A live CLJS REPL probe of `data-params` and `data-feed-definition` showed the
fully populated coordinate as:

```clojure
[:seon.web.feed/data ":seon.agent" ":seon.agent/id"
 "[1 \"root\" 3]" true]
```

## Owner

The normalized feed coordinate and instrumentation contract in
`src/seon/web/datastar.cljs`, with the data projection in
`src/seon/web/debug.cljs` and its shared feed regression.

## Acceptance

- The schema accepts exactly the current namespace/attribute/cursor/system
  coordinate and rejects the retired page-shaped coordinate.
- Tests derive the key from real URL parameters instead of restating only a
  synthetic fixture.
- Focused and complete CLJS gates pass.
- Default and ACME `/data/feed` return gzip SSE with an immediate
  `datastar-patch-elements` frame after hot reload and after restart.

## Resolution

Resolved by `78c544ac`. The URL-derived regression joined the affected CLJS
checkpoint at 681 tests and 3,477 assertions with zero failures. A public
default restart rebuilt the client, writer, and bootstrap; the live
`/data/feed` then returned 200 with `Content-Encoding: gzip` and an immediate
`datastar-patch-elements` frame. The parallel ACME lane owns subsequent ACME
evidence; its pre-handoff hot reload had already returned the same successful
frame.
