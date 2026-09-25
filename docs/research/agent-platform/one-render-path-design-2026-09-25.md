---
type: design
status: proposed
created: 2026-09-25
scope: One schema dispatch and one shape-aware, budget-owning floor printer
---
# One render path

Seon has declared schema pairs, a structural floor, and several competing selection and clipping paths. Compose the existing schema registry, guarded invocation and print nodes: **value + declared identity → schema renderer, else floor**.

## Authority and evidence

**The owner's correction supersedes the initial brief and current CLAUDE wording:**
“the floor printer was supposed to be smart and show snippets of each part of shapes so deeply nested large values still show part of the values while staying under the budget. This has a budget.”
There is **one presentation budget, inside the floor**. Render functions choose what to show; callers do not clip. Do not replace that budget with fixed depth/children/string limits. Those would preserve the wrong behavior.
The “Render ordinary values totally” and “Apply presentation limits once” laws remain, interpreted by this correction.
Only this document is authorized for editing; the orchestrator must reconcile the stale law wording during implementation.
Read end to end: CLAUDE.md and [clipping inventory](clipping-sites-2026-09-25.md) (`c6e75e57c`),
[prompt pipeline](agent-context-render-pipeline-2026-09-24.md), [context/query design](context-as-query-and-render-2026-09-23.md),
and [rendering census](one-happy-path/rendering-2026-09-24.md). Their proposals are evidence, not cumulative requirements.
Source anchors below are at `ddecb2ba4011bedb779cf9aa46efec48bb37d54f`; foreign edits were `src/seon/fn.clj` and the http-kit submodule.
The assignment's MCP-lane finding—declared renderers rarely apply, almost everything falls to `render-ai`—is reported evidence, not a measured frequency here.
**Live boundary:** MCP only, explicit root `/Users/sean/src/seon`, cluster `default`, JVM mode, `read_only=true`, 5,000-ms deadline.
`runtime_status` found PID 67823, start `2026-09-25T00:50:43Z`, alive but **faulted**, 15 open signatures, 365 failed tests and 18 unknown tests.
Its top-ten fault face is itself evidence of audit site 9; the counts exceed the displayed list. No JVM start, reload, adoption, test or provider call ran.
The first selection probe was refused because my request omitted required `:seon.db/db`; that is a malformed probe, not a product defect.
I then called the smaller installed selection owner with the database supplied:

