---
type: plan
status: active
tags: [plan, render, context, design]
---

# One-renderer open questions — the iteration ledger

Owner + orchestrator working ledger, 2026-08-14 evening. Every major
design question surfaced by the [renderer
re-audit](../research/renderer-reaudit-2026-08-14.md), the four earlier
censuses, and the owner's design dialogue. The candidate answers are
assembled into one coherent shape in the
[one-pipeline design sketch](../research/one-pipeline-design-sketch-2026-08-14.md)
— read it beside this ledger; it shows what each ruling buys. Nothing below is settled
until marked RULED with the owner's answer; no wave starts before the
structural questions (Q1-Q4) are ruled and the PRD is re-marked to
match.

## Ruled in this dialogue (2026-08-14 evening)

- **R-a. The view IS the namespace render.** An agent is a namespace;
  the human surface is `/ns/<ns>` (root's `/` is the same mechanism
  with root's specialized view). No per-agent layout resolution
  machinery, no routing design. The whole job is nailing the render
  functions for both projections.
- **R-b. The two seams are 2D and 3D of one vector.** One pull/walk
  produces one ordered vector of blocks; the AI seam reduces it to text
  in order; the HTML seam arranges the same vector in space with
  different focus/emphasis. Turtles all the way down — no hidden
  machinery between the vector and either seam.

## Structural questions (answer these first — they shape everything)

### Q0. RULED (owner, 2026-08-14 evening — ledger ruling 33). The regime collision: REPL parity vs elision values

**Ruling: no regime bit.** Parity = framing fidelity (no comment
scaffolding, no narration — the transcript reads as a real REPL), never
stock elision bytes. ONE compact shape-bearing elision face everywhere,
firing only at extremes; ordinary generated content (`help`, larger
inter-agent messages, opening episodes) prints WHOLE under generous
defaults — this is the system's default printer and must be trustworthy
without options. The bare-cut defaults and `...`/`#` faces die; #26/D5
superseded; #25 narrowed to "no trailing annotation." Full text:
[design-ideas-ledger ruling 33](design-ideas-ledger-2026-08-13.md).
Original options and evidence retained below for the record.

The single biggest new fact from the archaeology
([print-path-design-2026-08-01](../../sci-execution-runtime/plan/print-path-design-2026-08-01.md),
re-read in full): the current printer is not vandalism — it is a SEALED,
owner-ruled REPL-parity design. Bare `"..."`/`"#"` are STOCK Clojure
faces (its D5 acceptance row: `(range 200)` under `max-collection` ends
`"...")"`, byte-identical to a real REPL; `test/seon/repl_parity_test.clj`
locks the bytes). The LATER elision-value law (AGENTS.md §2.4) demands
every cut be an ordinary value carrying count/path/requery. Both regimes
now live half-merged in `seon.print`, and NO seam decides which applies —
so parity faces leak into non-REPL surfaces (pages, context units) and
elision defaults fabricate facts in REPL positions. This unowned boundary
is the hole most of the garbage fell through. Options (research lane
gathering evidence:
[parity-elision-collision](../research/parity-elision-collision-2026-08-14.md)):

- (a) position-dependent faces: one elision NODE always carries full
  facts; its TEXT face is stock (`...`) exactly in REPL-result position,
  rich (`… +N more; requery …`) everywhere else; HTML/debug always rich.
  The regime bit lives on the render request (the transcript's
  result-line renderer asks for parity).
- (b) parity dropped: honest markers everywhere; `repl_parity_test`
  elision rows rewritten. Cost: the transcript stops being byte-faithful
  to a stock REPL.
- (c) parity kept only for result lines inside the transcript, ruled per
  face rather than per request.

**Evidence landed
([parity-elision-collision](../research/parity-elision-collision-2026-08-14.md)),
repricing the options:** the collision was created by `e34eea186`
(2026-08-04, a walk-totality fix that silently overwrote the sealed D5
face with no ruling reference) and never reconciled — the two regimes
are exercised by DISJOINT tests, which is why nothing caught it. Parity
has numbered rulings (#25, #26); the elision law entered as a
vocabulary note and was never ruled against them — and current
`render-elision-ai` output VIOLATES live ruling #25 (the forbidden
"N of M" annotation). Critically, the parity gate constrains far less
than assumed: every elision-dependent parity row is already
`:known-divergence` (the harness asserts they FAIL), so option (b)
turns no green row red — its real cost is formally superseding
#25/#26, and its implementation is profile wiring in three namespaces,
not a printer change. Option (a)'s honest cost: the regime bit in emit
options breaks P-TEE as currently stated. Under ANY option, four
repairs are needed: `fit-terminal` re-cutting fitted nodes with bare
defaults; the `::truncated-string` in-string ellipsis (lies in both
regimes); the unrecorded `::address` parity trade (`3f6958fc2`); and
the parity harness reporting health about a path half the system
bypasses.

### Q1. Does the composing walk become THE renderer?

The nested composition the owner wants — the walk detects recognizable
schema'd values at every depth and composes their declared faces —
half-exists as `project-node*` (`src/seon/render.clj:437`), but is
mounted UNDER the floor with one caller: composition only happens when
the top value had no specialist, and a selected specialist swallows its
whole subtree (re-audit §2.4). Options:

- (i) Invert the mount: the walk IS the renderer. Selection runs
  per-node at every depth; a specialist face renders its own value and
  the walk renders/composes its children (specialists become frames,
  not terminals). One chain, applied recursively; the floor is the
  every-node default rung, not a separate engine.
- (ii) Keep specialist-as-terminal but require specialists to delegate
  unknown substructure back to the floor explicitly.
- (iii) Status quo (composition only under the floor).

Sub-decisions if (i): cycle/re-entrance discipline (the
`:seon.render/rendering` guard exists, `render.clj:441-460`); block
identity for a face nested inside a face (interacts with rip-out #12);
whether a specialist can opt out of child composition (a face that
genuinely owns its whole subtree, e.g. source code).

### Q2. The curation stance — what is an `/ai` face FOR?

Owner (this dialogue): the point of `:seon.render/ai` is thought-through
per-shape key selection and transformation for agent comprehension —
never premature clipping of the same data. The PRD leans the other way
("decency never requires a declaration; most shapes need none"). The
face census graded ~37 of ~51 faces genuinely curated — the error/run/
message/agent families are evidence-derived and real. Options:

- (i) Curated DATA faces are the expected deliverable for every
  important schema family: ordered, selected, transformed keys — still
  data that reads back through the reader, never prose — with the floor
  as the honest default beneath, and a census that tracks which
  families still ride the floor.
- (ii) The PRD's stance: floor-first everywhere; declared faces only
  where a shape provably earns one.
- (iii) Keep the graded-good prose faces as-is and only kill the
  narrating-in-result-position defect.

Interacts with PRD open question 1 (attribute-ordering inline faces)
and with "results are data" (prose only under instruction entities).

### Q3. One chain — and does the owning-namespace step survive?

Two chains exist today (`producer` four-step vs `project-node*`
two-step, re-audit §2.3), and the four-step chain's step 2 (unique
contract-fitting function in the owning namespace) is dead for most
renders: only 4 call sites supply `:seon.render/namespace`. Options:

- (i) One chain, applied at every node (with Q1-i), and step 2 KEPT and
  made real: the walk carries the owning namespace so agent-authored
  render functions in owned namespaces are discovered — this is the
  "agents accrete their own faces" story.
- (ii) One chain but DELETE step 2 (dissolution): explicit key → schema
  default → floor. Ownership-based discovery returns later if evidence
  demands it.
- (iii) Keep both chains (status quo) — rejected by the one-mechanism
  law unless the owner overrules.

Also under this question: floor identity by declared property instead of
the hardcoded symbol set (`floor-producer?`, `render.clj:172`); one
calling convention instead of two argument shapes.

### Q4. Where exactly does bounding run — and what dies?

The PRD's seam ruling (budget ONLY at context assembly for a model
call; the printer owns quality-not-budget; admission caps at storage)
convicts, beyond the register: the second `fit-terminal` character pass
(`render.clj:524`, chops pr-str'd hiccup — re-audit §2.5); the
emitter's `::length 32` / `::level 8` bare-truncation defaults
(§2.1); the transcript and ns private fit loops; the prompt
distance-decrement loop (`prompt.clj:225` — whole branches vanish with
no elision value). Questions to rule:

- (a) Do the emitter's length/level options survive at all (as
  explicitly-requested display windows), or die with the bare `"..."`
  path?
