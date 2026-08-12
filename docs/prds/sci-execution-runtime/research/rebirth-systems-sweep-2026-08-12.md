---
type: research
status: complete
tags: [research, context, render, database]
---

# Rebirth systems sweep — 2026-08-12

## Verdict

No: the agent-facing system does not yet regenerate a complete compact current
state from database facts alone. The strongest complete pieces are individual
messages, runs, errors, effective configuration, and maintenance summaries.
The highest-value absent pieces are spend since the last shown basis and a
small durable home for agent notes. The most dangerous false-complete piece is
messages: its declared forms produce calls to `my.message/inbox` and
`my.message/read`, but neither function exists.

This sweep applies rulings 47–50. Rulings 49 and 50 landed while the sweep was
running, so the named PRD was read end to end again after both changes. They
settle two questions that were open at the start: `my.plan` is retired unbuilt
in favor of one todo system, and external effects replay from explicitly old
receipts by default with fresh re-execution allowed only by a declared
read-only tag
(`docs/prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md:236-283`).

The audit standard is deliberately stronger than “some facts exist” or “the
generic value floor prints it.” A row passes only when current meaning is
durable, the important shape declares its own compact render, and a generated
form can reproduce that value at rebirth without reconstructing it by replaying
history.

## Dependency ledger and method

| Dependency or mechanism | Selected revision | Boundary read |
|---|---:|---|
| Datahike | `cdcb5792db8bd599487f099437265d18a31164a5` | `since` wraps a database value and filters datoms after the named basis; branch and commit reads are separate versioning primitives (`reference-code/datahike/src/datahike/api/impl.cljc:148-151`; `reference-code/datahike/src/datahike/query/execute.cljc:745-761`; `reference-code/datahike/src/datahike/versioning.cljc:182-210,463-510`). |
| Malli | `3517a3cd9271b2083780ac7be1725493905bca2e` | Open maps and schema properties are the declaration substrate used by render and data facts. |
| SCI | `fcbd8862800e638dc0f8f5521111f999279cbcd2` | Generated forms execute in the ordinary turn fork; no separate rebirth interpreter is required. |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | Flow channels carry only losable in-flight values; rebirth state therefore must be database facts. |

The shortest falsifier was static and decisive: enumerate the named systems,
locate their entity/value schema properties, then verify that each declared
`/form` names an executable current read. Source and schema were read directly;
no production path was edited. The ruling-40 test-result files were changing
concurrently and are labelled **in flight**, never credited as landed.

## Compatibility matrix

“Smart argument” means the ruling-32 pattern: empty history emits a full
current read; an already-shown collection emits a query over a Datahike
`since` database and then current pulls for affected identities. The render is
pure over the returned full or delta value. Retractions additionally require
the generator's current-membership comparison; `since` alone cannot enumerate
an entity that now has no matching datom.

