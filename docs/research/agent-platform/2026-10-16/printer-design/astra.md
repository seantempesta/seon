---
title: Printer core — independent Astra plan
type: design
date: 2026-10-16
lane: printer-design-astra
status: proposed; awaiting hybrid and narrow check
scope: Cleanup P1; P0 supplies the static harness
---

# Printer core

Seon already has schema selection, read-evidence-aware renderer calls, budgeted print nodes and Fipp emission. The change is to compose those owners into one request: acquire useful data → select each reached subject's face → allocate one structural tree → emit and retain exactly what was shown.

Recommend changing the existing owners in place. Remove root-only dispatch, independent budgets and text composition; keep the original value whole. The allocator knows shapes, costs, paths and reference depth, never function/task/error schema names.

Phase status: P0 harness prerequisite; P1a–P1g proposed, none implemented. This is one independent plan, not the hybrid. No printer-design/opus file was read. No production code, source reload, default restart/reset, provider request or other lane session was changed.

## Authority and evidence

The assignment's 2026-10-16 ruling supersedes two older statements: CLAUDE.md Presentation permits a render to call a nested render explicitly, and the September design permits final renderer text. Here renders return data, nested dispatch belongs to the printer walk, and renderer output is never executed. The hybrid must revise those owning statements with the implementation.

Authorities: CLAUDE.md, especially Presentation, Choosing the design and Design by independent plans and a hybrid; [Opus audit](../printer-audit/opus.md) and [inventory](../printer-audit/opus-findings.edn); [Astra audit](../printer-audit/astra.md) and [inventory](../printer-audit/astra-findings.edn); [previous design](../../one-render-path-design-2026-09-25.md). The inventories have different granularity: 91 grouped Opus findings versus 569 Astra findings/candidates, including 144 inferred cases. They are evidence to reproduce, not 660 implementation tasks.

Local source observation: HEAD `3a31c93f8ed0ccef565ea5c3e882c899832bd993`. Default ran archive `91e6ff519db8c4bdb5e7e9b9ba926bba309207a7`, PID 16746, start 2026-10-16T02:05:16.319Z. Initial database commit was `6ad1874b-5074-53c6-978b-80ec90885c84`, basis 536874055. Do not treat checkout source as installed code. Both installed schema inspection and live owner calls below were JVM-mode MCP evaluations, explicit agent root and cluster default, using the explicit default connection. The expressions used local values/functions only; no JVM Var was defined or replaced. There were no SCI implementation experiments requiring a scratch branch.

**Foreign boundary:** runtime_status reported faulted: 29 open faults, 394 errored receipts, 19 agent-error signatures, 9 unowned namespaces; readiness had no missing layers. bin/seon status reported unknown health. Working-tree changes included test/seon/cluster/db_flow_test.clj and dependency checkouts; this lane did not edit them. The data-oriented and REPL skills describe older mechanisms in places; source and installed schema take precedence. In particular, the installed `:seon.render/unit` requires a database, whereas the current structural printer accepts a database-free unit.

### What exists, and the library seams

| Owner / seam | Input and guarantee | Recompute / cost |
|---|---|---|
| `seon.sci.eval/shown-result` | Value, shown-request, admission record. Binds the live result, prepares print, stores shown text; currently also evaluates source returned by renders. | Each final evaluation result. Keep result custody and recording here, remove printing policy and source evaluation. |
| `seon.render/selection`, `seon.render/render-call` | Viewer, declared schemas, consumer and subject; selected face and read evidence. | Reached subject; reuse by actual content/dependencies. No registry scan or validator compilation per child. |
| `seon.render.value/prepare`, `seon.render.value/make-node` | Existing tree and both emissions; make-node dispatches only at id zero. | Each print; breadth queue, string doubling and separate fallback policy currently live here. |
| `seon.print/emit-text`, `seon.print/emit-hiccup`, `seon.print/emit-both` | An already selected `:seon.print/node` and `:seon.print/options`. | Linear in reached node operations and emitted text. These are internal sinks, not competing value-printer entrances. |
| `fipp.engine/annotate-rights`, `fipp.engine/annotate-begins`, `fipp.engine/format-nodes` | Stream of document operations and width; incremental pretty layout. | Per emitted stream. Fipp owns grouping, indentation, line breaks and layout; it does not own schema selection, token allocation, live results or dig forms. |
| `malli.core/schema`, `malli.core/validator`, `malli.core/explainer` | Named schema plus registry; compiled validator/explainer cached on compiled schema. | Compile when declaration inputs change. Validate a request once; validate shallow node variants in the existing iterative tree walk. |
| `seon.db/read-evidence-current?`, `seon.render.walk/acquire-entity` | Held immutable database and retained read evidence. | Relevant input changes, not unrelated transactions. Preserve these owners. |
| `seon.ai.tokens/estimate`, `seon.ai.tokens/estimate-chars` | Text/capacity and calibration. | One size model. The observed shipped ratio is 3.2; nobody copies that number into an allocator. |

