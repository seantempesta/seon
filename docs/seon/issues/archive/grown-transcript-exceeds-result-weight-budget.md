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

## Reopened evidence (2026-07-21)

The exact-head Stage 1.5 restart at `c977e774` opened the root feed and again
rendered `transcript render failed: ... datahike result-weight budget exceeded`.
The same server-side connection successfully delivered an 18,634-byte
Datastar patch, so transport and stale hot-reload caching are not the cause.
The current default root history has outgrown at least one maintained paged or
grouped member bound. Re-run instrumented acquisition against this immutable
database value and identify the exact failing member before changing any cap.

The read-only diagnosis at database value `t=536875950`, commit ID
`6a5f097b-70aa-54d3-94a5-3d1d8856222e`, identifies the exact member as the
stage-one `(turns-query 50)`. Root had 330 turns. The production 65,536-byte
result-weight limit failed; a diagnostic 131,072-byte limit succeeded with
work 868, result count 862, and result weight 72,328. Limits 1, 10, and 50 all
reported those same resource totals: Datahike orders the complete relation
before applying the output limit.

Clause isolation found the history-wide payload. The query weighed 4,413 with
only turn time, 10,669 after run and scheduled fields, and 71,509 after adding
`:seon.agent.turn/llm-usage`. The database contained 330 usage rows, 323
nonempty, totaling 24,057 characters. The four-eval paging begins only after
this query, so it cannot bound this stage. A diagnostic high-budget read warmed
the exact-query cache without mutating the database; warm success therefore
cannot count as proof.

The correction must replace the history-wide ordered relation with bounded
index work and then acquire payload only for the retained turn IDs. Raising the
weight limit or asserting only returned rows/patch bytes does not close this
issue. A cold real-writer regression must keep index visits, authority calls,
work, result count, and result weight within fixed ceilings when old history
grows from 50 to a large population while returning byte-identical newest
turns in honest order.

## Bounded acquisition correction (2026-07-21)

The stage-one full-history turn relation and its separate full-history count
are removed. Transcript acquisition now reverse-pages the indexed
`:seon.agent.run/agent` refs through at most four 16-run pages, reverse-reads at
most the configured turn-window entries for each retained run, selects the
newest distinct turn entity IDs, and pulls payload only for that fixed set. A
truncated run page, turn page, or aggregate candidate set produces an explicit
older-history omission marker in both the AI and HTML twins.

The focused work-bound regression supplies 50 and 1,000,000 historical turns
to the same authority boundary. Both cases return the newest 50 turns in the
same order with exactly three authority calls and 52 simulated index visits.
The adversarial empty-run case stops after four run pages: eight authority
calls and 68 index members total, with no payload pull and an honest omission
marker. `seon.agent.ctx.transcript-test` passes 15 tests and 47 assertions; its
full log is `tmp/test-cljs-20260721-022326-93618.log`.

Closure still requires the cold live/default acquisition and real grown page
proof. The shared watcher was rebuild-pending and the pod drained during this
unit because of an unrelated parse failure in another owned source path, so
that evidence is intentionally not claimed here.

## Independent-review correction

Review rejected the first bounded walk because reverse run and turn entity IDs
did not preserve the established `:seon.agent.turn/at` ordering, an incomplete
page fact could be lost across recursion, and the final bulk pull still carried
an arbitrarily large `:seon.agent.turn/llm-usage` scalar.

The corrected acquisition reverse-pages the global
`:seon.agent.turn/at` AVET range through at most four 64-datom pages. Each page
uses a bounded minimal pull to test the existing turn → run → agent connection.
Finding the configured number of matches certifies the global newest window;
exhausting the fixed scan first returns only the certified prefix and marks the
older/incomplete history honestly. The regression intentionally makes entity
IDs disagree with timestamps across 50 distinct runs and proves the result is
ordered by timestamp, with the same four authority calls and 65 index visits
for histories labeled 50 and 1,000,000. Four full pages containing no matching
agent turn stop at eight authority calls and 260 index visits with omission
preserved.

