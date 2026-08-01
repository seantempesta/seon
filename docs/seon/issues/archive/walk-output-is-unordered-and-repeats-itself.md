---
type: issue
status: resolved
severity: friction
tags: [issue, context, render, agent]
---

# Order the walk by relevance and say each fact once

## Problem

Three separate readability failures in the one agent-facing projection:

1. **No usable order.** Units appear in neither depth nor recency order. In
   scout's 213-line context the depth sequence runs
   `3,3,…,2,2,2,1,2,2,0,2,2,1,…`; the agent's OWN unit (`depth=0`,
   "Agent scout is idle.") lands at line 101 of 213, and the cluster config
   entity outranks the agent's transcript. Ruling #17's recency intent is not
   visible anywhere in the output.

2. **Every unit is preceded by a machine label.** `;; path=[:seon.render.walk/
   neighbours 4 :seon.render.walk/neighbours 3] depth=2 provenance=…` is
   longer than most of the units it labels and names an addressing scheme the
   agent is never told how to use.

3. **The same fact appears three to five times.** One message shows up as the
   message unit, the run's form unit, the run's receipt unit, a transcript
   entry, and inside the echoed context capture. A probe string committed once
   appears 9-12 times in a single page.

## Evidence

- `tmp/visual-qa/ai-scout.txt` — full 31 KB projection, ordering as above.
- `curl -s /agent/scout | rg -o 'LIVE-MORPH-PROBE[A-Z0-9-]*' | sort | uniq -c`
  → `12 …-DEBUG-3`, `10 …-XYZZY`, `9 …-XYZZY-2` for three messages sent once
  each.
- `tmp/visual-qa/root.png`, `tmp/visual-qa/agent-scout.png` — the entire
  above-the-fold of both pages is the shared cluster/config entity; the two
  pages differ by one word.

## Owner

`seon.render.walk` — ordering and unit selection.

## Acceptance

The projection opens with the agent's own state and its newest unread work,
followed by instructions and toolkit, with older material last. Path labels
are absent or reduced to something an agent can act on. One committed fact
renders once per projection.

## Resolution

Resolved by `8fdedd29e` and `3ffaba05b`. `seon.render.walk/units` is the one
post-flatten sorter: the root is the stable head, ordinary branches retain
grouped last-changed order, and the synthetic transcript is the stable tail.
Labels are compact depth/provenance comments; a branch root retains the
literal path accepted by the deeper-walk call. The per-walk rendered set now
also consumes the transcript's structural membership, so message content,
matched form source, and eval result have one projection rather than both a
raw unit and transcript copy. A real-fact regression gives each one a unique
sentinel and observes exactly one occurrence. The nursery owner moved from
line 101 of 213 to line 4 of 72.
