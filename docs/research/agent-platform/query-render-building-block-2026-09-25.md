---
type: design
status: "Opus first pass (lane research-query-render-history-opus); awaiting Astra review (AGENTS.md step 4)"
created: 2026-09-25
scope: Git archaeology of every query + render generation, and the one building block that replaces them
source-pin: 48ceb9cad (every src range below is valid at this commit; walk, data, web, transcript, bootstrap, prompt, context, cluster and mcp.clj are byte-identical to 3785970b9)
---

# Query + render: one building block

Seon already has every part: a declared read as data (`:seon.db/read-request`, executed by `seon.db/replay-read`), a
four-stage per-face selection (`seon.render/selection`), guarded invocation with supplied defaults
(`invoke-selected` → `sci.kernel/invoke` → `call-preparation/hook`), a retained render call (`render-call`), and
one floor (`render.value/prepare`). What is missing is **one entrance that accepts a value *or* a declared read,
and returns the rendered units of it**. Every consumer today writes its own "query, then loop, then render" around
`render-call`, or skips `render-call` entirely.

The principal Clojure approach: add a second **source** to the existing call request (`:seon.render/query`, a
`:seon.db/read-request` value) and lift the walk's per-member render loop into the one public entrance
`seon.render/units`. No new registry, cache, selector, printer, navigator or storage.

## 0. Broken things first

1. **The installed JVM does not run HEAD's selection.** Read-only probe on default (PID 35446, started
   `2026-09-25T14:24:44Z`, health `faulted`, 21 open faults, open fault "JVM RESTART NEEDED: loaded dependencies
   changed"): `@#'seon.render/selection-stage-order` answered **`[:declared :floor]`**. HEAD says
   `[:explicit-value :namespace :declared :floor]` (`render.clj:532-533`). The owner-ruled precedence
   (634d8aaa2, aa91ecb34) and the database-as-declared-input change (b195ae037) are committed but not
   installed. **RESTART NEEDED (root).** No claim below about selection behaviour is installed evidence.
2. **This is the seventh generation.** The same four-level selection was written on 2026-01-23 (`seon.ns.view`),
   2026-02-19 (`find-renderer`), 2026-05/06 (`resolve-render` in the pod), 2026-08-17 (`context-pipeline-ideal`)
   and 2026-09-24/25 (`selection`). "Context is a render of a database query" was written 2026-02-19
   (`seon.graph.context/build`), 2026-06-24 (`context-render.md`), 2026-07-28 (`render(its namespace, distance
   N)`), 2026-08-14 (`one-renderer-prd`), 2026-08-29 (`context-as-queries-handoff`), 2026-09-04
   (`agent-centric-design`) and 2026-09-23 (`context-as-query-and-render`). Each was replaced wholesale by a
   tree cut, never converged in place. §1 lists them.
3. **Declared-concern producers still do their own graph work in the AI face.** The steward faults concern is
   the live example: `faults-form` (`error.clj:1943-1966`) builds a read form, `render-faults-ai` (`:1968-1979`)
   prints it as source, and `render-faults-html` (`:1981-2004`) runs a *different* acquisition (`for-agent`,
   `:1925-1941`, pulling with `observation-selector` built from the whole projection, `:1643-1672`). The
   2026-09-24 capture found this read reprinted five times, 549 KB, **81% of a 680,643-character prompt**
   ([agent-context-render-pipeline](agent-context-render-pipeline-2026-09-24.md) §0.1). The one-REPL design
   already rules "no graph work in an AI renderer" ([one-repl-language](one-repl-language-2026-09-25.md) §5).
4. **The agent-facing walk is a second entrance with ambient custody.** `seon.render/walk`
   (`render.clj:1876-1976`, 101 lines) reads custody from the dynamic Var `*walk-context*`
   (`:1812`, bound by `call-with-walk-context` `:1814-1823` from `turn.clj:4715`), then `pr-str`s the units.
   AGENTS.md "Pass the world explicitly" forbids this.
5. **A banned placeholder survives in the one traversal.** `neighborhood` still paints
   `"renderer unavailable"` / `"Renderer unavailable."` on failure (`walk.clj:792-798`). Existing issue:
   `docs/seon/issues/namespace-page-repeats-renderer-unavailable.md`.
6. **The declared-read executor is private.** `seon.db/replay-read` (`db.clj:962`) is total over `:q`, `:pull`,
   `:pull-many` and `:index-page`, returns typed refusals, and records read evidence. Its only caller is
   read-currency replay (`db.clj:1166`). Every render-side query is hand-written instead: 36 `db/q`/`db/pull`
   sites in `render/transcript.clj`, 19 in `bootstrap.clj`, 18 in `error.clj`, 15 in `render/web.clj`, 15 in
   `my/program.clj`, 11 in `problems.clj` (`grep -cE '\((db|d)/(q|pull|pull-many|datoms|entity)[ )]'` at the
   pin).

## Evidence and limits

