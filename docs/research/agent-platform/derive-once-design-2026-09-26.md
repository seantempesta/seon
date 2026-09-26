---
type: design
lane: derive-once-design-opus
date: 2026-09-26
status: design + measurement only; no source edits; root and Astra review before any slice
base: HEAD 97ca61d77; live default pid 89341 (booted 21:35:37Z, uptime 1,599 s at the probes), JVM mode, read-only, MCP session derive-once-design-opus
supersedes-or-orders: cache-central S2–S4; shrink-db P2 P3 D8; shrink-eval slices 1 2 5; shrink-test S3 S4; shrink-web S2 S3 S9; shrink-schema S1 S3; defn-scan remaining S0
---

# Derive once: one discipline for Seon's internal derived values (2026-09-26)

The owner asked, verbatim: "I want the most efficient debug page as possible so we aren't recalculating anything.
We need to rethink how we are processing shit that we need multiple times."

**What exists.** `seon.cache` (Caffeine, one byte dial, `src/seon/cache.clj`) is live on default and holds five kinds.
Every other repeated derivation still keys on the wrong thing:

- a branch connection id (base SCI context, projection revision keys);
- a copy of the data it read (arity edges: ~100k datoms as a key; declaration datoms; whole render results);
- a projection *object*, so a new projection value gets a fresh empty holder;
- nothing at all (`stored-call-reach`, `loaded-program`, `config/effective` per render call).

**Data flow of the fix.** Every derived value becomes one of three things:

1. **An alias.** A cheap name (a Datahike commit id or attribute revision) maps to a content digest.
2. **A content entry.** It is keyed by that digest in `seon.cache`, and is shared by every branch, agent, test,
   MCP reply and page whose inputs are equal.
3. **An assembly.** A whole-program value built from per-element entries keyed by element digest. A one-row change
   recomputes one element and the elements that reach it.

**Principal Clojure approach.** One new pure value, the **program graph**: definition digests, call edges, reverse
edges and Merkle **closure digests**, derived per program content. Every consumer reads it instead of re-walking
the call graph. Measured: the whole graph costs 150 ms cold per JVM, and an edit costs O(reverse reach) of the
edited row (median 26 rows).

Units: time in ms or µs, heap in bytes (MB = 10^6 bytes), agent-facing text in estimated tokens (chars ÷ 4).

## 0. Broken or unproven first

- **Nothing here is built.** The design's numbers come from read-only probes on pid 89341 and from today's plans and
  landings, which are cited per row. The closure-digest numbers come from an unarmed scratch computation in the
  JVM (§9), not from an owner function.
- **The closure probe covered function→function edges only.** 5,385 function rows and 55,271 raw `:seon.fn/calls`
  datoms, filtered to edges whose callee is a function row. Test→function edges and contract→schema edges were not
  included. Their extra cost is estimated, not measured (§4a).
- **Datahike revisions are not what two plans assumed.**
  - `advance-query-cache-context` (`reference-code/datahike/src/datahike/query.cljc:2584-2592`) sets an attribute's
    revision to **the commit id of the transaction that last wrote it**. So revisions are store-global, but they
    are not content: two branches that write equal datoms get different revisions.
  - A value opened at another commit (`versioning.cljc:89-100`, used by `d/commit-as-db` and a new branch
    connection) carries **no per-attribute revisions**, only `:datahike.cache/conservative-revision` = its commit
    id. This matches the shrink-eval live key table, where branch `defn-scan-opus` showed 0 revisions.
  - Speculative values (`db.cljc:444-465`) get a random revision per touched attribute.
  - **Consequence.** A revision or commit id is an *alias* (a cheap within-lineage name). It never replaces the
    content digest. This is why every kind below has two steps.
- **The debug page is not slow while warm on this JVM, but it is not "lookups only" either.** A warm `?n=2` load
  measured 114 ms (details in §5). Of that, 94 `render-call` hits cost 75 ms (0.8 ms per *hit*), and
  `config/effective` ran 94 times for one page.
  - Cold measurements: 359 ms for n=1 (79 KB), then 271 ms for n=2 (223 KB), sharing the n=1 calls.
  - The 2.25 s / 6.8 GB cold n=2 figure is from the debug-context-page landing, on a JVM before P1.
  - I did not reproduce a post-commit reload, because it needs a write on default.
- **One unproven premise per option is named in §8.** No slice may start before its premise probe (§7, slice 0).

## 1. The discipline (five rules)

1. **Key by what is read, never by where it was read.** A key contains content digests: definition digests,
   closure digests, form-set digests, a pulled value, a blob digest. It never contains a connection id, generation,
   branch name, agent id, wall time or projection object. A key that names a connection is a defect, like a stamp.
