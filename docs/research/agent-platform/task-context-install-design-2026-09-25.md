---
type: design
status: "Opus revision 4, amended for the owner's ruling that renderers may read the supplied database value (§4.4): owner answers of 2026-09-25 (standing root task; references become real refs; the debug page at a selectable N comes first; evaluate deleting :seon.render/units) and Astra round-3 review 67a0358ab (seven corrections) folded in place; awaiting Astra re-review; no source edited"
created: 2026-09-25
scope: The agent's context is the rendering of the agent entity. One walk follows references level by level with set-at-a-time index joins, and presence of a render pair decides inclusion. Nodes are ordered definitions-first and chronologically. Each node renders once through its own pair, reading the supplied database value when it needs to. The reads re-checked before a turn are those whose captured subjects touch the graph or are unknown. Slice 1 is the walk plus the owner's debug page.
source-pin: 2d23029cb, with Astra's recounts at dc4f71d67 (the counted files were unchanged at 54c550e4c); Datahike 4157916b; Malli befcd302a
aligned-with: language-requirements-2026-09-24.md §§1.9, 1.13, 5, 5a, 7, 11, 12; query-render-building-block-2026-09-25.md (this design is its acquisition half for entity graphs); one-render-path-design-2026-09-25.md §§1-3, 5; reviews 595048666 and 67a0358ab
depends-on: design-references-as-refs-opus (refs replace the interim value joins, §4.1)
---

# Task context: the agent renders its linked graph

## What the owner asked for

The owner, 2026-09-25:

- "render the task not as an all in one but as a linked graph that can be recursively rendered and composed
  together … Turn 1 comes before turn 2, the functions that are used are explained and the schemas referenced are
  defined. Not everything needs to be rendered … we render it just by adding a reference to the agent's entity";
- "the presence of a render function determines skip … If we hit a ref we can pull it";
- walk the references with "a normal query" that joins ids, attributes and values;
- store references as real refs, so they validate and render clearly;
- a standing root task;
- **to see N**: first, a debug page with the AI and HTML faces side by side at a selectable N;
- and a question: why `:seon.render/units` exists at all.

## What exists

- The opening runs `turn/system-turn` → `declared-sources` → `walk/neighborhood`.
- That walk takes one entity at a time: `acquired-tree` pulls each entity and then follows its declared concerns
  (`walk.clj:483-533`). It follows only ref attributes (`walk.clj:103-122`), so it never reaches a task's
  functions, tests or schemas. Those links are stored as values: handles, symbols and keywords.
- Renderers read the database themselves: agent AI and HTML, namespace AI and HTML. That is lawful (owner 2026-09-25, §4.4). The defects are what they read (§0.3).
- The refresh before a turn re-checks every retained read-only evaluation.

## Data flow after this design

1. **Structure.** Derived once per schema projection and memoized by projection identity. It holds:
   - the ref attributes, followed forward through EAVT and in reverse through VAET;
   - the interim value edges, until the references-as-refs lane converts them;
   - the component attributes;
   - the identity attributes whose row schema declares a render pair.
2. **Walk.** Breadth-first from a root lookup, one level at a time, one set-at-a-time query per edge family,
   binding the whole frontier. It returns every `[source attribute target]` edge. The visited set deduplicates
   expansion but keeps dependency evidence. It stops at depth N, an input the caller can override.
3. **Order.** Definition edges are sorted definitions-first, and turns follow `:seon.turn/opened-tx`. Membership
   edges carry no order. A cycle renders once, with a back-reference.
4. **Render.** Each node renders once through its own pair. The pair is handed the node's lookup, its remaining distance, and the stable database value as a contract-declared default, and it may read that value.
5. **Compose.** `seon.render/units` renders the complete ordered list under one floor budget. The AI face is the
   prompt; the HTML face is the debug page.
6. **Refresh.** Retained reads are selected against the membership of the graph just acquired.

**The principal Clojure approach.** Sets are bound into Datalog, the order is a sort over plain edge data, and the
declarations are ones the registry already has. No mode keyword, registry, cache or second renderer is added.

## 0. Broken things first

1. **The prototype is gone.** `scratch.task-context` was never committed, and `(find-ns 'scratch.task-context)` on
   default (pid 37460) returns `nil`. The 7.7 KB / 0.3 ms figure in requirement §5a.4 is not reproducible.
2. **Task and schema rows have no render pair.** On default, `:seon.task/task` and `:seon.schema/schema` declare no
   `:seon.render/ai`, while `:seon.fn/fn`, `:seon.test/test`, `:seon.ns/ns`, `:seon.turn/turn` and
   `:seon.agent/agent` do. Under the presence rule, every schema would be skipped, and so would the task wherever it
   is not the root.
   - `seon.render.ns/schema-form` is **not** a schema renderer. It returns a `(doc <key>)` form (`ns.clj:726-730`,
     Astra correction 3). Real pairs are slice S2.
