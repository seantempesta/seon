---
type: decision
status: draft
tags: [prd, decision]
---
# Architectural Decisions: Graph Cleanup

---

## Decision 1: Hybrid Datalog+Clojure Resolution (Not Pure Datalog)

**Date:** 2026-02-27
**Status:** Confirmed via REPL testing

### Context

The original plan (from another tab) proposed pure-Datalog queries with `[(clojure.set/subset? ...)]` predicates to do set matching in the database.

### Decision

Keep the existing hybrid approach: Datalog finds candidates, Clojure does set filtering and ranking.

### Rationale

- Cardinality-many attributes bind ONE value at a time in Datalog — you can't bind the whole set
- `clojure.set/subset?` as a Datalog predicate would operate on individual values, not collections
- The existing codebase already does this correctly — we're just changing what Datalog queries (ref joins to specs instead of pre-computed attrs)

---

## Decision 2: Compute Required Keys at Query Time (Not Stored)

**Date:** 2026-02-27
**Status:** Accepted

### Context

Required keys = contains-keys − optional-keys. Three options: compute at query time, store redundantly, or store only required-keys.

### Decision

Compute at query time in the `functions-with-output-key` helper.

### Rationale

- One source of truth (the spec entity's contains-keys and optional-keys)
- Graph rebuilds from source on startup, so redundancy isn't a migration concern
- Set difference on small keyword sets is nanoseconds
- The helper computes it once per query, not per resolution call

---

## Decision 3: Ref Joins Over Pre-Computed Attrs

**Date:** 2026-02-27
**Status:** Confirmed via REPL testing (R1, R2, R4 all pass)

### Context

Current code pre-computes `:seon.fn/render-input-keys` at scan time. The alternative is joining through spec refs at query time.

### Decision

Remove pre-computed attrs. Use ref joins: `fn → output-spec → contains-keys`.

### Rationale

- **Proven:** R1 test shows the ref join query returns correct results
- **Efficient:** R4 test shows one `d/pull` gets fn + nested spec data
- **General:** The same query works for any output key (`:seon.render/html`, `:seon.render/documentation`, `:seon.health/status`)
- **No data loss:** Removing attrs from schema just means they stop being transacted. Graph rebuilds from source.

### Consequences

**Benefits:**

- Graph stores only facts — no render-specific pollution
- ALL functions with specs are linked, not just renderers
- New discoverable patterns (docs, health) need zero schema changes

**Costs:**

- Query is slightly more expensive (ref join + pull vs direct attr read)
- Tests need rewriting to transact spec entities instead of pre-computed attrs
