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

## Rulings 24-28 (owner, 2026-08-12 midday — the generated episode)

24. **The opening episode is GENERATED, never authored**: `bootstrap.edn`
    and the stored form plan are DELETED ("so we don't revert back...
    force us to fully think through an elegant solution"). The opening is
    the executed render of the fresh agent's walk: pull -> per-unit render
    selection -> introduction-ordered (comment, form) vector -> executed in
    the agent's fork as the ordinary system-authored run, real receipts.
25. **`(help)` is the agent entity's own declared render** — the walk's
    root unit; form `(help)`; value = the live situation DATA (a declared
    shape: id, namespace ref, unread count, open run, protocol namespaces);
    orientation prose is that shape's /ai render function. First by
    topology: nothing can precede the root, and every later form's subject
    is introduced by an earlier value (introduction-ordering IS the
    causality rule — inbox before read falls out).
26. **Demonstrations are `^{:seon.test/usage true}` tests**: one walkthrough
    usage test in my.run is the episode's arc (scratch->contract->error->
    test->complete), rendered as its ordered forms and executed in the
    fork. Suite-gated: bin/test goes red before any agent sees a rotten
    lesson. Demonstrations are uniform — bootstrap is not special.
27. **The unit render may carry the agent-voice comment**
    (`{comment, form}` from the form projection, accretion-open): think-
    then-act is modeled by the render function that owns the unit.
28. **Strict dogfooding is standing**: no manually assembled context
    anywhere — every context surface must trace to the walk + declared
    renders + executed receipts; a hand-assembled prompt fragment found
    anywhere is a defect.

## Rulings 29-31 (owner, 2026-08-12 afternoon — the expansion frame)

29. **The explained-set rule is the generator's invariant** (the
    macroexpansion frame: expansion to fixed point, resolve-or-fail where
    resolve = teach): before ANY form is emitted — including the
    demonstration's — its unexplained symbols get their explanations
    emitted first, recursively, membership-gated by the pull. Fixed point:
    no emitted form contains an unexplained symbol.
30. **Why-awake and the turn budget are situation DATA**: the open run's
    trigger ref and turns-remaining (new config dial + run derivation —
    the missing fact was the defect) appear in `(help)`'s value, so purpose
    and budget are facts the agent can act on, never ambient knowledge.
31. **`complete` IS the reply** — its settled value shows where delivery
    went (the run's trigger sender); no separate "message your requester"
    teaching, no prose-packaging: the lifecycle routes replies because the
    run carries its trigger.

## Rulings 32-34 (owner, 2026-08-12 — gap-closure generation)

32. **The generator's input is (pull, retained history) — gap-closure
    generation**: for every unit, emit the cheapest TRUE form closing the
    gap between the context and current facts. Fresh collection -> full
    listing then per-entity reads; already-shown collection -> the honest
    delta (`since` the retained entry's basis, Datahike's own vocabulary)
    then reads of only the new ids. One process; the emitted form is a
    function of the shown-basis the history already retains.
33. **Change provenance renders from tx-meta**: the ruled minimal
    provenance (:seon.db/user / :seon.db/process) joins through the delta
    query's transactions, so a generated comment like "; Root updated my
    plan" is a render of facts, never an inference.
34. **Bounded turtles**: render functions receive data in hand (cannot
    query -> rendering cannot cascade); the frontier is explained-once +
    membership-gated (fixed point); time is append-only + evidence-gated
    (only the delta since the shown basis generates). Generation is the
    derivative of the graph, never re-integration.

## Ruling 35 (owner, 2026-08-12 — render contract coherence)

35. **A render declaration must be contract-coherent at admission**: a
    schema X declaring `{:seon.render/ai fn-B}` (or /html, /form) is
    admissible only when fn-B's declared `:malli/schema` input accepts X —
    with the optional `:seon.db/database-value` argument permitted for
    call-preparation supply (its reads are captured as render-call
    evidence, which is what keeps invalidation correct). Checked at
    publication with a loud refusal naming both sides; a mismatched or
    schema-less declared render function becomes unrepresentable. The
    contract-fit selection path already enforces this by construction;
    this extends the same coherence to explicit declarations.

## Ruling 36 (owner, 2026-08-12 evening — work starts with a message)

36. **Updating an agent's data never starts its work — a MESSAGE does.**
    If root updates an agent's plan, root explicitly messages the agent
    ("implement the plan") to wake it; the plan change itself appends
    passively to context (rulings 6/32) and is seen whenever the agent next
    turns. The messaging wake is the one work kick-off. Evolving-session
    implementation (gap-closure T1/T2 mechanics) is GATED on owner review
    of the forthcoming PRD — exploration first, document, iterate, then
    build.

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
- **W1 — LANDED 2026-08-12** (all four mechanisms, gate triaged to 0/0 on
  repaired owners; fresh-agent history live-proven; see
  [w1-integration-summary](../../../../tmp/orchestrator/w1-integration-summary.txt)
  for the verbatim opening history): `:seon.render/form` + floors;
  append-only entry discipline; authorship; the agent's defs fact-reading install (the
  blocker above). EXIT: a fresh scratch agent's prompt is a real REPL history
  — true values, no dumps — byte-prefix-stable across two turns.
