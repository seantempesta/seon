---
title: "Why the cold context block costs 4.7–8.9 s"
type: research
status: complete (lane argon-context, 2026-10-15, SCI mode as agent `argon-context`)
created: 2026-10-15
---

# Why the cold context block costs 4.7–8.9 s

## Probe and cold vs. warm summary

Measured on cluster `"default"` in SCI mode on the held task `c12a57408f49` (`seon.error/an-unresolved-alias-name-offers-its-nearest-core-name-3`, attempt 6 agent `"attempt/a4de2ea6de02"`) on `:chain-f1f6dc8e6652` (read-only), using `seon.profile/measure` and per-function profile counters (`seon.profile/cells` / `seon.profile/summary`):

| Operation | State | Wall (`ms`) | Thread alloc (`MB`) | GC (`ms` / count) | Armed calls | DB reads |
|---|---|---:|---:|---:|---:|---:|
| `(seon.context/current-state db "attempt/a4de2ea6de02")` | Cold after restart (with three-way `moved-reach`) | `8,993.27` | `12,859.76` | `532` (18) | `1,471,154` | `4,333` |
| `(seon.context/current-state db "attempt/a4de2ea6de02")` | Cold cache (after three-way `moved-reach` removed) | `3,714.43` | `7,570.34` | `34` (5) | `646,510` | `1,261` |
| `(seon.context/current-state db "attempt/a4de2ea6de02")` | Warm repeat (with three-way `moved-reach`) | `292.37` | `255.49` | `0` (0) | `125,849` | `1,843` |
| `(seon.context/current-state db "attempt/a4de2ea6de02")` | Warm repeat (after `moved-reach` removed) | `108.93` | `169.20` | `0` (0) | `58,735` | `1,239` |
| `(seon.context/model-context db req "attempt/a4de2ea6de02")` | First render (`block-views` + `exchanges` after warm `current-state`) | `2,945.83` | `3,168.41` | `49` (4) | `520,570` | `2,704` |
| `(seon.context/model-context db req "attempt/a4de2ea6de02")` | Second render (`AsOfDB` test `must-reads` still uncached) | `703.00` | `1,152.10` | `9` (1) | `189,450` | `2,331` |

---

## 1. Ranked table of measured cold cost by function

### A. Cold `seon.context/current-state` (`8,993.27 ms` wall, `12,859.76 MB` thread allocation, `1,471,154` calls, `4,333` reads)

