---
type: issue
status: open
severity: friction
tags: [issue, render, debug-page, runtime, ugly-output]
---

# The runtime block's HTML is raw ids and instants

Read on `default` 2026-09-10 (owner: "the :seon.runtime/entity html looks
pretty bad"). The block's HTML shows: a "13 referenced entities" dump of
bare entity ids (69338 69335 78199 …), "Runtime / Idle / Trigger:
c2421a79" (a bare message id), and a turns list whose header prints a raw
`#inst "2026-09-10T20:02:27.231-00:00"` and "Trigger None" while the
runtime's trigger is set.

What a person wants from this block, all derivable from the pulled data:

- A state line: "Idle since 20:02:27 (3 min)" or "Turn open since …",
  and what woke it: "woke on message from root: 'Which customer has the
  largest total? …'" (the trigger rendered through the message's pair,
  linked to the message).
- Listens as chips: "listening: :seon.message/inbox, :example/amount".
- Turns as a compact table, newest first: opened (local time), duration
  (closed − opened, or "open"), trigger summary, evaluations count, reply
  (first line). No `#inst` literals, no db ids anywhere.
- No "referenced entities" dump on this block (or on any block): a link
  per referenced identity where an identity exists, otherwise nothing.

The AI side of the block is fine as data; this is the HTML pair.

Runtime-html lane observation, 2026-09-10: the generic reference dump is
emitted by `src/seon/render/web.clj:1294–1313`, using the numeric ids from
`referenced-entity-ids` at line 1144 as both link target and label. This
protected file belongs to the render owner; the runtime pair cannot remove
that surrounding header. Keep this issue open for that follow-up.

Live verification also encountered the already-filed MCP degradation in
`dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`: status returned
unknown health/Flow with `Read timed out`; `(+ 1 1)` returned 2, but runtime
pulls through `seon.operator/connection` timed out at 10 and 20 seconds.
The HTTP debug page remained readable. No default restart was attempted.

The runtime pair improvement landed in `237c4c572`; the remaining generic
header dump still needs the render owner. During HTTP verification at
21:00Z, Juniper's current trigger was itself a feed-backstop notification
(`:seon.await/backstop-fired`, error `9c931b6e-8128-4552-8290-abb5ca69e00d`).
The pair renders that stored message faithfully. This is follow-up evidence
for the existing `scratch-debug-feed-and-turn-backstops-after-adoption.md`
issue, not a runtime HTML failure or a claim about the backstop's cause.

Final runtime-html verification: implementation commits `237c4c572` and
`881c720f4`; isolated gate 10 tests / 71 assertions, platform 84 / 505,
all green. Live HTTP 200 with the new pair, zero `#inst` or `:db/id`
literals in its text. Three explicit adoption attempts reloaded the
definitions and instrumented them, then refused to seal because source
changed during adoption. Adopted marker remained
`6aa31517-2ccf-50a2-a61a-9f0a9261b8e2`; published source advanced to
`6aa31c20-1ac0-53b5-959b-b810cd0c5c92`. The orchestrator must verify
convergence when publication settles. Full rendered text and exact
verification boundary are in
`docs/prds/context-generation/research/runtime-html-landing-2026-09-10.md`.
