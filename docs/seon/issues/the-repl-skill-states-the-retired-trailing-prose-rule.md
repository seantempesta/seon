---
type: issue
status: resolved
severity: cleanup
tags: [issue, repl, docs, wave/docs-honesty]
---

# The repl skill still states the retired trailing-prose rule

## Problem

`.agents/skills/repl/SKILL.md:89` describes `seon.cluster.reply/sources` as
attaching a prose span to "the one it precedes, or for a trailing span the one
it follows".

The second half is no longer true. Commit `be2a4ae46` ("Stop making prose the
comment of the form it was written after") removed the trailing attachment at
the parser: prose that follows the final form is nobody's comment, because
`seon.repl/text` renders a comment ABOVE its form's prompt line and the fold
therefore inverted the agent's own authorship order in its rendered session.
The prose remains in the durable reply text (`:seon.cluster.run/reply`).

A skill's blast radius is every agent that loads it, and this one is loaded for
exactly the reply-parsing work the claim is about. An agent reading it will
expect a comment fact that the parser no longer produces.

## Evidence

- `.agents/skills/repl/SKILL.md:89` — the stale claim.
- `src/seon/cluster/reply.clj`, `plan-sources` — the loop's terminal arm now
  returns `forms` unchanged.
- `test/seon/cluster/reply-test/a-forms-comment-is-only-the-prose-written-above-it`
  — the regression asserting the wanted rule.

## Wanted

One sentence in `SKILL.md` saying prose attaches to the form it precedes, and
that prose after the last form is kept only by the stored reply text.

## Why it was not fixed in the same commit

The lane that made the change (`reply-order-and-faults`, 2026-09-07) did not own
`.agents/skills/`; another lane was editing adjacent REPL owners in the same
tree at the time.

## Resolution

2026-09-07: the skill line was rewritten to state the current rule (prose
attaches only to the form it precedes; trailing prose stays in the reply
text) in the commit that resolves this note.
