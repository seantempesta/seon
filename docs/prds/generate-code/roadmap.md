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

The pure parser projection began at `824575c4` and the shared ordered evaluator
landed at `c292ee2d`. `parse-program` reads a reply once and `project-program`
retains entries and spans, groups repeated declarations as sections of one
namespace, recognizes schema registration through each section's actual
aliases/refers, derives deterministic generated requirements, fences malformed
declarations, and returns structural cycle errors. Ordinary `:batch` turns now
consume its execution projection through the existing `eval-batch!`: namespace
dependencies order sections, declarations fence leakage, schemas precede the
remaining authored forms, independent namespaces survive failure, and ordered
eval IDs plus skipped entries remain the evidence seam for `generate-code!`.
The final source-generation gate passed 1,223 tests/5,485 assertions; focused
parser/eval/turn proof passed 77 tests/476 assertions. Current-artifact ordered
execution and cold-child reconstruction are now live-proven; orchestration can
consume this seam without changing the parser.

The exact current artifact now proves dependency ordering and top-level Promise
sequencing through a real execution child. Source authored the consumer before
its base namespace; the projection evaluated the base first. A Promise form
resolved to `42` before the following definition read its atom, and consumer
calls before and after a repeated namespace declaration both returned `43`.
That proof exposed and closed one cold-restart mismatch. A function already
compiled in the warm child kept using its alias after bare namespace reentry,
while the analyzer and database require edges had both lost it. The eval owner
now merges the namespace's prior real libspecs into a bare reentry before
cljs.js evaluates it; explicit `:require` declarations retain replacement
semantics. The affected gate passes 54 tests/231 assertions. On a fresh
database the ordered six-form batch completed 6/6, persisted
`[my.live.base-721 :as base]`, and awaited the final Promise to `43`. After a
full watcher/writer/pod restart, a new execution child evaluated the bare
consumer declaration and `(answer)` 2/2, with the durable result `43`.

Canonical behavioral-test evidence now follows the same eval seam. The test
runner writes its native summary counters and selected `:seon.test` refs onto
the exact `:seon.eval` entity that caused the automatic run. Because that eval
already belongs to a turn and the turn belongs to a run, generation completion
can follow the existing run/message/plan connections without inferring from
global latest-test timestamps or storing a second generation result. The
affected gate passed 610 tests/2,791 assertions; live current-artifact proof
remains part of the Stage 3 checkpoint.

Ordinary agent replies already pass through `parse-forms` once and
`eval-batch!` evaluates entries sequentially. Each whole top-level Promise is
awaited before the next entry, successful `(ns ...)` forms change the active
namespace for later entries, failures do not discard later independent forms,
and the return already carries ordered `:seon.eval/ids`. Ordinary replies do
not yet derive namespace units or dependency order. The pure projection and
ordered namespace executor below strengthen that one path; they are not a
second parser or evaluator.

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

## Native multi-namespace parser and executor contract

The parser, executor, and orchestration have distinct jobs.

### Parse and project once

`seon.repl.internal/parse-forms` remains the only forgiving reader. It keeps
the authored lexical order, original/evaluable source, narration and comments,
spans, mechanically repaired delimiters, and `#code/<lang> <<SENTINEL`
heredocs. One pure projection over those entries:

- assigns each entry to the last structurally valid `(ns ...)` declaration;
- leaves forms before the first declaration explicitly unassigned;
- records duplicate declarations deterministically without losing either
  source span;
- derives namespace requirements only from real `ns` `:require` clauses;
- recognizes `schema/register!` through the alias declared by that namespace,
  not by textual matching;
- preserves malformed entries in their source location; and
- returns generated dependency cycles as structural errors before evaluation.

Repair stays mechanical. It may balance delimiters, preserve independently
readable forms, and turn the existing heredoc syntax into valid quoted string
data. It must not invent a namespace, dependency, schema owner, `await`, or
intended program meaning. The transcript shows the parser's existing repaired
source and repair note so the agent learns the valid form that actually ran.

### Execute dependency order, preserve authored order

The executor topologically orders namespace units from their generated
requirements. Within one namespace it preserves authored order except for the
settled schema-first phases:

1. evaluate the `ns` declaration;
2. evaluate that namespace's recognized `schema/register!` forms in authored
   order;
3. evaluate remaining definitions and ordinary forms in authored order; and
4. run the existing affected behavioral-test gate.

