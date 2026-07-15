---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Babashka could not load the typed UDS client

## Problem

The Babashka operator could not reuse the existing synchronous
`seon.db.transport.uds/connect!` and `call!` boundary for native branch
lifecycle requests. Creating a second transport would have split framing and
protocol ownership, while leaving the failure blocked the next database-
lifecycle slice.

## Evidence

The exact pre-edit probe failed during namespace analysis:

```clojure
(require '[seon.db.transport.uds])
;; Unable to resolve classname:
;; java.nio.channels.AsynchronousCloseException
```

Babashka `1.12.212` could load the class through `Class/forName` and already
supported the Transit `1.0.333`, Unix `SocketChannel`, and stream classes used
by the synchronous boundary. The incompatibility was the SCI catch-class
symbol, not the codec, four-byte frame, socket API, or typed protocol.

## Owner

`seon.db.transport.uds` is the one JVM/Babashka byte, frame, and Unix-socket
owner. The operator consumes its existing synchronous call boundary; it does
not own another codec or transport.

## Acceptance

- Babashka requires `seon.db.transport.uds` without adding Datahike, JSON, a
  shell call, or another socket.
- A bounded local UDS fixture sends and receives the real closed branch-
  lifecycle maps with keywords and complete UUID coordinates preserved.
- JVM request admission, draining, publisher behavior, and writer lifecycle
  integration remain green.
- Every touched public function retains a named Malli contract.

## Resolution

Resolved by `ce342572`. The transport resolves the JDK asynchronous-close class
once through the local source-grounded dynamic compatibility pattern and
classifies it inside the existing resource-boundary catches. The complete
transport namespace passes under Babashka at 9 tests/28 assertions. The JVM
transport plus writer-integration gate passes 16 tests/93 assertions, including
the same exact typed lifecycle call.
