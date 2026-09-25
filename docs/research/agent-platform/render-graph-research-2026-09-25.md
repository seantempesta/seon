---
type: research
status: "Opus research lane research-render-graph-opus; measured on default; no source edited; awaiting Astra review"
created: 2026-09-25
scope: Find every node that has a render function, order the reached graph (definitions before uses, turns by tx), render independent nodes in parallel on one SCI fork, and reuse renders by content
source-pin: HEAD 67a0358ab (render.clj last changed d3c0d630e); sci 409888f6; datahike 4157916b
live: default JVM pid 59566, started 2026-09-25T21:34:14Z, health faulted (20 open faults, none from this lane); store 1,002,640 datoms, max-tx 536886775
aligned-with: task-context-install-design-2026-09-25.md §§2-4; query-render-building-block-2026-09-25.md §§3a-3f; one-render-path-design-2026-09-25.md; sci-context-efficiency-opus/astra-2026-09-24.md
---

# The render graph: find, order, render in parallel, reuse

**What exists.** An agent's context is acquired by `seon.render.walk/neighborhood` (`walk.clj:710`), which follows
the agent schema's eight declared concerns through about 640 lines of acquisition (`walk.clj:69-708`). Each reached
unit goes through `seon.render/render-call` (`render.clj:1488`). That call selects a producer per face
(`selection`, `:672`, in the order explicit value, viewing namespace, declared schema pair, floor). It checks
retained evidence, then invokes the producer through `seon.sci.kernel/invoke` (`kernel.clj:585`), which arms the
call and admits the result (`admit/admit-value`, `kernel.clj:642`).

**The data flow this report recommends:**

1. seeds;
2. level-at-a-time index seeks (EAVT for refs, AVET for declared value edges);
3. node → schema → pair from one per-projection index;
4. a DFS postorder, with children taken in first-transaction order;
5. node renders keyed by content, producer and face;
6. only the cache misses fan out over one SCI fork through `core.async/pipeline-blocking`.

**The principal Clojure approach.** Every step is a function of one held database value and one projection. The
index and selection are memoized with the existing `schema/projection-cache-value`. The walk is plain `loop`/`into`
over `d/datoms` seeks. Parallelism is a core.async transducer pipeline. No new registry, scheduler or evaluator is
added.

## 0. Broken things first

1. **A floor render of any value with no block id costs 4.6–4.75 s and 22.8 GB, then refuses.**
   - `render-call` on `{:a 1}` or a 3-key `:seon.ns.refer/binding` row, without `:seon.render.call/id`, selects
     `seon.render.value/render-ai`.
   - `render.value/node-id` refuses with "A rendered value root requires a caller-supplied block id", and puts the
     **whole unit in `:seon.error/offending`** (`value.clj:249-257`). That unit includes the SCI ctx and the database.
   - `kernel/invoke` then admits that error value. `admit-value` walks the ctx: 234,888 calls to
     `identity-only-projection-in` and 234,950 to `database-value?`, for 4,353 ms. It ends in an "opaque
     java.lang.Class" refusal.
   - My first 99-node pass took **33.1 s and 139 GB**. The 5 refer-binding nodes caused about 24 s and 114 GB of that.
   - This is a producer defect (the refusal carries its world) and an admission defect (cost follows every object
     reachable from the value). It is the only >1 s render path I found. Suggested issue:
     `docs/seon/issues/a-refusal-that-carries-its-request-costs-gigabytes-to-admit.md`.
2. **The turn renderer does database work: 42 turns cost 4.5 s and 18.9 GB.** One turn took 1.43–1.86 s.
   - `seon.render.transcript/render-run-ai` (`transcript.clj:795`) issued 1,096 `db/q` over 84 turn renders (13 per
     turn), 208,992 `decode-attribute-value-in` calls and 3.85 M `canonical-value-string` calls.
   - The other 134 nodes of root's graph cost 0.71 s and 1.17 GB together.
   - This breaks "renderers make no database reads". It is the critical path for any parallel plan (§3).
