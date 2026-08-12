---
type: research
status: complete
tags: [prd, render, context, sci]
---

# Render simplification audit

## Verdict

Converge the whole render system on one operation: the distance-bounded database
walk discovers a value, queries the program graph for the unique contract-fitting
renderer in that value's owning namespace, and invokes the renderer through the
guarded shared SCI context. The function returns either the AI string or HTML
Hiccup. If no function qualifies, the value reaches the one value printer; HTML
is only that printer's HTML decoration. This is the model ruled in #50 and its
amendments (`plan/README.md:1818-1899`).

This deletes the Hiccup-with-holes composition system, the generic open-kind
router, the compiled-Var path, three nested result envelopes, the transcript's
walk bypass, and the second cache proposal. It keeps the distance walk, the two
outputs, stable render-call identity, guarded errors-as-values, and the render
proc's latest package plus equality state.

The one unresolved semantic fact is not renderer selection but **how an arbitrary
walked value names its owning namespace**. Functions already carry an explicit
`:seon.fn/ns` ref (`resources/seon/schema.edn:1964-1987`); namespaces have a
unique assigned agent through `:seon.cluster.agent/namespace`
(`docs/seon/architecture/context.md:251-258`). The current tree has no universal
value/entity-to-namespace relation. Inferring it from an attribute or schema
keyword's text would repeat the name-inference defect rejected by ruling #47
(`plan/README.md:2132-2151`). Implementation must use an existing real ownership
ref where the domain has one, preserve the governing namespace while the walk
crosses a namespace edge, or stop for the owner to choose the missing fact; it
must not guess from names.

## Reading and evidence boundary

I read every requested document **whole, not with grep** before reaching the
verdict: `research/render-model-2026-08-02.md` (687 lines),
`research/render-pipeline-design-2026-07-29.md` (904 lines),
`docs/seon/architecture/ui.md` (550 lines),
`research/context-walk-synthesis-2026-07-31.md` (551 lines),
`docs/seon/architecture/context.md` (468 lines), and rulings #46-#50 in
`plan/README.md`. I then read the third amendment verbatim from commit
`6ebdcf45e` before finishing this report (`plan/README.md:1872-1899`). Searches
were used only after those whole reads to inventory current call sites and find
the older distance specification.

The first-party source basis at the final audit was
`0cc33e9dd6b7607fce08cfb2dadc6b481840f2d6`; other lanes had uncommitted work in
protected production/schema/test paths, so citations below describe the current
read-only tree and no claim is made that those unrelated edits belong to this
lane. Absence claims such as “no production caller” were verified by an `rg`
inventory over all current `src/**/*.clj` and `src/**/*.cljc`; an absent call
site has no file line to cite. Dependency pins were Datahike
`0e8601d7f2f6`, SCI
`72150fd44c81`, and core.async
`dc35f3e0d7bc`. The relevant dependency seams are
Datahike's write-schema value validation
(`reference-code/datahike/src/datahike/db/transaction.cljc:32-51`), SCI context
creation/evaluation (`reference-code/sci/src/sci/core.cljc:317-343`), SCI Var
interning and resolution (`reference-code/sci/src/sci/core.cljc:259-270,823-824`),
and the render package's measured `mult` behavior
(`research/render-pipeline-design-2026-07-29.md:137-159`).

## The change-map correction

`render-model-2026-08-02.md` said selection should never be inferred. That
recommendation is partially superseded. Selection **is computed** from namespace
ownership plus contract fit: function rows already record their namespace and
arity input/output schema refs (`resources/seon/schema.edn:1964-1987`), and the
indexer derives those refs from the declared Malli contract
(`src/seon/program.cljc:254-276`). What remains forbidden is inference from a
function name, namespace prefix, or other textual convention; the current
`render-<kind>` construction is exactly that forbidden path
(`src/seon/render.clj:282-292`; `plan/README.md:2132-2147`).

The earlier four-rung architecture also gave the viewing agent's namespace first
choice (`docs/seon/architecture/ui.md:86-99,142-152`). The #50 amendment replaces
that with ownership: namespace A does not override namespace B's widget renderer
merely because A is viewing it (`plan/README.md:1850-1869`). Thus renderer
**selection** has no cross-namespace ranking problem. The simple chain is:

1. an explicit AI/HTML producer on the value, when present;
2. the unique contract-fitting function in the value's owning namespace;
3. the matched schema's explicit AI/HTML property; and
4. the structural value-printer floor.

