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