| Agent-facing system | Fact-backed now? | Declared compact current-state render? | Exact delta | Smart-argument fit |
|---|---|---|---|---|
| Messages | **Yes.** Identity, content, endpoints, time, order, causality, and subject are durable (`resources/seon/schemas/seon.cluster.message.edn:1-79`; `src/seon/cluster/message.clj:306-423`). | **Declared but not executable.** The entity declares AI/HTML/form and `to` declares the inbox form (`resources/seon/schemas/seon.cluster.message.edn:48-56,87-91`); the faces are compact (`src/seon/cluster/message.clj:429-471`). But the forms emit `(my.message/read id)` and `(my.message/inbox)` (`src/seon/render/transcript.clj:847-857`) while `my.message` defines only `send` and `decline` (`src/my/message.clj:48-95`). | Implement the two fact-reading functions in the existing `my.message` owner with declared contracts; keep the existing schema form properties. Hand pulled endpoint identities to the renderer so it no longer queries while rendering (`src/seon/cluster/message.clj:450-457`). | **Yes.** Full inbox at rebirth; later `since` query plus current pulls for new/changed IDs. No new inbox “delta arity.” |
| Runs, forms, and eval receipts | **Yes.** Runs, component forms, terminal receipts, custody, trigger, interruption, and disposition evidence are facts (`resources/seon/schemas/seon.cluster.run.edn:1-107`; `resources/seon/schemas/seon.cluster.run.form.edn:1-41`; `resources/seon/schemas/seon.cluster.eval.edn:27-78`). | **Partial.** Run/form/receipt AI and HTML are declared; generic identity form is honest. The run face is compact, but it queries forms and receipts during rendering (`src/seon/cluster/run.clj:1728-1747,1773-1870`) rather than consuming the acquired value. | Give generation a connected current-run value containing the required form/receipt projections and make the render pure over it. A specialist overview form is useful but not required for totality. | **Yes**, with current-membership comparison for custody release or other retractions. |
| Errors and faults | **Yes.** Normalized immutable facts carry identity, time, process, kind, message, signature, evidence, and optional agent/run refs (`resources/seon/schemas/seon.error.edn:1-75`; `src/seon/error.clj:870-934`). | **Partial.** Per-error AI/HTML is declared and compact (`src/seon/error.clj:1039-1088`). The current aggregate declares AI/HTML (`resources/seon/schemas/seon.problems.edn:52-79`), but its AI twin omits error-signature, wedged-run, and failed-run families that HTML includes (`src/seon/problems.clj:456-525,561-584`). It also mixes database facts with live-process and loaded-namespace observations (`src/seon/problems.clj:365-400,527-559`). | Complete `seon.problems/ai-prose`; declare a current-problems form. Split or explicitly label the non-database liveness/JVM observations instead of claiming they survive from facts. No new per-error members. | **Yes** for durable error families: append-only facts, since identities, current pulls, rebirth grouping by signature/count/latest. |
| The agent's defs | **Yes.** Current agent-scoped rows carry namespace/name, faithful EDN/blob/size or unrestorable reason, atom flag, and ordinal (`resources/seon/schemas/seon.def.edn:1-41`); settlement replaces owned facts and turn creation installs from facts (`src/seon/cluster/run.clj:1403-1467`; `src/seon/sci/eval.clj:1441-1515`). | **No.** `:seon.def/def` declares no AI, HTML, or form (`resources/seon/schemas/seon.def.edn:7-32`). Functional restoration succeeds, but cognitive rebirth does not say what was restored. | Declare AI/HTML/form on the def entity; compactly render namespace/name and faithful value/size or unrestorable reason; add a per-agent listing form from the existing `:seon.def/agent` connection. No new facts. | **Yes.** Full listing at rebirth; changed IDs after a basis. Explicit clear is a retraction and therefore uses current membership. |
| Program facts — namespaces, functions, schemas | **Yes.** The canonical identities and ownership attrs are explicit and the one index emits their rows (`src/seon/program.cljc:10-45`; `src/seon/fn.clj:313-384`). | **Partial.** Namespace declares AI/HTML/form, while function and schema declare form (`resources/seon/schemas/seon.ns.edn:7-22`; `resources/seon/schemas/seon.fn.edn:14-44`; `resources/seon/schemas/seon.schema.edn:46-57`). Namespace rendering compacts its functions/schemas but queries the database (`src/seon/render/ns.clj:39-81,289-475`). Injected documentation currently indexes public functions only, so ruled namespace/schema/test and vararg `doc`/`dir`/`docs` are not all executable (`src/seon/sci/eval.clj:1069-1149`). | Extend the one injected documentation mechanism to namespaces, schemas, and tests and to its ruled arities; make namespace rendering consume the acquired aggregate/delta. Do not add a second registry. | **Yes** for addition/replacement. Deletion requires current membership. |
| Tests and latest results — ruling 40 | **In flight.** Tests are facts. The live lane is moving latest pass/fail/error/basis/time/failure facts onto each test row and deleting the historical result graph (`resources/seon/schemas/seon.test.edn:1-78`; `src/seon/test/runner.clj:836-906`). | **No.** The current in-flight test entity still declares no AI/HTML/form, and tests are not included by the function-only namespace query (`resources/seon/schemas/seon.test.edn:48-78`; `src/seon/render/ns.clj:47-59`). | Finish ruling 40 on the ordinary runner path, delete the obsolete result graph, and declare test AI/HTML/form. Recommended compact value: test claim/subject plus latest counts, basis/time, and bounded failure; form `(doc 'qualified-test)`. | **Yes.** A result replacement touches the test identity, so `since` finds it and a current pull supplies the whole latest result. |
| Todo | **Partial.** Unanswered messages are derived from the absence of a run trigger ref (`src/seon/cluster/work.clj:686-717`); open runs are facts; failing tests depend on ruling 40. No authored todo fact exists. | **No union shape.** Individual message/run faces do not implement ruling 49's one current task view (`docs/prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md:262-270`). | Add `:my.todo/id` identity, `/agent` ref, `/title`, optional `/parent`; presence means open and completion retracts the entity. Add non-entity `:my.todo/current` union of unanswered messages, open runs, failing tests, and authored items, declaring AI/HTML/form; `(my.todo/current)` renders grouped remaining work with counts/elision. | **Yes.** Full union at rebirth; since-affected IDs plus current membership later. Retraction makes completed items vanish without a status flag. |
| `my.plan` | **No, intentionally.** It was never built and agents have no plan relation (`resources/seon/schemas/seon.cluster.agent.edn:1-16`). | **No, intentionally.** | **No implementation.** Ruling 49 retires `my.plan`; archive the now-superseded plan issue and remove stale target vocabulary. Todo owns agent-authored intentions. | Not applicable; a generated `my.plan` form is now a defect. |
| Agent memory and notes | **No general home.** `:my.run/note` is only a paused run's wait reason (`resources/seon/schemas/my.run.edn:9-32`; `src/my/run.clj:117-131`), extracted by the run face (`src/seon/cluster/run.clj:1803-1816,1853`). It is not durable general knowledge. | **No.** There is no general note entity, collection, form, or compact current render. | Recommended minimal `my.note` design is priced below. | **Yes** for dedicated notes: full current collection at rebirth; since identities plus current membership later. Self-messages do not fit because they wake work and reconstruct knowledge from a transcript. |
| Schedules and maintenance | **Yes.** Schedule, task, fire, request/result, and maintenance receipt facts are explicit (`resources/seon/schemas/seon.schedule.edn:1-41`; `resources/seon/schemas/seon.schedule.task.edn:1-13`; `resources/seon/schemas/seon.schedule.fire.edn:1-46`; `resources/seon/schemas/seon.maintenance.receipt.edn:1-47`). Five root tasks are seeded as facts and fire+receipt are claimed atomically (`src/seon/schedule.clj:35-99,283-361`). | **Partial.** The derived latest-per-task maintenance report declares compact AI/HTML (`resources/seon/schemas/seon.maintenance.edn:21-25`; `src/seon/maintenance.clj:220-286,388-427`), but has no form, omits schedule expression/zone, and is not a generation-reachable root value. | Extend each existing report entry with schedule id/expression/zone; declare `seon.maintenance/report-form` returning `(seon.maintenance/report)`. Select that aggregate rather than raw fire history. Do not store next-fire or status. | **Yes.** Full latest-per-task report at rebirth; `since` finds changed task/fire/receipt identities, then current pulls supply stable owner/request facts that may predate the basis. |
| Subagent supervision | **Partial.** Agents, messages, runs, forms, and receipts are facts, but no supervisor/child connection exists (`resources/seon/schemas/seon.cluster.agent.edn:1-16`; `src/seon/cluster/agent.clj:93-112`). The current first-agent path constructs a special root run and parses prior source to infer whether root read/sent (`src/seon/bootstrap.clj:309-425`). | **No.** Agent self-situation cannot render direct children and their current work. | Add one child-side `:seon.cluster.agent/supervisor` ref at creation; derive a supervision value with child ID, current todo/open-run summary, and latest terminal result/problem; declare AI/HTML/form. Delete the special bootstrap supervision forms only after parity. No stored status aggregate. | **Yes.** Full direct children at rebirth; since affected child IDs and current membership later. |
| Configuration | **Yes.** One exact-reconciled effective row per cluster is the database authority (`src/seon/config.clj:448-500,535-560`). | **Partial.** The generated `:seon.config/entity` declares bounded AI/HTML (`src/seon/schema/edn.clj:82-103`; `src/seon/config.clj:51-90`) but no form. The face hand-picks model/thinking/token cap/eval limit/Flow/core-fault mode and supplies no deeper-read indicator, so most current dials are invisible. | Declare `seon.config/render-form`, spelling `seon.config/effective` for the selected cluster. Derive compact groups from the schema-declared dial set rather than a maintained key list; fit/elide normally and preserve the deeper form. | **Yes.** Rebirth reads the full effective row; a later changed datom identifies the singleton and generation full-pulls it. |
| Provider usage and spend | **Partly.** Every attempt is durable and stores provider usage as opaque no-history EDN; normalized token keys exist only as value schemas (`resources/seon/schemas/seon.ai.edn:4-56,112-125`; `resources/seon/schemas/seon.ai.attempt.edn:1-15`; `resources/seon/schemas/seon.ai.usage.edn:1-4`; `src/seon/cluster/loop.clj:725-829`). Model price rows are facts (`resources/seon/schemas/seon.ai.model.edn:10-14,68-125`). | **No spend view.** Attempt AI/HTML is a generic bounded fact view and model rendering shows the rate card/latest latency, not incurred spend (`src/seon/ai.clj:100-120,184-227`). | On each attempt, transact normalized prompt/cached/completion/total tokens and the effective input/cached/output price inputs used for that attempt; derive money, do not store a second spend total. Add a non-entity `:seon.ai/spend-summary` with AI/HTML/form, counts for missing usage/pricing, and form accepting the last shown basis. | **Excellent fit and highest-value render.** Fresh summary = all attempts; shown summary = `(spend {:since shown-basis})`; pure render totals only the supplied delta and labels its interval. For a cumulative headline, generator carries retained prior aggregate as input or re-reads current facts—never asks the renderer to query. |
| `my.branch` | **Partial substrate only.** Runs record opening commit ID, but no agent checkout branch/commit relationship or agent-facing namespace exists (`resources/seon/schemas/seon.cluster.run.edn:30,57-101`). `seon.db` reads commit IDs out of database values but cannot obtain database values from branches/commits (`src/seon/db.clj:1050-1078`). | **No.** No status/log/diff result faces. | First land branch roster, `branch-as-db`, `commit-as-db`, and parent reads in `seon.db`. Then add agent checkout branch/commit refs and `my.branch` checkout/log/diff/status. Declare status/log AI/HTML/form; keep diff explicit between two bases. | **Mostly.** Status is current. Log's smart cursor is the last shown commit ID and parent walk, not Datahike `since`; diff takes explicit bases. |
| Packages | **No current package facts or agent surface.** The target places native `package.json`/`deps.edn` under each cluster's `packages/` tree and package wrappers behind the same capability door (`docs/seon/architecture/toolkit.md:199-213`), but current `src/`, `resources/seon/schemas/`, and cluster data contain no package owner. | **No.** | Before package execution lands, declare package identity, ecosystem/native manifest digest, installed artifact digest, wrapper namespace refs, and installation/error receipt facts. Add compact package roster/detail AI/HTML/form. Manifest contents stay native artifacts; database facts carry identity/digest/current installation truth. | **Yes** for database installation facts: full roster at rebirth, since changed package IDs later. Filesystem manifest freshness is an external observation and therefore follows ruling 50. |
| External world — `my.fs`, `my.shell`, `my.web` | **Receipts yes; world no.** Every request opens a durable receipt before dispatch and settles it once (`src/seon/effect.clj:512-688`; `resources/seon/schemas/seon.effect.edn:16-58`). Receipt AI/HTML is declared. | **Partial.** Receipts can render the last observation, but no `/form` marks it old or offers deliberate refresh; no agent-scoped latest-observation aggregate exists. `context-suffix` remains a prompt-only hand assembly (`src/seon/effect.clj:690-779`). Capability metadata records handler and scheduling workload only; it cannot distinguish read-only operations (`resources/seon/schemas/seon.fn.edn:35-44,65`; `src/my/fs.clj:65-122`; `src/my/shell.clj:49-57`; `src/my/web.clj:8-34`). | Ship receipt-old default: add receipt `/form` and an agent-scoped latest-receipts report grouped by owner+canonical request, showing `settled-at` and “OLD/not current”; replace `context-suffix`. Then lift a declared `:seon.effect/read-only?` function fact; only tagged leaves may regenerate. First safe candidates are `my.fs/read`, `/glob`, `/stat`; never raw `my.shell/run`. Web needs specific capability evidence. | **Hybrid, ruled.** Receipt replay is a pure current database read. A tagged fresh read executes through the ordinary effect door and creates a new receipt; its render remains pure over that result. Untagged calls never re-execute. |

