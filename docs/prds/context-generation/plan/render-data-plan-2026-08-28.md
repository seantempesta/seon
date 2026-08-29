---
type: prd
status: draft
tags: [prd, agent, context, render, eval]
---

# The (render data) plan — context = render(pull), evals, one general renderer

*Draft for the owner's markup, 2026-08-28, from the live design
dialogue. This SEQUENCES the converged design; the unified PRD
(context-generation-prd-2026-08-17.md) stays the program authority and
amends where this contradicts it (the reader-selection ladder leaves
the critical path; receipts rename to evals; stored prompt bytes
demote to a memo). LEGEND: [REAL] exists at HEAD, verified this
session; [P] proposed.*

## 0a. REFOCUS (owner, 2026-08-29, ruling 52)

View 1 is the whole plan: FULLY REGENERATED context every turn — the
transcript is one recursive render of the graph from the agent's
perspective; stability is a property (P-STABLE-REGEN), so caching is
automatic and the replay/frontier split, settled-set bookkeeping, and
retained prompt bytes below are all DELETED from the design. The
fresh/diff duality of §0 collapses to fresh-only; the per-turn diff
EVAL survives as content (the newest appended entry), not as a cache
mechanism. View 2 (believably extending an existing context) is
parked unless view 1's stability fails. Everything below reads under
this refocus.

## 0. The essence (owner, 2026-08-28 dialogue)

- **context = fn(db-value)**: `db' = db ⊕ settle(eval(model(render(pull db))))`.
  Fresh, turn-N, and compaction are ONE function differing only in the
  settled set. Fork/rollback = Datahike branch + the same call.
- **`(render data)` is single-arg and total**: all walking belongs to
  the PULL; render is pure over the pulled value. A nested map with an
  identity attribute is an expanded ref (recurse, face it); a leaf map
  that is only an identity attribute is an unexpanded ref (render the
  handle + its read call). [REAL: the pull-shape rule already puts the
  identity attribute on every ref leaf.]
- **Recognition is validation**: a face's Malli input schema is its
  recognizer, its registration (36), and its acquisition requirement.
  "This is an agent's world" is a face whose input schema requires
  `:seon.ns/name` + the assigned-agent reverse ref — greatest
  required-key coverage wins; claims are terminal (35); greedy maximal
  coverage over mixed entities; a per-render seen-set shows any entity
  once (block equality-suppression at value level).
- **Evals** (rename of receipts; Clojure's own noun; the family
  `:seon.cluster.eval` is [REAL]): one executed form + its result at a
  basis. Results are stored as ADMITTED DATA, never prose; rendered
  bytes are a disposable memo keyed by (eval, code generation).
  Agent-authored evals never re-execute; render never writes (M11 is a
  precondition, not a follow-up).
- **The generated context**: the seven-sentence intro (the ONE
  authored instruction), the affordance `(help)` (ruling 46, amended:
  purpose = ns docstring), then per turn ONE diff-composition eval
  over the printed prior basis. Mention-tracing (forms AND markdown/
  string content) generates teaching evals — doc/dir/schema renders,
  never execution of found code — ordered before their referents.

## 1. What each step leverages (all queryable at HEAD)

| Data | Where | Leverage |
|---|---|---|
| identity attributes, ref types, cardinality | installed schema + registry [REAL] | selector generation, ref-leaf recognition, fingerprinting |
| `:seon.schema/references` (3,655 edges) | persisted, wave A [REAL] | type-level closure, expansion planning |
| faces on family schemas (`:seon.render/ai`, `/html`) | registry [REAL] | the candidates map, coverage claims |
| `:seon.fn` contracts incl. per-arity output/input refs, 4,452+ call edges, core name-only rows | program graph [REAL] | help's composition join, readers-of-family (help-only, not assembly) |
| eval facts: form source, ordinal, result-edn, error, read-evidence | `:seon.cluster.eval` [REAL] | replay, staleness intersection, diff bases |
| tx-meta receipt stamp (42c) + `:db/txInstant` on every datom | [REAL] | write-provenance joins, free recency ordering (kills M1) |
| `seon.db/diff` helper | [REAL] | the turn-N update eval |
| admission caps + elision values with requery identity | seon.print [REAL] | bounded results-as-data, honest history |

## 2. The steps, in order, each with its falsifier

**S1 — probes before code (REPL, one sitting).** (a) generated pull at
the ns root on live `default`: shape, cost at current data; (b) the
candidates lookup for three fingerprints (message, plan, ns+agent);
(c) `seon.db/diff` composed over a printed basis, rendered through the
floor — how ugly is M13 really. Falsifier: any probe contradicting §0
returns to dialogue before any wave.

**S2 — `(render data)` unification.** The floor gains the ref-leaf
case (expand under existing caps when the target's family claims a
face, else handle + read call); terminal greedy-coverage claims; the
seen-set. Merges walk's ref knowledge INTO the one renderer — no new
mechanism, one deletion (walk's separate orchestration). Property:
P-RENDER-TOTAL-ONE-ARG — generated pulled values render without db
access (instrument: any db call inside render is red).

**S3 — evals: results as data.** Settlement stores the admitted result
(it already does: `result-edn` [REAL]) **plus derived queryable
projections accreted in the same settlement (ruling 48b): the result's
schema families and referenced identities as edges (the uses-result
family)** — "which evals returned messages" is one Datalog query. The
eval family's `/ai` face renders form + result via `(render data)`;
retained prompt bytes (`:seon.render.history/bytes`) demote to memo.
Rename sweep receipts→evals is the ORCHESTRATOR'S atomic quiet-tree
pass together with the sym retype (rulings 47/48c), not wave D's
train. Property: P-NO-STORED-PROSE — no fact family stores rendered
bytes as authority.

**S4 — acquisition = generated pull + recency windows.** Selector from
schema refs [REAL: root-selector] + datom-`:tx` newest-first windowing
(kills M1); face input schemas refine expansion (kills M2 as a policy
table). Re-measure at 10k datoms (M3).

**S5 — the opening + turn loop on the v0 spec.** Intro entity,
affordance help, one diff eval per turn, mention-tracing teaching
evals. GATED live test per agent-context-concrete §3 (platform tier
now green; the drive can settle turns). Every stall names a missing
GENERATED line.

**S6 — the deletion wave** (census 2026-08-28, 24 DELETE rows):
`my.run/walkthrough`, the authored situation-form + `(help)` prose,
`supervision-tx` string-built forms, `getting-started-text` bytes →
the intro entity, dead `walk/prose` + `effect/context-suffix`, the 11
already-registered narration sites. Each deletion lands with the
generated replacement proving parity in a live capture.

## 3. Dissolved by this plan (do not build)

Reader-selection ladder tiers 1–2 as assembly (survive only as help
suggestions); per-edge expansion policy (M2); suppression matching
(M14 — since-t diffs are exact); windowing rule (M1 — `:tx` is free);
stored prompt bytes; `/form` (44, already ruled).

## 4. Open to the owner (carried from dialogue)

1. Unfaced families: affordance-line-only (recommended) vs
   visible-but-ugly through the shape floor.
2. Results-as-data means admission decides what history remembers — a
   huge result's fact is its elision value; full recall is the as-of
   requery. Accept?
3. Face redefinition re-renders history at next regeneration (priced
   cache break, like compaction). Accept?
4. The eval rename's sweep timing (with wave D's rename train, or its
   own pass).
