---
type: prd
status: active
tags: [prd, agent, context, data-model]
---

# The plan: resurrected mechanics, context-integrated

(Owner ruling 2026-08-13: the one task noun is **my.plan** — ruling 49's
semantics unchanged, its noun amended; the colliding run-internal
`plan-tx`/`plan-digest` spellings rename to `sources-*` in R2.)

Owner-directed 2026-08-13: resurrect the proven `my.plan` lineage mechanisms
in fact-first form under ruling 49's frame, and make the plan a first-class
citizen of the generated-context system. This supersedes the implementation
PRD's thinner Phase 5 sketch
([evolving-session-implementation-2026-08-12.md](evolving-session-implementation-2026-08-12.md));
the quarry evidence is
[plan-code-archaeology-2026-08-13.md](../research/plan-code-archaeology-2026-08-13.md)
(read end to end before implementing — every borrow below cites it).

## The frame that binds everything

Ruling 49: the plan is THE one task system — **derived obligations ∪
authored item facts** — and completed items vanish from its current-state
render. Ruling 47: what survives rebirth is a fact with a declared render;
the render IS the compaction. Ruling 48: everything re-bootstraps from
current facts. The union guard is structural: derived arms (unanswered
messages, open runs, failing test-result facts per ruling 40) are QUERIES,
never item facts — so no authored edit can delete an obligation by
omission.

## Borrowed, by exact reference (resurrect, don't reinvent)

1. **The derivation rule set** — quarry
   `099cdfa99^:src-old/my/plan/internal.cljc:24-46`: `blocked` (stored
   blocker OR open dependency), `ready` (open leaf unblocked; open
   NON-leaf with no open descendants becomes ready for its own
   verify-and-close — keep this detail), `open-work`, `leaf`, `descendant`
   as one readable Datalog rule set over authored items. Statuses are NOT
   stored (the old `:open/:active/:done/:blocked` enum dies): done-ness is
   presence of `:my.plan.item/completed-at`; blocked-ness derives from
   `needs` edges; the ONE optional authored state is the current-position
   anchor (an agent-level ref to the item being worked).
2. **Item facts** — from `099cdfa99^:src-old/my/plan.cljc:24-43`, trimmed
   to presence-based: identity (string id, unique), title, optional
   description, agent ref (items scope to one agent), optional parent ref
   (decomposition), cardinality-many `needs` refs (dependency), optional
   **subject refs** (namespace / message / function / any entity the item
   is about — the forward-looking member; see T3), optional
   `completed-at`, optional expected-result. No pace, no stored status, no
   escalation members (archaeology §7: scheduling/escalation stays out).
3. **Whole-value reconciliation** — the archaeology's strongest quarry
   (§"Whole-document reconciliation was real"): `plan!` accepts one
   complete authored tree (labels + `:after`-style references), compiles a
   PURE diff against current item facts into ONE transaction, refuses
   ambiguity loudly, reports explicit add/change/retract counts, and
   converges to a no-op on identical input. CAS-fenced on the read basis
   (§6 digest/fence lesson). It touches ONLY authored item facts — the
   union guard above makes derived obligations unreachable by
   construction.
4. **Bounded identity-preserving current views** (§5) — the declared
   `:seon.render/ai` + `/html` current-state render: remaining ready/
   blocked work with the anchor first, recent completions (bounded),
   older completions as an elision value with requery identity. Completed
   items vanish from membership; the render is the rebirth contract.

## New — the context integration (the forward-looking half)

- **T-form**: the plan declares `:seon.render/form` — the READY query is
  the form; the generated opening shows `(my.todo/ready)` (or the
  declared listing form) with its real receipt like any collection, and
  the since-basis delta algebra applies with zero todo-specific code.
- **T3 — intent-directed membership (GATED on one owner ruling):** ready
  items' subject refs join the walk's pull membership, so the context an
  agent wakes into contains the units its next work is ABOUT — root's
  system view and a task agent's focus fall out of the same rule.
  Admission rides the EXISTING beyond-closure token budget (the one
  `:seon.config.bootstrap/beyond-closure-token-budget` dial) — no new
  dial; intent members compete through the same gate as every
  beyond-closure declared render. Falsifier: an agent with no subject
  refs has a byte-identical opening; with refs, the delta is exactly the
  admitted subject units within budget. IMPLEMENT LAST, behind the other
  phases, and only after the owner confirms the membership semantics.

## Phases and ownership

| Phase | Scope | Exit |
|---|---|---|
| T1 | `resources/seon/schemas/my.plan.edn` (registry-query-first; open maps; declared renders per ruling 35), `src/my/plan.clj` (`add!`, `complete!`, `ready`, `todo` read), the quarried Datalog rules, focused tests incl. the parent-verify-close property and a rebirth property (current facts alone render the honest current state) | Focused green; the derivation rules quarried, not reinvented |
| T2 | `plan!` whole-value reconciliation + CAS fence + union guard | The archaeology's reconcile properties as tests: pure diff, explicit counts, ambiguity refusal, no-op convergence, derived arms untouchable |
| T3 | `/form` + membership integration | Held for the owner's membership ruling; the falsifier above lands with it |

Model the schema/verb/render idiom on `my.note` (`9b26cb7a7`) — the
freshest ruled example of exactly this shape, including per-shape render
functions.

## T3 amendments — 2026-08-13 evening (owner rulings, conversational)

T3 is UNBLOCKED: the membership ruling landed as ruling 2 of the
2026-08-13 batch (approved as specced above), and the producer-side
kind→class migration (W2 `2aacc58fe`–`5423c5a10`, W2b `d5f7c7a08`) freed
the owned files. Four rulings from the evening's design session ride into
T3's scope, recorded in the
[design-ideas ledger](design-ideas-ledger-2026-08-13.md) entries 17–20:

1. **Render the basis `t` beside every entry's result** (entry 17). The
   receipt already records the read-basis transaction; T3 renders it so
   agents anchor their own temporal reasoning on a shown value.
2. **No undeclared temporal keys** (entry 18, amended precision): the
   uniform read-surface convention is a declared `:seon.db/db`
   database-value argument plus fully namespaced registered options
   (`my.message/inbox` at HEAD is the canonical example). T3 extends the
   pair to read surfaces it touches; it never adds an undeclared key.
3. **Nothing rendered that will not run verbatim for the agent**
   (entry 19): generated delta/demo entries are ordinary replayable
   forms; the falsifier is replaying generated forms in a fresh fork.
4. **`seon.db/diff` (entry 20)** — the one generic delta helper over any
   form whose function declares a `:seon.db/db` input; final contract
   PENDING the live REPL exploration
   (`../research/generic-diff-exploration-2026-08-13.md` when it lands) —
   implement in T3 only after that note settles the shape; the demo-vs-doc
   rule (docs for lookup, demonstrations on first real use via `:about`
   intent + no prior demonstrating artifact) governs how subject units
   render either way.