Pins read: Fipp `255a19061fdc20d67eca09a17e9af282aa8bf909`; Malli `dd13ff81139159778edb0a67b491eccc0baa991d`. Fipp's `fipp.edn/pretty-coll` demonstrates lazy document production but its length/level ellipses do not supply retrieval. Use the existing Seon node emitter over Fipp, not a second EDN formatter or quota writer.

Git history explains some necessary compatibility: `ea4f02883` introduced `seon.print/printed` to preserve saved expected/actual text; `8fae17370` made small operation forms indivisible; `a5fc4cb49` introduced session/metadata print-budget overrides. Preserve these meanings through the one request. Do not restore the previous text-splicing owners.

## 1. One public entry and one request

Proposed new entry in the existing owner:

```clojure
seon.render.value/print-value
[:=> [:cat :seon.render/value :seon.render/request]
 :seon.render.value/printed-result]
```

A new name is appropriate: this takes a value separately, supports several consumers, and has a total result shape unlike prepare's nil/refusal/projection union. During conversion old entries can delegate to this one implementation; each adapter disappears with its last caller. No new printer namespace.

The live registry has no `:seon.render/request`. Reuse `:seon.render/value` (the justified arbitrary-value boundary), `:seon.print/options`, `:seon.render.profile/profile`, `:seon.render/distance`, `:seon.render.data/path`, `:seon.print/node` and the existing error and continuation members. Proposed schema population below is an interface specification, not a published schema:

```clojure
:seon.render/consumer [:enum :ai :html :form :log]
:seon.render/position [:enum :current :history :context :debug :log]
:seon.render/age [:int {:min 0}]
:seon.render/detail [:enum :summary :normal :full]
:seon.render/include-errors? :boolean
:seon.render/direction [:enum :head :tail]
:seon.render/mode [:enum :declared :structural]
:seon.render/outputs [:set {:min 1} [:enum :text :hiccup]]
:seon.render/part
[:map
 [:seon.render.data/path :seon.render.data/path]
 [:seon.render/position :seon.render/position]
 [:seon.render/age {:optional true} :seon.render/age]
 [:seon.render/detail {:optional true} :seon.render/detail]
 [:seon.render/direction {:optional true} :seon.render/direction]]
:seon.render/parts [:vector :seon.render/part]
:seon.render/request-base
[:map
 [:seon.render/profile :seon.render.profile/profile]
 [:seon.print/options :seon.print/options]
 [:seon.render/consumer :seon.render/consumer]
 [:seon.render/outputs :seon.render/outputs]
 [:seon.render/position :seon.render/position]
 [:seon.render/age :seon.render/age]
 [:seon.render/detail :seon.render/detail]
 [:seon.render/distance :seon.render/distance]
 [:seon.render/include-errors? :seon.render/include-errors?]
 [:seon.render/direction :seon.render/direction]
 [:seon.render/parts {:optional true} :seon.render/parts]
 [:seon.repl/handle {:optional true} :seon.repl/handle]]
:seon.render/request
[:multi {:dispatch :seon.render/mode}
 [:structural
  [:and :seon.render/request-base
   [:map [:seon.render/mode [:= :structural]]]]]
 [:declared
  [:and :seon.render/request-base
   [:map
    [:seon.render/mode [:= :declared]]
    [:seon.db/db :seon.db/database-value]
    [:seon.sci.eval/ctx :seon.sci.eval/ctx]
    [:seon.schema/projection :seon.schema/projection]
    [:seon.sci.admit/caps :seon.sci.admit/caps]
    [:seon.sci.eval/time-limit-ms :seon.sci.eval/time-limit-ms]
    [:seon.render/namespace :seon.render/namespace]]]]]
```

The declared arm carries the existing render-call execution caps/time-limit inputs. Those are execution bounds, not a second display budget. Publish this compiled union with its consumers in P1a; do not weaken the inputs to maps. The request carries no connection or writer. Call preparation supplies the same named read-only bindings to each face. Structural mode is the legitimate pre-boot capability: the same algorithm has no declared faces to select.

All shown numerical policy originates in a compiled profile populated by configuration. Add profile members for position/age budgets and map-order work, using the existing configuration derivation. A budget rule is an open map of from-age and token-budget; a position maps to an ordered vector of those rules. Largest applicable from-age selects the allowance; this is an upper allowance inside the root budget, never an additional budget. Missing rules use the profile root allowance. The default current allowance stays the observed 6,144 until corpus evidence changes it. Migrate the existing history reference-tokens value into the history rule, then delete that dial and its callers.

Detail is a render input: summary/normal/full selects useful domain data. It does not grant more tokens. Distance counts database-reference hops, not nested map depth. Age is an ordinal supplied with the historical unit, not an implicit wall-clock read. Parts annotate paths in the supplied value; they cannot introduce another budget, printer or mutable context. A current reply can be one ordinary map containing result, source, stdout, errors, findings and costs, with stdout marked tail-first. A history context is a collection of whole exchange units under one parent request.

