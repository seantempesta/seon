---
type: research
status: draft
tags: [research, agent, architecture, runtime]
---

# The generate-code loop — whole-program planner, fault-routed owners

Owner design session, 2026-07-26 evening. ROUGH by declaration — this
records the direction and the composition argument; nothing here is a
committed step until it enters the plan. Prerequisites: step 1
(`seon.effect`, messaging + db, then LLM family), step 4 (terminal
transaction commits program-graph facts).

## The ruled shape

Design B won over a fan-out coordinator: **the planner is one agent with the
complete problem, and it attempts the whole program at once.**

1. A problem/feature is stated well, in words, and committed (markdown in a
   blob + a small plan entity).
2. The planner emits the whole-program change as ordinary transactions —
   `register!` upserts schemas, a `defn` fact overwrites the prior fn fact,
   ns requires merge. The program graph's write path IS the patch language;
   no instruction format exists.
3. Acceptance is derived: affected tests (computed from program-graph edges,
   the `bin/seon test changed` idiom in-system) run at the new basis; results
   are facts.
4. Red facts route by provenance — the fault-routing rule (`4db607f7d`):
   each broken namespace's one assigned agent wakes, with the FULL plan in
   its derived context (an open plan touching your namespace renders into
   your context; nobody is briefed, nobody flies blind).
5. Owners fix locally; their commits are more facts; the loop's parallelism
   EMERGES from routing — there is no dispatcher and no feedback collector.
6. "Done" and "iterate" are derived states: all affected tests green = done
   (the returned diff is the fact-delta between two bases — the database
   computes it); an escalation = the planner wakes and re-plans **at the
   current basis**, which already contains every success the other owners
   committed. "Patch against the new updated codebase" is free because the
   codebase-as-facts has exactly one current value, and `since` queries show
   the planner precisely what changed under it.

## The contract rule (owner, verbatim intent)

The planner may be brutal — but a change to a function or schema is
**rejected IFF an existing consumer would fail under it.** Mechanism, not
review: a choke-point admission check on the terminal transaction, computed
from the program graph (who calls the fn, who reads the attribute) plus the
accretion test (requires no more, provides no less). On detected breakage
the planner chooses: **accrete** (new fn/schema beside the old) or **wake
the consumers** to adapt their dependency — their turn count is their
choice's cost, visible to root.

## The feedback systems, inventoried

All existing or same-day ruled; the loop invents none of them:

| system | role in the loop | state |
|---|---|---|
| program graph (`::calls` sound, `62bc86cb1`) | who consumes what → breakage detection, affected-test selection, multi-owner fan-out | built |
| fault routing by provenance | red facts wake namespace owners | ruled + transport proven (`8fc6c464d`) |
| derived context | the plan renders into every woken owner's context | existing mechanism (reactive context rule) |
| wake/message derivation (`waking-inbound?`, `4dbaeda0e`) | planner ↔ owner communication | built; identity idempotency = step-2 item |
| basis / `since` | re-planning sees the partially-updated world exactly | Datahike native |
| receipts + turn accounting | "one namespace took 10 turns and failed" is a query | built |
| custody (`:db.fn/cas`, one agent per namespace) | no two agents fight over one owner | built + ruled |
| drop counters, damping | lost telemetry and wake loops are loud facts | proven in testbed |

## Genuinely missing (small, named)

- **The admission/contract check** at the terminal transaction (breakage =
  computed rejection). Belongs to step 4's commit path.
- **The escalation rule**: a fix lineage open past N turns / M failed
  attempts (config facts) derives an escalation fact that wakes the planner.
  Symmetric damping: an escalated lineage stops waking its owner.
- **Plan lineage schema**: plan entity → spawned fix runs → derived status,
  so done/iterating/escalated are queries.
- **The tool itself** — agent-facing, so `my.*` flat (name TBD when built;
  `seon.ai` is the provider wire, not the home).

## Ruled same evening (owner)

- **Accrete-first.** Accretion is the primary move; adapt only when
  accretion cannot go further. Question 1 below is RESOLVED: standing
  policy, not per-change choice.
- **Refactoring is the same admission at batch scope, not a reverse
  process.** Two sanctioned moves: (a) accrete + drain — the new contract
  arrives beside the old WITH its own tests; callers migrate under their
  owners; the old fn's deletion is derived (graph shows zero callers),
  never scheduled; old tests die with the old fn. (b) batch update — same
  name, new contract, every caller and test replaced in ONE change set;
  admission evaluates the whole set at its post-transaction basis, so an
  internally consistent batch passes. Tests are never mutated to bless a
  broken promise; a new contract brings new tests.
- **Spec-first protocol.** The planner's deliverable is the contract layer:
  (1) data-model changes, (2) Malli function schemas with `:=>` plus `:fn`
  RELATIONAL properties (output-as-a-function-of-input — success defined
  with zero knowledge of internals) plus example tests, generatively
  checked. Implementation farms to cheap agents (local models) iterating
  until green; expensive intelligence writes contracts, free intelligence
  satisfies them. Anti-gaming: properties, not just examples — a hardcoded
  implementation must survive the generators. Success that is not
  spec-expressible (prose, UI feel) stays with `src-inspect-ai`, outside
  the green-loop.

## Open questions for the owner

1. ~~Accrete-vs-adapt default~~ RESOLVED above: accrete-first standing.
2. Who arbitrates a consumer that refuses/fails to adapt — root always, or
   the planner up to a budget?
3. Does a plan lineage get its own budget fact (total turns across all
   owners) in addition to per-owner escalation thresholds?
4. Local-model implementers: which model rides the free tier first
   (MLX/DiffusionGemma vs a small hosted row), and is implementer identity
   a lineage fact so cost attribution is queryable?