2. **Two steps: alias → content.** The alias kind maps a cheap name to the content digest:
   - `[kind-alias revisions-of-declared-attributes]` for a value in its own lineage;
   - `[kind-alias commit-id]` for a committed or opened value;
   - on a miss, the digest query.

   The value kind is keyed by the digest, so equal content on any branch hits. Alias entries weigh 128 B.
3. **Whole-program values are assemblies.** If a value changes by one element when one row changes, it is stored as
   per-element entries keyed by element digest. The whole is assembled with persistent `assoc`, O(changed ×
   log n). A derivation that walks the whole program per change is a defect.
4. **Per-call repeats are folds, not caches.** A value asked for twice inside one call is computed once and passed
   down (CLAUDE.md "per-call work that belongs once per evaluation"). Examples:
   - `identity-attributes` 6× per write;
   - `config/effective` per render call;
   - `agent-eid` per bound derivation;
   - `call-cache-evidence` twice per `render-call`.

   `seon.cache` holds only cross-call values of immutable inputs.
5. **Every consumer reads the same entry.** The agent's context, MCP replies, tests and the debug page call the same
   owner function with the same key. A consumer-specific holder is a defect: the per-projection holder, the render
   `shared-cache` atom, `::graph-calls`, `reach-holders` and the arity LRUs are all instances. The page is a view,
   not a cache.

## 2. Inventory: every repeated derived value

Frequency is per contracted `defn` form, per agent form, per test request and per page load. The profile columns are
cumulative on pid 89341 at 1,599 s of uptime: calls, total ms, max ms (armed and inclusive). A "plan" citation means
the number comes from that plan's probe.