Proposed `:seon.render.value/printed-result` is an open map with required `:seon.render.value/tree`, estimated-token charge, a vector of existing elision descriptors and a vector of diagnostics; optional `:seon.render.value/text` and `:seon.render.value/html` appear exactly for requested outputs. Empty vectors are valid runtime values. The original value remains in its current result owner, not copied into this result or stored as a tree. Use `:seon.print/omitted`, `:seon.print/elision-unit`, `:seon.print/bound-by`, `:seon.render.data/path` and the existing requery/continuation fields for cuts; do not invent a parallel cut language. A lazy count is explicitly pending, never a guessed total.

```clojure
:seon.render.profile/from-age [:int {:min 0}]
:seon.render.profile/age-budget
[:map
 [:seon.render.profile/from-age :seon.render.profile/from-age]
 [:seon.render.profile/token-budget :seon.render.profile/token-budget]]
:seon.render.profile/position-budgets
[:map-of :seon.render/position [:vector :seon.render.profile/age-budget]]
:seon.render.value/tokens [:int {:min 0}]
:seon.render.value/printed-result
[:map
 [:seon.render.value/tree :seon.print/node]
 [:seon.render.value/tokens :seon.render.value/tokens]
 [:seon.print/elisions [:vector :seon.print/elision]]
 [:seon.render.value/diagnostics [:vector :seon.render.value/diagnostic]]
 [:seon.render.value/text {:optional true} :string]
 [:seon.render.value/html {:optional true} :seon.render/hiccup]]
```

Add position-budgets as an optional member of the existing profile. Its configuration producer is the proposed `:seon.config.render.agent/position-budgets` dial, declared with `:seon.config/dial true` and the named position-budgets shape; `seon.render/agent-render-profile` carries it to the request. These are runtime request/result shapes, not database attributes or a new task lifecycle. When an impossible budget cannot hold even the minimal failed node, return that node and its diagnostic as data with no text/hiccup and charge zero; report the required charge in the existing insufficient-floor-budget error. The normal output-presence rule has this explicit failure case. Never report an oversized emitted string as within budget.

Consumer selects semantic face: AI selects the AI render, HTML the HTML render, form the form render; log uses AI data with a single-line layout. Form output is data, never evaluation. outputs selects the final sinks. Text plus hiccup for one consumer comes from the same selected tree. Different AI/HTML render functions may return different domain data; do not claim their text must match. They share occurrence selection, request semantics, retrieval and allocator. Request only AI text on the hot reply path; do not build unused HTML.

### Caller conversion

| Current entrance / composition | Target responsibility |
|---|---|
| `seon.sci.eval/shown-result` | Bind final value, construct request from its explicit bindings, call print-value once, retain exact emission and diagnostics. No core-text fallback or evaluated-source callback. |
| `seon.repl/response-entries`, `seon.repl/reply-map`, `seon.repl/shown-output` | Produce the reply map/parts; printer owns all selection, formatting and stdout allocation. Delete reply-map's string join and beside/spent arithmetic. |
| `seon.context/block-views`, `seon.context/model-context`, `seon.context/exchange-ai` | Produce context and exchange values, annotate position/age; select whole saved units. Remove manual reference text and direct face calls. |
| `seon.render.transcript/projected-entry`, `seon.render.sent/view-html` | Pass domain values/printed historical units to the same entry; keep UI shell outside domain rendering. |
| `seon.render.web/render-source-call`, web preview/error pages | HTML request over subject; no alternate evaluation or renderer dispatch. |
| `seon.mcp/runtime-status`, `seon.cluster.boot/request!`, operator/log/error entrants | Print their data under an explicit available profile; boot uses structural request. Writes to transports consume already emitted text. |
| `seon.render.value/core-text`, `seon.render.value/core-html`, `seon.render.value/text`, `seon.render.value/html`, `seon.render.value/render-ai`, `seon.render.value/render-html` | Convert every caller and schema attachment, then delete. A selector's structural alternative is a data decision, not a schema attachment back to the printer. |

A data producer fixes unnecessary bulk before this stage. A 100 MB accidental page dump is a producer defect even if the printer shows it cheaply. An intentionally large query remains intact and is explored by its handle. The printer does not infer intent from collection size.

## 2. Nested selection, without renders calling renders

Use `seon.render.walk` for reached-subject traversal and reference acquisition; use `seon.render/selection` and `seon.render/render-call` for the sole face selection/invocation. The generic allocator in `seon.render.value` owns the worklist and budget. Consolidate declared-projection's alternate invocation into render-call. Move identity/related acquisition out of `seon.render.value/declared`; delete its root-only condition and walk-with-related side composition.

Each admitted map or JVM/SCI Var is a subject occurrence with its original origin and path. The walk asks selection once: explicit value override, viewer namespace contract fit, most-specific declared schema, structural data. A nested subject uses its own declaration; a parent's attribute request does not turn the child into that parent.

A selected renderer's returned root is the replacement data for **that occurrence** and is lowered structurally once. It is not another selection request for that same root. Its child maps/Vars are new reached subjects and can select their own faces. This is the precise boundary between “never re-dispatch the render output” and “nested dispatch everywhere.” No renderer-name suppression set: a tree containing two nodes of the same schema must render both. Generic reference/ancestor cycles remain reference nodes, under the same depth/budget semantics.

