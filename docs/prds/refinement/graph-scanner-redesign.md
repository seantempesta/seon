# Graph Scanner Redesign

## 1. Current State Analysis

### What Works

- **Scanner** (`seon.graph.scanner`): Parses source with edamame, extracts `schema/register!` calls and `defn`/`defn-` forms. Links functions to specs by naming convention (`foo-request`/`foo-response`). Detects render functions.
- **Analyzer** (`seon.graph.analyzer`): Runs clj-kondo, extracts namespace definitions, var definitions, var usages (call graph), and namespace dependencies. Richer than scanner (arglists, docstrings, row numbers, call graph).
- **Ingest** (`seon.graph.ingest`): Transacts entities into Datalevin. Has bulk and incremental paths. Identity attrs on `:seon.fn/qualified-name`, `:seon.spec/key`, `:seon.ns/name`.

### What's Broken

**Problem 1: Retract-then-insert causes data loss.**

Both `ingest-analysis!` and `ingest-incremental!` retract ALL entities for affected namespaces before inserting new ones. If the replacement data is incomplete (e.g., analyzer fails silently on some forms, or scanner returns fewer results than expected), functions vanish from Datalevin permanently.

The `ingest-file!` function (used nowhere in production -- only in REPL comments) also does retract-then-insert for specs and functions.

The hook's `update-code-index!` calls `ingest-incremental!`, which retracts everything for the namespace, then inserts what the analyzer found. If the analyzer misses something, it's gone.

**Problem 2: Scanner misses functions in some cases.**

The scanner's `find-defn-forms` only looks at **top-level** forms (line 181: `(filter defn-form?)` applied directly to the top-level forms list). This means:

- Functions inside `(do ...)` blocks: missed
- Functions inside `(when ...)` or conditional compilation: missed
- Functions inside `(let [...] (defn ...))`: missed

However, the analyzer (clj-kondo) catches all of these since it does full static analysis. The real issue is that the hook runs BOTH and merges results -- the scanner provides specs+fn-linking, while the analyzer provides the call graph. If either is incomplete, the merged result has gaps.

**Problem 3: `def` vars not tracked.**

The scanner only extracts `defn`/`defn-` forms. The analyzer extracts `var-definitions` from clj-kondo, which DOES include `def` vars -- but the `extract-function-entities` function treats ALL var-definitions as functions (they all get `:seon.fn/*` attrs). There's no distinction between a `def` and a `defn`.

Example: `seon.health.workout/workouts` is a `def` with sample data. clj-kondo reports it as a var-definition with no arglists. Currently it would get a `:seon.fn/qualified-name` entity, but nothing marks it as a var vs a function, and its value/type is unknown.

**Problem 4: Graph connectivity is incomplete.**

