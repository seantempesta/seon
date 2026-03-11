---
type: prd
status: completed
tags: [prd, database]
---
# PRD: Graph Cleanup — Remove Derived Attrs, Unify Resolution

---

## Goals

1. **Remove pre-computed render attributes** from the graph — the graph stores facts (specs, functions, calls), not derived state
2. **Unify resolution** — rendering, documentation, and health checks all use the same query: "find functions whose output contains key X, whose required input keys ⊆ available data"
3. **Link ALL functions to specs** — not just renderers. Every function with a `-request`/`-response` spec gets linked.

---

## Problem Statement

The graph is polluted with render-specific derived attributes:

```
:seon.fn/render-input-keys     — duplicates data from input spec's contains-keys minus optional-keys
:seon.fn/render-optional-keys  — duplicates data from input spec's optional-keys
:seon.fn/page-renderer?        — derived from whether input has *ctx*
:seon.fn/needs-ctx?            — derived from whether input has *ctx*
:seon.fn/needs-conn?           — derived from whether input has *conn*
```

These attrs were added because `link-fns-to-specs` in `extract.clj` only links functions whose output spec contains `:seon.render/html`. Non-render functions are invisible to the graph even if they have perfectly good specs.

**Impact:**

- Documentation system can't discover function interfaces (only renderers are linked)
- Health checks can't use the same resolution pattern (no output key to query)
- Adding any new "discoverable function" pattern requires adding more pre-computed attrs
- The 5 derived attrs duplicate information already available via spec joins

**Root cause:** The graph stores conclusions instead of facts. The fix is to store only facts and derive conclusions at query time.

---

## Resources to Study

| Resource | What's There |
|----------|--------------|
| `docs/prds/refinement/renderer-resolution.md` | Full renderer resolution algorithm — specificity, tiebreaking, data sources. **"What's Built" section lists all working code.** |
| `docs/prds/refinement/code-graph-architecture.md` | Scanner vs analyzer split, extraction pipeline, REPL test results proving clj-kondo resolves aliases without cache |
| `docs/prds/refinement/graph-scanner-redesign.md` | Datalevin schema, upsert-not-retract pattern, data loss root cause analysis |
| `src/seon/graph/extract.clj` | `link-fns-to-specs` — the render gate we're removing (lines 192-246) |
| `src/seon/graph/ingest.clj` | Datalevin schema definition (lines 42-98) |
| `src/seon/graph/scanner.clj` | `extract-contains-keys` (124-142), `extract-optional-keys` (144-165) — already correct |
| `src/seon/graph/query.clj` | Existing query helpers — `dependencies-of`, `callers-of`, ref join examples (lines 135-142) |
| `src/seon/render.clj` | `find-renderer` (137-186), `resolve-renderer` (220-267) — consumers we're rewriting |
| `src/seon/ns/lifecycle.clj` | `find-page-render-fn` — consumer we're rewriting |
| `test/seon/render_test.clj` | 40+ tests — must all pass after rewrite |
| `reference-code/datalevin/test/datalevin/test/lookup_refs.clj` | Datalevin lookup ref test patterns |
| `reference-code/datalevin/test/datalevin/test/query.cljc` | Datalevin query tests with ref joins |

---

## What Already Works (Don't Break These)

From `renderer-resolution.md` "What's Built" section and agent research:

- **`resolve-renderer`** — specificity algorithm with namespace proximity tiebreaking (40+ tests)
- **`find-renderer`** — Datalevin-based lookup with caching
- **Schema extraction** — `extract-contains-keys` / `extract-optional-keys` in scanner.clj. Syntax `[::key {:optional true} :type]` is correct Malli.
- **Ref attributes** — `:seon.fn/input-spec` and `:seon.fn/output-spec` are `:db.type/ref` in the schema
- **Lookup refs** — `:seon.spec/key` is `:db.unique/identity`, so `[:seon.spec/key :seon.foo/bar-request]` works as a lookup ref
- **Call-graph ref joins** — proven pattern in `query.clj:135-142` joining through `:seon.call/from-fn` → `:seon.fn/qualified-name`

---

## Solution Design

### The Unified Pattern

All discoverable systems work identically:

| System | Output key | Meaning |
|--------|-----------|---------|
| HTML Rendering | `:seon.render/html` | Function produces HTML hiccup |
| Documentation | `:seon.render/documentation` | Function produces documentation text |
| Health Checks | `:seon.health/status` | Function reports component health |

**Discovery:** Find functions whose output spec's `:seon.spec/contains-keys` includes the target key.

**Resolution:** Filter by input spec required keys ⊆ available data. Rank by specificity.

### CONFIRMED: Hybrid Datalog+Clojure Resolution

