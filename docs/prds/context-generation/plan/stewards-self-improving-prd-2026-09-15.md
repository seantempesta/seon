---
type: prd
status: draft
created: 2026-09-15
owner: orchestrator with the owner (design dialogue 2026-09-15 09:00)
---

# Stewards: the system builds itself — draft for the owner's markup

The owner's reframe (2026-09-15): "We've been doing a good job building
the platform and the system, but we are now guessing at tasks when we
need to flip the goals and have the system build itself from now on."
The name we already have for the agent that answers for a namespace is
its **steward** (`:seon.ns/steward`); faults already route to
`:seon.error/steward`. This document turns the reframe into a plan on the
ingredients that exist, names the gaps, and lists the decisions.

## 0. The three inputs of every render (owner)

Every render is a function of data plus three request facts, all
fully-namespaced keys on the request map, already partly present in the
walk request (`:seon.render/distance`, the profile) and the debug route
(the viewer namespace):

| key | meaning | today |
|---|---|---|
| `:seon.render/viewer` | the namespace asking (its world view) | debug route `viewer` param; not on the pair contract |
| `:seon.render/distance` | how many refs out the render may reach | `:seon.render/distance` on the walk request |
| `:seon.render/detail` | the size/detail request (a profile) | `:seon.render.profile/*` applied by the value renderer |

A render pair receives all three and SHOULD use them (a plan rendered for
its own steward shows the completing call; rendered for a dependent
namespace at distance 1 it shows the objective and state only). Nothing
else changes in the pair contract: keys and values in, AI text or Hiccup
out. This is the one rule that makes "context for any namespace in the
graph, relevant to its view" a derivation instead of a new system.

## 1. The namespace picture (the steward's world view)

One render pair for the `:seon.ns` entity, derived from facts we store:

- **who depends on you**: `:seon.ns/requires` reversed and `:seon.fn/calls`
  reversed → dependents (count, names, last call basis);