- (b) What replaces the prompt's distance-decrement acquisition bound —
  does member-level selection at seam A subsume it, or does acquisition
  keep a config-derived distance with honest per-branch elision values?
- (c) Storage windows (`result-window-edn`) and the MCP oversized
  projection call `fit` today — are those seam-B admission uses
  (legitimate) or misuses of the quality printer?
- (d) Seam-A measurement: AI text only (rules out §2.10's
  markup-evicts-prompt), with the observed cluster calibration
  everywhere (§2.15)?

## Consequent questions (rule after Q1-Q4)

### Q5. Elision: one grammar, enforced where?

Bare `{:seon.print/face :seon.print/elided}` markers are legal per the
node grammar and render as fabricated sentences (§2.2); at least four
independent elision phrasings exist. Proposal to rule: the rich elision
value is the ONLY legal form (schema requires count/path/requery or
explicit refusal); admission emits it directly; `enrich-elisions` and
every hand-rolled "N more" sentence die; `render-elision-ai` is the one
face. Any dissent?

### Q6. The transcript rebuild

The transcript is the worst offender (private fit engine, budget
override, dead `:summary` tier, `<pre><code>` HTML, markup-evicts-
prompt) AND the spine of the whole context design. Rebuilding it as
ordinary blocks through the one pipeline is implied by Q1/Q4. To rule:

