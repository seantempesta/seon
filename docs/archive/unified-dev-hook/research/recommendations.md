# Recommendations for Unified Dev Hook Implementation

Based on research into Clojure parsing, Malli schema resolution, XTDB storage patterns, and change detection strategies.

## Executive Summary

Use **REPL introspection** for extracting code metadata, **Malli's built-in registry** for schema resolution, and **XTDB** for persistent state tracking. This approach is simpler, faster, and more reliable than static file parsing.

---

## Architecture Recommendation

### Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        bin/seon-hook (Babashka)                      │
├─────────────────────────────────────────────────────────────────────┤
│ 1. Receive file edit event from Claude Code                         │
│ 2. Run syntax repair (existing clojure-mcp-light code)               │
│ 3. Call REPL: (seon.dev.feedback/check-namespace 'ns)               │
│ 4. Process results, optionally call Gemini                          │
│ 5. Return JSON response                                             │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    seon.dev.feedback (Clojure)                       │
├─────────────────────────────────────────────────────────────────────┤
│ - Runs inside the REPL (port 7888)                                  │
│ - Uses (m/function-schemas) to get registered schemas               │
│ - Uses (mg/check) for generative testing                            │
│ - Stores function metadata in XTDB                                  │
│ - Detects new functions by comparing against XTDB state             │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                           XTDB                                       │
├─────────────────────────────────────────────────────────────────────┤
│ :function entities - Track function definitions                      │
│ :schema entities - Track resolved schema definitions                 │
│ :edit-event entities - Track edit history                           │
│ :error entities - Track failures for pattern detection              │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Key Decisions

### 1. Use REPL Introspection, Not Static Parsing

**Decision**: Query the running REPL after namespace reload.

**Rationale**:
- Malli already tracks function schemas via `(m/function-schemas)`
- Schemas are validated and parsed by Malli
- No file I/O or parsing overhead
- Always synchronized with actual loaded code
- Works with macros, reader conditionals, and complex forms

**Implementation**:
```clojure
(defn namespace-schemas [ns-sym]
  (get-in (m/function-schemas) [:clj ns-sym]))
```

### 2. Use Malli's Registry for Schema Resolution

**Decision**: Traverse schemas using `m/walk` and `m/deref`.

**Rationale**:
- Malli handles all edge cases (refs, local registries, recursive schemas)
- No need to reimplement schema parsing
- Works with custom registries defined in code

**Implementation**:
```clojure
(defn resolve-schema-refs [schema]
  (let [refs (atom #{})]
    (m/walk schema
            (fn [s _ children _]
              (when (and (keyword? (m/type s)) (namespace (m/type s)))
                (swap! refs conj (m/type s)))
              (m/-set-children s children)))
    @refs))
```

### 3. Store Code Entities in XTDB

**Decision**: Use XTDB for persistent state (functions, schemas, errors).

**Rationale**:
- Temporal queries for "what was the code when this error happened"
- Single source of truth (no separate `.edn` files)
- Relationship queries (what functions use this schema)
- Already running in the system

**Entity Model** (from PRD):
```clojure
;; Function entity
{:xt/id :seon.foo/bar
 :entity/type :function
 :fn/namespace :seon.foo
 :fn/name :bar
 :fn/source "(defn bar [ctx] ...)"
 :fn/source-hash "abc123"
 :fn/schema [:=> [:cat :user/id] :result]
 :fn/schema-refs #{:user/id :result}
 :fn/first-seen #inst "2024-12-28T..."}
```

### 4. Detect Changes via Source Hash

**Decision**: Hash function source to detect changes.

**Rationale**:
- Fast comparison (no deep equality check)
- Detects logic changes, not just re-evaluations
- Simple to implement

**Implementation**:
```clojure
(defn source-hash [var]
  (let [source (slurp (io/resource (:file (meta var))))]
    ;; Extract the defn form for this specific function
    ;; Hash it for comparison
    (hash source)))

;; Simpler approach: use var metadata
(defn var-identity [var]
  {:file (:file (meta var))
   :line (:line (meta var))})
```

### 5. Detect New Functions via XTDB State

**Decision**: Compare current function set against XTDB state.

**Rationale**:
- Persistent across REPL restarts
- Query-able (find all functions added this week)
- Simpler than tracking in-memory

**Implementation**:
```clojure
(defn new-functions [ns-sym]
  (let [current (set (keys (namespace-schemas ns-sym)))
        known (set (map :xt/id (query-functions-in-ns ns-sym)))]
    (clojure.set/difference current known)))
```

---

## Implementation Plan

### Phase 1: Core Feedback Namespace

Create `src/seon/dev/feedback.clj`:

```clojure
(ns seon.dev.feedback
  "REPL-side feedback utilities for the unified hook."
  (:require [malli.core :as m]
            [malli.generator :as mg]
            [xtdb.api :as xt]
            [clojure.test :as test]))

;; ============================================================
;; Schema Introspection
;; ============================================================

(defn namespace-schemas
  "Get all function schemas registered for a namespace."
  [ns-sym]
  (get-in (m/function-schemas) [:clj ns-sym]))

(defn extract-schema-refs
  "Extract referenced schema keywords from a function schema."
  [schema]
  (let [refs (atom #{})]
    (m/walk
      (m/schema schema)
      (fn [s _ children _]
        (let [t (m/type s)]
          (when (and (keyword? t) (namespace t))
            (swap! refs conj t)))
        (m/-set-children s children)))
    @refs))

;; ============================================================
;; Change Detection
;; ============================================================

(defn stored-functions
  "Get all functions for a namespace from XTDB."
  [node ns-sym]
  (xt/q node
        (xt/template
          (from :function [{:fn/namespace ~ns-sym} xt/id fn/name fn/source-hash]))))

(defn new-functions
  "Find functions that exist now but aren't in XTDB."
  [node ns-sym]
  (let [current (set (keys (namespace-schemas ns-sym)))
        stored (set (map :xt/id (stored-functions node ns-sym)))]
    (clojure.set/difference current stored)))

;; ============================================================
;; Generative Testing
;; ============================================================

(defn check-function
  "Run generative tests on a single function."
  [ns-sym fn-sym & [{:keys [num-tests] :or {num-tests 10}}]]
  (when-let [schema-data (get (namespace-schemas ns-sym) fn-sym)]
    (let [var (ns-resolve ns-sym fn-sym)
          result (mg/check (:schema schema-data) @var {:num-tests num-tests})]
      (when result  ; nil means passed
        {:fn fn-sym
         :error result}))))

(defn check-namespace
  "Check all schema-annotated functions in a namespace."
  [ns-sym & [opts]]
  (let [schemas (namespace-schemas ns-sym)]
    (->> (for [[fn-sym _] schemas]
           (check-function ns-sym fn-sym opts))
         (remove nil?)
         (into []))))

;; ============================================================
;; XTDB Storage
;; ============================================================

(defn record-function!
  "Store or update a function entity in XTDB."
  [node ns-sym fn-sym]
  (let [var (ns-resolve ns-sym fn-sym)
        schema-data (get (namespace-schemas ns-sym) fn-sym)
        existing (xt/entity node (symbol (str ns-sym "/" fn-sym)))
        now (java.time.Instant/now)]
    (xt/execute-tx node
      [[:put-docs :function
        {:xt/id (symbol (str ns-sym "/" fn-sym))
         :entity/type :function
         :fn/namespace ns-sym
         :fn/name fn-sym
         :fn/schema (when schema-data (m/form (:schema schema-data)))
         :fn/schema-refs (when schema-data (extract-schema-refs (:schema schema-data)))
         :fn/file (:file (meta var))
         :fn/line (:line (meta var))
         :fn/first-seen (or (:fn/first-seen existing) now)}]])))

(defn record-error!
  "Store an error event in XTDB."
  [node error-type fn-sym data]
  (xt/execute-tx node
    [[:put-docs :error
      {:xt/id (java.util.UUID/randomUUID)
       :entity/type :error
       :error/timestamp (java.time.Instant/now)
       :error/type error-type
       :error/function fn-sym
       :error/data data}]]))
```

### Phase 2: Hook Script

Create `bin/seon-hook` (Babashka):

```clojure
#!/usr/bin/env bb

;; Unified development feedback hook
;; Combines syntax repair, testing, and AI review

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def nrepl-port 7888)

(defn nrepl-eval [code]
  (let [result (p/sh ["clj-nrepl-eval" "-p" (str nrepl-port) code])]
    {:exit (:exit result)
     :out (str/trim (:out result))
     :err (:err result)}))

(defn file->ns [file-path]
  ;; Convert file path to namespace
  (when-let [match (re-find #"src/(seon/.+)\.clj$" file-path)]
    (-> (second match)
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn check-namespace [ns-sym]
  (let [code (format "(seon.dev.feedback/check-namespace '%s {:num-tests 10})" ns-sym)
        result (nrepl-eval code)]
    (when (zero? (:exit result))
      (read-string (:out result)))))

(defn main []
  (let [input (json/parse-string (slurp *in*) true)
        file-path (or (get-in input [:tool_input :file_path])
                      (get-in input [:file_path]))
        ns-sym (file->ns file-path)]

    (when (and file-path (str/ends-with? file-path ".clj") ns-sym)
      ;; 1. Reload namespace
      (nrepl-eval (format "(require '%s :reload)" ns-sym))

      ;; 2. Check for new functions (triggers AI review)
      (let [new-fns (nrepl-eval (format "(seon.dev.feedback/new-functions '%s)" ns-sym))]
        (when (seq (:out new-fns))
          (println "New functions:" (:out new-fns))))

      ;; 3. Run generative tests
      (let [errors (check-namespace ns-sym)]
        (if (seq errors)
          (println (json/generate-string
                    {:decision "block"
                     :reason (format "Generative test failures: %s" errors)}))
          (println "All generative tests passed"))))))

(main)
```

### Phase 3: Gemini Integration

Use the existing `seon.ai.gemini` namespace (from research doc) to:
1. Explain generative test failures
2. Review new functions
3. Suggest fixes for patterns of errors

---

## Trade-offs Accepted

1. **REPL required** - Cannot analyze files that don't compile. Acceptable because the hook runs after syntax repair and reload.

2. **No source position tracking** - We don't track exact character positions. Could add rewrite-clj later if needed for IDE integration.

3. **No offline analysis** - Cannot analyze code without running REPL. Acceptable for development workflow.

---

## Testing Strategy

1. **Unit tests** for `seon.dev.feedback`:
   - `extract-schema-refs` - test various schema structures
   - `new-functions` - mock XTDB, verify detection
   - `check-function` - test with known-failing schemas

2. **Integration tests**:
   - Full hook flow with test file
   - Verify XTDB entities created
   - Verify generative testing works

3. **Manual testing**:
   - Edit file, verify hook fires
   - Add new function with schema, verify AI review triggers
   - Break function, verify error detected

---

## Open Questions for Implementation

1. **Schema registry scope**: Should we use a global registry or per-namespace? Global is simpler but less modular.

2. **Generative test count**: 10 is fast but may miss edge cases. Make configurable.

3. **AI review trigger**: Only new functions, or also significant changes to existing functions?

4. **Error pattern detection**: How many failures before escalating to AI? 3? 5?

---

## Files to Create

| Path | Purpose |
|------|---------|
| `src/seon/dev/feedback.clj` | REPL-side feedback utilities |
| `src/seon/dev/schema_resolver.clj` | Schema resolution for AI context |
| `bin/seon-hook` | Unified Babashka hook script |
| `.claude/seon-hook.edn` | Configuration |
| `test/seon/dev/feedback_test.clj` | Tests |

---

## Next Steps

1. **Implement `seon.dev.feedback`** - Core namespace with introspection and testing
2. **Test in REPL** - Verify schema extraction and generative testing work
3. **Create hook script** - Babashka script that calls REPL
4. **Add XTDB storage** - Persist function entities
5. **Integrate Gemini** - AI review for new functions and failures
6. **Update `.claude/settings.json`** - Point to new hook
