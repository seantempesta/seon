---
type: prd
status: ready
tags: [prd, context, render, schema, data-model, web]
---

# Entity debug: curate the data and both renderings

## Outcome

Make the existing debug page a clear, useful view of **one selected database
entity and all its direct attributes**. Each attribute has one section with its
schema description and its AI and HTML renderings side by side. Improve the
underlying data model and shared render functions wherever the page exposes a
poor shape. Juniper is the example, not a special implementation.

Sean should open the running page in the morning and see a substantially better
example, including one coherent plan with real steps, useful messages, and
intuitive identity and namespace information. The outputs must actually work
through the agent's SCI execution path. Screenshots of fabricated data or
finished functions that are not live do not satisfy this PRD.

This is a bounded implementation handoff within the existing context-generation
program. The [working edge](unsettled.md) records the latest owner corrections.
The [earlier lab PRD](design-lab-prd-2026-09-05.md) supplies background, but its
older layout, budget, separate plan-item presentation, and vocabulary proposals
are superseded by the requirements here. Do not reconstruct them from history.

## The product we are building

The debug page helps improve the data and functions that will generate real
agent context. UI and AI output are projections of the same facts. The agent
can query those facts, update its plan, receive messages, and define better
renderers in its own namespace. Shared defaults work for every agent.

The immediate task is not to solve universal graph traversal, semantic ordering,
or automated context optimization. Build a reliable surface for iterating on
each piece of data. Preserve the existing context selection, evaluation reuse,
and comparison capabilities without allowing their machinery to dominate the
entity view.

## Page structure

```text
Selected entity: <actual identity, when available>       Database ID: <eid>
Rendering from: <viewer namespace>                      <database details ▸>

:attribute/key
Description from its registered Malli schema.
Referenced entity links, where applicable.
┌───────────────────────────────┬──────────────────────────────────────┐
│ AI                            │ HTML                                 │
│ namespace=> (actual-form)     │ Useful presentation of the same data │
│ actual evaluated result      │                                      │
└───────────────────────────────┴──────────────────────────────────────┘
Raw data and schema ▸          Applicable render functions ▸

<remaining direct attributes, one section each>

Incoming references
<grouped by attribute; entity links; renderings may be developed here later>
```

Requirements:

- The selected entity is prominent. Display its resolved database ID and useful
  declared identities. Do not imply the viewer agent is the selected entity.
- Any entity can be selected. A reference click changes the subject and URL;
  browser back/forward works. The viewing namespace remains a separate input.
- Direct attributes come first. Include every actual attribute, including
  attributes currently lacking custom renderers. Do not silently omit failed,
  empty, or unacquired values.
- One section per key, including cardinality-many attributes. Determine
  cardinality from installed schema, not from the current number of values.
- A component is rendered as a coherent value within its owning attribute.
  Its children do not become duplicate top-level sections. References remain
  navigable so the user can inspect the component entity separately.
- Put incoming references after direct attributes. They may eventually have
  renderings; do not ban that or mix them into the direct-attribute list.
- Show the key and its description above the paired outputs. Keep exact raw
  data, schema, query provenance, costs, and renderer details accessible through
  disclosure rather than dumping them into the main presentation.
- Show only applicable renderer candidates, in actual selection order. Explain
  the selected function and supplied inputs using the same selection mechanism
  that invokes it. Never fabricate missing AI/HTML candidate slots.
- No headings such as “renderer experiment”, “Block 1/2/N”, “locked context”,
  or “Assembled context”. Do not simply rename duplicate sections and keep the
  same confusing structure.
- A graph is secondary navigation. Preserve selection and layout across normal
  updates. It must not crowd the data or determine rendering order.
- Use existing Phosphor design tokens: warm dark surfaces, clear borders,
  readable cream text, restrained green/blue accents. Avoid excessive orange,
  giant diagnostic paragraphs, and nested scroll boxes for ordinary content.
- Verify desktop and a narrower browser viewport. Paired columns may stack
  when necessary, while keeping their shared attribute heading unmistakable.

## Data modeling requirements

Start by querying the actual selected entity and its direct/incoming attributes.
Create a dated audit table listing the key, owning schema, description, storage
relationship, selected renderers, current defects, and verified result. Derive
this inventory from the database; it is evidence, not a hand-maintained renderer
registry or permanent list of allowed attributes.

Use the existing Malli registration and Datahike schema bridge. Attribute docs
belong in schema `:description` metadata. Keep required fields precise and use
optional keys for genuinely absent information; never persist nil. Preserve
open map contracts. An entity is its attributes and refs, not a new kind stamp.