```clojure
(let [start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      result (mapv (fn [value]
                     [value (#'seon.render/schema-producers
                             projection {:seon.db/db database} value :seon.render/ai)])
                   [{:example/x 1} {} {:seon.problems/failed-tests []}])]
  {:design/schema-producers result
   :design/declared-problems (get-in projection [:seon.schema.projection/forms :seon.problems/problems])
   :design/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Result: `[[#:example{:x 1} []] [{} []] [#:seon.problems{:failed-tests []} [seon.problems/ai-prose]]]`, **0.391666 ms**, prepl **2 ms**.
The returned installed problems declaration contains its AI/HTML pair; MCP retained the response artifact at digest
`b10a6f2b755655f366fa61b977c9220debd20b171dd2e4bf2afac9e794a2dd5a` (5,234 bytes).
This verifies that named-attribute matching works for that value today, but an empty all-optional return loses its renderer.

```clojure
(let [start (System/nanoTime)
      profile (assoc (seon.render/agent-render-profile seon.config/defaults)
                     :seon.render.profile/token-budget 128
                     :seon.render.profile/max-depth 8 :seon.render.profile/max-children 8)
      value {:sample/a {:sample/deep {:sample/text (apply str (repeat 500 "a"))}}
             :sample/b {:sample/deep {:sample/text (apply str (repeat 500 "b"))}}
             :sample/c {:sample/deep {:sample/text (apply str (repeat 500 "c"))}}}
      prepared (seon.render.value/prepare
                {:seon.render/value value :seon.render/profile profile
                 :seon.render.value/root [:design/probe 1]})]
  {:design/text (:seon.render.value/text prepared)
   :design/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Result: **2.136833 ms**, prepl **4 ms**. Text retained only `:sample/a`, replaced its subtree with an elision
(`omitted 1`, path `[:sample/a]`), then an elision for the other two children (`omitted 2`, path `[]`, next-offset 1).
Both named the token budget and “the value has no result handle”; **no a/b/c leaf snippet survived**.
This is current floor behavior, not a target proof. Memory/allocation and end-to-end rendering frequency were not measured.

## 1. One selection, based on declarations

Keep `seon.render/selection` as the sole decision and `invoke-selected` as its guarded SCI invocation owner. Remove independent decisions in `project-node*` and the explicit-value/request/namespace compatibility search.
Never interpret a map's `:seon.render/ai` member as executable dispatch instructions; plain maps remain data.
AI/HTML entry points select the target and serialize the answer; they do not select a second time.
Carry the **named schema reference**, not a structurally inferred type, beside the value in the existing render request:

1. The producing call supplies its declared return schema and actual arity/branch evidence. Reuse
   `schema/function-matching-outputs-in` (`schema.clj:3888`), preserving named references before dereferencing.
   Follow collection element/attribute declarations through Malli paths for children. `:any`, anonymous maps and
   ambiguous `:or` branches convey no unique identity; never scan the registry to invent one. A producer may supply
   its already-known named branch. Invalid or contradictory identity evidence yields a diagnostic, not a guess.
2. For pulled entities, resolve **declared attribute ownership** in the held projection: identity attribute → owning
   entity schema. Existing `:seon.program/row-schema` (`seon.fn.edn:209`, `schema.clj:3368`) is a verified seam for
   program rows, not proof that all entities have it. Otherwise use the unique entity declaration owning the identity
   attribute; conflicting owners require producer evidence. A partial pull keeps that provenance without claiming
   it is a complete validated entity. An arbitrary `:db/id` or similarly named keys do not establish ownership.
3. A value's own declared shape means an explicit named declaration supplied by its producer/type adapter, checked
   against the held registry; it is not a `:kind` stamp or a search for every open map the value might satisfy.
   Return-schema provenance can identify a report about an entity; it need not dispatch as that underlying entity.

Read the selected schema's existing `:seon.render/ai` or `:seon.render/html` property. Missing pair means floor. Known schema with no renderer does not authorize searching for a less specific schema. Conflicting declarations
name the candidates. Error schema composition explicitly combines its base and declared facets, without extra guessing.
Attribute-only requests preserve the attribute's declared value schema and never borrow the parent entity renderer.
Untyped Clojure data, including `{}`, `nil`, scalars and collections, reaches the floor without DB or SCI custody.
`14a9291ad` widened shape indexing to all-optional maps with render properties. Retain that fix for schema diagnostics;
`matching-shapes-in` (`schema.clj:4100`) validates structural candidates and is **not** the new identity authority.
Today's specificity ranking (`render.clj:359`) and two pulled/transacted matching passes disappear from render dispatch.
Wire provenance at actual producers: SCI `shown-result` (`sci/eval.clj:3181`), MCP projection (`cluster.clj:392`), and entity reads.
Do not force callers through `render-ai` without the declaration, then try to rediscover it inside the floor.

Dependency seam: Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, `core.cljc:2561,2592,2637`
(`schema`, `properties`, cached `validator`), `util.cljc:168,196` (`subschemas`, `in->paths`).
Compile declaration/path derivations once per changed schema/contract in the existing projection cache; lookup is O(1),
path propagation O(path length), entity ownership O(present attributes). No new registry or per-node registry validation sweep.

## 2. Render functions choose content

Contract: `(render-fn value context) → renderable node | text`, with the schema's actual value contract,
and a declared context carrying target **`:ai`/`:html`**, original caller path, held projection, retrieval provenance,
and explicit database/SCI inputs only where used. Reuse existing namespaced request members and node/Hiccup schemas;
the short target names here describe the existing `:seon.render/ai` and `:seon.render/html` targets.
Migrate current merged value/context arguments together with their callers and declarations; no permanent dual API.

A renderer selects counts, identities, relevant fields, descriptions and links. It receives no spend counter or size dial. It may call the same path for sub-values, extending the **original** value path and preserving their declared schemas.
Compose those child nodes before final floor preparation: nested calls neither serialize nor receive a fresh full budget.
Existing print nodes carry the composition; no second rendering language. Renderer-owned text/Hiccup is terminal and
is neither redispatched nor fitted. Raw embedded values remain floor inputs; already rendered text remains exact text.
An overlong authored renderer is repaired at that renderer, not trimmed by its consumer. Use value/path-aware recursion detection: recursive schemas may render distinct children; the same value/path cannot re-enter itself.
A throwing or invalid renderer contributes a named diagnostic, whose evidence goes through the floor with that producer disabled.

## 3. The floor owns one budget and distributes it across shape

**Keep what works.** `print/fit` (`print.cljc:1294`) shrinks breadth and strings together before depth;
its comments correctly reject spending everything on one dimension. `fit-text` retains prefixes, `fit-children` keeps
map keys whole, and elisions retain coordinates/requery. `ordered-head` (`value.clj:425`) avoids whole-map sorting.
These are useful mechanisms, not duplicates simply because they contain a bound. **Replace the waste.** `prepare` (`value.clj:608`) first spends a depth-first node counter named token-budget, then
calls `fit`, which repeatedly emits/estimates candidates and halves global limits. Already dropped branches cannot return.
`fit-node` (`print.cljc:1247`) and even `emit-node` (`:856`) sort again using emitted key text; remove both repeat sorts. `window` sorts by `pr-str`.
The Throwable floor (`value.clj:37–131`) has separate fixed caps and an 8,192-character final `subs`.
Use the existing floor in `seon.render.value`, retaining `:seon.render.profile/token-budget` **only as its input**.
`request-profile` acquires that policy once; render functions/callers do not inspect it. Remove the token-named node
counter, global halving loop, secondary ordering, and independently tunable presentation depth/width/string ceilings.
Query-work, admission/storage and execution bounds remain distinct and report their own failures.
**One allocation traversal, one emission:**

1. Convert B estimated tokens to a character allowance once through `seon.ai.tokens/estimate-chars`, with the supplied
   calibration. Charge actual escaped text, delimiters, labels and compact elision syntax; estimate final output once
   for observation, never to drive another search. This guarantees the estimator's bound, not a model-tokenizer bound.
2. At each collection, establish sibling identities/counts and reserve container syntax, whole keys, and retrieval
   evidence **before descending**. Give every affordable sibling a minimum snippet share; split the remainder fairly
   using shallow demand (scalar encoded length, cheap counted size; unknown demand gets the same initial share).
   Shrink demands proportionally, redistributing surplus from small scalars among siblings before expansion.
   Carry those shares as local floor arguments, not new independent budgets or mutable caller policy.
3. Expand the reserved frontier across siblings before deepening any one branch. A long unary chain spends its share
   on a compact path plus a leaf snippet rather than a fixed-depth wall. Retain original full paths in node data.
   Each inspected value builds one node; already built nodes are never printed to choose another fit.
4. Preserve vector/list order; for entity maps put declared identities first, then canonical attribute order.
   Order unordered maps/sets once using the existing comparator/heap for simple finite keys; reuse that order for
   rendering and offset retrieval. Do not compare arbitrary objects by calling their printer or `toString`.
   Complex keys are indivisible; an unaffordable key causes a whole-entry elision, never a changed key.
5. If all sibling identities cannot fit, show affordable representatives and one explicit group elision with count
   and path; never claim that arbitrarily many branches can all be visible in finite B. Reserve this evidence first.
   Every represented branch receives a snippet before surplus deep detail. No hard depth/child/string replacement.
   If mandatory root/retrieval evidence alone exceeds B, return a named insufficient-floor-budget diagnostic;
   do not silently exceed B or loop. Small values remain complete.

Reuse existing elision nodes: bound, omitted count/unit, original path, total when known, next-offset and digest/requery.
Unknown counts are explicitly unknown, not a fabricated 1. Factor repeated retrieval roots into one visible legend;
each compact elision names its path and legend reference. AI receives **text**; HTML receives **Hiccup data**, built
from the same floor decision. MCP agent-facing content receives that text, with digest/cursor metadata alongside it;
do not turn the rendered node back into a map and print it again. HTML serialization never clips the resulting HTML.
Saved shown text is terminal in both targets, never floor input a second time.
Totality includes Throwables **inside** this floor: class, message, ex-data, causes, suppressed exceptions and frames
share the same allocation. Use safe core values/identity faces; catch failing accessors locally and name the failure.
Never dereference an atom or invoke arbitrary print methods. Untrusted lazy sequences/host iterators may execute or
block even on their first element: show an opaque identity plus explicit inspection/requery, not a false termination
promise. Known safe sequences can expose a bounded frontier; cycles retain an identity reference. Missing retrieval
custody produces an explicit unavailable reason, not a missing-root error that prevents plain values from rendering.
`floor`, `bounded-text`, `render-ai` become thin entries to this same projection-free core; no emergency second clipper.
Cost: O(visited nodes + emitted characters), plus one O(n log k) selection for each inspected unordered collection
(O(n log n) if all n are shown); retained working space O(frontier + output). Ordering arbitrary complex keys needs
honest work accounting, not a blanket O(B) claim. Recompute on changed value/declaration/renderer/B/calibration/path,
using existing content-valid caches. No full hidden-subtree scan merely to apportion display space.
The [Clojure printing guide](https://clojure.org/guides/repl/data_visualization_at_the_repl) supplies length/depth controls,
not shape-wide budget allocation or retrieval-bearing elisions; keep Seon's existing node seam rather than replacing it with those controls.

## 4. Re-adjudication of all 21 audit sites

Numbers/anchors refer to the linked inventory; **D** marks one of its original nine DELETE sites. Proposed renderer names below live in the existing namespace and get the existing owning response/error schema pair.

| # | Revised verdict | Reason and replacement |
|---|---|---|
| 1 | KEEP / simplify | `value-node*`/`prepare` is the floor; replace depth-first spending with §3 allocation, not another clipper. |
| 2 | CONVERT inside floor | Retain one B; replace `print/fit` search and second traversal with §3. Delete fit-only helpers after moving whole-key/elision semantics. |
| 3 D | KEEP carrier | `agent-render-profile` supplies the floor's B. Delete reads/spending elsewhere, not the key itself. |
| 4 | CONVERT inside floor | `bounded-text`/Throwable `floor` become wrappers over §3; remove caller-limit arity and independent final `subs`. Drop duplicate raw panic message printing. |
| 5 D | DELETE presentation min | Walk `min width query-width` is a second presentation cut. Keep declared pull/query-width acquisition, with incomplete-query evidence. |
| 6 | KEEP selection | Whole prompt units, not portions of values; see §5. |
| 7 D | DELETE comparison | Assertion `message-text` always delegates to the one path; keep complete stored failure message. |
| 8 | KEEP delegation | `report-value` routes to the one path; remove the caller's Throwable special branch. |
| 9 D | CONVERT | Delete `bounded-face`, `bounded-summary`, top-10. `seon.cluster/render-runtime-observation` shows health/counts and fault identity/operation/class/last-at; preserves complete observation as input. |
| 10 | CONVERT | `seon.cluster/render-runtime-observation` also supplies terminal status; operator emits its text. Counts and fault identities replace message bodies; no status-top/short-message. |
| 11 | KEEP | `problems/ai-prose` is a legitimate declared renderer; structured family sub-values use the common floor. |
| 12 | CONVERT | `seon.render.transcript/render-history` shows an explicitly queried recent turn page, total and older-page link; reuse saved evaluation units. Delete max-children/max-nodes as presentation inputs; keep honest query bounds. |
| 13 D | KEEP retrieval | `mcp-get-value`/`window` pages by requested path/offset. Keep steps, total, next offset, beyond-end/missing-artifact evidence. Separate retrieval page size from print width; reuse §3 ordering and render the requested page once. |
| 14 D | CONVERT | `seon.render.web/render-connections` shows count and the requested page with existing pager. Delete post-query `take width`; explicit page acquisition remains, never HTML substring clipping. |
| 15 D | DELETE dead code | Namespace omission/included-count always receives the full count; remove dead parameter and elision. |
| 16 | CONVERT | `seon.render.ns/render-referenced-schemas` shows complete referenced schema names/count and definition queries, not first 40 full definitions. Names go through the one path. |
| 17 | CONVERT | `seon.bootstrap/render-opening` renders selected subjects as identities and small read forms; no spent-token candidate loop. Subject membership belongs to its query. |
| 18 | CONVERT | `seon.test/render-world-refusal` shows operation/expected/path plus the native offending value via one path. Store no 400-character replacement in ex-data. |
| 19 | CONVERT | `seon.test.runner/render-drift` shows drift/reach counts and whole native name sets via one path; delete take-10 and “first 10” message assembly. |
| 20 D | CONVERT / DELETE | `seon.cluster/render-branch-errors` shows counts and error identities plus branch/basis; bridge emits it. Keep an explicit since/cursor query window, remove hidden max-20 display loss; delete form-preview echo, caller already has the form. |
| 21 D | DELETE local clipping | Docstring/markdown/changed-test/citations script report truncations: print complete reports or send report data to the same floor. An explicitly requested ranked query is still lawful. |

The audit's six domain conversions are 10,12,16,17,18,19, each named above; 9 and 10 deliberately share one renderer.
Admission byte limits, blob thresholds, agent-requested file/search windows, deadlines and legitimate ranking queries
stay. They must expose missing/partial acquisition and cannot be used as covert display limits. This design does not
delete admission or transcript subsystems wholesale; the census's larger deletion estimates require separate caller proof.

## 5. Prompt units

**Recommend keeping whole-unit selection**, not replacing it with automatic destructive compaction in this cut.
`:seon.config.ai/prompt-token-budget` belongs to provider-context admission, outside the value render system; it may
select whole historical evaluations, never fit their text. “Apply presentation limits once” explicitly permits
“select whole prompt units”; “Recover facts, not execution” preserves exact shown bytes. Record which units were omitted.
Count the complete prompt (frame, separators, retrieval notice and required opening included) against model capacity.
A newest unit that cannot fit must produce an explicit capacity refusal or request the existing compaction operation,
not silently overrun capacity as `retained-count` currently allows (`prompt.clj:232`). Compaction clears evaluations
and regenerates opening under “Derive wakes and completion”; it is a distinct state transition, not a printing fallback.

## 6. Ordered implementation slices and proof

Root obtains independent design review before production edits; this bounded design lane launches no agents. Owners below are responsibilities, not permission to touch currently held files. Root checks its ledger before assignment.
Each row is a loadable cut with **≤100 added physical lines including contracts/tests**; estimates include deletions.
If the actual producer-family migration cannot fit, split by named family before writing, never keep a second dispatcher.

| Slice / owner | Change and required installed probe | Est. + / − |
|---|---|---|
| 1 schema/render | Named-identity selection; remove namespace/specificity stages. Probe `{}` with/without declared problems return, conflicting identities, scalar and attribute-only values. | 60 / 180 |
| 2 SCI/MCP | Carry actual return/element provenance through shown-result and mcp-project. Probe declared vector children and raw `{}` through both real entrances. | 70 / 60 |
| 3 floor/print | One fair allocator in value owner, retire fit loop/node counter. Repeat three-branch probe, huge first vs tiny siblings, deep chains, escaped strings, tiny B. | 90 / 180 |
| 4 floor/error | Fold Throwable/bounded-text core into allocator; plain values need no DOM identity. Probe nested ex-data, accessor failure, opaque lazy/object and cycles. | 70 / 100 |
| 5 render/domain | Convert existing pair arguments and nested calls by family; remove project-node redispatch. Probe no fresh B on children, one invocation, failed producer diagnostic. | 80 / 160 |
| 6 cluster/operator | Shared runtime and branch-error renderers (9,10,20). Probe >20 faults: counts, identities and retrieval agree; terminal/MCP use same face. | 60 / 75 |
| 7 floor/MCP/web | Keep offset retrieval, reuse ordering; connections pager (13,14). Probe all pages reconstruct original including long strings, complex keys and end offsets. | 65 / 85 |
| 8 transcript/walk | Render queried history units; remove walk presentation min (5,12). Probe requested page membership and unchanged stored shown bytes in AI/HTML. | 50 / 75 |
| 9 namespace/bootstrap | Referenced-name and opening renderers; dead omission removal (15–17). Probe >40 names, small opening forms, no token spend outside floor. | 50 / 90 |
| 10 test-report | Native refusal/drift data and one message path (7,8,18,19). Probe large offender/whole drift set remains retrievable; no caller Throwable branch. | 35 / 65 |
| 11 scripts/config | Remove remaining dev truncations and presentation dials; preserve floor B, query/storage bounds (3,21). Search every original site and consumer. | 40 / 80 |

Planning estimate: **670 added, 1,150 deleted, net ~480 lines deleted**, including contract/regression edits; not a measured diff. Slice 5 is least certain: inventory actual pair callers before assigning its first family; revise the estimate rather than
hide adapters or count unrelated census retirements. Existing emitters, elision nodes, projection cache and SCI guard are retained.
After each authorized implementation cut, root proves rows → load → arm → adoption, then runs focused real-SCI probes. At completion root owns affected/platform and bulk proof once, plus browser paint, exact saved-text preservation and page reconstruction.
Measure elapsed/allocation, node visits, ordering passes, emit count and floor elisions on parent/change; target ordinary
operations <1 s, zero repeated fitting emissions, no >20% or 50-ms regression. Large-map scans must state proportional cost.
This landing changes **zero source/test lines**. Only read-only MCP observations above are live evidence; implementation, arming, browser, performance improvement and memory proof remain outstanding. The unrelated faulted system was not reset.
