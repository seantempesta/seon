---
type: research
status: active
tags: [research, datahike, render, caching]
---

# Render invalidation and query caching — grounded names and contract

Answers four owner questions with source and probes: what Datahike's internal
query cache is really called and what it keys on; what the old E/A/V interest
registration actually was; what the fresh tree has today; and whether caching
or invalidation can be shared across clusters in one JVM.

Predecessors: `query-invalidation-2026-07-29.md` (falsified read-tracing),
`seondb-facade-quarry-2026-07-29.md` (the full facade quarry and the
20-wakes/0-false datoms-range measurement),
`../plan/seondb-facade-contract-spec.md` (the surface awaiting owner review).
This report re-verifies their load-bearing claims against current source rather
than citing them.

## Dependency ledger

- Datahike: local root `reference-code/datahike` (`deps.edn:21-25`), submodule
  at `9b3be9d5` (`git describe`: `0.8.1729-98-g9b3be9d5`). This is Seon's own
  fork (`origin git@github.com:seantempesta/datahike.git`); the query result
  cache is fork-and-PR work, not stock upstream — `git log -S"query-result-cache"`
  names `ebbd623a`, `0b652215`, `f8192962`, `0070d507`, `0cf39e57`.
- Read map: `datahike/query.cljc` (three caches, dependency plans, inheritance),
  `datahike/pull_api.cljc` (pull dependency plans), `datahike/db.cljc`
  (committed cache identity), `datahike/writer.cljc` (commit-time revision
  advance), `datahike/connections.cljc` (process-global connection registry),
  `datahike/store.cljc` (connection id, store cache), `datahike/versioning.cljc`
  (branch/materialized-commit cache context),
  `datahike/index/persistent_set.cljc` (`CachedStorage`).
- Seon: `src/seon/cluster/wake.cljc` (the only live wake), `src/seon/cluster/store.clj`
  (branch connections), quarry sources `src-old/seon/db/writer.clj`,
  `src-old/seon/db.cljc`, `src-old/seon/reactive.cljc`.
- Probe (committed, reproducible):
  `tmp/render-invalidation/dependency_plan_probe.clj`, run with
  `clojure -M:dev tmp/render-invalidation/dependency_plan_probe.clj`.
  Recorded 2026-07-31, Java 26.0.1.

## 1. The caches, in Datahike's own vocabulary

There are **three query-side caches plus two storage caches**, and only one of
them is about invalidation.

### 1.1 Parsed-query cache — `query-cache`

`query.cljc:2413` — `(def ^:private query-cache (volatile! (datahike.lru/lru lru-cache-size)))`,
size 100 (`:61`). Keyed by the raw query form; the entry holds the parsed query,
its source bindings, and its input count (`:3051-3059`). Basis-independent.

### 1.2 Query-plan cache — `plan-cache`

`query.cljc:2415-2418`. The comment states the key exactly: "keyed by
`[where-clauses bound-vars rules-keys schema-hash]` … stable across
transactions as long as the schema hasn't changed." Index selection and merge
ordering only — no results.

### 1.3 Query **result** cache — `query-result-cache`

`query.cljc:2489-2491`:

```clojure
(defonce ^{:doc "Global weighted query-result cache plus atomic connection-generation admission."}
  query-result-cache
  (atom (empty-query-result-cache *query-cache-size* *query-cache-weight-limit*)))
```

- **Structure** (`:2439-2440`): `{:generations {connection-id generation}
  :lru {db-key -> {cache-key -> entry}}}`, a weighted LRU (`:2479-2482`).
- **What it keys on** — two levels:
  - outer **db-key = the exact committed cache identity**
    `[connection-id generation commit-id]` (`db.cljc:400-406`, via
    `committed-value-identity` `:385-398`). `AsOfDB` appends the time point
    (`query.cljc:2658-2671`). Speculative and detached values return nil and
    **do not cache** (`db.cljc:412-415` `clear-cache-context`).
  - inner **cache-key = `[query non-db-args]`** (`:2429`).
- **Scope**: one process-global `defonce` atom, but **partitioned by
  connection-id and generation**. `connection-id` is
  `[store-id branch]` for a self-writer (`store.cljc:50-61`), so every branch
  — every Seon cluster — occupies its own buckets.