HTML is a document grammar. Tags and attribute maps are syntax, not domain subjects. Normalize the declared hiccup to the same structural document nodes; native domain children are explicit child-value slots when hiccup syntax would be ambiguous (notably vectors). Add one child face to `:seon.print/node-kind`, with `:seon.render/value` and origin/path; provide a small data constructor in `seon.render`. It does not render. This replaces the core-text leaf calls and renderer-to-renderer calls; it is not a marker interpreter that evaluates forms. Ordinary AI maps need no wrapper.

Only reached visible slots invoke renders. A closed HTML disclosure contains a summary and dig descriptor, not a fully rendered hidden subtree. Opening it is a new request for that subtree on its held basis. Realize its work inside that request's read-only bindings; never let a lazy sequence of database reads escape the binding lifetime. A producer eagerly building huge hiccup before returning must change its own returned structure.

**Origin matters after transformation.** A derived value can add/rename/reorder members. A cut inside it must not emit get-in against the original result as if the paths matched. The print stage retains the face's data as a live result in the existing bounded result cache, with a result symbol derived by the existing result identity owner; cuts use plain core get-in/nth/drop/subs on that held data. No second result store. Equal renderer input/definition/read evidence reuses the retained derivation. Preserve the original result symbol for original-data cuts. In structural mode without custody, return the existing explicit requery refusal. After eviction/restart a derived handle is gone, exactly like an ordinary result. This retained output is live data, not persisted renderer text or a new execution event.

Renderer cache identity includes selected definition, value identity/content, face, detail, viewer selection inputs and read evidence. The budget does not change what an AI render returns; it changes the selected print tree. Hash neither an entire unseen lazy tail nor an arbitrary host object to obtain sharing. Such values use their existing live handle/held identity; immutable admitted entities use their existing digests. Do not add per-agent cache keys or a new atom.

## 3. Allocation policy

Replace `seon.render.value/sibling-level`, `seon.render.value/admit-chunk`, `seon.render.value/fill-strings` and their 32-character doubling with structural alternatives and incremental costs.

1. **Structure first.** An expanded map retains each admitted key and a value outline; a collection retains kind, known count or pending count, and a usable dig form. If all keys themselves exceed the budget, collapse that map with keys/get-in retrieval. “Structure always” cannot mean printing ten million keys under a finite budget.
2. **Whole strings while they fit.** Keep strings as one leaf candidate. There is no equal per-attribute share. A long docstring that fits beside collapsed collections remains whole. Digests receive no artificial priority over prose.
3. **Collections before strings.** Among reached, finite sibling alternatives, collapse the collection with the largest removable rendered cost first, keeping its count and dig form. Retain small collections when their collapse would cost more. Recompute only affected ancestor totals. Do not repeatedly print the whole map to choose the next collapse.
4. **Sequences show whole members.** Admit one member and its complete candidate structure at a time, then a single tail node with count and drop form. No row of empty tuple placeholders. A first member that cannot fit alone gets its own honest structural expansion/cut; otherwise one enormous first member could prevent all useful inspection. For sets/maps, preserve the held traversal order and retrieve using the original collection's seq/keys, never sort by printed text.
5. **Cut strings only after useful collection savings are exhausted.** Reduce the largest remaining string's visible region, leaving smaller strings whole. Charge quotation, escaping, omission count, dig form, enclosing structure and Fipp layout. Emit one cut with its exact substring coordinates. Whole code/operation forms remain indivisible while they fit; a large source string is still one source value with one subs form.
6. **Tail-first is explicit.** For output/log strings, keep the suffix and a subs form for the omitted prefix. For counted reversible sequences, use their held end ordering. Do not take-last on an unknown/infinite stream: the output owner must provide a finite retained log/window and its origin. The printer does not acquire or silently discard an unbounded log.
7. **One budget includes everything shown.** Labels, punctuation, cuts, error summaries and nested faces consume the same root allowance. A nested part's allowance is capped by root remainder. Emission never clips HTML or substrings the final text.

“Largest” is exact among the candidates inspected, not a promise to discover the biggest unseen subtree in an infinite input. Saturate cost measurements at the remaining allowance: once a candidate provably cannot fit, represent its expansion as over-budget and collapse it without exploring the rest. Equal saturated candidates follow held order. Finding a globally largest arbitrary subtree would force whole-value work on every reply; that is not the intended cost model.

Reuse the existing iterative nodes/worklist and Fipp operation cost facts. Charge once per reached operation; maintain subtree deltas when an alternative collapses. Fipp line layout must be included in candidate charge, not repaired with a last substring after emission. A proof compares the accounted charge with the actual emitted text for widths, multiline strings and nested maps. Do not claim a layout oracle before that proof is red and then green.