Usage is absent from both bulk pull patterns. Each retained turn receives one
fixed, 4,096-weight non-critical usage pull member; a failed member omits only
that telemetry and sets an AI/HTML omission fact. Future writes project usage
to valid EDN containing only the finite nonnegative numeric OpenAI-compatible
or Anthropic fields consumed by `seon.agent.ctx.usage`. A lazy 100,000,000-item
unknown value proves the writer projection never walks arbitrary provider
payloads. The corrected transcript and retry gates pass 25 tests and 92
assertions; the retained report is
`tmp/test-cljs-20260721-024026-12848.report.edn`.

## Cold real-writer falsifier

The frozen `b3fbebb1` checkpoint passed 1,452 CLJS tests / 7,014 assertions,
281 writer tests / 2,143 assertions, and restarted the default cluster ready.
The subsequent direct call to `acquire-recent-turns` against default database
value `t=536875957`, commit ID
`6a5f15b2-c20a-5e3a-854e-1b64c949912a`, returned zero turns with both omission
facts false even though the same database has 330 root turns. The raw reverse
AVET index-page request with prefix `[:seon.agent.turn/at]` returned an empty,
complete page.

This disproves the mocked AVET proof: `:seon.agent.turn/at` is registered as an
ordinary `:inst` and is not currently present in AVET. The isolated retained
branch adversary was aborted before any seed transaction and its branch was
closed; it supplies no evidence. Closure now additionally requires a grounded
indexed-attribute migration (or another exact timestamp-ordered fixed-work
mechanism), a real empty-before/nonempty-after AVET probe on an existing
database, and then the already specified divergent-ID/cursor/hostile-usage
proof. Until that succeeds, the source correction is not live-accepted.

## Additive timestamp-index correction

Pinned Datahike commits `58764d90` and `c1c4c293` add the required monotonic
schema evolution. A nil→true `:db/index` assertion atomically copies current
AEVT facts into AVET and retained temporal AEVT facts—including retractions—
into temporal AVET before publishing the database value. Reducer-boundary
validation rejects entity-map changes plus direct add, retract, CAS,
`retractAttribute`, and `retractEntity` removal routes without changing the
basis, schema, current index, or history index. Both schema/data orderings in
one transaction, as-of/since, implicit unique indexes, cardinality-many, and
file reopen are covered. The dependency gate passes 45 tests / 327 assertions
across the spec, persistent-set, and hitchhiker-tree profiles.

Seon commits `88d35f77` and `7e752954` mark
`:seon.agent.turn/at` indexed and permit only the exact same-shape missing-index
declaration through writer admission; omitted index metadata never requests
removal of an implicit or explicit installed index. The complete writer gate
passes 283 tests / 2,154 assertions. Independent review accepts the integrated
source mechanism. The one-time migration is synchronous and proportional to
the attribute's current plus retained history under the writer lock; this is
the atomic-publication tradeoff and must not be generalized into a bounded
writer-latency claim.

## Resolution

Closed by transcript commits `2c74f7d2` and `88a379f8`, Datahike commits
`58764d90` and `c1c4c293`, Seon admission commits `88d35f77` and `7e752954`,
and parent gitlinks `e042cb0e` and `cc2d812b`.

At frozen HEAD `3d5fa23f`, the complete CLJS gate passes 1,452 tests / 7,014
assertions and the complete writer gate passes 283 / 2,154. A clean default
restart migrated database value `t=536875959`: installed
`:seon.agent.turn/at` has `:db/index true`, reverse AVET returns 50 rows with a
real incomplete cursor, and direct root acquisition returns 50 timestamp-ordered
turns with honest older-history omission. The identity-encoded root SSE
returned a complete `app-view` with that marker and no render error; browser
selection showed the same live marker with an empty console.

The final isolated branch put 68 turns across 17 runs in 2099, except the
first-created low-entity-ID turn in 2100, and stored a 300,000-character usage
value on that same unique newest turn. Its identity SSE returned 27,862 bytes,
one `app-view`, the combined honest history-and-usage omission marker twice,
and zero render, transcript, or `:seon/error` matches. Because only the future
low-ID turn carried hostile usage, the marker also proves timestamp ordering
selected it before the per-turn usage member failed non-critically. The branch
closed cleanly; default remained ready and unchanged. The separate branch MCP
pending defect is preserved in
[[branch-qualified-eval-cljs-database-read-stays-pending]].
