---
type: prd
status: active
tags: [prd, render, agent, runtime]
---

# The agent interface economy — design and repair roadmap (2026-08-10)

Owner direction, 2026-08-10 (in chat, this session): catalog and track all
potentially bad outputs in one unified way; be ruthless about agent-context
problems; make `doc` deep (functions AND schema keys, with generated examples
and consumers); agent-authored render functions must appear on the UI
automatically; take the holistic view — "the floor is supposed to
automatically use the value printer"; spec fixes for everything with a roadmap
proving the system works.

Evidence base (all committed today, read end to end before this design):

- [ui-truth-2026-08-10.md](../research/ui-truth-2026-08-10.md) — the browser
  route walk: full loop PROVEN LIVE, defects presentational/cost only.
- [context-quality-audit-2026-08-10.md](../research/context-quality-audit-2026-08-10.md)
  — root's context measured: ~15.9k tokens, 43% schema walls, 5% callable API.
- [claims-sweep-2026-08-10.md](../research/claims-sweep-2026-08-10.md) —
  five of six overnight claims held; truncation half repaired same day.
- [bad-output-catalog-2026-08-10.md](../research/bad-output-catalog-2026-08-10.md)
  — 23 output classes: 5 facts, 3 derivable, 15 invisible.
- [full-gate log](../../../../tmp/full-gate-2026-08-10.log) — one test is 43.6
  minutes of the suite; run aborted wedged in an operator lifecycle test.

## The law — five invariants, one surface

The agent's interface to the system is one surface with three halves: PUSH
(the walk renders context), PULL (queries answer on demand), FACES (evaluation
renders results back). Every defect found today violates one of five
invariants on that surface. The fix program restructures so each violation
class is unrepresentable, per the loud-failures ethos.

### I1 — ONE TOTAL FLOOR (owner: "the floor is supposed to automatically use the value printer")

Render selection has exactly two outcomes: a declared contract-fitting
producer, or THE structural value printer (`seon.print`). There is no third
face. Today's violations are second floors and printer holes, all deleted or
closed by this invariant:

- the web `Renderer unavailable` substitute box (`src/seon/render.clj:479-519`,
  33 instances on `/`) — DELETED; a failed or absent producer falls to the
  printer face with a flat `:seon.error` value beside it, like every other
  failure;
- the map-rendered-as-vector-of-pairs face (census R1 example) — an admission
  shape reaching the printer wrongly; the printer's input is the admitted
  value, one grammar;
- bare `...` / `#` anonymous cuts (`src/seon/print.cljc:380-546`) — every cut
  emits the declared elision value; an anonymous cut is unconstructable;
- the `No matching clause` throw on an unknown print face — the face switch
  is total or refuses loudly with the member named (filed issue).

The floor is the honest destination, so falling to it is NOT an error — but
an important shape reaching agent context or a page through the floor
repeatedly is a missing declared producer (R1), which the quality page (I5)
surfaces as a fact, never a warning wall.

### I2 — BUDGET TOTALITY (no face escapes the fit owner)

Every unit output passes `seon.print/fit` with the one
`:seon.render.profile/token-budget` at ONE choke point (the walk applies it;
producers cannot opt out). Today producers opt IN — `seon.render.value` reads
the profile, `seon.render.ns` reads a private dial nothing supplies, error
faces and `data-edn` never fit at all. That is why my.fs renders 2.8× budget,
a six-word error became 2,154 chars, one `data-edn` hit 4.25M chars, and a
prospective seon.db owner context is ~164k tokens against a 32k budget.
Applying fit at the choke point dissolves the entire unbounded-face class —
including faces from FUTURE producers, which is what makes agent-authored
renderers (I4) safe by construction. HTML faces clamp through the same choke
point with page-scale profiles (owner question 1 below).

### I3 — REQUERY TOTALITY (nothing rendered is a dead end) + THE DEEP DOC

Every elided or summarized value carries a WORKING requery handle (the
declared elision value already carries identity/offset/profile; the audit
found refusals — "no durable blob or entity identity" — which are I3
violations at their producer). The pull surface those handles point at is the
deepened `doc`, all derived from existing facts:

- `doc` on a FUNCTION (landed 2026-08-03): symbol/arglists/docstring plus
  resolved arity input/output schema refs — extended with one seeded
  generated example call shape when cheap;
- `doc` on a SCHEMA KEY (new): the declared Malli form expanded one level;
  its properties (render producers, identity-only projection, etc.); one
  seeded `malli.generator` example; and the derived consumer/producer roster
  — functions whose arities take or return it, by `:seon.fn.arity/input-refs`
  / `output-refs` query. No registry, no list: an agent-authored function
  appears in that roster the moment its contract row commits.

### I4 — HONEST PUSH/PULL BALANCE (lean context, API-first)

Context = identity + own-namespace API + neighbor API cards + handles.
Schemas, tests, and source are ONE query away (I3 makes that real). The walk
model is right (owner: "I like the walk based rendering"); the spend is
wrong: 43% schema walls, 10% duplicated register! closure, 5% callable API.
The namespace renderer spends its (now-supplied) budget API-FIRST: `; fn`
lines first, schemas only with remaining budget, standard elisions for the
rest. Target: root ≤ ~8-9k tokens with all API lines intact; every `my.*`
unit within its 1,024 budget; a core-namespace owner FITS its prompt budget.
Agent-authored `:seon.render/ai` + `:seon.render/html` functions are selected
automatically by the existing contract query (`render.clj` candidates) — the
declaration is the wiring; the live proof rides the model-authoring drive.

