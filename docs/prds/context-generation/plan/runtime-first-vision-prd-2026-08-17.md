---
type: prd
status: draft
tags: [prd, agent, runtime, database, context]
---

# Runtime-first — the system designs itself at runtime; files are an export

*Rough PRD, owner + orchestrator dialogue 2026-08-17. Companion to the
[one-renderer PRD](one-renderer-prd-2026-08-14.md); this document owns
the no-files vision and the session-dataflow compaction design. Status:
draft for later work — recorded now so the seams we refactor under the
one-renderer waves stay aligned with this direction.*

## The vision

**The system has no files.** Files are an afterthought — an export to
disk so you can build a jar. The living system is: durable facts in the
database (source, contracts, schemas, tests, defs, sessions), live
behavior in rebuilt environments, and the ability to REBUILD any
environment from what we can serialize and re-execute (the rebirth
axiom, generalized to the whole system). `current-src` is the one
system-wide fork point: anyone wanting a fresh environment — across
clusters — forks its published commit. The edit hook remains the
file→facts bridge for the bootstrap era and the export path back to
disk; it stops being the primary authoring seam as agents author facts
directly.

Design consequences to hold every seam to:

1. **Store generators, never object graphs.** The SCI ctx is never
   serialized; its GENERATORS are the facts (`:seon.fn` source rows,
   `:seon.ns` requires, `:seon.def` defs-and-atom snapshots, the
   schema projection's EDN) and rebuild = re-execute them in
   dependency order. "Store the sci context in the database" resolves
   to: make sure every ctx is a pure function of facts. This is
   already true for agent forks (rehydration); the remaining gap is
   first-party host namespaces, whose generator is still a file — the
   export inverts: the file becomes the artifact derived FROM facts.
2. **konserve alignment.** Facts and blobs already live in
   content-addressed immutable storage with GC. Big values ride blobs;
   a blob is collectable exactly when no fact references it. We do NOT
   analyze JVM memory for liveness — "last referenced" is a Datalog
   question (the max basis-t of any edge into a node), never a heap
   walk.
3. **Merging is fact-merging** (2026-08-17 dialogue): identities are
   the merge keys (`:seon.fn/sym`, `:seon.schema/key`); candidate
   work lives on a Datahike branch + candidate ctx; the gate is
   derived (dependents' tests via `tests-reaching`, the contract
   accretion differ — widen=accrete=automerge, narrow=break=refuse —
   spec coverage queries); merge = same-identity upserts + host
   materialization while host namespaces still exist.

## The session dataflow graph — the integration seam

The context-ordering question and the compaction question have one
answer: **the transcript is a dataflow graph, and both generation and
compaction are reachability over it.**

Every eval entry already records or can record at settlement, as
ordinary facts (no JVM introspection anywhere):

- **uses-var** — `form-symbols` of the form ∩ known defs/program
  symbols, alias-resolved through the turn env;
- **uses-result** — `result/<id>` and entity identities referenced in
  the form (extractable by the reader; `print/references` already does
  the result side);
- **reads** — the call's Datahike read evidence (entity/attribute
  sets, already retained for render staleness);
- **writes** — the entry's transactions (provenance already stamped);
- **defines** — the `:seon.def` / `:seon.fn` facts it settled;
- **requires** — the namespaces it introduced into the env.

All edges are facts; liveness is Datalog.

## Generation, diffs, and compaction are ONE algorithm

The env-grounded fixpoint generator (2026-08-17 dialogue, extends
`ordered-episode`): a candidate form is ready when its parse-closure —
alias-resolved through the LIVE turn env — resolves, and its subject
appeared in an earlier settled value; executing the batch advances the
env, which readies the next candidates; `print/references` over
results extends the frontier. Then:

- **Initial context** = the fixpoint from an empty settled set.
- **Turn diff** = the fixpoint where settled = everything receipted;
  only diff-stale reader calls re-enter.
