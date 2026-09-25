---
type: design
status: "Opus revision 2 after Astra review ae7cfa793 (implement after fixes); all seven P1s and the ledger P1/P2s integrated in place; awaiting Astra re-review"
created: 2026-09-25
scope: Git archaeology of every query + render generation, and the one building block that replaces them
source-pin: 48ceb9cad (src ranges valid there; render.clj, render/walk.clj, render/data.clj and db.clj byte-identical at 19e9fe5f4)
aligned-with: language-requirements-2026-09-24.md (19e9fe5f4) §§1.9, 1.13, 11, 12; function-face-and-canonical-authoring-2026-09-25.md (52e252f74) §1; one-render-path-design-2026-09-25.md §3; floor-shell-order-review-2026-09-25.md
---

# Query + render: one building block

Today four owners each do one part. The database owner executes reads (`seon.db/q`, `pull`, `pull-many`,
`index-page`). The walk acquires an entity's concerns (`walk.clj:69-708`). `render-call` selects and retains one
face (`render.clj:1496-1678`). The floor prepares values (`render.value/prepare`). Around them, each consumer
writes its own "acquire, loop, render" (§4).

The principal Clojure approach is to join those owners in place, behind one public entrance, `seon.render/units`:

- acquire a declared source **once**, with its read evidence;
- render the reached units under **one request budget**, with the existing per-face selection and call-preparation
  defaults;
- return units whose continuations name their source.

Domain acquisition stays with its owners: walk, `seon.eval/of-agent`, bootstrap membership and the transcript's
domain analysis. Ordinary REPL settlement stays the REPL's. No new registry, cache, result store, selector, printer,
acquisition DSL or second read interpreter is added.

## 0. Broken things first

1. **The installed JVM did not run HEAD's selection.** On 2026-09-25 I ran a read-only probe on default (PID 35446,
   started `2026-09-25T14:24:44Z`, health `faulted`, open fault "JVM RESTART NEEDED: loaded dependencies changed").
   `@#'seon.render/selection-stage-order` answered **`[:declared :floor]`**. HEAD has
   `[:explicit-value :namespace :declared :floor]` (`render.clj:532-533`). The owner-ruled precedence
   (634d8aaa2, aa91ecb34) and the database-as-declared-input change (b195ae037) were committed but not installed.
   **RESTART NEEDED (root)**, then verification of the loaded order and defaults. The reviewer did not re-observe
   this; it is the author's evidence. No stage behaviour below is installed evidence.
2. **Query + render has been rebuilt about ten times** (§1). The lesson is narrower than "each generation was
   destroyed". Several generations were cut with a whole tree: the archive 6c1079c8d, the split f25e34594 and the
   quarry deletion 099cdfa99. Others converged in place: the blocks' "funeral" 7ba92be65 and the walk's evolution
   since 1a499558a. The surviving block and walk code show that **the explicit source and the recursion context can
   be kept through caller conversions**. This design makes that retention a proved property of each slice.
3. **Declared-concern producers do their own graph work in the AI face.** The steward faults concern is the example:
   - `faults-form` (`error.clj:1943-1966`) builds a read form, and `render-faults-ai` (`:1968-1979`) prints it as
     source;
   - `render-faults-html` (`:1981-2004`) runs a different acquisition, `for-agent` (`:1925-1941`), with
     `observation-selector` (`:1643-1672`).
   The 2026-09-24 capture found this read reprinted five times: 549 KB, 81% of a 680,643-character prompt
   ([agent-context-render-pipeline](agent-context-render-pipeline-2026-09-24.md) §0.1). "No graph work in an AI
   renderer" is already ruled ([one-repl-language](one-repl-language-2026-09-25.md) §5).
4. **The agent-facing walk is a second entrance with ambient custody.** `seon.render/walk` (`render.clj:1876-1976`)
   reads custody from the dynamic Var `*walk-context*` (`:1812`). That Var is bound by `call-with-walk-context`
   (`:1814-1823`), called from `turn.clj:4715`. The walk then `pr-str`s its units. AGENTS.md "Pass the world
   explicitly" forbids this.
5. **A banned placeholder is painted only when a failure has no owner.** `neighborhood` calls `renderer-failure`
   only when it found an owning namespace (`walk.clj:777-781`). Otherwise it paints "renderer unavailable"
   (`:792-798`). Existing issue: `docs/seon/issues/namespace-page-repeats-renderer-unavailable.md`.
6. **There is no public declared-read entrance, and the private executor captures no evidence.**
   - `seon.db/replay-read` (`db.clj:962-1026`) dispatches over `:q`, `:pull`, `:pull-many` and `:index-page` through
     `d/q-with-evidence`, `pull-plan-with-evidence` and `d/index-page`. It **discards their dependency evidence**:
     it never calls `append-query-evidence!` or `append-pull-evidence!`, and its only caller is read-currency
     comparison (`:1166`).
   - The public owners do the capture and checks: `q` (`:2365`, evidence at `:2416`), `pull` (`:2588`, pull-call
     evidence at `:2568-2576`), `pull-many` (`:2636`) and `index-page` (`:2818`).
   - Render-side queries are hand-written instead: 36 `db/q`/`db/pull` sites in `render/transcript.clj`, 19 in
     `bootstrap.clj`, 18 in `error.clj`, 15 each in `render/web.clj` and `my/program.clj`, and 11 in `problems.clj`.
7. **Nested values do not get the full ladder.** `project-node*` chooses only explicit or declared producers
   (`render.clj:1226-1229`), with no namespace stage. Requirement 11.1 records "nested open". `namespace-stage` fits
   `[argument]` before call preparation supplies defaults (`:617-637`). A renderer whose second positional input is a
   database is therefore rejected before its default exists.

## Evidence and limits

**Read end to end:**

