# Datahike vs Datascript as the agent's template environment — Datalog at session start, in QuickJS

**Date:** 2026-05-08 (Friday afternoon, Bangkok)
**Author:** research agent under Sean's direction
**Question:** Sean's instinct (2026-05-08): instead of hand-rolling an in-memory EAVT store wired through `assert/retract/query/schema`, **load a real Datomic-like database into the QuickJSContext at session start as a template environment** — schema (and optionally pre-seeded facts) already there. The agent learns to write Datalog patterns and transactions, not our hand-rolled `assert(e,a,v)`. Two named candidates from `replikativ/datahike`: **datahike-js** (the npm package, advanced-compiled CLJS) and **datahike-cljs** (compile-from-source). And the alternative we should test honestly: **Datascript** (Tonsky's lighter Datalog DB used in production by Roam, Logseq).
**Builds on:** [`2026-05-08-verifiers-jssandbox-integration.md`](2026-05-08-verifiers-jssandbox-integration.md) (Path A: Verifiers + Node sidecar with `@sebastianwessel/quickjs`, one QuickJSContext per trajectory) and the [brainstorm-decisions](../2026-05-07-brainstorm-decisions.md) primitive surface (`assert / retract / query / schema / embed / nearest / note / define / call / exec`).

---

## 1. TL;DR

**Recommendation: Datascript, not Datahike. Load Datascript into the QuickJSContext at session start; expose an the agent-namespaced wrapper (`db.transact`, `db.q`, `db.pull`, `db.entity`, `db.schema`) that the agent calls. Drop the hand-rolled `assert / retract / query / schema` primitives — they are now thin wrappers over Datascript that exist only as a stable JSON-RPC tool surface for Verifiers.**

Reasoning, in one paragraph: both Datahike and Datascript ride the same advanced-compiled-ClojureScript path, so Sean's "ClojureScript should run well inside QuickJS-emscripten" instinct is correct in principle. But the verified bundle sizes differ by **~6.6×** — Datascript ships **465 KB** as a single zero-dependency `datascript.js` (npm registry, v1.7.8), while Datahike-as-published ships **~3.09 MB** as `datahike.js.api.js` (npm registry, v0.7.1661 `@next`) with its konserve storage layer, hitchhiker-tree indexing, history machinery, and node-filestore externs all compiled in. At Phase-0 we want **in-memory only**, and Datahike's value-add over Datascript (durable storage, history-as-of-asof, hitchhiker-tree for billions of datoms) is exactly what we don't need. Datascript was *designed* for the "throwaway in-memory DB on a constrained JS engine" workload — that's literally Tonsky's stated thesis ("more like data structures than databases (think Hashmap)"). Datahike is the server-shaped sibling and brings server weight. Below in §A, §B, §D, §E I show the verified numbers, the QuickJS-compatibility hunt through the source, and the spike plan.

### What Sean's instinct gets right

- ClojureScript advanced-compiled output is **almost-pure ES** — `goog.*` runtime + standard `Date/Math/JSON/Map/Set`. Closure Compiler renames everything; there are no surprise late-bound globals. This is exactly the shape QuickJS-emscripten handles cleanly.
- Datalog-as-language for the agent is a real upgrade over five hand-rolled primitives. The query language itself is a small DSL the agent can learn and compose, and it's *queryable* in a way EAVT-tuple-shaped APIs aren't. Pull patterns + parameterized queries + `:in` clauses give the agent more leverage with the same primitive count.
- "Template environment loaded at session start" is the right framing. Schema is declarative data; we transact it once into the empty DB during sandbox init; the agent inherits a *typed world* rather than a blank tuple store.

### What Sean's instinct should be tempered on

