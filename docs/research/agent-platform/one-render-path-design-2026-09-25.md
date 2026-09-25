---
type: design
status: "owner go 2026-09-24 (all slices); implementation in progress"
created: 2026-09-25
scope: One schema dispatch and one shape-aware, budget-owning floor printer
---
# One render path

Changes after [Opus review 8a9e946d7](one-render-path-review-2026-09-25.md), all findings accepted by root:
Declared identity ownership and producer error/return alternatives replace structural discovery without losing pairs.
The floor uses one character allowance, cheapest-first equal shares and emitter-owned costs; renderers keep merged units.
Problems returns data; prepare owns offset retrieval; overlong renderer text yields a named diagnostic.
The revised slices land replacements before dispatch removal, convert explicit owners atomically, and price prompt admission.

Seon has declared schema pairs, a structural floor, and several competing selection and clipping paths. Compose the existing schema registry, guarded invocation and print nodes: **value + declared identity → schema renderer, else floor**.

## Authority and evidence

**The owner's correction and current CLAUDE presentation law govern this revision:**
“the floor printer was supposed to be smart and show snippets of each part of shapes so deeply nested large values still show part of the values while staying under the budget. This has a budget.”
There is **one presentation budget, inside the floor**. Render functions choose what to show; callers do not clip. Do not replace that budget with fixed depth/children/string limits. Those would preserve the wrong behavior.
The “Render ordinary values totally” and “Apply presentation limits once” laws remain, interpreted by this correction. Only this document is authorized for editing. The review was read end to end; accepted findings are integrated below.
Read end to end: CLAUDE.md and [clipping inventory](clipping-sites-2026-09-25.md) (`c6e75e57c`), [prompt pipeline](agent-context-render-pipeline-2026-09-24.md), [context/query design](context-as-query-and-render-2026-09-23.md), and [rendering census](one-happy-path/rendering-2026-09-24.md). Their proposals are evidence, not cumulative requirements.
Original source/probe anchors are at `ddecb2ba4011bedb779cf9aa46efec48bb37d54f`; review anchors are at `4f509ca3b`.
The assignment's MCP-lane finding—declared renderers rarely apply, almost everything falls to `render-ai`—is reported evidence, not a measured frequency here.
**Historical live evidence from the initial design (not rerun for this revision):** MCP only, explicit root `/Users/sean/src/seon`, cluster `default`, JVM mode, `read_only=true`, 5,000-ms deadline.
`runtime_status` found PID 67823, start `2026-09-25T00:50:43Z`, alive but **faulted**, 15 open signatures, 365 failed tests and 18 unknown tests.
Its top-ten fault face is itself evidence of audit site 9; the counts exceed the displayed list. No JVM start, reload, adoption, test or provider call ran.
The first selection probe was refused because my request omitted required `:seon.db/db`; that is a malformed probe, not a product defect.
I then called the smaller installed selection owner with the database supplied:

```clojure
(let [start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      result (mapv (fn [value] [value (#'seon.render/schema-producers projection
                                       {:seon.db/db database} value :seon.render/ai)])
                   [{:example/x 1} {} {:seon.problems/failed-tests []}])]
  {:design/schema-producers result
   :design/declared-problems (get-in projection [:seon.schema.projection/forms :seon.problems/problems])
   :design/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Result: `[[#:example{:x 1} []] [{} []] [#:seon.problems{:failed-tests []} [seon.problems/ai-prose]]]`, **0.391666 ms**, prepl **2 ms**.
The returned installed problems declaration contains its AI/HTML pair; MCP retained the response artifact at digest `b10a6f2b755655f366fa61b977c9220debd20b171dd2e4bf2afac9e794a2dd5a` (5,234 bytes).
At that observation, named-attribute matching worked; an empty all-optional return lost its renderer.

```clojure
(let [start (System/nanoTime)
      profile (assoc (seon.render/agent-render-profile seon.config/defaults) :seon.render.profile/token-budget 128
                     :seon.render.profile/max-depth 8 :seon.render.profile/max-children 8)
      value {:sample/a {:sample/deep {:sample/text (apply str (repeat 500 "a"))}}
             :sample/b {:sample/deep {:sample/text (apply str (repeat 500 "b"))}}
             :sample/c {:sample/deep {:sample/text (apply str (repeat 500 "c"))}}}
      prepared (seon.render.value/prepare {:seon.render/value value :seon.render/profile profile
                                          :seon.render.value/root [:design/probe 1]})]
  {:design/text (:seon.render.value/text prepared)
   :design/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Result: **2.136833 ms**, prepl **4 ms**. Text retained only `:sample/a`, replaced its subtree with an elision (`omitted 1`, path `[:sample/a]`), then an elision for the other two children (`omitted 2`, path `[]`, next-offset 1).
Both named the token budget and “the value has no result handle”; **no a/b/c leaf snippet survived**.
This was observed floor behavior, not a target proof. Memory/allocation and end-to-end rendering frequency were not measured.

## 1. One selection, based on declarations

Keep `seon.render/selection` and guarded SCI `invoke-selected`. Replace structural matching only when these lookups and their caller conversions land together. No new registry, ambient last-return state or `vary-meta` provenance stamp.
The review's live census found 326 paired schemas: 28 identity-bearing maps, 165 identity-less maps and 133 `:and` forms; **298 error/report pairs need producer alternatives**. Of 55 identity attributes, 29 occur in multiple maps.
These are review measurements (2.7-ms census, prepl 6 ms; ownership prepl 7 ms), not fresh measurements here.

1. **Errors at any depth:** resolve the value's own `:seon.error/operation` to that function's declared error output
   alternatives in the held projection's `:seon.schema.projection/function-contracts`. Validate against those only,
   using cached `malli.core/validator`: exactly one satisfied alternative supplies the schema; multiple matches name
   an ambiguity diagnostic. Compose its declared renderer with the error base. An uncontracted operation uses the
   base renderer; a contracted operation with no satisfied error alternative yields a named contract diagnostic.
   This applies inside faults/test results too; no top-level return provenance is needed for nested errors.
2. **Direct contracted calls/reports:** for a top-level `(f args…)`, resolve its head to the actual contracted Var
   under the evaluation's held namespace/program. Reuse `schema/function-matching-outputs-in` (`schema.clj:3888`)
   when evaluated arity/arguments are available; otherwise inspect that function's declared output alternatives.
   Validate the result against those alternatives only, preserving named schema references: one match selects,
   multiple matches diagnose, no match to a contracted return diagnoses. Do not re-evaluate argument forms.
   `let`, threading, `map`/`for`, literals and interop supply no inferred final-producer identity and go to the floor,
   where nested errors and entity maps still receive rules 1 and 3. No tracing of whichever function returned last.
3. **Entity maps:** declare the owner once on each identity attribute's schema using existing
   `:seon.program/row-schema` (`program.cljc:170–205`). Every identity attribute present must name the same schema.
   Select it only if **every present map key belongs to that schema, plus `:db/id`**. Missing/conflicting ownership
   or foreign envelope keys means floor, not agent/entity rendering. Partial pulls need not contain every required
   entity field; this is a closed-key ownership check, not a claim of complete validation. `:db/id` alone names no owner.
   Never search all schemas containing the attribute. Adding this property does not turn runtime rows into program rows.

Order: the error's own operation, then a known direct-call return, then eligible entity ownership, else floor. A declared report result may legitimately contain an entity identity; rule 3 must not capture that report envelope.
Existing explicitly supplied declared shapes remain valid producer evidence, not arbitrary renderer symbols in data.
For attribute/child identity, look up the registered attribute key and its named `:vector`/`:set`/`:maybe` element
reference in the registry; no Malli path propagation. Anonymous nested schemas convey no new named identity.
An attribute request stays about that value, never its parent. Missing declared pair means floor, not a wider search.
Plain Clojure values, including `{}`, `nil` and scalars, render without DB/SCI custody when no declaration is available.

Convert explicit and namespace owners in the **same dispatch-switch slice**: `oversight/unit` (`oversight.clj:211`)
declares its pair on `:seon.oversight/unit` and supplies that declared shape; make `seon.run/render-namespace-ai`
reachable by declaration, convert its four fixtures, and change walk/bootstrap/web owners (`walk.clj:774`,
`bootstrap.clj:324`, `web.clj:1463`). Wire SCI `shown-result` (`sci/eval.clj:3181`) and MCP (`cluster.clj:392`) there too.
Then remove explicit-value/request/namespace stages, specificity ranking and pulled/transacted matching from dispatch.
Retain `matching-shapes-in` and `14a9291ad` for independent schema diagnostics; do not delete the library function.
Malli seam: `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, `core.cljc:2561,2592,2637`
(`schema`, `properties`, cached `validator`). Cache declared alternatives/owned-key sets in the existing projection
by their declaration inputs; recompute when those change. Ownership is O(present keys), alternative selection is
O(number of that function's alternatives × validation cost). No registry scan or per-node contract compilation.

## 2. Render functions choose content

Keep the existing **one merged unit argument** (`producer-argument`, `render.clj:251–267`), including value, original caller path, target `:seon.render/ai` or `:seon.render/html`, retrieval provenance and needed DB/SCI custody.
Remove `:seon.render/profile` from producer units; only the floor receives B. No two-argument migration of 89 producers. A renderer returns a **smaller ordinary value or final text** (existing HTML Hiccup remains final target output).
Adjust output contracts at these boundaries; authors need no print-node grammar. Renderers choose counts, identities,
relevant fields and queries; they may hand sub-values to the same path, preserving original paths and declarations.
The floor allocates a returned value within the current share with that producer excluded at that path; it neither
redispatches into the same producer nor starts a fresh B. Nested calls share this invocation, never serialize early.

Final renderer text is charged in full against its share using the same emitter. If it overruns, replace it with a
**named renderer-overage diagnostic** identifying producer/path/allowance/actual cost, never trim the text or rerun
with a smaller dial. Diagnose at the parent if even that diagnostic cannot fit locally. The same rule covers failures
and invalid output. Final HTML is not substring-clipped; its visible text charge comes from its owning emitter,
not a second pretty-print cost model. Saved historical shown text remains terminal and is selected only as whole units.
`problems/ai-prose` returns its family-lines map: remove its nested `render-ai` call and `config/defaults` fallback.
Missing floor policy is derived once at the entrance from supplied custody or explicitly refused, never defaulted inside a renderer.

## 3. One floor budget, single-pass fair shares

Keep `fit`'s intent: shrink breadth and strings together, retain identities and string prefixes, keep map keys whole,
and preserve elision paths/counts/requery. Replace the depth-first token-named node counter in `prepare` (`value.clj:608`)
and repeated emit/estimate/halve loop in `print/fit` (`print.cljc:1294`). Already dropped branches cannot be recovered
by fitting. `fit-node` (`:1247`) and `emit-node` (`:856`) both re-sort by emitted text; remove these duplicate sorts.
`bounded-text`/Throwable floor (`value.clj:37–131`) also loses independent fixed caps and the final 8,192-character cut.
**Only characters participate in allocation.** Convert the floor's B once with `tokens/estimate-chars` and supplied
calibration (`tokens.cljc:159–169`). A collection count orders work; it is never added to a character cost. Use the emitter's own leaf printing (`emit-text` on scalar nodes) for escaped scalar costs and retain those leaf bytes;
use its delimiter/elision constants and namespace-map factoring for structural cost. No parallel cost formula.

1. Establish canonical child order and reserve container syntax, whole keys and omission/retrieval evidence.
   Derive the minimum representative share from **that child's first level**: its whole keys plus one-token value
   snippets (converted to characters with the same calibration), including required syntax/elision overhead.
   Choose the largest affordable representative prefix whose equal shares cover those minima; remaining members
   get a counted group elision. This shallow selection inspects no hidden subtree and introduces no tunable minimum.
2. Visit selected children cheapest first: known scalar encoded lengths ascending, then collections by cheap counted
   size; unknown-cost/opaque values last. These are separate ordering categories, not interchangeable cost units.
   For child i, `share = remaining-characters ÷ children-left`; recurse once and subtract actual characters used.
   Small children finish whole and pass unused space forward. There is **no redistribution or refitting loop**.
3. Place completed children back in canonical order, without printing/sorting them again. Descend only while the
   share lasts; at exhaustion use the existing `:subtree` elision and original path. No frontier expansion,
   deep-leaf search, leaf-snippet face or depth/children/string ceiling. Never rebuild paths by reading printed keys:
   retaining original paths deletes `fit-entry`'s `edn/read-string` round trip (`print.cljc:1133–1140`).
4. Emit the completed node once and compare its character count with the allowance once. An overage is a named
   allocator defect with path, not a second attempt. Mandatory root evidence that alone exceeds B yields an explicit
   insufficient-floor-budget diagnostic; never claim arbitrary metadata or infinitely many snippets fit finite B.

Canonical order preserves vectors/lists and puts declared entity identities first, then attributes. Select unordered map/set members once with the existing heap for safe finite keys. The comparator must also treat lazy/host values as
opaque: class and identity hash, with stable held-value tie order, never `toString`, custom printing, dereference or
recursive sorting inside comparisons. Compute safe composite ordering keys once per reached key; count their work.
An unaffordable complex key elides its entire entry. Across restarts opaque-object identity is unavailable, not stable.
`get_value` drills by path and passes offset to **prepare**; its elision supplies the next offset. Delete `window`,
its `pr-str` sort and preselected presentation page size. Preserve total/steps/beyond-end and missing-artifact evidence.

AI gets text; HTML gets Hiccup from the floor's same decision, with no later HTML clipping. MCP emits text plus retrieval metadata, never reprints a semantic map of the rendered node. Elisions name bound, count/unit (unknown
explicitly), original path and digest/requery; preserve carried admission `::truncated-string` lengths and `:children`
totals, and `preserve-requery`. A compact shared retrieval legend is charged once through the emitter, not free text.
Throwables are inside this allocator: class/message/ex-data/causes/suppressed/frames share the allowance. Catch failing
accessors locally. Do not execute untrusted lazy sequences/host iterators to render them; show opaque identity and
inspection/requery instead. Cycles show references. Missing retrieval custody says unavailable but does not prevent
plain-value rendering. `floor`, `bounded-text` and `render-ai` become entries to the same projection-free core.

Cost: O(visited values + output characters + selected-child cost ordering), plus O(n log k) unordered selection
and explicitly measured composite-key inspection. Space is O(selected nodes + output); no O(B) claim for all inputs.
Reuse existing caches by value/declaration/renderer/B/calibration/path/offset inputs. No hidden full-subtree scan.
The [Clojure printing guide](https://clojure.org/guides/repl/data_visualization_at_the_repl) offers depth/length controls,
not the required shape-sharing and retrieval evidence; retain the current node/emitter seam rather than substitute those controls.

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
| 11 | CONVERT | `problems/ai-prose` returns its family-lines map; no nested `render-ai`, fresh B or `config/defaults` fallback. Its data uses the current floor share. |
| 12 | CONVERT | `seon.render.transcript/render-history` shows an explicitly queried recent turn page, total and older-page link; reuse saved evaluation units. Page N is a constant of this renderer/query, never a configuration dial. Delete presentation max-children/max-nodes; keep honest query bounds. |
| 13 D | DELETE window; KEEP retrieval | `mcp-get-value` passes drilled path/offset to `prepare`; its one floor allocation chooses the page and next offset. Delete `window`, separate page-size cut and `pr-str` sort; preserve total/steps/beyond-end/missing-artifact evidence. |
| 14 D | CONVERT | `seon.render.web/render-connections` shows count and the requested page with existing pager. Delete post-query `take width`; explicit page acquisition remains, never HTML substring clipping. |
| 15 D | DELETE dead code | Namespace omission/included-count always receives the full count; remove dead parameter and elision. |
| 16 | CONVERT | `seon.render.ns/render-referenced-schemas` shows complete referenced schema names/count and definition queries, not first 40 full definitions. Names go through the one path. |
| 17 | CONVERT | `seon.bootstrap/render-opening` renders selected subjects as identities and small read forms; no spent-token candidate loop. Subject membership belongs to its query. |
| 18 | CONVERT | `seon.test/render-world-refusal` shows operation/expected/path plus the native offending value via one path. Store no 400-character replacement in ex-data. |
| 19 | CONVERT | `seon.test.runner/render-drift` shows drift/reach counts and whole native name sets via one path; delete take-10 and “first 10” message assembly. |
| 20 D | CONVERT / DELETE | `seon.cluster/render-branch-errors` shows counts and error identities plus branch/basis; bridge emits it. Keep an explicit since/cursor query window, remove hidden max-20 display loss; delete form-preview echo, caller already has the form. |
| 21 D | DELETE local clipping | Docstring/markdown/changed-test/citations script report truncations: print complete reports or send report data to the same floor. An explicitly requested ranked query is still lawful. |

The audit's six domain conversions are 10,12,16,17,18,19, each named above; 9 and 10 deliberately share one renderer. Admission byte limits, blob thresholds, agent-requested file/search windows, deadlines and legitimate ranking queries
stay. They must expose missing/partial acquisition and cannot be used as covert display limits. This design does not
delete admission or transcript subsystems wholesale; the census's larger deletion estimates require separate caller proof.

## 5. Prompt units

**Keep whole-unit selection**, following root acceptance of the review; this is provider admission, not value clipping.
`:seon.config.ai/prompt-token-budget` belongs to provider-context admission, outside the value render system; it may
select whole historical evaluations, never fit their text. “Apply presentation limits once” explicitly permits
“select whole prompt units”; “Recover facts, not execution” preserves exact shown bytes. Record which units were omitted.
Count the complete prompt (frame, separators, retrieval notice and required opening included) against model capacity.
A newest unit that cannot fit must produce an explicit capacity refusal or request the existing compaction operation,
not silently overrun capacity as `retained-count` currently allows (`prompt.clj:232`). Compaction clears evaluations
and regenerates opening under “Derive wakes and completion”; it is a distinct state transition, not a printing fallback.

## 6. Ordered implementation slices and proof

Independent review `8a9e946d7` is complete and root accepts all findings; **owner go given 2026-09-24 for all slices**. Owners below are responsibilities, not file assignments; root checks the ledger. Every slice is ≤100 added physical
lines including declarations/contracts/regressions. Preparatory accretion leaves current dispatch active; the switch
is atomic with every named caller conversion. If that cut exceeds 100 additions, return a smaller reviewed cut before
writing, not a partial switch. Shared `value.clj` slices run sequentially; there is no 89-renderer API migration.

| Slice / owner | Change, exit criterion and installed probe | Est. + / − |
|---|---|---|
| 0a schema | Declare missing identity owners with `:seon.program/row-schema`; prepare owned-key sets without changing dispatch. Probe agent-id envelope, two identities agreeing/conflicting, partial entity and non-program row classification. | 55 / 0 |
| 0b schema/render | Add cached error-operation and contracted head-Var alternative lookup without switching dispatch. Probe nested specific error, uncontracted base, ambiguous/no-match alternatives, direct report and `let` floor. | 60 / 0 |
| 1 render/SCI/MCP + explicit owners | Activate all three lookups; carry head Var into shown-result/MCP. In this same cut convert `oversight/unit`, `seon.run/render-namespace-ai`, four fixtures and walk/bootstrap/web owners. Only then remove old stages/specificity matching. Probe every converted entrance and no paired-error/report loss. | 75 / 260 |
| 2 floor/print | Fair-share allocation and emitter-owned costs. Exit requires deletion of `fit`, `fit-node`, `fit-children`, `fit-entry`, `structural-elision`, node counter and emit-node re-sort (~220-line core). Probe three branches, huge+tiny siblings, 50 long strings, tiny B, complex/lazy keys and exact charging. | 80 / 250 |
| 3 floor/error | Fold Throwable/bounded-text into same allocator; retire independent caps. Probe nested ex-data, throwing accessors, opaque lazy values, cycles and identity-free roots. | 45 / 100 |
| 4 render/problems | Retire `project-node*` redispatch; accept smaller data/final text using merged units without profile. Convert `ai-prose` to data. Probe nested problems uses one share, text-overage diagnostic, no defaults fallback and no repeated producer. | 30 / 140 |
| 5 floor/MCP | Offset retrieval inside prepare; delete window and page-size cut. Probe path/offset progress, original keys, full reconstruction, end offsets, missing digest and carried admission elisions. | 25 / 100 |
| 6 cluster/operator | Shared runtime/branch-error renderers (9,10,20). Probe >20 faults: complete counts, identities/requery, same terminal/MCP face. | 40 / 75 |
| 7 transcript/walk/web | Render declared history/connection query pages; remove walk presentation min (5,12,14). Probe membership, count/link and exact historical AI/HTML bytes. | 30 / 70 |
| 8 namespace/bootstrap | Referenced names/opening subjects; remove dead omission (15–17). Probe >40 names and small forms; no display token spending. | 35 / 90 |
| 9 test-report | Native refusal/drift data and one message path (7,8,18,19). Probe complete offender/drift retrieval and no caller Throwable branch. | 25 / 65 |
| 10 scripts/config | Remove dev clipping and max-depth/max-children/max-string-length from profile schema/config and all readers; keep only floor B and real query/storage bounds (3,21). Audit all 21 sites. | 20 / 80 |
| 11 prompt | Own §5 capacity behavior: include frame/separators/notice, retain whole units, diagnose an oversized newest unit or explicitly invoke existing compaction. Probe exact saved bytes and no silent overrun. | 20 / 35 |

Repriced estimate: **540 added, 1,265 deleted, net ~725 lines deleted**, including schema/contract/regression edits; not measured implementation. Compared with 670/1,150, this removes migration/frontier/path/leaf-face machinery and
prices identity declarations, emitter charging, offset consolidation and prompt admission explicitly. Slice 1's atomic
caller sweep is the main sizing uncertainty; no claim that 89 producers change within 75 lines.
Must-pass retained regressions: `value-test/background-poll-keeps-identity-while-payloads-grow`, `fit-preserves-breadth-and-long-strings`, carried string/children totals and `preserve-requery`. Add producer overage,
envelope ownership and nested operation-error cases; no green inferred from code or the review's hand calculations.
Root proves publication/load/arming/adoption, runs focused real-SCI probes, then affected/platform/bulk proof once
and browser paint. Record elapsed/allocation, visited nodes, order/emit counts and floor hits on parent/change:
ordinary <1 s, one final emission/comparison, no >20% or 50-ms regression; name proportional large-input cost.
This revision changes zero source/test lines and runs no JVM/tests or new live probes. Historical evidence above is retained; current health, memory and implementation performance are unverified. The foreign http-kit submodule remains untouched.
