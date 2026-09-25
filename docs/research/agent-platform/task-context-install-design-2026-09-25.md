---
type: design
status: "Opus revision 3, following the owner's directions of 2026-09-25 (linked graph, presence of a render function decides, set-at-a-time joins) and Astra review 595048666 (ACCEPT-AFTER-FIXES, folded in, §11); awaiting Astra re-review; no source edited"
created: 2026-09-25
scope: The agent's context is the rendering of the agent entity. One walk follows references level by level with set-at-a-time index joins; each reached node renders once through its own pair; one composer orders definitions before uses and turns by time. The reads re-checked before a turn are those whose captured subjects are graph nodes. Every duplicate walker retires.
source-pin: 2d23029cb (src ranges valid there); AGENTS.md at ba41af0c0; Datahike reference-code at 4157916b
aligned-with: language-requirements-2026-09-24.md §§1.9, 1.13, 5, 5a, 7, 11, 12; query-render-building-block-2026-09-25.md (this design is its acquisition half for entity graphs); one-render-path-design-2026-09-25.md §§1-3, 5; task-context-install-design-review-2026-09-25.md (Astra, 595048666)
---

# Task context: the agent renders its linked graph

The owner, 2026-09-25: "I want the agent to render the task not as an all in one but as a linked graph that can be
recursively rendered and composed together … Turn 1 comes before turn 2, the functions that are used are explained
and the schemas referenced are defined. Not everything needs to be rendered … a dynamic system that takes any data and
if there are render functions and we follow the same reference following rules we render it just by adding a
reference to the agent's entity." And: "the presence of a render function determines skip and the other keywords are
unnecessary. If we hit a ref we can pull it." And: "a very efficient way to pull the refs … a normal query … think
about what you want to join to different entity ids, attributes or values."

**What exists.**

- The agent's opening runs `turn/system-turn` → `declared-sources` → `walk/neighborhood`.
- The walk visits one entity at a time: `acquired-tree` pulls each entity, then follows its declared concerns
  (`walk.clj:483-533`). It follows only `:db.type/ref` attributes (`walk.clj:103-122`).
- The agent pair, `render-identity-ai`, reads the database inside the renderer and emits opening source text.
- Task links are **values**: handles in `:seon.task/tasks`, symbols in `:seon.fn/calls`, keywords in the arity
  contract refs. So today's walk cannot reach a task's functions, tests or schemas at all.

**The data flow after this design.**

1. **Structure.** It is derived once per schema projection and memoized by projection identity. It holds three
   things:
   - which attributes are refs;
   - which attributes hold values naming an identity, and through which identity attribute;
   - which identity attributes' entities have a render pair.
2. **Walk.** Breadth-first from a root lookup, one level at a time. Each level runs a fixed handful of set-at-a-time
   Datalog queries binding the whole frontier, and returns `[source attribute target]` edges. A visited set
   deduplicates. It stops at depth N, a caller-overridable input.
3. **Order.** A topological sort over those edges puts definitions before uses. Ties go to the entity's first
   transaction, so turn 1 comes before turn 2. A cycle is broken by a back-reference.
4. **Render.** Each node renders once, through its own pair, as a pure function of its pulled value and its remaining
   distance. A node at the last level renders at distance 0, which is its name. A later reference to a rendered node
   renders as a back-reference.
5. **Compose.** The query+render building block's `seon.render/units` renders the ordered node list under one floor
   budget, with one AI face (the prompt) and one HTML face (the debug page).
6. **Refresh.** Before a turn, the plan re-checks the walk's own evaluation, plus every retained read whose captured
   subjects touch a graph node or are unknown.

**The principal Clojure approach.** Joins are sets bound into Datalog, and the dependency ordering is a sort over
plain edge data. Reference rules are declarations the registry already carries. No mode keyword, context-specific
code, registry, cache or second renderer is added.

## 0. Broken things first

1. **The prototype is gone.** `scratch.task-context` was never committed: `git log -S` finds only requirement text
   (b1af1d100) and an MCP note (3dbf90999), and `(find-ns 'scratch.task-context)` on default (pid 37460) is `nil`.
   The 7.7 KB / 0.3 ms figure in requirement §5a.4 is not reproducible.
2. **The task and schema rows have no render pair.** On default:
   - `:seon.task/task` and `:seon.schema/schema` declare no `:seon.render/ai`;
   - `:seon.fn/fn`, `:seon.test/test`, `:seon.ns/ns`, `:seon.turn/turn` and `:seon.agent/agent` do.

   Under the owner's rule (presence decides), today's walk would **skip the task itself** and every referenced schema.
   Both need a declared pair (§4.4).
3. **The namespace pair is a namespace dump at distance ≥ 1.** `seon.render.ns/render-ai` renders the full page at
   distance 1 (`ns.clj:750-781`; walk docstring: "distance 0 renders the name only" is a renderer convention). The
   walk reaches `:seon.ns/ns` nodes through `:seon.fn/ns` and namespace subjects: 3 of them for task 132fee. The
   namespace AI face must render doc + counts + `(dir ns)` at every distance before activation. This is
   one-render-path audit sites 16/17.
