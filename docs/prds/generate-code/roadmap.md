---
type: prd
status: active
tags: [prd, agent, cljs, database, flow]
---

# Goal-driven code generation

## Outcome

`seon.ai/generate-code!` lets a small agent hand a difficult goal to a stronger
planning agent. The planning agent writes ordinary high-quality Clojure through
the existing REPL surface. Seon evaluates every mechanically recoverable schema
and namespace unit, derives namespace dependency order from `:require`, and
delegates failed units to reusable namespace-focused agents. The durable
`my.plan` graph allows recovery after interruption, while the caller receives a
compact database-derived change and verification result.

## Current status

The owner approved the explicit operation, namespace-symbol rebuild, ordinary
REPL output, schema-first evaluation, evidence-derived completion, and a
progressive developer context on 2026-07-19. The source audit, executable
probes, context measurements, and paid Kimi K3 results live in
[[research/design-seam-audit-2026-07-19]]. This roadmap is the implementation
ledger; none of the target workflow is current behavior yet.

The preparatory context cleanup is complete at `e205dc9e`: namespace rendering
has one configuration owner, the `:namespaces` block. Cluster
`:seon.config/namespaces` controls only which framework source is stored, and
agent-entity namespace render overrides no longer exist.

## Settled design from the audit

- The public operation is `seon.ai/generate-code!`; it is explicit and
  side-effecting, never an automatic stage of every turn.
- The caller supplies existing `my.plan` concepts: goal, description, and
  falsifiable expectation.
- The planning model emits ordinary ClojureScript REPL input, not EDN plan data,
  JSON, Markdown, or a namespace manifest.
- The existing parser reads the reply once. One pure projection fences the
  parsed entries by actual namespace declaration.
- The namespace DAG derives from generated `(ns ... (:require ...))` forms.
- Schemas are ordinary `schema/register!` forms colocated with their owning
  namespace and evaluated before remaining forms in that namespace.
- Namespace leaves are ordinary `my.plan` steps. Only namespace ownership and
  a scalar claim token are new attributes.
- One database-reactive ready-frontier query schedules work. An atomic claim,
  not observation, grants ownership.
- Eval and behavioral-test evidence determines completion. Worker prose never
  does.
- The full original plan reply and durable steps remain available for recovery;
  the small caller receives a compact derived result.
- One progressive `:generate-code` block teaches and reports the active
  development assignment. It derives its current view from database facts and
  disappears when the agent has no generation work.
- Full source selection continues through the existing `:namespaces` block;
  code generation does not add another namespace renderer or allowlist.
- Kimi K3 is an explicit, high-latency planning option. It is not the default
  executor and never bypasses Seon's contracts or verification.

## Proposed interaction

The calling agent makes one small request:

```clojure
(await
  (seon.ai/generate-code!
    {:my.plan/goal "Add order validation."
     :my.plan/description
     "Reject invalid orders before inventory changes."
     :my.plan/expect
     "Behavioral tests prove invalid orders never reserve inventory."}))
```

The planning agent receives database-derived context and answers with ordinary
REPL input:

```clojure
; Define the shared order data before behavior.
(ns my.order.model
  (:require [seon.schema :as schema]))

(schema/register! ::id :string)
(schema/register! ::order
  [:map [::id ::id]])

(defn valid?
  "True when an order satisfies the registered order contract."
  {:malli/schema [:=> [:cat ::order] :boolean]}
  [order]
  true)

(ns my.order.service
  (:require [my.order.model :as model]
            [seon.schema :as schema]))

; Remaining schemas, functions, and behavioral deftests follow normally.
```

The planner does not separately name plan steps. The projection derives two
namespace leaves and makes `my.order.service` need `my.order.model`.

## Target flow

