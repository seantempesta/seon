---
type: prd
status: active
tags: [prd, agent, context, architecture]
---

# Design ideas ledger — 2026-08-13 session

Every design idea that surfaced in the owner session, with its status.
AWAITING-OWNER rows are the ones he may not have responded to yet — each is
one decision away from queued work. Ruled items cite their record.

## Ruled today (no response needed)

1. **my.plan is the one task noun** — semantics of ruling 49 unchanged
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
   self-fade via ruling 46.
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
    (authorship fence + ruling 46 self-fade + supersedes edges). What
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

## Parked explicitly (owner said not yet / needs design first)

15. **R3**: `data/clusters/store` path + operator noun cleanup — priced
    options owed to the owner before any edit.
16. **Effectful re-execution tag** (`idempotent-read`) — pre-approved
    design, lands only with its never-lie falsifier when a capability
    leaf first declares it (ruling 50).