- `AGENTS.md` and the lane preamble;
- [duplicate-paths audit](duplicate-paths-audit-2026-09-25.md) §§1-3, 7, 10-11;
- [one-render-path](one-render-path-design-2026-09-25.md) §§1-6 and the
  [floor-shell-order review](floor-shell-order-review-2026-09-25.md);
- the render-dispatch landing, twelfth follow-up;
- [one-repl-language](one-repl-language-2026-09-25.md) §§2.4, 4.0, 5-6;
- the [language requirements](language-requirements-2026-09-24.md) (19e9fe5f4);
- the [function face](function-face-and-canonical-authoring-2026-09-25.md) (52e252f74) §1;
- the [review of this design](query-render-building-block-review-2026-09-25.md) (ae7cfa793).

**Reused, not redone:** `docs/prds/context-generation/research/render-archaeology-2026-08-14.md`.

**Archaeology method** (14,652 commits):

- deleted-file history filtered for render, context, query, walk, block, page, prompt and view;
- commit-message search (589 matches);
- `git log -S` for `find-renderer` and `renderer-resolution`;
- every `docs/` PRD or research path with those words.

Sizes come from `git show <deleting-commit>^:<path> | wc -l`. A definition's size runs from its top-level `(def…` to
its last non-comment, non-blank line (the audit's §2 rule).

**Live probe** (JVM mode, explicit root and cluster, read-only, one finite form):

- `(#'seon.db/replay-read database {:seon.db/read-operation :q …})` returned `1` in 0.127 ms.
- A pull request answered `#:seon.agent{:id "root", :namespace #:db{:id 42404}}` in 0.110 ms. The ref leaf is bare
  unless the selector asks for identities.
- The supplied-default rows are `[:my.program/context :seon.agent/id :seon.db/connection :seon.db/db]`.
- The stage order was `[:declared :floor]`.

Those timings price two tiny reads. They do **not** price a page, a query tree or an artifact parse.

No code, test, `bin/seon` or JVM start was run. Memory, allocation and end-to-end timings are unmeasured. Every
"+N" is an estimate.

## 1. Archaeology: every generation of query + render

Each row is one of three kinds: an **executable entry** (the function consumers called), a **composition helper**
(used inside an entry of the same generation), or a **proposal** (a design, never installed as an entrance).

| Gen | Kind | Commit range | Files (lines) | Entry | Right idea | How it ended | Survives |
|---|---|---|---|---|---|---|---|
| **G0 namespace UI** | executable | e041ef5c6 (2026-01-23) → 6c1079c8d (2026-07-13, tree archive, −54,229) | `seon.ns.view` 552, `seon.ui.viewer` 269, `render/default_page` 246, `render/code` 347, `render/example` 163 | `ns.view/render-value`, a multimethod on `[format view-type]` (`e041ef5c6:src/seon/ns/view.clj:82-119`) | Three levels: value metadata `:seon/view` → Malli schema property → default; faces per format | Archived with the JVM application | No code. The value-override idea lives in level 1 today |
| **G0b** | composition helper (selector) | 6e013a325 (2026-02-19) → archive | `render.clj` +56 | `find-renderer [conn data format]` | Find a renderer from its declared input keys | Ranked by key specificity, then recency, then name. That is neither full contract validation nor viewer-namespace selection | The idea recurs in `namespace-stage` (`render.clj:617-645`), without the ranking |
| **G0c** | executable | 37bc72b00 (2026-02-19) → archive | `graph/context` 392, `graph/query` 491, `repl/context` 121 | `graph.context/build` over conn, seed, depth (`:302-327`) | **Context = seed → recursive pull → topological order → per-entity text** | Archived | Walk acquisition descends from it |
| **G0d** | proposal | 552db0ffc, c5bc21291 (2026-02) | `refinement/render-pipeline.md`, `renderer-resolution.md` | — | "Agents declare what they need by input spec; the system injects it" | Relied on `*ctx*`/`*conn*` dynamic Vars | Requirement 1.13; `call-preparation/hook` |
| **G1 pod** | executable | 5a9255de8 (2026-05-19) → f25e34594 (split) → 099cdfa99 (quarry deleted) | quarry `9e44815f5`: `render.cljc` 1,132, `agent/ctx.cljc` 1,959, `ctx/transcript.cljc` 1,612 | `render [view ctx node]` + `resolve-render` (`render.cljc:1058-1132`) | Value slot (string/hiccup terminal) → symbol → schema default → generic. **One unwrap** (`:312-325`). **Recursion handle** `:seon.render/render` (`:1107-1108`). Record, then panic, then face | Whole pod deleted. It had no viewer-namespace stage, and `missing-render` (`:1047-1056`) returns nil for HTML | Level 1 and the per-face idea. The recursion handle is restored here as a declared composition (§3d) |
| **G1b** | proposal | 68bc63dfc, 6c93677b1 (2026-06-24/25) | `context-render.md`, `single-render-path-design` | `seon.ctx/render-context` (never landed as specified) | "Three render systems must collapse into one, not add a third"; derived, never stored; byte identity between prompt and inspector | Superseded by the JVM host | The acceptance test of this design |
| **G2 JVM host** | executable | cd239b795 → 8dc8623ad (2026-07-20/26) | `host/context.clj` 2,184, JVM `render/value.cljc` 281 | `execution.runtime/render-prompt!`, `render-agent-view!` | **One held database value through every nested selected function** | Execution child deleted | b195ae037's environment scoping (`render.clj:1082-1091`) |
| **G3 fresh blocks** | executable | 6dcda1ab9, f2a6ec0f2 (2026-07-27) → converged | `block.clj` 347 → 96, `render/root.clj` 217 (deleted 4a65f9c7a), `data.clj` 235, `render/agent.clj` 179 → 24 | block derivation; `/data` drill | **The block is the morph target**; an explicit held database; a cursor window | **Converged in place**: 7ba92be65 moved two prompt blocks into run rendering | `surface-id` (`block.clj:61-96`), `data/at`, `parse-cursor` |
| **G4 walk** | executable | 1a499558a (2026-07-28) → now | `walk.clj` 461 → 974 | first nested node/neighbour shapes calling `render/render` (`1a499558a:walk.clj:328-410`); now flat `render-call` units | Bounded hops, nodes and per-path cycles; a held database; totality | Evolved in place. The 23 s measurement (6f309c6d3) and the outage (965bce048) were fixed, not replaced | **Live**: `neighborhood` `walk.clj:710-807`, `history` `:926-974` |
| **G5** | proposals | 2026-08-13 → 2026-09-09 | `one-renderer-prd` 393, `context-pipeline-ideal` 113, `render-data-plan` 138, `context-as-queries-handoff` 265, `agent-centric-design` 651 | — | **Mechanical acquisition, one render target per attribute group**; face levels with loud ties; **form honesty**: an entry is a call the agent could make, and its print equals the AI bytes | Code grew concern by concern instead: `declared-acquisition`, and 26 source files returning `:seon.render/source` read forms | The `:seon.render/units` property (three schemas) |
| **G6** | executable + proposals | d0b0c7a62 (2026-09-23) → now | `render.clj` `selection`/`render-call`, `render.value/prepare`; the G6 designs | `render-call` | Per-face first match (owner); database as a declared input (b195ae037); one budget; one retrieval entrance per held source (floor review P1-2) | — | Base of this design |

**Counting.** Eight executable entries (G0, G0c, G1, G2, G3, G4, G6, plus the agent-facing `seon.render/walk`), one
selector helper (G0b) and three proposal families. The owner's "about ten times" holds. They are not ten identical
four-stage functions: the viewer-namespace stage exists only since 2026-09-24.

## 2. Best ideas taken, by name

| Idea | Source | Where it lands |
|---|---|---|
| Value override → viewer's contract-fitting function → schema pair → floor; ties loud | Owner ruling 2026-09-25 (req 11.1); value override from G0/G1; contract-fit discovery from G0b, without ranking | `seon.render/selection`, extended to nested values (§3c) |
| Inputs declared by contract and supplied by schema name; caller wins | G0d; req 1.13 | `call-preparation/hook`; the held database scoped by b195ae037 |
| Context = seed + declared acquisition, rendered | G0c, G4 | Walk stays the entity acquisition owner. `units` renders its composition (§3a) |
| One render target per attribute group | G5 ideal | A concern is one unit, never one unit per row (§3b) |
| Ids plus the call that expands them, never the expansion by default | Req 1.9; function face §1.2 | Child declared queries render as **handles** by default (§3d) |
| Form honesty | G5 one-renderer; one-REPL §5 | A query descriptor derives its form **without executing**. The REPL executes it once (§3b) |
| Recursion handle, one unwrap | G1 quarry | A declared render composition, under the same budget and producer chain (§3d) |
| The block as morph target, with an occurrence address | G3 | Occurrence id = parent occurrence + path (§3a) |
| One held database through nested renderers | G2 | b195ae037 (not installed; §0.1) |
| Retained reuse by read evidence | G4 `render-call` | Extended to source descriptor, acquisition evidence and child membership (§3a) |
| One retrieval entrance by held source | G6 floor review P1-2 | Source references resolved inside `units` (§3e) |

Correctly left dead: stored block rows and profiles, per-block token caps, the seven value dials, a strict dial
defaulting off, specificity/recency ranking, and a callable renderer registry.

## 3. The building block

### 3a. Data shape: source → units → rendered units

**Sources.** Exactly one source key must be present. Presence is decided by `find`, so a supplied `nil` value is a
real value (AGENTS "Preserve native values").

| Source key | Meaning | Custody it requires |
|---|---|---|
| `:seon.render/value` | Any value, including nil and false | none for the floor-only path. Schema/namespace stages need a projection and SCI context when present |
| `:seon.render/query` | A `:seon.db/read-request` (`seon.db.edn:94-104`) as data. Executed only by `seon.db/read` (§3f) | `:seon.db/db` |
| `:seon.render.walk/units` | An **already-acquired, ordered composition** of unit requests from a domain owner: walk entity acquisition, `walk/history` over `of-agent`, bootstrap members | whatever the members name |
| `:seon.render/source-ref` | A retained reference for a continuation: a live result handle, an admitted artifact digest, or a query tied to its branch lineage (§3e) | the reference's own custody |

A request with none or with more than one source is refused. The refusal is the declared
`:seon.render/request-error` naming the member, the expected "exactly one source" and the keys present. The request
schema is a Malli `:multi` in `seon.render.edn`. It dispatches on a registered named function
`seon.render/source-kind`, which returns the present source key or `::none`/`::both`. There are no branches for
`::none` and `::both`, so Malli's explainer reports the refusal
(`reference-code/malli/src/malli/core.cljc:2637,2653`). There is no anonymous predicate and no second registry.
**Value-only requests** keep database and SCI context optional, which serves boot diagnostics, status and fault
cases (req 12.1). Database-backed branches require `:seon.db/db`. The existing `:seon.render/call-request`
(`seon.render.edn:21-35`) currently requires value, database and context; it becomes the value branch's specialisation.

**Unit identity.** The **occurrence** id is the parent occurrence plus the structural path. It is the morph address
(`block/surface-id`) and the retention key. Source contents are **not** part of the id. They join the
**cache-validity evidence** (§3f), so two occurrences of one source keep separate DOM addresses and share valid
acquisition evidence. Anonymous values and scalars get occurrences without an invented entity lookup. The current
neighbourhood loop computes a call id and drops it from the returned unit (`walk.clj:765-802`); `units` returns it.

**Result.** `[:seon.render/rendered-unit …]`, a named open-map schema over existing members:

- **required:** `:seon.render.call/id` (occurrence), `:seon.render.walk/path`, `:seon.render/output` (the requested
  face's bytes, or the derived form for `:seon.render/form`);
- **optional:** `:seon.render.walk/lookup` when the occurrence is an entity; `:seon.render/source` (the source
  descriptor: value provenance, query or reference); `:seon.render.value/tree` (the selected structure with its cut
  nodes, from `prepare`); `:seon.error/value`; `:seon.db/tx-data` (failure-message data for the calling boundary to
  commit, §3g).

The tree is what lets AI and HTML share structural decisions and lets both adapters continue one cut (req 11.3). A
per-face declared renderer remains a legitimate per-face choice. Result values stay values until the edge
(req 12.1). An error unit carries the error value itself, never a summary of it (req 12.2).

### 3b. Cardinality and the two lifecycles

**One source, one root unit.** A scalar `:q`, a relation, a `pull-many` vector and a concern's vector of faults are
each **one value**, rendered as one unit. Collection shape never splits a result into units.

Child units exist only at **declared boundaries**:

- an entity schema's `:seon.render/units` concerns and owned components, as walk acquisition assembles them today;
- a declared render composition (§3d);
- a domain owner's ordered composition, passed as the `:seon.render.walk/units` source.

Prompt history is therefore a composition of evaluation unit requests. `walk/history`'s acquisition half keeps
three things of `of-agent`'s semantics (`eval.clj:9-63`): absent agent is distinct from empty history, the order is
by turn transaction, turn id and ordinal, and each evaluation's `:t` is attached. It also keeps its own rules:
active-reply-turn exclusion, generated evaluations, and per-evaluation subject and basis (`walk.clj:926-974`). None
of this is re-spelled as query literals.

**Render lifecycle.** A value, composition or reference goes to `units`, which returns faces. A query source is
executed by `units` only when a **face** of its result is requested. Examples are a page concern and a function face's
row pull (function face §1.1).

**Evaluation lifecycle** (the opening and any agent-visible read):

1. The descriptor becomes a form, **without executing**: `units` with face `:seon.render/form` returns the form
   derived from the read request (`seon.db/read-form`, printed by `repl/source-text`).
2. The ordinary REPL reads, evaluates, records and binds `result/e…`. That is the read's **single** execution.
3. The actual result goes back through `units` as a `:seon.render/value` source. The descriptor rides along as its
   `:seon.render/source` provenance.

Rendering never pre-executes an opening read and never records an evaluation. Bootstrap's `:seon.render/form`
consumers keep receiving forms (§5 S2).

**Saved history bypasses the floor.** A unit whose face is saved shown text (`:seon.render.history/bytes`) is
returned as is. It is not charged to B and is never re-clipped. `prompt/select` admits it whole (design §5).
Expanding a historical unit later is a new, bounded request.

### 3c. Selection and defaults: the same owners, completed

- **The order is unchanged.** Per face, first match wins (req 11.1; `render.clj:532-533`): (1) the value's own
  `:seon.render/ai`/`:seon.render/html` key, which may be a symbol, an inline function or data; (2) a
  contract-fitting function in the **viewing** agent's namespace; (3) the schema's declared pair; (4) the floor.
- **Nested values get the same ladder.** A reached nested value is selected by the same `selection` call, with the
  producers already rendering it excluded (the existing `:seon.render/rendering` chain). This closes "nested open"
  (req 11.1) and is the same cut as audit **R2**, which retires `project-node*`'s own two-stage chooser
  (`render.clj:1226-1229`). The line credit stays with R2.
- **Candidate fit uses the preparation plan, not the bare argument.** `namespace-stage` (`:617-637`) must fit a
  candidate against the argument **as call preparation would complete it**. It reads the callee's retained plan:
  supplied map entries and positional placements by schema name (req 1.13; audit C3's retained-contract planning).
  Fitting never executes a supplier or a renderer, and caller arguments win. Until C3 lands, a renderer that
  declares a positional database input is not a namespace-stage candidate. That limitation is stated, not hidden.
- **Inline functions.** A value key holding a function value is **invoked**, never displayed as an object:
  `sci.kernel/invoke` with `:seon.sci.eval/function` (6d25d9483), through the same arm, admission and failure path.
  A schema-less inline function receives only its value. A contracted inline function receives defaults only after
  call preparation's closure gap is fixed (`call_preparation.clj:1535-1542`; function face §5). Until then it also
  receives only its value, and the unit records that no defaults were supplied.
- **Output handling admits exactly one more declared shape.** `raw-output` (`render.clj:1316-1376`) accepts final
  text, Hiccup or form today. It gains one alternative: a declared `:seon.render/composition` (§3d). Any other
  returned data goes to the floor under the current allocation, as design §2 rules. An inspected map that merely
  contains `:seon.render/query` is **data**; it never becomes a database operation.

### 3d. Children, handles and the one budget

**One budget per request.** `units` opens **one** floor allocation from the request's profile. It is the character
budget B of one-render-path §3b: converted once, with the legend reserved once. Every rendered unit and descendant
of that request spends from its remaining allowance: visible content, handles, references, diagnostics, layout and
legend. No child starts a fresh profile, so a parent with k query children spends at most B, not (k+1)B (req 11.4).
The allocation is threaded as an explicit argument through the unit loop. There is no dynamic Var and no atom beside
a connection.

**Children render as handles by default** (req 1.9; function face §1.2). A declared child query's unit shows its id
and the call that expands it, for example `;;   callers 15   (my.program/callers {…})`. The count appears only when
the declaration names a count read, and that read is charged as query work. The child's result is executed and
rendered inline only when all three conditions hold:

1. its declaration marks it for expansion;
2. the request's distance still has a hop for it;
3. the remaining allowance admits at least its handle.

A handle that cannot be paid closes the parent's child list with one cut. The cut names the count of remaining
children and their ids as a continuation, like any other collection cut. Only **reached and admitted** children
execute. Nothing is queried eagerly before selection.

**A declared render composition is the recursion handle, as data.** A renderer may return a `:seon.render/composition`:
a named schema carrying `:seon.render/value` (the smaller value to render) and optionally declared children as
`:seon.render/query` descriptors or values. `units` renders the composition's value through the same selection. Its
children become child units under the same allocation and the same producer-exclusion chain. This is the only place
a renderer's output can introduce a query. Option C of §7 is therefore limited to explicitly declared
compositions.

**Work bounds and termination are separate from B.**

- Child execution per request is capped by the existing `:seon.config.eval.result/max-nodes` cap (as `neighborhood`
  uses it today, `walk.clj:733-734`). Distance decrements per hop.
- A child whose source descriptor equals an ancestor's is refused by name as a cycle. The rendering chain carries
  source descriptors as well as producers.
- Every read keeps its own `:max-work`, `:max-results` and `:max-result-weight` bounds and its deadline. Exhaustion
  yields a named refusal or continuation, never a silent stop.

### 3e. Continuations: one entrance, resolved from a held source

Every cut's coordinates name the unit's **source reference**, the selected producer and its program evidence, plus a
typed path and absolute offset into the **represented** value. That is the value the floor actually held, which may
be a renderer's smaller value. It is not the raw query result. `get_value` and `/data` only decode coordinates and
encode the common result. Source resolution, navigation and preparation all happen inside `units` (floor review
P1-2).

| Source reference | Valid while | Resolution |
|---|---|---|
| live result `result/e…` | the SCI context retains it (`:seon.sci.eval/result-objects`, `sci/eval.clj:620`; `repl/live-response` `repl.clj:465-480`) | the live object, reselected |
| admitted artifact digest | the blob is retained (existing MCP artifacts only; **no new historical blob writes**) | `read-artifact`, reselected |
| query + branch + basis | the branch is retained and its history reaches the basis. `as-of` answers only over the retained origin database (`reference-code/datahike/src/datahike/api/impl.cljc:153-183`), and GC follows reachable roots (`gc.cljc:22-80`) | `seon.db/read` on the branch's database `as-of` the basis |

A transformed output is continued only if the recorded producer, program identity and projection evidence equal the
current ones. Otherwise the result is typed. One new error schema, `:seon.render/continuation-error` over
`:seon.error/base`, carries `:seon.render.continuation/reason` from
`[:enum :stale :gone :unavailable :malformed]`, naming the member and the offending coordinate. Saved historical
shown text is never reconstructed by current renderers. "Not retained" is an honest terminal answer, never a
reason to re-evaluate.

**Navigation prerequisites (floor review, carried, not re-solved):**

- `render.data/at` keeps missing versus nil, but its raw `contains?`/`get`/`nth` (`data.clj:54-68`) must give way to
  typed structural steps (map-entry key and value roles, held-order set ordinals);
- `parse-cursor` (`:27-44`) must refuse malformed coordinates instead of rooting them;
- offsets are absolute and strictly progressing; empty and beyond-end are distinct results; current and historical
  labels are stated.

A browser-supplied query continuation is a **checked reference** to a query the server held: branch, basis and
descriptor digest. It is never executable source supplied by the client.

### 3f. The entry function, its reads and its reuse

```clojure
(seon.render/units request) ;=> [:seon.render/rendered-unit …] | :seon.render/request-error
;; request: one source (§3a); :seon.render/output :seon.render/ai | :seon.render/html | :seon.render/form;
;; :seon.render.call/id (parent occurrence, optional at the root); :seon.render/distance (default 0);
;; explicit custody: :seon.db/db, :seon.sci.eval/ctx, :seon.agent/id (viewer), caps, time limit,
;; :seon.config/on-core-error, :seon.render/profile (one B), retained/captured calls;
;; optional :seon.eval/shown-subjects, the known set K the function face needs (function face §1.3), passed as
;; an explicit input into the unit argument of renderers that declare it.
```

**The declared-read executor lives in `seon.db`.** `seon.db/read [database read-request]` is public and new. It
dispatches `:q` → `q`, `:pull` → `pull`, `:pull-many` → `pull-many` and `:index-page` → `index-page`, with explicit
database arguments. The public owners' validation, decoding, refusals and evidence capture therefore apply unchanged.
Nothing is copied into render. The `:seon.db/database` placeholder keeps its current meaning. A malformed request is
refused by `read-request`'s own schema at that boundary.

`replay-read` becomes a thin delegate to `read`. Currency comparison stays capture-neutral by calling it with no
evidence sink bound (`append-read-evidence!` is a no-op without a sink, `db.clj:495`). Its legacy mistyped-evidence
check on `:q` stays (`:984-986`).

**Subject substitution, exactly.** A declared concern's query names the parent as `:seon.render/subject`, only in:

- the `:args` vector of `:q`, at positions bound by `:in`;
- the eid position (second element) of `:pull`/`:pull-many` `:seon.db/pull-arguments`.

The substituted value is the parent's **lookup** (`[attr value]`). A query that needs a scalar binds it with `:in`
and destructures in the query. Q3 changes `agent-faults-query`'s `?id` input into a lookup binding `?agent`, so the
lookup is not flattened by a heuristic. `:index-page` options take no substitution. A missing parent subject is
refused by name.

The function face's example writes `:seon.db/pull-arguments` as a map; the existing schema is a vector
`[selector eid]`. The face's owner adopts the vector.

**Reuse.** `render-call`'s retention entry is extended, not duplicated. It now also holds the **resolved source
descriptor**, the source's **acquisition read evidence** and, for compositions, the **membership evidence** of the
owner's read. That last item lets an appended history row invalidate the composition, not only its children. The
query read happens **before** the reuse check, inside the same capture. Today `render-call` creates its sink after
selection (`render.clj:1511-1538` versus `:1586`); moving it before is a control-flow change priced in S2.

A unit is reused only when all of these agree: source descriptor, program and selection inputs (existing
`call-cache-evidence`), and read evidence. Required witnesses:

- an empty query is invalidated by an insertion;
- a relevant update or retraction invalidates;
- an unrelated write reuses;
- a changed template at the same occurrence cannot reuse;
- nil and false results stay values.

**Cost.** Cost is the source's read work (bounded by its own query bounds), plus per reached unit one selection
(candidate fitting is proportional to the viewer namespace's public functions and to declared alternatives), plus
one invocation, plus floor work O(V log V) over admitted nodes plus opened-collection ordering (one-render-path §3b
and floor review P1-6). Cache hits cost evidence comparison only. These are call counts, not constant bounds.
Parent-versus-change latency, memory, reached units and reads, and hit/miss counts are measured per slice.

### 3g. Failure

- A declared read refusal is a **value**. It renders through the error pair (req 12.2).
- A renderer that **returned** an error value is distinct from one that **did not return** (`invocation-unknown`,
  `render.clj:1117-1133`).
- A failing unit gets its diagnostic face. With an owner, that is `renderer-failure`'s two faces plus message
  tx-data (`render.clj:1770-1806`). Without an owner, the same diagnostic data applies: producer, path, reason and
  requery. The "renderer unavailable" strings go.
- `units` is **pure**. It returns failures as units carrying `:seon.error/value` and `:seon.db/tx-data`, and commits
  nothing. The calling execution boundary commits and delivers: the web render proc already commits page tx-data
  (`web.clj:571-572`), and the turn opening and MCP settlement use their existing fault owners.
- Under `:panic`, that boundary stores the fault, then throws and stops its graph (AGENTS "Panic visibly"). Siblings
  keep rendering only under `:record`.

### 3h. Every consumer delegates the same responsibility

"One call" means one **presentation** boundary. It does not mean that domain acquisition, settlement or layout
disappear.

| Consumer | What stays in its owner | The call |
|---|---|---|
| MCP evaluation result | ordinary evaluation and settlement; its separate evaluator is one-REPL work | `units {:value result}` (value-only path works without SCI context); returns faces plus tree and cuts. R3/R6 retire the reconstruction |
| `get_value`, `/data` | coordinate decoding, envelope encoding | `units {:source-ref … :cursor …}` (§3e) |
| Namespace/agent page | walk acquisition, fleet oversight, `surface-html`, SSE packaging | `units {:seon.render.walk/units (walk acquisition) :output html}` |
| Agent opening | bootstrap membership, ordering, demonstrations, origin metadata; REPL settlement | `units {:output form}` for each descriptor, then the REPL executes once, then `units {:value actual-result}` |
| Prompt history | `of-agent` semantics, active-turn exclusion, per-evaluation identity and basis, `prompt/select` whole-unit admission | `units {:seon.render.walk/units (history composition)}`; saved bytes bypass B |
| Function face | the row, its pair `seon.render.ns/function-ai`/`-html` (function face §1.1) | `units {:query (pull of the row) …}`; callers, tests and tasks as child **handles** |
| Agent-facing walk | a thin `my.*` custody adapter; its branch filter selects **unit paths**, not a path inside one value | `units` with explicit custody |
| Transcript session/outline/ledger | evaluated-sources, comments, branch errors, messages, effects, ordering, calibration, contributed-byte accounting | final presentation only |
| Status/runtime observation | the observation's acquisition, including no-database boot and fault cases | `units {:value observation}` with no SCI context required |
| Test reports | native expected/actual evidence (test owner; audit R10 and its schema cost) | `units {:value report}` |
| Errors and the faults concern | error identity and occurrences; the requested observation data (the selector choice is a semantic decision, kept) | one acquired result feeds both faces |
| `my.program` reports | its read/write verbs | `units {:value report}`; presentation never reruns a verb |

## 4. Duplicates mapped to the block, one ledger

**Booked elsewhere, not recounted.** Audit §11 groups R1, R2, R3, R4, R5, R6, R8, R9, R10, C3 and C8 own their
retirements. **Audit C7** books `replay-read`'s 65 lines as a deletion. This design keeps the dispatch as `read` and
turns `replay-read` into a delegate, so it books those 65 lines **here, as Q0**. C7 must drop `replay-read` from its
139 lines (74 remain) and reprice its currency work. This design's net must not be added to the audit's 2,287.

Credit counts whole old definitions removed or rewritten in place (old size minus new size shown as the add).
Moved lines, such as the neighbourhood loop `walk.clj:751-807` moving into `units`, earn no credit and cost no
addition. Fragments of mixed functions earn nothing. Each range appears once.

| Group | Pinned ranges | Replacement | Old | Source add (est.) | Status |
|---|---|---|---:|---:|---|
| **Q0** executor | `db.clj:962-1026` `replay-read` 65 | `seon.db/read` dispatch over the public owners (~18) + `replay-read` delegate keeping its legacy check (~12) | 65 | 30 | direct once the evidence witnesses pass |
| **Q1** history | `walk.clj:926-974` `history` 49 | rewritten in place as the acquisition half returning a composition (of-agent kept) | 49 | 30 | conditional: history bytes, exclusion and membership invalidation |
| **Q2** ambient walk | `render.clj:1876-1976` `walk` 101; `:1814-1823` 10; `:1825-1839` 15; `:1841-1846` 6; `:1848-1862` 15; `:1864-1874` 11; `:1812` 1 | `my.*` adapter with explicit custody → `units` | 159 | 20 | conditional: branch filter mapped to unit paths; `turn.clj:4715` and two tests converted |
| **Q3** faults | `error.clj:1897-1902` 6; `:1943-1966` 24; `:1968-1979` 12; `:1981-2004` 24 | a `:seon.render/query` on the concern (lookup-bound `agent-faults-query` pulling with `observation-selector`, kept) | 66 | 0 (declaration, below) | conditional: empty/non-empty, one execution, both faces, observation data preserved |
| **Q4** opening loop | `bootstrap.clj:303-344` 42; `:346-365` 20 | form derivation through `units`; membership, teaching and key chaining kept in bootstrap | 62 | 15 | conditional: behaviour map |
| **Q5** outer history retention | `web.clj:2537-2610` `derive-context!` 74 | extended `render-call` retention with membership evidence; `change-context` (`:2504-2535`) kept | 74 | 15 | conditional: append invalidates; unrelated write reuses; segments and ledger data preserved |
| **Q6** transcript acquisition adapters | `transcript.clj:404-432` 29; `:668-737` 70; `:739-753` 15; `:945-995` 51; `:998-1011` 14; `:1856-1880` 25 | only the adapters made redundant; in-flight sources, comments, grouping, effects and calibration go to named surviving owners | 204 | 40 | conditional: audit §3.3's behaviour → surviving-function map first |
| **Q7** entity observation | `data.clj:110-235` | none: snapshot, eid and direction validation, attribute offsets and identity completeness are query-work policy | 0 | — | kept |

| Core (no group) | Source add (est.) |
|---|---:|
| `units` entrance: source dispatch, shared allocation threading, handles, cycle guard (the loop itself moves) | 45 |
| retention extension (source descriptor, acquisition and membership evidence, capture before reuse) | 20 |
| `seon.db/read-form` form derivation | 10 |
| source-reference resolution for continuations (replaces adapter-side acquisition; windows stay in R1/R3) | 30 |
| `raw-output` composition alternative | 8 |
| namespace-stage fit through the retained plan (after C3) | 15 |
| ownerless diagnostic face (replaces the placeholder branch; no credit, mixed function) | 3 |

| Ledger | Old | Source add | Declarations | Net source + declarations |
|---|---:|---:|---:|---:|
| Q0 | 65 | 30 | 0 | −35 |
| Q1-Q6 (all conditional) | 614 | 120 | 10 (Q3 query) | −484 |
| Core | 0 | 131 | 28 (request `:multi` 12, rendered-unit 8, continuation error 8) | +159 |
| **All, if every condition holds** | **679** | **281** | **38** | **−360** |

Tests are priced separately: one class regression per slice plus the §8 witnesses, about +200 lines (estimate).
Caller edits inside the converted consumers are included in their group's add. They are estimates, not ceilings.
Every slice still stops before 100 added lines (§5).

**What the additions buy.** The core's +159 is not new machinery beside the old. It is:

- the only public source dispatch (removing Q0's private duplicate);
- the only continuation resolver (letting R1/R3 delete their windows and reconstruction without a replacement
  acquisition in two adapters);
- the ladder completion that lets R2 delete `project-node*`'s chooser.

Without the core, R2 and R3 cannot book their 205 lines, and Q1-Q6 have nowhere to delegate.

## 5. Adoption order: one active loop at every commit

Each slice keeps HEAD loadable and converts all affected callers in the same activation. Every temporary name
delegates to the one implementation and names its retirement. Root runs the tests.

| Slice | Change | Source + / − (est.) | Pays with | Proof root runs |
|---|---|---|---|---|
| **S0** contracts | Root restarts default and verifies the loaded order and supplied defaults (§0.1). Declare the source `:multi`, `:seon.render/rendered-unit`, `:seon.render/composition` and `:seon.render/continuation-error`; no behaviour change. Carry forward the floor review's blockers as prerequisites. | 0 / 0 (+28 declarations) | enabling | the schemas compile against the packaged projection; no behaviour change |
| **S1** executor | `seon.db/read` over the public owners; `replay-read` delegates. C7 drops `replay-read`. | +30 / −65 | Q0 | §8 witness 1 |
| **S2** lift | `units` holds the one render loop. `neighborhood` becomes a **thin delegate** returning `units` over its own acquisition, with output unchanged, so `page-result`, `listing-candidates` (`:form` outputs) and the agent-facing walk keep working. Retention extension. Ownerless diagnostic face. | +68 / 0 | enabling; closes the placeholder issue | page units equal (ids, paths, bytes); an ownerless failure names producer and path; §8 witness 2 for value and query sources |
| **S3** budget + continuation | One allocation per request, handles, cycle and work bounds, compositions in `raw-output`; source-reference resolution; `get_value` and `/data` both call it. Lands **with** floor R1 slice (i) and R3, which book the windows and reconstruction. | +38 / 0 here | R1, R3 | §8 witnesses 4 and 5 |
| **S4** faults | The Q3 declaration end to end. | 0 / −66 (+10 declarations) | Q3 | one execution; captured empty/non-empty reads; both faces; nested bounded; a cut continues |
| **S5** opening, history, walk | Opening without pre-execution, with `seon.db/read-form` (Q4); history as a composition (Q1); the `my.*` walk adapter, then delete `*walk-context*` (Q2). Retire `neighborhood`'s delegate name once its three callers call `units`. | +75 / −270 | Q1, Q2, Q4 | an opening read settles exactly once; history bytes exact; the walk equals the old unit set |
| **S6** ladder | Namespace-stage fit through the retained plan (after C3); nested values through `selection` (with R2). | +15 / 0 here | R2, C3 | §8 witness 3 |
| **S7** domain views | `derive-context!` fold (Q5) after membership proof; transcript adapters (Q6) after the behaviour map. | +55 / −278 | Q5, Q6 | append invalidates, unrelated write reuses; session/outline/ledger bytes and accounting equal |

The slices add 30 + 68 + 38 + 75 + 15 + 55 = **281** source lines and delete 65 + 66 + 270 + 278 = **679**.
That is the §4 ledger exactly: S2 carries the core's `units`, retention and diagnostic lines (45 + 20 + 3); S3 its
source-reference and composition lines (30 + 8); S5 `read-form` (10); S6 the plan fit (15). No slice exceeds 100
added lines. If S3's share of R1's classification and coordinate work grows, it splits along R1's (i)/(ii)/(iii)
commits.

## 6. What stays distinct

- Database read execution and capture **versus** since-diff comparison and changed-read refresh. They share the
  dispatch, not the purpose.
- Query-work bounds and deadlines **versus** the one presentation allowance.
- Entity and concern acquisition policy (walk), history semantics (`of-agent`) and bootstrap membership **versus**
  selection and face encoding.
- Occurrence identity **versus** cache-validity inputs **versus** historical basis.
- Ordinary REPL evaluation and settlement **versus** rendering its actual result.
- Exact saved shown text and whole-unit prompt admission **versus** rendering a new live value.
- Live object custody, retained artifact bytes and database history, which have different lifetimes.
- Diagnostic face construction **versus** durable core-fault recording, delivery and panic.
- Native test evidence, effect receipts and domain analysis **versus** how they are displayed.
- Source-code formatting, HTTP/SSE/MCP envelopes and DOM targeting **versus** value interpretation.
- Agent verbs (`seon.error/for-agent`, `my.program` queries) **versus** declared concern reads. They may share
  Datalog; a verb stays a function the agent calls.
- `render.data/entity-observation`'s paged observation **versus** a single `:index-page` read.

## 7. Where a concern's query is declared: resolved hybrid

The first pass offered three options. The review agrees with A and bounds B and C. The hybrid:

- **A (default): a `:seon.render/query` property on the concern attribute's schema**, holding a read-request
  template. It is read through the existing projection's Malli properties and invalidated by its declaration inputs.
  Use it for simple declarative reads, such as faults and the function face's callers, tests and tasks. It costs
  about 3 declaration lines per concern.
- **B: an existing domain function** where acquisition already carries ordering, projection, validation or
  composition semantics (`of-agent`, the walk, ledger assembly). Its output is plain data or a unit composition
  handed to `units`. Such functions are never translated into a general read program.
- **C: renderer-produced children**, only through a declared `:seon.render/composition`, under the same budget and
  provenance rules. Children are never triggered by an inspected map's shape.

No universal refusal of identity-less pulled maps: owned components and partial observations legitimately lack
identities. Only a continuation that promises an entity identity must prove one. The probe's bare
`{:db/id 42404}` leaf means a declared query that feeds entity continuations must ask for identities.

## 8. Required proof (acceptance witnesses)

These witnesses run through the installed test process when implementation is authorized. The floor review's
hostile-value, deep-serialization, safe-navigation and emitted-byte requirements remain prerequisites. They are not
duplicated here.

1. Every read operation records the right evidence. Empty → inserted, changed or retracted, unrelated-write reuse,
   malformed request and query-bound refusal have distinct outcomes.
2. Literal nil and false, scalar `:q`, relation `:q`, `pull-many`, one faults concern and ordered saved history
   have declared, stable unit boundaries. An opening read executes and settles exactly once.
3. Root and nested values obey the same per-face order. Supplied map and positional defaults use the held database,
   caller arguments win, and returned data cannot restart a producer or the budget.
4. Many query children stay within one B. Cycles and work exhaustion name a continuation or refusal. Two occurrences
   keep separate addresses without duplicating valid acquisition.
5. MCP and the browser retrieve the same selected fragment from live, query and artifact sources. The cases include
   transformed-output paths, wrong branch or basis, an absent live object, a missing artifact, a malformed cursor,
   nil versus missing, empty and beyond-end, and strict absolute-offset progress.
6. History shown bytes stay exact. Generated forms, demonstrations, selected run semantics, errors, effects and
   ledger accounting survive their conversions. Record and panic keep their different execution behaviour.

Performance per slice: source acquisition, selection and invocation, cache hit and miss, and both emissions. Report
parent-versus-change latency and memory, reached units and reads, and retention hits. AGENTS.md's >20% or 50 ms
rule blocks a commit.

## 9. Unresolved risks and out-of-scope findings

This lane writes one file, so these are recorded here.

- **Installed behaviour is unproven** (§0.1). Every stage claim is a HEAD-source claim.
- **Performance is unmeasured**, beyond two tiny reads.
- **Inherited gaps:** closure defaults (`call_preparation.clj:1535-1542`) and plan-based fit before C3 (§3c).
- **The one-render-path design §1** still says to remove the explicit and namespace stages right after restating the
  four-level ruling. That is a stale instruction in that design's owning revision (review P2). The latest owner
  order governs.
- **The function face writes `:seon.db/pull-arguments` as a map** (function face §1.1); the schema is a vector. This
  is for its owner to correct.
- **Skill citations have drifted.** The datahike skill cites stale `q`/`pull`/read-evidence lines; the current
  definitions are `db.clj:2365`, `:2588` and `:895`. This is a skill defect for its owner; this lane edits no skill.
- **Existing issues:** `docs/seon/issues/namespace-page-repeats-renderer-unavailable.md` (S2 closes it); the faults
  prompt share in `agent-context-render-pipeline-2026-09-24.md` §0.1 (S4 closes it); ambient `*walk-context*`
  (S5 removes it).
