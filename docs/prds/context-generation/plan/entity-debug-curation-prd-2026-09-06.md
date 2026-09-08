---
type: prd
status: SUPERSEDED
superseded-by: agent-record-and-turn-loop-prd-2026-09-07.md
tags: [prd, context, render, schema, data-model, web]
---

# Agent entity curation: one attribute, one unit of context

SUPERSEDED by [the turn-loop PRD](agent-record-and-turn-loop-prd-2026-09-07.md); the text below is historical evidence.

## Outcome

An agent entity carries a short list of attributes. Each attribute is one
self-contained unit of data that is relevant to the agent, has an AI rendering
that goes into the agent's context, and has an HTML rendering that a person
sees on the debug page. The debug page is the iteration surface for those
units: it is how the data model and the render functions get better, and the
same units are what context assembly will consume. Juniper is the example; no
mechanism is Juniper-specific.

The rewritten plan below replaces the 2026-09-06 handoff prose. Owner
direction on 2026-09-06 evening: "each attribute we are putting on the agent's
entity is extremely relevant and useful, and each attribute is a self-contained
set of data rendered into the agent's context and into an HTML panel; migrate
data from messages, the run loop, and where evaluations and results are
stored." The [working edge](unsettled.md) records later corrections.

## The dev loop this plan runs on (proven 2026-09-06 evening)

The development cluster is the one `.claude/seon-hook.edn` names under
`:current-source` (today `tmp/juniper-context-live`, cluster `juniper-context`,
web `http://127.0.0.1:7766`). An `Edit`/`Write`/`apply_patch` publishes the
changed files incrementally and the cluster adopts them in place; the open page
repaints through the ordinary feed. Two defects blocked this at handoff and are
fixed with regressions:

- adoption reloaded changed namespaces alphabetically, so a changed caller
  (`seon.cluster.loop`) reloaded before its changed callee
  (`seon.cluster.run`) and failed on the new Var; reload now follows
  `:seon.ns/requires` (`seon.cluster/reload-order`);
- the incremental writer `seon.cluster.source/upsert!` declared full program
  rows while the planner legitimately produces sparse same-identity upserts;
  the honest shape is `:seon.source/upsert-rows`.

Proof: editing the identity kicker in `seon.cluster.agent/render-identity-html`
published commit `6a9e3025…`, the cluster's `:seon.source/commit-id` equalled
the head, and the open page showed the new text with one navigation entry (no
reload). The mechanics are in AGENTS.md §6.

## Inventory — derived from the live database, 2026-09-06 evening

Juniper is entity 34620. Every row below came from `seon.db/q` over the
development cluster; counts are the moment's values, not a roster.

| Relationship | Kind | Count | Renders today | Defect |
|---|---|---|---|---|
| `:seon.cluster.agent/id` | direct, identity | 1 | `whoami` / identity card | usable |
| `:seon.cluster.agent/namespace` | direct ref (unique) | 1 | ns form / ns card | AI shows an error map for "no indexed members", which is the normal state of an agent namespace; alias/refer rows are raw keyword dumps |
| `:seon.cluster.agent/cluster` | direct ref | 1 | cluster prose / card | usable, shallow |
| `:seon.cluster.agent/run` | direct ref, present while a run is open | 0–1 | whoami transcript (wrong unit) | AI renders the agent, not the run |
| `:my.plan/anchor` | direct ref | 1 | one plan item | the plan renders as five reverse-ref cards plus the anchor; `render-item-html` throws on a numeric ref (`Don't know how to create ISeq from: java.lang.Long`) |
| `:my.plan.item/agent` | incoming | 5 | per-item cards | duplicated plan; no one-plan view |
| `:seon.cluster.message/to` | incoming | 3 | message prose / card | usable; 2 root messages plus the bootstrap task |
| `:seon.cluster.run/agent` | incoming | 548 → 609 during inspection | run line + transcript | 545 are `source:` preview runs created by inspecting the page; the ruling says previews stay in memory |
| `:seon.cluster.eval/run` (via runs) | two hops | 606 | transcript | same leak; results are the useful data, runs are the wrapper |
| `:seon.context.contribution/agent` | incoming | 4 | "Assembled context" numbered blocks | banned layout; the data itself is right |
| `:seon.error/agent` | incoming | 5 | none | not shown at all |

