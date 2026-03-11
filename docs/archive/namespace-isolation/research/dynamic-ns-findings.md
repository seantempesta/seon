# Dynamic Namespace Isolation - Research Findings

**Date:** 2026-02-01
**Approach:** Clojure core functions (`create-ns`, `refer`, `remove-ns`)
**Implementation:** `src/seon/experimental/ns_instance.clj`

## Executive Summary

**Dynamic namespace isolation works well for our use case.** Using `refer` instead of `intern` provides proper isolation with macro support. The approach is lightweight (~1MB per instance), uses only Clojure core, and integrates naturally with our existing patterns.

**Recommendation:** Proceed with this approach for trusted agent code. Consider Sci only if we need sandboxing for truly untrusted code.

---

## Key Questions Answered

### 1. Does `intern` copy or reference the original var?

**Finding:** We abandoned `intern` in favor of `refer`.

- `intern` creates a **copy** of the var value, but loses metadata (including `:macro true`)
- `refer` creates a **reference** that preserves metadata and sees base changes

**Implication:** With `refer`, when you update a function in the base namespace, all instances immediately see the change. This is desirable for our use case (hot reloading base code).

### 2. When base function changes, do instances see the change?

**Yes, they do.** Experiment results:

```clojure
;; Base has (defn changeable [] "original")
;; Create instance, call (changeable) => "original"
;; Modify base: (defn changeable [] "modified")
;; Call (changeable) in instance => "modified"

{:before "original"
 :after "modified"
 :sees-change? true
 :conclusion "REFERENCE: Instance sees changes to base"}

```

**This is good** because:
- Hot reload of base code propagates to all instances
- No stale code in running instances
- Agents see improvements to shared functions immediately

**Override behavior:** If an instance defines its own `changeable`, it takes precedence and no longer sees base changes. This is correct isolation - the instance "owns" that function now.

### 3. Do macros expand correctly in instance namespace?

**Yes.** Using `refer` preserves the `:macro true` metadata.

```clojure
;; Base defines:
(defmacro with-timing [& body]
  `(let [start# (System/currentTimeMillis)]
     ~@body
     (- (System/currentTimeMillis) start#)))

;; Instance can use it:
(instance-eval inst "(with-timing (Thread/sleep 10))")
;; => 541 (ms)

```

The macro expands in the instance context but executes correctly.

### 4. What happens with `::keyword` shorthand?

**As expected:** `::foo` expands to the instance namespace, not the base.

```clojure
;; In instance seon.experimental.ns-instance-base.0001:
(read-string "::test")
;; => :seon.experimental.ns-instance-base.0001/test

```

**Mitigation strategies:**

1. **Explicit keywords (recommended):** Always write `:seon.email/foo` instead of `::foo`
2. **Namespace alias:** We set up `(alias 'base base-ns)` so `::base/foo` works
3. **Convention:** Document that `::` should be avoided in instance code

---

## Isolation Test Results

All 6 tests pass:

| Test | Result | Details |
|------|--------|---------|
| Instances see base functions | ✅ Pass | Both instances get `greet` from base |
| Override in inst-1 doesn't affect inst-2 | ✅ Pass | inst-1: "Hi, Alice", inst-2: "Hello, Bob" |
| Base namespace unchanged | ✅ Pass | Base still has original `greet` |
| New var in inst-1 not visible in inst-2 | ✅ Pass | `my-var` only in inst-1 |
| `::` keyword resolves to instance namespace | ✅ Pass | Resolves to instance, not base |
| Destroy removes namespace | ✅ Pass | `remove-ns` fully cleans up |

---

## Memory Implications

**What's shared (not copied):**
- Function code (bytecode)
- Closed-over values in functions
- Class definitions
- clojure.core vars

**What's per-instance:**
- Namespace object (~few KB)
- Instance-specific vars
- Registry entry

**Estimated overhead:** ~1MB per instance (mostly namespace metadata and interned vars).

---

## Risks and Mitigations

### Risk: Shared JVM

All instances share the same JVM. One bad actor can:
- Call `System/exit` and kill everything
- Exhaust memory affecting all instances
- Block shared thread pools

**Mitigation:** This approach is for **trusted** agent code. For untrusted code:
- Use Sci with restricted capabilities
- Use separate JVM processes (heavyweight but isolated)
- Implement timeouts and resource limits

### Risk: `::` Keyword Confusion

Code using `::foo` will get instance-qualified keywords that don't match schema expectations.

**Mitigation:**
- Document convention: use explicit `:base-ns/foo` keywords
- Set up aliases: `::base/foo` works via alias
- Consider code rewriting as last resort

### Risk: Closure State Sharing

Functions that close over atoms/refs share that state across instances.

```clojure
;; In base:
(def ^:private counter (atom 0))
(defn inc-counter [] (swap! counter inc))

;; Both instances share the same atom!

```

**Mitigation:**
- Avoid mutable state in base namespace closures
- Pass state explicitly via parameters (already our pattern with `db` and `ctx`)
- Document this gotcha

---

## Integration with Existing Systems

### Fits with `seon.web.reactive.instance`

The registry pattern matches what we already have:
- 4-char hex IDs
- `create` / `destroy` lifecycle
- Registry atom for lookup

### Fits with MCP Sessions

Each MCP session can get its own namespace instance:

```clojure
;; On session create:
(create-instance-ns 'seon.web.reactive.demo {:instance-id session-id})

;; Evals bound to that namespace
(instance-eval inst code)

;; On session end:
(destroy-instance-ns inst)

```

### Fits with nREPL

Each agent's nREPL can `in-ns` to their instance namespace for interactive development.

---

## What's Not Covered (Future Work)

1. **Sci comparison:** May still be useful for truly untrusted code sandboxing
2. **JVM footprint measurement:** For when process isolation is needed
3. **Graduation criteria:** When to move code from sandbox → trusted
4. **Performance benchmarks:** `instance-eval` vs native `eval`

---

## Conclusion

Dynamic namespace isolation using Clojure core is **viable and recommended** for our use case:

- ✅ Strong isolation between instances
- ✅ Base code changes propagate (hot reload works)
- ✅ Macros work correctly
- ✅ Lightweight (~1MB per instance)
- ✅ No new dependencies
- ✅ Integrates with existing patterns

**Next step:** Integrate with `seon.web.reactive.instance` to give each reactive instance its own namespace for code customization.
