# Research: Unified Code Analysis with clj-kondo/clojure-lsp

Date: 2026-01-04

## Executive Summary

We investigated using clj-kondo and clojure-lsp as our single source of truth for code understanding. The key findings:

1. **clj-kondo analysis is powerful and fast** - ~30-45ms per file, provides var-usages for call graphs
2. **clojure-lsp.api exists for programmatic use** - `lsp-api/dump` gives enriched clj-kondo analysis
3. **cljfmt via clojure-lsp is the recommended formatter** - integrated, stable, good LSP support
4. **Call graph extraction is feasible** - var-usages contain `:from-var` field showing caller context

**Recommendation**: Use clj-kondo directly via CLI for speed; integrate cljfmt for auto-formatting.

---

## 1. clj-kondo Analysis Data Structure

Running clj-kondo with analysis enabled:

```bash
clj-kondo --lint src/seon/dev/hook.clj \
  --config '{:output {:analysis {:arglists true :var-usages true} :format :edn}}'
```

Returns a map with these top-level keys:

```clojure
{:findings []           ; Lint issues (empty = clean)
 :summary {...}         ; Timing, file count, error counts
 :analysis {...}}       ; The rich analysis data
```

### Analysis Data Structure

The `:analysis` key contains:

| Key | Description | Use Case |
|-----|-------------|----------|
| `:namespace-definitions` | List of `(ns ...)` declarations | Get namespace metadata |
| `:namespace-usages` | All `require`/`use` statements | Dependency graph |
| `:var-definitions` | All `defn`/`def`/`defmacro` | List functions in file |
| `:var-usages` | All function/var references | **Call graphs!** |
| `:locals` | Local bindings (let, fn args) | Scope analysis |
| `:local-usages` | Uses of local bindings | Unused variable detection |
| `:keywords` | Keyword usages (optional) | Schema key tracking |

### Example: Namespace Usages

```clojure
{:filename "/Users/sean/src/seon/src/seon/dev/hook.clj"
 :from seon.dev.hook
 :to seon.dev.codebase
 :alias codebase
 :row 28
 :col 14}
```

### Example: Var Definition

```clojure
{:filename "/Users/sean/src/seon/src/seon/dev/hook.clj"
 :row 392
 :col 1
 :ns seon.dev.hook
 :name process-hook-event!
 :arglist-strs ["[{::keys [xtdb-node event config]}]"]
 :doc "Process a Claude Code hook event..."}
```

### Example: Var Usage (for call graphs)

```clojure
{:filename "/Users/sean/src/seon/src/seon/dev/hook.clj"
 :row 477
 :col 22
 :from seon.dev.hook
 :to seon.dev.hook
 :name stage-repair          ; Function being called
 :from-var process-hook-event! ; **Caller context!**
 :arity 2}
```

The `:from-var` field is the key to building call graphs.

---

## 2. Call Graph Extraction

### How It Works

clj-kondo tracks which function is being defined when it encounters a var usage. This is stored in `:from-var`.

To build a call graph for `process-hook-event!`:

```clojure
(->> var-usages
     (filter #(= (:from-var %) 'process-hook-event!))
     (map #(select-keys % [:to :name :row])))
```

This gives all functions called by `process-hook-event!`.

### Call Graph for `process-hook-event!` (from hook.clj)

Functions called:

| Called Function | Namespace | Line |
|-----------------|-----------|------|
| `merge-config` | seon.dev.hook | 424 |
| `get-file-path` | seon.dev.hook | 427 |
| `debug` | taoensso.timbre | 430 |
| `clojure-file?` | seon.dev.codebase | 443 |
| `stage-repair` | seon.dev.hook | 451, 477 |
| `block-response` | seon.dev.hook | 453, 479 |
| `success-response` | seon.dev.hook | 440, 446 |
| `seon-source-file?` | seon.dev.hook | 457 |
| `file->namespace` | seon.dev.codebase | 464 |
| `stage-reload` | seon.dev.hook | 482 |
| `stage-compliance` | seon.dev.hook | 487 |
| `stage-unit-tests` | seon.dev.hook | 498 |
| `stage-gen-tests` | seon.dev.hook | 522 |
| `stage-record-edit` | seon.dev.hook | 515 |
| `stage-should-review?` | seon.dev.hook | 563 |
| `stage-review` | seon.dev.hook | 564 |
| `format-unit-result` | seon.dev.verify | 504 |
| `format-gen-result` | seon.dev.verify | 528 |
| `analyze-namespace` | seon.dev.compliance | 487 |
| `format-violations` | seon.dev.compliance | 335 |

This is exactly what we need for LLM context - when an agent edits `process-hook-event!`, we can show the source of all these functions.

### Finding Callers (Reverse Call Graph)

To find what calls a specific function:

```clojure
(->> var-usages
     (filter #(= (:name %) 'stage-repair))
     (map :from-var)
     distinct)
;; => (process-hook-event!)
```

---

## 3. clojure-lsp as a Library

### Summary

clojure-lsp provides `clojure-lsp.api` namespace for programmatic use without running an LSP server.

### Dependency

```clojure
{:deps {com.github.clojure-lsp/clojure-lsp {:mvn/version "2025.11.28-12.47.43"}}}
```

### Key Functions

| Function | Purpose |
|----------|---------|
| `lsp-api/dump` | Get full project analysis (clj-kondo + dep-graph) |
| `lsp-api/diagnostics` | Get all lint errors/warnings |
| `lsp-api/clean-ns!` | Programmatically clean namespaces |