4. **Choosing refresh candidates costs about 3.3 s per system turn for root.** `latest-evaluations`
   (`turn.clj:1925-1957`) pulls every retained evaluation with all of its read evidence: 18,673 rows over 45 forms.
   It took 3,254 ms and then 3,369 ms under a concurrent test load. The same join without evidence took 3.1 ms.
5. **Two effect calls pass `read-only-evaluation?`.** `(my.turn/complete …)` and `(my.agent/done)` record no effect
   or receipt row (`turn.clj:1959-1975`). Candidate selection cannot make this safe; the producer must record the
   effect (Astra fix 2). **Fixing that is a prerequisite of T2.**
6. **A task must-read names no definition.** Task `132fee0467c8` must-reads `seon.sci.eval/definition-rows`, which
   has no row. The task writer admits dangling links, against requirement 4.1.
7. **Pre-fix evidence is still stored.** Default started before `ca17618c0` (the form-scoped sink), so `(quote 1)`
   still carries 7,286 reads.
8. **A single rules query per level is slower, not faster.** The same edges as one rule-based query per level took
   95–133 ms per task at N=2, and 2,244 ms for 132fee at N=3. Five plain queries per level took 14–71 ms (§2.2).
   The owner's "one query per level" is refined to **one query per edge family per level**: a fixed five, independent
   of the frontier size.

## 1. Current owners (file:line at 2d23029cb)

### 1a. The walkers and context builders this replaces

| Walker or builder | Where | Lines | What it walks |
|---|---|---:|---|
| Per-entity acquisition: `declared-concerns`, `connection-attributes`, `root-selector`, `shallow-entity`, `connection-observation`, `acquisition-members`, `join-membership`, `root-pull-plan`, `namespace-connections`, `acquire-entity`, `acquired-tree`, `root-acquisition`, `forward-refs` | `seon.render.walk` `:94-591` | ~498 physical | refs only, one pull per entity, concerns per schema |
| `neighborhood` render loop | `walk.clj:710-807` | 98 | renders the acquired tree; its loop moves into `units` (building block S2) |
| `ordered-episode`, plus helpers `reference-keys`, `introduced-subject?`, `form-symbols`, `explained-symbol?` | `walk.clj:817-924` | ~108 | an explain-before-use fixed point over a pull; callers are only `bootstrap/next-entry-in` (dead) and tests |
| Bootstrap opening candidates, `entries` … `next-entry` | `bootstrap.clj:204-731` | 502 | production-unreachable (program graph: test callers only) |
| `declared-sources` | `turn.clj:1870-1923` | 54 | calls `neighborhood`, then re-reads the printed source |
| Agent pair with issue openings: `render-identity-ai`, `render-id-ai`, `identity-form` | `agent.clj:246-252, 278-310` | 39 | reads the database inside a renderer |
| Issue openings | `seon.issue.opening` | 233 | its own links walk (`links`, `:39-66`) and seven candidates |
| Namespace-page walkers: `namespace-row`, `function-rows`, `own-schema-rows`, `referenced-schema-closure` | `ns.clj:56-100, 171-227` | 100 | functions of a namespace, schema closure capped at 40 |
| Ambient agent-facing walk | `render.clj:1812-1976` `seon.render/walk` | 159 | booked in the building block as Q2 |
| Saved-history composition | `walk.clj:926-974` `history` | 49 | booked in the building block as Q1 |
| Transcript acquisition adapters; `derive-context!`; the faults concern | `transcript.clj`, `web.clj:2537-2610`, `error.clj:1897-2004` | 204 / 74 / 66 | booked in the building block as Q6 / Q5 / Q3 |
| Eval driver transcript | `eval/drive.clj:288-300` | 13 | reads the capture's exact bytes instead |

### 1b. Refresh today

`system-plan` (`turn.clj:1977-2017`) takes the declared sources plus every retained evaluation that passes
`read-only-evaluation?` and has no `generated-read-fault`. It then checks each one with `db/read-evidence-current?`
(`db.clj:1139`) and `read-evidence-changes` (`db.clj:1041`).

### 1c. Declarations the registry already carries (searched before adding a key)

| Need | Existing key | Where | Use here |
|---|---|---|---|
| A value names another entity's identity | `:seon.fn/reference-to` | `seon.fn.edn:17` ("Identity attribute of the function named by a declared reference value"). Used on `:seon.schedule.task/function`; read by `fn.clj:1698` and `test.clj:388` | declare it on the value-edge attributes (§4.1) |
| An identity attribute's owning row schema | `:seon.program/row-schema` | on 46 identity attributes (`:seon.fn/sym` → `:seon.fn/fn`, …) | target entity → schema → pair presence |
| A schema's render pair | `:seon.render/ai` / `:seon.render/html` | entity schema properties | presence decides whether a node is rendered |
| Reverse relations a schema follows | `:seon.render/units` | e.g. the agent's `[:seon.agent/plan :seon.issue/_agent …]` | reverse edges only (§4.1); forward refs need no declaration |
| Owned children | `:db/isComponent`, `:seon.db/component-schema` | 137 declarations | joined through within the owner's level; never nodes |
| Depth | `:seon.render/distance` | `seon.render.edn:129`, `[:int {:min 0}]` | N, with the caller's value winning |
| Query-work bound | `:seon.config.eval.result/max-nodes` | walk caps today | the visited-node cap |

