---
type: prd
status: draft
tags: [prd, render, agent, context, runtime]
---

# Self-generating context: the neighborhood renders itself as the history

Design session with the owner, 2026-08-11. This document records the rulings
and the composed mechanism; it extends — never replaces —
[repl-transcript-context-prd-2026-08-10.md](repl-transcript-context-prd-2026-08-10.md)
(the history-is-the-context move, storage verification, ordering keys) and is
grounded in two research reports commissioned and verified this session:
[incremental-invalidation-design-2026-08-11.md](../research/incremental-invalidation-design-2026-08-11.md)
and
[env-once-execution-design-2026-08-11.md](../research/env-once-execution-design-2026-08-11.md).

## The problem the owner named

Hand-authored bootstrap forms drift as the system changes, and the previous
whole-neighborhood dump ("42k of context… didn't seem real") is not a REPL.
The goal is automatic context discovery: the graph around an agent decides
what enters context, new data/refs/functions surface as agents define them,
and every entry reads as something the agent could have typed and would get
back — everywhere, for every agent, with no maintained list of forms.

## Owner rulings this session (each confirmed in chat, 2026-08-11)

1. **Render once; re-render only on an affecting change.** A render function
   runs once; its output is retained with the database reads it made
   (existing `:seon.render.call/read-evidence`); it re-runs only when a
   transaction touched a fact it actually read. Everything else is replay of
   retained bytes.
2. **Same eyes.** The retained artifact is shared: the agent's prompt, root's
   preview of that agent, and the user's page are the same block bytes at
   different fits. There is no second describe-an-agent mechanism.
3. **A profile is fit, never content** (restates the existing render-profile
   contract): the block comes from the same render function; the selected
   profile bounds only how much of it shows.
4. **Blocks stay the unit and the layout.** One primary block (large) plus a
   vertical column of smaller blocks for every other block carrying the HTML
   projection. The same newest-basis ranking that picks the primary block
   orders relevance for the AI side: freshest sits nearest the model's turn.
5. **The `/` root view is tiles of live windows**: one tile per attached
   agent showing that agent's newest-basis block — a live window, not a
   status card. The AI arm of the same entries is root's preview of its
   agents in context.
6. **Wakes and refreshes are different wires, and this design adds no agent
   wakeups.** Work wakes (a turn starts) remain only facts ADDRESSED to the
   agent — a message to it, an error against it (`wake-attributes` routing,
   untouched). Render refresh is passive: blocks update, history appends, the
   page morphs; the agent sees the fresh entries at its next turn, whatever
   caused that turn. Attention-worthiness is an explicit act: an agent that
   should interrupt root SENDS ROOT A MESSAGE.
7. **Both surfaces must be beautiful and lean.** No long context dumps, no
   useless error walls; the point of render functions is ideal context. Ugly
   output remains a defect with a feedback loop (standing order).
8. **`doc`/`dir` are extended, not replaced** (ruled earlier this session):
   Clojure-accurate names, injected into every namespace; extended to
   varargs and to schemas/tests/namespaces; they return a VALUE whose printed
   representation is the documentation (never a printed side effect and nil).
9. **Elision is a property of the printer, never taught**: printed the way
   Clojure prints `...`; the omitted detail stays queryable when asked for.
10. **Test results become facts**; `^{:seon.test/usage true}` (declared,
    lifted at index time like `:seon.workload`) marks the canonical usage
    demonstration; generative-vs-example is derived, never declared; nothing
    is added for "what a test asserts" — the test name is the claim.
11. **The self-erasing injection rule**: the system writes a form on the
    agent's behalf only when it would be true AND the agent didn't already do
    it. An agent that batches defn + schema + test + run gets nothing
    injected. Injections-per-turn is the measurable learning curve.
12. **Agent-authored forms NEVER re-execute** — made structural, not policy
    (the authorship fence below). Only system-authored pure reads refresh.
13. **The env, not "bootstrap forms"**: the agent's opening context is an
    extension of the ONE per-cluster `seon.env` value (`env/scope` with the
    already-declared agent-id member), persistent and shared at the cluster;
    never a second environment noun.

## The mechanism (composed from the verified reports)

### One derivation