3. **Every `render-call` recomputes its producer's call reach: about 4 ms per call.** The chain is
   `call-cache-evidence` (`render.clj:792`) → `render-program-evidence` → `db/stored-call-reach` (`db.clj:1429`). It
   took 2,657 ms over 670 calls. `config/effective` added 775 ms over the same 670 calls. Both depend on the
   producer and the program, not on the node, yet they run once per node.
4. **The retained-call reuse path saves 63% of the time and still costs as much as rendering.**
   - Opening 1, with nothing retained, took 5.39 s and 11.1 GB. Opening 2, with opening 1's captured calls
     retained, took 2.01 s and 7.6 GB. That is 11.5 ms and 44 MB per node, when a direct compiled render of a
     non-turn node is 1.5 ms and 2.7 MB (§3).
   - Across the two openings `kernel/invoke` ran 333 times for 345 calls, so producers were re-invoked almost every
     time.
5. **Symbol- and keyword-valued edges are mostly undeclared.**
   - Only `:seon.schedule.task/function` declares `:seon.fn/reference-to :seon.fn/sym`, the seam
     `fn.clj:1698` joins on.
   - Eight attributes typed `:seon.program/edge-symbol` declare no target: `:seon.fn/calls`, `references`,
     `must-read` and `see-also`; `:seon.test/subject` and `reach`; `:seon.task/must-read`; `:seon.test.seed/function`.
   - `:seon.fn/keywords` (`:qualified-keyword`) cannot be matched to `:seon.schema/key` (`:keyword`) by schema name.
   - A walker must hand-list these edges today. §1c prices the one-line declaration.
6. **Stored `:seon.render/ai` facts are strings.** There are 334 on schema rows, against the rule that symbols stay
   Datahike symbols. They are also a subset: the projection answers 534, because error schemas inherit
   `:seon.error/base`'s pair through `[:and …]`.
7. **The half-gigabyte trivial evaluation did not reproduce on this JVM.** On a retained branch, `(+ 1 2)` through
   the whole `seon.sci.eval/evaluate` allocated 2.3 MB in 2.7–3.9 ms, and `(vec (range 300))` 4.5 MB. The arm
   record reported 567 KB. The open issue `a-trivial-sci-evaluation-allocates-half-a-gigabyte.md` measured pid
   37460. The allocation class alive now is item 1: admitting values that reach a ctx.

## Method and limits

Every probe was a finite, read-only JVM form in the scratch namespace `scratch.render-graph` (session
`render-graph`), timed with `System/nanoTime` and `ThreadMXBean/getThreadAllocatedBytes`. Parallel allocation is
summed over the worker threads. "Fresh" means `(d/db-with db [])`, which bypasses Datahike's result cache.

- **SCI experiments** used branch `render-graph-probe` (created off basis 536886776, then retired), acquired through
  `seon.cluster.agent/acquire-context!` as MCP does (37 ms, 55 MB). One contracted private SCI function,
  `scratch.render-graph-sci/probe-render`, was defined on that branch.
- **Cleanup:** the scratch namespace was removed with `remove-ns`. No provider call or turn was run; nothing was
  transacted; no default Var was touched.
- **Limits:** each timing is one sample, taken while other lanes were active. The machine has 18 cores.
- **What the graphs are not:** root has no `:seon.agent/task` attribute (not installed), so the "task" graphs are
  seeded at the task entity itself, as task-context §3.3 proposes. The walker's value-edge list is hand-supplied
  (defect 5).

## 1. Finding renderable data

### 1a. Where render functions live, measured

