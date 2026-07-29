---
type: issue
status: open
severity: blocker
tags: [issue, web, runtime, flow]
---

# Bound http-kit streaming writes for slow SSE consumers

## Problem

The JVM feed's sliding buffer of one bounds only the queue before
`datastar/patch-elements!`. Once a frame reaches http-kit, `send!` reports
acceptance rather than socket completion and http-kit retains every partial or
subsequent write in an unbounded `LinkedList`. A slow or stopped browser can
therefore accumulate complete morph frames and heap even though
`seon.web.feed/enqueue-latest!` retains only the newest pre-send value.

This contradicts the architecture claim that one connection owns only one
newest complete value and cannot form an unbounded queue.

## Evidence

`src/seon/web/feed.clj:22-25` clears and offers into the connection's
`ArrayBlockingQueue`, and `:112-126` drains that queue into
`datastar/patch-elements!`. The Datastar http-kit adapter ultimately calls
http-kit's streaming `send!`.

In vendored http-kit 2.9.0-beta2, `AsyncChannel.send` returns `true` after
calling the write path (`reference-code/http-kit/src/java/org/httpkit/server/AsyncChannel.java:251-293`);
the return value is not a completion. Every socket attachment owns
`LinkedList<ByteBuffer> toWrites`
(`reference-code/http-kit/src/java/org/httpkit/server/ServerAtta.java:6-8`).
When a channel accepts only a partial write, `HttpServer.tryWrite` appends the
remaining buffers at `:368-386`; while any write is pending, every later send
is appended with `Collections.addAll` at `:395-399`. Neither path applies a
count or byte bound.

## Owner

The JVM web-render transport boundary: `seon.web.feed` together with the
selected http-kit/Datastar adapter contract. The reactive registration remains
the owner of computation coalescing and equality suppression.

## Acceptance

- A paused-read SSE client under repeated complete outer morphs retains a
  measured constant bound in both Seon's mailbox and http-kit's pending socket
  bytes.
- The send boundary publishes completion or explicit writable/backpressure
  state; a boolean meaning only "accepted into an internal queue" is not used
  as completion.
- When the bound is reached, the connection keeps at most the newest complete
  outer morph or closes loudly; it never converts the stream to incremental
  patch semantics.
- A fast consumer still receives the newest complete state, and reconnect
  still performs an unconditional repaint.
- The proof exercises the selected http-kit 2.9.0-beta2 implementation through
  the Datastar Clojure adapter, not a mock writer.

## Triage 2026-07-27

- **OPEN-DEFERRED.** The cited feed now exists only in the quarry at
  `src-old/seon/web/feed.clj:22-29,112-153`; the maintained fresh tree has no
  `src/seon/web/feed.clj`, and N4 must measure the still-unbounded dependency
  queue at `reference-code/http-kit/src/java/org/httpkit/server/ServerAtta.java:7`
  before adopting or forking that transport.

## Triage 2026-07-29

**PRESSING — live web transport correctness.** Fresh
`seon.render.web` now calls Datastar over http-kit, while the vendored
`ServerAtta.toWrites` remains an unbounded `LinkedList`; the old “quarry only”
triage is stale, but the underlying claim is current.

## Resolved 2026-07-29

Fork commit 238a85c (additive per-channel pending-byte state + atomic
drain-or-close completion; send!/tryWrite semantics preserved; JUnit
covered) + parent 875353668 (deps repoint with retire-on-upstream-merge
intent, SSE writer parks on drain-or-close, close-on-rejected-write
kept). Boundedness falsifiers green via the supported accessor;
renderer/web 31/119/0. Deep-dive evidence: httpkit-write-path-2026-07-29.md;
upstream PR against http-kit #180/#474 pending owner go.