| # | value | function of (true inputs) | consumers | how often computed today | measured cost |
|---|---|---|---|---|---|
| V1 | **schema projection** (forms → Malli registry + compiled tables) | schema form digests (3,548) + named-predicate closure digests; contracts are a separate input | writer, SCI arming, render selection, error, config, tests (37 `projection-cache-value` sites) | a **new projection value per contracted defn** (a spec is a projection range, `schema.clj:3057`), with a fresh empty holder; `load-projection` 1.6× per defn (defn-scan) | `load-projection` 175 / 14,566 / 703; `content-projection` 642 / 24,644 / 986; `carried-projection` 131,111 / 27,956; `value-projection` 14,776 / 17,755 |
| V2 | whole-projection tables (`entity-maps`, `reference-id`, `storable-attribute-in?`, `compiled-storage`, shapes) | schema forms only | writer (`write-entity-schemas`), program shapes | re-derived on every new projection: **166,000 armed calls per defn** (defn-scan) | `shapes-in` 632 / 2,314; `entity-maps` 22.8k calls per defn |
| V3 | `declaration-content-key` (declaration datoms as a key) | declaration datoms | projection tiers | twice per declaration write (shrink-db P3) | 644 / 5,916 / 464; 11.7 ms per walk |
| V4 | arity edge base (whole-program call-arity set, bounds, by-caller, by-callee) | `:seon.fn/call-arities` of every row plus declared bounds | writer arity gate, report comparison | per program change; the key copies ~100k datoms | `arity-base` 97 / 29,951 / 8,915; `arity-content-key` 192 / 1,631; caches 92–122 MB (cache-central) |
| V5 | `stored-call-reach` (transitive callees of a symbol) | definition rows along `:seon.fn/calls` | render program evidence (2–3× per render miss), `builder-digest`, `derive-context!` | per render miss, unmemoized | 252 / 891 / 49; **7.5–8.2 ms** for `render-ai`'s 795 symbols (§9) |
| V6 | render program evidence (row maps for the reach, compared row by row) | the producer's reached definition digests | `render-call` | computed twice per miss (shrink-web U10), then compared over 795 rows | `render-call` 196 / 1,111; `call-cache-evidence` 14 ms per warm page |
| V7 | projection builder digest (`builder-digest`, `loaded-builder`) | the closure of `seon.schema/load-projection` | every projection key | per commit memo, via `stored-call-reach` | ~2 ms plus a `commit-as-db` |
| V8 | **base SCI context** | loaded commit + rows differing from it + projection + fault recorder | every agent turn, MCP branch, test member, candidate gate | keyed by `revision-basis` (connection id + generation): **45 derivations for 50 lookups** (shrink-eval) | `derive-base-ctx` 71 / 18,863 / 1,701; 235 ms mean; a fresh branch 2,925 ms and 11.2 GB (task d215b1ebde7c); MAT 6.9 MB each |
| V9 | loaded program value and its digests | the loaded commit id | acquisition, `acquired-database?` | a `commit-as-db` per call, plus a full digest query per derivation | `loaded-program` 91 / 966 / 98; `function-digests` 147 / 4,189 / 301 (×2 per derivation) |
| V10 | test reach index and reach digests | the tests' transitive reach over definitions and schema forms | test selection, admission, reuse | `reach-content` reads **all 11,703 digests + 3,548 forms per call** (12 ms); the cold index takes **98 queries** | `reach-digests` 38 / 60,954 / 10,175; `reach-facts` 16 / 45,121 / 6,536 |
| V11 | changed definition symbols since a basis | definition digests at two commits | `select`, `admit-run` (twice in the writer, shrink-test 1.1) | per request and per admission | `changed-definition-symbols` 18 / 16,561 / 2,565 |
| V12 | program digest / program revisions | all program rows | test admission, adoption, acquisition | per request | `program-written-since?` ~75 ms per admission (task f868dc9e74f5) |
| V13 | error observation selector | error-family declarations | `occurred-since` per turn, error pages | per new projection value, so per defn | `observation-selector` 154 / 5,665; 58.7 ms miss, 0.02 ms hit (shrink-schema) |
| V14 | database attributes (`canonical-database-attributes`) | installed schema forms | config, cluster, web | unmemoized | 23 / 3,258 / 707 |
| V15 | deleted-predicate scan | forms + contracts | every `projection-from-rows` | per load | 179 / 6,851 / 266 |
| V16 | `identity-attributes` (sort of ~1,219 attributes) | installed schema | writer (6× per write), render identity | per call | 3,702 / 1,635; 95× per warm page |
| V17 | `config/effective` | config row + declared projection | render profile, gate, boot | per render call when the request lacks a profile | 1,413 / 2,706 / 306; **94× per page load** |
| V18 | render face (AI text / HTML hiccup of one value) | producer closure + profile + the value (or what the renderer reads) | agent context, MCP reply (`:seon.eval/shown`), debug page, namespace page | page: per load, validated by read evidence over whole retained results; context: once at settlement | `render.value/prepare` 727 / 3,245 / 97; per-face cold 7–85 ms (debug-context landing) |
| V19 | read evidence (`stable-value` + `read-result-digest` over whole results) | the read's result | render retention | per captured read, on every render call | 2.3 M `canonical-value-string` calls and 1.1 s for a 48-node page; the bulk of 6.8 GB cold (landing) |
| V20 | page state (`::graph-calls`, `::invocations`, `::page-results`, `::packages` …) | the renders above | web pages | unbounded atom per branch scope | 69.8 MB at idle (MAT, mem-idle) |
| V21 | history entries (`history-entries`, `entry-basis`) | terminal evaluations (immutable once settled) | MCP reply, transcript, pages | per MCP form; `entry-basis` scans every datom of an entry lacking a read basis | ~2.1 ms per form (shrink-web P5) |
| V22 | analyzer rows per file (clj-kondo + our analysis) | file digest + analyzer identity | publication, boot | per publication (`:all` 43.6 s warm) | owned by perf-boot; kondo keeps its own cache (AGENTS); **out of scope here**, except that its identity should read V23 |
| V23 | *new:* **program graph** (digests, edges, reverse edges, closure digests) | definition rows + `:seon.fn/calls` (+ test and contract edges) | V1 predicates, V5–V8, V10–V12, V18 | does not exist | whole graph cold: digests 8 ms + edges 98 ms + SCC 6.7 ms + hashing 37 ms ≈ **150 ms**; per edit O(reverse reach) |

## 3. The key for each value

The identity ladder is ordered from cheapest to strongest. Every rung is store-global:

- **K0 commit id:** 0.09 µs per read (§9).
- **K1 attribute revisions** of a declared attribute set: O(set) map reads, 142 attributes installed.
- **K2 definition digest** per row: stored as `:seon.program/definition-digest` on function, test and schema rows.
  After `9b6df1ff4` it hashes only the resolver context the definition uses.
- **K3 closure digest** per row (new): the Merkle hash of `[sorted own digests of the row's SCC, sorted closure
  digests of its external callees]`. This is Clerk's `hash-codeblock` rule (`reference-code/clerk` analyzer.clj
  :625-667, web-research §3), with Tarjan SCCs for the 24 mutual-recursion cycles (largest 5).
- **K4 set digests:** the forms digest (schema row digests plus the closure digests of named predicates) and the
  program digest (all row digests).