- **Datahike is not the right CLJS Datalog for QuickJS.** It's the server-shaped one — built for konserve storage, hitchhiker-tree on disk, distributed kabel-writer streaming, optional history, schema-on-write strictness. The published JS bundle drags all of that in. Datascript is the Datalog-at-the-client/edge member of the same family.
- **`shadow-cljs`-advanced is not "small by default."** Advanced compilation is aggressive about dead-code elimination *within* what's reachable, but the included `cljs.core` runtime + `core.async` machinery is sizable. Datahike's published bundle proves this — 3 MB is what you get when you ship the Datomic-compatible feature set in CLJS. Datascript's 465 KB is what a *stripped* CLJS Datalog looks like.
- **CLJS persistent-data-structure overhead in QuickJS is real.** Every `assoc` builds a new map; QuickJS has no JIT. At Phase-0 trajectory scale (a few thousand DB ops per trajectory, ~few million per training run), Datascript should comfortably handle this — but it's worth budgeting **2-5× the V8 cost per op (UNVERIFIED estimate)** and measuring in the spike before scaling up.

### Honest caveats

1. **Datalog-EDN-as-string is the agent's main learning surface.** Both Datahike and Datascript expect the query as an EDN string (`'[:find ?name :where [?e :name ?name]]'`) or as a JS-array reflection of the same EDN AST. Qwen3.6's JS prior is much stronger than its Datalog/EDN prior. Few-shot prompting in the Phase-0 system prompt will need to carry 5-10 examples covering find-rel / find-coll / find-tuple / find-scalar / parameterized / aggregate. This is real prompting work; not a blocker.
2. **Datascript-in-QuickJS is UNVERIFIED at the integration level.** I can verify size, dependencies, and source-level compatibility (no Node built-ins, no Buffer, no `process`, no `require`); I cannot verify zero-runtime-error end-to-end in `@sebastianwessel/quickjs` from desk research alone. The §E spike does this in 2 hours and is the load-bearing test for the whole recommendation.
3. **State persistence across `evalCode` calls.** The integration doc claims one-QuickJSContext-per-trajectory survives between `evalCode` invocations. Datascript's `Conn` is a CLJS `atom` stored in a JS variable — survives if `globalThis.__db` holds it. Confirmed by reading `src/datascript/conn.cljc` shape (atom-wrapping). Verified-by-design; the spike confirms in practice.
4. **Sean named Datahike specifically.** Datahike-js is the more polished surface today (built TypeScript types, Promise-based wrapper, named test suite). Picking Datascript means we use a less-polished published JS API but inherit a 6.6× smaller bundle and a runtime that's been battle-tested in browser-constrained environments by Roam Research, Logseq, Athens, and Precursor — companies that *had* to make CLJS Datalog work in tight client environments. For QuickJS, "battle-tested in browsers" is a stronger Phase-0 signal than "polished server API."

---

## 2. The two Datahike paths (§A1, §A2)

### 2.1 datahike-js — the published `datahike@next` npm package (§A1)

**API surface (verified — read in `npm-package/test.js` + `doc/javascript-api.md`):** Promise-based, fully async, named functions match `datahike.api` minus `!`/`?` suffixes. The set the agent would touch:

| Function | Purpose | Async |
|---|---|---|
| `d.createDatabase(config)` | initialize empty store | yes |
| `d.connect(config)` | get a `conn` (atom-shaped ref) | yes |
| `d.transact(conn, txData)` | write facts; returns `{tx-data, db-before, db-after}` | yes |
| `d.q(edn-string, db, ...args)` | Datalog query | yes |
| `d.pull(db, pattern, eid)` | pull entity by pattern | yes |
| `d.pullMany(db, pattern, eids)` | batch pull | yes |
| `d.datoms(db, ':eavt'\|':avet'\|...)` | raw-index access | yes |
| `d.entity(db, eid)` | entity ref (CLJS object — see caveat) | yes |
| `d.schema(db)` | introspect schema | yes |
| `d.db(conn)` | snapshot db value | yes |
| `d.history(db)` / `d.asOf(db, t)` / `d.since(db, t)` | temporal | yes |
| `d.release(conn)` / `d.deleteDatabase(config)` | teardown | yes |

Non-the agent: `loadEntities`, `seekDatoms`, `tempid`, `gcStorage`, `filter`, `withDb`, `dbWith`, `indexRange`, `entityDb`, `isFiltered`. We don't expose these to the agent at Phase-0.

**Bundle size (VERIFIED — npm registry):** `datahike@0.7.1661` unpackedSize = **3,088,611 bytes (~3.09 MB)** for the single `datahike.js.api.js` published file. Zero npm `dependencies` (everything CLJS-side is bundled). Source: `https://registry.npmjs.org/datahike` (queried 2026-05-08).