```mermaid
flowchart TD
  C["Small caller invokes generate-code!"] --> R["Create durable my.plan root"]
  R --> P["Warm planning agent receives retrieved context"]
  P --> O["Ordinary ClojureScript reply"]
  O --> X["parse-forms once; fence namespace units"]
  X --> D["Derive namespace steps and require edges"]
  D --> S["Evaluate ns declaration, schemas, then forms"]
  S --> G{"Unit passes eval and tests?"}
  G -->|yes| Z["Mark namespace step done"]
  G -->|no| A["CAS claim + assignment message"]
  A --> W["Reuse or delegate namespace worker"]
  W --> S
  Z --> F["Reactive query exposes dependents"]
  F --> S
  Z --> T{"Root terminal?"}
  T -->|done or blocked| Q["Return compact derived result"]
```

## Target data delta

After the namespace-symbol migration, extend the existing `my.plan/step` entity
with two optional attributes:

```clojure
(schema/register! :my.plan/namespace :seon.db/ref)
(schema/register! :my.plan/claim :string)
```

- `:my.plan/namespace` points to `[:seon.ns/name 'my.order.model]`.
- `:my.plan/claim` is the opaque preallocated assignment-message identity used
  by `:db.fn/cas`.
- `:my.plan/message` remains the assignment connection.
- `:seon.agent.run/cause` remains the connection from execution to that message.
- `:my.plan/needs` remains the only dependency representation.
- `:my.plan/status` remains the only work status.

Add `my.plan/blocked!` as the public transition for the already-supported
`:blocked` status. Do not add stored ready state, order numbers, worker state,
diffs, code blobs, or a generation entity.

## Target queries

The exact query forms should be finalized against the migrated schema, but the
relations are settled.

### Ready namespace frontier

Input: database value and generation root id. Output: a stable sorted vector of
step id and namespace symbol where:

- the step descends from the root;
- it has `:my.plan/namespace`;
- its status is `:open`;
- it has no `:my.plan/claim`; and
- no `:my.plan/needs` target is unfinished.

Use the existing `my.plan/rules` for descendant, unfinished, blocked, and ready
instead of copying their meaning into a second query rule set.

### Warm namespace worker

Input: namespace ref. Follow completed namespace steps to their
`:my.plan/message`, message recipients, and runs whose
`:seon.agent.run/cause` is that message. Prefer the newest successful recipient
whose derived agent state is idle. Absence delegates a new child. This is a
preference query, never ownership; the claim remains the fence.

### Root result

Input: generation root and one immutable database value. Derive:

- root and namespace-step statuses;
- changed namespace symbols;
- accepted and failed evals connected to the relevant worker runs/messages;
- behavioral-test outcomes; and
- unresolved blocked error handles.

Return the database value identity with the summary so later inspection reads
the same basis transaction.

## Planning context ladder

Use a stable-to-specific gradient:

1. complete production namespace name plus first docstring line, about 3,000
   estimated tokens in the current checkout;
2. exact `generate-code!`, schema, REPL, upsert, test, and deletion teaching;
3. 8–20 relevant compact namespace cards, initially about 4,400–11,000
   estimated tokens;
4. full source for the closest 1–3 owners and their `.internal` descendants;
   and
5. the caller's goal, description, and expectation nearest the response.

Deterministic retrieval is required. Optional embedding hits augment it only
when the one embedding feature is enabled and healthy. Measure recall before
adding namespace summaries to the existing embedding corpus.

## Progressive developer context

Specialized planning and namespace-repair agents receive one additional
`:generate-code` context block. It is not part of ordinary agent context. Its
renderer follows the agent's current run cause to the assignment message, plan
step, and generation root, then derives the appropriate view from the same
immutable database value used for the prompt. There is no stored phase flag.

The stable teaching prefix demonstrates the correct Seon development grammar:

- emit ordinary ClojureScript with real `(ns ...)` declarations and real
  `:require` aliases;
- use `schema/register!` and Malli, colocated with the namespace that owns the
  data;
- use fully namespaced data, immutable transformations, public function
  schemas, errors as values, and behavioral tests;
- redefine a named form to update it; omission leaves it unchanged;
- use explicit `ns-unmap` to remove a function or test; registered-schema
  deletion is unsupported in the MVP;
- explain reasoning only in `;` comments, including multiline `#code` blocks
  when literal text is required; and
- test small forms through the ordinary REPL before claiming the behavioral
  contract is satisfied.

The progressive suffix is derived from evidence:

