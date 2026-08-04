---
type: prd
status: draft
tags: [prd, agent, context, bootstrap, sci]
---

# Bootstrap as a concept graph — exploration with owner (2026-08-04)

Status: ITERATING WITH THE OWNER. No lane acts on this document.

The owner's framing: teaching the bootstrap is a graph problem. There are
weakly held priors about what the model already knows (Clojure basics,
Datomic-shaped querying, some Malli), and there are Seon-specific facts no
training data contains (code lives in the database, contract admission
rules, message/run lifecycle, the canvas). Instead of one hand-ordered
wall, declare the concepts, declare what each form teaches and requires,
and COMPUTE the ordered vector per profile — general-purpose vs
development to start.

This document is the exploration record: the reframe, the data model, the
cost model, the calibration loop, and the honest caution about how much
optimizer to build now.

## 1. The reframe — bipartite, not concept-to-concept

The first instinct is a graph whose nodes are concepts and whose walk
visits every concept at least once with cheaper edges over shown
material — a traveling-salesman shape. That model has a mismatch: the
agent never travels between concepts. It reads FORMS, and one form
touches several concepts at once (`(doc my.run/complete)` teaches
discovery-by-doc, the run lifecycle, AND models small-result discipline
simultaneously).

The natural structure is bipartite:

- **Concept** — one teachable fact about the system, with a per-model
  prior that the model already knows it.
- **Form** — one evaluable teaching artifact. A form **teaches** a set of
  concepts and **requires** a set of concepts (its lesson only lands if
  the reader has already seen them — the refusal form requires
  contract-annotation; the repair form requires the refusal).

The bootstrap vector is then an ordered selection of forms such that:

1. every target concept (profile-selected) is taught by at least one
   chosen form;
2. every chosen form's requires are taught earlier (or held at high
   prior);
3. total cost is minimal.

This is weighted set cover with precedence constraints — NP-hard in
general and completely irrelevant at our size: tens of forms, tens of
concepts. Exact search or a greedy ratio heuristic both run in
microseconds. The value of the formalization is not the optimizer; it is
that COVERAGE BECOMES A QUERY (see §6).

The owner's "routing cost gets cheaper for concepts already shown" is
exactly the precedence discount in this model: a form whose requires are
all taught contributes full value at only its token cost; a form whose
requires are missing is inadmissible (not expensive — inadmissible,
because a lesson whose prerequisites are absent doesn't degrade, it
fails). The prior handles the boundary case: a concept with a high prior
(Datomic query shape, `defn` basics) counts as pre-taught for
admissibility, so no form need cover it — which is precisely the "weakly
held priors" intuition.

## 2. Revisits — diminishing returns, not a flat penalty

The owner's instinct: revisits allowed, penalized, not encouraged. The
current hand vector already shows the better shape: the closed-map rule —
the single measured hazard (§2 of
[bootstrap-vector-design-2026-08-01.md](bootstrap-vector-design-2026-08-01.md))
— is deliberately touched THREE times (refusal face → repair →
persistence query), while `dir` discovery is touched once. Reinforcement
is a feature for hazardous, low-prior concepts and waste for easy ones.

So instead of a flat revisit penalty: each concept's marginal teaching
value diminishes per touch, scaled by need:

```clojure
need(c)        = weight(c) × (1 − prior(model, c))
value(c, k)    = need(c) × decay^(k−1)     ; k-th touch, decay ≈ 0.3
utility(walk)  = Σ value − λ × Σ tokens(form)
```

A high-need concept keeps paying on touch two and three; a known concept
pays almost nothing on touch one. The flat penalty falls out as the
special case decay → 0, and "encourage repetition for the hard thing"
falls out with no extra machinery.

## 3. The data model — all declared, all queryable

Everything below follows the standing principle: every question is a
Datalog query over declared facts, never a convention. Schema sketch (to
be authored properly under `resources/seon/schemas/` when this seals):

```clojure
;; A concept — one teachable fact.
{:seon.bootstrap.concept/id      :concept/code-is-queryable   ; unique identity
 :seon.bootstrap.concept/summary "Every fn/ns/schema/test is a database fact; discovery is a query."
 :seon.bootstrap.concept/tags    #{:discovery :database}      ; cardinality-many
 :seon.bootstrap.concept/weight  3}                           ; importance, owner-set

;; A per-model prior — a MEASURED estimate, never a guess frozen forever (§5).
{:seon.bootstrap.prior/concept  [:seon.bootstrap.concept/id :concept/datalog-basics]
 :seon.bootstrap.prior/model    "deepseek-v4-flash"
 :seon.bootstrap.prior/estimate 0.8
 :seon.bootstrap.prior/evidence <ref to grading runs>}

;; The existing plan form rows gain two ref-many attributes.
{:seon.cluster.run.form/source   "(seon.db/q '[:find ?sym :where ...])"
 :seon.bootstrap.form/teaches    [<:concept/code-is-queryable> <:concept/find-by-shape>]
 :seon.bootstrap.form/requires   [<:concept/datalog-basics>]}

;; A profile is a declared tag selection, never a hand list of concepts.
{:seon.bootstrap.profile/id   :development
 :seon.bootstrap.profile/tags #{:code :contracts :testing :discovery}}
```

Notes against the house rules:

- **No stored derived state.** A form's token cost is
  `seon.ai.tokens/estimate` over its rendered bytes, derived at walk
  time — never a stored column. Same for coverage and the walk itself.
- **Profiles select by declared tags**, so adding a concept with
  `:contracts` automatically enrolls it in every profile carrying that
  tag — no roster to maintain. Two profiles to start: `:general`
  (discovery, data manipulation, storage, messaging, canvas) and
  `:development` (adds contracts, testing, extension-vs-create,
  reading-existing-code).