## Ranked delta list

1. **Spend since shown basis.** It immediately tells root what every episode
   cost, fits ruling 32 unusually well, and makes missing usage/pricing loud.
   Normalize usage and effective pricing inputs onto attempts, then declare the
   interval summary render.
2. **Agent notes.** Today knowledge outside code, todo, and paused-run notes has
   no fact home. Land the tiny option below, not a knowledge system.
3. **Make declared message forms executable.** Current generation can emit
   calls that do not exist; this invalidates “out of the box” more directly
   than any presentation gap.
4. **Finish ruling 40 with declared test/result rendering.** Failing tests are
   one of todo's three derived arms and green tests are rebirth's proof that a
   lesson is already known.
5. **Todo union plus authored open-item facts.** This implements ruling 49 and
   replaces, rather than revives, `my.plan`.
6. **The agent's defs face.** The facts already work; add the missing compact
   cognitive account of what was restored.
7. **Subagent relationship and current supervision face.** A single ref makes
   direct-child membership queryable and deletes source-parsing special forms.
8. **Pure-over-value repairs for message, run, namespace, and problems
   renders.** This enforces bounded turtles without duplicating facts.
9. **Schedule/config current forms.** Existing compact values need executable
   form projections.
10. **Branch reads and `my.branch`; package facts and faces.** Both are larger
    target waves with missing substrate, not quick render patches.