- **Bounds**: `*query-cache-size*` 64 snapshots (`DATAHIKE_QUERY_CACHE_SIZE`,
  `:2445-2453`) and `*query-cache-weight-limit*` 1,000,000 shallow structural
  weight (`DATAHIKE_QUERY_CACHE_WEIGHT_LIMIT`, `:2455-2464`; 0 disables).
- **Admission and release**: `open-query-cache-generation!` (`:2524-2528`,
  called at `connector.cljc:369`) and `close-query-cache-generation!`
  (`:2604-2634`) which evicts every snapshot naming that connection generation.
  A put is refused unless the epoch and every source generation still match
  (`:3027-3049`), so release cannot race a late put.
- **Evidence, not inference**: `q-with-evidence` (`:130-165`) returns
  `:datahike.query/cache-evidence` with `:datahike.cache/outcome` ∈
  `hit | hit-after-acquire | miss-owner | miss-joined | uncacheable`, plus
  `query-cache-metrics` / `query-cache-evidence` (`:2636-2656`).

### 1.4 The cross-basis mechanism — attribute revisions

This is the part that matters for rendering, and it is **not** a cache: it is
per-attribute version data carried on the immutable database value.

- On every commit the writer advances the db's `:cache-context`
  (`writer.cljc:227-235` → `q/advance-query-cache-context`).
- `advance-query-cache-context` (`query.cljc:2568-2589`) assocs
  `:datahike.cache/attribute-revisions {attr commit-id}` for each modified user
  attribute (`:db/txInstant` excluded), and instead bumps
  `:datahike.cache/conservative-revision` when the commit is a merge, has
  unknown attributes, or touches any schema attribute. Docstring: "Cached
  result rows are not inspected or copied."
- A demanded miss may **inherit** an older result: `inheritable-entry`
  (`:2977-3000`) + `source-context-unchanged?` (`:2963-2975`) require the same
  conservative revision, a non-`:all` dependency set, and an unchanged revision
  for **every depended-on attribute**. `result-cache-get` (`:3002-3025`)
  promotes the inherited row lazily into the demanded snapshot bucket.

**Probe (independent reproduction of the prior doc's ~22 µs claim's
mechanism):** query `[:find (count ?e) . :where [?e :probe/id _]]`, then two
commits touching only `:probe/noise` on an existing entity →
`:datahike.cache.outcome/hit`, result 1, two snapshot buckets. Changing the
depended-on attribute instead produced `miss-owner` (an earlier run of the same
probe). So attribute revisions gate inheritance exactly as documented.

### 1.5 Storage caches (not invalidation)

- Konserve key LRU: `store.cljc:25-36` `add-cache-and-handlers` wraps the raw
  store with `kc/ensure-cache` + `cache/lru-cache-factory :threshold
  (:store-cache-size config)`.
- Decoded index-node cache: `CachedStorage` (`index/persistent_set.cljc:409-466`,
  constructed at `:461-470`) with its own LRU keyed by node address.

Both accelerate traversal; neither knows about queries.

## 2. Dependency plans — the parsed-Datalog registration mechanism

The thing the owner is calling "parsed-Datalog registration" already exists,
inside Datahike, with names:

- **`:datahike.read/dependency-plan`** — the returned value
  (`pull_api.cljc:437-454`, `query.cljc:154`).
- Its shape is `{:datahike.query.dependency/sources [{:datahike.query.source/symbol
  :datahike.query.source/argument-position :datahike.query.source/attributes}]}`
  (`pull_api.cljc:66-69`), or the keyword `:all`.
- **`dependency-plan-attributes`** (`query.cljc:2906-2933`) — union across
  sources, or per source argument position.
- **`query-attribute-dependencies`** (`query.cljc:2935-2944`) — **pure**: takes a
  query form with no db and no inputs, returns a set of attributes or `:all`.
  "This function is pure: it neither executes the query nor retains data."
