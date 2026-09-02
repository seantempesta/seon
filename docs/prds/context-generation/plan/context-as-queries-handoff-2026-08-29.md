---
type: prd
status: draft
tags: [prd, agent, context, render, architecture]
---

# Context as rendered queries — the handoff for the next session

*Written 2026-08-29 (late) for a FRESH session. Read it whole before
anything else. This is a THINKING document: the owner explicitly does
not want "go go go" — the next session explores the idea below to its
foundations, names its trials, and does the platform work that has to
finish first. Nothing in the generator gets built until the owner says
the design is settled.*

## 1. The goal, in one paragraph

Every agent's context should be **discovered from the agent's own
entity outward** — its namespace, the facts pointing at it, the data
it created, the world it uses — never authored, never hand-assembled.
It must generate **efficiently both fresh and accumulating**: a brand
new agent, a compacted agent, and turn N of a long-lived agent are the
SAME derivation over the same facts. If that derivation is a pure,
deterministic function of the database value whose output only ever
appends as facts accrete, then prefix stability — and therefore
provider caching — is a *property* we get for free, not a mechanism we
maintain. That is ruling 52 (view 1 only; view 2 — "extend an
existing context believably" — parked unless view 1's stability
fails).

## 2. The idea to explore now: stop being clever — it's all queries

The owner's proposal, in his words: **treat the whole thing as
database queries rendered through the render function.** The agent is
competent at querying the database; so is the system. Context is a
vector of `(query → rendered result)` pairs. When new data arrives we
don't need cleverness — we generate the query that gets the new data
and render it. Teaching is the same: the docstrings and schemas of the
functions the queries use, rendered.

**Do not treat anything below as settled — the owner wants to think
this through WITH the next agent. Bring the design space, not a
design.** Points he raised, deliberately left open:

- **Functions must be designed for the query-always approach.** Family
  readers like `(inbox)` may be LESS useful now; the shape he sketched
  is a generic query plus a well-named, intuitive render function:

  ```clojure
  ;; get all the messages
  (seon.messages/render (pull … :seon.messages …))   ; exact spelling open
  ;; later: get new messages
  (seon.messages/render (diff …))
  ```

  where the render function is the *guide* — pretty-printing, sorting,
  nice timestamps, showing what matters — and naming carries the
  discoverability. Whether per-family readers survive at all, or only
  the generic query + named render pair, is an OPEN QUESTION.
- **Diffs have to work for everything.** Open alternatives to weigh
  honestly, each with its consequences: (a) diff at the QUERY level —
  the same query at two bases, with render functions that accept a
  diff and explain it clearly; (b) diff at the OUTPUT level — diff the
  rendered/returned result against the last time THIS agent called the
  same thing; (c) some families needing a since-shaped query instead.
  The stored-result projections (48b) and the existing `seon.db/diff`
  helper are inputs to this decision, not the decision.
- **How queries are spelled** for the agent matters as much as what
  they return: the owner "forgets how we are doing this" for the pull
  — that is itself a finding about discoverability. What does a
  competent-but-new agent type first?

