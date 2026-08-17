---
type: research
status: draft
tags: [context, render, runtime, execution, planning]
---

# Execution structure — DRAFT for orchestrator refinement

**THIS IS A DRAFT. It invents no design.** It organizes what is already
ruled in the
[runtime-first PRD](../plan/runtime-first-vision-prd-2026-08-17.md), the
[one-renderer PRD](../plan/one-renderer-prd-2026-08-14.md), and
[ledger rulings 17-41](../plan/design-ideas-ledger-2026-08-13.md) into
one reviewable, executable shape. Every section below is a PROPOSAL to
the orchestrator; nothing here amends a PRD, and no production edit
accompanies it. Where this draft found a genuine collision between two
ruled statements, it names both sides and hands the decision to the
owner rather than picking one.

Read end to end for this draft: runtime-first PRD, one-renderer PRD,
design-ideas ledger, program README. Live grounding probes are cited
inline with `file:line`.

---

## 1. Proposed unified document outline

One document, absorbing both PRDs (see open item O-7 — whether it
REPLACES them or indexes them is the owner's call; §2.5 one-mechanism
argues for replacement, git as archive). Working title: **the context
generation program PRD**. Sections, one line of contents each:

| § | Section | Contents in one line |
|---|---|---|
| 0 | The system on one page | The existing mermaid, extended one node upstream: facts → generators → rebuilt environments → the one pull → two seams. |
| 1 | The two axioms | Runtime-first (no files; store generators, never object graphs) and one-renderer (one pipeline, two seams); every later section is a consequence of one of them. |
| 2 | The five stages, precisely | one-renderer §1 carried over intact — collect (pull at `[:seon.ns/name …]`), order, face, print, update-and-deliver. |
| 3 | The graph the system reads | The program graph as it exists at HEAD plus the five enrichment edge families, each with the named query it unlocks and its drift regression. |
| 4 | The session dataflow graph | uses-var / uses-result / reads / writes / defines / requires as ordinary facts; liveness is Datalog; no JVM introspection anywhere. |
| 5 | Generation, diffs, compaction — one algorithm | The env-grounded fixpoint under three settled sets (empty / receipted / live-subgraph); the cadence ruling; the cost stance and the priced cache break. |
| 6 | The failure policy — three faces, one fact | one-renderer §2 unchanged: dev panics, prod emits one error fact, unconstructability in three layers. |
| 7 | The rip-out register | The 23 verified deletions, re-derived at HEAD, with one added column: which wave executes each row. |
| 8 | Revivals | one-renderer §4 unchanged — the archive built sample→emit, the layout probe, the highlighter, the capped writer. |
| 9 | The property suite | one-renderer §5's eleven properties plus the eight named properties this execution structure adds, each bound to the wave that must land it. |
| 10 | The waves | Renumbered A–G with dependency edges, owned paths per wave, and explicit file-collision fences (README fuckup #6). |
| 11 | Open decisions | ONE list: one-renderer §7's five, plus the ten below, each marked BLOCKING or NON-BLOCKING per wave. |
| 12 | Deferred by ruling | Budgets (37), the merge/candidate-branch lifecycle, the host-namespace generator inversion, what `bin/seon init` becomes in a no-files world. |
| 13 | Sources | The two PRDs' source lists merged and deduplicated; the evidence index. |

Structural notes for the orchestrator: §7 (register) and §9 (properties)
are the two tables a reviewer will read most, so they carry the wave
column and stay adjacent to §10. §11 must never grow an "awaiting
markup" parking area — standing feedback rule; each row gets asked.

---

## 2. Wave A — graph enrichment edges

Dependency-ordered. Every task lands DERIVED with its query and a drift
regression (runtime-first, "Graph enrichment", ruled 2026-08-17).

### A1 — Core-call edges

- **Owned paths:** `src/seon/fn.clj` (the target filter at
  `fn.clj:245-248` drops any call whose target is not a first-party
  function — that is precisely why core calls are absent),
  `resources/seon/schemas/seon.fn.edn`, `test/seon/fn_test.clj`.
- **Implements:** runtime-first graph enrichment #1; one-renderer §2
  unconstructability layer 2 (the census prerequisite) and §6 wave 1.
- **Acceptance property:** NEW **P-CENSUS-NONVACUOUS** — for a
  generated program population containing a planted core-call to a
  banned target (a `pr-str` feeding a seam outside the printer's
  owners), the seam census returns it. A census that answers empty on a
  planted violation FAILS the property. This is the absence-of-signal
  law applied to the census itself; `text-boundary-report` and
  `render.lint` are blind today for exactly this reason.
- **Size:** M. Index volume grows materially — a store-footprint
  before/after measurement is part of the task, not a follow-up.

### A2 — Enrichment inventory probe (gates A3–A6)

- **Owned paths:** one dated note under
  `docs/prds/context-generation/research/`; throwaway probes in `tmp/`.
- **Implements:** §2.5 accretion-in-place; ledger 23 derive-or-die;
  README fuckup #3 (we rebuilt what already existed).
- **Acceptance property:** NEW **P-NO-REBUILD** — each of families 2–5
  carries a live-queried statement of what already exists at HEAD
  BEFORE any schema edit. Two are already confirmed by this draft (see
  §3 conflicts D and E) and the probe must confirm the rest.
- **Size:** S.

### A3 — `:seon.schema/references` (family 2)

- **Owned paths:** `resources/seon/schemas/seon.schema.edn`,
  `src/seon/schema.clj`, `src/seon/program.cljc` (the reusable helper
  `schema-references` already exists at `program.cljc:276-280`),
  `src/seon/fn/schema_shape.clj`, tests.
- **Implements:** runtime-first enrichment #2; §2.2 facts-over-inference;
  ledger 23 derive-or-die.
- **Acceptance property:** NEW **P-SCHEMA-CLOSURE** — for a generated
  registry population, the transitive reference closure of any key
  computed by Datalog equals the closure computed by walking the stored
  Malli forms; the drift regression goes red when a form changes without
  its edges.
- **Size:** M. Blocked on open item O-4 (stored attribute vs read-time
  derivation over the existing `:seon.schema/shape` tree).

### A4 — Per-arity output-ref query (family 3) — EDGE ALREADY LANDED

- **Owned paths:** `src/seon/fn.clj` (a query owner beside
  `tests-reaching` / `output-path-report`),
  `resources/seon/schemas/seon.fn.arity.edn` (docstring only), tests.
- **Implements:** runtime-first enrichment #3; §2.2.
- **Scope correction:** the EDGE exists and is populated —
  `seon.fn.arity.edn` declares `:seon.fn.arity/output-refs` and
  `program.cljc:517,555` computes and asserts it. The remaining work is
  the query, registry-alias chasing (ruling 20's amendment measured
  `:seon.entity/id-attr` alone covering 37/2231 keys), and the drift
  regression.
- **Acceptance property:** NEW **P-PRODUCERS-OF-KEY** — for a generated
  population, "which functions produce a value carrying this key" equals
  the set derived from stored `:seon.fn.arity/output-refs` with alias
  chasing, and returns a TYPED UNKNOWN — never an empty set — for a
  function with no declared output schema.
- **Size:** S.

### A5 — Session dataflow edges (family 4) — the delta only

- **Owned paths:**
  `resources/seon/schemas/seon.cluster.run.form.edn`,
  `src/seon/cluster/loop.clj` (`settle!` at `loop.clj:682`),
  `src/seon/cluster/run.clj`, `src/seon/sci/eval.clj` (the turn env,
  for alias resolution), `src/seon/print.cljc` (`references` already
  does the result side), tests.
- **Implements:** runtime-first enrichment #4 and the session-dataflow
  section; ruling 17 (basis `t` beside every result); ruling 20
  (`seon.db/diff` as the delta helper).
- **Scope correction:** `seon.cluster.run.form.edn` already carries
  `:seon.fn/calls`, `:seon.fn/keywords`, `:seon.test/subject`, and a
  `:seon.cluster.run.form/refreshes` ref — uses-var is PARTIALLY landed
  at the form row. The genuinely new families are uses-result / reads /
  writes / defines / requires.
- **Acceptance property:** NEW **P-DATAFLOW-COMPLETE** — for a
  generated turn sequence, every edge family is present for every
  settled receipt or is a typed unknown; no receipt yields silent
  absence. (Silent absence here would make wave B's liveness quietly
  over-collect — the project's recurring failure class.)
- **Size:** L. **Blocks all of wave B.**

### A6 — Test→schema edges (family 5)

- **Owned paths:** `src/seon/fn.clj` or `src/seon/test/selection.clj`,
  `resources/seon/schemas/seon.test.edn`, tests.
- **Implements:** runtime-first enrichment #5.
- **Acceptance property:** NEW **P-COVERAGE-GAP** — "which schemas lack
  generative coverage" is a Datalog query whose planted-gap case is
  non-empty and whose no-gap case is empty for the right reason.
- **Size:** S–M. Deferrable — see open item O-8 (its named consumer is
  the merge gate, which is unbuilt).

---

## 3. Wave B — the regeneration generator

Depends on A5 (edges) and A1 (censuses). Every task in this wave touches
`src/seon/render/walk.clj`, which rip-out register #14
(`walk.clj:83-153`) also rewrites — see conflict K; these must be
sequenced, never parallel lanes.

### B1 — Env-grounded readiness in `ordered-episode`

- **Owned paths:** `src/seon/render/walk.clj` (`form-symbols` at
  `walk.clj:737`, `ordered-episode` at `walk.clj:753`),
  `resources/seon/schemas/seon.repl.edn`,
  `test/seon/render/history_test.clj`.
- **Implements:** runtime-first "Generation, diffs, and compaction are
  ONE algorithm"; ruling 19 (nothing rendered that will not run
  verbatim); ruling 39 (the namespace root).
- **Acceptance property:** one-renderer §5 property 2 **Form honesty**,
  extended: alias-resolved symbols replay identically in a fresh fork
  (ruling 19's own named falsifier).
- **Size:** M.

### B2 — `settled` as an input set: three modes, one function

- **Owned paths:** `src/seon/render/walk.clj`, plus the callers at
  `src/seon/bootstrap.clj:588` and `src/seon/cluster/prompt.clj`.
- **Implements:** runtime-first ONE-algorithm; ruling 25 (rebirth is
  `generate(current facts, empty history)`).
- **Acceptance property:** NEW **P-ONE-ALGORITHM** — initial context,
  turn diff, and compaction are the SAME function under three settled
  sets; a generated case proves initial context equals
  compaction-with-empty-live-set byte for byte.
- **Size:** M.

### B3 — Liveness reachability over the dataflow graph

- **Owned paths:** `src/seon/render/walk.clj` or a new query owner in
  `src/seon/context.clj`; `resources/seon/schemas/seon.context.edn`;
  tests.
- **Implements:** runtime-first enrichment #4 and the liveness-roots
  paragraph. **Blocked on open items O-2 and O-3.**
- **Acceptance property:** NEW **P-LIVENESS-DERIVED** — the live
  subgraph is a Datalog reachability result over stored edges from
  schema-derived roots; planting a dead error subtree removes it,
  planting a later reference to it keeps it; a MISSING edge family
  yields a typed refusal, never a smaller "healthy" live set.
- **Size:** L.

### B4 — Regeneration at the prompt seam + priced cache break

- **Owned paths:** `src/seon/cluster/prompt.clj`,
  `resources/seon/schemas/seon.context.capture.edn`,
  `src/seon/context.clj`, tests.
- **Implements:** runtime-first cost stance; one-renderer §1.5
  append-only discipline; ruling 12c (fidelity before economy).
  **Blocked on open item O-1.**
- **Acceptance property:** NEW **P-COMPACTION-PRICED** — every
  compaction records a capture fact naming the prefix break and its
  measured token delta; a turn WITHOUT compaction still satisfies §1.5
  prefix-stability (prompt N+1 = prompt N + suffix), asserted as a
  property rather than asserted in prose.
- **Size:** M.

### B5 — Compaction never paraphrases

- **Owned paths:** tests; `src/seon/render/walk.clj`.
- **Implements:** runtime-first ("compaction NEVER paraphrases; it
  re-derives live facts at the current basis"); ruling 34 (results are
  data).
- **Acceptance property:** one-renderer §5 property 6 **Results are
  data**, plus NEW **P-NO-PARAPHRASE** — every entry surviving
  compaction is a form re-derived at the current basis; a generated
  population contains no surviving entry whose text is neither a printed
  value nor an instruction entity.
- **Size:** S.

---

## 4. Conflicts and overlaps found

Cited as section vs ruling. Each needs the orchestrator's judgment or
an owner ruling before the unified document can state one truth.

**A. Wave scope: one-renderer §6 wave 1 vs runtime-first "Graph
enrichment".** §6 wave 1 carries only "core-call edge indexing (the
census prerequisite)". The 2026-08-17 owner ruling puts FIVE edge
families in scope, each with a query and a drift regression. §6 is
under-scoped against a later ruling; the unified §10 must carry all
five, and wave A above is that expansion.

**B. Append-only vs regeneration.** one-renderer §1.5 states the AI
seam invariant flatly: "prompt N+1 is prompt N plus a suffix". The
runtime-first cost stance says "regenerate-per-turn is accepted as
potentially cache-inefficient; correctness first", then immediately
hedges "turns without compaction remain append-only". Those are two
different designs (always-regenerate vs append-until-compaction). This
is the single largest unresolved ordering question and it decides
whether B4 is a mode switch or a replacement. → open item O-1.

**C. Error retention, self-contradicting inside one paragraph.**
runtime-first names "unresolved errors" as a ROOT of liveness (kept),
and two sentences later names "an error entry nothing later referenced"
as dead by construction — the owner's own exact case. An unresolved
error receipt IS a durable fact, so the two rules select opposite sets
for the same entry. Ledger 25 leans toward the second (rebirth
structurally cleans mistakes; "half the transcript was garbage"). →
open item O-2.

**D. Already landed — per-arity output refs.** runtime-first enrichment
#3 asks for output refs "beside input refs". They exist:
`resources/seon/schemas/seon.fn.arity.edn` declares
`:seon.fn.arity/output-refs`, and `src/seon/program.cljc:517,555`
computes and asserts them via `schema-references`. Executing #3 as
written would rebuild a landed mechanism — §2.5 violation and a repeat
of README fuckup #3. A4 above narrows it to the query.

**E. Partially landed — session dataflow edges.**
`resources/seon/schemas/seon.cluster.run.form.edn` already carries
`:seon.fn/calls`, `:seon.fn/keywords`, `:seon.test/subject`, and
`:seon.cluster.run.form/refreshes`. uses-var is partly there at the form
row. A5 above scopes to the delta.

**F. Derive-or-die vs new stored edge attributes.** runtime-first's own
later-settle list flags this, but its enrichment section simultaneously
commits the families to landing "DERIVED (never hand-maintained)".
`:seon.schema/references` in particular is a stored parse of
`:seon.schema/form`, and a `:seon.schema/shape` ref tree ALREADY exists
(`src/seon/fn/schema_shape.clj`, `src/seon/program.cljc`) that may carry
the same information. Storing a second copy is a mirror unless the
drift checker exists. → open item O-4.

**G. Liveness roots vs ruling 39's no-enumeration amendment.** Ruling 39
amended: "NO MANUAL SPECIFICATION OF WHAT TO PULL — EVER"; any
enumerated membership list is the banned hand-maintained mirror.
runtime-first's liveness roots are an enumerated list (current defs,
plan `:about` refs, open run, arrival tail, unresolved errors). Either
the roots are derived from schema refs like every other membership, or
compaction is a ruled exception to 39. → open item O-3.

**H. §7.1's session family vs compaction-supersedes.** one-renderer §7.1
proposes the session schema (`session/agent`, `archived-at` absent =
current, one forward `/session` ref) and does not mention supersedes.
runtime-first's last bullet asks whether compaction emits a `supersedes`
REVISION of the session ("likely yes"). These are one schema decision,
not two; §7.1 cannot be marked up correctly without the answer. → open
item O-5.

**I. Ruling 37 (no budget machinery) vs compaction's config floors.**
runtime-first's roots include "the arrival tail (recency floor, config)"
and "a config floor may delay collection". Ruling 37 defers budget work
entirely; ruling 12b defers tuning until the drive. Ledger 12c resolves
the principle in compaction's favor — "membership decides what belongs;
a budget bounds the speculative tail, never squeezes the explained
closure" — so a liveness floor is MEMBERSHIP, not a budget. The unified
document should say this explicitly so a lane is not blocked by 37.

**J. Wave renumbering.** Putting graph enrichment and the regeneration
generator first splits §6 wave 1 and inserts a wave that §6 does not
contain at all (the generator lives implicitly under §1.5). Every
cross-reference from the rip-out register and the property suite to a §6
wave number must be re-pointed in the same edit; a stale wave number in
the register is exactly the drift class §2.2 bans.

**K. File collision: `src/seon/render/walk.clj`.** Rip-out #14
(`walk.clj:83-153`, the agent-entity pull root) sits in §6 wave 2, and
wave B's B1–B3 rewrite `walk.clj:737-830`. Same file, two waves. README
fuckup #6 is this exact class, twice. The unified §10 needs an explicit
fence: one lane holds walk.clj at a time, and the ordering is stated,
not left to scheduling luck.

**L. Non-conflict worth stating as a unifying thread.** Ruling 36
(functions render through a default face that outputs the generating
FORM) and runtime-first design consequence 1 (store generators, never
object graphs) are the same idea at two scales. The unified §1 should
say so — it is the cheapest way to make the two axioms read as one
document rather than two stapled together.

---

## 5. Open items the owner must rule — NOT already in one-renderer §7

§7 already holds: the agent entity + session family + fundamentals (7.1),
panel mechanics (7.2), `/form` timing (7.3), the new-chat mechanism
(7.4), the name for one walked piece (7.5). These are additional.

| # | Question | Blocks | Why it cannot wait |
|---|---|---|---|
| O-1 | **Regeneration cadence:** always-regenerate, or append-only until an explicit compaction trigger? And what TRIGGERS compaction (basis growth, token estimate, agent request, root maintenance)? | B4, and the shape of B2 | Conflict B; decides whether §1.5's prefix-stability survives as an invariant or becomes one of two modes. |
| O-2 | **Unresolved errors: root-live or dead-by-unreachability?** | B3 | Conflict C — runtime-first states both; ledger 25 leans dead. |
| O-3 | **Are liveness roots schema-derived, or is compaction a ruled exception to ruling 39's no-enumeration amendment?** | B3 | Conflict G. An enumerated root list is the banned mirror unless explicitly excepted. |
| O-4 | **`:seon.schema/references`: stored attribute or read-time derivation over the existing `:seon.schema/shape` tree?** | A3 | Conflict F + derive-or-die (ledger 23). |
| O-5 | **Does compaction emit a `supersedes` revision of the session?** (In runtime-first's later list, but §7.1's schema depends on it NOW.) | §7.1 markup, B4 | Conflict H — pull it forward or §7.1 gets marked up against an unknown. |
| O-6 | **Does wave A start before the one-renderer PRD is marked up?** README and §6 both say no wave starts before markup. | Everything | Process gate. Wave A is largely orthogonal to the markup questions; the owner may want to scope the hold rather than lift it. |
| O-7 | **One document or two?** Does the unified PRD REPLACE both PRDs (§2.5 one-mechanism, git as archive) or index them? | The whole outline in §1 | Replacement means deleting two active documents in the same commit; that is an owner call, not an orchestrator one. |
| O-8 | **Is test→schema edges (family 5) in the earliest waves, or deferred with the merge design it serves?** | A6 | Its only named consumer (the merge gate) is unbuilt; landing an edge with no consumer is speculative accretion. |
| O-9 | **Config placement for the liveness floors** (recency floor, error retention): per-cluster fact, per-agent overlay, or both? | B3, B4 | Ledger 12b's fraction-not-absolute reasoning applies here too and should be settled once, not per dial. |
| O-10 | **Does Drive 2 gate wave B, or follow it?** Ruling 26 moved the live drive UP, before W3–W5, on the reasoning that the system was behaviorally inert; wave B changes the context bytes an agent sees. | B1–B5 sequencing | A drive measured against a changing generator measures nothing; a generator built without drive evidence repeats the pre-ruling-26 mistake. |

---

## 6. What this draft did not do

- No production edits, no PRD edits, no schema edits.
- No design invented: every task cites a ruling or a PRD section, and
  every named property is either one of one-renderer §5's eleven or a
  new NAMED property stated in full above.
- Sizes are relative (S/M/L), not hour estimates — the owner's standing
  preference is scope by coherence, not count.
- Waves C onward are deliberately absent per the assignment; §6 waves
  2–6 remain the authority for them until the unified document lands.