| Rank | Function | Calls | Inclusive `ms` | Self `ms` | Thread alloc share (`MB`, measured) | Category | Proportional to today | Must be proportional to |
|---:|---|---:|---:|---:|---:|---|---|---|
| 1 | `seon.context/current-state` / `seon.context/task-sections` | 1 | `8,993.0` | `1.0` | `12,859.8` | Top-level entry | Whole reachable program + three-way branch merge-base + all earlier attempt evaluations | Task's references (`22`), proofs (`1`), and branch's own writes (`15` identities) |
| 2 | `seon.context/proofs` + `seon.context/step-observation` → `seon.context/head-entries` / `seon.context/current-results` | 5 | `6,036.0` | `1.0` | `~9,450.0` | Reach + three-way | Called **3 times** on `database` (`proofs`, `step-observation/current-results`, `step-observation/head-entries`) + 1 time on `main` + 1 time in `branch-changes` | One reach lookup for the branch's judged test set |
| 3 | `seon.test.runner/reach-entries` → `seon.test.runner/reached-graph` → `seon.test.runner/reach-fixed-point` | 8 | `4,781.0` | `122.0` | `~7,400.0` | **Non-three-way #1** | **Whole reachable program (`3,054–3,412` documented functions, `3,943` nodes)** walked and digested on cold cache | Only declarations changed since the base commit's cached reach index |
| 4 | `seon.db/behavior-digest` | 3,412 | `4,367.0` | `130.0` | `~6,850.0` | **Non-three-way #1** | `2 ×` `declaration-value` pulls per documented function (`entity-id` + uncached `namespace-entity`) + `seon.program/behavior-digest` | `1` cached digest per definition, or `O(namespaces)` (`~80`) namespace resolver contexts instead of `O(functions)` (`3,412`) |
| 5 | `seon.program/behavior-digest` → `seon.program/definition-digest` → `seon.program/used-resolver-context` | 3,412 | `2,181.0` | `74.0` | `~3,600.0` | **Non-three-way #1** | Re-parses every docstring-stripped function source string a **second time** (`parser/parse-string-all`) because stripping `:seon.fn/doc` changes the `source` string key in `::source-resolver-names` | `0` re-parses: a docstring literal contains no symbols or keyword qualifiers, so `used-resolver-context` of `entity` is invariant under `without-docstring` |
| 6 | `seon.program/source-resolver-names` | 4,034 | `1,130.0` | `927.0` | `~1,850.0` | **Non-three-way #1** | `rewrite-clj` `parser/parse-string-all` over every function's docstring-stripped source (`3,054–3,412` misses after admission already parsed the unstripped source) | Reused from the unstripped `(:seon.fn/source entity)` (`0` misses after admission) |
| 7 | `seon.program/without-docstring` | 3,412 | `792.0` | `792.0` | `~1,420.0` | **Non-three-way #1** | `rewrite-clj` `parser/parse-string` on all `3,054–3,412` documented functions on cold cache | Only functions whose `:seon.fn/doc` changed, or once at write/admission |
| 8 | `seon.db/declaration-value` + `seon.db/declared-collections` + `seon.schema.datahike/logical-collection` | 6,108 / 104,507 | `1,180.0` | `660.0` | `~1,950.0` | **Non-three-way #1** | `6,108` full entity reconstructions (`3,054` functions + `3,054` redundant namespace entity reconstructions for the same `~80` namespaces) | `3,054` function pulls + `~80` cached namespace contexts (`-2,974` entity reconstructions, `-72,526` `declared-collections` calls) |
| 9 | **Three-way branch readers**: `seon.cluster.registry/moved-identities`, `moved-past`, `commit-digests`, `main-changed`, `merge-sides`, `seon.test.runner/moved-reach` | 21 / 4 / 9 / 5 | `930.0` | `48.0` | `~1,450.0` | **Three-way readers (separately attributed)** | Commit history walk + three-way comparison (`base`, `ours`, `theirs`) + `moved-reach` calling 1-arity `(reach-declarations database)` (`{}` as `previous`) on every call | Replaced by `own-delta` (`branch-model-rulings.md`) |
| 10 | `seon.test.runner/reach-declarations` → `seon.test.runner/reach-attribute` | 8 / 136 | `842.0` | `118.0` | `~1,100.0` | Reach index | Scanning all `17` reach declaration attributes across the store when cold (and repeated in `moved-reach` with `previous = {}`) | Cached per attribute revision (`::reach-attribute`) |
| 11 | `seon.context/earlier-attempts` → `seon.context/last-step` → `seon.eval/of-agent` | 1 / 7 / 7 | `181.0` | `14.0` | `94.9` | **Non-three-way #3** | Pulling `:seon.cluster.eval/comment`, `:seon.cluster.eval/source`, and full `:seon.eval/shown` for **every evaluation of all 7 earlier attempts**, sorting all of them, and taking `(peek evaluations)` on every turn | Only the newest evaluation `(max ?t)` of each earlier attempt (or recorded at attempt end like `recorded-end-summary`) |

### B. Additional Cold & Warm Cost in `seon.context/model-context` (`2,945.83 ms` cold / `703.00 ms` warm)

When `seon.context/model-context` renders `[head tail]` via `seon.context/block-views`, it renders `:seon.context/must-reads` on `start = (attempt-start database agent-id)` (an `AsOfDB`) and `:seon.context/proofs` on `database`:

| Rank | Function | Calls | Inclusive `ms` | Self `ms` | Thread alloc (`MB`) | Category | Proportional to today | Must be proportional to |
|---:|---|---:|---:|---:|---:|---|---|---|
| 1 | `seon.render.test/render-ai` → `seon.test/host` → `seon.test/destructive-reach` → `seon.fn/gate-sets-in` → `seon.fn/gate-edges` | 2 | `1,370.0` (cold) / `767.2` (warm on `start`) | `698.0` (in `vector-query-call`) | `1,238.2` | **Non-three-way #2** | **Uncached on every `AsOfDB` (`attempt-start`)**: runs `destroyers` + `4` whole-program Datalog queries in `seon.fn/gate-edges` (`identity-entities`, `test-symbols`, `file-references`, `handlers`) + `declared-callers` across all `~4,500` declarations on every call | Cached in `seon.cache` under the view's exact identity (`seon.db/as-of-key` / `program-revisions`), costing `0.05 ms` and `0` reads on repeat |