- **Compaction** = the fixpoint where settled = the LIVE SUBGRAPH
  only: recompute reachability from the roots and REGENERATE the
  episode containing exactly the live entries (re-derived at the
  current basis), dropping dead subtrees wholesale.

**Roots of liveness:** the current env's defs (rehydrated — a def
still standing is alive by definition); the plan's `:about` subject
refs (externalized intent pins its evidence); the open run and the
arrival tail (recency floor, config); unresolved errors. **Dead by
construction:** an error entry nothing later referenced (the owner's
exact case — errors are leaves that never reconnect; they age out by
unreachability, no turn-count heuristic needed, though a config floor
may delay collection); one-off experiments whose results no live
entry uses; superseded intermediate states of data that later
transactions replaced. "The statements that were the main focus of
the session" = the maximal live dataflow chains into the roots —
derived, not summarized: compaction NEVER paraphrases; it re-derives
live facts at the current basis and drops the dead, so form honesty
survives compaction.

**Requires and cross-namespace calls integrate by domination:** a
require entry is live iff a live entry uses its namespace's symbols;
a def entry is live iff the def still stands or a live entry uses it —
and both can be REGENERATED compactly from their facts (the def fact
is authoritative; the episode shows `(def x …)` with its current
value, not the archaeology of how x got there). Previous-session
requires persist as agent-entity facts (the settlement loop already
ruled), so rebirth replays them from data.

**Cost stance (owner):** regenerate-per-turn is accepted as
potentially cache-inefficient; correctness first. Note: turns without
compaction remain append-only (prefix-stable, cached); compaction is
a DELIBERATE cache break, priced and observable (the capture facts
record it). Session archival (the session family,
[one-renderer PRD §7.1](one-renderer-prd-2026-08-14.md)) composes:
compaction within a session, archival between sessions, rebirth from
facts — three scales of the same rebuild-from-generators move.

## Graph enrichment — ruled in scope (owner, 2026-08-17)

The rich graph is mostly recorded already (`:seon.fn` calls/keywords/
arity contracts, `:seon.ns` requires, `:seon.test` subjects, AEVT for
data-side keyword usage). Five edge families are cheap at the existing
indexing/settlement seams and each unlocks a named query; the owner
ruled them into this work. Each lands DERIVED (never hand-maintained)
with its query and a drift regression:

1. **Core-call edges** (kondo already resolves them) → makes the
   seam censuses ("no printing outside the printer") non-vacuous.
2. **`:seon.schema/references`** (parse stored forms once at
   declaration) → schema closure and impact analysis become Datalog;
   the keyword graph's spine.
3. **Per-arity OUTPUT refs** beside input refs → "which functions
   produce a value carrying this key" — the efficient contract-fit
   index for the context generator, and provenance-of-data queries.
4. **Session dataflow edges** (uses-var / uses-result / reads /
   writes / defines / requires per receipt — computed at settlement
   already) → liveness, compaction, last-referenced-at-basis.
5. **Test→schema edges** (from subject contracts) → "which schemas
   lack generative coverage," a merge-gate criterion.

Serialization law restated for this section: vars are data (the SCI
env is a map of Vars); values serialize as their GENERATORS (source to
re-execute); live host references are `:seon.schema/identity-only` —
identity plus rebuild recipe, never object graphs.

## What this PRD will need to settle later (not now)

- The exact edge schema for the session graph (which edges are new
  attributes vs derived at read time — derive-or-die applies).
- The liveness roots as config facts (recency floor, error retention).
- The host-namespace generator inversion (facts → exported file) and
  what `bin/seon init` becomes in a no-files world.
- The candidate-branch lifecycle + accretion differ (shared with the
  merge design).
- Whether compaction emits a `supersedes` revision of the session (the
  curation machinery's shape) so the pre-compaction transcript stays
  forensically reachable. (Likely yes — nothing is deleted, the
  generation is superseded.)
