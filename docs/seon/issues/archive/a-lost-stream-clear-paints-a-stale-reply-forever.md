---
type: issue
status: resolved
severity: blocker
tags: [issue, render, stream, flow, transport]
---

# A lost stream clear paints a stale reply forever

## Problem

Every agent in a cluster streams its model reply onto ONE shared
`(sliding-buffer 1)` conn. The end-of-turn CLEAR is an ordinary entry on that
same conn, so any other agent's partial silently replaces it. The render proc
then never drops that agent's snapshot, and the agent's page paints its last
partial reply — with the blinking cursor — indefinitely, beside the settled
reply the facts already carry.

This is the transport law applied where its precondition does not hold. Loss on
a channel is free only when the lost value is re-derivable from facts or
superseded by a newer complete value. A clear is neither: nothing re-derives it,
and the next value that supersedes it belongs to a different agent.

## Evidence

`src/seon/cluster/loop.cljc:498-512` — one `stream-channel` per cluster, taken
from the handle; `sink` and `clear!` both `offer!` onto it, keyed only by
`:seon.cluster.agent/id`.

`src/seon/cluster.clj:736` — that channel is created once per cluster as
`(async/chan (async/sliding-buffer 1))`, shared by every armed agent graph.

`src/seon/render/web.clj:391-395` — the render proc removes an agent's snapshot
ONLY on an entry that carries no `:seon.ai/partial`:

```clojure
(if-let [snapshot (:seon.ai/partial message)]
  (assoc-in state [::streams agent-id] snapshot)
  (update state ::streams dissoc agent-id))
```

`src/seon/render/root.clj:179-192` — `text-html` renders from
`:seon.ai/partial` alone, with no fact gate: "When nothing streams the unit
carries no `:seon.ai/partial`" is exactly the assumption the lost clear breaks.

REPL falsification of the drop:

```clojure
(let [ch (a/chan (a/sliding-buffer 1))]
  (a/offer! ch {:seon.cluster.agent/id "a"})                                  ; A's clear
  (a/offer! ch {:seon.cluster.agent/id "b" :seon.ai/partial {:seon.ai/text "hi"}})
  (a/poll! ch))
;; => {:seon.cluster.agent/id "b", :seon.ai/partial #:seon.ai{:text "hi"}}
;; A's clear is gone; nothing will ever remove A's ::streams entry.
```

No test covers it. `test/seon/render/web_test.clj:494-546` proves reconnect is
repaint from facts and that no partial ROW can exist, which is a different
class: it never exercises a retained in-process `::streams` entry.

## Owner

`seon.render.web/render-step` together with `seon.cluster.loop`'s `:call` arm.

## Acceptance

- A clear cannot be lost to another agent's traffic. The preferred shape is to
  DELETE the clear message and derive staleness in `render-pass` from the
  database value — an agent whose run is no longer open contributes no
  `:seon.ai/partial` — so presence of a live streaming run is the state and the
  render proc holds no snapshot the facts do not justify.
- A regression drives two agents streaming concurrently, ends one, and asserts
  the ended agent's page carries no partial text while the other still streams.
- Whatever survives, no channel carries a value whose loss is neither
  re-derivable nor superseded; state that plainly in the surviving docstring.

Resolved by `fb1ce96d8`: clear entries were deleted, terminal facts now
supersede cached partials, and the lost-clear ordering has a recurring
regression.