### Usage Example

```clojure
(require '[clojure-lsp.api :as lsp-api]
         '[clojure.java.io :as io])

(let [result (lsp-api/dump {:project-root (io/file ".")
                            :analysis {:type :project-only}})]
  ;; Access enriched clj-kondo analysis
  (let [kondo-analysis (get-in result [:result :analysis])]
    (:var-definitions kondo-analysis)))
```

### Pros/Cons

**Pros:**
- Provides dependency graph across whole project
- Handles classpath resolution automatically
- Integrates cljfmt formatting

**Cons:**
- Heavier dependency (pulls in entire clojure-lsp)
- First scan is slow (indexes whole project)
- Overkill for single-file analysis

**Recommendation:** For our hook use case, raw clj-kondo is faster and sufficient. Consider clojure-lsp if we need project-wide analysis later.

---

## 4. Auto-Formatting Options

### Comparison Table

| Tool | Speed | Config | Editor Support | Notes |
|------|-------|--------|----------------|-------|
| **cljfmt** | Very Fast | EDN | Excellent (LSP) | Community standard |
| **cljstyle** | Fastest | EDN | CLI-first | Stricter, v0.17 for 1.12 |
| **zprint** | Moderate | Complex | Plugins | Full re-layout |
| **Standard Clojure** | Fast | **Zero** | Growing | New 2025, no config |

### Recommendation: cljfmt

For our use case (auto-format before agent sees code), **cljfmt** is the best choice:

1. **Already integrated with clojure-lsp** - editors use it
2. **Fast native binary available** - can shell out
3. **Configurable escape hatch** - custom macro indentation
4. **Stable and well-maintained**

### Integration Strategy

```clojure
;; Option 1: Shell out to cljfmt binary (fast)
(defn format-file! [file-path]
  (sh "cljfmt" "fix" file-path))

;; Option 2: Use cljfmt library
(require '[cljfmt.core :as cljfmt])
(defn format-string [s]
  (cljfmt/reformat-string s))
```

For the hook, shell out is simplest and fastest - no JVM startup inside Babashka.

---

## 5. Recommended Architecture

```
Edit event arrives
    |
    v
+-------------------+
| Auto-format       | <- cljfmt --fix (shell, ~50ms)
| (fixes whitespace)|
+-------------------+
    |
    v
+-------------------+
| clj-kondo         | <- Single parse (~35ms)
| analysis          |
+-------------------+
    |
    +---> :namespace-definitions -> namespace metadata
    |
    +---> :var-definitions -> functions in file
    |
    +---> :var-usages -> call graph (what this file calls)
    |
    +---> :findings -> lint issues
    |
    v
+-------------------+
| Context Builder   | <- Build rich LLM context
+-------------------+
    |
    +---> For each function being edited:
    |     - Show callers (who calls it)
    |     - Show callees (what it calls)
    |     - Include source of direct dependencies
    |
    v
+-------------------+
| Compliance Check  | <- Use :var-definitions instead of ns-publics
+-------------------+
    |
    v
+-------------------+
| Tests             |
+-------------------+
    |
    v
+-------------------+
| Gemini Review     | <- Rich context includes call graph
+-------------------+
```

### Key Changes from Current Architecture

1. **Replace `seon.dev.codebase/read-ns-form`** - Use clj-kondo `:namespace-definitions`
2. **Replace `seon.dev.compliance` var walking** - Use clj-kondo `:var-definitions`
3. **Add call graph to LLM context** - Parse `:var-usages` to show related functions
4. **Add auto-format stage** - cljfmt before analysis

---

## 6. Implementation Notes

### Calling clj-kondo Programmatically

From Clojure (not Babashka):

```clojure
(require '[clj-kondo.core :as clj-kondo])

(defn analyze-file [file-path]
  (clj-kondo/run! {:lint [file-path]
                   :config {:output {:analysis {:arglists true
                                                :var-usages true}}}}))
```

From Babashka (shell out):

```clojure
(require '[babashka.process :as p]
         '[clojure.edn :as edn])

(defn analyze-file [file-path]
  (-> (p/sh "clj-kondo" "--lint" file-path
            "--config" "{:output {:analysis {:var-usages true} :format :edn}}")
      :out
      edn/read-string
      :analysis))
```

### Performance Considerations

- clj-kondo analysis: ~30-45ms per file
- cljfmt formatting: ~50ms per file
- Total per-edit overhead: ~100ms (acceptable)

### Caching

clj-kondo has built-in caching. For project-wide analysis, use:
- `.clj-kondo/.cache/` directory
- Re-analyze only changed files

---

## 7. Next Steps

1. **Create `seon.dev.analysis` namespace** with:
   - `analyze-file` - Run clj-kondo, return parsed analysis
   - `extract-call-graph` - Get caller/callee relationships
   - `format-file!` - Auto-format with cljfmt

2. **Update hook pipeline** to use new analysis instead of manual parsing

3. **Enhance Gemini review context** with call graph information

---

## Appendix: Full clj-kondo Analysis Config Options

```clojure
{:output {:analysis {:arglists true        ; Include arglists in var-definitions
                     :locals true          ; Include local bindings
                     :keywords true        ; Include keyword usages
                     :protocol-impls true  ; Include protocol implementations
                     :var-usages true      ; Include var usages (for call graphs)
                     :var-definitions {:shallow false  ; Full or shallow analysis
                                       :meta [:added :deprecated]}
                     :context true}        ; Include surrounding context
          :format :edn}}                   ; Output format
```