### I5 — MEASURED (the quality page; census Option 1, recommended)

Exceptional render decisions become facts in the EXISTING render/error/attempt
families at the choke points that already exist — floor fallbacks with the
shape that fell, producer failures/contract violations as linked render
observations, fit refusals, no-target SSE patch evidence, fault recurrence
(F2's suppression contradiction fixed so repeats stay queryable), fault drops
(F3) as datoms. The successful-render path stays ZERO-WRITE. One derived
namespace page with declared `:seon.render/ai` and `:seon.render/html`
producers shows current quality — floor-heavy shapes, dead-end handles,
oversized faces, truncations, token pathologies — the same truth for agents
and the owner. Never a stored report, counter, or notification queue.

## Waves (dependency order; each wave = one lane set, one proof)

- **W1 — the total floor.** Delete the renderer-unavailable substitute and
  every second fallback face; close the printer holes (anonymous cuts,
  unknown-face throw); one regression per closed hole. Proof: the browser
  walk shows zero unavailable boxes and zero bare cuts; every unit is a
  producer face or a printer face.
- **W2 — fit at the choke point.** Move budget application into the walk's
  unit finish; delete the private ns dial (ns-render-budget lane's fix
  absorbs into this); error faces and `data-edn` display fit like everything
  else. Proof: no unit exceeds its profile; the seon.db owner walk fits.
- **W3 — requery totality + deep doc.** Fix dead-end elisions at their
  producers; implement schema-key `doc` (definition/properties/example/
  consumers). Proof: every elision handle in root's context resolves; `doc`
  on a schema key answers all four sections from facts.
- **W4 — the profile flip.** API-first spend, dedup the closure
  rendering, and DERIVED ALLOCATION (owner direction 2026-08-10: the
  flat per-unit 1,024 constant is a knob to delete — the only honest
  dial is the prompt-level budget, which is real; per-unit spend is
  DERIVED from what the walk already knows: distance, recency, the
  agent's own references. No per-value customization ever — a shape
  that renders badly improves the grammar or the profile, never gets a
  bespoke face; no character-count chops). Proof: root ≤ ~9k tokens,
  API lines intact, drive observer confirms better signal.
- **W5 — the quality page.** Census Option 1 facts + the dual-projection
  page. Proof: each of the census's 15 invisible classes is either
  queryable or structurally dead (made unrepresentable by W1/W2), zero
  remaining invisible.

## The program roadmap (everything, ordered)

- **Phase A — land the in-flight lanes** (all running now): ns-page-perf
  (namespace-page 7.6 s cause), suite-speed-cohost (the 43.6-minute test),
  error-class-red (schema-test red on HEAD), ns-render-budget (subsumed into
  W2 on landing), bootstrap-teaching (teaching failures strand new agents —
  drive prerequisite). Review each return before building on it.
- **Phase B — the frozen checkpoint** (source freeze, all lanes landed):
  one clean `bin/test --full` on the quiet tree at a named commit — the REAL
  suite number with per-class attribution (today's attempt: 47F/6E partial,
  aborted wedged in `init-owns-current-source…`; contaminated by mid-run
  edits, mine to own) — then republish + refork default.
- **Phase C — THE MODEL-AUTHORING DRIVE** (opus driver + independent opus
  observer, DeepSeek): can a real model author a contracted function end to
  end on this stack — the never-proven milestone. Extended stage: the model
  authors a RENDER function for a shape in its namespace and the page picks
  it up with zero wiring (I4's live proof). Every prior blocker is closed:
  render context (proven live), call preparation (verified in production),
  provider transport + durable truncation, transact! shape (landed today),
  bootstrap stranding (Phase A).
- **Phase D — the economy waves W1-W5** above.
- **Phase E — remaining filed defects**, each a class fix: SSE no-target
  patches (R7), MCP envelope 5× form echo, fault-loop second-mechanism
  deletion + F2 recurrence suppression contradiction + F3 dropped-fault
  datom, boot warning wall (O1 — the instrument.clj declaration-fallback
  ×100 observed live today), stale operator claim records (8, stale local
  formats), cohosted unbounded agent heap, `/data` key coverage (8 of 525).
- **Phase F — verification: "everything is working" defined as gates**:
  1. the model-authoring drive completes both stages (contracted function +
     authored renderer live on the page);
  2. `bin/test --full` on a quiet tree: green, or every red attributed to a
     filed issue with an owner — and total runtime under ~15 minutes
     (cohost fix + the fat tail measured today);
  3. the browser truth table re-walked: every route PROVEN-LIVE, zero
     unavailable boxes, zero console patch warnings, namespace pages under
     ~2 s;
  4. root context ≤ ~9k tokens API-first; the seon.db owner fits its budget;
  5. the quality page live with zero invisible census classes;
  6. drive rerun recorded as the recurring deterministic proof in `bin/test`
     (the standing graduation gate).

## Owner questions (asked in chat, answers to be recorded here)

1. **HTML clamp scope**: does I2's choke point clamp HTML faces too
   (recommended — same class, page-scale profiles), or is fit an
   AI-projection concern only?
2. **W5 timing**: quality-page facts land with W1/W2 (each choke point
   commits its observation as it is touched — recommended, one visit per
   seam) or as a separate final wave?
3. **Generated examples in `doc`**: shown by default or behind an argument?
   (Recommended: default for schema keys, argument for functions.)
