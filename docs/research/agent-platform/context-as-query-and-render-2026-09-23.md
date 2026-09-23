---
type: research
status: current
created: 2026-09-23
scope: agent context query, graph traversal, render precedence, history and incremental cost
---

# Context as a query and render functions

Seon already queries program and evaluation rows and dispatches declared renderers.
The smallest composition is a bounded query returning ordinary values, rendered by
schema pairs, with new observations appended through the existing system-turn owner.

## 1. Findings that should change the design

1. **VERIFIED — opening generation and prompt assembly are different pipelines.**
   `declared-sources` renders an agent neighbourhood into source, parses it, and
   `system-turn` evaluates selected reads (`src/seon/turn.clj:1852,2059`). The
   provider prompt instead renders saved evaluations (`src/seon/render/walk.clj:927`)
   through `seon.repl/render-ai` (`resources/seon/schemas/seon.eval.edn:6`).
   **UNVERIFIED proposal:** preserve this observation boundary; a universal query
   must not silently re-render old observations whenever documentation changes.
2. **VERIFIED — there is already a universal renderer decision, but its precedence
   is broader than namespace inheritance.** The exact order is
   `[:explicit-value :explicit-request :namespace :schema :floor]`
   (`src/seon/render.clj:453`). Namespace candidates are public functions compatible
   with input and output contracts; multiple matches refuse (`:276,519`).
   **UNVERIFIED proposal:** make the consumer namespace explicit and restrict its
   override to the schema being rendered; use the schema's pair otherwise.
3. **VERIFIED — current traversal is not the requested explanatory graph.** It
   follows declared concerns/components and special namespace requires/requirers
   (`src/seon/render/walk.clj:94,103,408`). Namespace functions and schemas are sorted
   by name (`src/seon/render/ns.clj:67,83`), not prerequisite order. The separate
   `ordered-episode` checks introduction/readiness inside an already bounded candidate
   set (`src/seon/render/walk.clj:856`); it does not discover the requested graph.
   **UNVERIFIED proposal:** change neighbour queries, not add another graph registry.
4. **VERIFIED — Malli supplies the path machinery.** `m/explainer` returns schema,
   value and error data; `mu/subschemas`, `mu/in->paths`, and `mu/get-in` navigate
   schema/value paths (`reference-code/malli/src/malli/core.cljc:2647`;
   `reference-code/malli/src/malli/util.cljc:168,196,342`). Seon's boundary refusal
   already records function, check, schema location and value location
   (`src/seon/instrument.clj:586`). **UNVERIFIED proposal:** consume these values;
   do not parse diagnostic prose or implement another schema-path interpreter.
5. **VERIFIED — green reach does not itself contain an observed successful call.**
   The inspected schemas declare test source, reach and digests, and run input
   identity (`resources/seon/schemas/seon.test.edn:2,9,88`;
   `resources/seon/schemas/seon.test.run.edn:38`). `verified?` checks current inputs
   and reach digest (`src/seon/test.clj:1342`). **UNVERIFIED:** a general persisted
   successful-argument trace is available. A source example from a green reaching
   test must be labelled as such, not presented as observed successful arguments.