### One plan on the agent

The owner explicitly requests **one agent attribute referencing a plan component
which owns the plan data and its steps**. Render that value once.

Target relationship, with final names reconciled against the existing registry:

```text
agent -- plan component ref --> plan
plan  -- item component refs --> authored steps
plan  -- current-step ref ----> one of those steps, when selected
step  -- parent/needs refs ---> related steps
```

Reuse the existing `my.plan` owner and stable item identities. Refactor its
lookups, writers, function contracts, schemas, fixture and renderers together.
Remove the replaced ownership representation; do not keep both an agent-owned
plan component and an authoritative reverse-linked plan system.

Component ownership is a database lifecycle relationship, not an embedded opaque
map. Confirm the bridge emits `:db/valueType :db.type/ref`, appropriate
cardinality, and `:db/isComponent true`. Each step has one component owner;
dependency and parent relationships are ordinary refs. Do not make shared
targets components or duplicate a step under multiple parents.

Derive readiness, blockage, progress and completion counts from facts. An absent
current-step ref means no selected focus; it must not hide the whole plan.
Nested transactions, individual step updates, changing focus, dependency
completion and component retraction need focused proofs. Use a disposable
database reset for incompatible changes rather than building migrations.

### Messages, runs and other agent data

Messages and agent activity must be discoverable from meaningful agent-owned
relationships, using the existing durable fact families. Do not copy messages,
evaluation results or run state into display-only mirrors. Component ownership
is appropriate only for genuinely owned data; a message referenced by sender
and recipient is not two owned copies.

Audit run/form/result ownership and explain unfinished moves. Preserve the real
run loop's recovery and settlement behavior. Do not claim the loop is fully
agent-owned because a renderer queries reverse refs. Curate every direct key
that is present; prioritize identity, plan, messages, namespace, and existing
form/result data without inventing unused attributes merely to fill the page.

## Render contracts and quality

Only the existing authored projections: `:seon.render/ai` and
`:seon.render/html`. Functions are ordinary contracted Clojure functions.
Defaults belong in shared domain namespaces and registered schema metadata.
Agent namespace overrides use the same indexed discovery and priority rules.
No Juniper-specific dispatch, renderer roster, or debug-only domain formatter.

### AI

- A renderer may generate source containing comments and forms. Parse and
  execute it through the existing reply reader, call preparation and SCI
  machinery. Display the exact source and actual returned values, using the
  shared transcript printer.
- Thinking comments may precede a form. **Computed answers and explanatory
  output are not comment-prefixed prose.** Never fabricate a REPL result.
- Show the actual current namespace at the prompt. Preserve multiline source,
  stdout, result identity and error semantics consistently with the real loop.
- Prefer natural calls such as `(seon.cluster.agent/whoami)` and a similarly
  clean plan call. The agent must not need to know its ID to discover its ID.
  Defaults come from the scoped environment, including the current database.
- Prefer an argless convenience call and a named, Malli-specified argument map
  for explicit agent/database/value inputs. Verify indexing and call preparation
  for all arities. Do not add wrapper functions solely to prettify one screenshot.
- The plan output should communicate objective, current focus, ready work,
  blocked work and recent completions succinctly. Include executable examples
  for inspecting and updating it, using stable identities and real attributes.
- Teach useful database operations in context. Do not repeat a long explanation
  of `pull`, `q`, `doc`, or every helper in every section. Use existing bulk
  documentation operations when applicable and verify their real arities.
- These outputs must remain usable as real agent context and composable with
  the agent's own forms and results. Preserve structured source/evaluation
  records; concatenated text is a final projection, not stored authority.

### HTML

- Identity: compact labeled facts, not paragraphs explaining what an agent is.
- Plan: clear objective, current step, truthful progress, readable hierarchy,
  completed/ready/blocked steps, dependencies, and expandable step detail.
  Explain how to inspect or update the plan using real forms. Do not invent
  interactive mutation controls that are not actually implemented.
- Messages: distinguish sender, recipient, message content and relevant time or
  state. Preserve the distinction between sample messages and actual agent work.
- Namespace data: explain aliases, refers, imports, source and ownership in
  their actual Clojure meaning. An alias's local symbol and target namespace
  need understandable labels, not an unexplained raw keyword dump.
- Forms/results: readable source followed by real output; metadata outside the
  transcript. Keep historical results distinguishable from current previews.
- Unknown data: improve the existing structural floor where needed. Maps,
  sequences, sets, refs, multiline text and error values should remain useful
  without special-case names. Do not write a second generic renderer.

## Juniper fixture