- **`pull-dependency-plan`** (`pull_api.cljc:53-69`) and
  `pull-spec-attribute-dependencies` (`:18-36`), which recurses into
  subpatterns and returns `:all` on a wildcard or non-keyword attribute.
  Lookup-ref entity refs contribute their identity attribute
  (`:38-51`).
- Database functions are handled explicitly: `missing?`, `get-else`, `get-some`
  contribute their attribute argument when it is a bound constant, else `:all`
  (`query.cljc:2706-2741`).

**Probe results (pure, no database):**

| form | dependencies |
|---|---|
| `[:find ?n :where [?e :probe/id ?i] [?e :probe/name ?n]]` | `#{:probe/id :probe/name}` |
| `[:find ?v :in $ ?a :where [?e ?a ?v]]` | `:all` |
| `[:find (pull ?e [:probe/name]) :where [?e :probe/id _]]` | `#{:probe/id :probe/name}` |
| `[:find (pull ?e [*]) :where [?e :probe/id _]]` | `:all` |
| `[:find ?e :where [?e :probe/id] [(missing? $ ?e :probe/name)]]` | `#{:probe/id :probe/name}` |
| `[:find ?e :in $ % :where (ancestor ?e ?x)]` | `:all` |
| pull `[:probe/name {:probe/from [:probe/id]}]` at `[[:probe/id "m"]]` | `#{:probe/from :probe/id :probe/name}` |
| pull `[*]` | `:all` |

Granularity is **attribute only**. No entity, no value, no range. Nothing in
the plan narrows by `?e`.

## 3. The old E/A/V interest registration (`src-old`)

Three distinct things existed; the quarry's distinction holds under re-reading.

1. **Capture** — `src-old/seon/db.cljc:320-340` `record-query-evidence!` stored,
   per database source argument, `{:seon.db/db … :seon.db/source-argument-position …
   :datahike.read/dependency-plan …}`. It read the plan Datahike returned; it
   never inspected returned rows.
2. **Reduction to attributes** — `src-old/seon/db/writer.clj:2856-2874`
   `evidence-dependencies` verified the evidence named the same live branch and
   reduced each plan through `d/dependency-plan-attributes plan position`, with
   `:all` absorbing sets. `listen-interest` (`:2877-2899`) accepted three inputs
   in priority order: recorded read evidence, a raw dependency plan, or a raw
   query form reduced by `d/query-attribute-dependencies`. An empty dependency
   set was **refused** as a protocol error.
3. **The interest index** — `src-old/seon/db/writer.clj:2765-2810`:
   `{::by-scope {scope {::all #{ref} ::by-attribute {attr #{ref}}}}}`, i.e. a
   reverse index from **changed attribute → candidate interests**, plus an
   `::all` fail-open bucket. `interest-attributes` (`:2772-2777`) preferred the
   dependency set and fell back to the attributes of explicit datom patterns.
4. **Exact datom matching** — `candidate-interests` (`:3174-3190`) narrowed by
   attribute, then `matching-datoms` (`:2991-3001`) applied
   `datom-matches-pattern?` (`:2981-2989`), which matches attribute, and
   optionally entity, value (byte-array aware), and addedness. Explicit E/A/V
   patterns were therefore real and exact — but they were **supplied by the
   caller**, never derived from a query.
5. **The wake path** — `deliver-report!` (`:3192-3205`) ran per transaction
   report over `public-transaction-datoms`, two-stage: attribute bucket, then
   exact match, then one interest event per surviving interest.

So: automatic render invalidation ran at **attribute granularity from
dependency plans**; exact E/A/V patterns were a separate, hand-supplied
mechanism. `src-old/seon/reactive.cljc` owned registration, newest database
value, recomputation, and equality suppression above that.

## 4. What fresh `src/` has today

One wake, no interest registration at all:

- `src/seon/cluster/wake.cljc:203-228` `route!` — a single `d/listen` handler
  per cluster. Per report it does exactly three things: one **unconditional**
  render wake (`:212`), then per datom a `case` on the attribute:
  `:seon.cluster.agent/id` → armer, `:seon.cluster.message/to` → that agent's
  mailbox, else nothing.
- `wake-attributes` (`:78-93`) = `#{:seon.cluster.message/to :seon.cluster.agent/id}`
  — the routing set, kept computed so its disjointness from
  `seon.cluster.loop/committed-attributes` can be checked rather than believed.