---

## 2. Top 3 non-three-way cold costs and smallest fixes

### Top Cost #1: `seon.db/behavior-digest` and `seon.program/behavior-digest` re-parse every documented function's AST and re-pull its namespace entity (`~3,100–4,367 ms`, `~6.8 GB` alloc)

- **Where it happens**:
  - `seon.test.runner/reached-graph` (`src/seon/test/runner.clj:994-996`) calls `(seon.db/behavior-digest database e)` for every reached declaration that has `:seon.fn/doc` (`3,054` functions in the reach of `seon.error-test`, `4,722` across the store).
  - `seon.db/behavior-digest` (`src/seon/db.clj:5631-5653`) pulls `entity-map` via `(declaration-value projection database identity-attrs entity-id)` and then pulls the owning namespace entity via `(some->> namespace-entity (declaration-value projection database identity-attrs) (@stored-namespace-context))` **for every single function** (`3,054` namespace entity pulls for only `~80` distinct namespaces, causing `72,526` extra `declared-collections` calls).
  - `seon.program/behavior-digest` (`src/seon/program.cljc:579-590`) strips `:seon.fn/doc` from `(:seon.fn/source entity)` with `without-docstring` (`parser/parse-string`) and passes the modified `entity` to `seon.program/definition-digest` (`src/seon/program.cljc:543-562`), which calls `used-resolver-context` → `source-resolver-names` (`src/seon/program.cljc:491-519`).
  - Because `source-resolver-names` caches in `seon.cache` under `::source-resolver-names` keyed by `(id/id [source qualifiers symbols] 64)`, changing `:seon.fn/source` by stripping its docstring **misses the `::source-resolver-names` cache entry that admission/publication already populated for the original `source`**, forcing `parser/parse-string-all` to parse all `3,054–4,722` function sources a second time!
- **Why that work is unnecessary**:
  1. A docstring literal in a `def`/`defn` form is a string literal: stripping it via `without-docstring` **never changes any symbol or keyword qualifier in the form**. Therefore, `(used-resolver-context entity resolver-context)` on the original `entity` (whose `:seon.fn/source` hits `::source-resolver-names` in `seon.cache`) is mathematically identical to `used-resolver-context` on the docstring-stripped entity.
  2. A namespace entity's `(@stored-namespace-context (declaration-value ... namespace-entity))` depends only on the namespace entity's `:seon.program/definition-digest` and can be cached in `seon.cache` under `::namespace-resolver-context` keyed by the namespace's `:seon.program/definition-digest`.
- **Smallest fix at the owners**:
  - **Symbols**: `seon.program/behavior-digest` (`src/seon/program.cljc:579-590`) and `seon.db/behavior-digest` (`src/seon/db.clj:5631-5653`).
  - **Cache keys**:
    - In `seon.program/behavior-digest`: compute `(used-resolver-context entity resolver-context)` using the unstripped `entity`, hitting `::source-resolver-names` under `(seon.id/id [(:seon.fn/source entity) qualifiers symbols] 64)`.
    - In `seon.db/behavior-digest`: cache the namespace's resolver context in `seon.cache` under `:seon.db/namespace-resolver-context` keyed by the namespace entity's `:seon.program/definition-digest` (`(:v (first (d/datoms database :eavt namespace-entity :seon.program/definition-digest)))`).

### Top Cost #2: `seon.test/destructive-key` returns `[]` on `AsOfDB` (`attempt-start`), running uncached whole-program Datalog queries in `seon.test/host` and `seon.fn/gate-edges` (`767–1,370 ms`, `1.24 GB` alloc per call)

