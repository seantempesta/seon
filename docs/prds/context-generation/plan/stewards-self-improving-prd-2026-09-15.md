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

## 9. The data model of a task (SUPERSEDED by §10 — kept for the record; the `seon.problem` family is withdrawn: detectors are functions, not declarations)

A task is three pieces of data, nothing else: a **problem declaration**
(what to find, what to show, what proves it done), a **context request**
(who is asking, at what detail, at what basis), and the **plan** it
generates (steps whose success is a function of the database). Every
scenario — fix an error, answer a message, write a missing function,
declare a contract — is a row of the first, a request of the second, and
rows of the third. No task is code.

### 9.1 Problem declaration (`seon.problem`, one entity per class)

| attribute | type | meaning |
|---|---|---|
| `:seon.problem/id` | identity string | e.g. `"test/red"` |
| `:seon.problem/find` | `:seon.db/query` | subjects; its read evidence is also the trigger (§8.1) |
| `:seon.problem/context` | `:seon.db/pull-selector` | the neighbourhood from the subject, both ref directions |
| `:seon.problem/done` | `:seon.db/query` bound to `?subject` | the success function of the whole task |
| `:seon.problem/steps` | vector of step templates | each `{title, done-query, writes}`; the plan generator instantiates them with the subject |
| `:seon.problem/writes` | set of fact families | what a fix may produce |
| `:seon.problem/budget` | int | turns per session |
| `:seon.problem/priority` | int | ordering when several fire |
| render pair | on the entity schema | statement + subject + done as the opening block |

### 9.2 Context request (`seon.render`, the keys every pair receives)

| key | meaning |
|---|---|
| `:seon.render/viewer` | the namespace asking — the steward's own for a session; a dependent's when it reads the picture |
| `:seon.render/subject` | the entity the context is about |
| `:seon.render/detail` | the profile |
| `:seon.render/basis` | the database basis (derived from the db value) |
| `:seon.render/window` | how far back stats look |
| `:seon.render/problem` | the declaration, when the context is for a task |

Construction = `pull subject with problem.context` → render each entity
through its pair with these keys → prepend the problem pair's block →
the plan block. The same function serves the opening of a session and
the debug page's "context for namespace X at detail D".

### 9.3 Plan and the success function (`my.plan.item`, accretion)

| attribute | type | meaning |
|---|---|---|
| `:my.plan.item/done-when` | string (exists) | the criterion in words, rendered for the model |
| `:my.plan.item/done-query` | `:seon.db/query` bound to `?subject` (new) | the success FUNCTION; evaluated at every settlement; `completed-tx` recorded the first time it is true |
| `:my.plan.item/subject` | ref (new) | what the step is about |
| `:my.plan.item/problem` | ref (new) | the declaration that generated it |
| `:my.plan.item/writes` | set (new, optional) | families this step may write |

`complete!` on a step with a false done-query is a flat error naming the
query and what it found (issue filed 2026-09-15 from run 7, where step 7
"report" was completed with no message sent). A step with no done-query
keeps today's behaviour for authored plans.

### 9.4 The catalogue, as rows (first set)

| id | find (subjects) | context (from subject) | done (bound to ?subject) | writes |
|---|---|---|---|---|
| `test/red` | tests with fail or error count > 0 | the test, its subject fn, the fn's callers and faults | fail-count 0 and error-count 0 at a later basis | fn, test |
| `fault/recurring` | fault signatures with occurrences ≥ N on ops of a namespace | the op fn, the callers (via run→agent→ns), the last evaluations that raised it, the op's tests | no occurrence after basis T | fn, test, schema |
| `fn/untested` | public fns with no reaching test | the fn, its contract, its callers, the ns's existing tests | a passing test reaches it | test |
| `fn/uncontracted` | public fns without a complete `:seon.fn/spec` | the fn, its callers' argument shapes, related schemas | spec complete, no `:any` | fn, schema |
| `schema/unrendered` | entity schemas without a pair | the schema, its attributes, entities using it, a sibling pair as example | pair rows exist and render | fn, schema |
| `message/unanswered` | inbox messages | the message, the sender's identity and plan, the thread via `:about` | a message from me `:about` it exists; inbox edge gone | message |
| `fn/missing` | call edges to symbols with no `:seon.fn` row | the caller, its contract, referenced schemas | the row exists with a complete spec and a passing reaching test | fn, test |
| `example/failing` | docstring examples that fail to evaluate | the fn, the example, its contract | the example evaluates | fn |