- (a) Do transcript entries get real per-entry `/html` faces (the
  orphan CSS — `seon-transcript-human/agent/peer/system` — shows the
  role-differentiated design already existed)? Presumably yes given the
  chat-view direction (ledger rulings 30-32); confirm it lands as part
  of the one-renderer waves rather than a separate chat project.
- (b) Does a real `:summary` tier exist at all (a curated shorter DATA
  face per entry kind), or is the honest model full-entry-or-
  whole-entry-elision (member-level, per the seam-A ruling)?

### Q7. HTML face parity for the error family and the `<dt>/<dd>` cards

AI has eight error prose specialists; HTML has one generic card across
all 327 declaration sites; hand-written specialist cards hardcode key
lists instead of `value/declared-attributes` (§2.11). To rule: is the
target (i) the composing floor + declared-attributes makes most bespoke
`/html` faces unnecessary (write few, delete the hand-rolled `<dt>/<dd>`
twins), or (ii) HTML parity — every family with a bespoke `/ai` face
owes a designed `/html` face? (i) is cheaper and matches R-b; (ii)
matches "the most beautiful apps" bar. Likely per-family judgment;
needs the census to say which families are load-bearing on screen.

### Q8. `web.clj`'s parallel content path

`session-timeline` (private pulls + `pop`/`conj` splice into another
namespace's hiccup), the second Clojure lexer, `generic-entity`'s
private EAV dump, and the debug page's string-concat prompt (§2.12).
Presumed rip-outs under R-a/R-b (the namespace view is blocks all the
way down; the debug pane renders the capture fact). Any of these the
owner wants kept as-is?

### Q9. PRD open questions that remain live

- `/form` projection timing (PRD q2).
- Chat-default timing for the agent page (PRD q3).
- New-chat mechanism: message-to-root sugar vs a creation route (PRD
  q4) — unaffected by R-a (it is a creation-flow question, not
  routing).
- The AGENTS.md §2.4 rewrite: "ugly output is a defect" should name the
  only legal responses (curated face at the schema, floor improvement,
  or an issue — never a local bound), so the instruction stops
  reseeding patch-paint. Owner already blessed the diagnosis; the
  wording lands with wave 1.

### Q10. Printer synthesis — the bounded open bits

The synthesis shape is recorded in
[value-printer-archaeology](../research/value-printer-archaeology-2026-08-14.md)
(revive sample→emit, dominant-string promotion, lazy guards, drill
hints, opaque tokens, inline-when-fits layout, verbatim probe; keep
sinks/tee, table face, elision-as-node, namespace lift, `references`;
delete the fit convergence loop, `fit-terminal`'s second pass, the
bare-cut default path). Still open beyond Q0:

- (a) Degradation order: adopt the archive's payload-first rule
  (dominant string promoted, scaffolding cut first) as the ruled
  default? The current fit loop's strings-first halving is the exact
  inverse.
- (b) Does the derived table face survive in the AI text (it is a
  deliberate divergence from stock REPL bytes — `print-table` pipes in
  result position), and under which regime from Q0?
- (c) The old sampler's small-value key preference reorders map entries
  for display; byte-stable but not insertion-ordered. Keep, or is
  declared-attribute order (schema-derived) the one ordering?

## Immediate defects independent of the waves (fix-on-sight candidates)

- `print.cljc:572` — `:?_current-ns_?/face` botched alias; the totality
  branch's own error value is permanently non-conformant and its test
  only checks the key name (re-audit §2.14).
- `walk.clj:596` — distance-cap elision marker suppressed for HTML only
  (absence-as-health on the page).
- `db.clj:580` and the `test/*` silent `(take N)` cuts — silent
  omission into agent-facing diagnostics.