| Source | Where | Count on default | Cheapest read | Cost |
|---|---|---:|---|---|
| **Schema pair** (shipped default) | Malli properties of the named schema (`internal/entity-properties` or `m/properties`), as `declared-producers` reads them (`render.clj:487-521`) | 535 schemas with a pair: 534 AI, 528 HTML, 3 with `:seon.render/units` | one pass over `:seon.schema.projection/forms`, memoized per projection | **5.9 ms, 18.2 MB once per projection fingerprint** |
| the same pairs as stored facts | `:seon.render/ai` / `:html` string datoms on `:seon.schema/schema` rows | 334 / 329 (no inheritance; strings) | `[:find ?k ?ai :where [?s :seon.render/ai ?ai] [?s :seon.schema/key ?k]]` | 1.3 ms, 0.39 MB (fresh db); all 334 agree with the projection |
| **Value key** (`:seon.render/ai`/`:html` on the value) | only on schema rows in the store | 0 non-schema holders | `explicit-value` = one `find` on the node map (`render.clj:574`) | O(1) per node |
| **Viewing agent's namespace functions** | `sci.kernel/public-functions-in` (`kernel.clj:140`) filters the program snapshot, then a contract fit per candidate | root's namespace `my.agents.root` has 1 function and 0 render candidates | per call it scans all **4,928** snapshot functions | 0.44 ms, 1.45 MB per call; it should be memoized per (snapshot, namespace) |
| **Floor** | `seon.render.value/render-ai`/`-html` | every node without a pair | — | — |

**The cheapest index** is one `projection-cache-value` entry, `schema key → pair`, next to the existing
`entity-owners` memo (`render.clj:304-325`, 1.7 ms). The stored facts are not an alternative, because they miss
inherited pairs. Full `selection` on one node costs 1.3 ms and 1.26 MB (106 ms and 169 MB for 134 nodes). Its
inputs are the node's schema, the face, the viewer's namespace and the projection, not the node's content, so it is
memoizable on exactly those.

### 1b. How an entity maps to its schema

The mapping already exists, and it is by **declared attribute, not by name guessing**:

1. **An entity with an identity attribute** takes that attribute's `:seon.program/row-schema`. 55 identity
   attributes are installed; 46 declare a row schema, and 20 of those row schemas carry a pair.
   `entity-owners` builds the map (`render.clj:304`).
2. **An identity-less component or ref target** takes the reaching attribute's `:seon.db/component-schema`, or its
   named ref schema (`attribute-identity`, `render.clj:380-404`). Examples are runtime, plan, settings, attempts and
   ns alias or refer bindings.
3. **Transaction entities** (reached through `:seon.turn/opened-tx`/`closed-tx`) are `:db/tx` leaves.

Measured over the reached graphs, every node got a schema (0 unknown):

| Graph | Nodes | Derivation | With a pair | Without a pair (floor or leaf) |
|---|---:|---|---:|---|
| root, forward refs, depth 6 | 176 | 3.5 ms, 0.8 MB | 99 (turn 42, attempt 30, message 17, refer 5, agent, runtime, plan, ns, overlay) | 76 `:db/tx`, 1 `:seon.db.process/process` |
| task `132fee0467c8`, depth 3 | 505 | 6.6 ms, 3.1 MB | 237 (fn 174, alias 41, ns 15, refer 4, test 3) | schema 141, shape 65, arity 45, file 15, argument 1, **the task itself** |

**`:seon.task/task` has no render pair.** A task-generated context needs one, or it renders through the floor.

### 1c. Edges: refs, reverse refs, value refs

- **Forward refs** come from one EAVT seek per entity, keeping datoms whose attribute is `:db.type/ref` (260
  attributes).
- **Reverse refs.** Datahike has no VAET, but refs are always AVET-indexed (`db/utils.cljc:312` makes
  `:db.type/ref` imply `:db/index`). A reverse edge is `(d/datoms db :avet attr e)`. All 260 ref attributes for one
  entity took **1.0 ms and 0.9 MB**. Root has 13 reverse attributes: `:seon.turn/agent` 42, `:seon.message/to` 17,
  `/from` 16, and others. Reverse edges must be declared, as the agent schema's `:seon.render/units` already does
  (`:seon.message/_to`, `:my.note/_agent`). Otherwise every turn and message becomes a child of its agent.
- **Value edges** are one AVET seek on the target identity per value (`[:seon.fn/sym v]`, then `[:seon.test/sym v]`;
  `[:seon.schema/key k]`; `[:seon.task/id h]`). The reverse value edge from a task to its subjects is
  `(d/datoms db :avet :seon.task/tasks handle)`.
