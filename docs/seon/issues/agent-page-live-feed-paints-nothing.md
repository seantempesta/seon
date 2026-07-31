---
type: issue
status: open
severity: blocker
tags: [issue, web, render, flow]
---

# Make the agent page's live feed paint the page a GET already renders

## Problem

`GET /feed/{id}` opens, returns SSE headers, and then sends ZERO bytes —
no initial paint and no morph, ever. The agent page is therefore static: a
fact committed while the tab is open never appears until the human reloads.

The cause is not the writer. `seon.render.web/page-of` returns `{}` for every
agent, because `seon.render.block/membership` returns `[]` for every agent:
`blocks` finds no installed `:seon.cluster.agent/blocks` children (agent
creation deliberately stores none) and `derived`
(`src/seon/render/block.clj:236-247`) is `[]` by construction pre-N5. The
whole snapshot/mult/tap/diff machinery is wired to a producer that is
structurally empty, while the GET page renders through the walk path instead.

The `/feed/{id}?debug=true` path works — it paints through `debug-page-of`,
which does not go through `membership`.

## Evidence

Observed 2026-07-31 on cluster `visual-qa` at `ef8cc6f77`:

```text
$ timeout 20 curl -sN http://127.0.0.1:7758/feed/scout > feed.sse
HTTP/1.1 200 OK   Content-Type: text/event-stream
bytes: 0                      # after a committed message during the window
$ timeout 18 curl -sN 'http://127.0.0.1:7758/feed/scout?debug=true&path=%5B%5D&offset=0'
bytes: 71558                  # 5 datastar-patch-elements events
```

REPL at the same basis:

```clojure
(count (seon.render.block/surfaces @conn {:seon.cluster.agent/id "scout"
                                          :seon.render/kind :seon.render/html
                                          :seon.sci.admit/caps caps
                                          :seon.cluster.run/live-processes #{}}))
;; => 0
(count (#'seon.render.block/membership @conn "scout")) ;; => 0
```

A plain re-GET of `/agent/scout` after the same commit DOES show the new
message, so the fact committed and only the live path is dead.

Secondary: the feed's writer thread wraps everything in
`(catch Throwable _ nil)` (`src/seon/render/web.clj:756`), so a genuine paint
failure would also be silent. Nothing appeared in `bin/seon logs visual-qa`.

## Owner

`seon.render.web` / `seon.render.block` — the one paint producer.

## Acceptance

With a tab open on `/agent/{id}`, committing one fact delivers at least one
`datastar-patch-elements` event carrying the changed block, and the initial
connection paints the same bytes the GET renders. A paint that throws is
recorded as a fault, not swallowed.