Schema descriptions exist in the stored `:seon.schema/form` strings (for
example `:my.plan/anchor`) but the page prints none of them; the extraction
seam is wrong, not the data.

## Target agent entity

The section list of the page is the list of units the agent schema declares,
in declared order. A unit is either a stored forward attribute or a declared
reverse relationship (Datahike pulls both; `:seon.cluster.message/_to` is as
much "an attribute of the agent" as a forward ref). Incoming references that no
unit declares fall to the bottom under "Other references".

| Unit | Stored shape | AI rendering says | HTML panel shows |
|---|---|---|---|
| identity | `:seon.cluster.agent/id`, `:seon.cluster.agent/cluster` | `(seon.cluster.agent/whoami)` and its three lines | id, namespace, cluster as labeled facts |
| namespace | `:seon.cluster.agent/namespace` → ns entity | the `ns` form; aliases and refers as "local → target"; "no definitions yet" as an ordinary empty state | the same, plus a link to the namespace page |
| plan | `:my.plan/steps` (component set, agent-owned), `:my.plan/current-step` (ordinary ref); steps own `:my.plan.item/steps`; `:my.plan.item/needs` stays an ordinary ref; `:my.plan.item/position` orders siblings | objective, current step, ready, blocked, recent completions, one real update form | tree with state per step, progress counts derived, expandable step detail |
| messages | `:seon.cluster.message/_to` (declared reverse) | newest-last inbox with sender and time | sender → recipient, time, content |
| current run | `:seon.cluster.agent/run` | one line: what is running, since when | same, with a link to the run |
| history | `:seon.cluster.run/_agent`, each with `:seon.cluster.eval/_run` | the agent's own submitted forms and results, newest first, bounded with an elision value | transcript per run, historical results labeled as such |
| selected context | `:seon.context.contribution/_agent` | the ordered selected evaluations as they will be sent | same, with add/remove/compact actions |
| faults | `:seon.error/_agent` | none by default | kind, message, run link |

Stated assumptions (reversible in the morning, one attribute each):

- **A1.** A declared reverse relationship counts as an attribute of the agent.
  Storing a forward duplicate (`agent/messages` beside `message/to`) is two
  fact families for one noun and is refused.
- **A2.** `:my.plan/anchor`, `:my.plan.item/agent`, and `:my.plan.item/parent`
  are retired in the same change that installs the component tree; the dev
  cluster is reset, never migrated.
- **A3.** Preview evaluation creates no run, form, evaluation, or error facts.
  The 545 existing `source:` runs are disposed of by the reset in A2.
- **A4.** Unit order is declared in the agent schema and is the same order in
  both projections.

## Render contracts

Only `:seon.render/ai` and `:seon.render/html`. Defaults live on the owning
domain schema (`seon.cluster.agent`, `seon.ns`, `my.plan`,
`seon.cluster.message`, `seon.cluster.run`, `seon.context`), discovered by the
existing selection; an agent namespace may override with the same priority
rules. AI renderers produce source that runs through the reply reader, call
preparation, and the SCI fork; the page shows the exact form at the real
namespace prompt and the exact returned value through the shared transcript
printer. Thinking comments may precede a form; results are never comment
prose. Owner intent (2026-09-06 late): the comments exist to teach the agent
what is going on, the forms produce results printed in the shape most useful
to the agent, and the HTML projection of the same facts is structured
differently, for a person reading the page. Argless calls resolve the calling
agent from the scoped environment; the named request-map arity is the explicit
form. `:seon.render/form` is retired and nothing here declares it.

Every attribute's `:description` renders above its paired outputs. Undocumented
attributes are documented in their owning EDN schema, not in prose here.

## Migration steps, in order

1. **Plan component.** Install `:my.plan/steps`, `:my.plan/current-step`,
   `:my.plan.item/steps`, `:my.plan.item/position`; retire the three
   backlinks; move `plan!`, `add!`, `complete!`, `plan`, `item`, readiness
   rules, and both renderers onto the tree; fix the numeric-ref crash by
   contract (the renderer receives pulled maps, never bare ids); rewrite the
   fixture to the new shape; prove empty, one, nested, dependency completion,
   changed title, no current step, second agent.