```
context(agent, db):
  result ← one pull at the agent entity, selector GENERATED from schema ref
           declarations (forward refs nested; every stored ref also spelled
           reverse; requested distance = selector depth; caps = pull :limit)
  for each unit in result:  three projections through the ONE selection chain
      :seon.render/form   the form that produces it (see below)
      :seon.render/ai     the printed value for the prompt
      :seon.render/html   the same as Hiccup
  order: traversal tree (parent listing before child lookup; siblings
         parallel), then arrival time for live material
```

Measured basis for the pull move: the bespoke walk cost 23.2 s at distance 2
(fixed to 1.74–1.85 s this session by the projection wrap, commit
`ee255347b`); the same neighborhood as one Datahike pull with reverse
selectors measured **19.6 ms cold / 1.9 ms warm** (probe, scratch cluster,
2026-08-11). The pull result is also the membership oracle: old/new diff
yields changed, added, and removed units — which `changed-at` cannot do
(it discovers no arrivals).

### `:seon.render/form` — the third projection

Declared exactly like `/ai` and `/html` (schema property naming a function),
selected by the same chain, with two computed floor rules so coverage is
total by construction:

- reached VIA an attribute → the listing query over that attribute;
- landed ON an entity → `(db/pull db '[*] <identity lookup-ref>)` (identity
  spelling is computed from `:seon.entity/id-attr` — landed this session,
  commit `ee255347b`).

Attribute-level declarations are admissible (probed: Malli properties on ref
attributes compile and read back exactly): `:seon.cluster.message/to` carries
the form that spells its reverse traversal `(my.message/inbox)`. `dir`/`doc`
are NOT engine arms — they are ordinary `/form` declarations on `:seon.ns`,
`:seon.fn`, `:seon.schema`, `:seon.test`. Which shapes deserve better than
the floor is a COUNT, not a judgment: the walk records floor hits per schema;
declare the top, re-measure.

### Change detection (verified: ~90% already built)

Every first-party read already executes with evidence (`d/q-with-evidence` /
`d/pull-with-evidence`, Datahike dependency plans, revisions, replayable
requests; `seon.db/read-evidence-current?`). The two changes:

1. the ONE `seon.cluster.wake` listener gains attribute-INTERSECTION routing
   for the render wake (same O(tx-datoms) loop, payload-free, `offer!`);
2. the render proc APPENDS basis-labelled entries and never rewrites — the
   disposable latest-call lookup is separated from the ordered history
   (today's code conflates them). Prompt N+1 = prompt N + suffix ⇒ provider
   prompt-cache prefix stability.

Cost classes (acceptance): unchanged context = ZERO database reads;
irrelevant commit = rejected in the listener; relevant-but-semantically-equal
= one replay, no append.

### Env executed once; authorship fence

Bootstrap is already an ordinary created-once system run with settle-once
receipts. Additions (from the env-once report): required
`:seon.cluster.run.form/author` (`:agent` | `:system`) assigned by
constructors, never a caller field; unique `:seon.cluster.run.form/refreshes`
ref chaining a refreshed read to its predecessor; receipts own component
`:seon.cluster.eval/read-evidence`. The refresh transition accepts only a
prior form identity and proves `:author :system` + terminal receipt + no
successor — re-executing an agent-authored form is unrepresentable. No
migration; old clusters stay sovereign.

Blocker in current code (filed):
[agent-definition-restore-reexecutes-authored-source.md](../../../seon/issues/agent-definition-restore-reexecutes-authored-source.md)
— restoring the agent's defs re-evaluates stored source every fresh fork; the fix is
fact-reading installation through the native SCI seam. This is IN W1's path.

### Requires

`(require …)` in a session executes normally in the turn fork; settlement
commits the resulting namespace row (the existing seam — no mid-eval side
effect). The agent's requires expand its neighborhood; nothing is written
into any namespace's source. `doc`/`dir` stay bare (clojure.repl precedent);
everything else is explicitly required and visible in the history — the
require line IS the teaching.

## What this deletes

The bespoke traversal engine's per-entity query/render/admission shape (the
walk's novel jobs — elision, caps, cycle stops, changed-at display — survive
as operations on the pull result); the 42k dump; hand-authored bootstrap form
lists beyond `(help)` + `(in-ns …)` + the requires; `changed-at` as an
invalidation oracle; the schema walls and comment-framed headers already
slated by the transcript PRD.

