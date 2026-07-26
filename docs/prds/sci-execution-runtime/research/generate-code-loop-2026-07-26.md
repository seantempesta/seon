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

## Prototype verdict — divergences first

The fake-agent Flow testbed supports the ruled composition, with three
clarifications that the sketch did not make precise:

- **An open owner run needs continuation, not another wake.** The first planner
  wake starts the namespace owner's fix run. A failed attempt from that same
  open run must not create another owner wake; the proc continues the already
  open run. Treating every retry as a fact-derived wake would violate the
  no-self-wake rule and create the very positive-feedback loop the damping rule
  is meant to prevent.
- **The escalation boundary must be inclusive and idempotent.** The prototype
  escalates when either `turn-count >= max-turns` or
  `failure-count >= max-failures`, and gives the escalation one identity.
  “Past N” was ambiguous; waiting for N+1 permits one extra failed attempt
  after the configured budget is exhausted.
- **Deterministic seed mixing needs wrapping arithmetic.** Checked JVM
  multiplication overflowed on the first property trial. The corrected
  seed-random outcome function uses explicit wrapping multiplication before
  constructing `SplittableRandom`. This is testbed machinery, but it matters:
  a recorded seed that throws instead of selecting an outcome is not a
  deterministic simulation.

Everything else survived contact. The plan status remains derived rather than
stored; only the escalation event is a durable fact. A planner re-woken after
owner B escalated queried the current database value with `since` and observed
owner A's already-committed success. A fake admission rejection stayed an
ordinary value containing the affected consumers and committed the
accrete-vs-adapt choice point.

The schema in `test/seon/flow/loop_test.clj` is explicitly a **prototype only**.
It uses throwaway in-memory Datahike connections and does not claim the
production attributes that land with step 4.

## Prototype expected versus observed

| property | expected | observed |
|---|---|---|
| Seeded termination | Every seed reaches all-green or escalated before either fact budget can be exceeded | Passed for 60 test.check trials selected from ten recorded outcome seeds. Every run returned derived `:seon.flow/done` or `:seon.flow/escalated` with `turn-count <= max-turns`. |
| No self-wake and escalation damping | An owner's own open-run fault never wakes it; after escalation only the planner is actionable | Passed. Failed B-attempt facts derived no B wake. B had exactly one wake, the initial planner wake. After two failures at `max-failures = 2`, repeated action derivation returned only `wake-planner`, and B remained at exactly two attempts. |
| Asymmetric current-basis re-plan | A succeeds once; B exhausts its budget; planner attempt two sees A's committed success | Passed. A committed at the first owner turn, B failed twice, and the escalation fact woke the planner. A query over `(d/since current-db before-a-success)` returned exactly `#{:owner-a}`; planner attempt two returned and durably recorded that same set at its current basis. |
| Contract rejection as a value | The controlled admission predicate rejects with consumers and records the accrete-vs-adapt choice point | Passed. The planner report carried `#{:consumer.alpha :consumer.beta}`, Flow's error channel stayed empty, and the plan facts recorded rejection, both consumers, and `accrete-or-adapt`. No real breakage detection was built. |

The test.check generator seed is `20260726`. The explicit outcome seeds carried
by the simulated runs are `7`, `17`, `41`, `73`, `101`, `211`, `307`, `401`,
`509`, and `997`. The real N-owner Flow proof uses seed `20260726` across three
owner procs and three attempts each. The recurring command and result are
recorded in `tmp/plan-evidence/generate-code-loop-2026-07-26.edn`.

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
  computed rejection). Belongs to step 4's commit path. The prototype supplies
  a controlled accept/reject function and proves only the loop's reaction to
  its returned value.
- **The escalation rule**: a fix lineage open past N turns / M failed
  attempts (config facts) derives an escalation fact that wakes the planner.
  Symmetric damping: an escalated lineage stops waking its owner. The prototype
  proves inclusive thresholding, one escalation fact, and the continuation
  versus wake distinction; the production query and transaction owner remain
  missing.
- **Plan lineage schema**: plan entity → spawned fix runs → derived status,
  so done/iterating/escalated are queries. The throwaway prototype proves the
  connections and derived statuses, but deliberately does not register the
  production schema before step 4.
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

## Open questions — ALL RESOLVED (owner, 2026-07-26 evening)

1. Accrete-vs-adapt default: **accrete-first standing policy.**
2. Arbitration of a consumer that fails to adapt: **root, always.**
3. Lineage budget fact (total turns across owners) on top of per-owner
   thresholds: **yes** (owner: tentatively interested — treat as a config
   fact from day one, cheap to carry).
4. Implementer tier: **MLX local models, hammered freely** — this loop is
   the real test of the distributed implementation. The ruled economics:
   the smart model iterates a LIMITED step budget on the data model and
   tests only (the contract layer); the distributed multi-step/hop/retry
   system of local implementers satisfies everything and returns the diff;
   the smart planner then decides more-or-victory. Implementer identity is
   a lineage fact so cost-per-green is queryable.