6. **VERIFIED — the expensive invalidation surrounds already reused renderings.**
   The prior profile reports 4,067.868 ms after an unrelated write and 8,241.552 ms
   after one saved-text change, despite reuse of individual render calls
   ([context-cost, §B](context-cost-2026-09-23.md#b-prompt-history-and-namespace-rendering)).
   Source confirms a whole-history root plus `every?` read-currency checks across
   retained calls (`src/seon/render/web.clj:2543`) and wildcard nested evidence
   (`src/seon/eval.clj:26`). **UNVERIFIED proposal:** incremental membership and
   per-value rendering replace aggregate replay; a different renderer alone cannot
   remove this cost.
7. **VERIFIED — budgets have several different meanings.** Value rendering fits AI
   output once (`src/seon/render/value.clj:585`); prompt selection drops whole old
   units and retains at least the newest (`src/seon/cluster/prompt.clj:228,297`).
   The frame is appended after selection (`:398`), so selection does not reserve
   its cost. Walk width/depth limits bound acquisition (`src/seon/render/walk.clj:84`).
   **UNVERIFIED proposal:** budget the final composition including frame and elisions;
   never apply a new value profile to historical shown text.
8. **VERIFIED — the obvious frame exception is hand-assembled outside a schema pair.**
   `repl/frame` queries the episode count and returns `"turns left: "` prose
   (`src/seon/repl.clj:50`); prompt appends it directly. Namespace/test renderers also
   generate explanatory comments and executable reads (`src/seon/render/ns.clj:744,795`;
   `src/seon/render/test.clj:40`). **UNVERIFIED proposal:** keep prose inside renderers
   of explicit values, moving frame facts into that same composition. This does not
   require banning strings inside render functions.
9. **VERIFIED — source evidence is not installed proof.** The prior profile names
   archive `ce73846828a5cc32798ef630b5a574f777646f30` and reports a namespace-render
   exception, not successful full rendering. This brief made no JVM calls.
   **UNVERIFIED:** any proposed path is installed, fast, or complete on default.

## 2. Evidence and present data flow

### Evidence boundary

**VERIFIED:** source read on `refactor/agent-platform`, initial HEAD
`c8dc92833fc1d2259edfded3fbb18002aab7b7b2`. The shared checkout already had unrelated
edits, including `cluster.clj`, `fn.clj`, `instrument.clj`, and `test.clj`.
Citations below describe the read checkout, not an installed JVM or exclusively HEAD.
Datahike gitlink read: `c79cd03a44427ac1734d917c7484c3e529c77716`;
Malli checkout HEAD: `56394c54e34415a333d6281c4bfddf7122d02bd5` (also dirty).
`AGENTS.md`, both modeling/Clojure skills, the modeling guide and the prior cost
report were read. The turn PRD §§13–15 supplies the observation boundary; B2 §2
supplies the retained-context target. Neither is installation evidence.

**VERIFIED:** recent context history includes `c07e89393` (selection refusal
conversion), `31a5e8862` (deletion of comparison/remove-tx), and `55f2a989a` (turn
error conversion). Names/comments can describe retired machinery: the long prose
at `src/seon/context.clj:4` is not a reliable map of today's prompt implementation.

**VERIFIED:** no JVM probes, transactions, reloads, tests, provider calls or system
operations ran in this assignment. Consequently there are no new REPL forms/results
or timing/memory measurements to report. Prior forms/results are preserved in
[context-cost, reproduction section](context-cost-2026-09-23.md#reproducible-probe-forms-and-limits),
not rerun here. No web-derived claims are made.

### The two paths

**VERIFIED source flow** (arrows name callers, not measured runtime events):

```text
opening / refresh
  turn/open-turn -> system-turn
    capture db -> agent namespace
    declared-sources -> walk/neighborhood -> root-acquisition
      schema concerns + component refs + namespace connections -> render-call
      generated source blocks -> planned-sources -> ordinary Clojure forms
    latest-evaluations -> system-plan -> read-evidence-current?/changes
    selected reads -> evaluate-sources / preview-sources
    equal shown value: refresh evidence; different shown value: append evaluation

provider prompt
  turn/call-turn -> opening-db -> cluster.prompt/prompt
    settings + agent overlay + recent model calibration + render profile
    render/acquire-context! -> web/derive-context! -> walk/history
      eval/of-agent -> ordered rows -> render-call -> evaluation schema pair
      seon.repl/render-ai -> entity-emission -> text(saved shown bytes)
    select whole history units -> compose -> append repl/frame
    contribution hashes/prices + budget report -> capture-tx -> provider handoff
```

**VERIFIED anchors:** `src/seon/turn.clj:4208,4238,4446,4450,4475`;
`src/seon/render.clj:1643`; `src/seon/render/web.clj:2543`;
`src/seon/cluster/prompt.clj:418`; `src/seon/repl.clj:360,441`.
Searching `src/seon/cluster.clj` found no `prompt` occurrence: the requested prompt
pieces reside in `src/seon/cluster/prompt.clj`, not the cluster lifecycle file.

**VERIFIED query and ordering inventory:**

| Piece | Actual reads / order / rendering | Source |
|---|---|---|
| Opening root | Installed attributes and identity projection; one-past-width pulls; declared reverse concerns; namespace require names resolved by lookup plus inverse query | `src/seon/render/walk.clj:69,124,408,429` |
| Neighbourhood | Deduplicate identities; visit own member before connected members; declared concern order; reverse children choose newest eids then emit kept children ascending; not topological ordering | `src/seon/render/walk.clj:252,483,710` |
| Agent concerns | Plan, issues, messages, settings, notes, namespace, runtime, faults; identity pair on agent schema | `resources/seon/schemas/seon.agent.edn:34` |
| Latest reads | Join agent/runtime/turn/evaluation; wildcard pull including read evidence; sort turn transaction and ordinal; latest map keyed by namespace + parsed forms | `src/seon/turn.clj:1839,1907` |
| Refresh plan | Declared sources first, then prior reads by basis/ordinal; deduplicate source key; exclude declarations, transaction receipts and effects; query currency/changes | `src/seon/turn.clj:1941,1959` |
| Changed output | Compare decoded shown values; update evidence for equality; otherwise store a `:seon.repl/changes` value except issue-origin reads; commit guarded by idle/current history | `src/seon/turn.clj:2118,2141,2171,2208` |
| Prompt configuration | Cluster query, effective config and agent overlay; calibration joins attempts, runs, captures and usage, latest ten ordered by time/id | `src/seon/cluster/prompt.clj:38,45,78` |
| Saved history | Agent existence pull; evaluations joined via runtime turns; sort turn transaction, turn id, ordinal; exclude current non-generate reply turn | `src/seon/eval.clj:9`; `src/seon/render/walk.clj:927` |
| Evaluation rendering | Namespace/renderer identity pulls and changed-since query can supplement saved row; source + response + full-value handle hint | `src/seon/repl.clj:256,360,441` |
| Namespace rendering | Namespace row, functions, owned schemas, referenced schema closure (40 cap); function/schema name order; distance 0 name, 1 full, farther compact | `src/seon/render/ns.clj:56,67,83,121,171,370,576` |
| Directory/doc | Public-function query; directory doc-order then symbol; full doc and contract for a function | `src/seon/sci/eval.clj:1492,1634,1666` |
| Test rendering | Source/calls pull + recorded result; explanatory identity form and recorded-result/changed-since-green/host reads | `src/seon/render/test.clj:11,15,40` |
| Error rendering | Flat error evidence, refusal prose/message, saved shown text and occurrence count; HTML evidence links | `src/seon/error.clj:1677,1691` |
| Legacy transcript views | Messages and receipts merged by entry-order; separate candidate-history/projection; runtime emits generated pull; history AI concern returns empty string | `src/seon/render/transcript.clj:337,599,654,929,968` |

**VERIFIED:** legacy transcript renderers are not the saved-history root called by
`web/derive-context!`; that root explicitly names `seon.render.walk/history`
(`src/seon/render/web.clj:2570`). This is not a claim that transcript code has no
other callers or is all safely deletable.

### Quotes, schemas, limits and size

**VERIFIED exact source excerpts:**

```clojure
;; src/seon/eval.clj:27 — default projection
'[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
  {:seon.cluster.eval/read-evidence [*]}]

;; src/seon/turn.clj:1970 — refresh decision
(cond
  (nil? previous) :none
  (db/read-evidence-current? database evidence) :unchanged
  :else :changed)

;; src/seon/render.clj:453 — renderer precedence
(def ^:private selection-stage-order
  [:explicit-value :explicit-request :namespace :schema :floor])
```

**VERIFIED schema inventory:** `resources/seon/schemas/seon.context.edn:1` declares
selection/capture request shapes; `seon.context.contribution.edn:1` permits both
agent/evaluation refs and capture measurements; `seon.context.capture.edn:1` retains
exact prompt evidence. These paths share the preceding resource directory.
`seon.cluster.prompt.edn:1` declares text/contributions/database and explicit execution
custody. `seon.render.edn:1,22,86` covers acquired context, call request and source
blocks; `seon.render.history.edn:1` covers whole units and selection;
`seon.render.profile.edn:1` covers token/depth/child/string limits.
`seon.render.call.edn:1` declares retained read evidence; `seon.render.walk.edn:1`
declares traversal/path/bounds. The remaining `seon.render*.edn` resources describe
value/data cursors, transcript views, failures, package/feed/debug and cost shapes;
they do not supply the requested evaluation-to-example traversal algorithm.

**VERIFIED boundaries:** `src/seon/render/value.clj:585` builds a structural value
and calls `print/fit` only for AI. HTML takes the other branch. The independent
emergency floor uses fixed limits (`:38,65`); do not confuse it with the agent
profile. Acquisition limits use max-collection and max-nodes, independently of
prompt tokens (`src/seon/render/walk.clj:84,89,742`). Prompt selection counts
separators and elision, retries dropping oldest whole units, and permits the newest
to exceed budget (`src/seon/cluster/prompt.clj:228,249,297`). Frame addition and the
final budget report follow selection (`:395`). Saved history is not clipped again.

**VERIFIED physical line counts** (blank lines, comments and contracts included;
range count is inclusive, not executable LOC; overlapping rows are not additive):

| Owner / piece | Lines | Boundary |
|---|---:|---|
| context.clj entire | 416 | `src/seon/context.clj:1` |
| selection and its writers | 189 | `src/seon/context.clj:63` through 251 |
| capture pair/rows/transaction/hash | 108 | `src/seon/context.clj:309` through 416 |
| turn.clj entire / opening-refresh segment | 5,592 / 397 | `src/seon/turn.clj:1` / 1839–2235 |
| cluster.clj entire, lifecycle owner | 3,693 | `src/seon/cluster.clj:1` |
| prompt.clj entire | 452 | `src/seon/cluster/prompt.clj:1` |
| contribution pricing / select-compose helpers / acquisition+prompt | 30 / 111 / 94 | `src/seon/cluster/prompt.clj:184` / 219 / 359 |
| render.clj entire / selection / retained evidence helpers | 1,887 / 333 / 159 | `src/seon/render.clj:1` / 276–608 / 693–851 |
| walk.clj entire / neighbourhood / saved history | 975 / 108 / 49 | `src/seon/render/walk.clj:1` / 710–817 / 927–975 |
| web context derivation | 70 | `src/seon/render/web.clj:2543` through 2612 |
| namespace / test / value / transcript render files | 836 / 115 / 715 / 2,379 | `src/seon/render/ns.clj:1`, `test.clj:1`, `value.clj:1`, `transcript.clj:1` |
| direct frame string | 28 | `src/seon/repl.clj:50` through 77 |

**UNVERIFIED deletion estimate:** these are ownership surfaces, not a promise that
397 turn lines or all 2,379 transcript lines can disappear. Settlement, historical
capture and HTML inspection have independent responsibilities.

## 3. Smallest proposed composition

**UNVERIFIED proposal throughout this section.** No implementation or new stored
schema is authorized. The formula is a functional boundary, not a claim that one
Datalog statement can or should do everything:

```clojure
(render profile (query db focus))
```

`db` is one captured immutable value. `focus` is an existing task/namespace/evaluation
lookup plus explicit consumer namespace and query bounds. The query returns ordinary
maps/values and explicit incomplete results. Rendering receives its compiled projection
and renderer definitions with `profile`; neither function silently dereferences a
connection. Reuse existing declared request/unit/value/path shapes, with optional
members only if registry review proves necessary. No node-kind, context membership,
resolved-link mirror or context-generation stamp is stored.

### Focus walk and dependency order

The formula cannot make live objects durable. The inspected evaluation schema has
shown/error strings and read evidence, not a guaranteed structured link from every
evaluation to its complete refusal (`resources/seon/schemas/seon.eval.edn:6`). A
live refusal value may supply the next node; after restart this edge must come from
retained structured evidence or be reported unavailable. Complete durable coverage
of that join is **UNVERIFIED**. Never recover it by parsing the saved error sentence.

1. Resolve the focus by identity. A task contributes its declared prerequisites,
   affected symbols and namespace; a namespace contributes its declarations and
   incoming users; an evaluation contributes its saved source, handle identity and
   available error evidence. The exact task edge population needs owner verification.
2. Derive the result symbol with `seon.id/symbol-in`; do not treat a database lookup
   as recovery of the live object. The handle is a value naming an object in the
   agent's SCI world. Render its availability explicitly; after restart saved shown
   text remains an observation, not the object. Source seams are verified at
   `src/seon/id.clj:47,55` and `src/seon/repl.clj:456`.
3. From an error, use the recorded refused function symbol/check/arity and explanation
   locations. Resolve that symbol to its function row, then its declared contract
   under the held projection. Distinguish the evaluated definition from today's
   definition; use retained basis/digest evidence or label historical contract unknown.
   A current row must not be described as the old refused implementation.
4. Follow Malli **schema** paths to subschemas; value `:in` paths locate bad arguments,
   not necessarily schema positions. Use `mu/in->paths` when converting value paths,
   retaining multiple matches. Include the offending schema and named parent schemas,
   their descriptions and required-key information. Missing/capped locations remain
   explicit gaps. Do not validate a truncated displayed value as the original input.
5. Resolve fully qualified keywords and `[[qualified/symbol]]` tokens from ordinary
   descriptions/docstrings. Tokens stay values; resolution to a present entity is a
   query. Memoize extraction by the source/description content digest. Reuse the
   publication reader/link parser if another lane establishes one; its availability
   is **UNVERIFIED** here. Do not introduce production regex or evaluate docstrings.
   Unresolved library symbols remain visible names with a reason, not invented refs.
6. Query reaching tests; admit an example only after current positive green proof.
   Render the smallest enclosing source form with the test's fixture/setup context
   and proof basis. If call arguments depend on fixture locals, show those bindings
   or the test form, not a fabricated standalone invocation. Reach plus green is a
   *green test source example*, not proof of the successful execution of every call
   expression (a test may expect a refusal). A literal known-good call requires
   explicit positive result evidence; otherwise say unavailable.
7. Follow the function's observed call/reference symbols to the wrapped library's
   actual declaration and indexed source/span at a pinned revision. Do not infer
   “wraps” from namespace spelling or follow every callee recursively. Prefer a
   directly named library function; allow one extra level when explicitly documented.
   Existing call/reference values and file/span declarations are verified at
   `resources/seon/schemas/seon.fn.edn:20,21,64`; complete reference-code indexing
   and this exact end-to-end join are **UNVERIFIED**.

Use a visited identity set and bounded frontier in the existing walk owner. Query
membership and explanatory prerequisites are separate: a link makes a candidate,
not an obligation to include its whole namespace. Place a short focus/error summary
first, then prerequisite schema descriptions/library contract, refused function,
example and optional callers. Within that explanation, emit prerequisites before
uses; preserve evaluation history chronology separately. Stable qualified identities
break ties, never file position. Cycles emit each member once with explicit cyclic
links; they do not stall the entire context or fabricate a topological order.

Initial policy for review: at most 32 explanatory nodes, 64 followed edges, depth 6,
one green example and one library source excerpt per refused function. A separate
query deadline uses the existing database bound; profile tokens govern rendering.
On exhaustion return bound, path, visited count, known omitted count (or unknown),
and an ordinary requery form. A sixth edge is not silently omitted because depth
ended. These numbers are provisional, not measured defaults.

### Renderer ownership and historical observations

Recommended precedence: explicit caller-selected renderer, then the consumer
namespace's unambiguous compatible override, then the entity schema's AI/HTML pair,
then attribute-map printer. The schema owner's interpretation is inherited through
its declared pair; do not search transitively through namespace requires for another
winner. Multiple compatible overrides produce a declared diagnostic. Compose error
base and satisfied error facets without a stored discriminator.

This changes today's explicit-value-first rule and needs the decision below.
Keep one pair per entity schema; optional consumer policy selects another pair for
that same concern rather than adding pairs per scalar. An attribute-only request
remains an attribute-only value. Pass consumer and subject ownership distinctly;
the current neighbourhood passes the acquired owner as `:seon.render/namespace`
(`src/seon/render/walk.clj:774`), so changing only selection would not implement
consumer preference correctly.

The new query is an ordinary generated read. Its newly rendered observations enter
history through the existing evaluation/settlement owner. Old shown bytes retain
their original renderer/profile; new renderer definitions affect new observations.
Frame facts become an ordinary rendered value in final composition. Keep exact
capture and provider handoff. Do not store a second context graph or generated docs
beside ns/fn docstrings and schema descriptions.

### Incrementality, costs and retirement

**VERIFIED baseline:** context-cost §B measured 177 contributions / 681,255 characters;
unrelated write 4,067.868 ms; one shown-text change 8,241.552 ms; warm 8.832–9.718 ms.
A separate 7.425-ms warm sample allocated 40,422,368 bytes on the REPL thread,
not retained heap. This brief verifies the report, not those measurements anew.
The source mechanism is `:all` → commit revision (`src/seon/db.clj:875`) → replay
and result digest when narrow evidence cannot certify currency (`:1139`).

**UNVERIFIED proposed keys and work:**

| Derived value | Immutable inputs / recomputation event | Intended cost |
|---|---|---|
| Focus membership | Focus + policy + reached relation values, including empty-result predicates | Initial bounded indexed walk; update changed membership and reachable closure |
| Schema/path explanation | Contract digest + referenced schema definitions + explanation locations | Compile via Malli once per changed contract/projection slice; walk reached paths |
| Entity block | Selected row values + renderer and reached helper digests + schema projection inputs + profile + consumer policy | Only changed blocks; no hashing all rows to discover one changed row |
| History unit | Saved source/shown/output/error + namespace/renderer/changed-since inputs actually used | Reuse unchanged bytes/hash/character count; update append/retraction/changed unit |
| Selection and prompt | Ordered block identities/sizes + budget + calibration + frame | Same inputs return retained result; changed suffix updates selection; serialization O(output bytes) |

A same-commit lookup is a cheap exact-basis hit. A different commit is not proof
that these inputs changed. Route matched entity/attribute/value and membership
changes through the existing Flow router design; reacquire only affected input
slices, then compare their content before rendering. Include absent-query interests,
ref removals/reassignments, renderer definitions/helpers, namespace override additions,
schema changes, test proof/input changes and calibration. Exclude nested historical
read-evidence from presentation; retain it with the read-refresh owner.

This removes the **4,068-ms path** by doing no history replay/hash after a genuinely
unrelated write, and the **8,242-ms path** by rebuilding one changed unit and its
actual dependents without recapturing the whole aggregate twice. Merely narrowing
`[*]` is insufficient if an aggregate is still re-read/hashed on each evaluation.
Production history appends; the profile's edited shown-text row was an invalidation
experiment, not a recommended mutation. Small deltas target milliseconds, unchanged
lookup <1 ms; these are **UNVERIFIED targets**, not benchmark results.

**VERIFIED dependency limits:** current Datahike commit identity is provided at
`reference-code/datahike/src/datahike/versioning.cljc:461`; listener registration at
`reference-code/datahike/src/datahike/core.cljc:200`. At fetched upstream `7f39cccc`,
`reference-code/datahike/src/datahike/dependency_tracking.cljc:78,93,122` provides
runtime dependency tokens, with 16 groups/256 terms (`:9`) and attribute/namespace
selectors (`:26`), not eid/value selectors. That file is absent at the current
checkout gitlink. These are distinct revisions, not installed interchangeable APIs.
Malli caches explainers on compiled schemas (`reference-code/malli/src/malli/core.cljc:2647`).

**UNVERIFIED proposal:** use upstream tokens only for broad schema/program policy
if that dependency is adopted, not one group per agent/entity. Exact matching and
recovery belong to the existing router/database owners; no new listener, scheduler
or invalidation registry. The ruled router target is recorded at
`docs/prds/agent-platform/plan/lane-flow-owns-running-machinery.md:587`.
History gaps/no-history/restart mean affected-domain reacquisition with explicit
unknown, not a cache hit. Cache pure derived values using dependency caches or the
existing Clojure cache owner, never mutate DB metadata or attach writer stamps.

**UNVERIFIED estimated implementation scope:** a first useful composition over
existing resolvable edges should add at most 80–100 lines in existing query/render
owners. Complete error-path/example/link support and incremental adoption likely
needs 200–350 changed/added lines total across those owners; this is **not** an
approved >100-line implementation. Review/de-scope before writing. No new subsystem.
Replace the context-specific root validation in `web/derive-context!` (70 lines),
its retained-evidence helpers where no other caller remains (159-line surface),
duplicate pricing/composition work (30-line pricing surface), and the frame's
query-plus-string boundary (28 lines). A conservative retirement target is 200–400
lines after caller conversion; broader B2 transcript/namespace deletion is separate.
Do not delete read evidence for arbitrary agent reads, settlement, capture, replay
inspection or the value renderer to meet a line estimate.

## 4. Owner decisions and proof boundary

**UNVERIFIED options and effort estimates; recommendations are design judgments.**

| Decision | Options, guarantee and sacrifice | Recommendation |
|---|---|---|
| One query means what? | A: one pure query function composing bounded indexed reads (≤100-line initial composition; no single-query slogan guarantee). B: one recursive wildcard pull (small wrapper; incomplete explanatory/value edges and aggregate cost). C: new declarative query language (days; parser/registry/maintenance). | A |
| Namespace overrides | A: schema default, explicit caller override only (hours; predictable but less automatic). B: explicit caller → consumer namespace compatible override → schema pair → printer (about a day including ambiguity proof; must change owner propagation). C: transitive namespace inheritance (several days; unclear conflicts). | B, after owner confirms explicit-value precedence retirement |
| Known-good example | A: green reaching test source, honestly labelled; unavailable literal call stays unavailable (hours; no observed argument promise). B: explicit successful-call evidence from test owner (1–2 days plus storage/privacy/bounds review; stronger guarantee). C: generate then execute a candidate (variable cost and effects; outside read-only assembly). | A now; B only if successful arguments are required |
| Documentation links | A: follow existing structural symbol/schema edges first (hours; prose-only links unresolved). B: reuse a verified publication parser for wikilinks/qualified keyword tokens, memoized by content (1–2 days after seam verification; source tokens remain values). C: parse prose at every render (small initial patch; repeated work). | A first, B as the link-owner addition |

**UNVERIFIED required implementation proofs:** same-basis and unrelated-write requests
invoke zero aggregate-history replay/hash; one append and one changed child recompute
only affected blocks. Test renderer/helper/profile/calibration changes, empty→nonempty
queries, retractions, ref moves, cycles, unresolved links, stale green, missing handles,
retention gaps and exact capture reconstruction. Compare 10 and 200 retained evaluations
with the same delta; report query/render/hash counts, serialized bytes, elapsed time
and allocated bytes separately. Full namespace rendering must first pass its previously
reported failing boundary. No result in this brief claims these gates passed.

**VERIFIED landing scope:** only this Markdown deliverable is authored here; source
and test line changes are zero. Implementation freeze and the no-JVM instruction
were preserved. The existing context-cost defect remains open; this brief does not
edit its issue, schedule, skills or another lane's files.
