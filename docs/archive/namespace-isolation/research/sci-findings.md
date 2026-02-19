# Sci Research Findings

## Executive Summary

Sci (Small Clojure Interpreter) provides sandboxed evaluation of Clojure code. After exploration, we determined that **dynamic namespace isolation (Round 1) is the right approach for trusted agent code**, while Sci would be appropriate for untrusted user-provided code sandboxing.

## Key Findings

### 1. Sci Context Isolation

Sci provides isolation through `sci/init` and `sci/fork`:

```clojure
;; Create isolated context
(def ctx (sci/init {:namespaces {'user {'x 1}}}))

;; Fork creates a shallow copy - atoms are SHARED
(def ctx2 (sci/fork ctx))
```

**Important**: `fork` creates a shallow copy. Atoms and other mutable state are shared between parent and forked contexts. For true isolation, create fresh contexts with `init`.

### 2. JVM vs Browser

Sci is written in `.cljc` with reader conditionals for JVM vs ClojureScript:

```clojure
#?(:clj  (require '[sci.core :as sci])
   :cljs (require '[sci.core :as sci]))
```

**Seon already uses Scittle** (Sci for browsers) in `src/seon/web/browser.clj` for client-side Clojure evaluation. This was discovered during the research.

### 3. Performance

Sci has 10-100x overhead compared to native Clojure evaluation:
- Acceptable for scripting, configuration, and user-defined rules
- Not suitable for hot paths or compute-intensive code

### 4. Built-in Sandboxing

Sci provides security controls:
- **Namespace allow-lists**: Only expose specific vars
- **No Java interop by default**: Must explicitly enable
- **No file I/O**: Cannot read/write files unless you expose those functions
- **Timeout support**: Can limit evaluation time

## Recommendation

| Use Case | Approach |
|----------|----------|
| Agent code (trusted) | Dynamic namespace isolation (Round 1) |
| User-provided code (untrusted) | Sci sandboxing |
| Browser-side evaluation | Scittle (already in use) |

### Why Not Sci for Agents?

1. **Performance**: 10-100x overhead is significant for agents making many REPL evaluations
2. **Complexity**: Agents need full Clojure power (Java interop, file I/O, etc.)
3. **Trust model**: Agents are trusted code running in isolated namespaces

### When to Use Sci

- User-defined trading rules or filters
- Configuration expressions
- Template evaluation
- Any code from untrusted sources

## Implementation Status

- **Round 1 (Dynamic NS)**: Complete, 15 tests passing
- **Scittle (Browser)**: Already integrated in `src/seon/web/browser.clj`
- **Sci for untrusted code**: Not needed yet, defer until user scripting feature

## Files Referenced

- `src/seon/experimental/sci_exploration.clj` - Sci exploration code
- `src/seon/web/browser.clj` - Scittle integration
- `reference-code/sci/` - Sci source for reference

## Related Documents

- `dynamic-ns-findings.md` - Round 1 implementation details
- `cpu-spike-investigation.md` - Root cause of XTDB overload