Read end to end: `AGENTS.md`, the lane preamble `tmp/orchestrator/fix-lane-preamble-2026-09-24.md`,
[duplicate-paths audit](duplicate-paths-audit-2026-09-25.md) §§1-3, 7, 10-11,
[one-render-path design](one-render-path-design-2026-09-25.md) §§1-6,
[floor-shell-order review](floor-shell-order-review-2026-09-25.md) (P1-1 to P1-7), the render-dispatch landing
(`docs/prds/agent-platform/landing/lane-render-dispatch-opus-2026-09-24.md`, twelfth follow-up) and
[one-repl-language](one-repl-language-2026-09-25.md) §§2.4, 4.0, 5-6. The earlier archaeology
`docs/prds/context-generation/research/render-archaeology-2026-08-14.md` was read. It is reused, not redone:
this document adds the *query* half and the later generations.

Archaeology method (14,652 commits, `git rev-list --all --count`):
`git log --all --diff-filter=D --name-only` filtered for render/context/query/walk/block/page/prompt/view;
`git log --all -i -E --grep` over render/query/context-block/walk/neighbourhood (589 matches); `git log -S`
for `find-renderer`, `renderer-resolution`; and every PRD/research path ever committed under `docs/` whose
name matches those words (≈230 files). Sizes are `git show <deleting-commit>^:<path> | wc -l`. Current
definition sizes use one awk pass over `git show HEAD:<file>`: first line of a top-level `(def…` to its last
non-comment, non-blank line before the next top-level form. That matches the audit's counting rule (§2) for these files.

Live probe (JVM mode, explicit root/cluster, `read_only`, one finite form; the first attempt was my malformed
pull arguments, which include the database; replay supplies it):

```clojure
(let [database @(seon.cluster.boot/connection "default")
      counted (#'seon.db/replay-read database {:seon.db/read-operation :q
                :seon.db/query-request {:query '[:find (count ?e) . :where [?e :seon.agent/id]]
                                        :args [:seon.db/database]}})
      pulled (#'seon.db/replay-read database {:seon.db/read-operation :pull
               :seon.db/pull-arguments [[:seon.agent/id :seon.agent/namespace] [:seon.agent/id "root"]]})
      rows (seon.db/q '[:find [?k ...] :where [?r :seon.call-preparation/key ?k]] database)] …)
```

Result: count `1` in **0.127 ms**; pull `#:seon.agent{:id "root", :namespace #:db{:id 42404}}` in **0.110 ms**;
supplied keys `[:my.program/context :seon.agent/id :seon.db/connection :seon.db/db]`; stage order
`[:declared :floor]`; prepl 3 ms. The pull shows a **bare `{:db/id …}` ref leaf**. The 2026-08-17 rule
"every ref leaf carries its identity attribute" is not satisfied by a plain selector; a declared query must
ask identities itself.

No code, test, `bin/seon` or JVM start was run. Memory, allocation and end-to-end render timings are
**unmeasured**. Every "+N" below is an estimate, not a diff.

## 1. Archaeology: every generation of query + render

