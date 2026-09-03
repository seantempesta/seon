---
type: prd
status: active
tags: [prd, agent, context, architecture]
---

# Design ideas ledger — 2026-08-13 session

Every design idea that surfaced in the owner session, with its status.
AWAITING-OWNER rows are the ones he may not have responded to yet — each is
one decision away from queued work. Ruled items cite their record.

**Ruling-number convention (owner, 2026-08-28):** a bare "ruling N"
means THIS ledger's sequence. The sci-execution-runtime sequence is
always cited as "R\<N\> (runtime)" — the two sequences overlap
numerically and a bare cross-citation is a defect to fix on sight.

## Ruled today (no response needed)

1. **my.plan is the one task noun** — semantics of R49 (runtime) unchanged
   ([ruling batch](README.md#rulings-2026-08-13-owner) #1).
2. **T3 intent-directed membership** — ready items' `:about` refs join
   pull membership through the existing beyond-closure budget; refless
   openings byte-identical (batch #2). Includes: `plan!`
   resolution-validation (hallucinated refs refused at planning time) and
   subject units rendering WITH their usage demonstrations — the two
   agent workflows (use-existing vs build-new) unified by which
   demonstrations the subjects carry.
3. **R2 semantic migrations now, serial, red-tolerant** (batch #3).
4. **Registered keys/functions self-explain; identity decides sameness;
   regeneration collapses definitions to declaration/doc faces with
   source one query away** (batch #4).

## Queued as work (authorized, in the working edge)

5. **Instruction-facts PRD** (owner reviews before implementation):
   AGENTS.md ideas become instruction entities with program-graph subject
   refs and declared renders; the file becomes a render-equality
   artifact; lessons reach agents through the walk (teach-on-miss) and
   self-fade via R46 (runtime).
6. **The five laws' demonstrations as suite-gated usage tests** — part of
   the instruction-facts PRD.
7. **Class-issue member lists become tag queries** — replace each class
   issue's hand list with its `class/<id>` tag derivation (hand lists
   were provably stale within a day).

## Ruled 2026-08-13 evening (owner, conversational)

17. **Render the basis `t` beside every entry's result.** The receipt
    already records the read-basis transaction; rendering it makes
    incremental reasoning agent-operable (the agent anchors its own
    `since` forms on a shown value, never a guess). Lands with T3.
18. **No UNDECLARED temporal keys on domain functions** (amended for
    precision, same evening): the banned shape is a key a function never
    declared and would silently swallow. A DECLARED, registered,
    contract-validated option is the ruled form and already exists at
    HEAD: `my.message/inbox` takes `:my.message/inbox-options`
    (`[:map [:seon.db/since :seon.db/basis-t]]`) plus a declared
    positional `:seon.db/database-value` argument, so
    `(inbox {:seon.db/since 1200})` and `(inbox (seon.db/as-of 1200))`
    both run verbatim for any agent. This pair — declared database-value
    argument + fully namespaced registered options — is the uniform
    convention for every read surface.

19. **Nothing rendered that will not run verbatim for the agent** (owner,
    2026-08-13 evening): the generator only ever emits forms any agent
    can replay identically — `seon.db` compositions, never a pretend
    `:since` key on a domain function. Temporal ability is never coded
    per function: it attaches to the DATABASE VALUE (Datahike's own
    `since`/`as-of`/`history`, surfaced once through `seon.db`). Domain
    read functions that should be retargetable DECLARE a database-value
    argument; call preparation supplies the current database when the
    caller omits it (caller wins) — visible in the rendered signature,
    contract-validated, zero hidden keys. Teaching = the rendered doc
    face (declared argument + supplied default) plus the generator's own
    executed delta entries; the falsifier is replaying generated forms
    in a fresh fork and comparing.

20. **`seon.db/diff` — the one generic delta helper** (owner, 2026-08-13
    late; supersedes the per-function temporal-options emphasis of 18):
    no function is ever taught the basis. Any form whose function
    declares a `:seon.db/db` input (a program-graph arity input-ref
    query, never a roster) is diffable from outside:
    `(seon.db/diff {:seon.db/form '(my.message/inbox) :seon.db/basis-t 1200})`
    executes the same form against `(as-of db basis-t)` and the current
    database (purity over the db value — no external sink, also a graph
    query — guarantees honest replay), then diffs by derived identity
    (`:seon.entity/id-attr`), `clojure.data/diff` fallback for
    identity-less values. Returns
    `#:seon.db.diff{:added :changed :removed}` +
    `:seon.db/basis-t`/`:seon.db/current-basis-t`. Undiffable input =
    loud typed refusal. v2 optimization behind the same contract: diff
    current against the receipt's stored prior result instead of
    replaying. Existing declared options like `:my.message/inbox-options`
    stay as harmless accretion; no new function adds one. The generator's
    cheapest-form choice becomes full form vs this one helper.
    Implementation rides with T3; the key spelling follows the ruled
    `:seon.db/database-value` → `:seon.db/db` unification.
    **AMENDED by the live REPL exploration
    ([research](../research/generic-diff-exploration-2026-08-13.md)):**
    no diff algorithm is built — `clojure.data/diff` over an
    identity-keyed map (`(update-vals (group-by id coll) first)`) IS the
    identity-aware diff; the helper's content is the double execution +
    identity re-keying. Simplified contract, mirroring `as-of`'s own
    positional shape (no options map, no quoted form — a var + varargs,
    so no eval context and purity checks against the var's
    `:seon.fn/external-sink` fact):
    `(seon.db/diff basis-t #'my.message/inbox "root")` →
    `#:seon.db.diff{:added :removed :changed}` +
    `:seon.db/basis-t`/`:seon.db/current-basis-t`. Identity derives from
    the function's declared `:seon.fn.arity/output-refs` row schema with
    registry-alias chasing (`:seon.entity/id-attr` alone covers only
    37/2231 keys — the exploration's measured gap); a collection with no
    derivable identity REFUSES loudly, never a silent positional diff.
    Datahike offers no diff/tx-range/since-datoms, and `since` is
    add-only (measured: loses changed rows and retractions), so the
    datom level cannot replace result diffing.

## AWAITING OWNER RESPONSE — surfaced, discussed, not ruled

8. **The "cheapest form" cost model.** V1: one comparison in the one
   generator (`membership-diff > collection-size/2 → full re-read, else
   delta`). Refinement: price candidates as `count × learned per-item
   render cost`, where the per-(shape, profile) cost is a FACT recorded
   from real render receipts — no bake-offs ever. Decision: adopt V1 rule
   now, or wait for live-drive token measurements?
9. **Lessons MINED from lived receipts.** The session-curation machinery
   (editor → revision → mechanical proof → adoption) could promote a
   lived span of real receipts into a canonical usage demonstration —
   teaching material that was executed by construction, not authored.
   Decision: add to the instruction-facts PRD scope or keep curation
   separate?
10. **Negative lessons as executed refusals.** Scars taught as one real
    wrong call + its typed flat-error receipt (ruling 37's demonstration
    already contains one) instead of prose warnings. Decision: standard
    part of every usage demonstration, or only where a class repeats?
11. **Auto-derived `:about` candidates.** Sugar atop T3: parse qualified
    symbols out of item titles/descriptions and suggest them as refs (the
    explicit `:about` + validation stays the contract). Cheap, unruled.
12. **Per-(shape, profile) render-cost facts** as the general learned
    constant enabling #8 — a small schema + one recording seam.
12c. **Fidelity before economy — the objective ordering** (owner,
    2026-08-13 night): context generation optimizes for the agent
    holding everything it needs — complete, true, replayable — and only
    then picks the cheapest true form among equal-fidelity
    representations. Token reductions are a COST REPORT, never the
    achievement; completeness falsifiers (authored results present,
    refless byte-identical, replay-identical) lead every acceptance,
    ratios trail. Elision is legal only with a requery identity;
    membership decides what belongs — a budget bounds the speculative
    tail, never squeezes the explained closure. The governing goal, in
    the owner's words: figure out the best ways to get GOOD context that
    is not hacked together — one derivation pipeline from facts
    (membership → explained closure → cheapest true form → declared
    renders → replayable entries), never hand-assembled fragments,
    templates, or special cases. Every acceptance asks "is this
    derived?" before "is this small?".
12b. **Beyond-closure budget as a declared fraction, not an absolute**
    (owner exchange, 2026-08-13 night): the dial itself is ruled and
    honest (declared config fact, one gate, estimated-token unit), but
    the absolute 1024 silently changes meaning when the model's context
    window changes; a declared fraction of the provider context-window
    fact stays proportional, and the live drive's measurements (with
    #12's receipts) decide the value. Do not tune before the drive.
13. **Derive-or-die for every mirror.** The session's meta-lesson: every
    hand-maintained mirror of the tree (member lists, IN-FLIGHT blocks,
    vocabulary, counts in prose) must be derived, checker-enforced, or
    dated — candidate for promotion from practice to explicit AGENTS.md
    law text.
14. **Store-bloat key-prefix census** on next occurrence (issue filed) —
    plus whether the exclusive sweep should run on a schedule NOW rather
    than waiting for the maintenance portfolio.

21. **`{:seon.db/entity true}` RULED DELETED** (owner, 2026-08-13 late,
    via the priced options round). The measurement (671 map shapes, 41
    flagged, 163 carrying identity attrs — the 122 unflagged are genuine
    request/error/report envelopes) proved identity-possession cannot
    define entity-ness, and the flag is our invention with no Datahike
    counterpart — a schema-level kind stamp violating our own
    entity-IS-its-attributes law. Ruling: delete the flag and derive
    each consumer's real question — the walk catalogues kinds from
    attributes present in ACTUAL DATOMS (live truth; empty kinds have
    nothing to browse); identity derives from `:db.unique/identity` in
    the installed schema (Datahike's own fact, `datahike.clj:256`);
    entity-vs-envelope = `storable-attribute-in?` at the bridge
    (`datahike.clj:284`). ~3 consumer call sites
    (`schema/internal.cljc` derive path, `render.clj:509`,
    `bootstrap.clj:294`, `walk.clj:151`) + 31 declaration deletions.
    IMPLEMENTATION QUEUED after plan-t3 returns (it holds walk.clj and
    bootstrap.clj).

22. **Cluster-name safety is computed, never a reserved list** (owner
    exchange, 2026-08-13 night): the store collision was a LAYOUT defect
    (refs and object store in one directory — the git analogy), which R3
    option 2 dissolves structurally. The remaining guard is derived: a
    cluster name must be a valid single path segment (tightening
    `:seon.boot/cluster-name`, an honest input narrowing), and creation
    refuses when the target directory exists and is not a cluster —
    checked against the filesystem at admission, never a hand list of
    reserved names. Identity beneath the address already exists: store
    id, branch head commit id, Datahike's named branch. Folded into the
    r3-store-layout lane's resume.

## Ruled in the 2026-08-13 night question round

23. **#13 PROMOTED**: derive-or-die is AGENTS.md law text (landed under
    §2.2 beside the regex rule, same commit as this entry).
24. **#12 APPROVED as record-now-consume-later**: the render-cost
    recording seam (small schema + the one receipt seam) lands so the
    live drive accumulates real per-(shape, profile) token facts; no
    consumers until evidence. Queued behind the drain.
25. **#10 REJECTED — no demonstrated mistakes.** The owner: mistake
    transcripts poison context ("half the transcript was garbage").
    The design already has the right two mechanisms and needs no third:
    the typed evidence-complete REFUSAL teaches at the moment of the
    mistake (the error value IS the correction, in the live turn where
    it helps), and REBIRTH structurally cleans mistakes on compaction —
    regeneration derives from current facts, so a failed form that left
    no durable fact simply does not appear in the reborn opening
    (authorship fence + R46 (runtime) self-fade + supersedes edges). What
    we know about a mistake is queryable (the form, its error receipt,
    whether anything superseded it), and the compaction form that
    cleans it is the one we already run: generate(current facts, empty
    history). #9 (curation-mined lessons) HOLDS until curation has real
    usage.
26. **#8 NOT RULED — measurements first, live agents before pricing.**
    The owner leans full-then-diffs-until-compaction but commits to
    nothing before seeing real context and real DeepSeek agent behavior
    live. Consequence: THE LIVE DRIVE MOVES UP — it now runs as soon as
    the drain lands and the generated-opening live-pull wedge is fixed,
    BEFORE W3-W5 (the producer accretion is complete and behaviorally
    inert, so the system is clean for a drive; the consumer migration
    waits behind the drive rather than in front of it).
27. **Floor-first rendering** (owner direction, same round): lean on
    `seon.render.value` — the total floor (`render-ai-data`,
    `window`, `prepare`, elision values) — rather than hand-declaring
    producers per shape. The floor should DERIVE good concise output
    for any registered shape (identity attr first, required attrs as
    columns, windowing, requery elision) from the schema facts it
    already has; declared producers remain for genuinely special
    surfaces (errors, messages), not as the price of decency. This
    supersedes the earlier inbox-fix framing (declare producers per row
    shape) — the exemplar becomes: make the floor render
    `:my.message/inbox` well WITHOUT a declaration.

28. **Delete the world-fetching defaults — the p1 class kill** (owner,
    2026-08-14 small hours): no production internal may re-derive
    expensive state (a projection, an environment) because the caller
    forgot to hand it — the fallback is DELETED, not preferred-away; a
    missing world is a flat typed refusal naming the site. The
    legitimate other kind is preserved and named: cheap authoritative
    handles at DECLARED seams (seon.db's elided current-database
    arities, call preparation's declared supplied defaults — visible,
    caller-wins, deref-cheap). First execution: the projection fallback
    in `seon.config/effective`/`render/request-profile`
    (entity-flag-deletion lane, in flight); the refusal's message is
    the caller to-do list, and the class regression asserts the typed
    refusal itself. Remaining class/p1 members sweep against the same
    rule after.

29. **`:about` is one plain vector of symbols and namespaced keywords**
    (owner, 2026-08-14): authored as
    `['seon.cluster.message/send! 'seon.db :seon.config.ai/model]` —
    never hand-built lookup refs. Resolution derived per token
    (slashed symbol → function row, bare symbol → namespace row,
    keyword → registry row); unresolvable token = flat `plan!` refusal
    naming it; the authored vector is stored faithfully and resolution
    is never stored. Amends the hours-old T3 shape in place before
    first real use (plan-t3 lane, in flight).

30. **The chat page IS the transcript's `/html` projection** (owner,
    2026-08-14 midday): the agent view renders the SAME history blocks
    the `/ai` projection feeds the model — input is the agent's history
    unit (never the namespace), each entry rendered per-block via its
    declared `:seon.render/html` producer or the derived floor; entries
    with no real html face elide to a compact chip (honest elision
    value: value kind, size, basis) that EXPANDS INLINE (owner
    amendment, same day): expanded, the chip pretty-prints the full
    data with syntax highlighting in place — the same pretty-data
    renderer as debug, the same bytes, summoned on demand — never a
    bounce to another page, never dropped. Chat = curated
    face, debug = complete pretty-data face; same block identities so
    morphs serve both. No agent-side injection — rendering is
    system-side projection selection; the declare-renders-at-birth rule
    is the quality incentive. Seam: `seon.render.transcript` history
    derivation + the web agent page. Folds into ui-overhaul.

31. **Debug default face = the `/ai` views, pretty** (owner, 2026-08-14
    afternoon): the debug view always renders what the model sees (the
    `/ai` projection) but formatted — pretty-printed spacing, syntax
    highlighting. Honesty falsifier amended from byte-identity to
    CHARACTER-CONTENT identity modulo whitespace and color: strip
    whitespace, compare — no character added, removed, reordered, or
    rewritten. The raw toggle keeps exact bytes (token/cache
    debugging). Amends decision 1/4 of
    [transcript-view-design-2026-08-14](../research/transcript-view-design-2026-08-14.md);
    folds into ui-overhaul with the chat-projection package.

32. **Producers hand back whole values — bounding lives at exactly two
    seams** (owner, 2026-08-14 afternoon, correcting the clip rip-out
    mid-flight): content gets smaller ONLY at (1) the render boundary —
    `seon.print`/the value floor applying the profile budget for both
    `/ai` and `/html`, emitting elision values — and (2) declared
    storage-admission caps (the `seon.sci.admit` config-fact family).
    `bounded-text` is internal machinery of those two seams, never a
    public convenience; a producer containing ANY bounding call is the
    defect. The census regression asserts producers bound nothing and
    the two seams are bounded-text's only callers (graph query). THE
    ONE RENDERER IS THE WORKHORSE: investment goes INTO the universal
    print system, never beside it — declared producers stay the
    exception for genuinely special surfaces.

33. **REPL parity is framing fidelity; elision is for extremes; the
    printer is a trustworthy default** (owner, 2026-08-14 evening,
    resolving open-questions Q0): parity's purpose was killing the
    comment scaffolding and narration that made output NOT read like a
    real REPL — it was never a commitment to stock `...`/`#` elision
    bytes. Elision markers exist for EXTREME data only, and when they
    fire they SHOW THE SHAPE (type, count, what remains, requery
    identity). Ordinary generated content — `help` output, the larger
    inter-agent messages, generated-opening episodes — prints WHOLE
    under defaults: defaults are sized so nothing ordinary elides,
    because this is the system's default printer and must be
    trustworthy without options. Consequences: the emitter's
    `::length 32` / `::level 8` bare-cut defaults die (they would elide
    help-sized output); the bare `...`/`#` faces die with them; the
    `repl_parity_test` elision rows (already `:known-divergence`) are
    rewritten to the shape-bearing face; ruling #26's D5 bare-face seal
    is SUPERSEDED by the shape-bearing elision face; ruling #25's
    no-annotation rule survives narrowed — no TRAILING annotation line
    after a value; the shape lives inside the marker at the cut point.
    No per-position regime bit is needed: one compact, shape-bearing
    elision face everywhere, styled identically in result lines and
    pages.

34. **Declared faces for everything are the goal; the floor is the
    net, never the norm** (owner, 2026-08-14 late evening, resolving
    open-questions Q2 and confirming the big picture): one mechanism
    generates the agent's context and the human's page — the ordered
    block vector from the one agent-rooted pull, faces per unit,
    append-only diff/history updates, two seams. Within it, every
    load-bearing family owes a thought-through declared face for BOTH
    `/ai` and `/html` — selection and transformation designed for the
    consumer, data that reads back through the reader, never dumped
    output. The floor value printer exists for honesty and totality; a
    family riding the floor in either output is an open census gap.
    Composition of face fragments covers nested values whose own face
    is missing. Neither output tolerates shit.

35. **Faces are terminal; the floor composes only what nothing
    claimed** (owner, 2026-08-14 late evening, resolving Q1): when a
    value — or a function's returned value — has a declared renderer,
    that renderer owns the output, full stop. Per-node composition is
    the FLOOR's mechanism: the last resort for values nothing claimed,
    where the floor walk detects nested registered shapes and composes
    their faces. The general posture stays ruling 34: register or
    explicitly specify renderers; do not lean on the floor. (The
    corollary: a declared face that renders its nested values badly is
    a curation defect to fix at that face, never a reason to add
    machinery under it.)

36. **Agent-authored render functions are ordinary program facts — the
    same mechanism renders them** (owner, 2026-08-14 late evening,
    resolving Q3): defining a function in an agent's namespace writes
    program-graph facts reachable from the agent's entity, so the pull
    acquires it and it renders automatically, like every other value.
    Functions get a DEFAULT renderer whose output is the FORM — the
    REPL form that generates the data — so evaluating it produces the
    value whose own renderer then shows it correctly on every surface.
    No registration step, no special discovery machinery: it's all the
    one pull + faces mechanism.

37. **No budget machinery until the pipeline works** (owner,
    2026-08-14 late evening, resolving Q4): defer seam-A budget work
    entirely — focus on getting the content RIGHT, not on clipping it.
    The interim knob is acquisition depth config (go less deep if
    needed), and if the right context is not appearing at the right
    depths the answer is moving data around (schema refs, membership),
    not clipping. HTML has no clipping, ever. The four existing budget
    loops still die with their owners; member-level selection with
    shape-chips remains the ruled DESIGN for when budgets return, but
    it is not built now.

38. **The namespace view's layout model: recency promotes, pinning
    locks, everything is live** (owner, 2026-08-14 late evening): the
    HTML side uses the same walk order but with NO diffing obligation —
    every block always shows its full live content (morph to current
    state; the append-only history discipline is an AI-seam
    requirement only). The MOST RECENTLY CHANGED block holds the large
    primary position; the remaining blocks sit in the right side panel
    ordered by last update (~three visible on desktop). The namespace
    LAYOUT RENDER sets all of this up as movable panels; a user PIN
    locks the main view in place (browser-local, per ui.md — never a
    fact). The transcript naturally holds primary most of the time
    because it changes most; an agent that wants to show the user
    something defines a function (ruling 36) and its block takes
    primary by recency. No dedicated "present to user" mechanism
    exists or is needed.

39. **THE ROOT PULL IS THE AGENT'S NAMESPACE — anything else is the
    wrong thing** (owner, 2026-08-14 night, correcting the live
    system): an agent can be ANY namespace, and its context is a pull
    of ITS NAMESPACE'S DATA. Period. Membership is what the namespace
    reaches: its ns form and requires (define-before-use starts at the
    ns form), its indexed functions and schemas, and the agent's own
    live material through the namespace's reverse ref (namespace ←
    agent → runs, messages, plan). NOT ruled in: rooting at the agent
    entity; following every installed ref bidirectionally; dragging
    the cluster entity (config, instruction set, toolkit refs) into
    every context — the live capture
    ([root-context-example](../research/root-context-example-2026-08-14.md))
    shows exactly that wrong root: nine toolkit namespaces dir'd from
    CLUSTER refs while root's own ns requires only four. Cluster-level
    instructions/config appear in a context only if the namespace or
    agent genuinely refs them — the parked instruction-facts design
    does not ride in through the pull. The `:seon.cluster/toolkit`
    refs and `toolkit-namespaces` vector as a membership source are
    dead under this ruling.

    **Amendment, same conversation: NO MANUAL SPECIFICATION OF WHAT TO
    PULL — EVER.** The selector stays generated from installed schema
    refs; the agent DISCOVERS everything from the pull and the system
    renders what came back. Any enumerated membership list — including
    category stagings like "identity, rules, toolkit, mine, events" —
    is the banned hand-maintained mirror. What appears in a context is
    decided by exactly two things: the namespace root and the schema's
    declared refs. Ordering is derived (pull-tree order,
    define-before-use from the ns form, arrivals last), never staged
    by hand.

40. **AGENTS ARE NAMESPACES — identity, not association** (owner,
    2026-08-14 night, sharpening ruling 39 and R-a): the namespace IS
    the agent. Its requires are its capabilities, its indexed
    functions are its skills, its runs, messages, and plan are facts
    reaching IT. Creating an agent is creating a namespace; root can
    be `my.agent.root` — every existing name is open for renaming.
    The current data model's separate `:seon.cluster.agent` entity
    joined to an ns entity by a `/namespace` ref is an artifact of the
    old view; the dissolution direction is agentic attributes
    accreting on the namespace entity itself (an entity IS its
    attributes — a namespace that carries an open run is an agent at
    work). Exact schema unification (merge the families vs retain the
    ref as internal plumbing under one identity) is the next
    data-model decision, brought to the owner with options before any
    schema edit.

41. **Two routes: `/` and `/ns/<full.ns.symbol>`** (owner, 2026-08-14
    night): the only routes right now are root at `/` and every
    reachable namespace at `/ns/<full.ns.symbol>`. Consequences:
    `/agent/{id}` and `/agent/{id}/debug` are dead (agents ARE
    namespaces, ruling 40); the `/ns/{ns}/debug` and `/data` PAGES are
    dead as routes — the honest `/ai` view (ledger 31) becomes an
    in-page affordance of the namespace view (browser-local, ruling
    38), not an address. Amendment (same conversation): `/data` — the
    ALREADY-WRITTEN global database browser (`route.clj:22`, the
    structural floor with paged get-in navigation, cursor in the URL)
    — survives as a third route. For the record, the dead `/agent/*`
    rows are not hypothetical: `route.clj:13-17` declares them today
    and `GET /agent/root/debug` served the live capture — they encode
    the pre-ruling-40 agent-is-not-a-namespace model and are
    deletions. Non-navigable plumbing (the SSE feed, the message
    POST, static assets) remains transport, not navigable surface;
    ui.md's route table is amended to match in the same wave that
    re-scopes §1.5.

42. **Execution start-gate rulings** (owner, 2026-08-17): (a) the
    unified execution document REPLACES both PRDs — one authority; the
    PRDs become history on its landing. (b) Core-call edges land as
    name-only `:seon.fn/fn` rows for core/library vars (the accretion
    precedent at `fn.clj:1604-1612`), accepting call-relation growth,
    with `bin/seon init` timed before and after. (c) The write
    carrier: `:seon.db/receipt` stamped into transaction metadata at
    the run loop's transact seam, UNSKIPPABLE (the absent-signal trap:
    an unstamped write must be impossible, not merely discouraged).
    (d) Background implementation of the verified graph work proceeds
    WHILE design iteration continues; the freeze-vs-receipts
    transaction shape is confirmed correct as built (sources atomic in
    one basis, receipts ascending per form).

43. **One name per attribute; readers must offer faces; no magic and
    no hardcoded openings** (owner, 2026-08-17 night): (a) the
    `my.*`-alias key mirrors are dumb and DIE — surface functions
    return STORAGE keys; message entities get their rename in the same
    sweep; the alias rows join the deletion register; P-NAME-ALIGNED
    (every reader's output-refs are stored attributes of the family it
    reads; every writer's inputs likewise) is the enforcing census.
    (b) Entities stay independent and may reference ANYTHING — each
    datum's own reference graph is its discovery surface. (c) The
    reader-selection filter gains the second criterion: a winning
    reader has auto-injectable-only required inputs AND offers render
    faces — so the agent gets the designed view, never the dense map,
    by construction. (d) NO MAGIC: the nicer view is an ordinary
    composition the agent could type — the face is a real function,
    `(render-x (reader …))` reconstructs it explicitly, and the raw
    data is always one call beneath. (e) NO hardcoded bootstrap forms
    anywhere: the opening derives entirely from find-data →
    discover-reader-with-faces → reconstruct-call. (f) tx-meta joins
    discovery: the receipt stamp (42c) makes "what this agent wrote"
    a derivable edge, and written entities enter the neighborhood and
    render through the same mechanism as everything else.

44. **The `/form` face DIES — forms are constructed, never authored**
    (owner, 2026-08-17 night): the declared read-recipe was a second
    declaration of what contracts already declare — the banned mirror.
    The ladder is THREE tiers: stored provenance (replay/attribution)
    → contract inference (output-refs cover the edge's identity
    attributes WITH SHAPE participating — collection-shaped outputs
    win collection edges, single-entity outputs win dig-ins — inputs
    auto-injectable, faces offered, distance-weighted) → the floor's
    mechanical identity pull. Hard constraints: `:seon.render/form` is
    REMOVED FROM THE GRAMMAR (unconstructable, not censused);
    ambiguity at equal distance is a loud error fixed ONLY by refining
    contracts — no tiebreaker declaration exists; the floor census
    names every family without a qualifying reader. Agents and humans
    can never hand-construct context by authoring forms — send →
    inbound refs → (inbox) output connects by rules alone. The
    architecture docs' "three projections" amend to two authored
    faces (/ai, /html) plus constructed forms in the same wave.

45. **Comment-shaped results are banned everywhere, forever** (owner,
    2026-08-17 night, re-arming first-implementation law): a result
    rendered or WRITTEN AS A COMMENT (`;; =>`, `; ⟹`) teaches agents
    to fabricate results as comments — observed destroying agents in
    both implementations. Results print BARE on the line after the
    form (the transcript's `⟹` glyph is deliberately not
    comment-shaped). The ban covers production, docs, skills, and
    examples: 300+ teaching instances swept this night (100+ files,
    including the repl skill); prose WARNINGS naming the ban are kept.
    The docstring guard that once flagged `;; =>` echoes was found
    INVERTED (test/seon/dev/docstring_test.clj:104 "NO LONGER
    flagged") — re-arming it is the acceptance criterion, filed as an
    issue.

46. **The affordance opening — help shows the world in function terms**
    (owner, 2026-08-17 night): the opening does NOT eagerly render
    every group. `(help)` generates, per attribute group on/around the
    agent (own, inbound, outbound), the CALL THE AGENT COULD RUN with
    a one-line docstring — affordances, not content — so no context
    the agent doesn't need is pulled in. Eagerly rendered: only the
    situation header (identity + `:seon.agent/purpose`) and the
    TRIGGER (the arrival that opened the run). The NAMESPACE entity's
    default `/ai` face is dir-style self-teaching: every function with
    its one-line doc and in/out schemas, plus the referenced schemas
    explained — the namespace documents itself; root overrides with a
    locally defined function (level 3), same as its page.
    **`:seon.agent/purpose`** is a plain string attribute written at
    creation (create arg or derived from the first message),
    agent-updatable, rebirth-safe, rendered by the situation face —
    one attribute, no mechanism. Staleness/arrival injection is
    unchanged: new data still appends as its explicit query + rendered
    result; affordances govern only what the OPENING volunteers.

    **Amendment (owner, 2026-08-28): `:seon.agent/purpose` DIES before
    it is built.** Evergreen purpose lives in the NAMESPACE DOCSTRING —
    already indexed, already rendered by the dir-style namespace face,
    one home, no new attribute. The situation header renders identity
    plus the ns docstring's first line. The episodic GOAL remains the
    [v0 opening spec §4](agent-context-concrete-2026-08-17.md) decision
    (root plan item recommended, still open). This resolves the
    conflict between this ruling as first written and the later v0
    spec, in the v0 spec's favor.

47. **Program identities are symbols; the context and the population
    are one act** (owner, 2026-08-29): `:seon.fn/sym` and
    `:seon.test/sym` retype from string to `:symbol`, matching
    `:seon.ns/name` (map to Clojure primitives; database data is
    disposable — retype + reset, never migrate). THE INVARIANT: every
    name the SCI context can resolve has a program row, minted where
    the context learns it — publication indexes macros as rows (they
    are callable, documented, sourced); the injected REPL bindings'
    rows derive from ctx construction itself (closing the hand-list
    blocker); a settled `(require …)` mints its namespace row at
    settlement. Call edges are recorded only for env-resolvable
    targets — an unresolvable mention is an error fact plus a mention,
    never an edge. Identity rows never retract (unmap retracts
    definition facts, not identity), so lookup refs are stable
    forever. The always-tempid tx-preparation direction is REJECTED as
    a band-aid over incomplete population; the settlement tempid
    machinery reverts to plain lookup refs once the invariant holds.

48. **Data-model-first triplet** (owner, 2026-08-29, question round):
    (a) function identity stays ONE SCALAR qualified symbol — no
    composite tuple; the sym's namespace half is redundant with the
    `/ns` ref, so a drift regression (one query: every row's sym
    namespace ≡ its `/ns` target) rides the retype pass; (b) eval
    results are the admitted EDN **plus derived queryable projections
    accreted at settlement** — the result's schema families and the
    identities it references as edges (the uses-result family) — so
    "which evals returned messages" is one Datalog query; full
    datomization rejected; (c) the orchestrator's atomic quiet-tree
    rename pass covers the sym retype (string→symbol) AND
    receipts→evals; kind→shape and database-value→db stay staged as
    spelling hygiene.

49. **Keys are always fully namespaced; values follow the vocabulary
    law** (owner, 2026-08-29, amended same day): the absolute law is
    about KEYS — every map key and database attribute is fully
    namespaced so the fact self-identifies and installs under a
    declared, validatable schema; no exceptions, and the edit-time
    check is that no bare-keyword key ever reaches a schema or a
    transaction. KEYWORD VALUES may stay unqualified where they map
    1:1 to a dependency's own vocabulary (core.async's
    `:io`/`:compute`, kondo's finding types) — the seam's name
    survives verbatim, and validation comes from the declared
    `[:enum …]` on the namespaced attribute, not from qualifying the
    member. The earlier storage-translates clause is WITHDRAWN; the
    ~45-file enum sweep is off the atomic pass.

50. **Store the full parse — the indexer is a bridge, not a curator**
    (owner, 2026-08-29): clj-kondo is the one parser, and its analysis
    output is stored COMPREHENSIVELY as namespaced facts instead of
    hand-picked projections — every cherry-picking omission has
    returned as a defect (the macro flag, M6's missing shape,
    location-less call sets). Concretely: var-definitions keep every
    field kondo emits (defined-by, fixed-arities, varargs-min-arity,
    macro, spans); VAR-USAGES BECOME ENTITIES — one per usage with
    from/to refs, the called arity, and source location — replacing
    the reduced `:seon.fn/calls` set as the authority (a calls-shaped
    set stays derivable as a query); namespace-usages, keyword sites,
    and protocol impls land as their own families. Field names follow
    kondo's own vocabulary under our namespaces (the seam's names and
    semantics, rank-2 naming law); new attributes are born compliant
    with rulings 47–49 (symbols as symbols, namespaced keyword
    values). Volume is datom-cheap (usage entities are the big family;
    prior measurement ~26k core usages alone — well within Datahike's
    ordinary range, and the store's growth problem is blobs, not
    datoms). SEQUENCE: after the orchestrator's atomic identity pass,
    so the new families are born on clean foundations; the render/help
    machinery (mention tracing, error adjacency, composition join,
    tests-reaching) consumes the richer edges instead of re-deriving.

51. **The graph closes over code, data, render, and errors; agent
    forms keep linking it; self-improvement is a derivation** (owner,
    2026-08-29): the program graph must store enough to answer the
    context-generation question BY QUERY. Six edge families close it:
    code→code (ruling-50 usage children), code→data (the 42c tx-meta
    eval stamp + the eval's usages = "what function made this datom"),
    data→readers and data→faces (declared on family schemas, 36/43),
    forms→graph (SETTLED RUN FORMS CARRY USAGE CHILDREN — resolving
    the bridge design's open question 1 YES: every form an agent
    writes accretes located, arity-exact edges, so each turn links the
    graph further), and errors→sites (error facts joined through the
    failing eval's usages land on the exact call site). CONSEQUENCE —
    self-improvement tasks are DERIVED, never enumerated: "which of my
    namespace's functions do other agents call most, and which of
    those calls recur in errors" is one Datalog query over usage
    children + error facts; a namespace-agent turns its top rows into
    my.plan items `:about` those functions and works them — many
    agents, own namespaces, in parallel, no scheduler. The derivation
    ships as a demonstrated query (help/recipes), not a new mechanism.

52. **View 1 is THE focus: fully regenerated, stable context; view 2
    is parked** (owner, 2026-08-29): context is regenerated whole
    every turn — explore the graph from the agent's perspective, and
    the transcript is a PROJECTION: one render function recursively
    calling render functions (the eval history renders in (basis,
    ordinal) order like any other data). STABILITY IS THE PROPERTY,
    NOT A MECHANISM: because the derivation is a pure function of
    facts and facts only accrete, two consecutive regenerations with
    no new facts are byte-identical, and with new facts the prior
    prefix is byte-identical — caching falls out; no replay/frontier
    split, no settled-set bookkeeping, no retained prompt bytes.
    Falsifier: **P-STABLE-REGEN** — regenerate twice at one basis
    (byte-equal), transact one fact, regenerate (old prefix
    byte-equal, new bytes appended only). Drives test with full
    per-turn regeneration from day one. View 2 (extending an existing
    context believably via generated-by + time diffs) is PARKED —
    built only if view 1's stability fails in practice. Platform
    repairs and bad-design fixes continue alongside as enablers.

    **52a — `(help)` is the render of a DERIVED COVERAGE SET** (owner,
    same day): help is not authored text but
    `(render (coverage db agent))` — the seed of the mention fixpoint.
    Coverage's five derived sources: self (ns docstring + publics),
    the trigger, the data neighborhood (affordance lines), the
    PROTOCOL VOCABULARY (the symbols the run loop's own code consumes
    when parsing replies — post-bridge, a usage query over the loop
    itself, so the system's protocol section derives from the
    system's source), and the injected world (the ctx bindings' rows,
    47). Experience stays DERIVED, never stored: ordering =
    names-before-use fixpoint; salience = cross-agent usage frequency
    with recency decay + error adjacency (51); teaching-state = this
    agent's own evals and errors (correct use → near-silence, errors
    → doc + schema + a real test as the demonstration via
    tests-reaching). No stored salience, ordering, or curriculum —
    those are queries or they rot.

    **52b — generation is BACKWARD, demand-driven; the goal is the
    seed** (owner, same day): generate from the END — the trigger +
    standing goal — and chain backwards: every entry's DEMANDS are the
    names its form and rendered result mention (usage children +
    :symbols + keyword families) and the data identities it
    references; each unmet demand generates its enabler (doc / schema
    render / affordance line), recursively; demands bottom out at the
    axioms (injected bindings + the intro) and at ALREADY-SATISFIED —
    the agent's stored evals are satisfiers (correct prior use demands
    nothing; a prior error demands the demonstration). Order is the
    topological sort of the demand DAG, dependencies before
    dependents, goal last — tail recency becomes a theorem. Nothing
    re-executes: history renders from stored results; defs restore
    from facts; faces stay invisible (registry-resolved at render).
    (help) is not special: it is the demand closure at empty history.
    STABILITY: backward generation governs ADMISSION; retention is
    MONOTONE between compactions; compaction re-runs the pure demand
    closure from current goals — which is §5's liveness rule: demand
    and liveness are one computation run at two moments.

53. **Faces return FORMS; context entries are evals all the way down**
    (owner, 2026-08-29): the goal of `:seon.render/ai` is not a text
    representation but the FUNCTION + ARGS whose execution yields it —
    the generator composes `(face-sym <read-form>)`, executes it, and
    the entry is that form with its stored result; `/html` identically
    (forms yielding hiccup). Generated render forms are SAVED and
    kondo-parsed like settled forms (51), so everything ever shown to
    an agent accretes usage edges — rendering, teaching, and
    graph-linking are one mechanism. Args are explicit in the form;
    ambient db rides call preparation with the basis rendered beside
    the result (17), so any context line replays verbatim. The schema
    property's split codifies: STRING = direct content, instruction
    entities only; SYMBOL = the face function the generator composes.
    Defaults: a function renders as `(doc 'sym)`; a namespace as the
    dir composition (46); a claimed family as `(face-sym <read>)`; the
    agent's namespace root as the opening composition. PRIORITY
    CHUNKING BY PROVENANCE: the end block is the agent's own evals
    (newest last); priority explanations are the data the agent
    CREATED (the 42c write-stamp join) and its own namespace's
    functions — self first, world by graph distance, never curation.

54. **The write door, the missile rule, and the steward scenario**
    (owner, 2026-08-29): (a) the GRAPH BOUNDARY is the discovery
    boundary — data enters an agent's world only through `transact!`,
    and a graph write must carry its provenance (eval ref → agent,
    function + explicit args, the tx as the replay identity) or be
    REFUSED at the seam; in-graph ⟹ auto-discoverable, outside ⟹
    invisible to context by design. (b) Explanation speaks the READ
    vocabulary: the read-recipe belongs to the FAMILY (its declared
    reader/face), never the writer — send writes, context says
    `(inbox)`; a defn's `:seon.render/ai` metadata for its result
    family is code, contract, and context-presentation in one act.
    (c) EXECUTE-VS-REPLAY: a composed form with a matching stored eval
    pulls the stored result back into the SCI context — never re-run;
    absent → execute and mint. The guard is the program graph's
    `:seon.fn/external-sink` fact: pure reads/renders re-derive freely
    on regeneration; any form whose closure touches an external sink
    is REPLAY-ONLY — missiles fire once because the graph knows which
    forms are missiles. (d) Compaction = retract/supersede the agent's
    context evals and regenerate; a fresh agent is regeneration with
    zero evals — one function. (e) Ordering: ref distance CHUNKS
    (0 = self + own evals as the end block; 1 = pointed-at/created;
    2 = the world using it), 52b demand topology ORDERS within.
    (f) THE STEWARD SCENARIO is the drive target: a namespace agent
    whose opening is per-function name + docstring first line +
    concise in/out, plus the two derived stewardship rows — who uses
    your functions (51 analytics) and where they hurt (error
    adjacency) — which ARE the self-improvement queue; helping other
    agents and users falls out of working the hot, erroring rows.

55. **Instance args: the pull supplies instances, contracts supply
    calls** (owner dialogue, 2026-08-29): the generator never guesses
    args — acquisition discovers CONCRETE identity values on ref
    edges; a reader's declared input schema is satisfiable from
    exactly two sources — ambient (db, self via call preparation) and
    the discovered identities, plugged into the parameter slot the
    contract names. Type-level fit (output-refs → family) × instance
    knowledge (the pull) = the composed call. COLLECTION-FIRST:
    a collection-shaped edge selects the collection reader — one
    `(inbox)` explains all N and teaches the reusable command;
    instance reads `(read id)` are OFFERED dig-ins, auto-promoted only
    by demand (the trigger, a mention) — never one read per item
    (M9). Fit = family match + input satisfiability + SHAPE agreement;
    ties loud (43); zero fits → the floor identity pull is always
    constructible since the pull holds the identity attribute — the
    ladder is total. M6 CLOSES WITHOUT NEW DECLARATIONS: output shape
    (collection-of vs single-of family) derives at registration from
    the declared Malli output form the registry already stores — one
    derived fact at the schema seam, no author burden. Writers never
    appear in explanations by construction: the family's reader is
    selected from the recipient's side regardless of who wrote.

    **REOPENED (owner, 2026-08-29 late):** the reader-centric spelling
    of 53–55 (`(face-sym <read-form>)`, collection-first `(inbox)`,
    the selection ladder's remnants) is NOT settled. The owner's
    current direction — "it's all database queries rendered through
    the render function; functions designed for a query-always
    approach; well-named render functions as the guide; diffs that
    work for everything, at the query OR the output level" — is to be
    explored deliberately with the next agent, unbiased. See
    [context-as-queries-handoff-2026-08-29.md](context-as-queries-handoff-2026-08-29.md)
    §2. What stays grounded: results as stored data (48b/53), the
    missile rule (54c), identity permanence (47), read vocabulary over
    writer (54b), stability as a property (52).

56. **REPL-first context — the four forks ruled** (owner, 2026-09-02):
    (a) render functions are ordinary functions, eligible by
    declaration; specificity order = inline on the value → the agent's
    own namespace → the schema's general face (the property stays as the
    fallback rung) → floor; (b) the delta is the `since` spelling with a
    diff under the hood that ALWAYS shows additions and deletions —
    history = initial value rendered, then diffs against the last shown
    basis; (c) rendering is invoked by calling the render function on
    the data, and the print floor (REPL and UI) looks up the most
    specific renderer; (d) pulls are nested/recursive per Datahike's own
    pull grammar, taught correctly — no `[*]` magic. Recorded in the
    [design draft §9](repl-first-context-design-2026-09-02.md). The
    reframe itself (agent dropped into its REPL; data + best render fn +
    doc/dir teaching; `doc` polymorphic over anything and lists; we own
    and tailor every tool) is ruling 56's preamble and supersedes the
    reader-centric spelling of 53–55.

57. **One generation, two projections; nothing hardcoded; turtles all
    the way down** (owner, 2026-09-03): the context walk rendered
    through `:seon.render/html` IS the entire system UI — every page is
    the `/html` projection of the same entries the walk emits for that
    root; the agent's context is the `/ai` projection. No authored page
    layouts, block lists, or per-kind templates anywhere. Every agent's
    context and page differ because their neighbourhoods differ — the
    intended behavior. Render functions are contracted `defn`s (facts),
    their selection is a query, their output is a value the walk
    renders, the page showing it is itself an entry. The generation is
    spelled as forms that are evaluated to surface the data AND to
    explain how it was found: `dir` of every namespace an entry
    references and `doc` of the functions it calls and of the render
    function that prints it come BEFORE the entry; a previously emitted
    query is shown as its diff against the recorded basis. Behavior
    authority: [repl-first-behavior-2026-09-03.md](repl-first-behavior-2026-09-03.md)
    §B10, §G. Phase rule: behaviors first, implementation talk only
    after every ❓ in that file is gone.

58. **Help bootstraps; renders accrete across namespaces; full
    regeneration first** (owner, 2026-09-03, question round on the
    behavior spec): (a) `(help)` BOOTSTRAPS the whole context: it
    explains every core function the agent is about to see, and it is
    GENERATIVE — index every symbol, var, and keyword the context walk
    and its renders produce, sort them, and derive both the optimal
    order of the generated forms and the help that explains those root
    commands; different agents get different helps (extends 52a).
    (b) Cross-namespace reuse: a render function written by ANOTHER
    agent for a family IS preferred in a viewing agent's context when
    that agent has no more specific definition of its own — the system
    grows by accretion and no agent starts at zero; the whole rendering
    order must be derivable by ONE rule-based query (the orchestrator
    designs the query and the facts it needs). (c) Render provenance
    rides the EXISTING after-the-value comment (the result handle
    comment) — add "rendered by <fn>" there; one mechanism, verified,
    never a second. (d) Compaction = a fresh session with NO lost
    context; the system is DEVELOPED FOR compaction-on-every-turn first
    (full regeneration each turn, even with poor caching) so context
    generation is nailed; incremental diffs (56b) come later — B5 is
    demoted to a later wave.

59. **One design before any deletion; floor-only provenance; result
    handles are real symbols; distance-then-newest** (owner, 2026-09-03,
    on the parallel-paths register): (a) NOTHING IS DELETED until ONE
    design document explains, on one platform, what is refactored, what
    is deleted, and what is added — "we keep building parallel systems
    doing different things; I want ONE design done correctly"; the
    register's three-deletion order is accepted as INPUT to that document,
    not as authorization. Understand the eval-result persistence (results
    saved, blobs, recall) before touching it. (b) Render provenance is
    expressed ONLY when a value hits the print floor (`/ai` or `/html`) —
    so the agent sees no magic; an explicit call to a render function is
    just a function call and is neither enveloped nor recorded; if the
    floor's provenance lands as metadata beside the stored eval result,
    fine; never a provenance fact per function call. (c) The
    `result/<id>` handle after a value is REVIVED as a REAL symbol the
    agent can reference (`(get-in result/k7f2 […])`), encouraged; on
    resume the system transparently determines whether the value is still
    available and serializable and shows the handle only then; find the
    SIMPLEST mechanism (SCI ctx binding vs reader) and remove every other
    code path doing something different — src-old is dead, quarry its
    lessons only (`src-old/seon/repl/parse.cljc:437-452`,
    `src-old/seon/agent/ctx.cljc:670-735` at `9e44815f5`). (d) Among other
    agents' render functions at equal coverage: CLOSEST namespace wins
    (a required namespace is one hop, its requires two, …), then newest.

60. **What a render function is; tokens, not characters; no coding until
    the design convinces** (owner, 2026-09-03): a render function is ANY
    function whose inputs are satisfiable from the value being rendered
    plus what call preparation can inject (the current database, the
    environment), and whose output is `:seon.render/ai` (MUST return a
    string) or `:seon.render/html` (MUST return hiccup); separate
    functions per projection are normal; a function needing anything else
    is not a candidate; several candidates are a SORT — the whole
    selection is one database query. A value may carry the render inline
    as content or as a function symbol. Result-handle ids derive from the
    eval identity. The per-value print budget is a CONFIG FACT in TOKENS
    (5k to start) — never characters, anywhere the owner reads. NO
    IMPLEMENTATION until the one-platform document shows the goal, what
    exists, what is kept/changed/deleted, and convinces the owner that the
    context is nested render functions with nothing hardcoded; platform
    bug fixes continue only on code that stays.

## Parked explicitly (owner said not yet / needs design first)

15. **R3**: `data/clusters/store` path + operator noun cleanup — priced
    options owed to the owner before any edit.
16. **Effectful re-execution tag** (`idempotent-read`) — pre-approved
    design, lands only with its never-lie falsifier when a capability
    leaf first declares it (R50, runtime).
