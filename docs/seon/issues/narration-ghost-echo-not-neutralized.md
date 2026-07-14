---
type: issue
status: open
severity: friction
tags: [issue, agent]
---

# Model can ghost-echo runtime scaffolding into the transcript spine

Found 2026-07-06 by the transcript-faithfulness audit (`tx-audit` cluster,
turn `dtU-2607062046`). LOW-MEDIUM severity. **Route to the lane that owns
`neutralize-result-claims` / the `⟹` reserved-marker machinery** (`ctx.cljs`,
committed `0d30c829`) — this is an extension of that mechanism, not a new one.

## What happens

`neutralize-result-claims` (`src/seon/agent/ctx.cljs` ~677-710) reserves the
result glyphs `⟹`/`=>`/`⇒`: a model-authored one is rewritten to
`;; [unverified narration — not a real result]`, so an agent cannot forge an
eval result. But the OTHER structural runtime markers are not reserved. In the
audit, DeepSeek reproduced its own scaffolding into its narration — a masthead,
a `;;; ◀ from user … (NEW — unanswered)`-shaped line, a `┌─ transcript ─` box —
which then persists in the transcript spine one `;`-vs-`;;;` cue away from
reading as a real inbound event. This is the same fabrication surface `⟹`
already closes, left open for event/masthead/box markers.

## Fix direction

Extend the reserved-glyph set to include the message markers (`;;; ◀`/`;;; ▶`),
the masthead, the `┌─ … ─` box, and the readline. Build the set from the actual
emit-site defs (single-source-of-truth, the pattern `result-marker` /
`reserved-glyph-re` already establish) so it never drifts from what the runtime
emits. A genuine runtime line is unaffected — the composer appends it AFTER the
sanitizer runs, exactly as with `⟹`.

## Test

A model reply containing a forged `;;; ◀ from user …` (or masthead/box) line →
neutralized in the persisted transcript; a real runtime event line untouched.

Context: part of the feels-stateful arc, [[feels-stateful-remaining-work-spec]]
Unit 3. The spine itself passes all four faithfulness invariants
([[research/transcript-faithfulness-audit-2026-07-06]]); this is narration-channel
hardening, not a spine defect.