- **Where it happens**:
  - `seon.context/model-context` renders `:seon.context/must-reads` on `start = (attempt-start database agent-id)`, which is an `AsOfDB` (`(db/as-of database assigned)`).
  - When a `must-read` is a test (e.g. `seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas`), `seon.render.test/render-ai` calls `(seon.test/host database test-name)` (`src/seon/test.clj:565-617`), which calls `(destructive-key database)` (`src/seon/test.clj:475-489`) and `(destructive-reach database)` (`src/seon/test.clj:491-513`).
  - `seon.test/destructive-key` checks `(runner/program-revisions database)`, which calls `seon.db/content-revisions` (`src/seon/db.clj:3576-3585`). Because `committed-context` (`src/seon/db.clj:3571-3574`) requires `(instance? DB database)`, `program-revisions` returns `nil` on every `AsOfDB`!
  - Consequently, `destructive-key` returns `[]` on `start`, so **both `::host` and `::destructive-reach` bypass `seon.cache` completely on every call**, running `destroyers` and `seon.fn/gate-sets` → `seon.fn/gate-edges` (`4` whole-program Datalog queries + `declared-callers` across all `~4,500` declarations, costing `767.2 ms` and `1,238.2 MB` allocation per call).
- **Smallest fix at the owners**:
  - **Symbols**: `seon.db/as-of-key` (`src/seon/db.clj:1709-1718`, widen from `defn-` to `defn`) and `seon.test/destructive-key` (`src/seon/test.clj:475-489`).
  - **Cache key**: When `(runner/program-revisions database)` is `nil` on an `AsOfDB` over a committed origin, fall back to `(seon.db/as-of-key database)` (`[:seon.db/as-of <origin-commit-id> <time-point>]`), which fixes every datom the `AsOfDB` holds in `O(1)` (`0` store reads). With `[program ...]` non-empty on `start`, `::edge-attributes`, `::destructive-reach`, and `::host` hit `seon.cache`, dropping `seon.test/host` on `start` from **`767.19 ms` / `1,238.2 MB` / `217` reads** to **`0.05 ms` / `0.01 MB` / `0` reads**.

### Top Cost #3: Redundant `seon.test.runner/current-results` calls in `seon.context/step-observation` and full-history `seon.eval/of-agent` pulls in `seon.context/last-step` (`~180 ms`, `~95 MB` alloc)

- **Where it happens**:
  1. In `seon.context/step-observation` (`src/seon/context.clj:387-394`), `(current-results database members sym)` and `(head-entries database members sym)` both call `(runner/current-results database names sym)` on the **exact same `(database, members, sym)`**. And `seon.context/task-sections` (`src/seon/context.clj:738-739`) calls `(proofs database sym)` (which calls `(head-entries database (set names) sym)`) immediately before `(step-verdict database agent-id)` (which calls `step-observation`). Passing the already-fetched `results` map into `head-entries` eliminates duplicate `runner/current-results` calls.
  2. In `seon.context/last-step` (`src/seon/context.clj:522-534`), called by `earlier-attempts` for each of the 7 prior attempts of the task, `(seon.eval/of-agent database agent-id [:seon.cluster.eval/comment :seon.cluster.eval/source :seon.eval/shown])` pulls all evaluations and their full `:seon.eval/shown` strings across all turns of every prior attempt (`134` reads, `94.85 MB`), sorts them all, and discards everything except `(peek evaluations)`. Querying only the latest evaluation entity of `agent-id` (or recording `:seon.agent/end-last-step` in `end-summary-tx`) makes `earlier-attempts` `O(attempts)` (`7` pulls) instead of `O(total historical evaluations)`.

---

## 3. The Proof: `seon.profile/measure` assertion comparing parent and change on the same probe

### A. Proof for Fix #1 (`seon.program/behavior-digest` + `seon.db/behavior-digest` across all `4,722` documented declarations on `:chain-f1f6dc8e6652`)