- **The declaration that makes value edges data (defect 5):** add `:seon.fn/reference-to :seon.fn/sym` once to the
  named schema `:seon.program/edge-symbol` (`seon.program.edn:64`), so all eight attributes inherit it. Declare it
  on `:seon.fn/keywords` (→ `:seon.schema/key`) and `:seon.task/tasks` (→ `:seon.task/id`) as well.
  - The value-edge map is then one projection pass, like the pair index.
  - About 3 declaration lines. It also lets `declared-reference-rules` (`fn.clj:1692-1712`) read one declaration
    instead of special-casing `:seon.effect/capability`.

## 2. Walking and ordering

### 2a. The level step, three ways (same frontier, fresh database value)

| Level mechanism | Frontier | Result | Time | Allocated |
|---|---:|---:|---:|---:|
| **EAVT seek per entity, filter ref attributes** | 176 root nodes | 349 edges | **0.23–0.34 ms** | **0.42 MB** |
| Datalog `[:find ?e ?a ?v :in $ [?e ...] :where [?e ?a ?v]]` (all attributes) | 176 | 1,017 tuples | 10.6–11.2 ms | 29.5 MB |
| Datalog with `?a` bound to the 260 ref attributes | 176 | 349 | 5.5 ms | 6.8 MB |
| Datalog value join `[?e ?a ?v] [?t :seon.fn/sym ?v]` | 176 | 0 | 2.0 ms | 5.2 MB |
| EAVT seek + AVET value resolution | 505 (task 132fee, 10,342 datoms) | 2,171 edges | 11.2 ms | 20.3 MB |
| Datalog refs + Datalog value join | 505 | 1,060 + 515 | 6.2 + 3.8 ms | 7.5 + 6.9 MB |
| raw `d/datoms :eavt` only | 505 | 10,342 datoms | 1.2 ms | 1.4 MB |
| the same through `seon.db/datoms` | 505 | 10,342 | 13.0 ms | 26.1 MB (**11× the raw seek**) |

- **Refs:** index seeks beat a Datalog level query by 20–40× on refs.
- **Value edges:** Datalog's hash join catches up once there are many values, because one AVET seek per value
  costs about 4.6 µs (2,171 seeks at 10 ms).
- **The owner's `seon.db/datoms` costs 11× the raw seek** (contract plus evidence). A walk that honours "direct
  `datahike.api` only in `seon.db`" needs a batch entrance there: one call per level, one evidence record per level.

### 2b. Per-node pull versus Datahike recursive pull

| Acquisition of root's graph (176 nodes) | Time | Allocated | Shape |
|---|---:|---:|---|
| BFS seeks, depth 6 | 0.8–2.1 ms | 1.1 MB | 176 nodes, 349 edges |
| then `d/pull-many '[*]` | 3.2 ms | 8.5 MB | 176 flat maps; children are `{:db/id n}` |
| then `d/pull '[*]` per node | 5.6 ms | 9.6 MB | the same (610 KB printed) |
| then `seon.db/pull` per node | 6.2 ms | 17.5 MB | the same |
| `d/pull` with `['* {ref 6} …]` over all 260 refs | 2.0 ms | 0.9 MB | **5 maps**: with `*` the recursion specs never recursed |
| `d/pull` with `[:db/id {ref 6} …]` | **805 ms** | **1,984 MB** | **4,309 maps for 176 entities (24×)**: a tree, not a DAG |

Datahike's recursion tracks `seen` per path and depth per attribute (`pull_api.cljc:224-283`). A shared node is
therefore re-pulled under every parent, and 260 attribute specs are checked per entity. The recursive pull is the
wrong tool for a linked graph; flat pulls of the BFS set are the right one.

### 2c. The three tasks and root (fresh database value)