| # | value kind | alias key (cheap) | content key | notes |
|---|---|---|---|---|
| V23 | `:seon.program/graph` | `[revisions of #{:seon.program/definition-digest :seon.fn/calls :seon.fn/sym :seon.test/sym :seon.schema/key}]`, else `[conservative commit id]` | program digest (K4) | built incrementally from any retained graph by digest-map diff (§4a) |
| V1 | `:seon.schema/forms` (base projection + form-only tables) | revisions of the schema-family attributes | **forms digest** | contracts are *not* in the key: this is what kills defn-scan's remaining S0 |
| V1′ | `:seon.schema/contract` (compiled contract per function) | — | `[forms digest, contract form digest]` | a defn compiles one contract; the projection is `(assoc contracts sym compiled)` |
| V2, V13, V14, V15, V16 | `projection-cache-value` entries | — | `[forms digest, table key]` | the per-projection holder (#16) retires; all 37 sites keep their call shape and change only the key; the selector (V13) misses once per schema-form change |
| V3 | deleted | — | — | the forms digest replaces the datom copy |
| V4 | none: no whole-program base | — | per-row edges read from the report's rows and indexes (§4c) | |
| V5, V6, V7 | `:seon.program/closure` inside V23 | — | the producer's closure digest | render program evidence becomes **one digest equality**; `builder-digest` is `load-projection`'s closure digest |
| V8 | `:seon.sci.eval/base-context` | `[conservative or revision alias]` → override set | `[loaded commit, sorted digests of rows that differ from the loaded program, forms digest, recorder id]` | shrink-eval F3 option 1, made concrete by V23's digest map; an agent/MCP/test branch with no overrides shares one base per loaded commit |
| V9 | `:seon.sci.eval/loaded-program` | — | loaded commit id (immutable) | F1+F2 |
| V10 | the test's closure digest (V23, with test and contract edges) | — | closure digest | the member's reach digest *is* its closure digest; selection = reverse reach of changed rows on V23 |
| V11, V12 | a diff of two V23 digest maps | commit ids | two program digests | `changed-definition-symbols` becomes `(keys (diff d1 d2))`: O(program) map compare, ~1 ms, no history queries |
| V17 | `:seon.config/effective` | revisions of `:seon.config/*` attributes + declared projection | the config row value | plus rule 4: once per request |
| V18 | `:seon.render/face` | — | `[producer closure digest, profile id, input key]`; the input key is the value itself (plain data), `[lookup, pulled entity]` for an entity (the pull is the content), or the blob digest | a renderer whose closure reaches a database read (a derived bit on V23, like Clerk's no-cache inheritance) adds `[read attributes → revisions]` evidence, checked in O(attributes); **no result digests** |
| V19 | deleted from render (shrink-web P1) | — | — | revisions only; `stable-value`/`read-result-digest` stay only for the explicit read-evidence API, if any caller remains |
| V20 | deleted | — | — | pages read V18 |
| V21 | `:seon.render/history-entry` | — | `[evaluation id, profile]` for a terminal evaluation (immutable) | the basis comes from the receipt's `read-basis-transaction`, never a datom scan |

**Never keyed:** live objects and identity-bearing results (`::result-objects`, atoms, Java objects). They render
once, and their `:seon.eval/shown` is the observation (cache-central §1d). `:seon.eval/shown` stays a fact: it is
history, not cache.

## 4. Incremental structures

### 4a. The program graph (V23): the one whole-program derivation, done once per program content

```
graph = {:digests {sym digest}           ; K2, one query (8 ms)
         :edges   {sym #{callee}}        ; :seon.fn/calls (+ test→fn, fn→contract schema keys)
         :reverse {sym #{caller}}
         :closure {sym closure-digest}}  ; K3, Tarjan SCC then Merkle in reverse-topological order
```

**Measured (§9), function rows only:**

| step | cost |
|---|---|
| digest query | 8.2 ms |
| edge query | 98 ms (55,271 datoms) |
| Tarjan | 6.7 ms |
| Merkle over 5,348 SCCs | 37 ms |

The result has 5,348 distinct closures over 5,385 rows.

**Estimated full graph** with 2,301 tests and 3,548 schema rows: ~200 ms cold, once per JVM.

**Incremental derivation (the common case).** A new program content is derived from any retained graph:

1. Diff the digest maps. The query costs 8 ms; the diff is O(rows), ~1 ms.
2. Pull `:seon.fn/calls` for the changed rows only.
3. Recompute the closure digests of the changed rows' reverse reach, in SCC order.

Measured reverse-reach sizes over all 5,385 functions: **median 26, p90 378, max 2,151**. Named rows:

| row | reverse reach |
|---|---|
| `seon.db/q` | 1,278 |
| `seon.schema/load-projection` | 1,610 |
| `seon.render.value/prepare` | 377 |
| `render-ai` | 119 |
| `evaluate-sources` | 88 |

At about 7 µs of hashing per row, a median edit costs **8 ms + ~0.2 ms** and the worst case 8 ms + ~15 ms.

**Skipping the query.** An alias hit on unchanged program-attribute revisions (a turn that wrote only receipts)
costs **~1 µs**, so most agent forms pay nothing.

**Memory.** Per graph value: digests and closures ~11.2k entries × ~150 B ≈ 1.7 MB; forward plus reverse edges ≈ 4.4
MB. That is ≈ **7 MB estimated weight** (successive graphs share structure, so the true retained size is lower). The
budget keeps 2–4 graphs. For comparison, the arity caches this deletes held 92–122 MB.

**What reads it:**

| consumer | reads |
|---|---|
| render program evidence | one closure digest |
| projection builder identity | `load-projection`'s closure |
| named-predicate identity in the forms digest | predicate closures |
| base-context override set | the digest-map diff against the loaded graph |
| test reach digest | the test's closure |
| test selection | reverse reach of the changed rows |
| `changed-definition-symbols` | the digest-map diff |
| perf-boot's analyzer identity (option 2) | analyzer closure digests instead of `analyzer-reach` |

The last is optional and belongs to the perf-boot owner.

### 4b. The projection: base per forms digest, contracts per function

A contracted `defn` changes `:seon.fn/spec` only. Today that is a new projection value with an empty holder, so
every table (V2, V13–V16) re-derives: 166k armed calls, 1.66–2.13 GB per form (defn-scan).

After the change:

- `carried-projection` = `(assoc-in base [:contracts sym] (lookup :seon.schema/contract [forms-digest
  contract-digest]))`.
- The base is `(lookup :seon.schema/forms forms-digest)`.
- Its tables are `(lookup kind [forms-digest k])`.

A defn therefore costs one contract compile plus one map `assoc`. The projection's identity for downstream keys is
`[forms-digest contracts-digest]`, where `contracts-digest` is a set digest maintained by the same `assoc`.

This also answers cache-central's stopped item #16–#18, which asked for a projection content digest. The fingerprint
is an xor, is absent on declaration projections and does not cover predicate bindings. The forms digest covers
all three.

### 4c. Arity checking without a whole-program base

The writer needs to know, for the rows a report touches:

- whether each changed row's outgoing `call-arities` fit its callees' declared bounds;
- whether its callers' recorded arities fit the changed row's new bounds.

Both answers are index lookups: `avet` on the callee symbol, over the report's rows only. Cost is proportional to the
changed rows × their callers (median 26), not to 100k datoms.

This deletes `arity-base`, `arity-content-key`, both arity LRUs and the whole-program `or` query. Shrink-db P1 (the
`or`→split, 1.2 s → 87 ms) stays as the immediate 4-line fix until this slice lands.

### 4d. Render faces as per-value entries

A page, a context and an MCP reply are all lists of units. Each unit's face is one V18 entry. Assembly is list
building plus string join. After a commit, only units whose pulled input changed miss.

Measured on today's pages, a turn adds ~2–5 receipt/turn units, at a per-face cold cost of 7–85 ms. The other ~165
of 170 faces hit.

## 5. The debug page as a view

The page is a **view over V18 entries and V23**. It adds no page-level cache, no retained-calls argument, no
`::graph-calls`, and no invalidation.

One load does exactly this:

1. **Read the request profile and effective config once**, as a V17 alias hit (~0.25 ms today unarmed).
2. **Walk the graph.** `render.walk/graph` does one pull per level. It measured 21 ms warm (n=2). With raw pulls it
   is ~6 ms: `pull-many` of 83 ids costs 6 ms in Datahike against 195 ms through `seon.db` (debug-context landing
   item 5, a db-owner fix).
3. **Look up the faces.** For each node and face, look up V18 by `[producer closure, profile, pulled node]`. That is
   170 lookups × ~1.3 µs armed (measured: an armed `seon.cache/lookup` hit costs 1.26 µs) ≈ **0.2 ms**. A miss renders
   that unit only.
4. **Serialize.** Hiccup to string, 223 KB for n=2: estimated ~10 ms (not measured separately).
5. **Show a derivation panel (new, small).** It shows the `seon.cache/status` per-kind hits, misses and load-ms delta
   across this request. That is JVM-wide, so concurrent work shows; it is labelled that way.
   - This is how the owner *sees* "we aren't recalculating anything": a warm load shows 0 misses.
   - It costs ~12–15 lines × 25 tokens if an agent ever reads it, about 375 tokens. It is the same block that
     `runtime_status` already prints.

**Targets**, stated as budgets to measure against, not results:

| load | measured today | target |
|---|---|---|
| warm n=2 | 114 ms (§9) | ≤ 25 ms |
| after one agent turn | full re-validation of every retained call's read evidence | ≤ 25 ms + (changed units × 7–85 ms) |
| cold JVM | 2.25 s / 6.8 GB (landing) | ~170 faces × their render cost once, after P1 removes result digests. Estimated 0.6–1.0 s, one time per content, shared with the agent's own context build |

**Heap.** The page's entries are about 170 faces. The AI text is ≈17.7k tokens ≈ 71k chars ≈ 142 KB. The HTML is of
the same order. That is ≈ **0.6 MB estimated**, against 69.8 MB idle in `shared-cache` today.

**The Datastar stream** keeps its last-sent package per open tab. That is transport state of the page's SSE proc,
held in that proc's state, not derived state. Shrink-web option 3 (keyframe morph) can delete even that; it is the
page owner's choice.

**The other debug surfaces** (outline, turn card/ledger, session ledger, subject experiments) become the same view
over the same entries. Whether they survive is the owner's page-family ruling (shrink-web S10). This design makes
their cost follow the entries either way.

## 6. What gets deleted

The line estimates come from the cited plans and from spans read at HEAD. Each slice's diff proves its own count.

| deleted | file | est. lines | replaced by |
|---|---|---:|---|
| `projection-cache`, `value-projection-cache`, `projection-cache-policy`, `projection-cache-key`, `declaration-content-key`, `revision-agreement`, `nearest-base`, `content-projection`/`value-projection` tiers, `as-of-key` (db.clj:1286-1545 span) | db.clj | −200 | V1 alias + forms kind |
| `builder-digest`, `loaded-builder` (db.clj:1481-1543) | db.clj | −60 | `load-projection` closure digest |
| `arity-base-cache`, `arity-commit-cache`, `arity-content-key`, `arity-base`, the whole-program `call-edges-query` | db.clj | −90 | report-row index checks (§4c) |
| per-write whole-schema plans and repeated `identity-attributes` (shrink-db P4) | db.clj | −25 | fold + `[forms-digest ::identity-attributes]` |
| projection holder `projection-cache-value`'s per-projection `basic-cache`, `with-compiled-cache` | schema.clj | −30 | `seon.cache` kind keyed `[forms-digest k]`; 37 call sites keep their call shape |
| `deleted-predicate-functions` per load (shrink-schema S2) | schema.clj | −25 | resolution at `compilable-form` |
| `revision-basis`, `program-basis`, `commit-id-of`, `remember-program-identity!`, `program-identity`, `watch-program-identity!` + two `d/listen`s, `acquisition-attributes`, `program-identity-cache`, lineage branch of `acquired-database?` | sci/eval.clj | −150 (+40) | V8 content key over V23 |
| `base-context-cache`, `refusal-recording-cache`, `program-cache-policy` (cache-central patch `sci-eval-s1.patch`) | sci/eval.clj | −13 net | kinds |
| `reach-holders`, `reach-lineage`, `reach-cache`, `reach-content`, `lineage-keys`, CAS loop in `reach-entries`, `reach-facts` BFS | test/runner.clj | −110 | test closure digest + V23 reverse reach |
| second `changed-definition-symbols` in `admit-run` (shrink-test 1.1) | test.clj | −3 | — |
| `shared-cache`/`branch-scope`, `invalidate-runtime-derived-state`, `render-program-evidence`, `program-evidence-current?`, duplicate `call-cache-evidence`, `retained-calls`/`captured-calls`/`invocations` threading, `refresh-retained-read-evidence`, `::graph-calls`/`::page-results`/`::page-databases`/`::programs`, `program-key`, `candidate-call-ids`, `refresh-root`, `newer-page?` | render.clj, web.clj, walk.clj, transcript.clj, prompt.clj | −600 to −900 (shrink-web §1a option 2) | V18 |
| `retain-read-results?` at 7 render sites (shrink-web P1) | render, web, walk | 0 (flags) | revisions only |
| `entry-basis` datom scan + extra namespace `q` (shrink-web P5) | transcript.clj | −12 | receipt basis, V21 |
| **Total deleted** | | **≈ −1,300 to −1,600** | |
| **Added:** the V23 owner (`seon.program/program-graph`, closure digests, diff), forms digest, contract kind, V18 key, derivation panel | program.cljc, schema.clj, render.clj, web.clj | **≈ +220** | |
| **Net** | | **≈ −1,100 to −1,400** | |

**Atoms, stamps and holders that disappear:**

- the render `shared-cache` atom;
- the reach-holder atom-of-delay;
- the program-identity listener atom;
- the per-projection holders;
- the six core.cache `defonce`s in db.clj, sci/eval.clj and runner.clj.

## 7. Ordered slices, mapped to owners and to the shrink plans

Each slice is one commit, keeps HEAD loading and converts every caller. Its checks: kondo `--cache false`, the
contracts-compile check, root's named tests, and a clock row taken before and after on the same probe.

| # | slice | owning files | supersedes / orders | net | clock row |
|---|---|---|---|---:|---|
| 0 | **Premise probes, no source.** (a) The forms digest equals across two projections that differ only in a contract, and it changes when a named predicate's closure changes. (b) V8 soundness: enumerate every `db/q` and `db/pull` in `acquire-program!` and its callees (shrink-eval's verification limit) against `[loaded commit, override digests, forms digest, recorder]`. (c) The closure graph with test and contract edges: its cost. (d) A warm n=2 page broken down into walk, lookups and serialize. | tmp/ only | — | 0 | the four probes |
| 1 | shrink-db **P1** (`or` → split). Keep it first: it is a 4-line, 1.2 s → 87 ms fix, independent of this design. | db.clj | shrink-db 1 | −10 | arity derivation ms |
| 2 | **V23 owner**: `program-graph` (alias → program digest → graph, incremental diff), closure digests. `stored-call-reach` reads it. `builder-digest`/`loaded-builder` go. | program.cljc (+db.clj deletions) | new; enables 3–7 | ≈ −10 | graph cold ms; per-edit ms; one-defn alias hit |
| 3 | **Projection split** (forms kind + contract kind; holder → `[forms-digest k]`). Deletes the db.clj projection tiers. Error selector V13 and V14–V16 ride the same key. | schema.clj, db.clj, error.clj (keys only) | cache-central S2+S3; shrink-db P3, D8; shrink-schema S1, S3; defn-scan's remaining S0 | ≈ −230 | 5 contracted defns on a fresh branch: armed calls per form (today 166k), p50 ms (today 1.6 s), GB per form |
| 4 | **Acquisition**: V9 by commit, V8 content key; delete the revision-basis machinery. Apply cache-central's `sci-eval-s1.patch` inside it. | sci/eval.clj | shrink-eval slices 1, 2, 5 (option 1 chosen; slice 0b is its soundness proof) | ≈ −125 | `derive-base-ctx`/`base-ctx` ratio (today 71/83); first form on a fresh MCP branch |
| 5 | **Writer**: arity from report rows (§4c); per-write folds (P4). | db.clj | shrink-db P2 (superseded), P4 | ≈ −110 | trivial `with` (today 4.24 ms); defn `transact!` |
| 6 | **Tests**: reach digest = closure digest; selection by reverse reach; delete reach holders and the BFS; delete the redundant admission check. | test/runner.clj, test.clj | shrink-test S1, S3, S4 (the equal-content ruling is answered: the closure digest *is* content, so equal branches share) | ≈ −115 | 20-test `reach-digests` cold (today 1,284 ms, 98 q); `admit-run` |
| 7 | **Render faces**: V18 kind; P1 flags off; delete `shared-cache`, retained-call threading, invalidation, program evidence walk. | render.clj, web.clj, walk.clj, transcript.clj, prompt.clj (cross-owner patches) | shrink-web S1, S2, S3, S9 (option 2), P5; cache-central S4 | −600 to −900 | debug page n=2 cold/warm ms and bytes; idle cache after 10 loads |
| 8 | **The page as a view**: one profile/config per request; derivation panel. | web.clj | shrink-web S7 (the P6 profile preamble) | ≈ ±0 | warm n=2 ≤ 25 ms; a warm load shows 0 misses |

**Order constraints.**

- 2 precedes 3, 4, 6 and 7: they read closure digests.
- 3 precedes 4: V8's key contains the forms digest.
- 7 precedes 8.
- 1 and 5 are independent of 3 and 4.
- Held files: db.clj and sci/eval.clj are under active lanes today (`tmp/orchestrator/file-ownership.md`). Root
  sequences them.

**Plan items this design does not touch:** eval-path S1–S8 (what a form declares), turn P1–P2 (the lazy `db/with`,
bound folds), the shrink plans' dead-code slices, and perf-boot's analyzer speed. They are orthogonal and can run in
parallel on disjoint files.

## 8. Three priced options

**A. Keys only: every repeated value into `seon.cache` under the right key, no new structure (simplest viable).**

- Scope: V1 keyed by a forms digest recomputed as a set digest per new schema population (3.5 ms measured), V8 (F3
  option 1, with digests diffed by query per new commit), V9, V13–V17, render program evidence keyed by
  `[producer, program digest]`, the reach index keyed by program digest, and the per-call folds.
- Guarantee:
  - no connection-keyed or object-keyed derivation;
  - a defn no longer rebuilds form tables;
  - branches with equal programs share bases;
  - the page hits on unchanged programs.
- Cost: ~2 days plus Astra review; net ≈ −450 lines (the db tiers, eval identity machinery, runner holders; the
  render cache is only bounded, not replaced).
- Sacrifice:
  - Any program change invalidates *every* render entry and reach entry, because the key is the whole program
    digest, not the producer's closure. After each agent defn, the page and the test index re-derive everything.
  - Arity keeps a whole-program base (P1+P2).
  - Per-commit cost stays O(program): an 8 ms digest query per new program.
  - Premise: the forms-digest probe (slice 0a).

**B. A plus the program graph (closure digests) and per-element assembly: projection split, arity from report rows,
render faces per value, page as view (RECOMMENDED).**

- Guarantee:
  - a derived value re-derives only when something it reaches changed: median 26 rows per edit, measured;
  - render, test reach, builder identity and base contexts all read one graph;
  - the debug page, the agent context and MCP share face entries;
  - a warm page is lookups, targeting ≤ 25 ms; after a turn only the changed units render.
- Cost:
  - ~5 days in 8 slices plus reviews, with cross-owner patches for render/web/walk/prompt;
  - +≈220 lines against ≈ −1,300 to −1,600, net ≈ −1,100 to −1,400;
  - ~7 MB estimated per retained graph;
  - graph cold ~150–200 ms once per JVM.
- Sacrifice:
  - one new derived structure to own (V23) and its SCC code (~40 lines);
  - closure digests depend on `:seon.fn/calls` being complete, so an unrecorded call (a `requiring-resolve`, a
    dynamic call) makes a closure too small. The render and test reach have this exposure *today*, since they walk
    the same edges.
  - Premises: slices 0a–0c.

**C. B plus persisted derived values: closure digests written by the writer beside each row, and faces over the blob
threshold stored as blobs, so a restart, a new branch or a replaced JVM starts warm.**

- Guarantee: no cold graph or cold faces after a restart; the first page after `start --head` costs lookups.
- Cost: B plus ~2 days:
  - a writer-side closure recomputation per program write (O(reverse reach), ~0.2–15 ms per defn);
  - two schema accretions;
  - a drift check (CLAUDE.md "Mirrors are derived, drift-checked");
  - the incremental schema proof on a live store branch.
- Sacrifice:
  - a stored derived fact to keep correct across every writer path;
  - a publication change for every program write;
  - the writer, which is the serialized hot path, pays for it;
  - the restart case it wins is ~200 ms of graph plus ~1 s of faces.

**Recommendation: B.** It is the only option where cost follows changed inputs, which is what the owner asked for.
A leaves "every defn re-derives every page and every reach index", and C buys ~1 s per restart with writer cost and
a stored mirror.

## 9. Evidence (probes on default pid 89341, JVM mode, read-only; session `derive-once-design-opus`)

| probe | result |
|---|---|
| rows | 5,385 function, 2,301 test, 3,548 schema rows with definition digests; digest queries 8.2 / 3.9 / 4.0 ms |
| call edges | 55,271 `:seon.fn/calls` datoms on function rows, 98 ms query |
| `stored-call-reach` `seon.render.transcript/render-ai` | 795 symbols; 8.2 ms, then 7.5 ms (unmemoized) |
| commit id read | 0.09 µs (`committed-value-identity`) |
| attribute revisions on default's value | 142 attributes |
| closure digests (function rows) | Tarjan 6.7 ms → 5,348 SCCs, 24 cycles, largest 5; Merkle hashing 37 ms; 5,348 distinct closures |
| reverse reach over all functions | median 26, p90 378, max 2,151; `seon.db/q` 1,278; `load-projection` 1,610; `render.value/prepare` 377; `render-ai` 119; `evaluate-sources` 88; `error/prepare` 277 |
| set digest over the schema digests (pr-str + SHA-256) | 3.5 ms; over the function digests 5.4 ms |
| `seon.cache/lookup` armed hit, private cache | 1.26 µs |
| debug page `/agent/root/debug?n=1` (curl) | 359 ms cold, then 32 / 36 ms; 79 KB; ≈7.1k tokens of faces |
| debug page `?n=2` (curl) | 271 ms first, then 139 ms; 223 KB; ≈17.7k tokens of faces |
| warm `?n=2`, profiled (`seon.profile/begin`/`explain`) | 114 ms elapsed. `graph-debug-html` 100 ms; `render-call` ×94 75 ms; `with-declarations` ×208 52 ms; `pull-call` ×96 24 ms; `walk/graph` 21 ms; `request-profile` ×188 15 ms; `call-cache-evidence` ×94 14 ms; `config/effective` ×94 14 ms; `identity-attributes` ×95 13 ms |
| `seon.cache` status at 1,599 s | 5 kinds, 36,980,476 B weighted of 512,000,000. `config/declared-projection` 1,234 hits / 1 miss; `schema/projection` 7 / 2 |
| profile rows in §2 | `seon.profile/cells` → `observation`, filtered to the listed symbols |

**Limits.**

- The page probes added entries to default's render `shared-cache` (reads only; the existing page path).
- No branch was created, no write was made, and no process-cache kind was added: the cache probe used
  `seon.cache/create`.
- The closure probe's unarmed scratch code is not committed. Slice 0 re-runs it as `tmp/derive-once/graph_probe.clj`
  together with the test and contract edges.
- The profile totals include concurrent lanes' work since 21:35Z.