Absence at every authored rung is ordinary and reaches rung 4. It is never a
“missing declaration” error (`plan/README.md:1826-1832`). A selected function
that cannot resolve, exceeds its limit, throws, or returns the wrong output is a
real renderer failure and remains a flat error value; SCI evaluation already
defines this non-throwing boundary (`src/seon/sci/eval.clj:1560-1580`).

## Keep / kill / absorb

“Absorb” means preserve the behavior in the named existing mechanism while
deleting the separate public concept and schema.

| Mechanism | Verdict | What it does and who calls it now | Existing well-understood owner | What is genuinely lost |
|---|---|---|---|---|
| Distance walk | **KEEP — load-bearing** | `neighborhood` pulls an entity, renders it, derives forward/reverse ref connections, and follows them within a hop/node/depth budget (`src/seon/render/walk.clj:462-483,583-680`). Page assembly calls it at `src/seon/render/web.clj:329-344`; prompt and agent renderers call it at `src/seon/render.clj:199-221` and `src/seon/render/agent.clj:279-318`. | It **is** the single context-pull, membership, delegation, and namespace-crossing mechanism ruled by #50 (`plan/README.md:1850-1869`). | Killing it loses related context, namespace delegation, bounded graph traversal, and the central owner goal. It is not a candidate for deletion. |
| `block/slot` | **KILL** | Emits a `data-slot` Hiccup hole with a stable surface id (`src/seon/render/block.clj:109-123`). Its only production caller is the root fleet special case (`src/seon/render/web.clj:346-363`). | Emit the fleet result as an ordinary walked render unit/fragment. Stable identity remains the renderer call's stable fragment id, which the page already derives for every walk unit (`src/seon/render/web.clj:306-344`). | Renderer-authored placeholder-first layouts. The target instead serves a complete cached keyframe, so an empty hole is not needed for initial paint (`docs/seon/architecture/ui.md:417-427`). |
| `block/expand` | **KILL; absorb its bounds into the walk** | Recursively walks arbitrary Hiccup, fills named slots, parses entity-ref holes, reroutes each ref, and independently enforces cycles, hop distance, node caps, and depth caps (`src/seon/render/block.clj:326-550`). Its only production caller expands the one fleet slot (`src/seon/render/web.clj:346-363`). | The distance walk already follows database refs, keeps a visited/rendered set, spends one hop, and enforces the same absolute caps (`src/seon/render/walk.clj:472-483,583-680`). Layout is ordinary Hiccup over the already resolved flat units, not another graph traversal. | Arbitrary Hiccup returned by a renderer can no longer smuggle a second ref graph or named child-block graph into page assembly. That is the duplicate system being removed, not a target capability. |
| `block/entity-slot` | **KILL** | Encodes a database lookup as printable `data-ref` in Hiccup for `expand` to parse and follow (`src/seon/render/block.clj:125-152,441-515`). It has no production caller; current calls are tests only. | Real database refs discovered by `walk/refs`, then delegated to the reached value's owning namespace renderer (`src/seon/render/walk.clj:583-680`). | A renderer can no longer invent graph membership by embedding an opaque ref marker in presentation. A relation that should affect context must be a queryable ref reached by the walk. |
| `block/data-panel` | **ABSORB, then delete wrapper** | Normalizes a unit with `floor-unit` and calls `value/render-html` (`src/seon/render/block.clj:605-656`). It is selected as the router floor, passed as the walk floor, and called directly by `/data` (`src/seon/render.clj:307-311`; `src/seon/render/web.clj:1026-1041,1188-1213`). | `seon.render.value/prepare` already admits once and tees one print tree to text and Hiccup (`src/seon/render/value.clj:171-203`). HTML is its HTML decoration through `render-html-data` (`src/seon/render/value.clj:213-218`). | Only the `data-panel` name and its separate unit-cleanup wrapper. Bounded `/data` navigation and HTML output remain in the one printer. |
| `block/data-prose` | **ABSORB, then delete wrapper** | Normalizes a unit with the same `floor-unit` and calls `value/render-ai` (`src/seon/render/block.clj:658-679`). It is the AI router and walk floor (`src/seon/render.clj:199-211,307-311`; `src/seon/render/web.clj:1026-1041`). | The same `value/prepare` projection, decorated as text by `render-ai-data` (`src/seon/render/value.clj:171-211`). | Only the second floor name. Reader-valid bounded value text remains. |
| `block/select` ordered predicate specialists | **KILL** | Late-resolves an ordered hand-authored list of predicate/producer symbols and takes the first match (`src/seon/render/block.clj:556-603`). No production caller exists in the current tree. | Program-graph query over function namespace plus declared input/output refs (`resources/seon/schema.edn:1964-1987`; `src/seon/program.cljc:254-276`). | Producer-controlled first-match ordering and predicates that silently fail closed. The replacement is queryable, automatic, and loud on ambiguity. |
| Transcript special-case branch in the walk | **KILL; absorb transcript projection into the ordinary owning-namespace renderer** | `transcript-node` fabricates a non-entity lookup, hard-codes the AI/HTML renderer choice, calls it directly, adds synthetic member nodes, and `units` gives it special tail ordering and suppresses the member entities (`src/seon/render/walk.clj:523-570,681-690,704-799`). | The agent value is discovered normally; its owning namespace's unique contract-fitting renderer may use the existing transcript projection functions (`src/seon/render/transcript.clj:571-648`). Related facts remain walk refs, and membership/order stay in the one walk. | The transcript gets no walk-only synthetic identity, forced tail position, or special member suppression. If independent transcript patching remains measured as valuable, it must be an ordinary stable renderer call, not a branch in traversal. |
| `:seon.render.walk/request` | **KEEP, simplify** | Carries database value, lookup, kind, floor, viewer namespace, caps, distance, and overrides (`resources/seon/schema.edn:2901-2917`). `neighborhood` is the public consumer (`src/seon/render/walk.clj:462-483`). | Keep one normal map argument for the walker: database value, root lookup, caps, distance, calling agent, and any explicit branch. Two typed entry points or an output projection parameter replace generic kind/floor/override routing. | Removing the map entirely would lose a clear, schema'd, immutable call boundary and make database basis/caps ambient. Those are load-bearing. |
| `:seon.render.walk/node` | **ABSORB into the walk's emitted unit** | Recursive nodes carry lookup, distance, changed-at, selected projection/output/error, and children (`resources/seon/schema.edn:2919-2944`). Only `units` and `prose` consume the tree before page/prompt assembly (`src/seon/render/walk.clj:704-887`). | Emit ordered units during the one bounded traversal while the traversal stack already knows path, branch, distance, and cycle state. | A separately reusable materialized recursive tree. Current consumers immediately flatten it, and the flattened unit retains the inspection path. |
| `:seon.render.walk/unit` flattened envelope | **KEEP, flatten one level** | Wraps the full node and repeats path, depth, branch, changed-at, and sometimes output (`resources/seon/schema.edn:2946-2959`; built at `src/seon/render/walk.clj:717-799`). Web uses it for stable id/rank/HTML and AI uses it for labeled prose (`src/seon/render/web.clj:243-271,329-344`; `src/seon/render/walk.clj:801-887`). | This is the surviving **render call result**: renderer identity + explicit args/address + path/distance/change basis + direct output or error. It becomes the block/pipeline fragment input without a nested `/node`. | Nothing required. Debug code changes from `[:unit :node ...]` to direct keys. |
| `:seon.render/request` | **KILL** | Generic `{unit, kind}` input to `resolve-unit` and `render` (`resources/seon/schema.edn:2353-2359`; `src/seon/render.clj:313-350`). | The walk already knows whether it is deriving AI or HTML. Call the selected qualified symbol through the guarded SCI renderer kernel with the ordinary value/context arguments. | Arbitrary runtime-selected kinds through one generic API. The target has exactly two outputs. |
| `:seon.render/rendered` | **KILL** | Wraps `{kind, output}` solely so a map output is distinguishable from a flat error (`resources/seon/schema.edn:2360-2364`; `src/seon/render.clj:346-381`). | The two typed renderer boundaries return `string-or-error` and `hiccup-or-error`; error presence remains the discriminator used everywhere else. | A generic kind echo. No consumer needs it once the output boundary is typed. |
| `:seon.render/surface` and `/surfaces` | **ABSORB into the pipeline fragment entry; delete schemas** | Adds block name, derived surface id, kind, and either output or error (`resources/seon/schema.edn:392-415`; `src/seon/render/block.clj:200-274`). `expand` indexes these wrappers; the only production creation is the fleet special case (`src/seon/render/web.clj:346-363`). | The surviving flattened walk unit supplies call identity/error/output; the render proc's ordered `{stable-id -> serialized fragment bytes}` state is already the equality/package owner (`research/render-pipeline-design-2026-07-29.md:334-366`). | A pre-serialization wrapper and generic kind field. Stable DOM identity, per-unit isolation, and bytes remain. |
| `:seon.render.block/block` schema | **KILL** | Declares a named map with optional AI/HTML declarations (`resources/seon/schema.edn:340-358`). `block/surface` accepts it, and the fleet oversight value is its only production path into the old surface/slot composition (`src/seon/render/web.clj:346-363`). | A block is derived from an ordinary renderer function call and stable explicit arguments, as the architecture already says (`docs/seon/architecture/ui.md:101-127`). Function contracts and the pipeline fragment entry are the declarations. | Manually named, preassembled blocks. Function-call identity replaces them without losing morph targets. |
| Open render `kind` set, including `:seon.render/log` | **KILL generic kinds; keep only AI and HTML outputs** | `kinds` discovers any `seon.render` key with a symbol/string/vector value (`src/seon/render.clj:232-269`); schemas explicitly make kind open and advertise future log/SMS/metrics (`resources/seon/schema.edn:2289-2304`). Errors and problems currently route log strings through it (`src/seon/error.clj:361-395,452-457`; `src/seon/problems.clj:264-301,448-469`). | Logs are ordinary sink-specific functions (`error/log-line`, `problems/log-report`), not a third agent/user projection. AI and HTML are the only render outputs ruled for the same block (`docs/seon/architecture/ui.md:101-118`). | Third-party consumers cannot accrete a new “kind” merely by adding a namespaced key. They add an ordinary function/consumer with its own contract, avoiding a polymorphic router. |
| Namespace `render-<kind>` string-building | **KILL (already ruled #47)** | Constructs a candidate symbol from the viewer namespace and asks `requiring-resolve` whether it exists (`src/seon/render.clj:282-292`). | Query `:seon.fn/ns`, `:seon.fn.arity/input-refs`, and `/output-refs` for contract fit; then run the qualified symbol from the returned program row through SCI (`resources/seon/schema.edn:1964-1987`). | Convenient magic names. No declared capability is lost. |
| Router's current four resolution paths | **REPLACE with the ruled ownership chain** | Current `resolve-unit` tries explicit value, name-built viewer namespace, validating schema rows, then a kind floor (`src/seon/render.clj:271-329`). The walk partly duplicates it with request overrides and explicit floor substitution (`src/seon/render/walk.clj:619-666`). | One query at the walk: explicit value producer -> unique contract fit in the data's owning namespace -> schema property -> value-printer floor. The amendment supersedes both viewer-first override and cross-namespace candidate distance (`plan/README.md:1850-1869`). | Viewer namespaces no longer redefine foreign data presentation, and no silent alternate renderer is tried after a selected renderer fails. Both are intended ownership guarantees. |
| Compiled `requiring-resolve` and JVM-side invocation | **KILL** | `render/render`, `block/select`, transcript helpers, and the public walk resolve compiled Vars with `requiring-resolve` (`src/seon/render.clj:331-381`; `src/seon/render/block.clj:592-603`; `src/seon/render/transcript.clj:356-372`; `src/seon/render.clj:199-218`). | Database program row -> qualified symbol -> live SCI Var in the cluster's shared context -> ruling #46 guarded kernel. Cold boot already interprets agent-authored source rows into one context, while its explicit compiled-first-party branch is the exception this cut removes (`src/seon/sci/eval.clj:1137-1235,1369-1394`); evaluation uses the supplied live context under its one interrupt/time-limit boundary (`src/seon/sci/eval.clj:1560-1610`). | Compiled execution speed. The owner accepted the measured guarded SCI costs and explicitly rejected this bypass (`plan/README.md:1872-1889`). |
| Separate per-call render cache proposal | **DO NOT BUILD; absorb into the render proc's existing retained fragment/package state** | The target prose describes dependency-aware per-call entries (`docs/seon/architecture/context.md:40-47`), but the approved pipeline already retains serialized fragment bytes, suppresses byte equality, builds keyframes from those retained bytes without rerendering unchanged units, and owns one latest package (`research/render-pipeline-design-2026-07-29.md:334-366,401-418`). | One single-writer render-proc state: stable call identity -> last bytes/dependency evidence, plus latest immutable package. Initial load and late joins reuse its cached keyframe (`research/render-pipeline-design-2026-07-29.md:620-649`). This is one cache owner, not a memoizer beside a package cache. | No required behavior. A cache hit before function execution is retained only if it is implemented as the same fragment entry's dependency check; a second general function cache is deleted. |
| Internal error card | **KILL; absorb failure into stakeholder messages plus loading/unavailable page state** | Both `block/error-card` and `web/surface-html` expose the internal error message directly in HTML (`src/seon/render/block.clj:309-324`; `src/seon/render/web.clj:243-271`). | Commit the flat failure and use the existing durable message wake (`src/seon/error.clj:704-707`). Notify the namespace owner always; root only at ruled volume or failed pings; agentless namespaces notify their explicit stakeholders. HTML says **loading** only while a repair owner is responsive and working, then **unavailable**; it never exposes internals. | On-page stack/projection detail. The owner and stakeholders receive the actionable explanation through messages instead. The current stakeholder relation is not yet a universal queryable fact and must be settled before this part lands. |

## Ruled producer representation, with probes

The third #50 amendment decides this question: every renderer is source in a
database program row, identified by its qualified symbol, resolved to the live
SCI Var in the cluster's one shared context, and invoked through the guarded SCI
kernel. There is no compiled-Var lane and no function-object declaration arm
(`plan/README.md:1872-1889`). This is also the simplest model that survives the
crash law. Current `acquire!` already queries and interprets source-bearing
agent-authored `:seon.fn` rows, but explicitly binds loaded first-party
namespaces as compiled JVM Vars; the target removes that renderer exception
(`src/seon/sci/eval.clj:1137-1188`). A live cluster already builds and retains
one context (`src/seon/sci/eval.clj:1369-1394`). The accepted cost is measured,
not assumed: 10.250 microseconds p50 for a trivial guarded render and 2.448
milliseconds p50 for a guarded 250-event Hiccup render
(`plan/README.md:2137-2151`).

The requested Datahike probe confirms that “store an in-process function” is not
a hidden simpler durable representation. In an in-memory database with
`:schema-flexibility :write`, `:db.type/any` is not a valid Datahike value type;
using its broad `:db.type/value` still refused `(fn [x] [:ok x])` as a bad entity
value. That refusal is the dependency's schema validation path
(`reference-code/datahike/src/datahike/db/transaction.cljc:32-51`). The decisive
result was:

```clojure
{:error :transact/schema,
 :attribute :probe/fn,
 :value #object[user$...],
 :schema #:db{:ident :probe/fn,
              :valueType :db.type/value,
              :cardinality :db.cardinality/one}}
```

An SCI probe then defined `widgets.core/render-widget`, retained both its SCI Var
and its dereferenced function value, redefined the function, and resolved again:

```clojure
{:same-var? true,
 :same-fn? false,
 :captured-old [:v1 7],
 :var-current [:v2 7],
 :resolved-current [:v2 7]}
```

That is why the process-local cache may retain the **SCI Var** associated with a
durable symbol, but must not retain its dereferenced function value: the former
tracks ordinary redefinition, the latter freezes old code. SCI documents
`intern` as finding/creating and root-binding an SCI Var
(`reference-code/sci/src/sci/core.cljc:259-270`) and exposes context resolution
at `reference-code/sci/src/sci/core.cljc:823-824`.

The four consequences are direct:

- **Restart/recovery:** source and contracts survive as database facts; the
  process-local SCI Var is re-derived. The current cold acquisition proves that
  path for agent-authored rows; first-party renderers must join it instead of
  taking the compiled branch (`src/seon/sci/eval.clj:1137-1235,1369-1394`).
- **Caching identity:** use the durable qualified symbol plus explicit arguments
  and cluster code revision as call identity; retain the SCI Var as an execution
  handle, never a captured function body. Serialized equality remains actual
  bytes (`research/render-pipeline-design-2026-07-29.md:334-366`).
- **Cross-agent callability:** the cluster handle carries one SCI context into
  its runtime (`src/seon/cluster.clj:1060-1088`), and evaluation uses the supplied
  context rather than rebuilding/forking it (`src/seon/sci/eval.clj:1581-1606`).
  Thus every agent resolves the same live renderer without copying a function.
- **Queryability:** the database row retains symbol, namespace, source, arities,
  and input/output refs (`resources/seon/schema.edn:1961-1987`). A bare function
  object provides none of those query joins and is rejected by Datahike anyway.

What the walk needs at render time is therefore small: the candidate query's
qualified symbol, the cluster SCI context, the discovered value plus explicit
render arguments, and the guarded invocation settings. It does not need the
compiled namespace loaded, `requiring-resolve`, a parallel JVM Var registry, or
a persisted function object.

## Two functions for one shape in one namespace

**Proposal: loud refusal, not “most-specific input wins.”** Automatic wiring
returns a renderer only when exactly one function in the owning namespace both
accepts the discovered shape and declares the requested AI or HTML output. Zero
matches reaches the next rung/floor. More than one match returns one flat
ambiguity error naming the namespace, shape(s), projection, and every candidate
symbol; it never chooses lexically, by definition order, or by required-key
count.

The evidence against specificity scoring is current behavior. `matching-shapes-in`
can return every validating open map schema and sorts them by **count of required
attributes**, then schema-key text (`src/seon/schema.clj:2329-2335,2397-2417`).
That is deterministic, but it is not semantic subtyping: two open contracts may
overlap, a predicate/union need not have a comparable required-key count, and
the house rule explicitly says unused extra keys are accepted. A “most specific”
score would therefore add rules while silently selecting a function the agent
did not identify as preferred. Loud ambiguity has one rule—unique or refuse—and
makes the missing distinction the owning agent's problem, which is consistent
with failure-as-data and stakeholder repair.

For the best agent experience, an exact duplicate can be reported immediately
when a durable `defn` enters the program graph because its namespace and
input/output refs are already facts (`src/seon/program.cljc:254-276`). General
overlap must still be checked at render resolution because Malli contracts can
overlap without identical refs. The `defn` itself need not be rolled back; the
ambiguity is a renderer-selection failure, and the agent can narrow or remove
one contract in its next edit.

## Namespace distance

No specification found defines a second namespace-to-namespace renderer-candidate
distance, and the amended ownership rule makes one unnecessary. The closest old
proposal said code-distance and data-distance would be different edge selections
over one walker (`research/renderable-corpus-plan-2026-07-28.md:457-531`), while
the namespace quarry described each real require edge spending a hop to render
the reached namespace through its own lens
(`research/namespace-renderer-quarry-2026-07-29.md:15-21`). Neither defines a
metric for ranking renderer candidates in foreign namespaces.

The implemented measure is exactly the one the owner identified:

- request schema: optional nonnegative `:seon.render/distance`, caps always the
  absolute bound (`resources/seon/schema.edn:417-474`);
- one default site: `block/distance` defaults absence to 1
  (`src/seon/render/block.clj:154-164`);
- one hop spent for each followed walk connection
  (`src/seon/render/walk.clj:668-670`), matching the older `expand` accounting
  (`src/seon/render/block.clj:452-515`);
- namespace render distance is already root-relative—own namespace 1, every
  other namespace 2 (`src/seon/render/walk.clj:448-452`); and
- namespace nodes expose only actual `:seon.ns/requires` connections
  (`src/seon/render/walk.clj:454-460`).

Therefore use `:seon.render/distance` only for **what related values the walk
reaches and how much detail their renderer receives**. Once a value is reached,
namespace ownership selects the candidate set; distance never ranks functions.
This deletes the overnight change map's proposed “another namespace's renderer
ordered by distance” question (`plan/overnight-2026-08-03.md:340-356`).

## Exact landing order

The order below cuts full obsolete mechanisms before polishing seams, while each
commit leaves one coherent rollback point.

1. **Seal fail-first contract tests and query falsifiers.** Prove a zero-match
   value reaches the printer; two same-namespace matches refuse loudly; a B-owned
   value reached from A selects only B; renderer calls execute under SCI and a
   redefinition changes the next call; no compiled `requiring-resolve` runs.
2. **Land the program-graph candidate query and guarded SCI invocation.** Query
   namespace + input refs + one of the two output refs, require uniqueness, and
   invoke the resolved live SCI Var. Do not touch walk topology yet.
3. **Collapse the resolution chains.** Make the distance walk call that one
   selector; remove viewer overrides, namespace name construction, duplicated
   walk resolution, generic `kind`, and compiled invocation. Absence goes to the
   floor; selected failures do not silently try another rung.
4. **Collapse outputs and floors.** Replace request/rendered/surface/block
   envelopes with the direct flattened render-call unit; make AI and HTML the two
   typed outputs; route both floors through one prepared value projection.
5. **Remove walk special cases.** Move transcript behavior to an ordinary
   owning-namespace renderer, emit flattened units directly, then delete the
   recursive node envelope and transcript-specific ordering/suppression.
6. **Delete `slot` / `entity-slot` / `expand` / `select`.** Move the root fleet
   output onto the ordinary walk first, then delete the now-zero-caller Hiccup
   marker walker and its schemas/tests.
7. **Land stakeholder-shaped failure handling.** Replace internal HTML error
   cards with loading/unavailable state and durable explanation messages. Stop
   here if the value-owner/stakeholder facts are still not queryable; do not
   substitute a list.
8. **Move equality and initial paint to the one render-proc package owner.** Keep
   retained fragment bytes/dependency evidence and the latest package in the
   same state, build keyframes from retained bytes, and make page/feed joins reuse
   them. Delete per-tab page maps and initial per-connection rendering as the
   pipeline design requires (`research/render-pipeline-design-2026-07-29.md:620-649`).
9. **Delete schema debris and dead callers in the same final wave.** Remove the
   open kind, render request/rendered/surface/block/slot-expansion shapes and all
   tests that pin those deleted APIs. Update architecture only after the owner
   accepts this audit, because this lane is research-only.

## Fail-first falsifiers

These are behavioral gates, not exact-prose tests:

1. **Floor totality:** render an undeclared scalar, collection, and open map in
   both projections. AI returns bounded reader-legible text; HTML returns the
   same admitted print tree's Hiccup decoration; no missing-declaration error is
   present (`src/seon/render/value.clj:171-218,259-277`).
2. **Ownership across a walk:** A requires/reaches B, and B's data matches both
   an A renderer and a B renderer. Only B's function executes; removing B's
   function reaches schema property/floor, never A's function
   (`plan/README.md:1850-1869`).
3. **Ambiguity:** two B functions accept one value and return AI. Resolution is
   one flat error naming both symbols; candidate insertion order and lexical
   order do not change the result.
4. **SCI-only execution:** instrument a first-party and agent-authored renderer,
   prove both enter the guarded SCI kernel, then redefine each in the shared
   context and observe the next render change. A trap around
   `requiring-resolve` must remain untouched (`plan/README.md:1872-1889`).
5. **Restart:** define and commit a renderer, kill the process, reopen the
   cluster, and render without replaying the defining turn. The same program row
   resolves in the rebuilt context (`src/seon/sci/eval.clj:1137-1235,1369-1394`).
6. **Distance:** distance 0 follows no ref; 1 follows one; each namespace node
   exposes only require edges; lowering node/depth caps always wins over distance
   (`resources/seon/schema.edn:417-474`; `src/seon/render/walk.clj:448-460,668-678`).
7. **No second walker:** a renderer returns Hiccup containing the old `data-slot`
   and `data-ref` attributes; they remain ordinary inert attributes or are
   refused by the output grammar, never cause traversal. `rg` finds no production
   calls to `slot`, `entity-slot`, `expand`, or `select`.
8. **Package reuse:** open two tabs after a settled package and prove neither
   calls a renderer or serializes Hiccup; both receive the same cached keyframe
   bytes. Change one unit and prove unaffected equal units are neither serialized
   nor sent
   (`research/render-pipeline-design-2026-07-29.md:334-366,620-649`).
9. **Audience-correct failure:** break B's renderer. The browser shows no
   exception/symbol/internal message; B's owner always receives one clear durable
   explanation; root receives one only at the ruled volume/unresponsive-ping
   condition; each explicit stakeholder of an agentless B can follow up or drop
   it (`plan/README.md:1839-1845`).

## Minimal-rules render model — one page

1. **Membership is the distance walk.** Start from the namespace owner agent's
   entity at one immutable database value. Follow real refs; namespace nodes
   follow only `:seon.ns/requires`. Distance is an optional request argument,
   defaults once to 1, spends once per followed ref, and never exceeds the shared
   admission caps (`resources/seon/schema.edn:417-474`;
   `src/seon/render/walk.clj:448-460,668-670`).
2. **Ownership decides who renders.** Wherever B's value is reached, B renders
   it. A never needs B's widget rules. Do not rank foreign renderers by namespace
   distance and do not give the viewer namespace an override
   (`plan/README.md:1850-1869`).
3. **An ordinary contracted `defn` wires itself.** A public function in B whose
   input accepts the discovered shape and whose output ref is AI string or HTML
   Hiccup is a candidate. This is a database query over existing program facts,
   never registration and never a name convention
   (`resources/seon/schema.edn:1964-1987`; `src/seon/program.cljc:254-276`).
4. **Unique or loud.** Exactly one fit wins. No fit continues to schema property
   then floor. More than one fit is a flat ambiguity error naming all candidates;
   there is no specificity calculus.
5. **Exactly two outputs.** AI returns text; HTML returns Hiccup. Log/reporting is
   an ordinary consumer function, not a render kind. Direct results or flat
   errors need no request/rendered/surface envelope.
6. **One printer is total.** Unauthored values are normal. One admitted print
   tree feeds text and HTML decorations (`src/seon/render/value.clj:171-218`).
   The HTML floor may remain hidden by default while still being available via
   “show everything” (`plan/README.md:1396-1405`).
7. **Every renderer is durable code run by SCI.** The database stores source,
   symbol, namespace, and contract facts. The shared cluster context holds the
   live SCI Var. Every call crosses the one guarded SCI kernel; there is no JVM
   compiled bypass, `requiring-resolve`, or function-object declaration
   (`plan/README.md:1872-1889`).
8. **A block is a call, not a data type.** Stable identity derives from qualified
   renderer symbol plus explicit arguments/address. The walk emits one flat unit
   carrying that identity, path, changed basis, and output/error. HTML derives
   its DOM id from the same identity.
9. **One cache owner.** The render proc retains each call's dependency evidence
   and serialized bytes, suppresses equality, builds one revisioned delta plus
   keyframe package, and retains the latest package for joins. Tabs hold only a
   delivered revision and never rerender (`research/render-pipeline-design-2026-07-29.md:334-366,401-418,620-649`).
10. **Failures go to people who can act.** The owning agent always gets a clear
    durable message. Root is escalated only for error volume or unresponsive
    pings. Agentless namespaces notify explicit stakeholders. Humans see loading
    only while repair is active, then unavailable—not internals
    (`plan/README.md:1839-1845`).

### Delete from the current tree

- `src/seon/render/block.clj`: `slot`, `entity-slot`, `expand`, `select`,
  `surface`, `error-card`, `data-panel`, `data-prose`, and their now-dead helper
  branches; retain/move only stable-id derivation and any value-normalization
  proven still needed (`src/seon/render/block.clj:72-679`).
- `src/seon/render.clj`: `kinds`, namespace name-building, generic kind routing,
  compiled `requiring-resolve`, and the request/rendered wrappers
  (`src/seon/render.clj:232-381`).
- `src/seon/render/walk.clj`: viewer overrides, duplicate resolution, transcript
  special node, recursive node materialization, and nested-node flattened-unit
  shape; retain the bounded ref walk and emit flat units
  (`src/seon/render/walk.clj:523-570,619-680,704-799`).
- `src/seon/render/web.clj`: fleet slot/expand special case, internal error-card
  detail, per-connection initial `page-of`, and per-tab full HTML maps
  (`src/seon/render/web.clj:243-271,346-363,754-842,1051-1081`).
- `src/seon/render/transcript.clj`, `src/seon/error.clj`, and
  `src/seon/problems.clj`: compiled router calls and generic log-kind routing;
  keep their ordinary domain projections/functions
  (`src/seon/render/transcript.clj:330-375`; `src/seon/error.clj:361-395,452-457`;
  `src/seon/problems.clj:264-301,448-469`).
- `resources/seon/schema.edn`: block, slot-expansion, surface(s), open kind,
  generic render request/rendered, recursive walk node, overrides, and generic
  floor declarations after all callers are gone
  (`resources/seon/schema.edn:340-474,2289-2372,2878-2959`).
- Delete tests that pin those removed APIs in the same commits; replace them with
  the nine behavioral falsifiers above. Production/schema/test edits are outside
  this research lane.

### Open points only the owner can rule

1. **What exact fact determines an arbitrary walked value's owning namespace?**
   The ruling requires ownership, but the current universal data model does not
   expose one relation. Recommended constraint: preserve a governing namespace
   from an explicit ref already on the data or traversal edge; when none exists,
   use the schema property/floor until a real ownership fact is declared. Never
   infer ownership from keyword text.
2. **What facts enumerate stakeholders for an agentless namespace?** Current
   source can query the one assigned namespace agent and detect unowned
   namespaces (`src/seon/problems.clj:245-256`), but no general stakeholder
   relation was found. That missing fact blocks honest fan-out; it does not block
   owner-agent notification or the rest of the render simplification.
3. **When exactly does loading become unavailable?** “Owner agent is responsive
   and has accepted repair work” is the recommended event-derived boundary.
   Volume and ping responsiveness already have runtime observations, but no
   settled repair-acceptance fact was found. Do not use elapsed time as a proxy.
