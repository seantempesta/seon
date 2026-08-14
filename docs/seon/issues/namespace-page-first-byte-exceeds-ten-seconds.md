---
type: issue
status: open
severity: friction
tags: [issue, web, performance, wave/namespace-page-performance]
---

# Bound namespace-page first-byte latency below the observer timeout

## Problem

Fresh agent and root page requests produced no byte within ten seconds. The
debug shell returned, but the ordinary namespace pages left a read-only
observer waiting without page evidence.

## Evidence

Against the ready Drive 1 web server at `http://127.0.0.1:54474`:

```text
GET /agent/drive-one-agent -> timeout after 10.008777 s, 0 bytes
GET /                      -> timeout after 10.009215 s, 0 bytes
GET /agent/drive-one-agent/debug -> 200, 2183 bytes, 1.160782 s
```

A subsequent body request eventually returned the namespace HTML, proving the
routes existed; the first-byte wait, not a 404 or connection refusal, consumed
the timeout.

## Owner

The synchronous initial-paint path in `seon.render.web` and the render walk it
awaits before emitting the response.

## Acceptance

Agent and root page handlers carry a declared bound and either emit the first
complete bounded page within it or return a typed diagnostic naming the
unfinished render. A live regression measures first-byte latency on a fresh
cluster with the shipped toolkit graph.
