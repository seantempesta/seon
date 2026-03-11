---
type: prd
status: completed
tags: [prd, archive]
---

# PRD: Phase 11 - Unified Analysis Pipeline

**Status:** Phase 11a COMPLETE
**Depends On:** Phase 9 (complete), Phase 10 (linting setup complete)
**Branch:** `feature/unified-dev-hook`

---

## Implementation Summary

### Phase 11a: clj-kondo Library Integration (COMPLETE)

**Completed 2026-01-04**

Successfully migrated from CLI-based clj-kondo to library-based in `seon.dev.analysis`:

1. **Removed CLI shelling**: Replaced `run-clj-kondo-cli` (which used `clojure.java.shell/sh`) with `run-clj-kondo-lib` (which uses `clj-kondo.core/run!`)

2. **Removed CLI output parsing**: No longer need to parse EDN output from stdout - the library returns rich Clojure data structures directly

3. **Updated `analyze-file`**: Now uses the library call, with proper exception handling instead of exit code checking

4. **Namespace extraction fix**: Changed from using `:namespace-usages` (which was fragile) to using `:namespace-definitions` which provides the actual `ns` declaration

5. **Performance**: Library-based analysis runs in ~40ms for large files (hook.clj with 500+ lines)
   - This is comparable to CLI performance but avoids subprocess overhead
   - JVM warmup means subsequent calls are faster

6. **Tests updated**: Fixed test expectations that referenced the old `run-clj-kondo` function name

**Files Changed:**
- `src/seon/dev/analysis.clj` - Main implementation
- `test/seon/dev/analysis_test.clj` - Updated test expectations

**Dependencies:**
- `clj-kondo/clj-kondo {:mvn/version "2025.12.23"}` added to deps.edn (done in prior work)

### Gotchas Discovered

1. **Server restart required for new deps**: After adding clj-kondo dependency, the running server needs a restart to pick it up

2. **Partial refactors break the hook**: The dev hook itself uses `seon.dev.analysis` for compliance checks. If you rename a function but don't update its call site, the namespace won't compile and the hook fails with a cryptic message about the old function not existing

3. **clj-kondo library return structure**: The library returns `{:findings [...] :analysis {...} :summary {...} :config {...}}` - different from CLI EDN output which was wrapped differently

4. **Namespace extraction**: Use `:namespace-definitions` not `:namespace-usages` - the latter gives you namespaces that are *used*, not the namespace being defined

---

## Vision

Replace scattered CLI tools and manual parsing with a single **programmatic analysis pipeline** that runs entirely in-process. No shelling out, no subprocess overhead, no parsing CLI output.

### Current State (Scattered)

```
Manual read → parse ns form
clj-kondo CLI → parse EDN output
Splint CLI → parse output
cljfmt CLI → shell out
clojure.test → separate process

```

### Target State (Unified)

```
Single JVM process:
  clj-kondo.core/run! → analysis data
  cljfmt.core/reformat-string → formatted code
  clojure.test programmatic → test results
  seon.dev.compliance → uses analysis data

```

---

## Key Constraint: Everything Runs as a Library

**No CLI shelling.** All tools must be called programmatically:

| Tool | Library Approach |
|------|------------------|
| clj-kondo | `clj-kondo.core/run!` |
| cljfmt | `cljfmt.core/reformat-string` |
| Splint | `noahtheduke.splint.api/run` (if available) or skip |
| clojure.test | `clojure.test/run-tests` with custom reporter |

### Why Programmatic?

1. **No subprocess overhead** - saves 50-100ms per tool
2. **Rich data structures** - no parsing CLI output
3. **Better error handling** - exceptions, not exit codes
4. **Composable** - data flows between stages naturally
5. **Testable** - can unit test each stage

---

## Research (Completed)

### 1. clj-kondo as Library

**Status:** COMPLETE - Implemented

```clojure
(require '[clj-kondo.core :as clj-kondo])

(clj-kondo/run! {:lint ["src/seon/dev/hook.clj"]
                 :config {:output {:analysis {:var-usages true
                                              :var-definitions true
                                              :arglists true}}}})
;; Returns: {:findings [...] :analysis {...} :summary {...} :config {...}}

```