All policy numbers belong to the profile/configuration: token budget/ceiling, age allowances, depth/explicit print bindings, width, and bounded key-order work. Delete chunk-width, scalar-bound, name-bound, nest-cap, sorted-bound, tuple-bound and text-limits when their policies disappear. A semantic constant such as one tail node is not a dial. If a remaining numeric cutoff actually controls policy, declare it as a dial; do not preserve unexplained constants merely by moving them to a config file.

### Complexity and cost obligation

Let V be reached structural nodes, E emitted text operations, S examined string content, and K the finite candidates considered at one expansion. Selection/allocation is O(V + S + K log K) using one local ordering of collapse candidates, emission O(E); memory is O(V + E). Already-counted collections give counts without traversing their tails. Unknown lazy tails stop after the admitted prefix plus the next bounded candidate; Clojure chunk realization remains a documented dependency cost. No dependence on the total 5,000/5,000,000 rows when the same prefix fills the budget.

The ordinary reply pays one request normalization, one set of viewer/schema bindings, only reached face calls, one allocation and the requested sinks. It must not construct HTML for an AI-only reply, compile validators repeatedly, scan all schemas per node, acquire all refs eagerly, or re-serialize candidate prefixes repeatedly. Reuse retained face output/read evidence through seon.cache only where measured useful.

P1c must measure `seon.profile/measure` around the full print entry on parent and candidate in the same quiet run, with the six captures below. Report wall time, allocation scope, CPU window, reached reads/calls and final tokens. A faster allocator alone does not prove a faster reply. No performance claim is accepted from this plan's layout prototype.

## 4. Real-value experiments

The final probe is reproduced in the appendix. It uses default's actual entities and query rows, the installed structural printer and token estimator, and an expression-local alternative allocation. No render pair runs: this isolates the generic allocator. Full declared-render, HTML paint, cache lifetime and complete reply integration remain red-proof obligations.

Observed 7,229 functions and 4,956 documented functions. The exact 5,000-row case is a sorted function-symbol/arglists query prefix, not synthetic rows and not the 4,956-row doc query. Function: seon.sci.eval/shown-result, 20 fields. Task: entity 483409, seon.sci.kernel/interruptible-range-allocates-six-times-core-range. Error: stored occurrence 1023637, 32 fields. Exchange: entity 1343740 via seon.repl/entity-emission and seon.context/exchange. Agent must-reads: 20 real resolved references for attempt/ba03314dbf54's held task, seon.error/an-unresolved-alias-name-offers-its-nearest-core-name-2.

| Value | Budget | Today estimated tokens / cuts | Alternative estimated tokens | Alternative content |
|---|---:|---:|---:|---|
| Function entity | 1,024 | 978 / 7 | 1,004 | Docstring and spec whole; four collections collapsed; source cut once |
| Task | 512 | 404 / 0 | 404 | Whole |
| Stored error | 1,024 | 1,022 / 1 | 1,005 | Only data-edn shortened; occurrence message whole |
| 5,000 query rows | 6,144 | 6,143 / 18 | 5,907 | 343 whole rows, one tail reference for 4,657 |
| Agent must-read vector | 6,144 | 5,906 / 1 | 5,530 | 19 whole references, one tail reference |
| Evaluation exchange | 512 | 301 / 0 | 301 | Whole |

The final installed printer made zero **additional** cuts on the alternative values. The alternative's own count/dig maps are the cuts; zero additional cuts does not mean whole original data. The must-read vector is the block's actual slot: printing the enclosing map separately measured 5,924 tokens and two cuts. The earlier small two-reference task block fit whole at 968 tokens.

| Value | Today print ms / allocated MB | Prototype allocation ms / allocated MB |
|---|---:|---:|
| Function | 5.87 / 9.04 | 59.85 / 114.33 |
| Task | 0.64 / 1.07 | 0.97 / 1.59 |
| Error | 2.31 / 4.23 | 9.60 / 18.17 |
| Query | 18.91 / 39.57 | 54.30 / 90.64 |
| Must-reads | 17.95 / 36.15 | 33.58 / 64.75 |
| Exchange | 0.80 / 1.32 | 1.46 / 2.58 |

These are measured calling-thread allocations from seon.profile/measure, decimal MB. Prototype columns exclude its final emission, so they are already a cost regression. They reject a production “print, measure, collapse, print again” algorithm. The final MCP request was 346.7 ms, 427,288 calls and 1,069.7 MB reported allocation; the evaluated form itself was 300 ms. This is experiment work over many repeated full prints, not an ordinary-print performance result. Result handle: result/ee0b5233cc4dc; request-cost handle: result/c4ecb386bd248.

The prototype uses a conservative sequence overhead reservation and a string sizing reserve. Those numbers are experimental parameters, not proposed runtime defaults. A first version incorrectly assumed four characters/token, and overfilled strings; using seon.ai.tokens/estimate-chars fixed it. One earlier expression had a delimiter error; one discovery query guessed absent :seon.task/task and returned an invalid-read value. Neither is evidence of a printer algorithm defect. The delimiter error did expose repeated whole source in its shown error; that behavior is already covered by the audits' error-copy/printing families, not a new successful result.

