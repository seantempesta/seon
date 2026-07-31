---
type: prd
status: active
tags: [prd, agent, context, render]
---

# Context-walk experiment protocol

Owner-approved 2026-07-29, re-scoped by the 2026-07-31 rulings: the walk is
now the RULED direction, not a candidate — "block" names the one render unit
in both projections and there is no static scaffold path. What survives here
is the staged, iterate-don't-guess EXPERIMENT MECHANIC: prove each step
against real outputs before the next. This is not a second program spine; the
README rulings (13–15 and the 2026-07-31 batch) own the design, this file owns
only the experiment mechanics. The stored-membership block path deletes when a
stage graduates past it (never two standing paths).

Grounding evidence (all committed): code-graph-end-to-end-2026-07-29.md,
old-context-assembly-2026-07-29.md, transcript-aging-quarry-2026-07-29.md,
the value-renderer port (in flight), README rulings 13–15.

## Stages — each exits through owner review of REAL outputs

**S0 — baseline corpus.** Capture the current block-derived prompts (the
exact-prompt capture facts already commit pre-provider) for helper + root on
scratch twins across representative triggers: chat message, routed problem,
error wake. Byte + estimated-token sizes recorded. Exit: a committed corpus
the later stages diff against.

**S1 — shadow render, nothing live.** Derive the same agents' context as
render(agent-entity) over the draft walk + the ported value renderer:
namespace section as code statements, requires at distance 2, message/mode
data by shape. NO model calls consume it; the comparison script renders
block-context and walk-context side by side from one db copy. Exit: the
owner and orchestrator READ the actual outputs; verdict on helpfulness gaps;
renderer iteration list. Failure here is cheap and expected.

**S2 — one guinea-pig agent live.** A scratch-cluster agent takes real
Ollama turns on walk-derived context; its block-context twin runs the same
triggers. Compare behavior (task completion, tool-call sanity, confusion
markers in replies). Exit: walk-context agent is not worse; defect list
drives renderer iteration, not framework rework.

**S3 — transcript joins the walk.** The transcript renders from facts
(runs → evals/receipts + messages, time-sorted) with aging as render policy
(quarry: 50-turn acquisition, newest-uncharged, budget-charged elders,
whole-event omission). Exit: transcript output matches the aging quarry's
behavioral spec on constructed histories; guinea-pig turns stay sane.

**S4 — ordering measurement.** Ordering v1 is PURE NAIVE per the 2026-07-31
ruling: blocks sort by last-change transaction basis, nothing else — no pins,
no bands, no hysteresis. This stage MEASURES it: real prompt-cache hit rates
on sustained Ollama drives vs S2, plus per-block piece digests
(registration-memory cache, derivable from scratch) as the diagnostic. Exit:
either the naive order holds, or a measured oscillation is recorded that
justifies designing banding/hysteresis against those numbers.

**S5 — graduation.** helper + root on the default cluster switch to
walk-derived context; the stored block system deletes in the same commit
(its remaining members become ordinary agent-entity facts the walk reaches). The owner's bar, run as an agent eval: a fresh
agent, told one sentence, changes what it sees by writing one defn. Exit:
the eval passes and the owner signs the deletion.

## Rules

- One stage at a time; the next stage's lane launches only after the owner
  reviews the previous stage's outputs.
- Every stage commits its comparison script + outputs under
  `../research/context-walk/` — unreproducible outputs are anecdotes.
- Renderer iteration happens INSIDE a stage (cheap); framework rework
  between stages requires a recorded ruling.
- Scratch clusters only until S5; the default cluster is the owner's.