3. **Two renderers read the wrong things, not the database as such.** Reading the supplied database value is lawful
   (owner 2026-09-25, §4.4). The defects are these:
   - The namespace AI and HTML faces call `render-data`, which acquires every function and schema of the namespace
     and prints the whole page at distance ≥ 1 (`ns.clj:349-386, :732-773`). That is a namespace dump, and it
     duplicates what the walk already reached.
   - The agent's AI face re-derives the opening by walking concerns and issues inside the renderer. Graph traversal
     belongs to the walk, for cost and ordering (§4.4), not because reading is banned.
   - The agent's HTML face (`agent.clj:312-350`) pulls identity and cluster status. That is lawful and stays.
   - Neither the dump nor the duplicated traversal is fixed on HEAD.
4. **Choosing refresh candidates costs about 3.3 s per system turn for root.** `latest-evaluations`
   (`turn.clj:1925-1957`) pulls all 18,673 read-evidence rows it retains. It took 3,254 ms and then 3,369 ms, under
   test load. The same query without evidence took 3.1 ms.
5. **Effect calls pass `read-only-evaluation?`.** `(my.turn/complete …)` and `(my.agent/done)` record no effect row.
   Fixing that producer comes before any change to refresh selection (T2).
6. **A task must-read names no definition.** Task `132fee0467c8` must-reads `seon.sci.eval/definition-rows`, which
   has no row. The task writer accepts dangling links, against requirement 4.1.
7. **Pre-fix evidence is still stored.** Default predates `ca17618c0`, so `(quote 1)` still carries 7,286 reads.
8. **Deriving value edges by schema name is refuted.** I matched each attribute's named value schema against the
   named schema of an identity attribute. That produced **289** candidate edges, nearly all false: every ref shares
   `:seon.db/ref`, and every blob digest shares `:seon.blob/digest`. Contract keys, `:seon.fn/keywords` and
   `:seon.schema/references` are plain `:qualified-keyword`. Value edges must therefore be declared, or stored as
   refs (§4.1).
9. **One rules query per level is slower.** It took 95–133 ms per task at N=2 and 2,244 ms for task 132fee at N=3.
   Plain queries, one per edge family, took 14–71 ms (§2.2).

## 1. Current owners (file:line at 2d23029cb; Astra's comparable spans at dc4f71d67)

### 1a. The walkers and context builders this replaces

| Walker or builder | Where | Comparable lines | Fate |
|---|---|---:|---|
| Per-entity acquisition (`declared-concerns` … `forward-refs`) | `walk.clj:94-591` | 482 | slice C, after its production callers `web.clj:2021-2030, :2043-2058`, the `render-data` users and the root-pull, walk, entity-pair and schedule tests are converted to `graph` |
| `neighborhood`'s render loop | `walk.clj:710-807` | (98, moved) | moves into `units` (building block S2); no credit |
| `ordered-episode` and its helpers | `walk.clj:817-924` | 100 | T0; callers are `bootstrap/next-entry-in` (dead) and `test/seon/render/episode_test.clj:42-47` plus the history tests |
| Bootstrap opening candidates | `bootstrap.clj:204-731` | 502 | T0; six `bootstrap_test` cases (`:83, :162, :223, :279, :352, :373`) |
| `declared-sources`, agent identity AI, situation AI | `turn.clj:1870-1923`; `agent.clj:246-310, :396-430` | 128 | S3 |
| `latest-evaluations` + `system-plan` | `turn.clj:1925-2017` | 74 | T2, rewritten |
| `seon.issue.opening` | whole file | 233 | T3, after the issue-to-task migration (requirement 7.11) |
| Namespace-page walkers | `ns.clj:59-103, :174-230` | 100 | C, with `render-data`'s conversion |
| `:seon.render/units` readers | `walk.clj:94-101`; `web.clj:694-715, :1682-1719` | 68 | U (§6) |
| Booked in the building block | Q1 `history` 49; Q2 `seon.render/walk` 159; Q3 faults 66; Q5 `derive-context!` 74; Q6 transcript adapters 204 | — | not recounted here. Q4's 62 lines belong to T0 here, and **root must strike Q4 and S5's bootstrap work from the building block** |

### 1b. Declarations the registry already has (searched before adding a key)

| Need | Existing key | Note |
|---|---|---|
| A value names an identity | `:seon.fn/reference-to` (`seon.fn.edn:17`) | Declared on a **ref** attribute today (`seon.schedule.task.edn:3`). Its reader `fn.clj:1692-1711` treats the target as an eid, and `test.clj:388` uses it for reach invalidation. It is **not reused** for value edges (§4.1) |
| An identity's owning schema | `:seon.program/row-schema` | used to test whether a target has a pair |
| Render pair | `:seon.render/ai`, `:seon.render/html` | presence decides inclusion |
| Components | `:db/isComponent`, `:seon.db/component-schema` | §4.1 component rule |
| Depth | `:seon.render/distance` (`seon.render.edn:129`) | N; the caller's value wins |
| Query-work bounds | Datahike `:max-work`, `:max-results`, `:max-result-weight` through `seon.db/q` (`db.clj:2404-2455`; Datahike `query.cljc:4596, :4652`); mapped from caps as in `ns.clj:40-50` | §4.2 |
| Turn chronology | `:seon.turn/opened-tx` (`seon.turn.edn:13`) | §4.3 |
| Schema → schema references | `:seon.schema/references` (`seon.schema.edn:83`) | an existing relation, now walked |

