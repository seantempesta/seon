---
type: issue
status: open
severity: friction
tags: [issue, context, render, agent]
---

# Keep recorded context captures out of the next context

## Problem

`:seon.context.capture` entities are ordinary neighbours of a run, so the
walk renders them into the NEXT prompt — including
`:seon.context.capture/prompt`, the verbatim previous prompt. The agent
therefore reads a quoted, truncated copy of what it was told last turn,
wrapped in `:seon.sci.admit/truncated-string` machinery, plus contribution
hashes, token counts and basis integers it can do nothing with.

Every turn feeds the previous turn's text back in, so the recorded capture
grows: 2310 tokens for run 1's capture, 5290 for run 2's, in a two-turn
episode with no new work.

## Evidence

`tmp/visual-qa/ai-scout.txt:128` and `:167` — each is one line containing a
`:seon.context.capture/prompt` whose value is ~2 KB of the previous prompt,
ending `…, :elided true} (elided — this value is larger than the configured
caps)`.

Contribution token counts read from the same facts: 2310 → 5290.

The same walk also renders `:seon.ai.attempt/*` rows (endpoint, model,
attempt id) and raw transaction entities
(`{:db/id 536870940, :db/txInstant …, :seon.db/trigger #:db{:id 4458}}`) as
generic data prose. None of it is actionable by the agent.

## Owner

`seon.render.walk` membership — what is a walkable neighbour of a run.

## Acceptance

An agent's prompt contains no copy of a previous prompt, no
`:seon.ai.attempt` row, and no bare transaction entity. Observability keeps
those facts for the debug surface and forensics; the agent-facing walk omits
them. Two consecutive no-op turns produce captures of comparable size.