## Minimal memory and notes options

### 1. Recommended — tiny `my.note` current-fact home

Price: about three required attributes (`:my.note/id`, `/agent`, `/content`),
one optional `/about` ref only when a concrete subject exists, one entity and
one collection schema, three render functions, three verbs (`add!`, `forget!`,
`notes`), and one rebirth property/live proof. Updates replace content by
identity; forgetting retracts the entity; Datahike history preserves the old
fact. Transaction metadata supplies provenance. There are no categories,
confidence, embeddings, summaries, revisions, knowledge graph, or timestamps.

Guarantee: non-task knowledge has one honest durable home and renders as a
bounded current list. Trade-off: it supports remembering prose, not semantic
retrieval or inference.

### 2. Reuse self-addressed messages

Price: essentially zero code. Rejected except as a temporary manual practice:
a self-message is an unanswered trigger and wakes work
(`src/seon/cluster/work.clj:686-717`), conflates knowledge with an assignment,
and reconstructs “memory” from history instead of a compact current set.

### 3. Put notes on authored todo items

Price: one optional details member on the ruling-49 todo item. Acceptable only
for task annotations. General knowledge cannot use it honestly because
completed items deliberately vanish; completion and forgetting would become
the same operation.

## External-world options and owner ruling

The effect door's actual semantics constrain every option. `request*` requires
a current run-form context, resolves the declaring function's stored
`:seon.effect/capability`, validates and admits the request, commits the open
receipt, dispatches the handler on `:io`, then commits exactly one terminal
state (`src/seon/effect.clj:512-688`). A generated overview is therefore not a
“pure render that happens to read”: it is a real capability effect with a real
receipt. Current metadata has no read-only fact, and `:seon.workload :io`
classifies scheduling, not safety (`resources/seon/schemas/seon.fn.edn:35-44,65`).