Use the existing checked-in fixture as the reproducible setup. Give Juniper one
plan with a parent objective and several meaningful steps: completed work, a
current ready step, dependent blocked steps, descriptions and expected outcomes.
The current sample concerns improving context inspection; keep its completion
claims honest. Include at least two sample root messages.

Exercise empty plan, no current step, one item, multiple items, nested steps,
dependency completion, changed title, and completed work. Confirm the whole plan
still appears exactly once. Test a second agent to prove the renderers are shared
and argless lookups use the correct scoped agent.

Do not manufacture success statuses for work that has not happened. Sample
facts are legitimate; fake verification evidence is not.

## Execution, cache and live updates

Reuse the existing invocation cache and shared execution path. Preview evaluation
is memory-only: no run writes, blob staging, agent custody claim, or fault churn
merely because a page was inspected. Explicitly selected evaluations may be
persisted through the existing writer without executing the forms again.

Same relevant inputs reuse the evaluated result. Changed data, renderer code,
schema or scoped inputs invalidate the appropriate result. Unrelated database
transactions and observation-only eval wakes should not repeatedly execute it.
Cached results must preserve the database basis actually used to evaluate them.
An evicted selection fails explicitly; it never secretly reevaluates on Add.

Treat reviewed renderers as pure. Do not add a new effect restriction framework
or promise that a SCI fork rolls back arbitrary external effects.

The development loop must update indexed code, JVM definitions, schema
projection, SCI acquisition, instrumentation and the open browser coherently.
Do not claim success from a changed branch head or successful file reload alone.
If publication is interrupted, diagnose the terminal state before retrying.
Do not serve new renderer declarations against a SCI context missing the
corresponding functions. Keep the last coherent version or expose an explicit
update failure rather than a misleading low-level nil argument error.

Validate public input/output contracts, including after reload. Relevant private
function contracts may also be instrumented. Do not weaken a symbol schema to
accept nil as a cure for missing program facts.

## Known state at handoff — reverify before acting

These are dated observations, not enduring configuration:

| Item | Observed state |
|---|---|
| Working branch | `context-generation-drive`; shared tree contains other agents' edits. |
| Owned development root | `/Users/sean/src/seon/tmp/juniper-context-live` |
| Cluster / browser | `juniper-context`; port 7766 at the time of writing. Discover current state with the operator. |
| Juniper | Entity 34620; namespace `my.agents.juniper`. IDs may change after reset. |
| User's selected subject | 32367 is an alias binding, not Juniper. Its local and target symbols are both `seon.fn`. |
| Direct Juniper attributes | Live query found only agent id, cluster, namespace and `:my.plan/anchor`; plan items/messages were still reverse-linked. Component refactor is not yet proven. |
| Plan source | `4b9d09c9b` improved renderers and five-item fixture; focused tests passed. Fixture installed live, but browser exposed a numeric-ref crash in `render-item-html`. |
| Alias failure | Database contains `seon.render.ns/render-alias-ai` and `/render-alias-html`, while the live SCI program snapshot lacks them. This produces the reported `program-namespace` nil argument failure. |
| Publication | Manifest/schema bugs were fixed in `cefb889ff` and `c5b6c5d2a`; full source publication advanced a head, but development adoption did not complete. `50671c110` applies the existing 15-minute operation deadline to source publication; focused regression passed 1 test/5 assertions. Latest hook still failed with `No such var: run/evaluation-facts`; determine reload ordering/current loaded state before retrying. No publication operation remained active at that handoff. |
| Memory previews | `f37044224`: saved-evaluation transaction gate 1 test/100 assertions. `0ef4406a9`: memory preview/context channel/shared transcript gate 6 tests/123 assertions. Live adoption and provider parity still unverified. |
| Entity layout | Header, descriptions, direct-attribute grouping, incoming references at bottom and removal of numbered headings included in `0ef4406a9`; final cardinality edit postdates that test run. Browser proof pending. |
| Source of evidence | `juniper-example-state-2026-09-06.md` and `juniper-render-quality-2026-09-06.md` under research; older entries are historical. |

Do not assume open tools or old process handles mean work is still running.
Inspect agent/process status and Git diff before taking ownership. Never reset
another session's cluster or discard uncommitted work. Coordinate namespace
reloads and full publication; independently green source slices are not proof
of a coherent live system.

Sean requested committing all open work, including inherited edits. That
checkpoint includes previously uncommitted namespace HTML budget changes;
their presence is not approval to restore rendering limits. Review against
the standing requirement to leave presentation limits disabled. The plan
component refactor has not landed at this checkpoint. The loader diagnostic
guard is being corrected to report the missing function before attempting a
namespace lookup; that correction does not by itself repair partial adoption.