The owner's direction retires the round-3 proposal of `:content`/`:address`/`:skip` modes: no mode key is added.

## 2. REPL proof on real data (default, JVM mode, read-only, inline `let` forms)

### 2.1 The three real tasks

| Task | Subjects | Proofs | Must-read |
|---|---|---|---|
| `132fee0467c8` definitions-bypass-entry-gate | `seon.sci.eval/evaluate`, ns `seon.sci.restoration-test` | 3 deftests | `evaluate`, `definition-rows` (dangling) |
| `300aca1ad57c` tests-reaching-saturates | `seon.fn/tests-reaching`, `seon.fn/gate-set` | none | `reverse-closure`, `gate-sets` |
| `cea47b015b23` agents-can-validate | ns `seon.schema` | none | three `seon.schema` functions |

### 2.2 Set-at-a-time walk versus a per-node walk, with the same edges

**The structure.** It was derived in **1.1 ms** from `(:schema db)` and the projection:

- 123 non-component ref attributes (260 ref attributes in all);
- 56 identity attributes, of which 22 have a row schema with a pair, plus `:seon.task/id` and `:seon.schema/key`
  assumed declared (§0.2).

**Edge families per level.** There are five plain queries, each binding the frontier `[?e ...]`:

1. forward refs: `[?e ?a ?t]` with `[?a ...]` bound to the ref attributes (EAVT);
2. value references: `[?e ?a ?v] [?t ?i ?v]` with `[[?a ?i]]` bound to `[[:seon.fn/calls :seon.fn/sym]
   [:seon.fn/must-read :seon.fn/sym] [:seon.task/must-read :seon.fn/sym] [:seon.fn/keywords :seon.schema/key]]` (AVET
   on the identity side);
3. contract keys through the owned arity component: `[?e :seon.fn/arities ?ar] (or [?ar :seon.fn.arity/input-refs
   ?k] [?ar :seon.fn.arity/output-refs ?k]) [?t :seon.schema/key ?k]`;
4. declared reverse value references: `[?e :seon.task/id ?h] [?t :seon.task/tasks ?h]`;
5. render presence over the candidate targets: `[?t ?ia _]` with `[?ia ...]` bound to the rendered identity
   attributes.

**The per-node baseline** walks the same edge families for one entity at a time: `pull [*]`, then one query per
value, contract, reverse and presence check. Both walks ran on fresh `as-of` values, so Datahike's query cache could
not answer.

| Task, N=2 | Nodes | Set-at-a-time: queries / cold ms / warm ms | Per-node: queries / cold ms / warm ms |
|---|---:|---|---|
| 132fee | 134 | **10** / 182 / 100 | 3,823 / 288 / 264 |
| 300aca | 16 | **10** / 41 / 37 | 871 / 69 / 68 |
| cea47 | 11 | **10** / 35 / 36 | 483 / 80 / 76 |

- **On the committed database value** (no `as-of` merge), the first set-at-a-time run took 71 / 14 / 14 ms. The
  first ref query's plan compilation was 53 ms of the 71.
- **At N=3, 132fee** reaches 348 nodes with 15 queries: 376 ms cold on an `as-of` value, 11 ms on a repeat against
  the committed value.
- **The cost unit is the query.** Queries per level are a constant 5, against 1 + values + frontier × checks for the
  per-node walk. Work per query is the frontier's EAVT seeks plus the AVET joins it produces, bounded by the edges
  touched, never by store size. Datoms touched were not counted directly. The edge count stands in for them: 157 at
  N=2 and 686 at N=3 for 132fee.
- **`as-of` views cost** 2–3× the committed value. That matches the cost-db landing's temporal-index finding. The walk
  runs on the committed branch value.

**Why not Datahike's recursive pull.** `pull_api.cljc:340-344` (at 4157916b) recurses per entity frame, depth first,
over ref attributes only. It cannot follow a symbol, keyword or handle value to its identity. Only an AVET join can,
and the join is set-at-a-time.

### 2.3 The graph, its order and render-once

For 132fee at N=2 from the task, the walk plus the topological order took **56 ms** on the committed value:

| Node kind | Nodes |
|---|---:|
| `:seon.fn/sym` | 62 |
| `:seon.schema/key` | 65 |
| `:seon.ns/name` | 3 |
| `:seon.test/sym` | 3 |
| **Edges** | **157** |
| **Cycles broken** | **0** |
| **Edges placed use-before-definition** | **0** |