Each row is one transaction; thresholds (N, T) are config facts.

### 9.5 What the loop does with them (no task code)

gather: for each steward, run every `find`, instantiate plan items with
`subject`/`problem`/`done-query`, order by priority → act: sessions →
settle: evaluate done-queries, record completions, record metric deltas
→ judge: the ledger and the explain probe. The only new code paths are
the plan generator (find → items) and the settlement check (done-query →
completed-tx); everything else is the existing walk, pairs, loop.

## 10. From the audits: the inside-out build (2026-09-15 16:40Z)

Two read-only audits probed default and replaced §9's abstractions with
facts: [data-audit-a](../research/data-audit-a-2026-09-15.md) (program
side) and [data-audit-b](../research/data-audit-b-2026-09-15.md)
(session side). Both reached the same conclusions independently:

1. **No problem family, no metrics registry, no task-kind stamp.** A
   detector is a query, so it is a public contracted function that returns
   subjects (`seon.cluster.message/unanswered`, `seon.test/red`, …); the
   success function is another (`seon.test/verified?`, `seon.fn/tested?`,
   `seon.cluster.message/answered?`); a missing subject is `false`, a query
   refusal is an error, never completion. `seon.problems` already derives
   aggregates; the picture and the panel read the detectors.
2. **The plan item is the task.** Existing rows plus two accretions that
   run7-wave is landing: `:my.plan.item/subject` (ref) and
   `:my.plan.item/done-query` (bound to the subject, evaluated at
   settlement, `completed-tx` recorded the first time it is true; `complete!`
   refuses while false). The item id derives through `seon.id` from
   detector identity + subject + basis. Run 7's `juniper/report` is the
   standing counterexample (completion recorded, no message).
3. **Test evidence must name the tested program.** One schema delta serves
   every program chain: `:seon.test/run` (ref) and `seon.test.run`
   {id, at, git-sha — built today, discarded; + program-digest, basis-t,
   branch}. Results are recorded to `:current-src` by default through the
   one writer seam (`commit-results!`/`record-tx`), retiring the duplicate
   `:test-results` destinations; a recording failure gates completion.
4. **The render request.** No pair receives viewer/subject/detail/basis
   today (probe P7: none survive `render-argument`). Accrete to
   `seon.render.edn`: existing db/value/profile/distance + `viewer`
   (namespace ref), `subject` (entity ref), optional `window` (transaction
   bounds); basis derives from the db value; detail IS the profile. The
   new keys join the retained-call/invocation evidence so a render cached
   for another viewer is never reused.
