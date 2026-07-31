---
type: issue
status: resolved
severity: blocker
tags: [issue, render, context, architecture]
---

# The transcript is a third prose owner for messages and eval receipts

## Problem

`resources/seon/schema/message.edn` already declares
`:seon.render/ai seon.cluster.message/render-ai` as the message
family's lens, and `resources/seon/schema/run.edn` declares
`seon.cluster.run/render-receipt-ai` for an eval receipt.
`src/seon/render/transcript.clj` re-derives both from scratch
(`message-form`, `message-direction`, `full-eval`, `summary-data`) and
never calls `seon.render/render`, `seon.render.block/data-prose`,
`data-panel`, or `seon.render.value`. It composes hiccup directly in
`html-output` and reaches `seon.render.block` only for `surface-id`.

The two owners have already drifted in the same wave that created the
second one: `transcript.clj:394` reads "Its effect may have happened;
nothing was retried"; `seon.cluster.run/render-receipt-ai` reads "its
effect may have happened, and nothing was retried." One fact, two
sentences, two maintainers.

The consequence is a real bound escaping. `full-eval`
(`src/seon/render/transcript.clj:369-379`) splices the raw stored
`:seon.cluster.eval/result-edn` bytes verbatim into the output, so
`:seon.sci.admit/caps` — `max-depth`, `max-collection`, `max-string`,
`max-nodes` — is applied to nothing this renderer shows. That is the
exact rule `seon.render.block`'s own floor docstrings state: "a second
set of size dials would drift from the first."

## Acceptance

The transcript composes the message and receipt family lenses through
`seon.render/render` at a distance, or those two lenses are deleted and
the transcript is declared the single owner in the schema. Either way
there is exactly ONE prose owner per fact, and every value the
transcript shows passes the one caps-bounded floor. One recurring proof
that a receipt result exceeding `max-string`/`max-collection` is bounded
and marked in the transcript's output.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`

## Resolution

Resolved by `618175e83`. The transcript's independent message/receipt prose,
preview clipping, result summaries, and interrupted-state wording were
deleted. Message and receipt sentences now come from their schema-family
projection selected by `seon.render.walk/projection` and invoked through
`seon.render/render`; supplemental data and stored result values enter the
one admission-backed data floor with the caller's caps. The recurring
`receipt-content-enters-the-shared-capped-floor` proof exceeds both
`max-string` and `max-collection`, asserts both in-band markers, and proves a
late map entry is absent. `bin/test seon.render.transcript-test` passed on
2026-07-31.