- **W2 — LANDED 2026-08-12** at its sealed cost classes (0 reads unchanged /
  0 wakes irrelevant / 1 replay equal / 1 append per message —
  [acceptance evidence](../research/w2-change-flow-acceptance-2026-08-12.md));
  ONE open cost defect: cold pull 1.5–1.8 s, attributed to triple selector
  parsing (1,013 of 1,525 ms), three carrier options awaiting the owner on
  [the issue](../../../seon/issues/cold-root-pull-is-slower-than-the-four-query-floor.md).
  Original scope: interest routing; `read-evidence-current?` replay;
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

## Morning 2026-08-12 — MINIMUM evidence (drive-proven, ruling pending)

HALF (7,393 tokens, 46% of FULL) produced the contracted defn, called it,
and queried the contract back in ONE turn across two replicates; the only
"incomplete" residue was disposition (not calling my.run/complete), which is
a teaching/mechanism gap fixed the same night (undisposed-at + honest next-
turn notice, 16672698d), not a context gap. QUARTER/FLOOR — which drop the
worked demonstration — never attempted the defn. The load-bearing content is
the WORKED CONTRACTED-DEFN DEMONSTRATION. Recommendation to the owner: rule
HALF's shape as the V1 minimum; the ablation table lives in
[the plan](../research/minimum-context-ablation-plan-2026-08-11.md).

Implementation evidence (2026-08-12): the authored bootstrap resource and
plan readers are deleted; generation, the declared form-output validator,
explained-set/determinism regressions, scoped call preparation, and root's
first-agent hook are committed. Focused integration is green: 96 tests and
528 assertions across bootstrap, history, my.run, the run loop, and SCI eval.
A fresh isolated, drive-free boot executes `(help)` once with a real receipt
and makes zero model attempts, then fails loudly at the second derivation with
`:seon.bootstrap/prefix-drift` (one form, one receipt, run still open). Thus a
complete rendered-history token estimate is not yet claimable; the remaining
boundary is stable subject/key selection for the post-receipt live pull.

## Morning rulings 2026-08-12 (owner, second round)

22. **The opening history is one complete episode** (gap-1 closure): help ->
    a real stored task message -> require/explore -> build -> verify ->
    `(run/complete ...)` — so an agent wakes MID-LIFE, its own last words the
    disposition of a finished episode, the fresh addressed message its
    purpose. The episode shape is the agent's remembered behavior, never
    described prose. All existing mechanisms (bootstrap is an ordinary run;
    the demo message is a real fact).
23. **Core-tooling presentation rides two rungs**: protocol-carrying ns/fn
    docstrings first (zero mechanism — `:seon.ns/doc` already renders
    through every dir), then a declared namespace render function owned by
    the namespace itself (ruling 15) where richer presentation earns it —
    e.g. my.run presented as a lifecycle in protocol order. No walk changes,
    no priority lists.

18. **HALF is the V1 minimum opening context**: instructions, own namespace
    in detail, required namespaces as one dir listing each, the worked
    contracted-defn demonstration, and the task message (drive evidence: two
    replicates, identical durable results to FULL at 46% tokens). The
    demonstration is the load-bearing teaching.
19. **Tiles are live windows by default; pins override.** HTML renders exist
    for the USER's eyes; each tile shows the agent's newest-basis block; the
    user may star/pin any block and the pinned block holds the tile instead
    of the live window until unpinned. For agents the same entries are a
    glimpse of current work — and the context must TEACH root to query its
    agents' histories or simply message them when an output does not explain
    itself (attention is a taught vocabulary, never a dashboard mechanism).
    **Implementation note (2026-08-12):** ruling 11 makes a creation-time read
    of a nonexistent child unwritable, so root opens with plain HALF. The
    existing first-agent arrival/armer path waits for that agent's bootstrap
    episode to settle, then opens one ordinary system-authored root run: one
    database read of the agent's two most recent form/value receipts and one
    `my.message/send` asking what it is doing, with the latter also completing
    the run. Each form self-erases independently when root's settled history
    already proves the corresponding read or sent message; the deterministic
    run identity prevents reinjection.
20. **Stable grid + freshness highlight** for tile arrangement; newest-first
    ordering lives only inside an agent's own column.
21. **Preview depth: newest block + recent messages** per attached agent in
    root's context and tile.
- ~~History compaction~~ RULED 2026-08-11 night: DEFERRED — W2 builds
  without eviction; histories grow unbounded until a live long-lived agent
  forces the decision with evidence. Batched-at-checkpoint remains the
  recorded leaning for when it returns.
- Overnight rulings 2026-08-11: "the agent's defs" confirmed (retired vocabulary
  sweep authorized); unlimited DeepSeek FLASH drives approved for the
  minimum-context ablation and W2 live proofs; pre-existing test failures
  are fixed only when quickly root-caused, else filed.
