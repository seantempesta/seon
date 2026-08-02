---
type: issue
status: open
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
