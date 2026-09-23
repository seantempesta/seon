---
type: issue
status: open
severity: cleanup
created: 2026-09-21
tags: [issue, render, agent-platform]
---

# Cluster status renders unknown observations as raw error maps

Browser observation of `http://127.0.0.1:7994/` at 20:24:51 UTC, default
PID 19386, showed a complete `:seon.error` map inside the token/cost value
and another inside `store-bytes`. The first says an attempt has no reported
USD cost; the second says the footprint requires explicit operator measurement.
The typed unknown is legitimate. Its raw map in a compact status value makes
the page unreadable and should be rendered through the existing HTML owner.

B2 owns this presentation correction. Preserve the unavailable meaning and
the explanation/action; do not invent a zero price or scan the store on each
page render. Acceptance is observed browser output with a short readable
unknown value and accessible diagnostic detail. This is not a startup blocker
and was not changed during baseline preparation.