- Delivery is `offer!` into `(sliding-buffer 1)`; the handler must never throw
  (it hangs the writer, measured) and never park (measured 804 ms transact)
  — `:12-33`.
- `:180-186` states the render wake is deliberately unconditional because
  matching "would be a hand-list of read attributes (the class F2 R6 refuses
  until the program graph can compute it)".
- The API name is `d/listen` / `d/unlisten` (`api/specification.cljc:1069-1095`,
  impl `datahike.core/listen!`).

There is **no** `seon.db` facade, no interest index, no read-evidence capture in
fresh `src/`. Render owners call `datahike.api` directly (verified list in
`query-invalidation-2026-07-29.md` §"Fresh render read seams").

## 5. The sound invalidation contract for a walk-based render

### 5.1 The absence-miss argument, restated precisely

A render's output is a function of the database value, not of the datoms it
happened to return. Therefore its dependency set must be **the set of facts
whose change could change the output**, which strictly contains the facts it
read. Result-level tracing observes only positive returned `(e,a)` pairs, so it
under-approximates that set in four ways the prior probe demonstrated
(`query-invalidation-2026-07-29.md` §"Read-tracing feasibility"):

1. **Empty→non-empty.** A query matching nothing records nothing, so the
   commit that first makes it match looks irrelevant and the render never
   re-runs.
2. **Absent pulled attribute.** `d/pull` of an attribute the entity lacks
   returns no key, so adding that attribute later is invisible.
3. **Join-internal facts.** A `d/q` result row carries no provenance for the
   entities/attributes joined through to produce it; changing the joined-through
   value changes the output while the traced set is untouched.
4. **Negative clauses.** `not`, `missing?`, and anything whose truth depends on
   absence has, by construction, no datom to trace.

The general statement: **absence is not observable in a result**, and rendering
depends on absence. Any mechanism derived from results is unsound; only a
mechanism derived from the **read form** is sound.

### 5.2 Does registering parsed-query dependencies cover absence? Yes.

`query-attribute-dependencies` parses the query, never executes it. The probe's
decisive case: a query over `:probe/name` with **zero matching entities**
returned `[]` and still reported dependency `#{:probe/name}`. Committing the
first `:probe/name` datom changes that attribute's revision and wakes the
render. Cases 1, 2, and 4 above are covered because the attribute appears in
the form (`missing?` contributes its attribute explicitly,
`query.cljc:2722-2727`). Case 3 is covered because every joined-through
attribute is a clause attribute.

The contract is therefore: **register the read form's dependency plan, reduced
to attributes; fail open to `:all`; never narrow from results.** This is sound
in the precise sense that the dependency set is a superset of the true
dependency set — it over-wakes (false positives), never under-wakes.

Two conditions preserve soundness and are the ones that historically bit:

- **Fail open on failure.** If a render throws, its collected evidence must be
  replaced by `:all`, or a broken page can filter out the very transaction that
  repairs it (`seondb-facade-quarry-2026-07-29.md` §"Fresh capture contract",
  issue `failed-page-render-retains-stale-dependencies.md`).
- **Empty is legal, `:all` is the default.** Old code refused an empty
  dependency set as a protocol error (`writer.clj:2892-2896`); fresh policy
  makes empty mean "static render" (quarry, same section). Both are safe; what
  is unsafe is treating "no evidence collected" as "no dependencies" — that
  must widen to `:all`.

### 5.3 Registering E/A/V rather than just A

Attribute-only registration is sound but imprecise. Narrower registration is
only sound with an extra read of `db-before`, and the quarry measured exactly
this for an index range (`seondb-facade-quarry-2026-07-29.md` §"Datoms"):

| evidence policy | wakes | true | false | precision |
|---|---:|---:|---:|---:|
| fail-open `:all` | 60 | 20 | 40 | 33.3% |
| attribute only | 40 | 20 | 20 | 50.0% |
| attribute + inclusive range + `db-before` | 20 | 20 | 0 | 100% |