- **Ordering method.** The topological sort is Kahn's algorithm over use → definition edges. Ties go to the entity's
  earliest transaction.
- **The first twelve placed**, all definitions before their users: `seon.sci.eval/context-projection`,
  `:seon.agent/id`, `:seon.eval/outcome`, `seon.sci.eval/interpreted-arity-message`, `:seon.error/member`,
  `seon.id/evaluation`, `:seon.print/length`, `:seon.sci.admit/value`, `seon.env/environment-state`,
  `:seon.cluster.eval/source`, `:seon.sci.admit/record`, `:seon.sci.eval/request`.
- **The agent root** at N=2 (root has no task) reaches 47 nodes: 42 turns, its plan, a message, its namespace and its
  runtime. The walk's BFS order of those turns is **not** transaction order (measured `false`), so the
  earliest-transaction tie-break is load-bearing. The placed order of turns was not re-checked in this probe; it is a
  witness required at landing (§9).
- **An earlier variant graph** (the round-3 content rule, 113 nodes, 184 edges) also had 0 cycles and 0 violations.

### 2.4 Render-cache reuse across turns

A node's render key is its content digest, face, producer program identity and distance. The content digest is:

- `:seon.program/definition-digest` for functions and tests;
- the schema form for schemas;
- the pulled value for other entities.

| Span | Unchanged node digests |
|---|---|
| Root's two consecutive turns (tx 536878808 → 536878885) | **133 / 133** (112 + 11 + 10 nodes over the three tasks) |
| Across a publication (tx 536872396 → now; 1,756 of 4,935 functions changed) | 4 / 9 function nodes, 4 / 4 schemas |

Unchanged nodes re-render nothing. The composer re-emits only changed nodes and their positions.

### 2.5 Sizes at the edge of the graph

With no mode keywords, a node at the last level renders at distance 0: its name, about 40 characters. For 132fee the
62 function nodes are:

- 2 at level 1: `evaluate` itself (29,253 characters of source, whole, req 5.8) and the proof helper;
- 60 at level 2, at about 2.5 KB of names in all.

That is why no taught-vocabulary exclusion is needed any more: harness and `seon.db` functions reached as callees are
names at the edge. Round 2's measured hop-3 name growth (5–7× per level) is why the default N stays small (§4.3).

### 2.6 Read counts for refresh

| Rule | Evidence rows read | Re-checked | Note |
|---|---:|---:|---|
| Today | 18,673 | 8 reads in 2 evaluations | the two effect calls, §0.5 |
| Form constants (round 2) | 0 | 0 for 132fee | **refuted** by Astra fix 1: it misses reads through `result/e…`, bindings and empty results |
| Captured-evidence subjects against graph nodes (this design) | 18,673 today; each form's own reads after `ca17618c0` | 60 in 2 evaluations for 132fee, plus 682 unattributable rows kept conservatively | §5 |

## 3. Root's proposed shape: verified, refined or refuted

