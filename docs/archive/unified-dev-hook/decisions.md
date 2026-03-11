# Architectural Decisions: Unified Dev Hook

**Last Updated:** 2024-12-28

**Related Research:**
- `research/parsing-approaches.md` - Detailed comparison (Decision 1)
- `research/malli-resolution.md` - Schema resolution details (Decision 3)
- `research/recommendations.md` - Implementation plan based on these decisions

---

## Decision 1: REPL Introspection Over Static Parsing

**Date:** 2024-12-28
**Status:** Accepted

### Context

Need to extract function definitions and Malli schemas from code on edit. Two approaches:
1. Parse `.clj` files statically (rewrite-clj, tools.reader)
2. Query the running REPL after namespace reload

### Decision

Use **REPL introspection**. Query `(m/function-schemas)` after reloading the namespace.

### Rationale

- Malli already maintains a registry of function schemas
- Schemas are validated by Malli on registration
- No file I/O or parsing overhead
- Works with macros and complex forms that static parsing struggles with
- Always synchronized with actual loaded code

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| rewrite-clj | Source positions, works offline | Complex, needs sync | Overkill for our use case |
| tools.reader | Lighter weight | No positions, still static | REPL approach simpler |
| Regex | Fast | Fragile, can't handle edge cases | Not production-ready |

### Consequences

**Benefits:**
- Simpler implementation
- Always accurate (uses Malli's own registry)
- Fast (no file I/O)

**Costs:**
- Requires running REPL
- Can't analyze files that don't compile

**Mitigations:**
- Hook runs after syntax repair, so code always compiles
- REPL is always running in our workflow

---

## Decision 2: All State in XTDB

**Date:** 2024-12-28
**Status:** Accepted

### Context

Need to track:
- Known functions (for new-fn detection)
- Function sources (for change detection)
- Error history (for pattern detection)
- Schema definitions (for context building)

Options: files in `.claude/` or XTDB entities.

### Decision

Store **all state in XTDB**. No `.edn` files for persistent state.

### Rationale

- Time travel: "What was the code when this error happened?"
- Relationship queries: "What functions use this schema?"
- Single source of truth
- Already running in the system
- Consistent with project philosophy

### Entity Types

```clojure
:function    ; fn definitions with schemas
:schema      ; resolved Malli schemas
:edit-event  ; edit history
:error       ; failure history

```

### Consequences

**Benefits:**
- Temporal debugging
- Powerful queries
- No file sync issues

**Costs:**
- XTDB must be running for hook to work
- Slightly more complex than file I/O

---

## Decision 3: Malli Registry for Schema Resolution

**Date:** 2024-12-28
**Status:** Accepted

### Context

Schemas can reference other schemas:

```clojure
[:=> [:cat :user/id :order/cart] :order/result]

```

Need to recursively resolve these to build full context for Gemini.

### Decision

Use Malli's built-in `m/walk` to traverse schemas and collect refs, then resolve each from the registry.

### Rationale

- Malli handles all edge cases
- Works with custom registries
- No need to reimplement schema parsing

### Implementation

```clojure
(defn extract-schema-refs [schema]
  (let [refs (atom #{})]
    (m/walk
      (m/schema schema)
      (fn [s _ children _]
        (when (and (keyword? (m/type s)) (namespace (m/type s)))
          (swap! refs conj (m/type s)))
        (m/-set-children s children)))
    @refs))

```

---

## Decision 4: Source Hash for Change Detection

**Date:** 2024-12-28
**Status:** Accepted

### Context

Need to detect when a function's logic changed (vs just being re-evaluated).

### Decision

Hash function source and store in XTDB. Compare on each edit.

### Rationale

- Fast comparison (no deep equality)
- Detects logic changes
- Simple to implement

### Alternative Considered

Using var metadata (`:line`, `:file`) - but this doesn't detect content changes within the same location.

---

## Decision 5: Configurable Gemini Triggers

**Date:** 2024-12-28
**Status:** Accepted

### Context

Gemini API calls have cost. Need to be strategic about when to invoke.

### Decision

Invoke Gemini only when it adds value:
- New function with schema (first-time review)
- Generative test failure (explain counter-example)

Do NOT invoke on:
- Every edit
- Unit test failures (usually obvious)
- Passing generative tests

### Configuration

```clojure
{:gemini {:on-new-function true
          :on-gen-fail true
          :on-syntax-fail false
          :on-unit-fail false}}

```

---

## Open Decisions

### How many generative test iterations?

**Options:**
- 10 (fast, may miss edge cases)
- 50 (balance)
- 100 (thorough, slower)

**Current:** 10 for hook, configurable for manual deep check.

### Should AI review be blocking?

**Options:**
- Blocking: Must address feedback before continuing
- Informational: Show feedback but don't block

**Current:** Informational for new functions, blocking for gen-test failures.