**QuickJS compatibility (analyzed in source):**

- Reads of `src/datahike/js/api.cljs` (lines 1-150) confirm: the only Node-leaning code is a guarded `(when (and (exists? js/require) (fn? js/require)) (try (js/require "./konserve.node_filestore") (catch :default _ nil)))`. **This no-ops cleanly under QuickJS** because `js/require` is undefined. The `:memory` backend stays functional.
- `cljs.core.async` channels are used internally; the published API's `maybe-chan->promise` wraps them as Promises. QuickJS supports Promises (it's ECMAScript-2023). No issue.
- The `shadow-cljs.edn` `:npm-release` build target uses `:output-feature-set :es2020`. QuickJS-emscripten supports up to ES2023 for most features. No incompatibility.
- The `externs/node_fs.js` is for Closure Compiler's renaming pass when the file backend is reachable; doesn't introduce runtime code into the `:memory` path.
- BigInt warnings from `persistent-sorted-set` (per `doc/javascript-api.md` line 384). QuickJS-emscripten supports BigInt. No issue.

**My conclusion:** datahike-js *should* run inside `@sebastianwessel/quickjs` for the in-memory-only workload, but at 3 MB compiled-CLJS we are paying the full server-shaped cost for the `:memory` subset. This is wasteful when Datascript exists.

### 2.2 datahike-cljs — compile-from-source path (§A2)

The build path: invoke `shadow-cljs` with a custom build target (`:output-feature-set :es2020`, `:optimizations :advanced`) on a custom `:entries` set that excludes konserve.node-filestore, kabel-writer, and the file backend code paths. Reading `shadow-cljs.edn` confirms the existing `:npm-release` target is the closest starting point — we'd fork it locally.

**Estimated bundle reduction (UNVERIFIED — no published lean Datahike build to measure against):** dropping konserve-node-filestore, hitchhiker-tree's storage tier, kabel-writer, and history machinery probably trims the 3 MB to **~1.5-2 MB** (rough estimate based on the size of the included subdirs `src-hitchhiker-tree/`, `src-kabel/`, `src-secondary/`). Even after this work, **Datascript's 465 KB is still significantly leaner without us building anything**. So the cost-benefit case for compile-from-source Datahike is poor: 1-2 days of CLJS build engineering to land somewhere that's still 3-4× heavier than off-the-shelf Datascript.

**Verdict on the two Datahike paths:** datahike-js is feasible but heavy; datahike-cljs is engineering work to land on something that's still heavy. Both lose to the alternative.

---

## 3. The alternative — Datascript (§B)

**Status (VERIFIED):**

- npm package `datascript` v1.7.8, latest published, single-file `datascript.js` produced by ClojureScript advanced compilation.
- `unpackedSize` = **465,248 bytes (~454 KB)**. Source: `https://registry.npmjs.org/datascript/latest` (queried 2026-05-08). Zero npm dependencies.
- License EPL (compatible with our use).
- Production users (per the project README): Roam Research, Logseq, Athens, Precursor, PartsBox, Hulunote, Cognician, Zetawar. **Every one of these is a browser-first or browser-only product** — Datascript is the CLJS Datalog that's been pushed through tight client-side environments at scale.
- Last release 1.7.8 dated 2021 in the repo's `release.clj` history; the project is mature/stable rather than active. **For our use this is a feature, not a bug** — we want a stable substrate, not a moving target during training.