**Pure Datalog cannot do set operations on cardinality-many attributes.**

When you write `[?e :seon.spec/contains-keys ?k]`, Datalevin binds `?k` to ONE key at a time (one datom per value). You cannot bind the whole set and call `subset?`. The plan in the other tab had queries with `[(clojure.set/subset? ...)]` predicates — **these won't work.**

The existing codebase correctly uses hybrid resolution:

1. **Datalog** finds candidates (fns with matching output key via ref join)
2. **Clojure** pulls input spec data and filters with `every?` / `subset?`
3. **Clojure** ranks by specificity (key count → proximity → alphabetical)

This pattern stays. We just change WHAT Datalog queries (ref joins to specs instead of pre-computed attrs).

### The Shared Helper

Goes in `seon.graph.query`:

```clojure
(defn functions-with-output-key
  "Find functions whose output spec contains a specific key.
   Uses ref join: fn → output-spec → contains-keys.
   Then pulls input spec data and computes required vs optional keys.

   Returns [{:seon.fn/qualified-name \"...\",
             :required-keys #{...},
             :optional-keys #{...},
             :seon.fn/doc \"...\"} ...]"
  [{::keys [conn output-key]}]
  ;; STEP 1: Datalog ref join (verified in Phase 0 research)
  ;; STEP 2: Pull input spec's contains-keys and optional-keys
  ;; STEP 3: required = (set/difference contains optional)
  ;; STEP 4: Return enriched maps
  )
```

### Required Keys: Computed, Not Stored

Required keys = contains-keys − optional-keys. This is computed at query time, not stored separately.

Rationale: The graph rebuilds from source on startup, so redundancy isn't a migration concern. But computing it at query time means one source of truth (the spec). Cardinality-many set operations in Clojure are fast. The shared helper computes this once and returns it.

---

## Constraints

- Must not break existing render tests (40+ in `render_test.clj`)
- Graph rebuilds from source on startup — no data migration needed
- Schema attr removal is safe: removing attrs from the schema map means they stop being transacted. Existing data is overwritten on next re-scan.
- Resolution performance must stay fast — current approach caches results in `resolution-cache`

### No Guessing — Read Source, Test in REPL, Then Write

**This is a hard rule for all agents on this PRD.**

1. **Read the source code** before writing anything. Datalevin source is in `reference-code/datalevin/`. Malli source is in `reference-code/malli/`. The project's own code is the primary reference.
2. **Test in the REPL first.** Create an in-memory Datalevin database, try the query, see if it works. Don't assume — verify.
3. **Only write to disk when you're sure it's correct.** If something doesn't behave as expected, read the library source to understand why. Don't cargo-cult patterns from docs that might be outdated.
4. **When stuck, search with context.** Use `(user/search "query" :files ["relevant/file.clj"])` — include the actual code that's confusing you.

The reference code directories exist so agents can look up exactly how Datalevin handles refs, how Malli extracts optional keys, how the query engine resolves joins. Use them.

---

## Phases

### Phase 0: Verify Assumptions (Research — COMPLETE)

All research questions answered with live REPL evidence. See `notes.md` for full transcripts.

| # | Question | Result |
|---|----------|--------|
| R1 | Ref joins through cardinality-many keywords | **PASS** — `[?e :seon.fn/output-spec ?out] [?out :seon.spec/contains-keys :seon.render/html]` works. Multiple candidates work. |
| R2 | Lookup refs stored correctly by `link-fns-to-specs` | **PASS** — `[:seon.spec/key :seon.foo/bar-request]` resolves to entity ID at transact time. Stored as `#:db{:id 1}`, not raw vector. |
| R3 | All code references to 5 derived attrs | **COMPLETE** — 4 source files, 3 test files, ~8 doc files. Full list in notes.md. |
| R4 | `d/pull` through refs | **PASS** — One pull gets fn + nested input/output spec data including contains-keys and optional-keys. |
| R5 | Performance comparison | Skipped (nice-to-have, no reason to expect problems). |

**Gotchas discovered:**

1. Spec entities must be transacted BEFORE fn entities for lookup refs to resolve. `ingest-namespace!` already does this correctly (specs in step 2, fns in step 3).
2. Cardinality-many values come back as vectors from `d/pull`, not sets. Convert with `(set ...)` for subset operations.

**Proven query pattern for `functions-with-output-key`:**