| Gen | Commit range | Files (lines at deletion or now) | Entry function | What it got right | Why replaced | Survives today |
|---|---|---|---|---|---|---|
| **G0 namespace UI** | e041ef5c6 (2026-01-23) → 6c1079c8d (2026-07-13, "archive paused JVM application", −54,229) | `seon.ns.view` 552, `seon.ui.viewer` 269, `seon.render` (clj) 789, `render/default_page` 246, `render/code` 347, `render/example` 163 (deleted 5e8e40c06) | `seon.ns.view/render-value` `{::value ::format}`, multimethod on `[format view-type]` | Faces per format (`:ai :html :human :raw`); **value metadata overrides, Malli schema property is the default, inference last**. Map-in/map-out. | JVM application paused for the CLJS pod | No code. The four-level idea returns in G6. |
| **G0b spec-driven discovery** | 6e013a325 (2026-02-19) → same archive | `render.clj` `find-renderer` (+56), `graph/scanner` | `find-renderer [conn data format]` | **Contract-fitting renderer discovery** from `:seon.fn/render-input-keys` (level 2 of today) | Ranked by specificity, then recency, then name: a silent tie-break, where today refuses ambiguity | `namespace-stage` (`render.clj:617-645`) restores the idea without the ranking |
| **G0c context builder** | 37bc72b00 / 0a0bd0a06 (2026-02-19) → 6c1079c8d | `graph/context` 392, `graph/query` 491, `repl/context` 121 | `seon.graph.context/build {::db-name ::seed ::depth}` | **Context = seed → recursive pull over refs → topological order → text.** The first "context is a query" | Datalevin era; whole tree archived | Walk acquisition (`walk.clj:252-573`) is the descendant |
| **G0d renderer resolution PRDs** | 552db0ffc (2026-02-22), c5bc21291 (2026-02-26) | `docs/prds/archive/refinement/render-pipeline.md`, `renderer-resolution.md` | (design) | **"Agents never wire data sources; they declare what they need via input spec; the system injects it"** — today's supplied defaults | `*ctx*`/`*conn*` dynamic Vars; page-renderer heuristic | `call-preparation/hook` (`call_preparation.clj:1497`) is the same idea done by schema name |
| **G1 pod render + context blocks** | 5a9255de8 (2026-05-19) → f25e34594 (tree split 2026-07-27) → 099cdfa99 (quarry deleted 2026-08-05) | `render.cljs→cljc` 1,132, `agent/ctx.cljc` 1,959, `agent/ctx/transcript.cljc` 1,612, `agent/ctx/namespaces.cljc` 1,292, `render/value.cljc` 1,927 (quarry pin 9e44815f5) | `seon.render/render [view ctx node]` with `resolve-render` (`render.cljc:1058`); `seon.agent.ctx/rendered-context-from-entity` | **One resolution ladder** (slot string/hiccup → symbol → schema default → generic); **one recursion handle** `:seon.render/render`; **one envelope unwrap**; `missing-render` names the missing symbol; record → panic → face; per-block token estimate reused for whole-prompt accounting | Pod deleted; stored block rows and profiles; strict dial defaulted off; seven value dials | Selection's four stages; failure faces in `renderer-failure` (`render.clj:1770`). The recursion handle and envelope unwrap did not survive |
| **G1b "context is a render of the database"** | 68bc63dfc (2026-06-24), 6c93677b1 (2026-06-25), e5305b5a9 (2026-05-26) | `docs/prds/archive/agent-fsm/archive/context-render.md`, `single-render-path-design-2026-06-25.md`, `ctx-render-strategies-prd.md` | `seon.ctx/render-context [{:seon.db/db :seon.agent/id}] → twin` | **"Three render systems must collapse into one, not add a third."** `:seon.render/ai [:or :string :symbol]` value slot (today's level 1). Handle = the entity's own id; derived, never stored; one producer returns the AI+HTML twin; byte identity between prompt and inspector | Pod era; sections still hand-ordered by `:seon.ctx/priority` | Level 1 (`explicit-value-stage`, `render.clj:581-590`) |
| **G2 JVM host** | cd239b795 (2026-07-20) → 8dc8623ad (2026-07-26, "Delete the 5,715-line old guarded door") | `host/context.clj` 2,184; `render/value.cljc` (JVM) 281 (→0413f07e7); `src-old/seon/render/surface.cljc` 87, `view_unit.cljc` 49, `web/feed.clj` 170 | `seon.execution.runtime/render-prompt!`, `render-agent-view!` (design `render-context-single-owner-cut-2026-07-16.md`) | **One held `:seon.db/db` carried through every nested selected function** (consistent basis) | UDS execution child deleted | The held-basis rule survives as b195ae037's environment scoping (`render.clj:1082-1091`) |
| **G3 fresh tree: blocks** | 6dcda1ab9, d240656f2, f2a6ec0f2 (2026-07-27) → 4a65f9c7a (2026-08-03) | `render/block.clj` 347 → **96 now**; `render/root.clj` 217 (deleted); `render/data.clj` 221 → 235; `render/agent.clj` 179 → **24 now**; `seon.context` 340 → 416 | `block` derivation; `/data` drill | **The block is the morph target** (`surface-id`, `block.clj:61-96`); the `/data` cursor is a window with a cursor, not an elision | Stored/hand-listed blocks; "the funeral" 7ba92be65 folded them into the neighbourhood | `block/surface-id`, `data/at`, `data/parse-cursor` |
| **G4 the neighbourhood walk** | 1a499558a (2026-07-28, "an agent's context is render(its namespace, distance N)") → now | `render/walk.clj` 461 → **974**; `schema/walk.edn` | `walk/neighborhood request → units`; `walk/history`; agent-facing `seon.render/walk` | **Flat units, each a `render-call`**; distance is an argument, spent per hop; total (failure is a value) | Measured 23 s (6f309c6d3); universal render-walk outage (965bce048); `render.lint` 529 lines added 746a87d51, deleted 9440698f3 (2026-09-22) | **Live**: `neighborhood` `walk.clj:710-807`, `history` `:926-974`; callers `web.clj:470`, `bootstrap.clj:362`, `turn.clj`, `render.clj:1939` |
| **G5 context-generation PRDs** | 2026-08-13 → 2026-09-09 | `docs/prds/context-generation/plan/`: `one-renderer-prd-2026-08-14.md` 393 (deleted 93187e7cb), `context-pipeline-ideal-2026-08-17.md` 113, `render-data-plan-2026-08-28.md` 138, `context-as-queries-handoff-2026-08-29.md` 265, `agent-centric-design-2026-09-04.md` 651 | (design) | **Mechanical acquisition**: own / incoming / outgoing, and **one render target per attribute group** (all messages-to-me are one unit). Four face levels with ties loud. **Form honesty**: every context entry is a call the agent could make, whose print equals the AI bytes. One drill contract for both seams. "Context for the AI and the human is one render of one pull" | Never implemented as one function. Code grew by concern: `declared-acquisition` (`walk.clj:657-692`), d6d399561 "declared concerns", source-returning renderers | `:seon.render/units` property (3 schemas: agent, cluster.status, issue); **26 source files** return `:seon.render/source` read forms |
| **G6 agent platform** | d0b0c7a62 (2026-09-23) → now | `context-as-query-and-render-2026-09-23.md` 419, `agent-context-render-pipeline-2026-09-24.md` 330, `one-render-path-design-2026-09-25.md` 693, `floor-shell-order-review-2026-09-25.md` 231, `one-repl-language-2026-09-25.md` 703 | `seon.render/selection`, `render-call`, `render.value/prepare` | Per-face first match (owner 2026-09-25); **database as a declared input supplied by call preparation** (b195ae037); shell-order floor with one budget; **one retrieval entrance keyed by held source** (Astra P1-2) | — | This document's base |

**Count.** Eleven distinct entrances that took data from the database and rendered it for a face: `ns.view/render-value`,
`find-renderer`, `graph.context/build`, `repl.context/for-*`, pod `render/render` + `agent.ctx` assembly,
`ctx/render-context`, `execution.runtime/render-prompt!` / `render-agent-view!`, fresh-tree blocks,
`walk/neighborhood` + `history`, the agent-facing `seon.render/walk`, and `render-call` itself. The owner's
"about ten times" is accurate.

**The pattern of loss.** Each generation was replaced by a *tree* cut (archive 07-13, split 07-27, quarry deletion 08-05),
not by converging callers onto the previous owner. What was lost every time was the same three things: the
**recursion handle** (a renderer handing a sub-value back to the same dispatch), the **declared query as data**
(G0c's seed + pull, G5's attribute-group target), and **one entrance** (G1b's "not a third"). The
replacements kept rediscovering selection, which is the easy part.

## 2. Best ideas taken, by name

| Idea | Source generation | Where it lands in the block |
|---|---|---|
| value key → viewer's contract-fitting fn → schema pair → floor, ties loud | G0 `ns.view`, G0b `find-renderer`, G1 `resolve-render`, G5 ideal, G6 ruling | Unchanged `seon.render/selection` (`render.clj:671-692`) |
| inputs declared by contract, supplied by the system, caller wins | G0d renderer-resolution | Unchanged `call-preparation/hook`; b195ae037 already scopes the held database |
| context = seed + declared pull, rendered | G0c `graph.context/build`, G4 walk, G5 one-renderer | Source = `:seon.render/query` (`:seon.db/read-request`) |
| one render target per attribute group | G5 `context-pipeline-ideal` | A declared concern's query is one unit, never one unit per row |
| every ref leaf carries identity | G5 2026-08-17 probe | A declared query asks identities; the probe above shows why |
| form honesty: the context entry is the call | G5 one-renderer §1.3; one-REPL §5 | The AI form of a query unit is derived from its read-request |
| one recursion handle, one envelope unwrap | G1 quarry `render.cljc:290-325,1088` | A renderer returns a smaller value or a `{:seon.render/query …}` child; the entrance renders it. No second path down |
| named missing/failed renderer, never a placeholder | G1 `missing-render` | `units` returns the existing `renderer-failure` face; deletes `walk.clj:792-798` |
| the block is the morph target | G3 `block.clj` | Unit identity `:seon.render.call/id` → `surface-id` |
| one held database value through nested renderers | G2 single-owner cut | b195ae037 (installed at HEAD, not in the JVM) |
| retained reuse by read evidence, not by commit id | G4 `render-call` | Unchanged; a query's reads land in the same `:seon.render.call/captured-reads` |
| one retrieval entrance by held source + path + offset | G6 Astra P1-2; G3 `/data` cursor | Same entrance with `:seon.render.data/cursor` |

Correctly left dead (from render-archaeology §"Correctly deleted", confirmed): stored block rows and profiles,
per-block token caps, seven value dials, `render-strict?` defaulting off, specificity/recency ranking.

## 3. The building block

### 3a. Data shape: source → unit → render

**Source.** Exactly one of:

- `:seon.render/value` — any value (unchanged);
- `:seon.render/query` — a `:seon.db/read-request` (`seon.db.edn:94-104`: `:q` with `:seon.db/query-request`,
  `:pull`/`:pull-many` with `:seon.db/pull-arguments`, `:index-page`), with two placeholders in its arguments.
  `:seon.db/database` is already substituted by `replay-read` (`db.clj:980-982`, probed above). `:seon.render/subject`
  (new) is substituted by the entrance with the parent unit's lookup.

Search result: no `:seon.render/query` exists in the registry (`git grep` over `resources/` at the pin).
One new member on `:seon.render/call-request`, plus one attribute-schema property of the same name for
declared concerns. No new entity kind.

**Unit.** The existing call request (`:seon.render/call-request`), which is already the unit:
face `:seon.render/output`, source, identity `:seon.render.call/id`, path `:seon.render.walk/path`,
`:seon.render/distance`, and explicit custody (`:seon.db/db`, `:seon.sci.eval/ctx`, `:seon.agent/id` as the
viewer, caps, time limit, `:seon.config/on-core-error`, floor profile, retained/captured calls). The
*argument* a renderer receives stays `render-argument`'s unit (`render.clj:196-233`), which since b195ae037 omits
the database. A renderer that declares `:seon.db/db` is supplied the request's database by call preparation.

**Render.** The existing `render-call` (`render.clj:1496-1678`) decides one unit. A query source resolves first:
`(db/replay-read db (substitute query subject))` inside the call's captured-read scope. The read result *is*
the value; a typed read refusal is also a value, and renders through the error pair. Then comes the unchanged
selection, invocation and output check.

**Output.** A vector of the existing walk units (`:seon.render.walk/units`): each carries `:seon.render.call/id`,
`:seon.render.walk/path`, `:seon.render/output` (the rendered face), and optionally `:seon.error/value`. A
query unit also carries `:seon.render/query`, so the AI context can print the call that produced it.

### 3b. One entry function

```clojure
(seon.render/units request) ;=> [:seon.render.walk/unit …] | :seon.render/request-error

;; request = :seon.render/call-request, accreted:
{:seon.render/output   :seon.render/ai            ; or :seon.render/html
 :seon.render/query    {:seon.db/read-operation :q
                        :seon.db/query-request {:query '[:find [(pull ?e [:seon.cluster.eval/id …]) ...]
                                                         :in $ ?agent :where …]
                                                :args [:seon.db/database :seon.render/subject]}}
 ;; or :seon.render/value v
 :seon.render.call/id  [:seon.render/ai [:seon.agent/id "root"] :history]
 :seon.render/distance 0                          ; 0 = this unit only
 :seon.db/db db :seon.sci.eval/ctx ctx :seon.agent/id "root" …}
```

Contracted with a complete named schema over the existing members. Cost is the source's read (bounded by
its own query-work bounds), plus one selection, plus one invocation per unit, plus the floor under B per unit.
There is no whole-program work. A unit whose call id, producer and read evidence are current is reused
(`render-call`'s existing path).

`units` **is** the per-member loop now inside `neighborhood` (`walk.clj:751-807`), lifted out and given the two
sources. `neighborhood` keeps only what is genuinely its own, **the default declared query of an entity**:
installed-schema acquisition, declared concerns, components and distance (`walk.clj:69-708`). `render-call`
stays public for one-unit callers during conversion. Its callers then either call `units` with distance 0
or remain as `units`' internal step.

### 3c. Selection and contract defaults compose; nothing new

- **Per face, first match wins** (owner 2026-09-25): (1) the value's own `:seon.render/ai`/`:seon.render/html`:
  a symbol, an inline function or data (`explicit-value-stage`, `inline-rendered` `render.clj:581-604`);
  (2) a contract-fitting function in the **viewing** agent's namespace (`namespace-stage` `:617-645`, viewer =
  request `:seon.render/namespace`, else `:seon.agent/id`'s namespace); (3) the schema's declared pair
  (`declared-stage`); (4) the floor. For a query unit, "the value" is the read result. So a query's result can
  carry its own face key, a viewer can override how its `[:vector error]` looks, and otherwise each element's
  schema pair applies inside the floor.
- **Inputs by contract.** `invoke-selected` (`render.clj:1069-1115`) invokes through `sci.kernel/invoke`, whose
  named-Var path calls `call-preparation/hook` (`kernel.clj` invoke; `call_preparation.clj:1497-1556`). Declared
  but omitted inputs are supplied by schema name from the four rows probed above; the held database comes via
  `env/scope {:seon.db/db …}` (b195ae037). Caller arguments win. Map inputs use `supplied-map-entries`;
  positional inputs use the plan's arity placement.
- **Known gaps, owned elsewhere (not re-solved here):** inline closures are never prepared
  (`call_preparation.clj:1535`; the landing prices options at about 120 lines or about 30 lines plus
  analysis). The namespace stage's one-argument fit never selects a positional renderer declaring a second
  database input (landing, twelfth follow-up). The block inherits both limits. An inline function receives
  only its value (design §1).

### 3d. Nested components and declared queries render in their own blocks

AGENTS.md: "scalars in its block, components/declared queries in theirs". Two producers of child units:

1. **Schema-declared.** An entity schema's `:seon.render/units` names its concern attributes (existing,
   `seon.agent.edn:38`, `seon.issue.edn:46`, `seon.cluster.status.edn:7`). A concern attribute's schema may
   declare `:seon.render/query`, a read-request template over `:seon.render/subject`. `units` emits one child
   unit per concern: source = that query, path `[attr]`, id `[face parent-lookup attr]`. Owned components are
   child units whose source is the component value, as `declared-acquisition` does today (`walk.clj:657-692`).
   Distance decrements per hop (`walk.clj` docstring rule). One unit per attribute group (G5), never per row.
2. **Renderer-returned.** A renderer may return a smaller value containing a `{:seon.render/query …}` map. That
   is the recursion handle (G1) as data. The floor does not execute it inline. It emits a reference node at
   that path, naming the child's call id (a cut-shaped node, charged like one, so B stays single). `units`
   appends the child unit after the parent. Within a unit, nested values that are not queries stay in the
   same floor under the same B (one-render-path §2/§3b: no fresh B, no redispatch of the same producer).

A child whose read refuses or whose renderer fails yields its own unit carrying `:seon.error/value` and the
`renderer-failure` face (`render.clj:1770-1806`). Siblings render. The "renderer unavailable" strings go.
Under `:panic` the failure is also recorded and thrown at the entrance, per AGENTS.md "Panic visibly"
(existing `renderer-failure` tx-data path, `walk.clj:777-781`).

### 3e. Cuts and continuations

The floor is one-render-path §3 as revised by the floor-shell-order review: classification before any
touch; one character budget spent in shell order; one tail cut per collection carrying `:seon.render.data/path`,
`/next-offset`, `/total` or `/total-unknown`; fipp for the AI text; Hiccup from the same tree. The block adds
exactly one thing, which Astra's P1-2 asks for: **the continuation names the unit's source**, so MCP and the
browser use one entrance:

```clojure
(seon.render/units {:seon.render/value v :seon.blob/digest d            ; stored artifact
                    :seon.render.data/cursor {:seon.render.data/path p :seon.render.data/offset n} …})
(seon.render/units {:seon.render/query q :seon.db/db (as-of basis) …    ; query at its basis
                    :seon.render.data/cursor {…}})
```

- A **value** cut's requery is its digest plus path and offset (`get_value`, `/data?value=`). A live unsaved
  value has no expander and says so (design §3f).
- A **query** cut's requery is the read-request plus basis `t` plus path and offset. It is valid forever,
  because the query is data and `as-of` answers history. It is labelled current or as-of; this is Datahike's
  time seam, not a stamp.
- An **entity** cut is the entity's default declared query, i.e. `neighborhood`'s acquisition, labelled current.

`mcp-get-value` (`cluster.clj:555-610`) and `data-response` (`web.clj:3516-3627`) both become a cursor
request to `units`. Their windows and second navigator are already in audit R1/R3 and are not recounted.
Navigation stays in `render.data/at` (`data.clj:46-89`), which preserves missing-versus-nil (audit §1.3).

### 3f. Every consumer, one call each

| Consumer | Today | With the block |
|---|---|---|
| MCP eval result | `mcp-project` admits, artifacts, prepares, reconstructs (`cluster.clj:388-494`); bridge re-enriches (`mcp.clj:371-441`) | `units {:value result :distance 0}` → AI text + cut coordinates (R3/R6 retirements, audit) |
| `get_value`, `/data` | two acquisitions and windows | `units` with cursor (§3e) |
| Namespace/agent page | `page-result` → `neighborhood` → `surface-html` (`web.clj:435-572`) | `units {:query (entity default) :distance d :output html}` → `surface-html` per unit (morph per unit) |
| Agent opening | `bootstrap/direct-candidates` + `listing-candidates` call `render-call` per member (`bootstrap.clj:303-365`) | `units {:output ai}`; each query unit's derived read form becomes the opening's `:seon.repl/form`, evaluated through the ordinary REPL settlement (form honesty; the evaluation, not the renderer, records) |
| Prompt history | `walk/history` hand-queries `eval/of-agent`, loops `render-call` (`walk.clj:926-974`); `derive-context!` caches it (`web.clj:2537-2610`) | `units {:query evaluations-of-agent}`; each evaluation unit renders its **saved shown bytes** via the evaluation pair; `prompt/select` keeps whole units (§5 of design) |
| Agent-facing `(seon.render/walk)` | ambient `*walk-context*`, `pr-str` of units (`render.clj:1812-1976`) | a `my.*` call with explicit custody delegating to `units` |
| Transcript session/outline/ledger | 36 hand queries in `transcript.clj` | per-view declared queries (turns of agent, attempts of turn) → `units`; layout and domain analysis stay (audit §3.3) |
| Status / runtime observation | `mcp-runtime-observation` 83, `bounded-face`/`bounded-summary`, operator summaries (audit R4) | `units {:value observation}` with one declared renderer; terminal and MCP take its text |
| Test reports | stored rendered strings (`runner/failure-report`) | `units {:value native-report}` after native evidence (audit R10, §1.5) |
| Error faces | `error/prepare-result` renders twice (audit C8) | `units {:value error}`; the faults concern becomes a declared query (§4 Q3) |
| `my.program` reports | hand queries → report map → floor | unchanged queries (they are the agent's verbs); the report renders via `units {:value report}` |

## 4. Duplicates mapped to the block, measured

**Not recounted.** Audit §11 groups R1 (1,099), R2 (140), R3 (65), R4 (88), R5 (20), R6 (50), R8 (21), R9 (15), R10 (50),
C3 (329) and C8 (33) are rendering or defaults work that the block *consumes*. Their credit stays with their
audit groups and owning designs. The groups below are **not in the audit ledger**. Each range belongs to one
group only. Sizes are at the pin; "credit" counts old definition lines that the named replacement removes.

| Group | Pinned ranges (lines) | Replacement | Credit | Add (est.) | Status |
|---|---|---|---:|---:|---|
| **Q1 history as a hand query + loop** | `walk.clj:926-974` `history` 49 | a declared read-request (evaluations of an agent, excluding its own run as now) + `units` | 49 | 12 | direct |
| **Q2 agent-facing walk with ambient custody** | `render.clj:1876-1976` `walk` 101; `:1814-1823` `call-with-walk-context` 10; `:1825-1839` `walk-error` 15; `:1841-1846` `ambient-database-value` 6; `:1848-1862` `repl-state` 15; `:1864-1874` `selected-walk-units` 11; `:1812` `*walk-context*` 1 | a `my.*` entry with explicit custody → `units` (branch filter becomes a path cursor) | 159 | 20 | direct; converts `turn.clj:4715` and two tests |
| **Q3 faults concern: query + two faces by hand** | `error.clj:1897-1902` `faults-input` 6; `:1943-1966` `faults-form` 24; `:1968-1979` `render-faults-ai` 12; `:1981-2004` `render-faults-html` 24 | one `:seon.render/query` on the concern attribute (the existing `agent-faults-query` datalog, with an identity-bearing selector, not `observation-selector`) | 66 | 10 | direct; `for-agent` (17), `agent-faults-query` (20) and `observation-selector` (30) stay for agents and other callers |
| **Q4 duplicate per-member render loop** | `bootstrap.clj:303-344` `direct-candidates` 42; `:346-365` `listing-candidates` 20 | `units` + filter on the returned units | 62 | 15 | conditional: opening-subject membership and demonstrations must be preserved by a behaviour map |
| **Q5 second retention layer over history** | `web.clj:2537-2610` `derive-context!` 74 | `render-call` retention on the history unit's id and read evidence | 74 | 5 | conditional: its context-action (`change-context` `:2504-2535`) is kept; prove the cache hit on an unrelated write |
| **Q6 transcript acquisition** (audit §3.3 inventory, unbooked there) | `transcript.clj:404-432` `history` 29; `:668-737` `candidate-history` 70; `:739-753` `projection` 15; `:945-995` `agent-history-under` 51; `:998-1011` `agent-history` 14; `:1856-1880` `acquire-ledger-data` 25 | declared queries → `units` | 204 | 40 | conditional on the behaviour → surviving-function map the audit requires first |
| **Q7 entity observation page** | `data.clj:195-235` `entity-observation` 41; `:135-151` outgoing 17; `:153-193` incoming 41; `:110-133` helpers 22 | an `:index-page` read-request (replay-read supports it, `db.clj:1010-1013`) | 0 | — | candidate only: its snapshot-bound continuation refusal is a distinct guarantee until probed |

| Scope | Credit | Add (est.) | Net |
|---|---:|---:|---:|
| Direct (Q1-Q3) | 274 | 42 | −232 |
| Conditional (Q4-Q6) | 340 | 60 | −280 |
| **Block core** (not a group): query source in `render-call` +12, subject placeholder +3, publish `replay-read` +1, `units` entrance moved from `neighborhood` (net +10 for the second source and contract), read-form derivation +10, schema members +8 | — | 44 | +44 |
| **All, if conditions hold** | 614 | 146 | **−468** |

Mixed functions keep zero credit, as in the audit: `neighborhood` (98, its loop moves rather than disappears),
`page-result` (138), `render-call` (183), `acquire-context-report` (54), `history-contributions` (30), the
26 source-returning renderers (their read forms become derivable, but each keeps its face), and `my.program`'s
query verbs.

## 5. Adoption order: each cut pays for itself

Every slice keeps HEAD loadable, lands its caller conversions, and carries one class regression. Root runs
the tests (lane rule). Precondition **A0**: root restarts default, so that HEAD's four-stage selection and
b195ae037 are installed (probe §0.1). Otherwise no slice can be proven.

| Slice | Change | + / − (est.) | Justified by | Proof root runs |
|---|---|---|---|---|
| **A1** | `:seon.render/query` source in `render-call`; `:seon.render/subject` placeholder; publish `seon.db/replay-read` (name for review); convert `walk/history` to a declared read + `render-call` per row | +28 / −49 | Q1 | history bytes for root identical before/after; history's read evidence reused after an unrelated write |
| **A2** | lift the per-member loop into `seon.render/units`; `neighborhood` returns acquisition units only and `page-result` calls `units`; replace `walk.clj:792-798` placeholders with `renderer-failure` faces | +25 / −12 (placeholder branch and loop wrapper) | prerequisite of A3/A4; closes the existing issue | page units equal (ids, paths, bytes) on default; a failing renderer names producer and path |
| **A3** | faults concern as a declared query; AI unit shows the derived form + rendered result, HTML the same result | +10 / −66 | Q3; the 81% prompt defect | opening for an agent with faults: one read, one result; with none: an empty unit, no source reprint |
| **A4** | agent-facing walk → `my.*` entry with explicit custody → `units`; delete `*walk-context*` | +20 / −159 | Q2 | `(my.….walk {:depth 2})` equals the old text for root; `turn.clj:4715` binding gone |
| **A5** | cursor continuation through `units` (value digest / query + basis / entity); `get_value` and `/data` call it | budget in audit R1/R3 | Astra P1-2 | same fragment for MCP and browser for one cut |
| **A6** | opening subjects through `units` | +15 / −62 | Q4 | opening entries equal as a set; demonstrations kept |
| **A7** | fold `derive-context!` into `render-call` retention | +5 / −74 | Q5 | cache hit after an unrelated write; miss after an evaluation append |
| **A8** | transcript views over declared queries | +40 / −204 | Q6 | the audit's behaviour map, then byte equality of session/outline/ledger |

A1-A4 are each under 100 added lines, and each is net negative on its own except A2, which is the enabling
move (+13 net) and pays in A3 and A4.

## 6. What must NOT merge

These are distinct guarantees, not duplicates (audit §7, plus this dig):

- **Whole-unit prompt admission** (`prompt/select` `prompt.clj:301-333`, `retained-count`) versus the floor's B.
  Admission selects saved units; it never fits their text.
- **Saved shown bytes** versus re-rendering. History units render the exact saved text; a new viewer's current
  view is labelled current (one-REPL §2.4).
- **Evaluating opening forms** versus rendering a query. The renderer derives the form. The REPL evaluates,
  records and binds the result (`result/e…`). The block never records evaluations.
- **Read-currency replay** versus declared-query execution. They share `replay-read` as an executor, not a
  purpose. Since-diffs and changed-read refresh stay in `seon.db` and the turn.
- **Walk acquisition policy** (installed refs, declared concerns, ownership, distance) is the *producer* of an
  entity's default query, not part of selection.
- **Query-work bounds** (pull width, `max-work`, `max-results`, deadlines) versus presentation B.
- **Source-code formatting** (`repl/source-text`, syntax tokens) versus value rendering.
- **Transport:** SSE packaging and deltas (`web.clj:1851-1925`), `surface-id` addressing, MCP/JSON envelopes.
- **Durable records:** evaluation facts, effect receipts, test assertion evidence, exact prompt capture
  (`context/capture-tx`), error identity/occurrences, live result objects (audit §7).
- **Agent verbs** (`seon.error/for-agent`, `my.program` queries). A declared query may call the same datalog,
  but a verb is a function the agent calls and stays one.

## 7. Open question for review (priced)

Where a declared concern's query lives. This decides how Q3/Q6 are written; the rest is unaffected.

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **A. `:seon.render/query` property on the concern attribute's schema (recommended)** | The query is data beside its schema: queryable, printable as the call, and no function is needed per concern | about +3 per concern; one property read in `declared-acquisition` | Templates are limited to read-request plus two placeholders; computed queries need B |
| B. A contracted query function named by the property, returning a read-request | Arbitrary computation, still data at the boundary | +1 function per concern; call preparation supplies its inputs | One more indirection per concern; tempts graph work back into code |
| C. No declaration: the renderer returns `{:seon.render/query …}` at render time (§3d.2 only) | Zero schema change | nothing new | The query exists only after a render; no index of what each concern reads |

## 8. Unresolved risks

- **Installed behaviour is unproven** (§0.1). Every stage claim is a HEAD-source claim.
- **Performance is unmeasured.** A query unit adds one `replay-read` (0.11-0.13 ms for the two probed reads,
  finite), but real concern queries, the per-unit floor and `units` over a page are not timed. A1 must time
  parent versus change on the page and the prompt (AGENTS.md: >20% or 50 ms blocks).
- **The subject placeholder** substitutes only in `:args` and pull arguments. A query that needs the subject
  inside `:find`/`pull` patterns must use `:in` binding. Declared templates that cannot must use option B.
- **The identity of a renderer-returned child** (§3d.2) derives from parent id + path. Two children at one path
  in one value are impossible by construction, but a renderer returning different queries across renders
  changes the child's id silently. The query value itself should join the id; this is priced at 0 and needs
  review.
- **Closure inputs and positional fit** gaps (§3c) are inherited, not solved.

## 9. Out-of-scope findings

This lane may write one file, so no issue files were created. The durable record is this document:

1. The installed default runs a two-stage selection while HEAD has four (§0.1). Root: restart and verify.
2. `*walk-context*` ambient custody (`render.clj:1812`), in scope of A4.
3. "renderer unavailable" placeholders at `walk.clj:792-798`. Existing issue:
   `docs/seon/issues/namespace-page-repeats-renderer-unavailable.md` (A2 closes it).
4. The steward-faults read dominates prompts (81%). Recorded in
   `docs/research/agent-platform/agent-context-render-pipeline-2026-09-24.md` §0.1 (A3 closes it).
5. `replay-read` pulls return bare `{:db/id}` ref leaves unless the selector asks identities (probe). Declared
   queries must ask them; a check in `units` could refuse an identity-less ref leaf by name. Unpriced.
