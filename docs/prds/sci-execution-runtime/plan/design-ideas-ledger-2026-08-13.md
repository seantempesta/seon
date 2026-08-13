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
13. **Derive-or-die for every mirror.** The session's meta-lesson: every
    hand-maintained mirror of the tree (member lists, IN-FLIGHT blocks,
    vocabulary, counts in prose) must be derived, checker-enforced, or
    dated — candidate for promotion from practice to explicit AGENTS.md
    law text.
14. **Store-bloat key-prefix census** on next occurrence (issue filed) —
    plus whether the exclusive sweep should run on a schedule NOW rather
    than waiting for the maintenance portfolio.

## Parked explicitly (owner said not yet / needs design first)

15. **R3**: `data/clusters/store` path + operator noun cleanup — priced
    options owed to the owner before any edit.
16. **Effectful re-execution tag** (`idempotent-read`) — pre-approved
    design, lands only with its never-lie falsifier when a capability
    leaf first declares it (ruling 50).