2. **Page as declared units.** Read the unit list from the agent schema;
   one section per unit with key, description, paired outputs, raw data and
   candidates under disclosure; cardinality-many is one section; undeclared
   incoming refs at the bottom; delete "Assembled context", "Block N", locked
   vocabulary; fix description extraction; selection actions stay but sit
   inside the selected-context unit.
3. **Previews in memory.** Inspection evaluates through the shared evaluator
   and invocation cache without run, form, evaluation, or fault writes; Add
   persists the cached result without re-execution; eviction refuses loudly.
   Prove with the run count before and after ten page loads.
4. **Messages, history, faults units.** Shared renderers on the owning
   schemas; message alias/attribution kept; history bounded newest-first with
   an elision value; faults listed, not hidden.
5. **Namespace unit.** Empty state instead of an error map; alias and refer
   rows labeled in Clojure's own words.
6. **Reset, reseed, prove.** `bin/seon --root tmp/juniper-context-live reset
   --force`, reinstall the fixture, screenshot each unit, record the exact AI
   text per unit in the research note, measure cold and warm paint.

## Lanes and ownership

One orchestrator integrates, reviews every diff on the live page, and commits
path-limited. Lanes are file-disjoint and run bare.

| Lane | Steps | Owns | Must not touch |
|---|---|---|---|
| plan | 1 | `resources/seon/schemas/my.plan*.edn`, `src/my/plan.clj`, `test/my/plan_test.clj`, `docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj` | `src/seon/render/web.clj`, agent schema |
| page | 2, then 3 | `src/seon/render/web.clj`, `src/seon/render/data.clj`, `resources/public/css/input.css`, `resources/seon/schemas/seon.cluster.agent.edn`, `test/seon/render/web_test.clj` | `src/my/plan.clj`, message/run owners |
| units | 4, 5 | `src/seon/cluster/message.clj`, `src/seon/cluster/run.clj` (render functions only), `src/seon/render/transcript.clj`, `src/seon/render/ns.clj`, their EDN schemas and tests | `web.clj`, plan files |

Step 6 is the orchestrator's. Shared-schema edits (an attribute two lanes
need) go through the orchestrator in one commit before either lane depends on
it.

## Acceptance checks

- The page lists exactly the declared units once each, in declared order,
  then "Other references"; every section shows the attribute's description.
- Juniper's plan appears once, through `:my.plan/steps`; a second agent
  renders through the same defaults with the correct scoped agent.
- Every AI form shown executed in the correct scoped SCI fork and returned the
  displayed value; the transcript bytes match what context assembly emits.
- Ten reloads of the page change no run, evaluation, or error count.
- A renderer edit and a schema edit each reach the open page through the hook
  with the cluster commit equal to the head; no manual reload is presented as
  proof.
- No renderer error dumps, no numeric entity ids in plan or message text,
  historical results labeled historical.
- Focused tests cover each unit's renderers with nonempty data;
  `bin/test` platform tier stays green.

## Vigilance while lanes run

Each orchestrator wake: `bin/seon --root tmp/juniper-context-live status`;
tail of `logs/hook-debug.log` for advisories; page text for
`:seon.error/kind`; Juniper's run count as the leak detector; lane heartbeats
by `git log`. Platform breakage is fixed before feature work resumes.

## Handoff shape for the morning

Commits by lane; screenshots per unit; exact AI text per unit; what is proven
on the live page versus by tests alone; open defects as issues; the next design
questions with two to four priced options each.

## References

- [Plan component schema research](../research/plan-component-schema-2026-09-06.md)
- [Plan data and rendering research](../research/plan-agent-data-and-rendering-2026-09-06.md)
- [Juniper example state](../research/juniper-example-state-2026-09-06.md)
- [Fixture](../research/juniper_fixture_2026_09_06.clj)
- [Browser probe](../research/juniper_context_browser_probe_2026_09_06.cjs)
- Owners: `src/seon/render/web.clj`, `src/seon/render/data.clj`,
  `src/seon/render.clj`, `src/my/plan.clj`, `src/seon/cluster/agent.clj`,
  `src/seon/cluster/message.clj`, `src/seon/cluster/run.clj`,
  `src/seon/render/transcript.clj`, `src/seon/cluster.clj`
  (`development-source-refresh!`).
