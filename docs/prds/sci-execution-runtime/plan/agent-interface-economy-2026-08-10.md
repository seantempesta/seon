---
type: prd
status: active
tags: [prd, render, agent, runtime]
---

# The agent interface economy — the complete plan (2026-08-10, owner-aligned)

Owner direction, sealed across this session's dialogue: minimum context
through the NORMAL value printer; context is a REPL transcript that IS
the walk that IS the bootstrap — **one mechanism that works
everywhere**; both renderers are the P in REPL, just different targets;
no hacks, no per-value faces, no character chops; question everything a
unit renders; don't get bogged down on things we may not need.

Evidence base (all committed 2026-08-10):
[ui-truth](../research/ui-truth-2026-08-10.md) ·
[context-quality-audit](../research/context-quality-audit-2026-08-10.md) ·
[claims-sweep](../research/claims-sweep-2026-08-10.md) ·
[bad-output-catalog](../research/bad-output-catalog-2026-08-10.md) ·
[cohost-boot-speed](../research/cohost-boot-speed-2026-08-10.md) ·
[model-authoring-drive](../research/model-authoring-drive-2026-08-10.md)
(driver: MILESTONE ACHIEVED, both stages; observer verdict pending).

## THE TARGET — one mechanism

**An agent's context is a REPL transcript rendered by the walk.** A new
agent's prompt reads as 10–20 ordinary forms it appears to have already
run — requires, a help call, doc on its own API, a query over its
messages — each followed by its actual printed value. Bootstrap already
executes REAL forms settling REAL run/form/receipt facts, and form rows
already carry `:seon.cluster.run.form/{id,ns,ordinal,run,source}` with
results stored under ruling #25's inline/blob split — so **the initial
context, the live conversation, and the historical transcript are the
SAME walk over the SAME facts**, differing only in how far back the
profile spends. The separate transcript assembly path, the schema-wall
namespace dump, and the `;; d0 · [:lookup]` comment-header framing are
all second mechanisms this deletes.

**Both renderers are the P in REPL, just different targets** (owner
verbatim): the walk yields forms + values; the one printer renders each
value to the text target (agent prompt) or the hiccup target (page).
One session, two projections, two profiles — the page spends deep for
humans, the prompt spends minimal for models. Declared producers are
printer arms for shapes the grammar cannot serve, discovered by
contract (proven live today: a model-authored producer was selected
with zero wiring), and matter mostly for HTML.

**Push is minimal; pull is rich.** Identity + lightly-explained API
(names, arglists, one-liners — never full bodies) + recent
conversation + handles. Everything else is one query away: `doc` on
functions AND schema keys (definition, properties, a generated
example, consumers/producers derived from arity refs), plus one bulk
tool taking an array of symbols/keys/namespaces and returning their
faces together. Every elision carries a working requery handle;
refusals name the query that answers them — the system's own output
is the teacher, and the transcript shape means the agent has already
watched itself pull.

**Economics by construction.** The only dial is the prompt-level
budget; per-unit spend is DERIVED (distance, recency, the agent's own
references). Zero re-render at an unchanged basis in BOTH projections
is a hard graduation gate (HTML proven ~17 ms today; AI unmeasured).
No timeouts, no waits: long operations are defects to root-cause.

## PHASE 1 — prep: fix only what the target needs (lanes, parallel)

1. **The total floor** (the drive found the exact seam): the printer
   renders ANY value — `{:a 1 :b 2}` currently refused because
   `render-argument` merges value keys into the unit
   (`src/seon/render.clj:106-107`) against a qualified-keys unit
   contract; fix the class (the value rides as a value, never merged
   into the envelope). Delete the web `Renderer unavailable` substitute
   face; close anonymous `...` cuts and the unknown-face throw; an
   elision must never be longer than the value it replaces (the
   4-char-string elision defect). One regression per hole.
2. **Declaration-resolution noise at its last owners**: the ×1000
   fallback warnings observed live (instrument.clj:125/136, the
   per-EVAL projection rebuild in eval.clj) — same class as the two
   fixes that killed /data and the write seam; finish it.