No semantic schema ranking was used to preserve the docstring. Positive collection savings and correct escaped/token accounting were sufficient on this real value. This is evidence for the policy, not proof for all shapes.

## 5. Historical units, errors and the last resort

Use `seon.print/printed` for already emitted historical content. It is indivisible: admit the whole unit and its measured charge, or show one count/handle/requery reference. Never re-budget its characters. A fresh current reply is assembled before its first print, so stdout/errors/findings and value share the budget at their common producer. A late recording error becomes a separate diagnostic part under the same entry; it must not overwrite the already evaluated value. Historical replay preserves the saved emission; an explicit live inspection is a new request and can choose a new profile.

include-errors? governs attached diagnostic detail, never whether an error happened or is recorded. False retains the count and retrieval to omitted details. It must not hide an error that is itself the returned value. Errors absent from the visible budget still remain queryable beside the original live value.

There is one structural last resort within print-value. If a face fails, lower the original value structurally at that occurrence and attach one typed diagnostic, with its path, under the same budget. If traversal fails at a lazy child, preserve the reached prefix and put a failed/opaque node at that child. Host objects are class/identity, with no arbitrary toString or field walk. If the allocator itself fails, its boundary returns the existing minimal failed/opaque node for the original object plus the failure, using the same leaf emitter; it does not retry the whole traversal or invoke another renderer/printer. Request validation must account for the minimum root/error representation; impossible tiny requests return a typed insufficient-budget result with the required charge, not a falsely in-budget transcript.

Delete `seon.render.value/datum`, `seon.render.value/bounded-text`, `seon.render.value/throwable-text`, `seon.render.value/link-lines`, `seon.render.value/text-limits` and fixed core-profile with their last callers. Convert `seon.error/render-chain-ai`'s borrowed frames limit to data/ordinary printer selection. Cause acquisition remains the error owner's operation. Its repeated frames and copied ex-data are producer issues; the printer cannot recover evidence already clipped in storage.

AI faces returning assembled text become named data shapes: `seon.cluster.message/format-ai` disappears into message data; `seon.effect/render-ai` returns effect fields and payload references; error faces return variant data and fixed advice; `seon.problems/ai-prose` returns families/operations; transcript/run faces return entries; `seon.print/render-elision-ai` returns the elision map. Source/doc/instruction prose remains authored string content inside those values. Do not ban every string return or wrap a formatted report in a map and call it data.

The static harness distinguishes transport codecs and source write-back from presentation. Required lossless EDN/JSON encoding cannot be routed through a budgeted printer. Storage-form/identity changes in the audits need their producer's proofs and are not silently added to P1.

## 6. P0 harness and phased implementation

P0 is a development proof, not a runtime guard. Reuse `seon.fn.analyzer`'s clj-kondo analysis and parsed schema attachments. Derive the renderer set from packaged declarations. Do not derive “allowed printer code” as every transitive callee of shown-result: that graph reaches general code and could exempt the violations it is meant to find.

P0's rule tests must first reject small source fixtures containing aliased renderer calls, map/apply/Var references to faces, alternate render-call owners, printer wrappers in a face, native data formatted into an error/Hiccup sink, and display truncation outside allocation. Positive fixtures cover literal chrome, protocol codecs, source parsing, query windows and authored prose. Analyze actual source contracts/AST for flow where possible; unresolved dynamic flow is reported as unresolved evidence, not silently certified. A global inventory is an explicit campaign result until the owning conversions make it green; no filename exemption list or grandfathered finding baseline.

Proposed proof symbols below are **new tests**, not claims that they exist or have run. Prefer adding the cases to the named existing tests where they cover the same behavior class. All implementation phases use the ordinary agent runner on isolated branches; host-bound printer/evaluator/schema changes use the candidate publication/reload and throwaway-JVM path.