5. **The call ledger is deferred.** No seam sees every call (computed
   callees bypass SCI's hook). When built: bounded per-evaluation
   aggregates by fn+outcome as `:seon.eval/calls` components, with explicit
   coverage; not needed for the first task.
6. **Persisting as program files.** Durable source strings and bounded
   writers exist; the reverse `.clj` writer does not. MVP restriction: a
   pure `seon.program/source-files` over existing program shapes → exact
   `{path, text, identities, basis}` for a NEW explicit source namespace and
   test namespace at two new paths, plus an effect
   `seon.cluster.export/source!` through the fs/edit owner. Editing files
   in place is a separate scope.

### 10.1 The minimum viable task (one, complete by definition)

An agent-to-agent request that owes a response about a message — the
chain with a concrete false witness today (`e10231f6`) — where the agent's
reusable result is a small pure predicate plus its test, installed through
ordinary admission, verified by a test run tied to the program digest, and
exported to two new files. Success = `answered?` true AND the function has
source/spec/ns AND a reaching test with positive passes and zero failures
at the relevant program basis with a fresh run AND the export wrote both
files. Nothing in that sentence is a model's claim.

### 10.2 Build order, inside out (each step: schema/function + regression + live proof on default)

1. Data: `:my.plan.item/subject`, `done-query` (run7-wave); `:seon.test/run`
   + `seon.test.run` provenance; default recording to `:current-src`.
2. Read functions: `seon.cluster.message/unanswered`, `answered?`;
   `seon.test/verified?`, `red`; `seon.fn/tested?`, `untested` — contracted,
   typed absent-subject behaviour, tests on the canonical fixture.
3. Generation: one function `plan-item` (detector + subject + basis →
   plan transaction data with done-query); no scheduler yet — the
   orchestrator calls it for the first task.
4. Context: the request keys on the pair contract; message/agent/plan/item
   pairs take explicit subject arguments; the generated opening teaches the
   exact completing/reporting calls and shows the success query's current
   result.
5. Session: the ordinary turn graph; budget from config; the ledger and
   the explain probe judge.
6. Files: `seon.program/source-files` + `seon.cluster.export/source!`; the
   task's last step exports; done-query includes the two files' digests.
7. Then the batch: root's schedule runs detectors for every steward and
   generates items; the picture pair for `:seon.ns` reads the same
   detectors.


## 11. The task as stored data, with tests as the success functions (owner, 2026-09-15 10:50)

Owner: "it might actually be a set of functions for validating success —
maybe explicit deftests, test-driven. Which data structure defines a task?
Agents should inspect and add their own. A user asking a question is
another task; the response is written too; the context is all
communications with that user."

### 11.1 Success conditions are tests

A success function is a `deftest`: durable (`:seon.test` row with source,
subject, ns), runnable by the agent (`(my.test/run)`), recorded with
provenance (`:seon.test/run` → program digest, basis), and read by one
predicate (`seon.test/verified?`). A test can assert a live fact as
easily as a computed value — `(is (message-answered? db msg))` — so code
tasks and communication tasks share one success mechanism. This is
test-driven development as the loop: the tests exist before the work;
the agent's job is to make them pass; completion is derived from their
results, never asserted.

### 11.2 The data structure: a task kind is references to functions

```clojure
#:my.task{:id       "message/answer"            ; identity
          :title    "Answer a message you were sent"
          :subjects [:seon.fn/sym "seon.cluster.message/unanswered"] ; detector: db → subjects
          :context  [:seon.fn/sym "seon.cluster.message/thread"]     ; subject → the data the agent needs (here: every message between the two agents, the sender's identity and plan)
          :tests    #{[:seon.test/sym "seon.cluster.message-test/answered"]} ; test templates bound to the subject
          :writes   #{:seon.message}
          :budget   12}
```

Every value is a ref to a row that already exists or that an agent can
create with `defn`/`deftest`: the detector and the context are contracted
functions, the tests are durable tests. A task kind is therefore data an
agent can inspect (`(seon.db/pull '[*] [:my.task/id "message/answer"])`),
copy, and add to by transacting a row that references functions it wrote.
No kind stamp: the presence of `:my.task/id` is the family.

### 11.3 An instance is a plan item

`(my.task/instantiate task subject)` → ordinary plan transaction data:
`:my.plan.item/task` (ref), `:my.plan.item/subject` (ref), the tests
instantiated for the subject as `:seon.test` rows referenced by
`:my.plan.item/tests`, the title and prose done-when rendered from the
kind, `done-query` = "every test in `:my.plan.item/tests` is verified at a
basis after this item's creation". Completion is recorded at settlement
by the existing check (`d31d31639`). The item id derives through
`seon.id` from kind + subject + basis.

### 11.4 Rendering the task (one pair)

The `:my.task` pair renders, for the viewer: the title and statement;
the subject through its own pair; the context function's output (each
entity through its pair, with viewer/subject/window on the request); the
tests with their current results ("`answered` — not yet run" / "failed:
…"); the exact calls that complete it (`(my.test/run)`; `(my.agent/done)`
last). That block is the opening of the session and the debug page's
Record for the item.

### 11.5 A user question is the same row

`#:my.task{:id "message/answer" …}` already covers it: the subject is the
message; the context function returns the thread with that user (every
`:seon.message` between the two agents, by `from`/`to` and `about`) plus
the sender's plan and identity; the test asserts a reply `:about` the
message exists and the inbox edge is gone; the response IS the write.
Quality of a prose answer is not machine-verifiable; the test asserts
delivery and threading, and the human's follow-up is the next task.

### 11.6 First rows

| id | subjects fn | context fn | tests | writes |
|---|---|---|---|---|
| `message/answer` | `message/unanswered` | `message/thread` | `answered` | message |
| `test/red` | `test/red` | `test/subject-context` (test, fn, callers, faults) | the red test itself | fn, test |
| `fn/untested` | `fn/untested` | `fn/context` (fn, contract, callers) | a new test reaching the fn, green | test |
| `fault/recurring` | `error/recurring` | `error/context` (op, callers, evaluations) | a regression test naming the signature, green | fn, test |
| `fn/export` | `fn/exportable` (admitted, tested, not on disk) | `fn/context` | files exist with the row's digest | files |

The five detector/context functions and five test templates are the
first build slice, each with its own deftest; the kinds are five
transactions.