| Seed | Depth | Frontier per level | Nodes | Edges | Datoms reached | Time | Allocated |
|---|---:|---|---:|---:|---:|---:|---:|
| agent `root` (forward) | 6 | 1 · 4 · 48 · 122 · 1 | 176 | 349 | 1,017 | 0.81 ms | 1.13 MB |
| agent `root` + reverse `turn/agent`, `message/to`, `note/agent` | 3 | 1 · 65 · 111 | 178 | 412 | — | 2.8 ms | 3.5 MB |
| task `132fee0467c8` | 2 / 3 | 1 · 5 / · 139 | 145 / 505 | 179 / 876 | 3,957 / 10,342 | 1.05 / 6.07 ms | 1.8 / 10.1 MB |
| task `300aca1ad57c` | 2 / 3 | 1 · 4 / · 11 | 16 / 73 | 29 / 94 | 331 / 715 | 0.21 / 0.43 ms | 0.2 / 0.5 MB |
| task `cea47b015b23` | 2 / 3 | 1 · 4 / · 22 | 27 / 46 | 33 / 60 | 312 / 440 | 0.12 / 0.33 ms | 0.1 / 0.5 MB |

For comparison, today's `walk/neighborhood` for root (distance 1) took **208 ms and 399 MB** for 11 units and
1.9 KB of output. That includes rendering, but its acquisition alone reads the agent's eight concerns with
per-concern pulls.

Program graphs fan out through keywords: `:seon.fn/keywords` accounts for 318 of task 132fee's 876 depth-3 edges.
That confirms task-context §2.3's conclusion: hop 1 is content, hop 2 is names, and hop 3 and beyond are counts.

### 2d. Order: DFS postorder, children by first transaction

A node's first transaction is the minimum `:tx` over its EAVT datoms, read during the same seek. A 12-line iterative
DFS emits each node after its targets, so definitions come before uses.

| Graph | Order time | Allocated | First-tx derivation | Edges pointing forward (use before definition) |
|---|---:|---:|---:|---|
| root, 176 nodes | 0.42 ms | 0.62 MB | 2.1 ms | 118 of 349: `turn/agent` 42, `turn/starting-ns` 39, `message/to` 17, `message/from` 16, and one each of `runtime/agent`, `config/agent`, `my.plan/agent`, `agent/namespace` |
| task 132fee, 505 nodes | 0.96 ms | 1.87 MB | 6.2 ms | 3 of 876 (call cycles) |

- **Root's graph is cyclic by construction.** Every forward-pointing edge is a back-pointer to the agent or to its
  namespace. A postorder therefore makes these edges **handles**: the target is already on the path and renders as
  its address.
- **Turns come out in transaction order** (verified: postorder turn order = tx order).
- No topological-sort library is needed, and the one on the classpath refuses cycles (§5).

## 3. Parallel rendering on a shared base

### 3a. Is one SCI fork safe for concurrent calls? (reference-code/sci 409888f6)

- **Dynamic bindings are per thread.** `sci.impl.vars/dvals` is a `ThreadLocal` of binding frames
  (`vars.cljc:40-45`). The ctx store var is `^:dynamic` (`ctx_store.cljc:9`) and is bound per evaluation
  (`interpreter.cljc:80-83`).
- **Analysis state is per call.** `eval-form*` creates a fresh `closure-bindings` volatile and binding array for
  each form (`interpreter.cljc:44-73`) and reads the env with `@(:env ctx)`.
- **Env writes are atomic.** `def`, `ns` and `refer` go through `swap! (:env ctx)` (`namespaces.cljc:623,636`;
  `utils.cljc:387`). They are safe, but concurrent defs could interleave; renderers do not def.
- **Arming is per thread.** Seon's arm is `ThreadLocal` (`::thread-arm`, `kernel.clj:412`) and `with-arm` releases
  it on every exit (`kernel.clj:357-380`).
- **A fork copies an env atom** (`core.cljc:345-351`). Two forks share nothing mutable.

**Verdict:** concurrent **invocation of already-defined functions** through one fork is safe. Concurrent
evaluation of forms that def is atomic per Var but unordered, so it is out of scope for rendering.

### 3b. Measured: the same nodes, byte-identical output in every mode

