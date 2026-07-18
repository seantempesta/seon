---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, web, cljs]
---

# Keep grown transcripts renderable within database result bounds

## Problem

An ordinary agent page can fail its transcript render with
`datahike result-weight budget exceeded`. The transcript's second grouped
database read pulls complete stored eval source, output, result, error, and
message strings for the retained turn window before the existing render-time
token clipping runs. The fixed 790,528 structural-weight allowance can
therefore reject valid retained history even though the displayed transcript
would be bounded.

## Evidence

The failure was observed in the web UI on the grown default database, which
contained more than one hundred resumed agents. Root turns were rendering
roughly 31--33K context tokens at the same time. The user-visible error was:

```text
transcript render failed: {:seon.db.protocol/success? false,
 :seon.db.protocol/error-kind :seon.db.protocol.error/database,
 :seon.db.protocol/error "datahike result-weight budget exceeded"}
```

`seon.agent.ctx.transcript/acquire-transcript` gives the eval member 524,288,
the message member 262,144, and the grouped response 790,528 units. Stored eval
source, output, and result strings may each be much larger than their final
transcript display. The current focused transcript tests exercise query shape
and immutable database-value reuse but have no grown-content fixture.

An authorized clean reset removed the historical population. A fresh ordinary
agent then completed nine turns and fourteen evals; its agent page and
Datastar feed rendered the transcript successfully with no result-weight
error. This proves current initialization and ordinary small history, but it
does not close growth behavior.

## Owner

`seon.agent.ctx.transcript/acquire-transcript` is the one transcript database
read. `seon.eval/record-eval!` owns the stored bounded eval projections. Any
fix must strengthen those owners without adding a second transcript, feed, or
cache.

## Acceptance

- A generated database fixture reaches the configured retained turn window
  with maximum ordinary eval/message projections and reproduces the old
  failure before the fix.
- The same immutable database value produces the complete intended bounded
  transcript without exceeding the database protocol's encoded-frame limit.
- Full source/error evidence remains available through its existing code or
  blob owner; no hidden data loss or stored render is introduced.
- Small transcripts retain the current two-stage grouped-read behavior and
  query-cache reuse.
- A real grown agent page and Datastar feed render without an error card.