```clojure
;; Step 1: Find candidate entity IDs via ref join
(d/q '[:find ?e
       :in $ ?output-key
       :where
       [?e :seon.fn/output-spec ?out]
       [?out :seon.spec/contains-keys ?output-key]]
     @conn output-key)

;; Step 2: Pull fn + spec data in one call per candidate
(d/pull @conn [:seon.fn/qualified-name :seon.fn/namespace :seon.fn/doc
               {:seon.fn/input-spec [:seon.spec/contains-keys :seon.spec/optional-keys]}
               {:seon.fn/output-spec [:seon.spec/contains-keys]}]
        eid)

;; Step 3: Compute required keys in Clojure
;; required = (set/difference (set contains-keys) (set optional-keys))
```

### Phase 1: Clean the Graph (Implementation — NEXT)

**All Phase 0 research confirms this is safe to proceed.**

This phase is ONE agent task. The agent must complete ALL changes and run ALL tests before returning.

#### Step-by-step transformation

**1. `src/seon/graph/ingest.clj` — Remove 5 attrs from schema**

Delete these lines from `datalevin-schema`:

```clojure
:seon.fn/render-input-keys    {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
:seon.fn/render-optional-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
:seon.fn/page-renderer?       {:db/valueType :db.type/boolean}
:seon.fn/needs-ctx?           {:db/valueType :db.type/boolean}
:seon.fn/needs-conn?          {:db/valueType :db.type/boolean}
```

**2. `src/seon/graph/extract.clj` — Simplify `link-fns-to-specs`**

Remove the render gate (`is-render?` check). Remove all 5 derived attr computations. Link ALL functions with matching `-request`/`-response` specs:

```clojure
;; BEFORE: only links render functions, computes 5 derived attrs
;; AFTER: links ALL functions, stores only spec refs
(defn- link-fns-to-specs [fns specs]
  (let [spec-by-key (into {} (map (juxt :seon.spec/key identity)) specs)
        now (java.util.Date.)]
    (mapv (fn [fn-entity]
            (let [qn (:seon.fn/qualified-name fn-entity)
                  ns-str (:seon.fn/namespace fn-entity)
                  fn-name (:seon.fn/name fn-entity)
                  input-key (keyword ns-str (str fn-name "-request"))
                  output-key (keyword ns-str (str fn-name "-response"))]
              (cond-> (assoc fn-entity :seon.fn/updated-at now)
                (spec-by-key input-key)
                (assoc :seon.fn/input-spec [:seon.spec/key input-key])
                (spec-by-key output-key)
                (assoc :seon.fn/output-spec [:seon.spec/key output-key]))))
          fns)))
```

**3. `src/seon/graph/query.clj` — Add `functions-with-output-key`**

New shared helper using the proven query pattern from Phase 0:

```clojure
(defn functions-with-output-key
  "Find functions whose output spec contains a specific key.
   Uses ref join: fn → output-spec → contains-keys.
   Pulls input spec data and computes required vs optional keys.

   (functions-with-output-key {::conn conn ::output-key :seon.render/html})
   ;; => [{:seon.fn/qualified-name \"seon.foo/bar\"
   ;;      :required-keys #{:seon.foo/x :seon.foo/y}
   ;;      :optional-keys #{:seon.foo/z}} ...]"
  [{::keys [conn output-key]}]
  (let [eids (d/q '[:find ?e
                    :in $ ?output-key
                    :where
                    [?e :seon.fn/output-spec ?out]
                    [?out :seon.spec/contains-keys ?output-key]]
                  @conn output-key)]
    (mapv (fn [[eid]]
            (let [pulled (d/pull @conn
                           [:seon.fn/qualified-name :seon.fn/namespace
                            :seon.fn/name :seon.fn/doc :seon.fn/updated-at
                            {:seon.fn/input-spec [:seon.spec/contains-keys
                                                  :seon.spec/optional-keys]}
                            {:seon.fn/output-spec [:seon.spec/contains-keys]}]
                           eid)
                  input-spec (:seon.fn/input-spec pulled)
                  contains (set (:seon.spec/contains-keys input-spec))
                  optional (set (:seon.spec/optional-keys input-spec))]
              (assoc pulled
                     :required-keys (clojure.set/difference contains optional)
                     :optional-keys optional)))
          eids)))
```

**4. `src/seon/render.clj` — Rewrite 3 functions**

Replace `find-renderer`, `resolve-renderer`, `find-page-renderer`. All currently query `:seon.fn/render-input-keys` directly. Rewrite to call `functions-with-output-key` and use `:required-keys` from the result.

The filtering/ranking logic stays the same:

- Required keys must be subset of available data keys
- Rank by key count (more specific wins)
- Tiebreak by namespace proximity, then `updated-at`, then alphabetical

The caching in `resolution-cache` should wrap the new helper.

**5. `src/seon/ns/lifecycle.clj` — Rewrite `find-page-render-fn`**

Currently queries `:seon.fn/page-renderer? true` and `:seon.fn/render-input-keys`. Rewrite to use `functions-with-output-key` with `:seon.render/html`, then filter for functions whose required-keys include the `*ctx*` key.