- Functions are linked to specs only by naming convention (scanner's `link-fns-to-specs`). If you don't follow the convention, no link.
- The call graph from clj-kondo (var-usages) shows which functions call which, but doesn't distinguish "calls function" from "references var."
- No connection from function/var to "which specs does this function's body reference" beyond the naming convention.
- Spec-to-spec references (`:seon.spec/contains-keys`) only captures top-level map keys, not nested schema references.

### Data Flow in the Hook

```
File saved
  -> hook.clj/update-code-index!
    -> analyzer/analyze-form (clj-kondo on file source)
    -> analyzer/extract-entities (ns, fns, var-usages, ns-deps)
    -> scanner/scan-file (specs, fns from edamame)
    -> scanner/link-fns-to-specs (adds input-spec, output-spec refs to fns)
    -> Merges: analyzer's functions get replaced with scanner-linked functions
    -> ingest/ingest-incremental! (retract all for ns, insert new)
```

The merge at line 294 of hook.clj is critical: `(assoc entities :seon.graph.analyzer/functions linked-fns)`. This REPLACES the analyzer's function list with the scanner's linked version. But the scanner only finds top-level `defn` forms, so any functions the analyzer found that the scanner didn't are LOST.

This is the root cause of the disappearing functions bug.

## 2. Proposed Datalevin Schema

```clojure
{;; === Namespace entities ===
 :seon.ns/name       {:db/valueType :db.type/string  :db/unique :db.unique/identity}
 :seon.ns/doc        {:db/valueType :db.type/string}
 :seon.ns/file       {:db/valueType :db.type/string}
 :seon.ns/target     {:db/valueType :db.type/keyword}
 :seon.ns/dynamic?   {:db/valueType :db.type/boolean}

 ;; === Function entities (defn, defn-) ===
 :seon.fn/qualified-name   {:db/valueType :db.type/string   :db/unique :db.unique/identity}
 :seon.fn/namespace        {:db/valueType :db.type/string}
 :seon.fn/name             {:db/valueType :db.type/string}
 :seon.fn/doc              {:db/valueType :db.type/string}
 :seon.fn/arglists         {:db/valueType :db.type/string}
 :seon.fn/row              {:db/valueType :db.type/long}
 :seon.fn/private          {:db/valueType :db.type/boolean}
 :seon.fn/updated-at       {:db/valueType :db.type/instant}
 ;; Spec links
 :seon.fn/input-spec       {:db/valueType :db.type/ref}
 :seon.fn/output-spec      {:db/valueType :db.type/ref}
 ;; Render metadata
 :seon.fn/render-input-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
 :seon.fn/page-renderer?    {:db/valueType :db.type/boolean}
 :seon.fn/needs-ctx?        {:db/valueType :db.type/boolean}
 :seon.fn/needs-conn?       {:db/valueType :db.type/boolean}

 ;; === Var entities (def) — NEW ===
 :seon.var/qualified-name  {:db/valueType :db.type/string   :db/unique :db.unique/identity}
 :seon.var/namespace       {:db/valueType :db.type/string}
 :seon.var/name            {:db/valueType :db.type/string}
 :seon.var/doc             {:db/valueType :db.type/string}
 :seon.var/row             {:db/valueType :db.type/long}
 :seon.var/private         {:db/valueType :db.type/boolean}
 :seon.var/value-type      {:db/valueType :db.type/keyword}  ; :vector, :map, :string, :number, etc.
 :seon.var/schema-key      {:db/valueType :db.type/ref}      ; ref to spec entity if annotated
 :seon.var/updated-at      {:db/valueType :db.type/instant}

 ;; === Call graph ===
 :seon.call/from-fn  {:db/valueType :db.type/ref}
 :seon.call/to-fn    {:db/valueType :db.type/ref}
 :seon.call/row      {:db/valueType :db.type/long}

 ;; === NS dependencies ===
 :seon.ns.dep/from-ns {:db/valueType :db.type/string}
 :seon.ns.dep/to-ns   {:db/valueType :db.type/string}
 :seon.ns.dep/alias   {:db/valueType :db.type/string}

 ;; === Spec entities ===
 :seon.spec/key           {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
 :seon.spec/namespace     {:db/valueType :db.type/string}
 :seon.spec/definition    {:db/valueType :db.type/string}
 :seon.spec/base-type     {:db/valueType :db.type/keyword}
 :seon.spec/contains-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
 :seon.spec/updated-at    {:db/valueType :db.type/instant}}
```

Key change: `seon.var/*` is a separate entity type from `seon.fn/*`. Functions have arglists; vars have value-types. Both have qualified-name identity attrs, so they upsert cleanly.

## 3. Proposed Scanner Changes

### 3a. Extract `def` forms

Add `def-form?` and `extract-def` alongside the existing `defn-form?` and `extract-defn`:

```clojure
(defn- def-form? [form]
  (and (list? form)
       (symbol? (first form))
       (= 'def (first form))))

(defn- infer-value-type [value-form]
  (cond
    (vector? value-form) :vector
    (map? value-form)    :map
    (set? value-form)    :set
    (string? value-form) :string
    (number? value-form) :number
    (keyword? value-form) :keyword
    (boolean? value-form) :boolean
    (list? value-form)   :expr  ; computed value
    :else                :unknown))

(defn- extract-def [form ns-name now]
  (let [sym (second form)
        rest-forms (drop 2 form)
        [doc-str value-form] (if (and (string? (first rest-forms))
                                      (> (count rest-forms) 1))
                               [(first rest-forms) (second rest-forms)]
                               [nil (first rest-forms)])]
    (when (symbol? sym)
      (cond-> {:seon.var/qualified-name (str ns-name "/" sym)
               :seon.var/namespace ns-name
               :seon.var/name (str sym)
               :seon.var/private (boolean (:private (meta sym)))
               :seon.var/value-type (infer-value-type value-form)
               :seon.var/updated-at now}
        doc-str (assoc :seon.var/doc doc-str)))))
```

### 3b. Use `walk/postwalk` for defn extraction too

Change `find-defn-forms` from top-level-only to walking all forms, so functions inside `do` blocks are found:

```clojure
(defn- find-defn-forms [forms ns-name now]
  (let [results (atom [])]
    (walk/postwalk
     (fn [form]
       (when (defn-form? form)
         (when-let [entity (extract-defn form ns-name now)]
           (swap! results conj entity)))
       form)
     forms)
    @results))
```

This matches how `find-register-calls` already works (uses `postwalk`).

### 3c. Don't replace analyzer functions

The scanner should NOT produce `:seon.fn/*` entities at all. Its job is specs and vars. Function entities come from the analyzer (which has richer data: arglists, row numbers, docstrings). The scanner adds spec-linking metadata to analyzer-produced functions.

Refactor `link-fns-to-specs` to accept analyzer functions and scanner specs, and return enriched analyzer functions. This is already roughly what happens, but the current code creates duplicate fn entities from the scanner that then replace the analyzer's.

## 4. Proposed Ingest Changes

### 4a. Upsert, not retract-then-insert

Since `:seon.fn/qualified-name`, `:seon.spec/key`, and `:seon.var/qualified-name` are all `:db.unique/identity`, Datalevin already handles upsert natively. Transacting `{:seon.fn/qualified-name "seon.foo/bar" :seon.fn/arglists "[x y]"}` will update the existing entity if one exists, or create a new one.

**Remove all `retract-*` functions.** Replace with:

1. **Upsert** all entities from the current scan/analysis. Identity attrs handle create-or-update.
2. **Selective retract** only for entities that were in the OLD namespace but are NOT in the NEW scan. This requires comparing old vs new.

```clojure
(defn- retract-stale-fns!
  "Retract functions that existed in the namespace but are no longer in the source."
  [conn ns-name new-qualified-names]
  (let [existing (d/q '[:find ?e ?qn
                         :in $ ?ns
                         :where
                         [?e :seon.fn/namespace ?ns]
                         [?e :seon.fn/qualified-name ?qn]]
                       @conn ns-name)
        new-set (set new-qualified-names)
        stale (remove (fn [[_ qn]] (new-set qn)) existing)]
    (when (seq stale)
      (db/transact! conn (mapv (fn [[eid _]] [:db/retractEntity eid]) stale)))))
```

Same pattern for specs and vars. Call graph edges (`:seon.call/*`) and ns-deps still need retract-then-insert since they lack identity attrs.

### 4b. Ingest pipeline

```
ingest-namespace! [conn ns-name entities specs vars]
  1. Upsert namespace entity
  2. Upsert spec entities
  3. Upsert function entities (with spec links)
  4. Upsert var entities
  5. Retract stale functions (in ns but not in new data)
  6. Retract stale specs (in ns but not in new data)
  7. Retract stale vars (in ns but not in new data)
  8. Retract call edges from ns, insert new ones
  9. Retract ns-deps from ns, insert new ones
```

Steps 1-4 are safe (upsert). Steps 5-7 only retract what's confirmed missing. Steps 8-9 are retract-then-insert but for non-identity edges, which is fine -- a missing call edge is harmless compared to a missing function.

## 5. Proposed Hook Integration

### Current problem

```clojure
;; hook.clj line 294 - THIS LOSES ANALYZER FUNCTIONS
(let [linked-fns (link-fns-to-specs
                   (:seon.graph.analyzer/functions entities) specs)
      entities (assoc entities :seon.graph.analyzer/functions linked-fns)]
  ...)
```

Wait -- re-reading this, `link-fns-to-specs` is called with the ANALYZER's functions, not the scanner's. So the replacement shouldn't lose analyzer functions. The bug is elsewhere.

Actually, looking more carefully: `link-fns-to-specs` takes `fns` and `specs`. It iterates over `fns` and tries to find matching specs. It returns the SAME fns, possibly enriched. So it should be safe.

The real data loss happens in `ingest-incremental!`: it retracts ALL functions in the namespace, then inserts only what the analyzer found. If `analyze-form` (clj-kondo on a string via stdin) produces fewer var-definitions than the full file has, functions disappear.

Possible causes:

- `analyze-form` uses `{:lint ["-"] :filename file-path}` which reads from stdin, not the file. But the hook passes the file's source via `(slurp file-path)` wrapped in `with-in-str`. This should work.
- clj-kondo's `{:var-definitions {:shallow true}}` might skip some definitions in certain contexts.
- If the file has syntax issues that clj-kondo partially parses, some functions may not appear.

### Proposed fix

Replace the hook's `update-code-index!` with:

```clojure
(defn- update-code-index! [file-path]
  (let [conn (get-graph-conn)
        ;; 1. Analyze with clj-kondo (functions, call graph, ns-deps)
        analysis (analyzer/analyze-form
                   {::analyzer/source (slurp file-path)
                    ::analyzer/file-path file-path})
        ;; 2. Scan with edamame (specs, vars, def forms)
        scan-results (scanner/scan-file {::scanner/file-path file-path})
        specs (filterv :seon.spec/key scan-results)
        vars (filterv :seon.var/qualified-name scan-results)  ; NEW
        ns-markers (filterv :seon.ns/name scan-results)]
    (when (::analyzer/success analysis)
      (let [entities (analyzer/extract-entities
                       {::analyzer/raw-analysis (::analyzer/raw-analysis analysis)})
            fns (::analyzer/functions entities)
            linked-fns (scanner/link-fns-to-specs fns specs)]
        (ingest/ingest-namespace!
          {::ingest/conn conn
           ::ingest/ns-name (or (:seon.ns/name (first ns-markers))
                                (:seon.fn/namespace (first fns)))
           ::ingest/functions linked-fns
           ::ingest/specs specs
           ::ingest/vars vars
           ::ingest/call-edges (::analyzer/var-usages entities)
           ::ingest/ns-deps (::analyzer/namespace-usages entities)
           ::ingest/ns-entities (into (::analyzer/namespaces entities)
                                      ns-markers)})))))
```

Key difference: `ingest-namespace!` upserts functions and only retracts stale ones, so if the analyzer misses a function, it stays in the graph (stale > missing).

## 6. Migration Path

### Phase 1: Fix the data loss (critical)

1. Add `retract-stale-fns!`, `retract-stale-specs!` helpers to ingest.
2. Change `ingest-incremental!` to upsert + retract-stale instead of retract-all + insert.
3. Keep everything else the same. This is a surgical fix.

**Test**: Edit a file, verify all its functions remain in Datalevin. Edit again, verify a deleted function gets retracted.

### Phase 2: Track `def` vars

1. Add `:seon.var/*` attrs to `datalevin-schema`.
2. Add `def-form?`, `extract-def`, `find-def-forms` to scanner.
3. Add `retract-stale-vars!` to ingest.
4. Update hook to pass vars through.
5. Update `scan-source` to return var entities alongside specs and fns.

**Test**: `seon.health.workout/workouts` appears as a `:seon.var/qualified-name` entity.

### Phase 3: Fix scanner's defn walking

1. Change `find-defn-forms` to use `postwalk` (finds nested defns).
2. But since we're moving to analyzer-only for functions (Phase 4), this may be unnecessary.

### Phase 4: Clean separation of concerns

1. Scanner: extracts specs and vars (edamame-based, structural).
2. Analyzer: extracts functions, call graph, ns-deps (clj-kondo-based, semantic).
3. Scanner enriches analyzer functions with spec links (`link-fns-to-specs`).
4. Scanner does NOT produce `:seon.fn/*` entities. Remove `defn-form?`, `extract-defn`, `find-defn-forms` from scanner.
5. Single `ingest-namespace!` function replaces both `ingest-analysis!` and `ingest-incremental!`.

**Test**: Full test suite passes. `query_graph` finds functions, specs, and vars. No data loss on incremental updates.

### Schema migration

Datalevin schema is additive -- adding new attrs (`:seon.var/*`) requires no migration. Just update `datalevin-schema` and the new attrs become available on next startup. Existing entities are unaffected.

No data migration needed. On first full scan after deployment, var entities get created.