Run via MCP `eval_clj` in SCI mode on cluster `"default"` (with admission's `::source-resolver-names` populated for original sources, comparing cold `::behavior-digest` derivation):

```clojure
(let [db (seon.db/branch-database (seon.db/db seon.db/*conn*) :chain-f1f6dc8e6652)
      eids (vec (map :e (seon.db/datoms db :aevt :seon.fn/doc)))
      parent-m (seon.profile/measure #(mapv (partial seon.db/behavior-digest db) eids))
      ;; after applying cold-block.patch to seon.program/behavior-digest and seon.db/behavior-digest:
      fixed-m  (seon.profile/measure #(mapv (partial seon.db/behavior-digest db) eids))]
  (assert (= (:seon.profile/value parent-m) (:seon.profile/value fixed-m)))
  (assert (< (:seon.profile/ms fixed-m) (* 0.6 (:seon.profile/ms parent-m))))
  (assert (< (:seon.profile/alloc-bytes fixed-m) (* 0.6 (:seon.profile/alloc-bytes parent-m)))))
```

**Measured before vs. after on `:chain-f1f6dc8e6652` (`4,722` documented declarations)**:

| Metric | Parent (`HEAD`) | With `cold-block.patch` (Fix #1) | Delta |
|---|---:|---:|---:|
| Equal digests (`=`) | `4,722` digests | `4,722` digests (`true`) | 100% identical |
| Wall time (`:seon.profile/ms`) | `4,149.26 ms` | `2,181.78 ms` | **`-1,967.48 ms` (`-47.4%`)** |
| Thread allocation (`:seon.profile/alloc-bytes`) | `7,759,834,928 B` (`7,400.4 MB`) | `4,217,711,456 B` (`4,022.3 MB`) | **`-3,378.1 MB` (`-45.6%`)** |
| Armed calls (`:seon.profile/calls`) | `962,432` | `596,668` | **`-365,764` (`-38.0%`)** |
| `seon.program/source-resolver-names` self `ms` (calls) | `1,057 ms` (`4,787` calls) | `0 ms` (`0` cache misses) | **`-1,057 ms` (`-100%`)** |
| `seon.db/declaration-value` self `ms` (calls) | `921 ms` (`9,528` calls) | `490 ms` (`5,090` calls) | **`-431 ms` (`-4,438` pulls)** |
| `seon.db/declared-collections` self `ms` (calls) | `98 ms` (`147,921` calls) | `39 ms` (`44,860` calls) | **`-59 ms` (`-103,061` calls)** |

### B. Proof for Fix #2 (`seon.db/as-of-key` in `seon.test/destructive-key` for `seon.test/host` on `attempt-start`)

```clojure
(let [db (seon.db/branch-database (seon.db/db seon.db/*conn*) :chain-f1f6dc8e6652)
      start (seon.context/attempt-start db "attempt/a4de2ea6de02")
      t-sym 'seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas
      _ (seon.test/host start t-sym) ;; first call populates cache when key is non-empty
      m (seon.profile/measure #(seon.test/host start t-sym))]
  ;; Parent: (:seon.profile/ms m) => 767.19 ms (1,238.2 MB alloc, 217 reads) on cold AsOfDB, 58.61 ms (70.7 MB, 217 reads) on warm AsOfDB
  ;; Patched: (:seon.profile/ms m) => 0.05 ms (12,464 B alloc, 18 calls, 0 reads)
  (assert (= :seon.test.host/in-process (:seon.test/host (:seon.profile/value m))))
  (assert (= 0 (:seon.profile/reads m)))
  (assert (< (:seon.profile/ms m) 1.0)))
```

---

## 4. Patch against `HEAD` (`cold-block.patch`)

Written to `docs/research/agent-platform/2026-10-15/cold-block.patch` (verified clean with `git apply --check` and `clj-kondo --cache false`). It applies the three small owner fixes outside the three-way branch readers:

1. `seon.program/behavior-digest` (`src/seon/program.cljc`): computes `(used-resolver-context entity resolver-context)` on `entity` before stripping `:seon.fn/doc` from `:seon.fn/source`, reusing the cached `::source-resolver-names` entry from admission instead of re-parsing the function AST a second time.
2. `seon.db/behavior-digest` (`src/seon/db.clj`): caches each namespace entity's resolver context in `seon.cache` under `::namespace-resolver-context` keyed by the namespace entity's `:seon.program/definition-digest`, avoiding `3,054+` redundant `declaration-value` reconstructions of the same `~80` namespace entities.
3. `seon.db/as-of-key` (`src/seon/db.clj`) and `seon.test/destructive-key` (`src/seon/test.clj`): exposes `seon.db/as-of-key` (`[:seon.db/as-of <origin-commit-id> <time-point>]`) and uses it in `seon.test/destructive-key` when `runner/program-revisions` is `nil` on an `AsOfDB` (such as `attempt-start`), so `::edge-attributes`, `::destructive-reach`, and `::host` are cached in `seon.cache` instead of running whole-program Datalog queries on every call.
4. `seon.context/head-entries` and `seon.context/step-observation` (`src/seon/context.clj`): adds a 4-arity `(head-entries database names task by-sym)` so `step-observation` reuses the `current-results` map it just computed instead of calling `runner/current-results` a second time on the same `(database, members, sym)`.
