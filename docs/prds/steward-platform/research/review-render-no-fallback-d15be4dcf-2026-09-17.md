---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, render, selection]
---

# Orchestrator review — `d15be4dcf` (no render fallback)

Reviewed in full before the gate (PRD lane rule 4): `src/seon/render.clj`
(+/- 53), `src/seon/render/walk.clj` (+20), `test/seon/render/history_test.clj`
(+98), the landing note and the two issue notes.

**What it changes.** `:seon.render.walk/attribute` meant two things on one
key; the seam guessed which from the value's keys (`contains?`). Now the walk
decides: `scoped-attribute` stamps the attribute on a render request only for
a member synthesized FOR an attribute (no eid of its own), never for a
neighbour reached THROUGH it. Selection asks one question,
`attribute-scoped?`; an attribute-scoped request resolves to its declared
pair or to the floor. `attribute-declared-producers` and its guard are
deleted. The value renderer drops the attribute for nodes beneath the
subject, so children render by their own shape.

**Why it is right.** Owner law 2026-08-29: hand the authority the decision
instead of re-deriving it from a mirror; the walk knows which member it made
FOR an attribute, the seam never can. Decision 9 option 1 as ruled: an
uncurated attribute is visibly generic. The neighbour regression pins the
one legitimate case the old guard served.

**Checked and accepted.** No new noun; no harness; four drift assertions now
assert declarations already in the tree; fixture seeds the schema keys it
reads; `seon.render/render-form` remains the floor; the lane filed the ugly
generic output as its own issue rather than hiding it with a fallback
(`a-generic-attribute-scoped-render-answers-with-the-owning-entity`).

**Rejected.** Nothing. **Follow-up noted:** the generic answer for an
attribute-scoped request is the owning entity's pull; a per-attribute generic
form is its own slice.

**Gate requested:** batch 100 on the namespaces in
`tmp/orchestrator/gate-requests/render-no-fallback.txt`.