## 2. REPL proof on real data (default, JVM mode, read-only, inline `let` forms)

### 2.1 The three real tasks

| Task | Subjects | Proofs | Must-read |
|---|---|---|---|
| `132fee0467c8` | `seon.sci.eval/evaluate`, ns `seon.sci.restoration-test` | 3 deftests | `evaluate`, `definition-rows` (dangling) |
| `300aca1ad57c` | `seon.fn/tests-reaching`, `seon.fn/gate-set` | none | `reverse-closure`, `gate-sets` |
| `cea47b015b23` | ns `seon.schema` | none | three `seon.schema` functions |

### 2.2 Set-at-a-time versus per-node, same edges, N=2 from the task

**The prototype edge families.** These were five queries per level, each binding `[?e ...]`:

1. forward refs;
2. interim value joins for `:seon.fn/calls`, `:seon.fn/must-read`, `:seon.task/must-read` and `:seon.fn/keywords`;
3. contract keys through the arity component;
4. reverse task handles;
5. render presence.

This is a **prototype subset of §4.2**, as Astra's correction 1 notes: it lacks ordinary reverse refs,
`:seon.fn/see-also`, forward `:seon.task/tasks` and `:seon.schema/references`.

**Timings.** Both walks ran on fresh `as-of` values, so Datahike's query cache could not answer.

| Task | Nodes | Set-at-a-time: queries / cold / warm | Per-node: queries / cold / warm |
|---|---:|---|---|
| 132fee | 134 | **10** / 182 ms / 100 ms | 3,823 / 288 ms / 264 ms |
| 300aca | 16 | **10** / 41 / 37 | 871 / 69 / 68 |
| cea47 | 11 | **10** / 35 / 36 | 483 / 80 / 76 |

- On the committed database value, the first run took 71 / 14 / 14 ms. Of 132fee's 71 ms, 53 ms was the first
  query's plan compilation.
- At N=3, 132fee reaches 348 nodes in 15 queries: 376 ms cold on an `as-of` value.
- Deriving the structure took 1.1 ms. It found 123 non-component ref attributes (260 in all), 56 identity
  attributes, and 22 identity attributes that have a render pair.

**Cost caveat (Astra correction 1).** Datahike uses AVET point seeks only when one upstream relation binds both
attribute and value, every attribute is indexed, and the pair set passes its selectivity threshold
(`query/execute.cljc:458-486`). Otherwise it falls back to scanning and filtering (`:548-587`). "Bounded by edges
touched, never by store size" is therefore **not established**. Slice S1 records each final query's plan and seeks.
Datoms touched were not counted; edges stand in for them: 157 at N=2 and 686 at N=3.

**Why not recursive pull.** `pull_api.cljc:340-344` recurses one entity frame at a time, over ref attributes only.

### 2.3 The prototype graph and its order

For 132fee at N=2, the walk plus the sort took 56 ms. It reached 62 functions, 65 schemas, 3 namespaces and 3 tests,
over 157 edges: 0 cycles, and no use placed before its definition. The sort placed definitions first, with ties
broken by earliest transaction.

That result does **not** validate the final ordering (Astra correction 2):

- the final edge set adds two-way membership edges, such as task ↔ subject and namespace ↔ function;
- the tie-break must be `:seon.turn/opened-tx`, not the earliest surviving datom;
- the root agent's graph at N=2 (47 nodes, 42 of them turns) was not placed and checked. The walk's own order of those
  turns is not transaction order (measured `false`).

§4.3 defines the final relation; its witnesses are in §9.

### 2.4 Reverse refs into the root agent (for the `:seon.render/units` question)

One VAET query with the attribute unbound took **202 ms**. It found 96 holders over 13 attributes:

| Attribute | Holders | Attribute | Holders |
|---|---:|---|---:|
| `:seon.turn/agent` | 42 | `:seon.message/to` | 17 |
| `:seon.message/from` | 16 | `:seon.error.occurrence/agent` | 4 |
| `:seon.schedule.task/owner` | 3 | `:seon.schedule.fire/agent` | 2 |
| `:seon.maintenance.request/agent` | 2 | `:my.note/agent` | 2 |
| `:seon.db/user` | 2 | `:seon.runtime/agent` | 1 |
| `:seon.ns/steward` | 1 | `:my.plan/agent` | 1 |
| `:seon.config/agent` | 1 | | |

- **Bind the attribute.** An unbound attribute scans. The walk binds the ref-attribute set, giving one VAET seek per
  attribute.
- **Presence was undercounted.** A presence check through `:seon.program/row-schema` found **0** rendered holders.
  `:seon.turn/id` and `:seon.message/id` name no row schema with a pair, although the turn and message schemas have
  pairs. Presence must therefore be decided by the target entity's owning schema (one-render-path §1, rule 3), not
  only by `row-schema`. This is a correction to S1.

### 2.5 Content stability across turns