3. **Warm-walk measurement + reuse**: measure the AI walk at an
   unchanged basis; make unit reuse real in both projections (retained
   calls exist; the remaining ~3 s per-node pull cost from
   ns-page-perf's measurements lives here). This prices Phase 2.
4. **Suite fat tail** (velocity only, no gold-plating): the ~185 s
   operator tests and the wedge-prone `init-owns-current-source…` —
   root-cause the await-process! hang seen in the aborted gate run.

Explicitly NOT prep (parked unless the target demands them): further
per-unit budget refinement (the dial dies in Phase 2), polishing the
old transcript renderer (replaced), the remaining Phase-3 dynamic-var
deletions (own wave, unchanged), docs-as-facts (parked by owner).

## PHASE 2 — the REPL-transcript walk (the centerpiece, one design + one build wave)

Design session first (owner + orchestrator, short — the shape is
already sealed; what needs ruling is only the unit grammar):

- how a HUMAN message renders as a form/value in the session;
- how model prose renders (comment grammar: `;` prose before forms —
  the reply parser already preserves this);
- the compact schema/API faces (`doc`-style) replacing schema walls;
- what the 10–20 bootstrap-visible forms ARE for a fresh agent (the
  current bootstrap forms, re-read as the initial transcript).

Build (file-disjoint lanes over one sealed design):

- walk renders run.form rows + settled results in ordinal order as
  form → printed-value units; recency-derived spend from the one
  prompt budget; old runs elide to handles;
- DELETE: the separate transcript assembly, compact-ai-text's
  unconditional schema dump, comment-header unit framing, the
  per-unit token dial;
- deep `doc` (schema keys: definition + properties + generated
  example + consumers by arity-ref query) and the bulk faces tool;
- HTML target: the namespace/agent page is the same session rendered
  rich (two profiles, one walk) — the page IS the transcript.

Data changes: none expected beyond declaring any missing edge found
during build (verified today: form sources/ordinals and settled
results are already facts; the context capture stays as forensic
truth). If a missing fact appears, declare it — never a side channel.

## PHASE 3 — prove it (drives, not assertions)

1. Fresh cluster; a NEW agent bootstraps on walk-only context and its
   prompt reads as a plausible REPL history (human-judged + observer).
2. Re-drive model authoring on the minimum context; compare against
   today's baseline (success first-try at ~12-13k prompt tokens):
   same-or-better success, materially fewer tokens, healthy c:p.
3. The UI walk re-run: every route PROVEN-LIVE, pages render the same
   session rich; THEN the multi-agent preview wall design discussion
   (owner) on proven ground.

## PHASE 4 — the quality page (only what survives)

Census Option 1, trimmed: many of the 23 classes die structurally in
Phases 1–2 (no second floors, no unbounded faces, no dead handles).
Commit exceptional-observation facts only at the seams that survive;
one derived dual-projection page; zero-write success path.

## GATES — "everything is working" (Phase F, updated)

1. Model-authoring drive: both stages confirmed by an independent
   observer (stage 1+2 driver-claimed today; observer pending), then
   recorded as a recurring deterministic proof in `bin/test`.
2. `bin/test --full` on a quiet tree: green or fully attributed, under
   ~15 minutes (cohost fix landed: 43.6 min → 71 s; run in flight).
3. Browser truth table: every route PROVEN-LIVE, zero substitute
   faces, zero console patch warnings, pages < ~2 s cold.
4. A fresh agent's context ≤ ~5k tokens, reads as a REPL session, and
   a core-namespace owner fits its prompt budget.
5. Zero re-render at unchanged basis, measured, both projections.
6. The quality page live; every surviving bad-output class queryable.

## Owner rulings recorded this session (2026-08-10)

- Context is a REPL transcript = the walk = the bootstrap; one
  mechanism everywhere. Both renderers are the P in REPL.
- Minimum push via the normal printer; producers are printer arms,
  discovery matters mostly for HTML; per-agent difference is profile
  selection, never code.
- The only budget dial is prompt-level; per-unit spend derived; no
  per-value customization; no character chops; elisions never longer
  than the value.
- Long waits are bugs. No hacky fixes to look good on one test.
- Bad outputs: catalog as queryable facts (census Option 1), zero
  writes on success.
