# Phase 10: Linting Integration

## Overview

Integrate static analysis (clj-kondo) and style/metrics checking (Splint) into the dev hook pipeline.

## Goals

1. **Catch bugs early** - Run clj-kondo on changed files to detect errors before tests
2. **Enforce complexity limits** - Block on functions that are too long
3. **Suggest improvements** - Surface style suggestions without blocking
4. **Fast feedback** - Incremental linting only on changed files

## Non-Goals

- Full project lint on every edit (too slow)
- Auto-fixing in the hook (too risky)
- Replacing editor integration (complements, not replaces)

## Current State

Linting is set up but not integrated into the hook:

- clj-kondo v2025.04.07 installed system-wide
- Splint 1.22.0 configured in deps.edn
- Config files: `.clj-kondo/config.edn`, `.splint.edn`
- Manual script: `bin/lint`
- Reference code: `reference-code/clj-kondo/`, `reference-code/splint/`

## Implementation Plan

### Phase 10a: Add Lint Stage to Hook

Create `seon.dev.lint` namespace:

```clojure
(ns seon.dev.lint
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def Schema
  [:map {:closed true}
   [:seon.dev.lint/file-path :string]
   [:seon.dev.lint/success :boolean]
   [:seon.dev.lint/errors [:vector :string]]
   [:seon.dev.lint/warnings [:vector :string]]
   [:seon.dev.lint/metrics {:optional true}
    [:map
     [:fn-length-violations [:vector :string]]
     [:param-count-violations [:vector :string]]]]])

```

Key functions:
- `lint-file` - Run clj-kondo on a single file
- `check-metrics` - Run Splint metrics on a single file
- `format-lint-result` - Format for user feedback

### Phase 10b: Hook Pipeline Integration

Add to `seon.dev.hook/process-hook-event!`:

```
Current order:
1. repair (PreToolUse)
2. reload
3. compliance
4. unit-tests
5. gen-tests
6. review

New order:
1. repair (PreToolUse)
2. reload
3. LINT (new - fast, before tests)
4. compliance
5. unit-tests
6. gen-tests
7. review

```

### Phase 10c: Configuration

Add to `default-config`:

```clojure
{:lint {:enabled true
        :clj-kondo {:enabled true
                    :block-on-error true}
        :splint {:enabled true
                 :metrics {:enabled true
                           :fn-length-block 50
                           :fn-length-warn 30
                           :param-count-block 7
                           :param-count-warn 5}
                 :style {:enabled false}}}}  ; style suggestions off by default

```

User can override in `.claude/seon-hook.edn`.

### Phase 10d: Severity Levels

| Category | Default | Configurable |
|----------|---------|--------------|
| clj-kondo errors | BLOCK | Yes |
| clj-kondo warnings | WARN | Yes |
| metrics/fn-length > 50 | BLOCK | Yes |
| metrics/fn-length > 30 | WARN | Yes |
| metrics/param-count > 7 | BLOCK | Yes |
| metrics/param-count > 5 | WARN | Yes |
| Splint style | OFF | Yes |

### Phase 10e: Performance Optimization

1. **Incremental**: Only lint the specific file being edited
2. **Caching**: clj-kondo caches analysis, reuse it
3. **Parallel**: Can run clj-kondo and Splint in parallel
4. **Short-circuit**: If clj-kondo errors, skip Splint

Expected overhead: ~50-100ms per file.

## Technical Decisions

### Why Both Tools?

- clj-kondo catches real bugs (unresolved symbols, arity errors)
- Splint has metrics (fn-length) that clj-kondo lacks
- Together they provide comprehensive coverage

### Why Not Eastwood?

Eastwood requires evaluating code and is too slow for hook integration. It's better suited for CI pipelines.

### Shell Out vs Library?

- clj-kondo: Shell out to system binary (faster, no JVM startup)
- Splint: Shell out to Babashka (faster than JVM Clojure)

Both tools have stable CLI interfaces, so shelling out is reliable.

### Exit Code Handling

- clj-kondo: 0 = clean, 2 = warnings, 3 = errors
- Splint: 0 = clean, 1 = violations found

Map these to our BLOCK/WARN/OK model.

## Testing Plan

1. Unit tests for `lint-file` and `check-metrics`
2. Integration test with a file that has known issues
3. Verify hook pipeline runs lint before tests
4. Verify blocking works for severe violations
5. Verify warnings are surfaced without blocking

## Success Criteria

- [ ] Lint errors block edits with clear message
- [ ] Lint warnings appear in feedback without blocking
- [ ] Function length > 50 blocks with actionable message
- [ ] Lint adds < 100ms overhead per file
- [ ] Configuration is documented and works

## Open Questions

1. Should we cache Splint results like we cache reviews?
2. Should lint results be stored in XTDB for tracking?
3. Should there be a "lint-only" mode for manual runs?

## Dependencies

- Phase 8 (compliance) - Similar pattern to follow
- bin/seon-hook - Hook entry point
- seon.dev.hook - Pipeline orchestration

## Timeline

- 10a: 2 hours - Create lint namespace
- 10b: 2 hours - Hook integration
- 10c: 1 hour - Configuration
- 10d: 1 hour - Severity levels
- 10e: 2 hours - Performance optimization

Total: ~8 hours