| Span | Unchanged node digests |
|---|---|
| Two consecutive root turns | 133 / 133 |
| Across a publication (1,756 of 4,935 functions changed) | 4 / 9 function nodes, 4 / 4 schemas |

These numbers show that **content is stable**. They are not cache hits (Astra correction 4, §4.5).

## 3. Owner answers and review verdicts

| # | Item | Verdict |
|---|---|---|
| O1 | Standing root task | **Adopted** (§5). |
| O2 | References become real refs | **Adopted.** The walk is written against refs: EAVT forward, VAET reverse. The five value relations still stored as values use an interim declared join, retired by the references-as-refs lane (§4.1). |
| O3 | See N first | **Adopted.** Slice S1 is the walk plus the debug page at N=0–3, faces side by side (§7). |
| O4 | `:seon.render/units` | **Delete it** (§6). Forward refs and components are followed by the walk. Reverse refs come through VAET under the presence rule, bounded by N and a per-attribute collection cap. |
| R1–R7 | Root's round-3 shape | Verified as in revision 3. The context is the agent's rendering; the three parts stand; there are no mode keywords, by the owner's direction; order is definitions-first plus chronology; each node renders once; cost follows the graph; one block serves every consumer. Corrections are in §11. |

## 4. Design

### 4.1 Edges

**Refs (the target state).**

- Every ref attribute is followed forward (EAVT) and in reverse (VAET).
- A target is included only if its owning schema has a pair (§2.4). The request's root is always included.
- The owner's presence rule governs reverse edges too, bounded by N and by `max-collection` per node and attribute.

**Interim value edges (retired by design-references-as-refs-opus).** One constant in `walk.clj`, named with its
retirement, holds `[holder-attribute identity-attribute]` pairs:

- `:seon.fn/calls`, `:seon.fn/must-read`, `:seon.fn/see-also` and `:seon.task/must-read` → `:seon.fn/sym`;
- `:seon.task/tasks` → `:seon.task/id`;
- `:seon.fn.arity/input-refs`, `:seon.fn.arity/output-refs` and `:seon.schema/references` → `:seon.schema/key`.

The walk follows each pair forward (holder value → identity) and in reverse (identity value → holders). This is an
explicit, temporary table, not structural guessing (§0.8). It is not written into `:seon.fn/reference-to`, whose
installed meaning is eid-targeted and reach-bearing (Astra correction 1). Each relation the refs lane converts drops
its row. **`:seon.fn/keywords` is not an edge:** it records literal mentions and is not a declared reference, a
point the refs lane decides.

**Components.** An owned child with a render pair is a node. The agent's runtime and its turns are such nodes. An
owned child without a pair (an arity row) is joined through inside its owner's level. It never becomes a node, and it
needs no identity. The contract-key join `[?e :seon.fn/arities ?ar] [?ar ?contract-attribute ?k]` is the general
component step for that case.

**Must-reads and distance.** A transitive must-read, attached to a subject function or namespace, sits one level below
that subject. At the default N it renders at distance 0, as its name. I keep the owner's ordinary distance rule, as
Astra advised; §2 no longer claims that every must-read gets positive distance. A task that wants a transitive
must-read shown in full raises N.

**Data gaps, not walked:** render referrers, config documents read by a test world, prior art, and literal lookups
inside proofs.

### 4.2 The walk and its bounds

```clojure
(seon.render.walk/graph
  {:seon.db/db db :seon.render.walk/lookup [:seon.agent/id agent-id]
   :seon.render/distance 3 :seon.sci.admit/caps caps :seon.sci.eval/time-limit-ms ms})
;=> #:seon.render.walk{:root eid
;                      :nodes {eid {:seon.render.walk/level 1 :seon.render.walk/schema :seon.task/task}}
;                      :edges [[source attribute target] …]   ; every edge, into visited nodes too
;                      :order [eid …]                         ; §4.3
;                      :skipped {schema-or-attribute count}   ; targets with no pair (shown on the debug page)
;                      :cut {…}}                              ; only when a bound was reached
```

**One level runs four parameterized queries through `seon.db/q`, each binding `[?e ...]`:**

1. forward refs, `[?e ?a ?t]` with `[?a ...]` bound to the ref attributes;
2. reverse refs, `[?t ?a ?e]` with `[?a ...]` bound to the ref attributes, which uses VAET;
3. interim value edges, both directions:
   - forward, `[?e ?a ?v] [?t ?i ?v]` with `[[?a ?i] ...]` bound;
   - reverse, `[?e ?i ?v] [?t ?a ?v]`;
   - the component step for pair-less owned children;
4. presence: the target's owning-schema identity attributes, through the projection's owned-key sets
   (one-render-path 0a).

That is O(levels × 4) queries. The final plans are recorded in S1 (§2.2 caveat).

**Bounds (Astra correction 1).** Each query passes `:max-work`, `:max-results` and `:max-result-weight`, mapped from
caps as `ns.clj:40-50` does, plus the request's time limit. Bounds apply inside Datahike, before results are
materialized. When a query refuses, or the visited set reaches `max-nodes`:

- the level stops;
- `:cut` names the bound, the level, the visited count and the frontier left unexpanded, with a requery form;
- a partially completed level is reported as partial, never counted as complete.

**Acquisition per node.** The walk hands each node its lookup, its distance and the held database value. Its pair
reads what its face needs from that value: a task row, a `:seon.schema/form`, a function's source, a namespace's doc
and bounded counts, a turn's saved units. A node at distance 0 renders its name from the identity the join already
bound. Only the nodes rendered at distance ≥ 1 read anything, about 2–10 per task at the default N (§2.3's
level-one sets), so batching them into one `pull-many` per schema is an optimization to take only if S1 measures a
cost. It is not machinery in the walk.

### 4.3 N and order (Astra correction 2)

**N counts levels from the root.** A node at level L renders at distance N − L.

| Agent-context N | What renders |
|---|---|
| 0 | the root alone |
| 1 | the agent, with its task and other neighbours as names |
| 2 | the task in full; its subjects, proofs and must-reads as names |
| 3 (default) | subjects, proofs and must-reads in full; their callees and contract schemas as names |

N is the function's `:or` default, and the caller's value wins. Agent-root counts at N=3 are measured in S1; §2's
task-root counts are not agent-root counts.

**The ordering relation uses definition edges only:**

- function → callee;
- function → contract schema;
- schema → referenced schema;
- test → function;
- function → must-read.

Each such edge means the target is defined before the holder, so the sort runs over the **reversed** edges: nodes
with no remaining definitions go first.

**Membership edges are traversal only.** Task ↔ subject, namespace ↔ function, agent → turn and message ↔ agent make
nodes reachable but never constrain order.

**Cycles.** Strongly connected components of the definition relation are found with Tarjan's algorithm. Each
component renders once, at the position of its member with the smallest `(opened-tx or first transaction, lookup)`.
Its other members render in the same stable order. An edge that closes the cycle renders as `;; ↑ <id>`.

**Chronology.** Time-bearing nodes sort by `:seon.turn/opened-tx` (turns), `:seon.message/id` transaction (messages),
then by lookup. A message shared by two turns renders once, at its first turn. Its later references are
back-references.

The whole ordering costs O(V + E log V).

### 4.4 Render: each node's own pair, reading the supplied database (owner ruling 2026-09-25)

**The ruling.** Renderers MAY read the database. Each is handed a stable database value, a contract-declared input
supplied as a default by schema name (requirement 1.13; one-render-path §2), and reads it. The rule "renderers make no
database reads" is **withdrawn**. Earlier rounds cited it: revision 2's objection to a walk inside the agent pair, and
Astra's corrections 7 (round 2) and 3 (round 3), "pure inputs". Those citations no longer hold.

**Why the walk still sits outside the render functions:**

1. **Cost.** Set-at-a-time level joins took 10 queries for task 132fee. A walk from inside each node's renderer is
   the per-node walk: 3,823 queries (§2.2).
2. **Ordering.** Placing definitions before uses, and turns in time, needs the whole edge set before the first node
   is emitted (§4.3). A renderer sees only its own node.
3. **Render-once.** Deduplicating across the whole context needs one visited set per request.

A renderer's own reads of its node's details are ordinary. They run against the same held value and are captured as
evidence of the evaluation that shows them.

**Pairs added in S2:**

- `:seon.task/task`: AI and HTML over the acquired task row. They show the intent, the handle, and the expansion call
  `(seon.issue/task-edges (seon.db/db) "<handle>")`.
- `:seon.schema/schema`: AI and HTML over `:seon.schema/form`. The definition itself is printed by the floor. The
  `doc` form is not executed.

**Rewritten in S3** (what they read changes; that they read does not):

- the agent pair, both faces: identity, the task handle and turns left. It no longer walks concerns or issues itself; the graph is the walk's;
- the namespace pair, both faces: doc, bounded counts and `(dir ns)` at every distance, read from the supplied value, instead of `render-data`'s whole page.

**Presence and selection are separate questions.**

- *Membership* (is the node included?) is decided only by a schema pair.
- *Selection* (which face renders it?) follows the full ladder: the value's own key, then the viewer's namespace
  function, then the schema pair, then the floor.

So a viewer override changes how a node looks, never whether the walk visits it.

Pairs are compiled against the packaged projection with Malli's `validator` and `explainer` (`core.cljc:2637, :2653`).

### 4.5 Cache and composition (Astra correction 4)

`render-call`'s existing retention keeps every input it tracks today (`render.clj:783-827, :859-875`):

- the program rows the producer reached;
- the projection;
- the selected callable and its arguments;
- the environment;
- the captured reads.

The node's content digest is added to that key. Distance, viewer, profile and membership are already inputs. There is
no whole-program commit key.

Every prompt is the **complete current context**. Render-once is request-local deduplication. Cross-turn reuse
returns the retained bytes of unchanged nodes, and the composer still emits every required node in order. HTML may
patch only the changed nodes.

### 4.6 The opening and activation

- **The opening is `(help)` plus one walk form.** The walk form goes through the building block's Q2 `my.*` custody
  adapter, a dependency of S3. It uses the agent's configured lookup, never a literal.
