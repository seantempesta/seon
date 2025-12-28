# Implementation Notes: Unified Dev Hook

**Last Updated:** 2024-12-28

---

## Overview

Unified development feedback hook combining:
1. Syntax repair (from clojure-mcp-light)
2. Unit tests (from auto-test-hook)
3. Generative tests (new - Malli)
4. AI review (new - Gemini)

All state stored in XTDB.

---

## Key Learnings

*To be filled during implementation*

---

## Gotchas

### Malli Function Schemas Registration

Functions must be registered with `m/=>` for the hook to see them:

```clojure
;; This works:
(m/=> my-fn [:=> [:cat :int] :int])
(defn my-fn [x] (* x x))

;; This does NOT register (schema defined but not linked):
(def MySchema [:=> [:cat :int] :int])
(defn my-fn [x] (* x x))
```

### REPL Must Be Running

The hook queries `(m/function-schemas)` via nREPL. If REPL is down:
- Skip generative tests
- Fall back to unit tests only
- Log warning

---

## Code Patterns

*To be filled during implementation*

---

## Testing Notes

### REPL Commands for Manual Testing

```clojure
;; Check what schemas are registered
(m/function-schemas)

;; Get schemas for a specific namespace
(get-in (m/function-schemas) [:clj 'seon.foo])

;; Run generative check on a function
(require '[malli.generator :as mg])
(mg/check [:=> [:cat :int] :int] (fn [x] (* x x)) {:num-tests 10})

;; Extract schema refs
(require '[seon.dev.feedback :as fb])
(fb/extract-schema-refs [:=> [:cat :user/id] :order/result])
```

---

## References

- [Malli Function Schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md)
- [clojure-mcp-light hook.clj](~/.gitlibs/.../clojure-mcp-light/src/clojure_mcp_light/hook.clj)
- Research: `docs/prds/unified-dev-hook/research/`