**6. `src/seon/ns/routes.clj` — Update comment (line 297)**

One comment references `:seon.fn/page-renderer?`. Update text only.

**7. Tests — Rewrite test data to use specs+refs**

Tests currently transact fn entities with `:seon.fn/render-input-keys` directly. They need to transact SPEC entities first, then fn entities with lookup refs. This is the biggest test change.

| Test file | What changes |
|-----------|-------------|
| `test/seon/render_test.clj` | ~10 test data blocks: replace `render-input-keys` with spec entities + refs. Assertions change from checking `render-input-keys` to checking query results. |
| `test/seon/graph/extract_test.clj` | Remove assertions on `page-renderer?`, `needs-ctx?`, `render-input-keys`. Add assertions that `input-spec` and `output-spec` refs are present. |
| `test/seon/health/workout_test.clj` | Same — remove assertions on derived attrs, verify spec links instead. |

**8. Docs — Update references (can be deferred)**

~8 doc files reference old attrs. These are historical notes, not code. Update as a separate cleanup pass.

#### Agent verification checklist

The Phase 1 agent MUST verify all of these before returning:

```clojure
;; 1. All render tests pass
(user/run-tests 'seon.render-test)

;; 2. All extract tests pass
(user/run-tests 'seon.graph.extract-test)

;; 3. All workout tests pass
(user/run-tests 'seon.health.workout-test)

;; 4. Graph links ALL fns with specs (not just renderers)
;; After a reload, count linked fns — should be >> just render fns
(d/q '[:find (count ?e)
       :where [?e :seon.fn/input-spec _]]
     @graph-conn)

;; 5. No references to old attrs remain in source code
;; (grep should return only doc files)
```

### Phase 2: Documentation Rendering (New Feature)

**File:** `src/seon/render/code.clj` (NEW)

Core functions:

- `render-ns-docs` — default doc renderer, queries graph, needs no namespace cooperation
- `compatible-functions` — given data keys, find functions that can consume them
- `resolve-docs` — find best doc renderer using unified pattern
- `context-for-agent` — assemble working context for agent prompts

**Output key:** `::seon.render/documentation`

### Phase 3: Health Checks

Same pattern. Output key: `:seon.health/status`.

### Phase 4: Agent Prompt Integration

Wire `context-for-agent` into `build-agent-prompt` in `seon.ai.claude.clj`.

---

## Open Questions (Agents: update notes.md with answers)

1. **Is `::ns` schema machinery worth the complexity?** The other tab's plan shows each namespace registering a `::ns` schema for custom documentation. Alternative: just `resolve` a `ns-meta` var at runtime and merge it in. The schema approach is consistent with the unified pattern but adds boilerplate. The resolve approach is simpler but breaks the pattern. **Agent should prototype both in Phase 2.**

2. **Should `functions-with-output-key` cache results?** The current `find-renderer` uses `resolution-cache` invalidated on rescan. The new helper should probably do the same.

3. **What's the right default for `render-ns-docs` detail level?** `:summary` (names only), `:interface` (+ arglists + key types), or `:deep-dive` (+ full docstrings)?

---

## Success Criteria

1. **All existing render tests pass** after Phase 1 — zero regressions
2. **Graph indexes ALL functions with specs**, not just renderers — verified by REPL query counting linked fns before and after
3. **No pre-computed render attrs** in the schema or transacted data
4. **`functions-with-output-key`** works for any output key — same query, different key
5. **Default doc renderer** produces useful output for any namespace with docstrings and schemas
6. **`(user/health)`** discovers health functions automatically

---

## Deliverables

- [x] Phase 0: Research findings in `notes.md` with REPL transcript evidence (R1-R4 PASS)
- [x] Phase 1: Graph cleanup — remove 5 attrs, rewrite consumers, all render tests pass (47 tests, 136 assertions, 0 failures)
- [x] Phase 1b: Spec linkage upgraded from naming convention to `:malli/schema` metadata parsing (scanner.clj + extract.clj)
- [x] Phase 1c: Datalevin conn lifecycle fixes — scanner resume always halt+init, runtime-db stores only conn manager
- [x] Phase 2: `seon.render.code` with default doc renderer + tests (14 tests, 31 assertions, 0 failures)
- [ ] Parallel: Flow-based Datalevin writer (separate PRD at `docs/prds/flow-datalevin-writer/prd.md`)
- [x] Phase 3: Graph-discovered health checks — already works via Phase 1 spec linkage (`seon.health/check` discoverable via `:seon.health/status`)
- [x] Phase 4: Agent prompts include namespace context + health status (`build-agent-prompt` in `seon.ai.claude`)