- **your users' failures**: fault rows whose `:seon.error/op` is one of
  your functions, grouped by `:seon.error/signature` with
  `:seon.error/occurrences`, and the calling namespace derived through
  `:seon.error/run` → turn → agent → namespace (the owner's "these are
  your users"); the last five distinct signatures;
- **your tests**: `:seon.test/*` rows whose `:seon.test/ns` is you, or whose
  `:seon.test/subject` reaches your functions (`seon.fn/tests-reaching`);
  red count, green count, functions with no reaching test;
- **your contracts and renders**: public functions without complete
  contracts (`:seon.fn/spec` absent or containing `:any`), entity schemas
  in your namespace without a declared AI/HTML pair;
- **your context cost**: prompt bytes your renders contribute, elision
  count in agents' contexts (the printer records elisions);
- **goals**: derived lines such as "3 dependents call you without a fault
  in the last N turns; 2 with faults" — the number to move.

The picture IS the debug page's "Record" for a namespace and the opening
block for its steward, rendered through the same pair with
`:seon.render/viewer` = the steward. Missing fact to declare: none for
v1 — every line above is a query over rows we keep (verify
`:seon.error/run` is set on faults raised during agent turns; if not,
that is the one declaration to add at the fault committer).

## 2. Metrics as queries (how we know it worked)

A metric is a pure function of a database value, declared once (name,
docstring, the query, the direction that is better), registered as data
so the picture, the problems panel, and the evaluation all read the same
list. First set, each derivable today:

| metric | query over | better |
|---|---|---|
| red tests | `:seon.test/fail-count`, `error-count` by ns | ↓ |
| fault signatures per namespace, occurrences | `:seon.error/*` | ↓ |
| dependents without faults / with faults | calls + faults | ↑ / ↓ |
| public functions without a reaching test | `seon.fn/tests-reaching` | ↓ |
| public functions without a complete contract | `:seon.fn/spec` | ↓ |
| entity schemas without a render pair | schema rows + pair declarations | ↓ |
| docstring examples that fail to evaluate | the executable-example regression as rows | ↓ |
| dead functions (no callers, no tests) | calls + tests | ↓ |
| context bytes per turn, elisions per context | evaluations, printer facts | ↓ |
| steps completed per provider turn, turns to done | plans, turns | ↑ / ↓ |

Success of a session = the metric deltas between its opening basis and
its close, recorded as facts on the session (a derived report, not a
stamp), shown on the ledger's header line and the picture.

## 3. Plans generated from state (no more guessed tasks)

A **plan rule** maps a metric finding to a plan item with a done-when
query (the stopping point):

| finding | plan item | done when (query) |
|---|---|---|
| a red test in your ns | fix `<test>` | its last result passes |
| a fault signature with ≥ N occurrences from a dependent | make `<op>` handle the case or refuse with a useful error | no new occurrence of the signature after basis T |
| a public fn with no reaching test | write a test for `<fn>` | a `:seon.test` row reaches it and passes |
| a public fn with an incomplete contract | declare its contract | `:seon.fn/spec` complete, no `:any` |
| an entity schema without a pair | declare the pair | pair rows exist; the picture renders through it |
| a docstring example that fails | fix the example or the function | the example evaluates |
| context cost above the namespace's median | shrink the render | bytes per context ↓ |

Rules are data (schema-declared), so adding one is a fact. The generated
plan is ordinary `my.plan` data; the steward's session ends by
`(my.agent/done)` or the budget; the ledger and the explain probe judge
it; the metric deltas are the score.

## 4. The batch: gather, then act, repeatedly

Root's maintenance portfolio (already a [TARGET] row) becomes the driver:

1. **Gather** (one system turn per steward, no provider): render each
   namespace's picture, compute its metrics, apply the plan rules, queue
   the items on the steward's plan — for every namespace with a steward,
   in one batch.
2. **Act** (bounded provider sessions, N at a time): each steward works its
   plan under its turn budget; results are facts.
3. **Judge**: metric deltas per session, the explain probe per session,
   faults routed by steward; the dashboard (root's page) shows all
   pictures.
4. Repeat. Every cycle the rules see the new state. The system improves
   in a distributed way, and the orchestrator reviews the ledger instead
   of inventing scenarios.

The Juniper orders scenario retires as the default test; it stays as a
fixture for the loop proof. The first stewards are the platform's own
namespaces (`seon.db`, `seon.plan`, `my.*`, the render pairs), so the
system's first jobs are its own bugs — the ones the live runs keep
finding.

## 5. The debug page for this

- The turn ledger stays the way to see what happened per turn (WE SENT /
  AGENT REPLIED / RESULTS), with the stalled state visible.
- The **graph view** (owner's idea): entities as nodes (namespaces,
  functions, agents, plans, faults) connected by refs, each node showing
  its render block, with an AI/HTML toggle and the three request dials
  (viewer, distance, detail) as controls — so a person sees exactly what
  a given namespace's context would contain at that distance and detail.
  Built on the existing walk (membership/order) and the shipped cytoscape
  script; the node body is the same pair output the page uses.
- The namespace picture as the page's Record for a namespace.

## 6. Order of work

1. Finish the ledger blockers (stalled visibility, q guard) — in flight.
2. Render request as data: `:seon.render/viewer` on the pair contract;
   pairs read viewer/distance/detail (plan, settings, inbox, runtime, ns).
3. The namespace picture pair; verify the fault→run→namespace chain.
4. Metrics registry (data) + the first ten metrics + their render.
5. Plan rules (data) + generation as a system turn; done-when queries.
6. The gather/act batch under root's schedule; N stewards; dashboards.
7. The graph view.
Each step lands with a regression and a live proof on default.

## 7. Decisions for the owner

1. Term: **steward** (already declared) — confirm, so no new noun.
2. First steward set: the platform's own namespaces (recommended) vs.
   example namespaces.
3. Metric v1 set: the ten above, or fewer to start.
4. Plan-rule thresholds are config facts (occurrences ≥ N) — confirm.
5. Batch size N and the per-session turn budget as config facts.

## 8. Inside out: the data (owner, 2026-09-15 09:30)

"Describe everything as data; don't hardcode the system to do each task."
Four questions, each answered by attributes — what exists, what to declare.

### 8.1 When does a session start? (triggers)

| exists | declaration |
|---|---|
| `:seon.wake/listen true` on a ref attribute: a datom whose value is the agent wakes it; `:seon.wake/opens-turn?` says whether it opens a paid turn | attribute-level, schema-declared |
| `:seon.listen/pattern` (attribute + optional entity/value) on `:seon.runtime/listens` | per-agent authored index patterns |
| `:seon.schedule.task` (owner, function, schedule) | time-driven |
| `:seon.wake/context-inert` | what can never be a trigger |

Gap: a **condition** trigger — "this query has subjects now" (a red test
appeared, a fault signature crossed N). Declare it once on the problem
(`:seon.problem/find`) and DERIVE its listen set from the query's own read
evidence (the attributes/patterns it reads); the loop's since-diff already
computes exactly that for reads. No second trigger registry.

### 8.2 What is on the table? (surface)

| exists | declaration |
|---|---|
| Pull patterns and reverse refs over any entity (`seon.db/pull`); the walk's neighbourhood by declared concerns | `:seon.problem/context` — the pull pattern from the subject that names the neighbourhood the fix needs |
| program rows (`seon.fn`, `seon.ns`, `seon.test`), faults (`seon.error`), turns/evaluations/attempts, plans, messages, elisions, render cost | the stats bundle keys on the render request (§C of the 09:15 note): viewer session, viewer↔subject calls, subject dependents/faults/tests/contracts/pairs/cost |
| — | **call ledger** `seon.call` {fn, agent, turn, outcome, duration}, written in the turn's settlement transaction |
| `:seon.test/*` results | committed to `:current-src` by default, keyed by run basis + source commit; `:seon.test/turn` when run from a session |
| `:seon.error/run` | verify it is set for faults raised in turns (the "your users" link) |

### 8.3 Who renders it? (render functions)

| exists | declaration |
|---|---|
| one AI/HTML pair per entity schema, declared on the `:map` props (`:seon.render/ai`, `:seon.render/html`) | unchanged; every pair receives `:seon.render/viewer`, `:seon.render/detail`, `:seon.render/basis`, `:seon.render/window` and the stats bundle as ordinary keys |
| — | a pair for `:seon.problem` (the statement, the subject, the done-when as the plan step) and for `:seon.ns` (the picture, §1) |

Context for a session = pull the subject with the problem's pattern,
render each entity through its pair with the request keys, prepend the
problem pair's output. One function; no scenario code.

### 8.4 How is it proven done? (write-back)

| exists | declaration |
|---|---|
| `:my.plan.item/done-when` is a STRING (prose) | add `:my.plan.item/done-query` (a query bound to `:my.plan.item/subject`); the loop evaluates it at every settlement and records `:my.plan.item/completed-tx` the first time it is true — completion derived from facts, never asserted by the model |
| `:my.plan.item/about` (tokens) | `:my.plan.item/subject` (a ref to the entity the item is about) and `:my.plan.item/problem` (ref to the declaration that generated it) |
| `:seon.problem/writes` | the fact families the fix may produce; a session that wrote outside them is a finding |
| the model's `(my.plan/complete! …)` | remains for authored steps; generated steps complete by query |

### 8.5 The one new family

```clojure
#:seon.problem{:id       [:string {:seon.db/identity true}]
               :kind     :qualified-keyword
               :find     :seon.db/query         ; subjects; its evidence = the trigger listens
               :context  :seon.db/pull-selector ; the neighbourhood, from the subject
               :done     :seon.db/query         ; bound to ?subject; true = done
               :writes   [:set :qualified-keyword]
               :budget   :int                   ; turns per session
               :entity   [:map {:seon.render/ai … :seon.render/html …} …]}
```

Generated plan items reference the problem and the subject; sessions are
ordinary turns; the ledger, the metric deltas, and the explain probe judge
them. Adding a task class is a transaction of one `:seon.problem` row.