1. **Initial planning:** root goal, description, expectation, complete
   production namespace summary catalog, deterministic retrieval results,
   relevant schemas/contracts, and full source for the closest owners.
2. **Namespace repair:** original planner reply, the exact fenced namespace
   unit, accepted forms, localized read/eval/test errors, prerequisite results,
   sibling status, and the root acceptance contract.
3. **Verification:** remaining acceptance failures and the exact database/eval
   evidence needed to close them.
4. **Inactive:** empty output, so ordinary turns pay no developer-context cost.

The existing `:namespaces` block carries all source detail. At assignment time
its `:seon.agent.ctx.namespaces/full-source` presence-set is reconciled to the
selected existing owners, the worker's target namespace, and every selected
owner's `.internal` descendants. Requirements not selected for full source
remain compact cards. A newly named namespace has no source to render until an
accepted form creates it. Reusing a warm worker replaces this assignment set;
it never accumulates stale namespaces across generations.

Context is deliberately asymmetric. The planning agent sees broad summaries,
retrieved contracts, and a few full owners so it can design the whole change.
A namespace worker sees the complete root contract and sibling DAG for
orientation, but full source and error evidence only for its owned unit and
dependencies. The compact caller receives only the final derived result.

## Model roles

Define named model profiles through the existing database-owned provider
resolution rather than exposing provider choices to calling agents:

- planning — strong model, large reasoning and context allowance, explicit
  `generate-code!` only;
- execution — low-latency small model for ordinary turns and mechanical repair;
- namespace repair — defaults to execution, overridable after evaluation shows
  a namespace class needs more capability; and
- summarization/embedding — existing specialized paths, not part of code
  generation scheduling.

The current configuration already supports global provider/model/temperature/
output-cap/thinking/timeout/base-URL/key-name values and per-agent overrides for
provider, model, temperature, output cap, thinking, and retries. It does **not**
yet have a named planning/execution profile, a per-agent timeout, or per-agent
base URL/key selection. Stage 7 must extend that one resolution path rather than
adding call-site conditionals. The smallest target is a typed, database-backed
model-profile entity referenced by an agent: explicit request options override
agent values, which override its model profile, which overrides the global row,
which overrides shipped defaults. `generate-code!` assigns the planning profile
mechanically; callers never select a provider.

Provider-specific options stay inside that existing adapter configuration. Kimi
K3 always reasons, currently accepts only maximal reasoning effort, and needs
completion headroom: the live 4,096-token probe returned no visible answer. A
K3 planning profile therefore omits temperature and reasoning effort, raises
the output and wall-clock limits deliberately, and carries a documented cost
ceiling. Empty length-limited output is a normal error value. Because base URL
and credential-variable selection are global today, Kimi and Muse cannot be
independent per-agent OpenAI-compatible profiles in one cluster until that
specific resolution limitation is addressed; separate clusters remain the
honest comparison path.

## Implementation stages

### Stage 0 — approve contracts

**Graduated 2026-07-19.** The owner approved:

- the two-attribute plan delta;
- one coordinated namespace-symbol migration with a database rebuild;
- schema-first per-unit evaluation;
- completion by observed eval/test evidence;
- the progressive developer block and block-owned full-source selection; and
- K3 as opt-in planning rather than the default executor.

### Stage 1 — namespace symbol foundation

Change `:seon.ns/name` and `:seon.ns.require/target` from keyword values to
symbols through the one indexing, config, render, eval, query, and test path.
Rebuild a disposable database; prove idempotent boot indexing and namespace
lookup. Do not support mixed representations.

Exit:

- source and runtime eval write identical symbol namespace values;
- requirement edges point to symbol namespace identities;
- config selection and context rendering consume symbols directly;
- a second index pass writes no changes; and
- the complete affected CLJS gate plus a fresh live cluster pass.

### Stage 2 — pure generation projection

Add one pure function beside the existing REPL parser that groups
`parse-forms` entries into namespace units, classifies actual aliased
`schema/register!` calls, and derives generated require edges.

Exit:

- multiline `#code`, comments, and spans survive;
- two valid namespaces become two units;
- a malformed first namespace cannot capture later forms;
- forms before a namespace remain explicit and unassigned;
- duplicate namespace declarations reconcile deterministically; and
- generated cycles return a structural error before evaluation.