**API surface (read in `release-js/test_include_node.js` + the project's npm wrapper):**

```javascript
const d = require('datascript');
const conn = d.create_conn(schema);          // schema is optional
d.transact(conn, [[":db/add", -1, "name", "Ivan"]]);
const res = d.q('[:find ?e ?v :where [?e "name" ?v]]', d.db(conn));
// => [[1, "Ivan"]]
```

The full named JS API (per the project's longstanding shadow-cljs surface):

| Function | Purpose |
|---|---|
| `d.create_conn(schema?)` | empty connection (schema is a JS object) |
| `d.transact(conn, txData, txMeta?)` | sync write |
| `d.q(query, db, ...args)` | sync Datalog query |
| `d.pull(db, pattern, eid)` | sync pull |
| `d.pull_many(db, pattern, eids)` | batch pull |
| `d.entity(db, eid)` | entity ref (lazy reads) |
| `d.datoms(db, index, ...components)` | raw index access |
| `d.db(conn)` | snapshot |
| `d.empty_db(schema?)` | new empty db |
| `d.db_with(db, txData)` | non-mutating transact, returns new db |
| `d.touch(entity)` | force-realize lazy entity |

**Synchronous, not Promise-based.** This is actually an advantage in QuickJS — no event-loop coordination needed; every `evalCode` returns directly with the result. The agent's emitted JS is plain `const r = db.q(...)`, not `await db.q(...)`. Less for the agent to learn and less for the host to plumb.

**No storage backend complexity.** Datascript is in-memory-only by design. Persistence (where wanted) is "serialize the db value to disk via `datascript-transit`" — separate library, not in our path.

**QuickJS compatibility analysis:**

- The release-js wrapper (`release-js/wrapper.prefix` + `wrapper.suffix`) produces a UMD-style module: CommonJS / RequireJS / global-fallback. `@sebastianwessel/quickjs` exposes `nodeModules` for CommonJS-style mounting. **Confirmed compatible by design.**
- No `process`, no `Buffer`, no `fs`, no `setTimeout`/`setInterval`/`setImmediate`, no `crypto`, no `IndexedDB`. Datascript uses only `Date`, `Math`, `Object`, `Array`, `Map`, `Set`, `JSON`, `String`, `Number`, `Boolean` — all ECMAScript-standard and present in QuickJS-emscripten.
- `goog.*` runtime is bundled inline by the Closure Compiler's `:advanced` mode; not a separate runtime dependency.
- The Datascript README explicitly recommends adding `:externs ["datascript/externs.js"]` for shadow-cljs consumers — we don't need this since we use the pre-built bundle.
- **Browser-tested at production scale** through Roam, Logseq, Athens, etc. QuickJS's ES surface is closer to old-Safari than Roam's targets, but the API surface (no Node, no DOM) overlaps cleanly.

**My conclusion:** Datascript is the right Phase-0 fit. **Smaller, sync-API (one fewer concept for the agent), production-tested in cramped JS engines, zero-dependency, EPL-licensed.** It's exactly what Tonsky designed it to be — "a basic building block" for state-tracking client apps.

---

## 4. Template-env shape — what does "loaded at session start" mean concretely (§C)

### 4.1 Pre-declared schema

The brainstorm-decisions doc ([line 38-46](../2026-05-07-brainstorm-decisions.md)) sketched starter entity types `:person / :event / :goal / :value / :project / :org`. Concrete Datascript schema (passed to `create_conn`):

```javascript
const schema = {
  // identity / refs
  ":person/id":        { ":db/unique":      ":db.unique/identity" },
  ":org/id":           { ":db/unique":      ":db.unique/identity" },
  ":event/id":         { ":db/unique":      ":db.unique/identity" },

  // many-cardinality + reference attrs
  ":person/parent-of": { ":db/cardinality": ":db.cardinality/many",
                         ":db/valueType":   ":db.type/ref" },
  ":person/works-at":  { ":db/valueType":   ":db.type/ref" },
  ":person/values":    { ":db/cardinality": ":db.cardinality/many",
                         ":db/valueType":   ":db.type/ref" },
  ":goal/owner":       { ":db/valueType":   ":db.type/ref" },
  ":event/participants": { ":db/cardinality": ":db.cardinality/many",
                           ":db/valueType":   ":db.type/ref" },

  // tag-like many-strings
  ":person/locales":   { ":db/cardinality": ":db.cardinality/many" },
};
```

Datascript supports a **schema-flexibility-by-omission** pattern — only attrs that appear in the schema are constrained; everything else is free-form (string-or-number-or-whatever stored verbatim, no `:db/cardinality/one` enforcement). **This is what we want at Phase-0**: declared structure for the load-bearing entity types, agent has freedom to extend.

Datahike has explicit `:schema-flexibility :read` (permissive) vs `:write` (strict) modes, with `:read` being the analog of Datascript's default. `:read` is what the agent needs (§D below).

### 4.2 Pre-seeded facts (the "3 months prior chat" example)

Per brainstorm-decisions: scenarios where the agent inherits prior knowledge ("user has a kid named Aiden, kid goes to Lincoln Elementary"). Translated to Datascript transactions at session start:

```javascript
// Sandbox-init step — runs once before agent's first turn
db.transact(conn, [
  // The user (entity 1)
  { ':db/id': -1, ':person/id': 'user', ':person/name': 'Sean' },
  // The kid (entity 2)
  { ':db/id': -2, ':person/id': 'aiden', ':person/name': 'Aiden', ':person/age': 7 },
  // The school (entity 3)
  { ':db/id': -3, ':org/id': 'lincoln-elem', ':org/name': 'Lincoln Elementary',
    ':org/type': ':org.type/school' },
  // The relationships
  [':db/add', -1, ':person/parent-of', -2],
  [':db/add', -2, ':person/attends', -3],
]);
```

After this, the agent's first turn sees a populated DB and can immediately answer `"how old is the user's kid?"` via:

```javascript
db.q('[:find ?age . :in $ ?u :where [?u :person/parent-of ?k] [?k :person/age ?age]]',
      db.db(conn),
      [':person/id', 'user']);  // lookup ref for user entity
// => 7
```

### 4.3 The primitive surface — agent-facing tool definitions

Two design options:

**Option (a): Drop our hand-rolled primitives entirely; agent calls Datascript directly via the JS host.**

```javascript
// Agent-emitted JS in evalCode:
db.transact(db.conn, [{ ':person/id': 'aiden', ':person/grade': 2 }]);
const grade = db.q('[:find ?g . :where [?p :person/id "aiden"] [?p :person/grade ?g]]',
                   db.db(db.conn));
```

Pros: agent learns one substrate, transferable knowledge (Datalog is real), full power. Cons: query EDN-string is unfamiliar; agent has more API to learn; harder to audit individual calls in the host-side trace.

**Option (b): Keep `assert / retract / query / schema` as thin the agent-named wrappers over Datascript; agent uses the wrappers.**

```javascript
// Agent-emitted JS:
assert('aiden', 'grade', 2);                       // → db.transact under the hood
const g = query({ entity: 'aiden', attr: 'grade' }); // → db.q under the hood
```

Pros: identical surface to brainstorm-decisions; cleaner trace; failure-injection / cheating-detection easier (we own the wrapper boundary). Cons: wraps away the Datalog leverage we just bought; agent learns the agent's idiom not Datalog.

**Recommendation: hybrid.** Expose **both layers** in the QuickJSContext globals:

- `assert / retract / query / schema` for the curriculum's first ~3 episodes (the trivial-recall and preference-update episodes from brainstorm-decisions §curriculum). These are 1-line wrappers over `db.transact` / `db.q`.
- `db.transact / db.q / db.pull / db.entity / db.datoms / db.schema` exposed *also* — the agent gets to use them as soon as it discovers them, and in the curriculum episodes 4-7 (multi-entity / long-arc / cross-cultural) the wrappers are insufficient and the agent will gravitate to the richer Datalog surface.

The trace records both layers separately — this is a useful training signal (does the agent gradually shift toward `db.q` for complex retrieval? when?). The mapping table:

| the agent primitive (brainstorm) | Datascript-backed implementation |
|---|---|
| `assert(entity, attr, value)` | `db.transact(db.conn, [['+', lookupOrAlloc(entity), attr, value]])` |
| `retract(entity, attr, value)` | `db.transact(db.conn, [[':db/retract', lookup(entity), attr, value]])` |
| `query(pattern)` | `db.q(buildEdnFromShape(pattern), db.db(db.conn))` |
| `schema()` | `db.schema(db.db(db.conn))` |
| `embed / nearest / note / define / call / exec` | unchanged from integration doc |

The wrapper layer is ~30 lines of JS injected into the QuickJSContext globals at sandbox init; we own it; we can instrument it.

### 4.4 The `define()` + spec/test contract — does it change?

**No.** `define(name, spec, impl, tests)` admits agent-written JS functions that call the primitives. Whether the primitive substrate is hand-rolled or Datascript-backed, `define()` doesn't care — its contract is "the function passes its spec, passes its tests, gets stored in the per-user library." The substrate change is invisible to `define()`.

What *does* change: the agent's `impl` bodies now have access to the richer Datalog surface, so the kinds of functions it writes will be more sophisticated. A function like `peopleAtSchool(orgName)` becomes a one-line `db.q(...)` rather than a hand-rolled scan. **This is exactly the kind of capability lift Sean's instinct is reaching for.** The LoRA earns more from training because the per-user functions are more interesting.

---

## 5. Honest gotchas (§D)

### 5.1 Schema-on-read vs schema-on-write

Datahike's `:write` mode rejects unknown attributes; `:read` mode is permissive. Datascript has only the permissive default. The brainstorm-decisions agent-learns-its-own-idiom principle says **permissive is correct** for the agent. Concretely: if the agent decides episode-3 needs a `:person/inferred-mood` attribute we never declared, the DB shouldn't reject it — the curriculum's job is to reward useful structure, not to gate it through us.

But **schema introspection becomes essential**. The agent needs `db.schema(db.db(conn))` working so it can read what's already declared. Datascript exposes this; Datahike exposes this; both are fine.

### 5.2 Datalog query syntax in JS — the prompting surface

The agent emits JS that constructs Datalog queries. Two formats both libraries accept:

**EDN-as-string (simple, what `test.js` shows):**
```javascript
db.q('[:find ?n :where [?e :name ?n]]', someDb);
```

**EDN-as-JS-array (more programmatic; arrays nest like Lisp s-expressions):**
```javascript
db.q([':find', '?n', ':where', ['?e', ':name', '?n']], someDb);
```

The string form is cleaner; the array form is easier for the model to assemble incrementally. Qwen3.6's prior is stronger on JS arrays than on raw EDN strings.

**Few-shot prompting estimate for Phase-0 system prompt:**

- 1 example each of: find-rel (`[:find ?e ?n :where ...]`), find-coll (`[:find [?n ...] ...]`), find-tuple (`[:find [?n ?a] ...]`), find-scalar (`[:find ?n . :where ...]`).
- 1 example of parameterized query with `:in`.
- 1 example of multi-clause (join) query.
- 1 example of pull pattern: `db.pull(d, [':person/name', ':person/age'], eid)`.
- 1 example of `db.entity` lazy access.

That's **8 examples, ~80-120 tokens each, ~700-900 tokens of prompting overhead.** Sustainable. Not free.

### 5.3 Performance at trajectory scale

Per the question prompt: ~5-30 turns × 5-50 transactions/turn × 5-50 queries/turn = a few thousand DB ops per trajectory; ~1K trajectories per training run = ~few million ops total.

**Datascript benchmark expectations:**

- In V8 (Node.js, JIT-compiled), Datascript transacts and queries millions of datoms per second on commodity hardware (per Tonsky's published benchmarks in `bench/`, dated but representative).
- In QuickJS-emscripten (no JIT, WASM-interpreted), expected slowdown is **2-5×** for Datascript-shaped workloads (UNVERIFIED rough estimate; cross-cuts of QuickJS-vs-V8 microbenchmarks suggest 3-10× for general JS, but CLJS persistent data structures sit in the slower part of that range because they hit allocator + property-access hot paths).
- For our scale (a few thousand ops/trajectory × ~100ms wall budget per trajectory turn), this is comfortably fine. **Sandbox time will remain 3-4 orders of magnitude below LLM time at Phase-0 scale**, mirroring the integration doc's conclusion.

Datahike's hitchhiker-tree supports tens of millions of datoms in-memory. We're nowhere near that; it's overkill.

### 5.4 CLJS persistent-data-structure overhead in QuickJS

CLJS's `PersistentHashMap` and `PersistentTreeMap` allocate aggressively (every `assoc` is a new map with structural sharing). QuickJS's allocator is fine but unjitted; the slow path here is property-name hashing + bit-array traversal in `cljs.core`. Datascript uses `BTSet` (a sorted set of datoms) as its index substrate — better cache locality than full hash-trie maps.

**Verdict: real but manageable at our scale.** Datahike's `persistent-sorted-set` with hitchhiker-tree (a separate disk-friendly data structure) would be heavier in QuickJS than Datascript's lighter `BTSet` — another small reason Datascript fits better.

### 5.5 State persistence across `evalCode` calls — the load-bearing test

Per [integration doc §3.1](2026-05-08-verifiers-jssandbox-integration.md): one QuickJSContext per trajectory, surviving between `evalCode` invocations for the lifetime of the rollout.

**Datascript `Conn` is a CLJS atom** (`atom (atom (empty-db ...))` shape, per source). At the JS level after advanced compilation it's a renamed object with a `state` field holding the current DB value. **It's a regular JS object reference**; storing it in `globalThis.__db` after sandbox init makes it survive across `evalCode` calls in the same QuickJSContext.

**Sandbox init plan (~30 lines of host JS, run once before agent's first turn):**

```javascript
// In Node host, before passing context to Verifiers rollout
await sandbox.evalCode(`
  const d = require('datascript');               // mounted via nodeModules
  globalThis.db = {
    conn: d.create_conn(${JSON.stringify(schema)}),
    transact: (txData) => d.transact(globalThis.db.conn, txData),
    q: (query, ...args) => d.q(query, d.db(globalThis.db.conn), ...args),
    pull: (pattern, eid) => d.pull(d.db(globalThis.db.conn), pattern, eid),
    entity: (eid) => d.entity(d.db(globalThis.db.conn), eid),
    schema: () => d.db(globalThis.db.conn).schema,
    db: () => d.db(globalThis.db.conn),
  };
  // Load pre-seeded facts
  globalThis.db.transact(${JSON.stringify(seedFacts)});
  // the agent-named wrappers
  globalThis.assert = (e, a, v) => globalThis.db.transact([{ ...lookupRef(e), [a]: v }]);
  globalThis.retract = (e, a, v) => globalThis.db.transact([[':db/retract', lookup(e), a, v]]);
  globalThis.query = (pattern) => globalThis.db.q(buildEdn(pattern));
`);
// Subsequent evalCodes can use globalThis.db.* and globalThis.assert / query / retract
```

**This is the spike's most important verification** — confirm the mounted `datascript` module loads cleanly under `nodeModules`, confirm `globalThis.db` survives, confirm the wrappers work.

---

## 6. Recommendation + spike plan (§E)

### 6.1 Pick

**Datascript.** The Phase-0 fit dominates: 6.6× smaller bundle; sync API; production-tested in browser-constrained engines for ~10 years; zero dependencies; EPL-licensed; battle-tested at Roam / Logseq scale; "throwaway in-memory DB" is exactly Tonsky's design intent.

The hand-rolled `assert / retract / query / schema` primitives stay as thin wrappers over Datascript for trace clarity and curriculum-episode-1-3 simplicity, but the agent also has direct access to `db.transact / db.q / db.pull / db.entity` for episodes 4-7 where the Datalog leverage matters.

### 6.2 Engineering cost to integrate

**~1.5-2 engineering days, on top of the Path-A spike from the integration doc.**

Decomposition:

| Task | Hours |
|---|---|
| Mount `datascript` v1.7.8 into `@sebastianwessel/quickjs` `nodeModules` config; verify `evalCode` can `require('datascript')` and call basic API | 2 |
| Build sandbox-init script that creates `conn`, transacts schema, transacts seed facts, exposes `globalThis.db.*` and `globalThis.assert/retract/query/schema` wrappers | 3 |
| Build pattern-shape → EDN-string translator for `query(pattern)` wrapper (the "JS-shape pattern → Datalog EDN" bridge) | 2 |
| Build entity-lookup helper (`lookupOrAlloc(entityName)` resolving `':person/id'`-keyed lookup or allocating new tempid) | 2 |
| End-to-end test: empty sandbox → schema transact → seed facts transact → 5 query shapes → assert + retract round-trip → schema introspection | 2 |
| Spec the few-shot Datalog examples for the Qwen3.6 system prompt (8 examples, ~900 tokens) | 1 |
| Trace instrumentation: log every primitive call + every `db.*` call separately; expose to host trace for watchdog | 2 |

**Total: ~14 hours = 1.75 engineering days.** Single-developer, sequential. The integration-doc spike was 5-7 days; this adds ~2 days for an integrated total of **7-9 days.**

### 6.3 Updated spike plan

The integration doc's §10 spike was 3 days for the Path-A baseline (Verifiers + Node sidecar + QuickJS + EAVT mock). With this template-env work folded in, the revised plan is:

| Day | Goal | Acceptance |
|---|---|---|
| 1 | Verifiers ↔ Node sidecar JSON-RPC working; QuickJSContext per trajectory; basic `evalCode`; **`require('datascript')` works**; smoke `db.transact / db.q` in QuickJS | 5 trajectories complete with 1 round-trip query each |
| 2 | Sandbox-init template-env script: schema + 3 entities seeded; `globalThis.db.*` exposed; the agent-named wrappers (`assert/retract/query/schema`) over Datascript | Trajectory inherits a populated DB at start; agent (mocked) round-trips assert + retract; query returns correct count |
| 3 | Real Qwen3.6 driving the loop with 8-example Datalog few-shot prompting; trace records both primitive layer and direct-db layer; watchdog gets the full trace | 10 trajectories with real Qwen output show no `db.*`-related runtime errors; few-shot prompting resolves common Datalog mistakes |
| 4 | **State-persistence stress test** — long trajectory (30 turns, ~1500 ops) verifying `globalThis.db.conn` survives; performance microbench (ops/sec in QuickJS vs Node V8) | < 5× V8 slowdown; no leaks; trajectory completes inside 60s budget |
| 5 | Integration-doc finalization: any QuickJS gaps from real-Qwen output documented; fall-back path to `isolated-vm` evaluated if the gaps bite | Go/no-go on Datascript+QuickJS for Phase-0 |

**The §4 state-persistence stress is the load-bearing acceptance test.** Everything else is engineering plumbing with bounded risk.

---

## 7. References

- Datahike repo: `~/src/reference/datahike/` (shallow clone). Key reads: `doc/javascript-api.md`, `doc/cljs-support.md`, `npm-package/test.js`, `npm-package/test-isomorphic.ts`, `shadow-cljs.edn`, `src/datahike/js/api.cljs`, `externs/node_fs.js`.
- Datascript repo: `~/src/reference/datascript/` (shallow clone). Key reads: `README.md`, `release-js/` (full dir), `release-js/test_include_node.js`, `script/release.clj`.
- Integration doc: [`2026-05-08-verifiers-jssandbox-integration.md`](2026-05-08-verifiers-jssandbox-integration.md) — Path A (Verifiers + Node sidecar with `@sebastianwessel/quickjs`), the substrate this template-env work composes onto.
- Brainstorm decisions: [`../2026-05-07-brainstorm-decisions.md`](../2026-05-07-brainstorm-decisions.md) — the primitive set and curriculum this template-env replaces.
- npm registry queries (2026-05-08): `https://registry.npmjs.org/datascript/latest` (v1.7.8, 465,248 bytes); `https://registry.npmjs.org/datahike` (v0.7.1661 `@next`, 3,088,611 bytes).

---

## 8. What changed our understanding vs the brainstorm

1. **The hand-rolled EAVT primitives were always going to be reinvented poorly.** Datascript already exists, has been production-hardened for a decade, and is 465 KB — there is no engineering-honest argument for hand-rolling our own. Sean's instinct here was right; the brainstorm's primitive-list was a placeholder, not a commitment.
2. **Datahike is the *wrong* member of the family for Phase-0.** Sean named Datahike specifically and the question prompt asked us to be honest if Datascript fit better. It does — by ~6.6× on bundle size and by the entire dimension of "designed for client/edge constraints vs designed for server constraints." This is the kind of distinction that's invisible until you measure.
3. **the agent's primitive surface is now layered, not flat.** Hand-rolled `assert/retract/query/schema` stays as the curriculum-onboarding layer; `db.transact/q/pull/entity` is the leverage layer the agent grows into. The trace records both — gradient between them is a useful training signal we didn't have before.
4. **The `define()` + spec/test contract is unaffected.** The substrate change is invisible to it, by design. This is a happy property of the integration-doc architecture: Datascript slots in cleanly without disturbing the function-library moat.