| Nodes | Path | Sequential | 4 workers | 8 workers | 8 workers, one `sci/fork` each |
|---|---|---:|---:|---:|---:|
| 134 non-turn nodes of root's graph | `render-call` | 712 ms / 1.17 GB | 226 ms / 1.41 GB (3.1×) | **153 ms** / 1.77 GB (4.7×) | 164 ms / 1.71 GB |
| same | **direct compiled producer** (armed Var) | 205 ms / 357 MB | — | **37 ms** / 357 MB (5.5×) | — |
| 42 turns | `render-call` | 4,526 ms / 18.9 GB | — | 2,512 ms / 19.1 GB (1.8×) | — |
| 42 turns | direct compiled | 3,316 ms (sum) | — | 1,921 ms / 13.3 GB | — |
| all 176 | `render-call` | 5,232–5,534 ms / 20.1 GB | 3,297 ms / 20.3 GB (1.6×) | — | — |
| 176 values, one SCI render function | raw SCI fn call | 38 ms / 79 MB | — | 13 ms / 78 MB (2.9×) | — |
| same | `kernel/invoke` with `:seon.sci.eval/function` | 67 ms / 92 MB | — | 20 ms / 92 MB (3.4×) | — |

Every parallel run's outputs equalled the sequential run's: full strings for `render-call` and direct calls; SCI
results under `=`. The direct compiled producer (selected producer applied to
`render-invocation-argument`) equalled `render-call` on all 134 nodes.

**Findings:**

- **Parallelism works on one fork; per-worker forks add nothing** (164 ms against 153 ms). The shared base can be
  the agent's own fork.
- **Speedup is bounded by the slowest node, not by SCI.** The largest turn render (1.43 s direct) sets the floor for
  the 176-node graph. Fix §0.2 first, and parallelism pays about 5× at 8 workers.
- **`render-call`'s wrapper costs 3.5× the render itself** on non-turn nodes (712 against 205 ms). The wrapper cost
  is §0.3's per-call program evidence and config, plus admission of compiled producers' output.
- **SCI invocation overhead is small:** `kernel/invoke` adds about 0.17 ms and 75 KB per call over the raw SCI fn.
  A private branch-local function by **symbol** is refused ("Selected function has no acquired row"). Only its
  function value works. An agent's render function therefore needs a program row, or is passed as a value.
- **Allocation does not fall with parallelism:** 357 MB sequential equals 357 MB at 8 workers for the direct path.
  Wall time falls; the work does not.

## 4. Caching by content

**The experiment:** two consecutive openings of root, as-of 536878884 (just before the last turn opened) and as-of
536878885 (its opening).

| Measure | Value |
|---|---|
| Nodes, opening 1 / opening 2 | 171 / 174 |
| Acquisition (BFS + pull), opening 1 / 2 | 236 ms (first as-of, cold history) / 38 ms |
| Changed or new in opening 2 | 5: the new turn, its message, its tx, the runtime (turn list grew), the agent entity |
| **Hits keyed by `[value, producer, face]`** | **169 of 174 (97%)** |
| Hits keyed by the node's EAVT `[a v]` vector (no pull needed) | 170 of 174; 6.8 ms, 4.9 MB for 174 |
| Hit soundness: direct render at opening 1 against opening 2 | **138 of 138 checked hits identical** (all non-turn hits plus 10 turns) |
| Today's retention (`:seon.render/retained-calls`) on the same pair | opening 1 5.39 s / 11.1 GB; opening 2 2.01 s / 7.6 GB |

**The key.** Use `[content, producer, producer-program-identity, face, profile]`:

- `content` is the node's `[a v]` datoms. These are already in hand from the EAVT seek, compared by value in an
  in-memory `clojure.core.cache` (no digest collision risk), or digested with `seon.id/digest` when the key must
  outlive the JVM.
- `producer-program-identity` is the producer's definition digest plus its callees' digests: `stored-call-reach`,
  computed **once per opening per producer**, not per node (§0.3).
- The key is sound only for renderers that read nothing but their argument. The turn renderer reads the database
  (§0.2), so its hits hold only because closed turns are immutable. The durable fix is to move those reads into
  acquisition. The alternative is to key such renderers by their read evidence, as `render-call` does today, at its
  measured cost.

With the key, opening 2 renders 5 misses, not 174 nodes. On today's costs that is about 5 × 5 ms, plus the one new
turn.

## 5. Libraries: use, don't hand-roll