### Stage 3 — schema-first evaluator composition

Compose the existing per-form evaluator over a namespace unit: declaration,
schemas, remaining forms, affected tests. Do not add another compiler state or
program recorder.

Exit:

- local `::schema` keys resolve correctly;
- valid forms on either side of a malformed form are retained;
- dependencies wait for a complete upstream gate;
- explicit `ns-unmap` deletes a function/test and omission does not; and
- all accepted forms appear once in the canonical program facts.

### Stage 4 — durable root and namespace steps

Extend `my.plan` with namespace and claim, add `blocked!`, and idempotently
reconcile the generated namespace DAG beneath one root.

Exit:

- repeated projection creates no duplicate step or edge;
- a changed planner reply revises open namespace leaves without losing done
  evidence;
- namespace steps point to real namespace entities; and
- blocked/reopen transitions preserve evidence and clear claims correctly.

### Stage 5 — reactive scheduler and atomic assignment

Register one root-frontier computation. Compose identity allocation, claim CAS,
assignment message, and step-message link in one writer transaction. Ensure the
worker only after commit.

Exit:

- two simultaneous callbacks produce one assignment;
- unchanged frontiers produce no callback;
- bursts converge on the newest database value;
- a restart after commit but before delegate ensures the same worker work;
- completion exposes the next namespace without polling; and
- releasing a terminal root removes the observer.

### Stage 6 — warm namespace repair

Build database-derived worker selection and the progressive `:generate-code`
context contract. Reconcile each assigned agent's existing `:namespaces` block
to the exact full-source set. Workers use the ordinary REPL and affected-test
surface.

Exit:

- green first-pass units launch no worker;
- a failed unit receives exact original plan, accepted prefix, errors, target
  source, `.internal` source, neighbor contracts, and sibling status;
- a later request reuses an idle successful namespace worker;
- an inactive worker renders no generate-code block and retains ordinary
  transcript continuity for a later assignment;
- reassignment replaces, rather than unions, the namespaces block's previous
  full-source set;
- current database context overrides stale transcript assumptions; and
- impossible work blocks the step and wakes only its owner.

### Stage 7 — public function and model routing

Add `seon.ai/generate-code!` over the approved root, planner, projection,
scheduler, and result query. Add named planning/execution/repair model profiles
to the one provider-resolution mechanism, including the missing per-agent
timeout and compatible-gateway selection required for honest profile isolation.

Exit:

- a small calling model can correctly frame the three-field request;
- its transcript receives one compact result rather than the full planner
  context;
- provider failures, empty K3 output, deadlines, and budget exhaustion are
  error values;
- the default execution provider remains unchanged; and
- K3 is invoked only when the planning role explicitly selects it.

### Stage 8 — MVP live graduation

Run one two-namespace goal with a deliberate first-pass defect through a fresh
isolated cluster and a small calling model.

Exit:

- schemas and the valid namespace are accepted immediately;
- one warm repair worker fixes only the failed namespace;
- the dependent namespace starts only after the prerequisite passes;
- behavioral acceptance passes;
- coordinator restart recovery creates no duplicate message, run, or program
  fact;
- the caller receives an accurate compact result; and
- token, latency, provider cost, turns, retries, and context layers are recorded.

## Deferred until after MVP

- automatic planner selection on ordinary turns;
- deletion of registered schemas;
- semantic rewriting of cyclic namespaces;
- a second embedding corpus for namespace summaries;
- dynamic per-namespace model escalation;
- cross-root global concurrency policy beyond the current agent/run bounds; and
- accepting planner-generated filesystem patches instead of ordinary REPL code.

## Next implementation boundary

Stage 1 is dependency-critical: migrate namespace values to symbols through the
single analyzer/index/config/context/query path, wipe and rebuild an isolated
database, and prove the second index pass is a no-op. Stage 2's pure projection
and Stage 7's model-profile source inventory can proceed as non-overlapping
research, but no orchestration code may assume symbol identities until Stage 1
passes its live rebuild gate.