The reason `db-before` is mandatory is the same absence argument in value form:
a cardinality-one change reports only the **asserted** new value, so a value
moving *out of* a registered range is invisible unless the old value is read
from `db-before`. Any value- or entity-narrowed interest inherits that
obligation. Note the cost shape: one EAVT point read per candidate `(e,a)` in
the report, memoizable within the report — paid on the writer's critical path,
which is exactly where `wake.cljc:22-28` forbids work. Value-level narrowing
therefore belongs in the woken consumer, not in the listener.

### 5.4 Pull and entity walks

- **A static pull selector is fully covered.** `pull-dependency-plan` recurses
  into subpatterns, so following `{:probe/from [:probe/id]}` registers
  `#{:probe/from :probe/id …}` — the ref attribute *and* the child attributes.
  A newly-appearing ref changes the ref attribute, which is in the set, so the
  walk's shape changing is itself covered. This is the important structural
  answer: **register the selector's attribute closure, never the walk's touched
  entity set.**
- **The touched entity set is a result.** Registering the entities a walk
  visited repeats read-tracing's error exactly: an entity that *would* be
  visited after the next commit was not visited on this pass and so would never
  be registered. Entity ids may be used to *narrow* (with `db-before`), never to
  *define* the interest.
- **Dynamic walks widen.** A wildcard `[*]`, a depth/selector chosen from data,
  or a recursive `...` spec whose expansion is data-dependent must reduce to
  `:all`. `pull-spec-attribute-dependencies` already returns `:all` for wildcard
  and non-keyword attributes (`pull_api.cljc:22-36`), verified by probe.
- **`d/entity` is the selectivity killer.** The facade spec's eager projection
  is `pull '[*]` (`../plan/seondb-facade-contract-spec.md:22`), whose plan is
  `:all` (probe). Any walk step that reaches for `entity` converts the whole
  render to a wake-on-every-commit render. A walk that wants selectivity must
  name its attributes.

### 5.5 The mechanism I would recommend, and why it is smaller

Datahike already carries the invalidation oracle **on the database value**:
`(get-in db [:cache-context :datahike.cache/attribute-revisions])` is a map
`attr -> commit-id`, plus `:datahike.cache/conservative-revision`
(probe printed both on a live committed db). Consequences:

- A render that retains (a) its dependency attribute set and (b) the revisions
  it last saw can decide **in O(|deps|) map lookups at wake time**, against the
  db value it already holds, whether anything it depends on changed — with **no
  listener-side datom matching, no reverse interest index, and no work on the
  writer's critical path**. That is `source-context-unchanged?`
  (`query.cljc:2963-2975`) applied by us instead of by the cache.
- The unconditional render wake in `wake.cljc:212` stays exactly as it is. The
  selectivity moves into the woken pass, which is where derivation belongs and
  where it may legally read `db-before` if a narrower value test is ever wanted.
- The conservative revision is the built-in fail-closed: schema commits, merges,
  and unknown-attribute commits force every render to recompute
  (`query.cljc:2579-2583`).
- It is process-local derived memory. After restart the first pass recomputes —
  the same property the prior doc required.

This eliminates the second-mechanism risk: no Seon-authored parser (Datahike's
is executed-aware and already handles rules, database functions, and pull
recursion), and no Seon-authored per-commit matcher (the revisions are already
computed at commit). What Seon must still own is the **one read seam** that
captures the plans, which is the `seon.db` facade already specified in
`../plan/seondb-facade-contract-spec.md`.

Order of adoption, unchanged from the prior verdict: measure the unconditional
path first; add selective registration only when render cost or churn justifies
it; keep byte-equality suppression regardless, because freshness outranks cache.

## 6. Cross-cluster / cross-branch sharing in one JVM

**Directly probed.** One in-memory store, `d/branch! conn :db :fork`, both
connected, the identical query run on each:

```
main branch outcome                 :datahike.cache.outcome/miss-owner
forked branch outcome               [:datahike.cache.outcome/miss-owner 2]
main    :cache-context  connection-id [#uuid "91ae…" :db]    generation #uuid "c109…"
                        commit-id #uuid "6a6c…39ac…"
                        attribute-revisions #:probe{:id #uuid "6a6c…39ac…"}
fork    :cache-context  connection-id [#uuid "91ae…" :fork]  generation #uuid "f845…"
                        commit-id #uuid "6a6c…39ac…"   (no attribute-revisions)
same store object?                  false
cache metrics                       {:snapshot-count 2 :open-generation-count 2}
```