- **Its evaluation value is the `graph` value.** The value is printed by the graph schema's pair, which composes
  through `units`. So the context-first refresh stage reads membership straight from that result (§4.7).
- **Pair publication and implementation load together.** A published pair changes selection for existing values
  immediately, so S2 lands its pairs and their functions in one loadable commit (Astra correction 6).

### 4.7 Refresh (Astra correction 5)

**Context first.** `system-turn` evaluates the walk form first. The plan then selects retained reads against that one
result's node set. After a restart, the walk is reacquired once on the ordinary path. Membership is never
reconstructed from evidence or from shown text.

**A retained read is skipped only if every captured dependency is positively outside the graph.** Each dependency
source (plan, request, pull) must satisfy all of:

- every constrained entity position resolves: an eid, a lookup, or an identity value;
- none of those resolved entities is a graph node;
- the source database or branch coordinate is the context's.

Everything else is re-checked:

- `:all` plans;
- unattributable sources;
- mixed dependencies;
- patterns with the entity unbound, whether the value is bound or not, because an insertion can make them relevant;
- literal arguments that are not identities.

The effect-producer fix (§0.5) comes first. Context-first must never evaluate a refresh candidate twice during the
transition.

## 5. The standing root task (owner O1)

One entry in `resources/seon/tasks/seon.cluster.edn`:

- **name** `"orchestrate-platform"`, **kind** `:namespace`;
- **intent** "Orchestrate the agents and keep the platform healthy: every open fault has an owner, every running lane
  is progressing, and the platform health checks are green";
- **must-read** `seon.cluster.status/agents` and `seon.error/for-agent`.

Its subject is namespace `seon.cluster`, through a `:seon/tasks #{"<handle>"}` annotation on the `ns` form.

**Proofs** are the platform health deftests, each annotated with the task's handle. Root names them. I have not
verified which existing tests qualify, so none is listed here.

**References:**

- **Open faults** come through the reverse ref `:seon.error.occurrence/agent` into the root agent: 4 holders today
  (§2.4). They render through the error pair.
- **Running lanes are a data gap.** No ref links a lane agent to root; `:seon.message/from` has 16 holders but means
  something else. The references-as-refs lane should decide whether lane agents get a `:seon.agent/task` whose task
  names root's task (a `:seon.task/needs` ref) or another relation. Until then, lanes are one call away:
  `(seon.cluster.status/agents {})`.

Root's opening at N=3 is its identity, this task and its must-reads, open-fault occurrences, the trigger, and recent
turns. There is no namespace dump and no ask-yourself error.

## 6. Deleting `:seon.render/units` (owner O4)

| Use today | What it does | What the walk gives instead |
|---|---|---|
| Agent schema (`seon.agent.edn:38-46`), 8 entries | Lists which relations render on the agent page and opening, and their order | `:seon.agent/plan`, `:seon.agent/settings`, `:seon.agent/namespace` and `:seon.agent/runtime` are forward refs and components, followed automatically. `:seon.issue/_agent`, `:seon.message/_to` and `:my.note/_agent` are reverse refs, found through VAET with presence. `:seon.error/of-steward` is a **derived concern** with no datoms (`seon.error.edn:228-236`, `:seon.render/derived true`); the root task's open-fault references replace it, and its faults renderer is building block Q3. Order comes from §4.3 |
| Issue schema (`seon.issue.edn:46`), 4 entries | Lists the relations shown on an issue page | `:seon.issue/functions`, `:tests`, `:errors` and `:members` are forward refs, followed automatically |
| `:seon.cluster.status/root` (`seon.cluster.status.edn:6-7`) | Attaches a derived status concern to the root agent page | Not a relation. The root task's must-read `seon.cluster.status/agents` and its fault references replace it. The derived-concern schema `:seon.cluster.status/concern` becomes the value of that ordinary call, rendered by its pair |
| `walk.clj:94-101` `declared-concerns` (and its use in `connection-attributes`) | Reads the list for traversal | Replaced by the §4.1 structure. Already inside C's 482 lines, so no extra credit |
| `web.clj:694-715` `declared-entity-units`, `:1682-1719` reverse-units pulls | The entity debug page pulls each declared reverse relation | The page renders the `graph` of its subject. That is 22 + 38 = 60 lines |

**The deletion, slice U**, lands after S3 and after C's conversion of `web.clj`:

- the `:units` definition (`seon.render.edn:171-175`, 5 lines) and the three schema entries (about 13 lines);
- the two web definitions (60 lines);
- `:seon.render/derived` and its reader at `walk.clj:671`, once Q3 and the status conversion land (about 4
  declaration lines).

Net: **−68 source lines and about −22 declaration lines**, with no replacement key.

**Risk.** Following every reverse ref can pull in many holders: 42 turns reverse-refer to root. The bounds are N,
`max-collection` per node and attribute (a cut names the rest), and the presence rule. The composer shows the newest
`max-collection` turns. Whole-unit prompt selection (one-render-path §5) still owns admission.

## 7. Slices

### S1: the walk and the owner's debug page (first)

**What it delivers.**