### 1. Selected/recommended — receipt-old default plus declared fresh reads

Price: **medium**. First add a receipt-derived observation value/form that says
“old/not current” and offers explicit refresh. Then lift one read-only metadata
tag into a queryable function fact, exactly like workload/capability metadata,
and teach generation to re-execute only tagged calls. Filesystem `read`,
`glob`, and `stat` and selected web reads are candidates; raw `my.shell/run`
is not safely classifiable as a whole.

Guarantee: untagged effects never happen during derivation; tagged reads can
give honest fresh openings through the one door. Cost/risk: medium index,
schema, generator, render, and proof work; a wrong declaration can repeat a
costly read. The first declaration must land with the ruling-50 never-lie
falsifier: an untagged effect remains unexecuted and visibly old, a tagged read
creates one new receipt, and the displayed value corresponds to that receipt.

This is no longer merely a recommendation: ruling 50 selected it
(`docs/prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md:272-283`).

### 2. Execute every read-classified effect during generation

Price: **medium implementation, high operational risk**. It needs the same
missing classification fact, then generation runs all such effects through the
door. Guarantee: freshest available external observation. Trade-offs: rebirth
can block, spend money, hit rate limits, or vary without a database change;
classification mistakes become derivation-time mutations. It gives up the
strong default that recovery is a database read.