## Rulings appended after W0 markup (owner, same session)

14. **Define-before-use is the one intra-distance ordering rule**: no entry
    may reference a symbol the history has not already introduced (require
    introduces an alias, `dir` introduces names, `doc` may then use one, a
    call may follow its doc). Computed from the parsed forms, stable
    alphabetical tie-breaks. One regression walks a generated history with
    the introduced-symbol set and asserts the property — "the context
    teaches" becomes machine-checkable.
15. **Render functions live with the shape's owner** (existing convention,
    now ruled): the schema names a fully qualified function in the owning
    namespace; agent-authored renderers live in the agent's namespace. No
    `seon.agents` collection home, no roster.
16. **V1 spend policy is concise-until-cap**: render everything at concise
    (names, one-liners, schema names) in order until X tokens; every concise
    value indicates its deeper form. No per-distance budgets in V1.
    **[TARGET]** distance-adaptive rendering — render functions receiving
    proximity to the agent's namespace and adapting spend, plus usage-edge
    detail sharpening (`:seon.fn/calls` from run forms shortening effective
    distance) — is the eventual design, deferred until a live V1 history is
    measured.
17. **Agent-authored functions, schemas, and tests get index parity**:
    settlement commits them through the same analysis as shipped source —
    same calls/keywords/subject edges, same tests-reaching derivation. One
    mechanism, verified in W1.

## Waves (build → drive → learn; design re-litigated only at boundaries)

- **W0 (this document)** — owner marks up anything that reads wrong.
- **W1 — the history emits itself**: `:seon.render/form` + floors;
  append-only entry discipline; authorship; the agent's defs fact-reading install (the
  blocker above). EXIT: a fresh scratch agent's prompt is a real REPL history
  — true values, no dumps — byte-prefix-stable across two turns.
- **W2 — change flows**: interest routing; `read-evidence-current?` replay;
  the schema-derived root pull. EXIT: one message transacted → exactly one
  appended entry, page morphs one block, everything else zero reads.
- **W3 — both views beautiful**: agent page + `/` tiles from the same entries
  (primary + column, newest-basis ranking), preview profiles, drill-by-form;
  driven DeepSeek turn on minimum context vs the 42k baseline, independent
  observer. EXIT: the owner looks at both surfaces and they are beautiful.
- Each wave: sol lanes implement; a dogfood lane drives the agent-facing
  surface and files every ugly output; injections-per-turn and floor-hit
  counts are the instruments.

## W2 evidence — 2026-08-12

W2 landed in `bc3dfe3fd`, `bdb7b8efc`, `b0a3713d3`, and `291681222`.
The event-driven acceptance results are recorded in
[W2 change-flow acceptance evidence](../research/w2-change-flow-acceptance-2026-08-12.md):
unchanged acquisition made **0 reads**; an irrelevant commit produced **0
render wakes**; a relevant semantically equal root read made **1 replay** and
**0 appends**; one new message made **1 append** with prior bytes retained.

The cold root pull measured **1,795.387292 ms** versus the **46.0 ms**
four-query floor, a **39.0301585× regression**. W2's correctness and
incremental change-flow exit is met, but cold performance remains open in
[cold root pull is slower than the four-query floor](../../../seon/issues/cold-root-pull-is-slower-than-the-four-query-floor.md).

## Open with the owner (not yet ruled)

- Tile content: live window (newest-basis block, whatever shape) vs fixed
  agent card with freshness — recommended: live window.
- Preview depth for attached agents; drill via the same form the agent would
  type — recommended as stated.
- Tile ordering: newest-first vs stable grid + freshness highlight —
  recommendation: stable grid with freshness highlight, newest-first only in
  the column.
- ~~History compaction~~ RULED 2026-08-11 night: DEFERRED — W2 builds
  without eviction; histories grow unbounded until a live long-lived agent
  forces the decision with evidence. Batched-at-checkpoint remains the
  recorded leaning for when it returns.
- Overnight rulings 2026-08-11: "the agent's defs" confirmed (retired vocabulary
  sweep authorized); unlimited DeepSeek FLASH drives approved for the
  minimum-context ablation and W2 live proofs; pre-existing test failures
  are fixed only when quickly root-caused, else filed.
