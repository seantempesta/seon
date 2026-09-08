---
type: defect
status: open
severity: friction
tags: [adoption, web, dev-cluster, class/availability]
---

# Development adoption drops the web server while it reloads

Observed 2026-09-08 14:26 on `default` by the page monitor: during
`init --dev default --changed src/seon/render/value.clj …` (a hook
publication) the debug page went from 200 to connection refused (HTTP 000)
and came back after the adoption. Every hook publication therefore takes
the owner's window down for the length of a reload, and with several lanes
editing that is most of the time.

## Why

Adoption reloads the changed namespaces in `:seon.ns/requires` order and
re-acquires SCI; somewhere on that path the http-kit server or its render
proc is stopped and restarted instead of the Var-level swap the live-update
law promises ("re-evaluating a `defn` against the running system changes
proc behaviour immediately"). A reload should never close the listening
socket.

## To do

Find the stop on the adoption path (`seon.cluster/refresh-source!` →
`development-source-refresh!` → the render/web graph) and make adoption
swap Vars under a live server; regression: a page request issued DURING an
adoption is served (200), never refused. Owner: the lane holding the
refresh path (`hook-coalesce`, 2026-09-08).
