# Architectural Decisions: Polymarket Analysis

**Last Updated:** 2025-12-27

---

## Decision 1: EDN Files for Research Phase

**Date:** 2025-12-27
**Status:** Accepted

### Context

Need to store downloaded Polymarket data. Options: XTDB (existing), new XTDB instance, or simple files.

### Decision

Use EDN files in `data/polymarket/` for the research phase.

### Rationale

- **Simplicity:** No database setup, just `slurp`/`spit`
- **Inspectable:** Can read files directly, share easily
- **Fast iteration:** No schema migrations during exploration
- **Reversible:** Easy to migrate to XTDB later when patterns understood

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| Existing XTDB | Temporal queries | Schema changes affect trading data | Risk to existing data |
| New XTDB instance | Clean isolation | More setup, overkill for research | Premature optimization |

### Consequences

**Benefits:**

- Faster iteration during research
- Easy to share data files

**Costs:**

- No temporal queries (acceptable for initial analysis)
- Manual file management

---

## Decision 2: Follow thetadata.clj HTTP Pattern

**Date:** 2025-12-27
**Status:** Accepted

### Context

Need HTTP client for Polymarket Data API. Could use various approaches.

### Decision

Follow existing `seon.trading.thetadata` pattern using hato + cheshire.

### Rationale

- **Consistency:** Same patterns throughout codebase
- **No new deps:** hato and cheshire already in project
- **Proven:** Pattern works well for ThetaData

---

## Decision 3: Staged Implementation with Git Checkpoints

**Date:** 2025-12-27
**Status:** Accepted

### Context

Feature has multiple components. Could implement all at once or incrementally.

### Decision

7 stages, each with tests, REPL verification, and git commit.

### Rationale

- **Verifiable progress:** Each stage can be tested independently
- **Rollback safety:** Can revert to any stage
- **Agent-friendly:** Clear boundaries for autonomous work