- `seon.render.walk/graph`: the §4.1 structure, the §4.2 levels and bounds, the §4.3 order.
- Nothing on the agent path calls it.
- On `/agent/{id}/debug`, a `?n=0..3` selector. It shows the AI face and the HTML face side by side for each node,
  in graph order. Each node is rendered through today's `render/render-call` per face, which is the one existing
  render path. The page also shows a panel of `:skipped` targets (schemas with no pair, with counts) and the `:cut`
  when one was reached.
- The existing route and debug page (`route.clj:15`, `web.clj:3667`) are reused. No new route is added.

**What it does not do.** It changes no pair, no opening and no refresh. Task and schema nodes render through the
floor until S2, so the page shows the owner exactly what is missing.

**File grant (exact):**

| Path | Change | Estimate |
|---|---|---:|
| `src/seon/render/walk.clj` | add `graph`, its structure memo, the level queries and the order; no existing function changes | +65 |
| `resources/seon/schemas/seon.render.walk.edn` | `:seon.render.walk/graph`, node, edge and cut schemas | +12 declaration lines |
| `src/seon/render/web.clj` | the N selector and side-by-side node faces on the agent debug page | +30 |
| `test/seon/render/walk_graph_test.clj` (new) | a world built by canonical writers (task EDN through `seon.issue/index-tasks!`, program rows from test source) | +70 test lines |
| `test/seon/render/web_debug_test.clj` | one case: N=0..3 renders both faces in the same node order | +20 test lines |
| `docs/prds/agent-platform/landing/lane-impl-task-graph-s1-2026-09-25.md` (new) | the landing note | — |

The test world asserts:

- node kinds per level;
- no definition placed after its user;
- turns in `opened-tx` order;
- the cut at `max-nodes`;
- presence skipping;
- four queries per level.

**Bounds and probes.** Source is ≤ 100 added lines (65 + 30). If `web.clj`'s share grows past 35 lines, the lane
stops and reports. The proofs S1 must record:

- the query plans;
- queries per level;
- timings at N=0/1/2/3 from `[:seon.agent/id "root"]` and from the three tasks' roots;
- memory;
- page paint.

**Dependencies:** none. The refs lane may land before or after; its conversions only shrink the interim table.

### Later slices

| Slice | Change | Prerequisites |
|---|---|---|
| S2 | task and schema pairs, both faces; the task entry, a data file; pairs and their functions in one commit | S1 |
| S3 | the opening switches to `(help)` + the walk form; agent and namespace faces stop duplicating the walk (namespace: no page dump); situation AI and the `declared-sources` walk retire | building block S2/S3 (composition, shared floor) and Q2 (`my.*` adapter); the standing root task; `:seon.agent/task` assigned to every agent; unmigrated issue workers keep one ordinary opening acquisition (their `issue.opening/source` call, moved out of the renderer) until T3 (Astra correction 7) |
| T0 | delete the bootstrap candidates and `ordered-episode` with their tests | none |
| T2 | evidence-scoped refresh (§4.7) | the effect-producer fix; S3 |
| T3 | delete `seon.issue.opening` and its require and caller | issue → task migration (requirement 7.11) |
| R | refs lane conversions delete interim table rows | design-references-as-refs-opus |
| C | delete per-entity acquisition and the namespace walkers after converting `web.clj:2021-2058`, `render-data` and their tests | S3 |
| U | delete `:seon.render/units` (§6) | S3, C, building block Q3 |

## 8. Ledger (Astra's comparable spans; estimates, not measured diffs)

| Slice | Source + | Source − | Declarations |
|---|---:|---:|---:|
| S1 | 95 | 0 | +12 |
| S2 | 40 | 0 | +4 |
| S3 | 52 | 128 | +13 (`:seon.agent/task` 2; no-task, dangling-task and absent-agent errors 11) |
| T0 | 0 | 602 (502 + 100) | −8 est. |
| T2 | 60 | 74 | 0 |
| T3 | 5 | 233 | −1 |
| C | 10 | 582 (482 + 100) | 0 |
| U | 0 | 60 | −22 |
| R | −10 (interim table rows) | 0 | per refs lane |
| **Total** | **252** | **1,679** | **−2** |

- **Net:** −1,427 source lines if every conditional slice lands, and −677 for S1 + S2 + S3 + T0 + T2.
- **Tests:** about +150, with the retired bootstrap, episode and history tests credited when they land.
- **Booked elsewhere:** Q1, Q2, Q3, Q5 and Q6 are the building block's. Q4 moves here.

## 9. Required witnesses

1. **Graph shape.** Node kinds and counts for the three tasks and for the root agent at N=0–3. No definition is placed
   after its user. Membership cycles do not constrain order. A definition cycle renders once, with a back-reference.
2. **Chronology.** Turns are placed by `opened-tx`. A shared message renders at its first turn.
3. **Presence.** Pair-less targets are skipped and counted. The root is always rendered. Viewer overrides change faces
   only.
4. **Bounds.** Refusal and the cut report a partial level honestly. Query plans and seeks are recorded.
5. **Cache.** Hits are counted, not inferred from digests. A publication re-renders only reached changed inputs. The
   complete context is emitted every time.