What IS grounded already (use it, don't re-litigate it): results are
stored data rendered once (53/48b); regeneration re-derives pure
queries and replays anything effectful (54c, decided by the graph's
external-sink facts); identity rows never retract (47); explanations
speak the read vocabulary, never the writer's (54b); context stability
is a property to prove (52), not a mechanism to maintain.

## 3. Trials and tribulations — what we learned the hard way

Read these as constraints the new design must respect, each bought
with real time:

1. **Authored faces were brittle.** `:seon.render/form` (an authored
   form per family) died (44): forms must be CONSTRUCTED from
   contracts and provenance, never written by hand. Same fate for the
   narration renderers (66.7% of agent context was English narration;
   results-as-data, ruling 53) and the authored opening prose
   (`walkthrough`, situation-form, `getting-started-text` — the
   [hardcoded-context census](../research/hardcoded-context-census-2026-08-28.md)
   found 34 sites; ruling 43's "no hardcoded openings" claim was
   REFUTED at HEAD).
2. **The reader-selection ladder was the complexity trap.** Family
   fingerprints, producers-of-key inference, distance weighting,
   auto-run-vs-offered — the mechanics register grew fifteen unruled
   rows (M1–M15). "It's all queries" dissolves most of them; the ones
   that survive are M13 (the diff result has no designed face — it
   renders as editscript soup) and M6 (output SHAPE — collection-of
   vs single-of family — is derivable from the declared Malli output
   form at registration, ruling 55).
3. **Band-aids over incomplete population.** A `(help)` call edge
   dangled because macros were never indexed; my first fix minted
   tempid companion rows at the transaction seam. The owner rejected
   it: the disease was that the SCI context resolved names the
   database had no rows for. Ruling 47's population invariant fixed
   it at the source — every ctx-resolvable name has a row, minted
   where the ctx learns it; identity rows never retract. Lesson: when
   a ref dangles, the population is incomplete; fix creation, not
   transaction encoding.
4. **Mirrors rot; derive at the authority.** Five independent classes
   were one disease (fixture rosters vs the config compiler,
   existence pre-reads vs the writer's upsert, a reply pipe vs the
   process's exit, a lint cache vs canonical analysis, a dev-class
   cache vs the dependency closure). It is now law in AGENTS.md §2.
   For context generation it means: NO stored salience, ordering,
   curriculum, or prompt bytes as authority — derive them or they lie.
5. **Slow is a bug, and it hides as a hang.** Three members this week:
   the walk's selector recursing past the node cap (50 s → 8.5 ms);
   the analysis prelude rebuilt per settled form (25 minutes for one
   curation replay → cached per program population); the bootstrap's
   distance-3 walk re-derived per advance (499 units / 28 s → distance
   2, 109 units / 1.2 s). Any per-turn derivation over the graph must
   be measured at the ruled population size (548 namespaces, ~840
   function rows, 4,481 call edges) BEFORE it becomes a design.
6. **Masking is the top systemic risk.** Four Aug-14 breakages
   (prompt fixtures, fn-test expectations, the db diff key, the edit
   hook's advisory feedback — dead for fifteen days) hid behind a
   twelve-day bulk-tier blackout caused by a require race in the test
   runner. Stale-green visibility now exists (`bin/seon status`
   derives "last known green, basis T, N days ago"; unknown is never
   green). Check it at session start.
7. **The parser already knows.** We cherry-picked fields out of
   clj-kondo's analysis for months and every dropped field returned
   as a defect (the macro flag, shape, locations). Ruling 50: store the
   full parse; usages become located, arity-exact component children;
   the stored `:seon.fn/calls`/`keywords` sets die as derivable. The
   design is verified against kondo's own analysis README in
   [full-parse-bridge-design-2026-08-29.md](full-parse-bridge-design-2026-08-29.md).
8. **Symbols are symbols; keys are namespaced.** `:seon.fn/sym` is
   still a STRING (`:seon.ns/name` is a symbol) — the atomic retype
   pass is queued (47/48). Keys always namespaced; keyword VALUES
   follow the dependency's vocabulary (`:io`/`:compute` verbatim, 49
   amended).
9. **Write provenance ≠ read recipe.** Every agent write carries its
   eval in tx-meta (42c) — "what function made this datom" is a join.
   But the explanation of data speaks the READ vocabulary of the
   family (`(inbox)`), never the writer (`send`). Do not let the
   writer's identity leak into how the recipient's context reads.
10. **Effects fire once.** Regeneration re-derives pure queries and
    REPLAYS stored results for anything effectful. This is not a
    policy knob — the graph's external-sink facts decide it.

## 4. What the graph can already answer (leverage), and what it cannot yet

Available at HEAD, verified this session: identity attributes and ref
types (installed schema + registry); the persisted schema reference
graph (3,655 edges); faces declared on family schemas; `:seon.fn`
contracts with per-arity input/output refs; 4,481 call edges plus
core name-only rows; macros as full rows with `:seon.fn/macro?`; all
51 injected REPL bindings as rows; eval facts (form source, ordinal,
`result-edn`, error, `read-evidence`); the eval tx-meta stamp;
`:db/txInstant` on every datom (recency is free); `seon.db/diff`;
admission caps and elision values with requery identity.

NOT yet stored, and needed for "it's all queries" to be total:

- **usage children on definitions AND settled forms** (50/51) — so
  mention tracing, "who calls this", and cross-agent analytics are
  queries, and every agent form links the graph;
- **result projections at settlement** (48b) — the families and
  identities a result references, so "which evals returned messages"
  and "what has this agent seen" are queries (this is also how
  already-satisfied demands are detected without string matching);
- **output shape at registration** (55) — collection-of vs single-of
  family, derived from the declared Malli output form;
- **symbol identities** (47/48a) and the receipts→evals rename (48c);
- **a designed face for diff results** (M13) — the marquee "here's
  what's new" entry must not be soup.

## 5. The platform work that must complete BEFORE generator work

In the owner's ruled order (the [working edge](unsettled.md) is the
live record; this list is its explanation):

