# PRD: Phase 11 - Unified Analysis Pipeline

**Status:** Research Complete, Ready for Implementation
**Depends On:** Phase 9 (complete), Phase 10 (linting setup complete)
**Branch:** `feature/unified-dev-hook`

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

## Research Needed

### 1. clj-kondo as Library

**Status:** Partially researched

```clojure
(require '[clj-kondo.core :as clj-kondo])

(clj-kondo/run! {:lint ["src/seon/dev/hook.clj"]
                 :config {:output {:analysis {:var-usages true
                                              :arglists true}}}})
```

**Questions:**
- Does it work in our JVM without conflicts?
- How to get analysis without writing to stdout?
- Performance vs CLI?

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

- [ ] No subprocess calls in hot path (hook execution)
- [ ] Analysis + format + compliance < 150ms per file
- [ ] Call graph extraction works for any function
- [ ] All 290+ tests pass
- [ ] Hook feedback includes timing breakdown

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