Read that carefully: the fork shares the **same commit id** and produces the
**same result**, and still misses. Why, from source:

- The outer cache key is `[connection-id generation commit-id]`
  (`db.cljc:400-406`) and `connection-id` embeds the branch
  (`store.cljc:50-61`). Different branch ⇒ different bucket.
- Inheritance requires `compatible-source-keys?` (`query.cljc:2951-2961`), which
  compares `(subvec member 0 2)` — connection-id **and** generation — for
  equality and allows only the commit-id to differ. Cross-branch inheritance is
  structurally unrepresentable.
- A branched/materialized value gets a fresh context with
  `:datahike.cache/conservative-revision = commit-id` and no attribute revisions
  (`versioning.cljc:69-100`), whose docstring says why: "stored commits do not
  retain the process-local attribute revision map, so cross-commit promotion
  would otherwise compare two absent revisions as unchanged."

**What IS shared across two clusters in one JVM:**

| thing | shared? | evidence |
|---|---|---|
| the `query-result-cache` atom | yes — one process-global `defonce`, but bucketed per connection generation | `query.cljc:2489-2491`, `:2439` |
| cached result **rows** across branches | **no** | probe; `:2951-2961` |
| attribute revisions across branches | **no** | probe; `versioning.cljc:88-100` |
| parsed-query cache (`query-cache`) | **yes**, fully — keyed by query form only | `query.cljc:2413`, `:3051-3059` |
| plan cache | **yes**, when the schema hash matches — clusters forked from one `current-src` normally do | `query.cljc:2415-2418` |
| konserve key LRU / decoded index nodes | **no** — each `d/connect` builds its own store handle and `CachedStorage` | `connector.cljc:319-324`, `index/persistent_set.cljc:461-470`; probe `same store object? false` |
| the durable index structure itself | **yes**, by content-addressed copy-on-write: a fork writes no index data | `versioning.cljc:237-247` |
| the connection registry, and write hooks per physical store | yes | `connections.cljc:3`, `:59-73` |

So the answer to "can caching be global to the cluster or even cross-cluster":
**parsing and planning already are process-global and free across clusters;
results and invalidation are per-branch by construction and cannot be shared
without changing the fork.** That is the correct default — two clusters are
sovereign programs at different commits — and cross-branch result sharing would
require proving that both branches' dependency attributes are at the same
revision, which is precisely the fact `versioning.cljc:88-96` says is not
retained for stored commits.

Cheap wins that follow, no fork change needed:

- Raise `DATAHIKE_QUERY_CACHE_SIZE` above 64 when many clusters are live: the
  LRU counts **snapshots across all connections**, so N clusters × M recent
  bases share one 64-slot budget and can evict each other.
- Seon opens one connection per branch and refuses a second
  (`src/seon/cluster/store.clj:357-385`), so the generation partition is exactly
  one per cluster.

## 7. Vocabulary — the grounded names

Use the dependency's words on the dependency's side of each boundary.