- **Concepts that only prose can teach are still concept rows**, taught
  by the `(help)` form. This is quietly one of the strongest wins: every
  sentence in `help` becomes accountable to a named concept, and a
  sentence with no concept is flagged as noise by the same coverage
  query that flags an untaught concept. Prose stops being a wall and
  becomes covered edges.

## 4. The walk — computation, batching, and what it feeds

`plan-walk` is a pure function `(db, profile, model) → ordered forms`:

1. targets = concepts whose tags intersect the profile's;
2. taught₀ = concepts with prior ≥ admissibility threshold (they gate
   admissibility only; they still carry small residual need, so a cheap
   form touching them isn't forbidden, just barely rewarded);
3. greedily pick the admissible form maximizing marginal utility per
   token until residual need is exhausted (or exact-search — the graph
   is tiny; both, and assert they agree, is the honest move);
4. emit the order, then compute BATCH BOUNDARIES.

Batching deserves its own point. In a scripted bootstrap the model reads
everything after the fact, so batching carries no information constraint —
its job is to MODEL the behavior we want the agent to adopt ("run many
forms per turn; split where a later form depends on an earlier result").
So the batch boundary is itself derived from the graph: forms with no
teaches→requires edge between them share a batch; an edge splits it.
The bootstrap demonstrates its own advice, and the demonstration is
computed from the same declared edges rather than hand-chosen.

Downstream, NOTHING changes. The walk's output is exactly today's
ordered sources vector: `population-tx` installs it, `seed-tx` freezes
it into the agent's bootstrap run, the digest pins it, the transcript
renders it. The graph sits upstream of the one existing mechanism —
plan-population time, not per-agent runtime — so the digest stays stable
and per-model/per-profile vectors are just different computed plans
through the identical seeding path. This is the accretion test passing:
the flat vector was the special case of a one-profile graph.

## 5. The calibration loop — priors are measured, per model

The priors start as owner-authored estimates ("weakly held" is the
correct epistemic state). But the experiment harness in
[bootstrap-vector-design-2026-08-01.md](bootstrap-vector-design-2026-08-01.md)
§5-6 already grades TRANSFER per concept: O2/P2a measures whether the
find-by-shape query transferred, O5/P5 measures whether refusal→repair
transferred. Each grading predicate maps to concept rows, so grading
runs UPDATE `:seon.bootstrap.prior/estimate` with evidence refs:

- a concept that transfers even when its form is dropped → prior was
  underestimated → the walk stops spending tokens on it;
- a concept that fails to transfer despite teaching → raise its weight
  or add a reinforcement form → the walk touches it more.

That closes the loop the flat vector cannot express: the bootstrap for
`deepseek-v4-flash` and the bootstrap for a stronger model DIVERGE
automatically from the same graph as their measured priors diverge —
per-model calibrated vectors (owner direction, 08-01 midday) become a
query result, not a maintained fork.

## 6. What the representation buys even with zero optimizer

Ranked by value:

1. **Coverage proof.** "Is every target concept for `:development`
   taught before required, at least once, within budget?" is one query.
   Today the equivalent check is a human rereading the EDN.
2. **Accountable prose.** Every `help` sentence owns a concept;
   orphaned sentences and untaught concepts surface symmetrically.
3. **Per-model/per-profile vectors with one mechanism.**
4. **Runtime introspection.** An agent's bootstrap receipts → forms →
   concepts, so "has this agent been shown X?" is queryable mid-session.
   Future in-session teaching (agent trips a refusal for an untaught
   concept → the system can know it was never shown) becomes possible
   exactly because the edge exists as a fact.
5. **The optimizer itself** — real once concepts number in the dozens
   and models in the several; at 14 forms × 2 profiles a topological
   sort plus the coverage query delivers ~90% of the value.

## 7. Honest cautions

- **Don't build the router first.** Slice order: (a) declare concepts +
  edges over the EXISTING 14 forms and run the coverage query — this
  will immediately reveal untaught concepts and unaccountable prose;
  (b) add the `:general`/`:development` profiles and the greedy walk;
  (c) wire prior updates to the grading harness. Each slice is useful
  alone; the optimizer's sophistication grows only when the graph does.
- **The graph does not replace the experiment.** The 08-01 design's
  point stands: bootstrap content is an experiment, not a derivation.
  The graph organizes hypotheses (concepts, priors, edges) so the
  experiment can update them; it does not make the first vector right.
- **Form quality is still authorship.** The optimizer chooses among
  authored forms; it cannot write the good refusal→repair beat. Concepts
  without a strong demonstrating form are the creative backlog, surfaced
  by query.

## 8. Open questions for the owner

1. **Concept granularity.** One concept per `help` sentence (~12
   concepts from prose alone) vs coarser clusters (~5)? Finer gives
   sharper coverage accounting; coarser is less bookkeeping.
   Recommendation: start coarse, split a concept only when a grading
   predicate needs to distinguish its halves.
2. **Admissibility threshold vs soft priors.** Hard threshold (prior ≥
   0.7 counts as taught) is simpler; fully soft (requires satisfied in
   expectation) is smoother but harder to reason about.
   Recommendation: hard threshold, revisit with evidence.
3. **Where do concept declarations live?** In the same
   `resources/seon/bootstrap.edn` beside the forms they annotate
   (one artifact, one digest — recommended), or as separate schema-EDN
   population.
4. **Does `:development` extend `:general`** (tag union, agents get both)
   or stand alone? Recommendation: profiles are just tag sets, so
   `:development` simply includes `:general`'s tags — extension by
   construction, no inheritance mechanism.
