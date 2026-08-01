---
type: issue
status: open
severity: friction
tags: [issue, sci, render]
---

# The elision marker tells an agent nothing about what it lost

Found by the bootstrap-vector design lane (2026-08-01, live probes on a
scratch cluster): a capped collection renders into agent context as a
bare `:seon.sci.admit/elided` marker with no retained/total count and
no receipt identity — a model cannot tell whether it lost three
elements or 300,000, and has no handle to page into the remainder.

This contradicts the sealed print contract's own standard (ruling #26:
cut REASONS are part of the grammar) and the honesty bar already met
elsewhere (the MCP envelope carries retained/total; the routed /data
window pages with an explicit elision link). The one general printer
should give the agent the same evidence: what was cut, how much
remained of how many, and the identity through which the full value is
reachable (the receipt/blob digest where one exists).

Acceptance: an agent-facing elision names retained/total (or total
unknown, stated) and, where the full value survives as a blob or
receipt, the identity to reach it; the bootstrap vector's guardrail
beat can then SHOW an elision that teaches rather than confuses; a
regression covers the capped-collection face in both sinks.
