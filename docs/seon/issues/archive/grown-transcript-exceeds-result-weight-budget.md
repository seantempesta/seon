---
type: issue
status: resolved
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

## Source-grounded correction

The failure is the eval member query's `:datahike.resource/max-result-weight`,
not the outer `execute-many` result allowance. Datahike's maintained
`scalar-weight` charges a string by its complete character count plus one.
Both a newly computed result and a query-cache hit are certified against the
caller's limit, so reuse cannot admit the current all-evals relation after it
has grown beyond 524,288 units.

Strengthen `acquire-transcript` in place:

- keep stage one and one immutable database value;
- query lightweight eval IDs and turn refs first;
- acquire complete AI eval facts in bounded, ordered pages through the existing
  query and `db/execute-many` operations;
- acquire normal HTML activity rows with a minimal selector that omits output,
  result, error, and error-data payloads; and
- page messages at the same owner when the generated maximum-content fixture
  proves one message relation can exceed its bound.

Datahike already includes query form, non-database arguments, offset, limit,
and database value in query-cache identity, so equivalent readers can reuse
identical pages. No new protocol, cache, stored activity projection, raw index
assembler, or blanket allowance increase is required. Measure the existing
paged operations before considering protocol streaming.

The generated fixture must also expose one independently unbounded write
projection: `record-eval!` caps output, error, and result EDN but does not cap
`:seon.eval/error-data`. Bound that projection at the existing write owner and
verify where complete forensic evidence is retained. Do not truncate exact
`:seon.eval/source`; source remains program evidence. The current claim that
all complete output/result evidence is already blob-backed is not established
by `record-eval!` and must be proved or corrected rather than assumed.

## Resolution

The existing owners are strengthened without another cache, feed, or
transcript representation:

- `record-eval!` applies the configured database projection cap to
  `:seon.eval/error-data`, while exact `:seon.eval/source` remains unchanged;
- AI eval pulls use four-row pages, calibrated against Datahike's measured pull
  structure rather than raw string arithmetic;
- the redundant ordered full-history current-namespace query is deleted;
  current namespace derives from the already acquired successful eval rows,
  falling back to the existing pre-window query only after window rotation; and
- HTML retains its minimal selector and omits AI-only payloads.

A real database fixture allocated 50 retained turns and 400 evals through
`seon.db.id/allocate!`, with source, output, and result projections each at
16,384 characters. Before the correction, its real Datastar feed returned the
reported result-weight error. Instrumented acquisition after the correction
completed through 57 bounded database calls with no failed member. After a
clean supervised restart, the same grown database produced a 75,408-byte
complete Datastar patch with no render or result-weight error. Focused
transcript and eval-receipt tests pass 19 tests/77 assertions.

The exact model reply is retained by `:seon.agent.turn/reply-blob`, the exact
executed form remains `:seon.eval/source`, and structured error datoms are
bounded projections under the architecture's storage rule. The earlier
suggestion that complete arbitrary eval results were already blob-backed was
not true and is not relied upon by this correction.
