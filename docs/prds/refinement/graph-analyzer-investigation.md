# Graph Analyzer Investigation

**Date:** 2026-02-22
**Status:** Root cause identified, workaround applied

## Summary

The render pipeline was not resolving functions because the graph database contained stale data from startup. After manual re-ingestion, the pipeline works correctly.

## Initial Symptoms

- `find-renderer` always returned `nil`
- `has-renderer?` returned `false` for valid workout data
- Only 1 render function in database (expected 3)

## Investigation Process

### 1. Verified Analyzer Works

```clojure
(analyzer/analyze-project! {})
;; => Success, 1146 functions, 102 namespaces, 15232 var-usages
```

The analyzer correctly parses the codebase using clj-kondo.

### 2. Verified Scanner Works

```clojure
(scanner/scan-directory {:seon.graph.scanner/dir-path "src/"})
;; => 717 specs, 3 with :seon.render/* keys
```

The scanner correctly finds specs including render-related specs.

### 3. Verified Link Functions Works

```clojure
(scanner/link-fns-to-specs fns specs)
;; => 3 render functions with :seon.fn/render-input-keys
```

The linking correctly identifies functions with matching `-request`/`-response` specs.

### 4. Found Root Cause

Checking the database showed only 1 render function:

```clojure
(d/q '[:find ?e :where [?e :seon.fn/render-input-keys]] @conn)
;; => [[1324 "seon.render.example/position-render"]]
```

The workout render functions were missing because:

1. **Graph is only populated at startup** (`:seon.graph/scanner` component)
2. **Server was running for ~2 hours**
3. **workout.clj was modified during that time**
4. **No incremental update mechanism triggered**

### 5. Resolution

Manual re-ingestion fixed the issue:

```clojure
;; Specs first (for lookup ref resolution)
(db/transact! c specs)

;; Then functions (with linked render-input-keys)
(db/transact! c linked-fns)

;; Result: 3 render functions now in database
```

After re-ingestion:

```clojure
(render/has-renderer? {:seon.health.workout/exercise "Squat" ...} :ai)
;; => true

(render/try-render {:seon.health.workout/exercise "Squat" ...} :ai)
;; => "Squat — 5x5 @ 100kg"
```

## Issues Found

### Issue 1: No Incremental Graph Updates

The scanner only runs at startup. When code is modified via the dev hook, the graph is not updated. This means:

- New render functions aren't discovered
- Modified specs aren't reflected
- The graph becomes stale over time

**Recommendation:** Wire the dev hook to call `ingest-incremental!` when source files change.

### Issue 2: Datalevin Errors During Bulk Re-ingestion

Full `ingest-analysis!` failed with:

```
Value out of range for long: 1.8385472029033133E20
```

This appears to be corruption or a bug in the call graph processing. The error occurs during bulk ingestion but NOT when ingesting specs and functions separately.

**Workaround:** Separate ingestion of specs, then functions, works correctly.

### Issue 3: Lookup Ref Resolution Order

The `ingest-incremental!` function can fail if:

1. A function references a spec via lookup ref `[:seon.spec/key :foo]`
2. The spec `:foo` doesn't exist in the database

Error: "Nothing found for entity id [:seon.spec/key ...]"

**Root Cause:** When scanning a subset of files (e.g., just `src/seon/health/`), functions may reference specs from unscanned files.

**Solution:** Always ingest specs BEFORE functions (already implemented in `ingest-analysis!`), and ensure all referenced specs are included.

## Architecture Notes

### Current Flow (Startup Only)

```
system.clj:init-key :seon.graph/scanner
├── analyzer/analyze-project!
├── analyzer/extract-entities
├── scanner/scan-directory
├── scanner/link-fns-to-specs  ← Adds render-input-keys
└── ingest/ingest-analysis!    ← Writes to Datalevin
```

### Missing: Incremental Flow

```
dev hook (file changed)
├── analyzer/analyze-form
├── scanner/scan-file
├── scanner/link-fns-to-specs
└── ingest/ingest-incremental!  ← NOT currently called
```

## Verification

After manual re-ingestion:

| Metric | Before | After |
|--------|--------|-------|
| Functions with render-input-keys | 1 | 3 |
| has-renderer? for workout data | false | true |
| try-render for workout data | nil | "Squat — 5x5 @ 100kg" |

## Next Steps

1. **Wire dev hook to graph updates** - When a `.clj` file is saved, trigger incremental ingestion
2. **Investigate bulk ingestion error** - The "Value out of range for long" error needs root cause analysis
3. **Add graph freshness check** - On startup, compare file mtimes with graph timestamps