| Need | Library and seam | Status in this JVM | Verdict |
|---|---|---|---|
| Index seeks, reverse refs | Datahike `d/datoms` `:eavt`/`:avet`; refs AVET-indexed (`db/utils.cljc:312`) | vendored fork | use, through a `seon.db` batch entrance |
| Flat entity maps | Datahike `pull-many '[*]` | vendored | use for the reached set |
| Recursive graph acquisition | Datahike recursive pull (`pull_api.cljc:224-283`) | vendored | **reject**: tree duplication, 805 ms / 1.98 GB |
| Set-at-a-time joins | Datahike Datalog | vendored | use for value joins over many values; 20–40× slower than seeks for refs |
| Parallel map with bounded workers | `core.async/pipeline-blocking` | `org.clojure/core.async 1.10.874-alpha3` in `deps.edn` | use |
| Content-keyed cache | `clojure.core.cache` / `core.memoize` | 1.1.234 / 1.1.266 on the classpath (transitive), already used by `seon.sci.eval` memos | use |
| Per-projection memo | `seon.schema/projection-cache-value` (`schema.clj:422`) | first-party, used by `entity-owners` | use for the pair and value-edge index |
| Topological sort | `clojure.tools.namespace.dependency` | 1.5.0 is on the **running** classpath (from `~/.m2`), but not in `clojure -Spath` of the base deps: dev-only. `depend` refuses cycles | **do not depend on it**: the 12-line DFS handles cycles as handles |
| Topological sort | weavejester/dependency | only in `~/.m2`; not a dependency | no |
| Call reach | `seon.db/stored-call-reach` (`db.clj:1429`), already an AVET/EAVT walk | first-party | reuse, memoized per opening |

## 6. Recommended composition

```clojure
;; one held database value, one projection, one agent fork
(-> seeds
    (walk db {:depth d :reverse (declared-reverse-attrs projection)})    ; levels of seeks
    (order)                                                              ; DFS postorder, first-tx children
    (select projection viewer face)                                      ; memoized per [schema face viewer fp]
    (render-misses cache {:ctx fork :workers 8}))                        ; pipeline-blocking over misses only
```

1. **Index (once per projection):** `render-pairs` (schema → pair) and `value-edges` (attribute → target identity),
   both as `projection-cache-value` entries beside `entity-owners`. Cost 5.9 ms / 18 MB per fingerprint.
2. **Walk (per level):** one batch of EAVT seeks for the frontier; AVET seeks for declared value edges and declared
   reverse refs. Cost O(frontier datoms + value edges), measured at **~1 µs per datom plus ~4.6 µs per value-edge
   seek** (root: 0.8 ms for 176 nodes; task 132fee at depth 3: 6–11 ms). Depth, `max-nodes` and hop-3 counts stay
   query-work bounds (task-context §2.3).
3. **Order:** postorder with first-tx children; back-pointers render as handles. About 1 ms per 500 nodes.
4. **Select (per distinct `[schema face viewer]`):** 1.3 ms uncached; a map lookup after that. Memoize
   `public-functions-in` per (snapshot, namespace).
5. **Render (per miss):** direct compiled producer, about 1.5 ms (non-turn mean); SCI render function through
   `kernel/invoke` with its function value, about 0.4 ms; producer program identity once per opening. Misses fan out
   through `pipeline-blocking` on the agent's one fork, where 8 workers gave 4.7–5.5× on 134 nodes.
6. **Reuse:** a core.cache keyed `[content producer program-identity face profile]`. The measured consecutive-opening
   hit rate is 97%.

**Target for root's opening:** acquisition under 10 ms, order under 1 ms, selection a few map lookups, and renders
equal to the misses. That is 5 misses (under 50 ms) on a warm opening; a cold one is bounded by the slowest renderer
after §0.2 is fixed.

**Deletions it enables** (candidates; each needs its caller map, and several overlap building-block groups Q1-Q6,
which own the credit):

- `walk.clj:69-708`, entity acquisition by per-concern pulls (about 640 lines). Callers remain in `bootstrap.clj`
  (`root-acquisition`, `join-membership`, `entity-lookup`, `root-pull-plan`), `web.clj` and 9 test namespaces. The
  BFS plus order prototype is about 45 lines.