| # | Proposal | Verdict and evidence |
|---|---|---|
| 1 | The context is the rendering of the agent entity; attaching a reference puts it in | **Verified.** `:seon.agent/task` (a handle value, `:seon.fn/reference-to :seon.task/id`) is the only new link. Anything else attached by ref, or by a value declared with `reference-to`, is walked, and it appears if its schema has a pair. |
| 2 | WALK / RENDER / COMPOSE | **Verified, one refinement.** The walk reads one held database value with 5 queries per level, not a single query (§0.8). Renderers are pure over the node value (the agent pair's database read retires, Astra fix 7). The composer is `units`. |
| 3 | `:content` / `:address` / `:skip` modes | **Retired by the owner.** Presence of a pair decides skip. Distance decides content versus name: the last level renders at distance 0. Reverse edges still need the existing `:seon.render/units` declaration, because a walker cannot follow every reverse reference safely: the agent has 42 turns, and `:seon.fn/calls` has thousands of reverse holders. |
| 4 | Order: topological, then by transaction; cycles broken by address | **Verified.** 0 violations and 0 cycles on 157 and 184 real edges (§2.3). The transaction tie-break is required: BFS order of turns is not transaction order. |
| 5 | Each node renders once | **Verified.** The visited set is the dedupe. A later reference renders as a back-reference: the node's id plus "above". |
| 6 | Cost follows the graph; nodes cached by digest | **Verified.** O(levels × 5) queries, each bounded by edges touched. 133/133 digests were reused across consecutive turns. The cache is `render-call`'s existing retention with the content digest added to its key (building block §3f). No new cache. |
| 7 | One block for all consumers | **Verified.** The same structure, walk, order and composer serve the agent context, a task, the namespace page (namespace → reverse `:seon.fn/ns` declared in units) and an error (error → `:seon.error/operation` value → function). §7 names the retired walkers. |

**The owner's three earlier alternatives** (round 2) resolve as follows:

- "A render function for the agent that creates its context" becomes the agent's **pair**, over nodes the walk
  acquired. The renderer does no acquisition.
- "The task accumulates data on the turn" stays rejected: `task-delta` retracts removed task entities
  (`issue.clj:634`), and runtime facts are separate entities (req 2.8, 7.7).
- "The agent attaches the task and the context is a render on that" is this design.

## 4. Design

### 4.1 Reference rules are declarations (edge-to-owner table, Astra fix 3)

Forward refs are followed automatically. A value edge is followed when its attribute declares `:seon.fn/reference-to`
(the existing key, whose description is widened from "function" to "identity", §10 Q1). A reverse edge is followed
when the holder schema lists it in `:seon.render/units`.

| Edge | Stored as | Declaration to add | Status |
|---|---|---|---|
| agent → task | `:seon.agent/task` handle (new) | `{:seon.fn/reference-to :seon.task/id}` | new attribute, 2 lines |
| task → subjects and proofs | `:seon.task/tasks` values on fn, ns and test rows | task schema `:seon.render/units [:seon.task/_tasks]`; `:seon.task/tasks {:seon.fn/reference-to :seon.task/id}` | 2 lines |
| task → must-read | `:seon.task/must-read` symbols | `reference-to :seon.fn/sym` | 1 line |
| fn → callees | `:seon.fn/calls` symbols | `reference-to :seon.fn/sym` | 1 line |
| fn → must-read / see-also | `:seon.fn/must-read`, `:seon.fn/see-also` | `reference-to :seon.fn/sym` | 2 lines |
| fn → contract schemas | `:seon.fn/arities` component → `:seon.fn.arity/input-refs` / `output-refs` keywords | `reference-to :seon.schema/key` on both arity attributes; the component is joined through | 2 lines |
| test → keywords used | `:seon.fn/keywords` | `reference-to :seon.schema/key`, matching only registered keys, by the AVET join itself | 1 line |
| fn → namespace | `:seon.fn/ns` ref | none (forward ref) | installed |
| namespace → its functions | reverse `:seon.fn/ns` | namespace schema units `[:seon.fn/_ns]`, rendered at distance 0 (names) | 1 line |
| fn → callers | reverse `:seon.fn/calls` | **not declared**: callers are fetched on demand with `(my.program/callers {:seon.program/subject 'f})`, the installed request shape | none |
| fn → reaching tests | derived gate set | **not an edge**: `(my.program/tests-reaching {:seon.program/subject 'f})` on demand | none |
| fn → open tasks | forward `:seon.task/tasks` | covered by the `reference-to` above | — |
| agent → turns | `:seon.agent/runtime` component → `:seon.runtime/turns` | agent units already list the runtime; turns render their saved bytes (building block Q1) | installed |
| agent → trigger message | `:seon.turn/trigger` ref from the open turn | forward ref | installed |
| render referrers, config documents, prior art, literal lookups inside proofs | — | **data gaps**: no attribute holds them; named, not walked | gap |

**Precedence (Astra fix 3).** Subjects and must-reads are level-1 or level-2 nodes, reached before any incidental
callee, so they always render at distance ≥ 1. A taught tool reached only as a callee is a name at the edge.

### 4.2 The walk

```clojure
(seon.render.walk/graph {:seon.db/db db :seon.render.walk/lookup [:seon.agent/id "root"]
                         :seon.render/distance 3 :seon.sci.admit/caps caps})
;=> #:seon.render.walk{:nodes [{:seon.render.walk/lookup [:seon.task/id "132fee0467c8"] :seon.render.walk/level 1} …]
;                      :edges [[source-eid attribute target-eid] …]
;                      :order [eid …]                     ; §4.3
;                      :cut   {…}}                        ; present only when a bound was reached
;  | :seon.render.walk/elided-error | :seon.db/invalid-read-error
```

- **Structure** (memoized on projection identity): the ref attributes, the `[[attribute identity-attribute]]` value
  edges, reverse units per schema, component attributes, and the rendered identity attributes. It is O(schema), taking
  1.1 ms, once per projection.
- **One level** runs the five §2.2 queries through `seon.db/q`, so evidence capture and refusals apply. Each binds
  the frontier as `[?e ...]`, and reverse edges bind only the frontier entities whose schema declares them. The next
  frontier is the targets not yet visited that passed the presence check.
- **Bounds (Astra fix 4).**
  - Visited nodes are capped by `max-nodes`, and edges read per level by `max-collection` × frontier.
  - Each query keeps its own deadline under the existing database bound.
  - At exhaustion the value carries `:cut` naming the bound, level, visited count, the frontier not expanded and the
    requery form. Counts are never claimed for work not done.
- **Entities are pulled once.** Hop 1 content (source, doc, spec, schema form) is pulled with one `pull-many` per
  node kind, over the nodes that render at distance ≥ 1, using the function-face selector. Nodes at distance 0 need
  only their identity, already bound by the join.
- **Replaces** `acquired-tree`/`acquire-entity`/`acquisition-members` and their helpers (§1a). `neighborhood`
  becomes `units` over `graph` (building block S2), so the page keeps working through one walker.

### 4.3 N, order and render-once

- **N is the number of levels from the root.** A node at level L renders at distance N − L:
  - N=0: the root alone;
  - N=1: the agent and its task's name;
  - N=2: the task rendered, with its subjects, proofs and must-reads as names;
  - **N=3 (default for the agent context):** subjects, proofs and must-reads rendered, and their callees and
    contract schemas as names.
- **The default lives in the function's `:or`,** and the caller's value wins.
- **Measured N=3 from the agent** equals N=2 from the task: 134 / 16 / 11 nodes in 14–182 ms (§2.2).
- **Order.** Kahn's algorithm over use → definition edges gives definitions first. Ties go to the earliest
  transaction, then the lookup. A remaining cycle is broken at its earliest-transaction member, which renders as a
  back-reference where it is used. It is O(V + E log V).
- **Render-once.** The first placement renders the node. Every later edge into it renders `;; ↑ <id>`. Time-ordered
  entities (turns, messages, errors) have no dependency edges among themselves, so the tie-break orders them.

### 4.4 Render: pure node pairs

Each node renders through the ladder (value key → viewer's namespace function → schema pair → floor) over its pulled
value plus `:seon.render/distance`. No renderer reads the database.

- **Declared pairs to add:**
  - `:seon.task/task`: a task renderer that shows the intent, the handle and the callable `(seon.issue/task-edges
    …)` form, in about 20 lines;
  - `:seon.schema/schema`: the installed `seon.render.ns/schema-form` (`ns.clj:744`).
- **The agent pair** is rewritten pure, in about 12 lines: identity, the task handle and the turns-left frame fact
  from its supplied value. `render-identity-ai`'s issue query and `render-situation-ai`'s call to
  `bootstrap/situation` leave the renderer (Astra fix 7).
- **Situation facts:**
  - the trigger message is an edge (`:seon.turn/trigger`);
  - turns left stays in the prompt frame;
  - the unread count is an address, `(seon.turn/unanswered-triggers …)` on demand.
- **The namespace AI face** renders doc, counts and `(dir ns)` at every distance (§0.3).

### 4.5 Compose and the opening

- `units` receives the `graph` value's ordered nodes as its `:seon.render.walk/units` source (building block §3a).
  It opens one floor allocation B, renders each node once and returns rendered units with occurrence ids = the node
  path.
- The HTML face is the same unit tree: the owner's debug page.
- **The opening is one form:** `(my.render/walk {:seon.render.walk/lookup [:seon.agent/id "root"]})`. That is the
  building block's Q2 custody adapter over `graph` + `units`, with defaults supplied for the database and the viewer.
- The REPL evaluates it once, records it and prints it. Its read evidence is exactly the walk's queries, since
  `ca17618c0` scopes the sink to the evaluation and its shown result.
- `(help)` stays the first opening form.

### 4.6 Activation order (Astra fixes 5, 6, 8)

1. **W1, unused.**
   - `graph` (structure, levels, order, cut), with its schema and the declarations of §4.1 and §4.4.
   - A focused regression over the three real tasks, asserting three things: the node kinds; zero use-before-definition
     edges; turns in transaction order.
   - Nothing calls it yet.
2. **W2, one activation**, after building block S2/S3 supply compositions and the shared floor.
   - It lands in one loadable commit: `declared-sources` returns the two forms; the agent pair goes pure; the
     namespace AI face is fixed; `neighborhood` delegates to `units` over `graph`.
   - Prerequisites:
     - a standing root task;
     - `:seon.agent/task` assigned to every existing agent;
     - one migration boundary for unmigrated issue workers, who keep `seon.issue.opening` until T3.
   - Witnesses:
     - a context, an identity-only agent and an agent nested in a message each select their own pair;
     - the same value renders both faces;
     - historical bytes are unchanged.
3. **T0** (independent, any time): delete the production-unreachable bootstrap candidates and `ordered-episode` with
   their helpers. Six named tests retire with them (`bootstrap_test.clj:83, :162, :223, :279, :352, :373`). T0 has
   exclusive credit, so the building block drops Q4's 62 lines.
4. **T2** (after §0.5's producer fix): the refresh of §5.
5. **T3** (after 7.11): delete `seon.issue.opening`, the require at `agent.clj:82` and the caller at `:301`.
6. **C** (after page parity is proved): delete the per-entity acquisition (§1a, about 498 lines) and the
   namespace-page walkers (100).

## 5. Refresh: reads whose captured subjects are graph nodes (Astra fix 1 applied)

The subject of a read comes from its **captured evidence**, the authority (`db.clj:892-942`):

- the pattern attributes and values of each dependency-plan source;
- the query arguments of its `:seon.db/read-request`;
- the eid or lookup of its pull.

A retained read is re-checked when any of these hold:

- it names a graph node, by eid, identity value or lookup;
- its patterns name an attribute of a node's schema with no value bound, which is how an empty result that a new
  insertion could fill is recognized;
- its evidence is unknown: an `:all` plan or no attributable subject. These are kept conservatively. 682 of root's
  18,673 rows are of this kind today.

It is left alone only when its evidence **positively** names subjects that are all outside the graph. Such a read is
not re-run; its shown text stays as that turn's observation, and the agent re-runs it on demand.

**Context first.** `system-turn` evaluates the opening's walk form first. It then builds the plan against that one
evaluation's node set. The walk runs once, and the plan never selects against stale membership. After a restart
without a live value, the node set comes from the recorded evaluation's evidence, never from parsing shown text.

**Cost.** The evidence pull stays in the database owner. Selection first queries evaluations that have evidence and
no receipt or effect, keeping `read-only-evaluation?`'s non-empty-evidence concern as a join. It pulls evidence only
for those. After `ca17618c0`, each form carries only its own reads, so the retained evidence stops growing with
platform work. The target is the whole plan under 50 ms for root's history once pre-fix rows age out; parent-versus-
change timing is required at landing.

## 6. Agents without a task

- **An absent task** makes the task edge absent. The walk still renders the agent: identity, turns and trigger at
  N=3. The composer ends with the declared `:seon.task/no-task-error`, which names the member `:seon.agent/task`, the
  expected handle, and the call that lists open tasks.
- **A dangling handle** is a distinct `:seon.agent/dangling-task-error` at the walk. `task-findings`' symbol-keyed
  schema is not coerced (Astra fix 6).
- **An absent agent** is today's `agent-not-found` refusal.
- **There is no namespace fallback.** Root gets a **standing root task** (`resources/seon/tasks/seon.cluster.edn`)
  whose must-reads include the status, faults and agents functions. Current observations stay one call away, and
  notifications keep their delivery path.
- **MCP** reaches this through the shared agent/REPL entrance, once branch acquisition carries an agent identity. That
  is a one-REPL prerequisite, named but not priced here (Astra fix 6).

## 7. Ledger

| Slice | Change | Source + | Source − | Declarations |
|---|---|---:|---:|---:|
| T0 | bootstrap candidates (502), `ordered-episode` and its helpers (~108) | 0 | 610 | −8 est. (`seon.bootstrap.edn:2-8`) |
| W1 | `graph`: structure (~15), level queries (~25), order and render-once (~20), cut (~10); the task renderer (~20) | 90 | 0 | +32: 11 `reference-to` or units lines, the `:seon.agent/task` attribute 2, the graph value schema 8, the no-task and dangling-task errors 11 |
| W2 | opening forms (~12), pure agent pair (~12), namespace face at every distance (~8), `neighborhood` → `units` over `graph` (~10), context-first stage (~10); deletes the `declared-sources` walk and the renderer-side reads | 52 | 93 + 35 (`render-situation-ai`) | 0 |
| T2 | evidence-subject refresh (§5) | 60 | 74 | 0 |
| T3 | `seon.issue.opening` (conditional, 7.11) | 5 | 233 | −1 |
| C | per-entity acquisition (~498) and namespace-page walkers (100), after parity | 10 | 598 | 0 |
| **Total** | | **217** | **1,643** | **+23** |

- **Net.** Source is **−1,426** if every conditional slice lands, and −711 for T0 + W1 + W2 + T2. Regressions are
  about +150 lines. The retired bootstrap and history tests are credited when they land.
- **Size bounds.** W1 is at 90 added lines and W2 at 52; every slice is under 100 added source lines. W1's
  declarations are priced separately.
- **Booked in the building block, not recounted:** Q1 (`history`, 49), Q2 (`seon.render/walk`, 159), Q3 (faults,
  66), Q5 (`derive-context!`, 74), Q6 (transcript adapters, 204). Q4's 62 lines move to T0 here.

## 8. Simplest alternative

**Keep the per-entity walk and add value-edge following inside `acquired-tree`.** That is about +30 lines.

- It works for small tasks.
- It issues 483–3,823 queries per context (§2.2), 1 + values + checks per node. Its cost grows with the frontier's
  row sizes.
- It leaves the namespace page's schema-closure walker and `ordered-episode` as separate orderers.

**Rejected** on the measured query counts. The set-at-a-time walk is roughly the same size and replaces three
walkers.

## 9. Required witnesses at landing

1. The three real tasks' node kinds and counts, and zero use-before-definition edges.
2. Turns placed in transaction order. BFS order alone fails, measured.
3. A cycle is rendered once, with its back-reference.
4. Missing pairs are skipped. A task without a pair is a named failure, not a silent skip, because it is the context
   root.
5. Cache: with no program change between two turns every node is a hit, and a publication re-renders only the changed
   digests.
6. Refresh:
   - an empty-result read is re-checked after an insertion into a node schema's attribute;
   - an `:all` read is kept;
   - a read positively naming only non-node subjects is not re-run;
   - an effect call is never re-run (after §0.5's fix).
7. Parent-versus-change timing of `graph` + `units` and of `system-plan`, the queries per level, and memory, at
   N=1/2/3 and on a high-degree root. The high-degree root is `evaluate`, with its 107 calls.

## 10. Open questions for the owner (priced; simplest viable first)

1. **The value-edge key.**
   - **A (recommended):** widen `:seon.fn/reference-to`'s description to "identity attribute named by a reference
     value"; 1 line; its two readers (`fn.clj:1698`, `test.clj:388`) keep their meaning for `:seon.fn/sym`.
   - **B:** a new `:seon.db/reference-to` plus migrating one declaration and two readers; about 1 hour; the cleaner
     name.
   - **C:** derive value edges by matching attribute and identity value types; 0 declarations, but it guesses: a
     symbol attribute is not always a reference.
2. **Reverse edges.**
   - **A (recommended):** declared in the existing `:seon.render/units`; about 3 lines now.
   - **B:** follow every reverse ref and reverse value; 0 declarations, but 42 turns, thousands of `:seon.fn/calls`
     holders, and a context bounded only by `max-nodes`.
   - **C:** no reverse edges; this loses the task's subjects and proofs.
3. **Default N for the agent context.**
   - **A (recommended):** N=3 from the agent (§4.3).
   - **B:** N=2; the task's references become names only.
   - **C:** N=4; 348 nodes for 132fee and 376 ms cold on `as-of` (§2.2).
4. **Taskless root.**
   - **A (recommended):** a standing root task (5 EDN lines).
   - **B:** no-task error only; root must ask itself.
   - **C:** refuse the turn; this stops root.

## 11. Astra review 595048666: each fix, applied or superseded

| Fix | Status | How |
|---|---|---|
| 1 Refresh correctness | **Applied** | Subjects come from captured evidence; unknown and `:all` reads are kept; empty results are covered by node-schema attributes; a context-first stage evaluates the walk once; the form-constant rule is withdrawn (§5, §2.6) |
| 2 Effect replay | **Applied** | The claim that selection contains it is removed; the producer fix is T2's prerequisite (§0.5) |
| 3 Reference families, precedence, N | **Applied** for the edge table (with data gaps named) and for N=0–3 (§4.1, §4.3). **Superseded** for the taught-tool rule and its precedence: under the owner's model, subjects and must-reads are reached at levels 1–2 and render with distance left, while incidental callees are names at the edge. No exclusion list remains (§2.5) |
| 4 Traversal policy and work bounds | **Superseded** for the separate task-view walker: there is no task-specific traversal; the one generic walk follows declared value edges. **Applied** for the bounds: `max-nodes`, per-query deadlines and a named cut, with timings at N=2/3 and a high-degree root required (§4.2, §9.7) |
| 5 Activate one declared value with both faces | **Applied** | W1 is unused; W2 is one loadable activation after building block S2/S3, with selection witnesses for a context, an identity-only agent and an agent nested in a message (§4.6) |
| 6 Assignment, migration, MCP | **Applied** | A standing root task and assignment of every agent come before W2; absent agent, absent task and dangling handle are distinct; unmigrated issue workers keep their opening until T3; MCP is named as a one-REPL prerequisite (§6) |
| 7 Renderer-side reads; callable expansions | **Applied** | The situation and identity reads leave the renderers; turns-left stays in the frame and the trigger is an edge; expansions use `{:seon.program/subject 'f}` (§4.1, §4.4) |
| 8 Ledger ownership | **Applied** | T0 has exclusive credit (Q4's 62 lines move here); the six affected tests are named; T3 removes the require at `agent.clj:82` and the caller at `:301`; the declarations are priced (§7) |

**Parallel research lane.** `docs/research/agent-platform/render-graph-research-2026-09-25.md` did not exist when
this revision was committed (`ls` at f2adbfa00). Its measurements are not adopted here. When it lands, root should
reconcile its numbers against §§2.2–2.4 before Astra's re-review.

## 12. Unresolved risks and out-of-scope findings

This lane writes one file, so root files these. Existing issues were not searched for 1–3.

1. **Effect calls pass `read-only-evaluation?`** (§0.5). Suggested issue:
   `docs/seon/issues/effect-calls-pass-the-read-only-refresh-test.md`.
2. **The task writer admits a dangling must-read** (§0.6). Suggested issue:
   `docs/seon/issues/task-must-read-links-are-not-resolved.md`.
3. **`latest-evaluations` pulls all history evidence** (§0.4), over 1 s per system turn until T2. Suggested issue:
   `docs/seon/issues/system-plan-pulls-every-retained-read.md`.
4. **The namespace AI face dumps at distance ≥ 1** (§0.3). This is one-render-path sites 16/17, and it must land
   before W2.
5. **The probes used `datahike.api/q` directly** in an inline form. Production goes through `seon.db/q`, whose
   evidence and decoding cost on these queries is unmeasured.
6. **Timings are single samples** on a default under concurrent test load. Nothing is installed.
7. **This design depends on `ca17618c0` being loaded;** default predates it. **RESET NEEDED: no.**