**Answers:**
- Yes, works in our JVM without conflicts
- Library returns data directly - no stdout to capture
- Performance: ~40ms per file, comparable to CLI but no subprocess overhead

### 2. cljfmt as Library

**Status:** Needs research

```clojure
(require '[cljfmt.core :as cljfmt])

(cljfmt/reformat-string "(defn foo[x](+ x 1))")
;; => "(defn foo [x] (+ x 1))"

```

**Questions:**
- Configuration loading (indentation rules)?
- In-place file formatting?
- Performance characteristics?

### 3. Splint as Library

**Status:** Needs research

Splint may have an API namespace. Check `reference-code/splint/` for:
- `noahtheduke.splint.api` or similar
- Programmatic invocation without CLI

**If no API:** Skip Splint, clj-kondo covers most lint cases.

### 4. clojure.test Programmatic

**Status:** We already do this in `seon.dev.verify`

Current approach captures output via binding. Consider:
- Custom reporter for structured results
- Avoid stdout capture if possible
- Parallel test execution?

---

## Architecture

```
+------------------+
| Edit Event       |
+------------------+
        |
        v
+------------------+
| Format Stage     |  cljfmt.core/reformat-string
| (in-memory)      |  Write back if changed
+------------------+
        |
        v
+------------------+
| Analysis Stage   |  clj-kondo.core/run!
| (single parse)   |  Returns rich data structure
+------------------+
        |
        +---> namespace, var-definitions
        +---> var-usages (call graph)
        +---> findings (lint issues)
        |
        v
+------------------+
| Compliance Stage |  Uses var-definitions from analysis
| (no re-parsing)  |  Check schemas, map-in, docstrings
+------------------+
        |
        v
+------------------+
| Test Stage       |  clojure.test with custom reporter
| (programmatic)   |  Structured results
+------------------+
        |
        v
+------------------+
| Context Builder  |  Build LLM context from call graph
+------------------+
        |
        v
+------------------+
| Gemini Review    |  Rich context with related functions
+------------------+

```

---

## Implementation Phases

### Phase 11a: clj-kondo Library Integration

1. Add clj-kondo as dependency (not just CLI tool)
2. Create `seon.dev.analysis/analyze-file*` using library
3. Benchmark: library vs CLI performance
4. Migrate from shell-based to library-based

### Phase 11b: cljfmt Library Integration

1. Add cljfmt as dependency
2. Create `seon.dev.analysis/format-string` and `format-file!*`
3. Load project cljfmt config
4. Integrate into hook pipeline

### Phase 11c: Unified Compliance

1. Update `seon.dev.compliance` to accept analysis data
2. Remove redundant var-walking (use clj-kondo var-definitions)
3. Single analysis pass feeds both lint and compliance

### Phase 11d: Enhanced Test Integration

1. Review current `seon.dev.verify` approach
2. Consider custom test reporter for cleaner data
3. Add test timing to dense feedback

### Phase 11e: Call Graph Context for LLM

1. For edited functions, extract callees
2. Include source of called functions in review context
3. Configurable depth (direct calls only vs transitive)

---

## Dependencies to Add

```clojure
;; deps.edn
{:deps {clj-kondo/clj-kondo {:mvn/version "2024.09.27"}
        cljfmt/cljfmt {:mvn/version "0.12.0"}}}

```

---

## Success Criteria

- [x] No subprocess calls for clj-kondo (Phase 11a) - cljfmt still shells out (Phase 11b)
- [x] Analysis ~40ms per file (well under 150ms target)
- [x] Call graph extraction works for any function
- [x] All 293 tests pass
- [ ] Hook feedback includes timing breakdown (future work)

---

## Open Questions

1. **Splint integration** - Worth the complexity or just use clj-kondo?
2. **Incremental analysis** - Can clj-kondo cache between edits?
3. **Parallel execution** - Run format and analysis concurrently?

---

## References

- [clj-kondo API docs](https://github.com/clj-kondo/clj-kondo/blob/master/doc/api.md)
- [cljfmt library usage](https://github.com/weavejester/cljfmt#library)
- Research: `docs/prds/unified-dev-hook/research/clojure-lsp-analysis.md`
- Current analysis: `src/seon/dev/analysis.clj`
- Reference code: `reference-code/clj-kondo/`, `reference-code/splint/`
