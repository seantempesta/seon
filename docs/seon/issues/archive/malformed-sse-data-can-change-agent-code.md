---
type: issue
status: resolved
severity: blocker
tags: [issue, ai, agent]
---

# Refuse malformed SSE data before it can change agent code

## Problem

The streaming provider parser treats malformed `data:` JSON as presentation
noise. It deletes that chunk, joins valid completion fragments on either side,
and can return a different valid Clojure program for execution.

This finding is **in flight (schema-edn-consolidation lane)** because
`src/seon/ai.cljc` is modified in the shared tree; the lane's current diff only
changes its schema-resource comment and does not alter this behavior.

## Evidence

- `src/seon/ai.cljc:418-430` explicitly blesses malformed-chunk skipping and
  catches JSON failure as `nil`.
- `src/seon/ai.cljc:436-458` continues accumulating the remaining text as a
  successful snapshot.
- `src/seon/ai.cljc:671-697` turns that snapshot into the same completion shape
  as a one-shot response.
- `src/seon/cluster/loop.cljc:1205-1226` parses `:seon.ai/text` into the durable
  execution plan.
- `test/seon/ai_stream_fold_test.clj:117-124` explicitly asserts that malformed
  `data:` JSON is skipped.
- Load-only probe: a valid prefix `(my.run/complete \"safe`, malformed middle
  chunk, and valid suffix `\")` returned
  `#:seon.ai{:text "(my.run/complete \"safe\")", :tokens 3}`.

## Owner

`seon.ai/stream-event` and the one streamed-completion result boundary.

## Acceptance

- Keep-alive comments, blank lines, and `[DONE]` remain valid protocol events.
- A nonblank `data:` payload that is not valid provider JSON returns one flat
  error value with response evidence; no partial completion is frozen or run.
- A regression proves deleting, corrupting, or truncating any content-bearing
  chunk cannot yield a successful different program.

## Ruling #45 and resolution evidence

Owner ruling #45 selects failure at the provider boundary. The first
unreadable or structurally invalid nonblank `data:` payload returns the flat
`:seon.ai/unparseable-body` value. Because the response has started and output
was observed, `seon.ai/disposition` returns `:fail` even when a backup exists:
the loop records the failed attempt, closes the run, and never retries,
fails over, or freezes a plan. The agent receives the visible turn failure and
decides what to do next.

The alternatives were rejected for narrower contracts:

- a poisoned success would add a second completion shape every execution
  consumer must remember, and carrying poison only as error evidence reduces
  to the selected flat error;
- persisting malformed transport data before refusing at the reader would
  mislabel the provider response as successful, couple transport integrity to
  execution parsing, and duplicate the attempt/error facts the loop already
  records.

Silence remains correct only for content-free protocol input: comments, blank
lines, `[DONE]`, non-data SSE fields, valid usage/finish/empty-delta chunks,
and presentation-sink exceptions. Invalid JSON, a streamed provider error
document, and present non-string `content` or `reasoning_content` fields are
terminal errors. Visible text and reasoning share this one fold, so neither
can concatenate around a refused chunk.

The non-streaming syntax path was already atomic: `send-request` parses the
whole JSON body once and its existing catch returns `:seon.ai/unparseable-body`;
there is no accept-and-continue fold to repair. The same completion boundary
now also refuses decoded provider error documents and present non-string
assistant fields.

Implementation commit: `cbaffa1f0`.

Evidence on 2026-08-02:

- Before the source change, the exact audit probe returned
  `#:seon.ai{:text "(my.run/complete \"safe\")", :tokens 3}`.
- The replacement test was run against that old source with
  `bin/test seon.ai-stream-fold-test`: 19 tests, 72 assertions, 29 failures.
  All three new regressions were red, including the real JDK/http-kit streamed
  completion, which returned a success-shaped reconstructed program with no
  error evidence.
- After the source change, the same focused namespace passed. After the
  terminal no-retry assertion was added, the combined required gate
  `bin/test seon.ai-stream-fold-test seon.ai-test seon.cluster.turn-test`
  passed 98 tests and 463 assertions with zero failures and zero errors.
- A load-only JVM rerun of the exact splice probe returned
  `#:seon.error{:kind :seon.ai/unparseable-body, ...,
  :data #:seon.ai{:body "{not json"}}`; no `:seon.ai/text` key survived.
- The end-to-end streaming proof used the test's loopback http-kit server and
  the production JDK streaming body handler with canned SSE. It made no paid
  provider call and needed no credential.

The issue stays open for orchestrator review as requested.

## Closure — 2026-08-13

An unreadable `data:` payload now returns `unreadable-stream-data` as a flat error instead of being dropped (`src/seon/ai.clj:672,713,745`, verified 2026-08-13).