| use | say | never | defining source |
|---|---|---|---|
| the query cache | **query result cache** (`datahike.query/query-result-cache`) | memo, query memoizer, snapshot cache | `query.cljc:2489-2491` |
| its key | **committed cache identity** `[connection-id generation commit-id]` | db pointer, basis key | `db.cljc:400-406` |
| the other two | **parsed-query cache**, **plan cache** | "the query cache" (ambiguous) | `query.cljc:2413`, `:2415-2418` |
| the per-attribute version | **attribute revisions** / **conservative revision** | dirty bits, attribute epoch, generation | `query.cljc:2568-2589` |
| cache outcome | **hit / hit-after-acquire / miss-owner / miss-joined / uncacheable** | warm/cold | `query.cljc:130-165` |
| what a read reports | **dependency plan** (`:datahike.read/dependency-plan`) | read trace, read set, query signature | `pull_api.cljc:437-454` |
| its reduction | **dependency attributes** (`dependency-plan-attributes`, `query-attribute-dependencies`) | dep list, watch set | `query.cljc:2906-2944` |
| Seon's captured bundle | **read evidence** (`:seon.db/read-evidence`) | read log, tracking data | `src-old/seon/db.cljc:320-340` |
| Seon's registration | **interest** (attribute-indexed) | subscription, watcher, reactive query | `src-old/seon/db/writer.clj:2765-2810` |
| the signal | **wake** — it carries no information | notification, event, invalidation message | `src/seon/cluster/wake.cljc:1-10` |
| the listener API | **`d/listen` / `d/unlisten`** | `d/listen!` (that's the internal impl) | `api/specification.cljc:1069-1095` |

"Registration" is the act; the registered value is a **dependency plan**; the
delivered signal remains a **wake**. There is no need for a new noun.

## 8. Open questions (with evidence, not speculation)

1. **Is attribute granularity precise enough for Seon's actual schema?** The
   60-report experiment says attribute-only is 50% precise for a range query.
   Nobody has measured it on the real S0–S3 render pieces. Falsifier: instrument
   the current unconditional path, record per-report `(rendered-blocks,
   byte-changed-blocks)` for a live drive, and compare against the dependency
   sets the plans would produce.
2. **Does the attribute-revision oracle (§5.5) beat an interest index in
   practice?** Both are unmeasured in fresh Seon. The oracle's cost is
   O(|deps|) per registered render per report; the index's is O(|changed attrs|)
   per report plus the writer-thread constraint. Falsifier: a bench with N
   registered renders × M commits.
3. **`d/entity`'s `:all` cost.** No fresh call sites exist yet
   (`../plan/seondb-facade-contract-spec.md:60-61`). If the walk design wants
   entity-like navigation, does it get a *bounded, attribute-named* projection
   instead? Unresolved and owner-facing.
4. **Query-result-cache budget across many clusters.** 64 snapshots shared
   process-wide; unmeasured under multi-cluster load. Cheap probe:
   `query-cache-metrics` after a multi-cluster drive.
5. **Does the render pass hold one basis?** The facade spec dereferences once
   (`with-db @connection`), but no fresh test pins it. Falsifier exists in the
   spec's sealed list (#1) and is unimplemented.

## 9. Skill drift found (not edited — reported per lane rules)

1. `.claude/skills/datahike/SKILL.md`, "Temporal, listeners, triggers": says
   "`d/listen!` installs a transaction listener by key". The public API name is
   **`d/listen`** (`api/specification.cljc:1069-1083`); `datahike.core/listen!`
   is the impl behind it, and all fresh call sites use `d/listen`
   (`src/seon/cluster/wake.cljc:203`). Minor but it is exactly the kind of name
   a lane copies.
2. Same skill, same section: "never reach back to the connection from inside
   it" is right but understates it. The measured prohibitions are stronger and
   live at `src/seon/cluster/wake.cljc:12-33`: a throwing handler **hangs the
   writer forever** (the listener fires inside the transaction go block before
   `deliver`), and a parking handler adds its full latency to the committing
   caller (804 ms measured). Worth one sentence with that pointer, since the
   failure mode is a hung JVM.
3. Neither `datahike` nor `data-oriented-clojure` mentions dependency plans,
   `q-with-evidence`, or the attribute-revision facts at all — they are the
   fork's most Seon-specific feature and the entire basis of render
   invalidation. `references/fork-maintenance.md` covers plan-vs-result cache;
   the main skill could carry one pointer line.
4. **Fork docstring drift** (in `reference-code/datahike`, ours to fix):
   `query-attribute-dependencies` (`query.cljc:2938-2942`) claims it returns
   `:all` when "a variable, rule, **pull pattern**, malformed form, or unknown
   clause" prevents narrowing. Probed: a *concrete* pull pattern inside `:find`
   narrows correctly (`[:find (pull ?e [:probe/name]) …]` → `#{:probe/id
   :probe/name}`); only a **wildcard or dynamic** pull widens. The docstring
   understates the function and would push a reader toward unnecessary `:all`.