| Phase | Slice and deletions | Red proof and completion |
|---|---|---|
| P1a — request and structural entry | Existing value/print/render owners and schema resources; publish request/result/profile shapes and print-value. Old entries temporarily delegate to one body. No new cache. | Proposed seon.render.value-test/one-request-owns-every-output: same input/parts yields shared cuts/charge through text, hiccup, log and form; nil/false survive; structural request works without DB. Extend seon.render.value-test/ai-values-are-laid-out-by-fipp-at-their-width. Red is missing unified behavior, not an unresolved symbol. |
| P1b — nested subjects and origins | Consolidate declared-projection into render-call, make walk the invocation owner, remove root-only make-node dispatch and with-related side print. Add typed child slots for HTML. | Proposed seon.render.value-test/nested-subjects-use-the-viewers-render: real SCI declared faces at root, two same-schema children and nested Var; no direct face calls; non-reached child never runs. Proposed seon.printer-elision-test/a-derived-face-cut-reads-the-derived-value: renamed/generated output, run its cut on retained data; eviction answers gone. Existing declared-producers-still-have-absolute-precedence remains. |
| P1c — structural allocation | Replace sibling/chunk/fill queue with whole-string and whole-member alternatives, incremental charges, profile policies. Delete superseded constants/functions. | Extend documentation-body-is-whole-or-one-executable-elision, ai-realizes-only-the-members-it-prints and a-map-beyond-the-budget-costs-the-budget-not-its-size in seon.render.value-test. Add real function/query rows and a map with more key text than budget. Proposed seon.print-test/accounted-layout-equals-emitted-charge covers multiline/escapes/width. Compare full-entry parent/candidate cost with seon.profile/measure; reject regression. |
| P1d — one reply and history composition | seon.repl/context plus their schema consumers; replace reply-map, response-entries string fragments, shown-output arithmetic, exchange reference-tokens policy and exchange-text joins. Convert source-output producers; remove evaluated-source render callback. | Extend seon.repl-test/the-reply-is-one-edn-map-whatever-the-value, printed-output-is-separate-from-the-value, history-preserves-shown-text-without-applying-a-later-profile and a-response-carries-errors-as-data-and-late-diagnostics-beside-the-value. Proposed seon.repl-test/parts-share-one-budget covers value+stdout+errors+findings and age allowances. Proposed seon.render.value-test/a-rendered-form-is-never-evaluated proves zero effects. |
| P1e — data faces and outer callers | Convert schema-attached text faces and renderer-to-renderer calls in file-disjoint groups from P0's current evidence. Domain helpers return data. Convert web/transcript/sent/MCP/operator callers to print-value. Each group deletes its final obsolete wrapper; no concurrent ownership of the same files. | P0 rules for each converted owner become green; real entrance tests compare domain data and retrieval, not incidental wording. Keep seon.context-test/a-failure-claim-shows-its-saved-printed-value-once and seon.print-test/a-printed-value-shows-its-text-unchanged-wherever-it-sits. Proposed seon.render.value-test/html-reaches-only-open-value-slots proves lazy render acquisition. |
| P1f — remove the second printer | Convert remaining core-text/core-html edges, delete datum/bounded-text/throwable-text/text-limits/core-profile and public adapters. Remove schema attachments to printer-as-renderer. | Extend seon.render.value-test/a-lazy-result-that-throws-while-printing-is-shown-with-its-failure and seon.print-test/terminal-emission-is-total-for-an-unknown-or-absent-face. Proposed seon.render.value-test/a-failed-face-keeps-value-and-one-error covers read-only face failure and early boot. P0 proves no surviving reference to deleted entries or duplicate budget. |
| P1g — integrated proof and cleanup | Re-run P0 on current whole population; remove obsolete policy docs/tests in their owning conversion, revise September design and CLAUDE Presentation. Root batches the restart after proven slices. | Through actual MCP evaluation and saved context/web views, all six subjects preserve value, runnable digs, one budget and failure delivery. Record parent/candidate print and full-reply costs, allocation and reads; inspect browser paint separately. Root runs affected integration/bulk once. No inferred audit candidate is called fixed without its reproduction. |

P1e is a set of short file-owned slices, not one 88-namespace commit. The one-render lane's context/repl/transcript/sent files are a dependency: this plan does not claim them or assume their current work is complete. Root schedules those conversions after that lane's commit or integrates the contract with its owner. No implementation task is filed from this independent plan before the hybrid/narrow check.

The first red versions must load and fail an assertion about behavior. P1a can exercise the proposed behavior through a test-local call to today's entry until the new name exists; do not use a missing Var as the red. Test selection is by actual reaching tests; the table is a starting set, not authority to omit newly reaching tests.

### Alternatives and decision

- **Use Fipp length/level alone:** smallest implementation, but loses shared token allocation, useful docstrings and executable retrieval. Does not meet the requested behavior.
- **Change existing selection/node owners (recommended):** preserves Fipp, schema selection, read evidence and result custody. Seven loadable concerns above; cost is caller conversion and provenance proof. No new registry or persistent service.
- **Globally size and repeatedly compact whole values:** simplest policy prototype, but the measured function case costs roughly ten times today's print and cannot safely count an infinite sequence. Retain only as a bounded layout experiment; do not install it.

## 7. Verification for this documentation slice

Only this Markdown path is claimed. Cache-disabled lint of src script test completed: 591 files, 0 errors, 1,802 warnings, 15 informational findings, 8,089 ms reported. Exit 2 is the warnings status, not zero findings. No runtime tests are claimed green, no pre-existing reds are accepted, and source/test line delta is zero.

All four Clojure fences parse (2, 24, 10 and 1 forms respectively). The 17 proposed declarations in the two schema fences compile with `malli.core/schema` against a local `malli.registry/composite-registry` of those declarations and default's held registry: all 17 returned schema? true, evaluated form 2 ms; MCP request 82.9 ms, 1,790 calls, 11.6 MB allocation. Handle result/eab178573c30a. No declaration was registered or published. This proves reference resolution and schema construction, not instrumentation, publication or runtime behavior.