A dependent namespace becomes eligible only after every generated prerequisite
passes its complete gate. Independent namespace units may run concurrently.
One bad form does not erase valid forms on either side, but a namespace cannot
be marked done while any required eval or behavioral contract fails. Accepted
forms continue through the existing eval, analyzer, schema publication, and
program-fact paths exactly once; there is no generated-file authority,
compiler-state fork, replay log, or second indexer.

### Retain full evidence, return a compact result

Every parsed entry already records one durable eval and `eval-batch!` returns
its ordered `:seon.eval/ids`. The generation workflow retains those ordinary
eval facts, including namespace, original and repaired source, timing,
result/error, and test evidence. It derives namespace status and diffs from the
same database value instead of storing duplicate result or diff blobs.

The small calling agent receives one ordinary result map derived at an immutable
database value. Its final schema must reuse established `my.plan` status and
step connections, `:seon.ns/name`, and the existing ordered `:seon.eval/ids`
rather than introduce a generation/result vocabulary. The rendered result
shows the combined source diff, namespace-level applied/failed status, failed
forms with the minimum repair context, acceptance evidence, and the existing
eval/result IDs for expansion. Successful values remain queryable but are not
dumped into the caller's context by default.

### Drive namespace-focused repair agents

The namespace DAG is the input to the database-reactive scheduler, not work the
small caller performs. Green first-pass units need no worker. Each failed ready
unit is atomically claimed and sent to one reusable namespace-focused agent
with:

- the original complete planning reply and root acceptance contract;
- its exact namespace unit, accepted forms, failed eval IDs, and test errors;
- prerequisite results and compact sibling status;
- full current source for its namespace, selected owners, and their `.internal`
  descendants; and
- ordinary REPL instructions for redefining, testing, and explicitly deleting
  functions/tests.

Completion is derived only from successful eval and behavioral-test facts.
Worker prose or an empty reply cannot mark a unit done. A blocked worker records
the existing `my.plan` blocked transition plus its error evidence; that wakes
only the owning coordinator. When all ready work settles, the reactive frontier
exposes dependents without polling. A later generation prefers the warm idle
agent that previously completed that namespace, preserving useful transcript
continuity while replacing stale assignment-specific context.

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

The current configuration supports the complete non-secret provider surface on
each agent entity: provider, model, temperature, output cap, thinking, timeout,
base URL, credential-variable name, DiffusionGemma backend, extra body, and
retries. Explicit request options override agent values, which override the
global row, which overrides shipped defaults. Commit `0a99a7d9` adds named
sparse `:planning` and `:execution` variants plus a request-only selector on
every agent-birth function. The selected ordinary agent attributes are copied
into the atomic birth transaction, so `generate-code!` selects a role name and
callers never choose a provider or carry its configuration.

Provider-specific options stay inside that existing adapter configuration. Kimi
K3 always reasons, currently accepts only maximal reasoning effort, and needs
completion headroom: the live 4,096-token probe returned no visible answer. A
K3 planning profile therefore omits temperature and reasoning effort, raises
the output and wall-clock limits deliberately, and carries a documented cost
ceiling. Empty length-limited output is a normal error value. Kimi and Muse can
run as independent agents in one cluster because endpoint and credential-name
selection are part of each agent's derived configuration.

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

**Graduated 2026-07-19 at `824575c4`, refined at `c292ee2d`.** `parse-program`
and `project-program` landed in the existing parser namespace with the full
exit battery below.

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

**Source-complete at `c292ee2d`; current-artifact live proof pending.** The
ordinary batch turn consumes `:seon.repl/eval-entries` and the existing
`eval-batch!` owns all evaluation, Promise resolution, analyzer publication,
program facts, and ordered eval IDs. No second compiler or recorder exists.

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
scheduler, and result query. Select planning/execution/repair settings through
the existing named model variants, whose values are copied onto the new agent
at birth. The variant mechanism is complete at `0a99a7d9`; this stage consumes
it without branching on providers.

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

Stage 1 remains the persistence-critical boundary: migrate namespace values to
symbols through the single analyzer/index/config/context/query path, wipe and
rebuild an isolated database, and prove the second index pass is a no-op. The
completed Stage 2 projection allows Stage 3's invocation-local evaluator
composition to proceed independently. Stage 4 database reconciliation and all
later orchestration must not assume symbol identities until Stage 1 passes its
live rebuild gate. Named model variants are available now; their isolated Kimi
compatibility proof can also proceed without touching namespace persistence.