## Existing owners and references

| Responsibility | Read and improve |
|---|---|
| Entity observation and page | `src/seon/render/data.clj`, `src/seon/render/web.clj`, existing route/feed owners |
| Renderer selection and floor | `src/seon/render.clj`, `src/seon/render/value.clj`, `src/seon/render/ns.clj` |
| Identity and plan | `src/seon/cluster/agent.clj`, `src/my/plan.clj`, their registered EDN schemas |
| Messages and activity | `src/seon/cluster/message.clj`, `src/seon/cluster/run.clj`, `src/seon/context.clj` |
| Forms and output | `src/seon/cluster/reply.clj`, `src/seon/cluster/loop.clj`, `src/seon/render/transcript.clj` |
| SCI acquisition and defaults | `src/seon/sci/eval.clj`, `src/seon/sci/kernel.clj`, `src/seon/env.clj`, `src/seon/call_preparation.clj` |
| Source adoption | `bin/seon-hook`, `script/seon/fresh_operator.clj`, `src/seon/cluster.clj`, source publication owner |
| Reproducible example | `docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj` |
| Browser proof | `docs/prds/context-generation/research/juniper_context_browser_probe_2026_09_06.cjs` |

Load applicable repository skills before changing their owners. Read actual
dependency source under `reference-code/`, especially Datahike components and
pull, Malli properties/contracts, SCI acquisition/fork behavior, Datastar updates
and Cytoscape navigation. Use Git history to find prior working approaches,
then improve the existing owner rather than restoring parallel implementations.

## Overnight execution and deliverables

One agent owns integration and personally reviews outputs. Parallel work can
cover plan data/rendering, remaining attribute schemas/rendering, and live
publication/execution correctness with explicit nonoverlapping ownership.
Coordinate shared schema and web changes before publication. Use the strongest
available agents for modeling and integration; avoid repeated whole-suite runs.

Fix the live source/SCI inconsistency early enough to prove every subsequent
change on the actual page. Move the plan to its single component relationship,
then curate remaining attributes against the database-derived inventory. Keep
the existing debug surface running as each coherent slice lands. Do not spend
the entire night building infrastructure without a visible improved example.

Deliver:

1. Committed source, schemas, reproducible fixture and focused regressions.
2. A running, current example with the exact URL and selected identity.
3. An attribute audit with each AI/HTML output personally inspected; list any
   remaining defects explicitly, including structural floor weaknesses.
4. Browser screenshots and exact AI text from the live page, plus repeatable
   proof scripts in the repository. Read screenshots, not just HTTP status.
5. Measured cold/warm page paint, edit-to-visible latency, query/render/eval
   counts for unchanged and changed inputs, and store/basis behavior during
   repeated preview use. A 30-second page load or thousands of redundant calls
   is a defect to investigate, not a successful baseline.
6. A concise handoff naming commits, verified behavior, unfinished behavior,
   running processes and the next design question. Do not claim completion
   while the user is still viewing old code or error dumps.

## Acceptance checks

- The live selected entity is unambiguous; direct attribute keys match the actual
  database inventory, exactly once each. Ref navigation and browser back work.
- Every direct attribute has useful schema documentation and paired output.
  Undocumented keys are fixed in the owning schema, not explained only in chat.
- Juniper's complete plan appears once through its agent-owned component.
  Steps, focus, progress, dependencies and update examples are understandable
  in both projections. A second agent uses the same defaults correctly.
- Messages and namespace bindings have useful shared renderers; arbitrary
  remaining values have a legible floor. No candidate list contains missing
  renderers and no selection depends on Juniper's name.
- Every displayed AI form executes in the correct scoped SCI context and returns
  the displayed value. Exact form/result bytes use the same transcript path as
  actual agent context; verify provider-bound composition without a paid call
  before deliberately choosing any live model test.
- Repeated preview inspection creates no durable evaluations or blobs and does
  not claim the agent. Adding the preview reuses its exact evaluated result.
  Relevant changes refresh; unrelated changes reuse; eviction is explicit.
- Editing a renderer and a schema through the normal development workflow
  updates the already-open page, with matching indexed source, acquired SCI,
  and instrumentation. No manual namespace patch is presented as that proof.
- No unexplained contract errors, red renderer dumps, hidden missing entities,
  or duplicate plan renderings remain in the example. Historical failures are
  labeled as historical facts rather than passed off as current previews.
- Focused tests and browser checks cover these behaviors with nonempty data.
  Passing tests alone, a published head alone, or no JavaScript console errors
  alone do not establish completion.