The requested docs-only bin/lane commit path uses seon.test.cache/gate-input? to skip publication and a throwaway JVM when no executable input changes and no test is named. No executable test reaches this plan; naming an unrelated test would not prove it. The implementation phases above must name their actual reaching tests and satisfy publication/reload evidence. Foreign in-flight edits remain outside the candidate. Probe forms and results are retained below rather than depending on a scratch file.

## Appendix: repeatable allocation experiment

Run as a single JVM-mode eval_clj expression with agent root, cluster default. It reads default, computes locally and returns small measurements. Numeric entity IDs below identify this dated store capture; after retention/rebuild, select the stated identities again and record that it is a new capture. The local symbolic root is an illustrative dig origin, not a bound live result: this experiment measures allocation and does not claim executable-continuation proof. P1b must prove actual origins. The prototype's abbreviated :omitted/:dig maps are experiment data; production uses the existing namespaced elision schema.

The expression deliberately uses the existing printer as its cost oracle. Its repeated prints are the measured reason it must not become the implementation.

```clojure
(let [db @(seon.cluster.boot/connection "default")
      profile (seon.render/agent-render-profile (seon.config/effective db "default"))
      task (seon.db/pull db '[*] 483409)
      emission (seon.repl/entity-emission (assoc (seon.db/pull db '[*] 1343740) :seon.db/db db))
      values [[:function 1024 (seon.db/pull db '[*] [:seon.fn/sym 'seon.sci.eval/shown-result])]
              [:task 512 task]
              [:error 1024 (seon.db/pull db '[*] 1023637)]
              [:query 6144 (vec (take 5000 (sort-by first (seon.db/q db '[:find ?s ?a :where [?e :seon.fn/sym ?s] [?e :seon.fn/arglists ?a]]))))]
              [:must-read 6144 (#'seon.context/must-reads db (seon.context/held-task db "attempt/ba03314dbf54") #{})]
              [:exchange 512 (seon.context/exchange {:seon.config.context/reference-tokens 512} emission nil false)]]
      prepare (fn [v budget] (seon.render.value/prepare {:seon.render/value v :seon.render.value/root 'result/probe
                                                        :seon.render/profile (assoc profile :seon.render.profile/token-budget budget)
                                                        :seon.render.value/options {:seon.render.value/structural? true}}))
      size (fn [v] (seon.ai.tokens/estimate (:seon.render.value/text (prepare v 100000))))
      cut (fn [v path] {:omitted (count v) :dig (list 'get-in 'result/probe path)})
      compact (fn compact [v b]
                (if (vector? v)
                  (loop [i 0 out [] spent 300]
                    (let [rest-cut {:omitted (- (count v) i) :dig (list 'drop i 'result/probe)}
                          next-out (when (< i (count v)) (conj out (nth v i)))]
                      (if (and next-out (<= (+ spent (size (nth v i)) 1) b))
                        (recur (inc i) next-out (+ spent (size (nth v i)) 1))
                        {:value (cond-> out (< i (count v)) (conj rest-cut)) :whole-members i})))
                  (loop [out v candidates (sort-by (comp - size second) (filter (fn [[k x]] (and (coll? x) (> (size x) (size (cut x [k]))))) v)) collapsed []]
                    (if (<= (size out) b)
                      {:value out :collapsed collapsed}
                      (if-let [[k x] (first candidates)]
                        (recur (assoc out k (cut x [k])) (rest candidates) (conj collapsed k))
                        (loop [out out strings (sort-by (comp - count second) (filter (comp string? second) out)) shortened []]
                          (if (or (<= (size out) b) (empty? strings))
                            {:value out :collapsed collapsed :shortened shortened}
                            (let [[k s] (first strings)
                                  marker {:omitted (count s) :dig (list 'get-in 'result/probe [k])}
                                  room (seon.ai.tokens/estimate-chars (max 0 (- b (size (assoc out k marker)) 32)))
                                  n (min (count s) room)
                                  replacement {:prefix (subs s 0 n) :omitted (- (count s) n)
                                               :dig (list 'subs (list 'get-in 'result/probe [k]) n)}]
                              (recur (assoc out k replacement) (rest strings) (conj shortened k))))))))))]
  (mapv (fn [[label b v]]
          (let [before (seon.profile/measure #(prepare v b))
                proposed (seon.profile/measure #(compact v b))
                candidate (:seon.profile/value proposed)
                after (prepare (:value candidate) b)
                p (:seon.profile/value before)]
            {:case label :budget b :members (count v)
             :before-tokens (seon.ai.tokens/estimate (:seon.render.value/text p))
             :before-elisions (:seon.render.value/elisions p)
             :before-ms (:seon.profile/ms before) :before-alloc (:seon.profile/alloc-bytes before)
             :after-tokens (seon.ai.tokens/estimate (:seon.render.value/text after))
             :after-printer-elisions (:seon.render.value/elisions after)
             :proposal-ms (:seon.profile/ms proposed)
             :proposal-alloc (:seon.profile/alloc-bytes proposed)
             :whole-members (:whole-members candidate)
             :collapsed (:collapsed candidate) :shortened (:shortened candidate)}))
        values))
```