6. **Refresh.** An empty-result read is re-checked after an insertion. `:all` and mixed dependencies are kept. A read
   through an indirect result or binding is kept. A changed task reselects. An effect call is never re-run.
7. **Timing.** Parent versus change for `graph`, `units` and `system-plan` at N=0–3, plus a high-degree root
   (`evaluate`, 107 calls), with memory.

## 10. Open questions (priced; simplest viable first)

1. **The interim value-edge table.**
   - **A (recommended):** one constant in `walk.clj`, each row deleted by the refs lane; 0 declarations; a named,
     temporary hand list.
   - **B:** a new declared property on each attribute, 8 lines across 4 schema files, then deleted again by the refs
     lane.
   - **C:** wait for the refs lane before S1; S1 then reaches only ref edges, so the task's functions would be missing
     from the page.
2. **Running lanes for root.**
   - **A:** keep them as the `seon.cluster.status/agents` call.
   - **B (recommended):** the refs lane adds a lane-task → root-task relation (such as `:seon.task/needs` as a ref);
     about 2 declaration lines plus writer work in that lane.
   - **C:** a derived concern; rejected, since it is the `:seon.render/derived` machinery §6 deletes.

## 11. Review dispositions

### Astra round 3 (67a0358ab)

| Correction | Disposition |
|---|---|
| 1 Walk implements the declared graph, with real bounds | **Applied.** The final parameterized query families (§4.2) include ordinary reverse refs, see-also, forward task handles and schema references. There is a component rule. `reference-to` is not reused. Bounds pass into `seon.db/q`, with a partial-level cut. The AVET cost claim is withdrawn pending S1's recorded plans |
| 2 Traversal edges separate from definition order | **Applied.** The definition relation only, reversed; membership edges are traversal only; Tarjan components; `:seon.turn/opened-tx`; transitive must-reads at distance 0 stated; agent-root counts measured in S1 |
| 3 Real task and schema pairs; pure inputs for both faces | **Applied** for the pairs: S2 adds real pairs over the task row and `:seon.schema/form`; `schema-form` withdrawn; presence and selection separated. **Superseded** for "pure inputs" by the owner's 2026-09-25 ruling that renderers may read the supplied database value (§4.4). The walk-side per-kind acquisition is dropped: pairs read their own rows. The namespace dump and the agent's re-walk are still fixed in S3 |
| 4 Retention keeps all inputs; complete context | **Applied** (§4.5) |
| 5 Evidence refresh without reconstruction | **Applied.** Membership comes from the newly acquired graph, reacquired after a restart; every dependency must be positively outside; the additional cases are in §9.6 |
| 6 Gate the opening on the complete graph | **Applied.** S1 changes nothing on the agent path; S2 publishes pairs with their implementation; S3 depends on building block S2/S3 and Q2; configured lookup |
| 7 Migration, deletion credit, callers | **Applied.** Issue workers keep one ordinary opening acquisition until T3; the episode test is added to T0; C converts `web.clj:2021-2058`, `render-data` and the tests first; root must strike Q4 and S5 from the building block |

### Astra round 2 (595048666)

As dispositioned in round 3:

- fixes 2, 5 and 6 are closed; fix 7's "renderer-side reads" objection is withdrawn by the owner's ruling (§4.4), though its callable-expansion half stands;
- fix 1's remedy is superseded by captured-evidence refresh (now completed by correction 5);
- fix 3's taught-tool policy is superseded by the owner's distance model, with its coverage completed by correction 1;
- fix 4's task walker is superseded by the one walk, with its bounds completed by correction 1;
- fix 8 is completed by correction 7.

The parallel lane's `render-graph-research-2026-09-25.md` had not landed when this revision was committed. Root
should reconcile its measurements against §2 when it does.

## 12. Unresolved risks and out-of-scope findings

This lane writes one file, so root files these. Existing issues were not searched for items 1–3.

1. **Effect calls pass `read-only-evaluation?`** (§0.5). Suggested issue:
   `docs/seon/issues/effect-calls-pass-the-read-only-refresh-test.md`.
2. **The task writer admits a dangling must-read** (§0.6). Suggested issue:
   `docs/seon/issues/task-must-read-links-are-not-resolved.md`.
3. **`latest-evaluations` pulls all history evidence** (§0.4), over 1 s per system turn until T2. Suggested issue:
   `docs/seon/issues/system-plan-pulls-every-retained-read.md`.
4. **The namespace faces dump the page, and the agent AI face re-walks concerns** (§0.3). S3 fixes both. Reading the database in a renderer is lawful and is not itself the defect.
5. **`web.clj` is 3,811 lines.** S1 adds about 30. A shrink assignment for that file is outside this lane.
6. **The probes called `datahike.api/q` directly.** Production goes through `seon.db/q`, whose evidence and decoding
   cost for these queries is unmeasured. Timings are single samples under test load.
7. **Loadability is unproven.** No slice has been loaded.
8. **This design depends on `ca17618c0` being loaded.** Default predates it. **RESET NEEDED: no.**