- The per-call program-evidence and config work in `call-cache-evidence` (`render.clj:792-831`) and the
  retained-call reuse check, replaced by the content key. How much of `render-call` (`render.clj:1488-1671`, 184
  lines) goes is booked with building block S2's retention extension, so no conflicting ledger is added here.
- The hand edge lists in `fn.clj:1692-1712` rule 1, once `:seon.fn/reference-to` is declared on
  `:seon.program/edge-symbol`.

## 7. Priced options for what is uncertain (simplest viable first)

1. **The level step.**
   - **A (recommended):** index seeks through a new `seon.db` batch entrance: about 25 lines, hours. 20–40× cheaper
     on refs. It needs one evidence record per level, not per datom.
   - **B:** one Datalog query per level through `seon.db/q`: 0 new entrances. 5–11 ms per 176-node level; evidence
     is free.
   - **C:** Datahike recursive pull. Rejected: 805 ms / 1.98 GB and a tree.
2. **Parallelism.**
   - **A:** sequential plus the content cache: 0 lines. A warm opening is about 5 misses.
   - **B (recommended once §0.2 is fixed):** `pipeline-blocking` over misses on the agent's one fork: about 15 lines.
     4.7–5.5× at 8 workers. It costs threads per opening and loses nothing but ordering, which the output
     reassembles by postorder index.
   - **C:** a fork per worker: +8 forks per opening, no measured gain.
3. **Value-edge declaration.**
   - **A (recommended):** `:seon.fn/reference-to` on `:seon.program/edge-symbol`, `:seon.fn/keywords` and
     `:seon.task/tasks`: about 3 declaration lines plus the projection pass (~10 lines).
   - **B:** a hand map in the walker: about 5 lines, but a hand-maintained list, which is banned.
   - **C:** derive by named-schema equality: 0 lines, but it fails for `:seon.fn/sym` (bare `:qualified-symbol`)
     and `:seon.schema/key` (`:keyword`).
4. **Database-reading renderers (the turn).**
   - **A:** key them by read evidence as today: 0 lines, and keeps §0.4's cost.
   - **B (recommended):** move `render-run-ai`'s 13 queries per turn into acquisition, as child nodes of the turn:
     about a day in the transcript owner, overlapping building block Q6.
   - **C:** exclude turns from parallel rendering: 0 lines, but they stay the critical path.

## 8. Unresolved risks and out-of-scope findings

This lane writes one file, so root files these after searching for existing issues:

1. A refusal that carries its request (ctx and database) costs 4.6 s and 22.8 GB to admit (§0.1; `value.clj:249-257`
   and `kernel.clj:642`). Suggested: `docs/seon/issues/a-refusal-that-carries-its-request-costs-gigabytes-to-admit.md`.
   Also worth checking: whether `render.value/node-id` should derive an id for anonymous roots rather than refuse
   (building block §3a says anonymous values get occurrences).
2. The turn renderer does 13 queries per turn and takes up to 1.86 s for one turn (§0.2). Suggested:
   `docs/seon/issues/the-turn-renderer-queries-the-database-per-turn.md`.
3. Program evidence is recomputed per render call, at about 4 ms (§0.3). Suggested:
   `docs/seon/issues/render-call-recomputes-producer-reach-per-node.md`.
4. `public-functions-in` scans the whole program snapshot per selection (§1a). It belongs with item 3 as the same
   class: per-call work that belongs once per snapshot.
5. `seon.db/datoms` costs 11× the raw seek (§2a). This matters for any owner-routed walk.
6. The `:seon.render/ai` facts are stored as strings (§0.6), and the stored subset lacks inherited pairs. They
   should not be used as the index.
7. `:seon.task/task` has no render pair (§1b). Task-context §3.4 declares the pair on `:seon.agent/context`
   instead, which may be sufficient.
8. The half-gigabyte issue is not reproduced on pid 59566 (§0.7). Its owner should re-measure before fixing, and
   compare with item 1.
9. Single samples under concurrent lane load. The parallel figures are wall-clock on an 18-core machine and were
   not repeated. **RESET NEEDED: no.**