1. **The freeze remainder — exactly three named reds** in the bare
   gate: `armed-test/the-first-cluster-proc-fault-at-resume-becomes-a-fact`
   (the injected first resume fault never reaches a terminal worker
   event — a real fault-path behavior question);
   `armed-test/a-message-committed-during-boot-arming-is-conserved`
   (the test conflates run opening with provider progress; rework its
   await onto the run-open fact); and the seed-recorded
   [generated-attempt-traces blocker](../../../seon/issues/generated-model-attempt-traces-diverge-from-durable-facts.md).
   Owner ruling: bare reads FULLY GREEN before any rename lands.
2. **The atomic identity freeze** (orchestrator, quiet tree):
   `:seon.fn/sym`/`:seon.test/sym` string→symbol with the sym↔`/ns`
   drift regression; receipts→evals across ~29 src files and ~10
   schema files (the `seon.maintenance.receipt` family's scope is
   UNDECIDED — ask). Retype + reset, never migrate; `bin/seon init` and
   full gates close it.
3. **The full-parse bridge lane** (50) on the clean identities — usage
   entities, keyword sites, the `:symbols` bucket, deletion of the
   stored calls/keywords sets, the four regressions in the design doc,
   the init timing gate.
4. **Result projections at settlement** (48b) and **output shape at
   registration** (55).
5. **The write door validates provenance** (54a) — a graph write
   without its eval/call/basis provenance is refused, so "in the graph
   ⟹ discoverable" holds by construction.
6. Smaller, but real: the MCP `runtime_status` "missing-projection"
   smell a lane reported; the deferred `effective-config` rows in
   `effect_test`; the 69-GB store-growth class (exclusive-sweep wave);
   the dev-cache `ensure-cache` wiring is landed — verify a run
   transcript names its digest.

Only after 1–4 does the generator work start, and only on the owner's
explicit go.

## 6. Questions for the exploration (do NOT rush these)

1. **What does "the query" look like, and who picks it?** Generic
   pull + named render function, or per-family readers, or both?
   Given a discovered group (attribute + identities) and the registry,
   is there ONE total, deterministic choice — and what is the honest
   base case when a family declares nothing? Draft the alternatives
   side by side before drafting a rule.
2. **History as queries.** The agent's own evals are already
   `(query → result)` pairs. Is every history entry re-renderable from
   its stored result without re-execution, including agent-authored
   effects? What does a stored result need (48b) for its RENDER to
   be re-derivable when a face changes?
3. **The delta.** Query-level diff (same query, two bases, render
   functions that accept and explain a diff), output-level diff
   (against the agent's own last call), or since-shaped queries per
   family? Which one works for EVERYTHING, and what does the rendered
   diff look like so it is not soup (M13)?
4. **Stability (P-STABLE-REGEN).** Regenerate twice at one basis →
   byte-equal; add one fact → old prefix byte-equal, new bytes appended
   only. Where can ordering ever be non-deterministic (ties, salience
   decay, face redefinition)? Each is a rule to write down.
5. **Teaching as queries.** `(doc f)`, `(dir ns)`, a schema render, a
   real test as a demonstration (`tests-reaching`) — when does a
   mention demand which one, and how does the agent's own correct
   prior use satisfy the demand (52b)?
6. **Compaction.** If it is literally "retract the agent's context
   evals and regenerate," what survives (effect results, defs) and
   what is the priced cache break?
7. **The steward drive.** Design the v0 scenario's opening as pure
   queries: the namespace's functions (name + first docstring line +
   concise in/out), "who uses your functions" (51), "where they hurt"
   (error adjacency). Measure it at the real population size.

## 7. Reading order for the new session

1. This file, whole.
2. The [working edge](unsettled.md) — current state and the exact
   freeze remainder; then `bin/seon status` and `git log --oneline -20`.
3. Rulings 47–55 in the [ledger](design-ideas-ledger-2026-08-13.md)
   (bare numbers = this ledger; "R<N> (runtime)" is the old sequence).
4. [render-data-plan-2026-08-28.md](render-data-plan-2026-08-28.md)
   (read under its §0a refocus) and
   [full-parse-bridge-design-2026-08-29.md](full-parse-bridge-design-2026-08-29.md).
5. The five-class synthesis:
   [class-root-cause-synthesis-2026-08-29.md](../../sci-execution-runtime/research/class-root-cause-synthesis-2026-08-29.md).
6. The program README's fuckups section — still true, still relevant.
