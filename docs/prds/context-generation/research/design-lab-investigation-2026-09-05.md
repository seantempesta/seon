---
type: research
status: active
tags: [research, agent, context, render, data-model]
---

# Design lab investigation — 2026-09-05

This is dated evidence, not another implementation schedule. The proposed
sequence lives in the [design-lab PRD](../plan/design-lab-prd-2026-09-05.md).
The owner explicitly reopened the design: defaults bootstrap every namespace's
agent, each agent may query any connected data and define its own render
functions, and the current UI is not the desired design.

## What was inspected

Read the design-lab PRD end to end on this turn. Earlier orientation read the
agent-centric design, program overview, working edge and orchestrator manual
end to end. Two bounded research agents inspected renderer selection and
Datahike observation respectively; neither changed code or clusters.
Primary investigation inspected the actual browser pages, current web owner,
CSS, prompt owner, dependency delivery code, and made read-only JVM MCP probes.
No implementation, reset, model call, or agent message was performed.

Research checkout observed at `c2cc606d0a5c520d98e037401e937c57083ddcbb`;
the shared tree contains other work. Source evidence below describes inspected
bytes, not proof that every loaded Var matches HEAD.

## Live evidence

`bin/seon status`: default, pid 77269, prepl 54993,
[local UI](http://127.0.0.1:7994), process start 2026-09-05T18:31:34Z.
MCP status answered with two agents and four errored receipts; arithmetic
evaluation returned 3. No full correctness gate was run in this planning task.

The [committed probe](scripts/design-lab-orientation-2026-09-05.clj) ran through
`mcp__seon__eval_clj`, JVM mode, cluster default, 15-second request bound.
Final run: 4 ms, no exception, uncapped result, database basis 536871002,
commit `6a9c61d9-6aa3-5c87-ac0f-b2d2e5eb2b23`.

- Agent/namespace pairs: root → my.agents.root; my.note → my.note.
- Root has current id, namespace, and cluster attributes. Its other information
  must be found through refs/queries; absence from the direct attributes does
  not mean absence from the database.
- A bounded query for `:my.plan.item/id` returned no rows. This cluster cannot
  yet demonstrate rendering of a non-empty stored plan.
- Reading source-commit from that instance's top level did not find it.
  Database commit and published program commit must not be conflated; the
  lab needs to get source identity from the actual acquisition/registry owner.
- Immutable trial contract:
  `[:function [:=> [:cat :int] :int] [:=> [:cat :string :string] :string]]`.
  On arguments `[7]`, `function-accepts-in?` returned true and
  `function-returns-in?` for `:string` returned true. Checking both conditions
  on each SAME Malli arity returned false. No function/contract was installed;
  the trial was an immutable assoc of the projection.

## Browser observations

Root page: fleet summary occupies the broad primary area; maintenance records
fill a narrow right column with separate scroll boxes and long wrapped names.
There is substantial empty space and no direct way to inspect selection.
Debug: prospective text fills one long preformatted pane; HTML arrives through
the existing feed in the other pane, with nested vertical/horizontal scrolling.
The HTML did arrive; an initial loading message was not a persistent failure.

The prospective prompt contains large namespace/schema listings, generated
supervision forms using `db/q` and `run/complete`, and recorded unresolved-symbol
errors for those forms. It also renders maintenance functions with stale-Var
diagnostics. These are observed output, not proof of their current root cause.
The base-system agent owns its repairs. Existing relevant issues:
[generated forms](../../../seon/issues/render-history-serializes-unexecuted-form-projections.md),
[teaching contracts](../../../seon/issues/doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives.md),
[run rendering](../../../seon/issues/run-renderer-narrates-forms-and-receipts.md).

Concrete layout cause: `web/page-result` reverses the flat unit list for rank
(`src/seon/render/web.clj:395-403`); `rank-class` assigns one primary unit
(`:244-249`); CSS puts all other units in column 2 with 10rem scroll boxes
(`resources/public/css/input.css:1115-1148`). Improving this requires a useful
layout over observations, not preserving that rank arrangement as a design law.

## Linked atlas

Read the live
[Seon Context Atlas](https://claude.ai/code/artifact/08eb53eb-5bc7-41a7-8e4d-2f2d9e924fc6)
through the browser, particularly Data model and Three projections. Firecrawl
was unauthenticated; web open failed; browser access succeeded.

Useful interaction: selecting a datum relates it to its storage, query, render
function and destination. Keep this. The page identifies a hand-authored MODEL
object as its source. Its diagram still includes inbox/components and
many-agents-per-namespace claims that differ from the latest discussion and
current schema. Do not copy those facts into the lab. Neither its counts nor its
displayed forms are live proof. Its tabs include more material than inspected
here; this report does not claim an exhaustive validation of the atlas.

## Current renderer selection

Source: `src/seon/render.clj:178-336,437-498,622-724`;
`src/seon/render/walk.clj:477-491,560-574`.

1. Explicit renderer on the value, then explicit renderer on the request.
2. Matching public functions in the namespace supplied by the caller.
3. Validated schema-declared renderer.
4. Structural floor.

Multiple namespace candidates refuse; there is no distant-namespace or recency
ranking in this owner. The walk supplies the rendered member's namespace,
not consistently the viewing agent's. Nested values check explicit/schema
renderers but skip namespace candidates. Nested failures can retain the original
print node while top-level failures surface as errors.

Reuse `call-static-evidence` and retained calls: they already expose selected
function, argument, declaration, basis and read evidence. Expose a contracted
explanation from the same selection decision consumed by rendering, not a
separately reimplemented ranking in the UI.

Malli input refs can narrow candidates, but cannot establish positional arity,
optional input, predicates, or full output compatibility. Validate the actual
arguments and requested output on one arity. The public contract functions at
`src/seon/schema.clj:3080-3131` currently separate those questions.

## Observation and schema findings

Datahike EAVT finds outgoing assertions; AVET [attribute target] finds incoming
refs (`reference-code/datahike/src/datahike/pull_api.cljc:368-391`).
No incoming-ref mirror is needed.

`seon.db/datoms` eagerly realizes the complete matching cursor
(`src/seon/db.clj:1268-1281`). Taking a page afterward is not bounded
acquisition. Consume a bounded cursor inside that database owner, or prove an
existing resource-limited query path; keep decoding in `datom->data`
(`:1254-1266`). Show decoded values as decoded when storage uses EDN strings.

`render.walk/root-selector` already derives installed refs/identities
(`src/seon/render/walk.clj:59-153`), but it builds a recursive pull, has
message/run-specific acquisition, and does not preserve every assertion's tx.
Width+1 proves more data exists; it does not prove the exact omitted count.

Database-side aggregates may materialize/group the relation
(`reference-code/datahike/src/datahike/query.cljc:2357-2381`).
Optimized secondary-index aggregates are conditional
(`:3672-3725,4541-4575`). Measure exact counts separately from pages.
Seek is inclusive: continuation must be exclusive and stop at the selected
index prefix. Pair it with the immutable database identity and temporal view.

`schema/matching-shapes-in` reuses the projection's attribute index and
validators (`src/seon/schema.clj:3313-3334`). `candidate-shapes-in` is a
capped diagnostic window, not the full population (`:3287-3311`).
A partial entity page cannot establish that the full entity fails a schema.
Show unvalidated status when complete required inputs were not acquired.

Agent namespace is already a unique Datahike ref
(`resources/seon/schemas/seon.cluster.agent.edn:71-75`);
`cluster.agent/assigned-id` queries its reverse (`src/seon/cluster/agent.clj:181-192`).
The observation subject and viewing agent must be distinct inputs. Inspecting a
cluster entity does not imply that there is one global SCI context.

## Execution, prompt, and delivery boundaries

`external-sink` and `projection-boundary` classify output crossings, not
purity (`resources/seon/schemas/seon.fn.edn:10-27`). Render functions themselves
carry these tags. Their absence cannot prove no external effects. SCI fork
isolates Var changes, not file writes or transactions. Before an arbitrary
candidate can be labelled read-only, enforce the actual invocation boundary and
test effects, not just datom-count equality. Candidate listing is useful before
arbitrary diagnostic execution is ready.

Render invokes the existing SCI kernel and print fit
(`src/seon/render.clj:369-435,517-573`). Its cost path requires retained inputs,
held run and connection (`:700-724`); omit effect custody in diagnostics where
compatible, but do not equate that omission with a complete purity guarantee.

`web/debug-prompt` prefers an old captured prompt whenever one exists
(`src/seon/render/web.clj:590-596`). The prospective branch constructs walk
history; it does not call the full provider prompt owner. Provider assembly has
additional custody, budget, calibration and state behavior
(`src/seon/cluster/prompt.clj:182-274`). Show current prospective output and
historical captures separately; do not claim equality without testing it.

Namespace debug GET currently ensures an absent owner
(`src/seon/render/web.clj:1748-1765,1856-1869`). This conflicts with read-only
inspection. Existing agents can be observed now; an unassigned namespace should
be inspectable without booting an agent.

Delivery already has revisioned packages, contiguous deltas and gap keyframes
(`src/seon/render/web.clj:680-759,1431-1575`). The Datastar skill and its design
reference still call these TARGET and describe per-tab diffing. That claim is
stale; do not rebuild delivery.

Datastar supports cleanup callbacks in attribute plugins
(`reference-code/datastar/library/src/engine/engine.ts:105-155,302-334`).
Its init attribute ignores the expression's return
(`library/src/plugins/attributes/init.ts:18-35`), so returning a graph destroy
callback from data-init is not sufficient. Establish graph lifecycle through
the actual supported mechanism. Preserve selection/pan/zoom across updates;
ten updates must leave one graph instance, not ten lost layouts.

## Dependency ledger

| Dependency / checkout | Mechanism to reuse | First-party seam |
|---|---|---|
| Datahike cdcb5792db8bd599487f099437265d18a31164a5 | indexes, seek, resource-limited reads, pull evidence, immutable identity | seon.db, seon.render.walk |
| Malli 3517a3cd9271b2083780ac7be1725493905bca2e | function arities, validator reuse, explainers (`core.cljc:2627`) | seon.schema, seon.render |
| SCI fcbd8862800e638dc0f8f5521111f999279cbcd2 | fork, call preparation, interrupt (`core.cljc:298,345`) | seon.sci.kernel, seon.sci.eval |
| Datastar bb9ed6fbe78cf5690f5ad23a5faf86407a44982f | morphing, stable DOM ids, attribute cleanup | public JS and seon.render.web |
| Datastar Clojure 1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2 | SSE event serialization | seon.render.web |
| http-kit 238a85cc555a38892f2f9a7583c9cf5cec0fb201 | drain-or-close completion (`server.clj:321`) | seon.render.web/write-package! |
| core.async dc35f3e0d7bc2eef502e77982f48641f025c8051 | existing render proc, mult, sliding buffer | seon.render.web/feed |

Cytoscape is proposed by the PRD/atlas; no vendored asset was found by filename
search. Select and pin the asset and inspect its update/destroy API before its
implementation. No browser graph library is required for the first datom and
renderer table.

No latency targets beyond the recorded MCP probe were proved on this turn.
Use returned work/weight, subject degree, snapshot, cold/warm query and render
timings, and browser delivery measurements in implementation acceptance.

## Graph composition pitch — latest owner steering

The owner explicitly rejects assuming the present walk or discovery is correct.
The start may be ANY entity, with viewer and requested output supplied separately.
Compare these patterns before committing to the traversal shape:

- [Ring middleware](https://github.com/ring-clojure/ring/blob/master/SPEC.md):
  a function wraps a handler, modifies incoming request data and transforms its
  response. Simple local composition; it does not decide graph expansion.
- [Pedestal interceptors](https://pedestal.io/pedestal/0.8/reference/interceptors.html):
  enter/leave/error functions over a context map, with queue and reverse leave
  stack. Execution can be inspected and extended as data; branching graph
  acquisition still needs its own semantics. Reitit's vendored
  doc/http/interceptors.md and modules/reitit-core/src/reitit/interceptor.cljc
  expose chain compilation and the executor protocol; an executor is not built
  into Reitit itself.
- [Pathom resolvers](https://pathom3.wsscode.com/docs/resolvers/) and
  [planner](https://pathom3.wsscode.com/docs/planner/): attribute input/output
  dependencies support demand-driven acquisition. The computation plan is a
  DAG, distinct from Datahike's entity/ref graph. Useful if derived data needs
  automatic composition; not a substitute for choosing a useful presentation.
- Ordinary recursive function composition: the parent passes arguments to
  child render calls and combines returned values. No object hierarchy is
  required. A graph needs cycle/path semantics and bounded acquisition in
  addition to this familiar tree-shaped composition.
- [Clojure datafy/nav](https://clojure.github.io/clojure/clojure.datafy-api.html):
  separate describing a value from navigating a selected part. The checked-out
  clojure/src/clj/clojure/datafy.clj:17-39 demonstrates the narrow APIs. This is
  a useful generic inspection model, not a requirement to wrap Datahike in
  another object representation.

Proposed experiment, not settled architecture: inspect enough local entity data
to discover candidates, then let the chosen contracted function ask for the
additional data/child renderings it needs. A generic default shows attributes
and bounded navigable refs for unknown subjects. A specialized function can
request a summary without expanding complete child contexts. Viewer, snapshot,
output and local detail/budget arguments flow down as immutable values; output,
read evidence, errors and cost return upward as ordinary data. Wrap calls for
shared invocation concerns only if ordinary function composition is insufficient.

The performance hypothesis is that selecting a summary before deep expansion
avoids work that a walk-everything-first pipeline later discards. It is not yet
measured. A depth bound remains a backstop, not the source of semantic relevance.
Malli input/output contracts alone cannot infer which joins or summaries matter;
queries and small functions must state that behavior. Avoid both a new stored
renderer registry and a generic resolver engine unless the experiment shows
the existing facts/functions cannot express the needed composition simply.

The inspector should show BOTH actual entity/ref navigation and the actual
function calls with inputs/outputs. The same entity rendered for two viewers
is two different calls. Cycles yield a bounded reference; a globally visited
entity set must not suppress a legitimate different view. Cache identity must
include the view/function/inputs and program identity as well as database basis.

Documentation verification: path-scoped git diff --check passed. The issue-index
checker still reports six missing schedule rows and two scheduled non-open
notes outside this research slice; none names the new findings. No broad issue
triage or correctness-suite run was attempted during the design discussion.

## Fast experiments and dependency ordering — followup, 2026-09-05

MCP runtime_status and eval_clj answered after the shared process changed to
pid 94171, start 19:02:38Z. Health was observed with four errored receipts; this
is connectivity evidence, not proof those errors are fixed. A pure JVM form
confirmed ordinary let binding/evaluation and that quoting a form returns its
symbols as data without invoking the named function. No cluster facts or shared
SCI definitions were changed. No performance comparison was attempted.

Existing fast-loop seams, verified by the render-selection research agent:

- src/seon/schema.clj:2491 projection-with-schema and :2631
  projection-with-function-contract derive experimental projections.
- src/seon/sci/eval.clj:2235 fork-candidate-ctx uses fork-for-turn; :2246
  evaluate-candidate evaluates function candidates with contracts/tests without
  promoting them. Accrete render comparison here; do not introduce a second
  candidate installer. Keep its ctx and projection together.
- src/seon/sci/eval.clj:716 install-row! consumes committed declaration facts;
  src/seon/cluster/loop.clj:1683 installs settled declarations into the base and
  current turn contexts. This adoption path does not require process restart.
- Host Var reload does not establish that a SCI Var, acquired function snapshot
  and schema projection changed together. Source publication/refork remains a
  distinct experiment from an ephemeral candidate or settled agent definition.
- reference-code/sci/src/sci/core.cljc:345 documents fork behavior; external
  effects are not rolled back. reference-code/malli/src/malli/core.cljc:2627
  supports reusing compiled validators.

src/seon/render/walk.clj:642 form-symbols currently tree-walks symbols and uses
namespace/dotted spelling; :656 ordered-episode treats explained symbols and
introduced subjects as readiness. This is an existing baseline to compare, not
a compiler dependency analysis. Quoted symbols, lexical bindings, aliases and
macro expansion make structural occurrence different from executable dependency.
Teaching a quoted function name may still be desirable: record that decision
separately rather than treating it as unresolved execution.

Primary language grounding: [Clojure evaluation](https://clojure.org/reference/evaluation),
[special forms](https://clojure.org/reference/special_forms), and local
reference-code/clojure/src/clj/clojure/core.clj:2790 declare, :6705 letfn.
Proposed ordering: ordinary generated forms with prerequisite references, then
an ordered vector consistent with those references; parent-authored order for
independent siblings. Discovery/render recursion can return prerequisites along
with output. Report blocked dependencies/cycles explicitly. No general scheduler
or new expression language is implied.

Visualization grounding: [Cytoscape API](https://js.cytoscape.org/) supports
attributed graphs, compound nodes, batch updates and layout extensions;
[its layout guidance](https://blog.js.cytoscape.org/2020/05/11/layouts/) separates
node positioning from graph selection and warns about large-graph visual noise.
The local atlas loads Cytoscape 3.30.4 at line 3 and constructs elements from
MODEL at :548; :558 creates the instance. Thus the library fits the intended
interaction, but neither live data integration nor scale is proven. Keep
backend ordering independent of visual coordinates, and measure browser layout
separately. The upgraded debug view should show an ordered transcript and cost
table beside selectable entity/ref and generated-form dependency graphs.