### 3. Last receipts only, refresh on demand

Price: **low to medium**. Add the old/staleness/currentness presentation and
refresh form, but no read-only metadata or automatic re-execution. Guarantee:
generation has no external effects and is deterministic over database facts.
Trade-off: openings may begin stale or have no observation at all; the agent
must spend a later form to refresh. This is the ruled shipping default, but not
the final capability once trustworthy read-only declarations exist.

## Defects and issue disposition

The compatibility matrix above is the durable filing for every discovered
delta. These dedicated root-cause notes are ready, but were not created
because their required index rows share an actively edited authority and its
gate is already red for foreign changes:

- `docs/seon/issues/declared-message-forms-name-missing-functions.md` — declared
  opening forms call nonexistent functions.
- `docs/seon/issues/provider-spend-has-no-queryable-current-facts-or-render.md`
  — usage is opaque and no spend-since-shown face exists.
- `docs/seon/issues/agent-knowledge-has-no-fact-home.md` — durable non-task
  notes cannot survive rebirth.
- `docs/seon/issues/subagent-membership-has-no-database-connection.md` — direct
  supervision membership is inferred by special code rather than a fact.
- `docs/seon/issues/todo-has-no-authored-item-facts-or-union-render.md` — ruling
  49's authored arm/current union is absent.

Existing issues that already own audited deltas:

- `docs/seon/issues/run-renderer-narrates-forms-and-receipts.md` owns the
  run-render impurity/presentation seam.
- `docs/seon/issues/seon-db-has-no-branch-or-commit-reads.md` blocks
  `my.branch`.
- `docs/seon/issues/cluster-config-and-bootstrap-plan-render-as-raw-maps.md`
  retains the config/cluster rendering history; current config AI/HTML has
  since improved, leaving the missing form as this sweep's narrower delta.
- `docs/seon/issues/agent-plan-has-no-declared-database-relationship.md` is
  superseded by ruling 49 and should be archived, not implemented.

## Verification boundary

This was a protected, research-only sweep. No source, schema, or test was
edited and no production gate was run. The evidence is file/line source
inspection plus exact absence searches. The ruling-40 lane's dirty schema and
runner were treated as in-flight evidence only.

The issue gate is the one incomplete boundary. `docs/seon/issues/index.md` and
five indexed notes were under another live lane's edits. At the checkpoint,
`bin/issues-index --check` also failed on two unrelated `severity: medium`
values (`operator-status-refuses-its-own-readiness-result.md` and
`opening-walkthrough-replicates-a-usage-test.md`) and the unrelated missing
row for `generated-turn-fork-omits-the-agent-scoped-environment.md`. The user
directed this sweep to stop rather than edit or resume another lane when such
a boundary blocks its gate. Consequently the dedicated notes above were not
created unindexed; they and their index rows must be filed together after the
shared authority is coherent.
