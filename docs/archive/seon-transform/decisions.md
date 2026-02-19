# Seon Transform - Decisions

Record architectural choices with rationale here.

---

## Decision Log

### 2024-12-13: Sequential Stages with Git Checkpoints

**Decision**: Transform in 5 sequential stages, each with a git commit as rollback point.

**Rationale**:
- Allows incremental verification
- Easy rollback if something breaks
- Clear progress markers
- Each stage is independently testable

**Alternatives considered**:
- Big bang transformation (rejected: too risky, hard to debug)
- Parallel development (rejected: complexity, merge conflicts)

---

### 2024-12-13: Keep Trading as First Domain

**Decision**: Trading functionality becomes `seon.trading.*` rather than being removed.

**Rationale**:
- Proven, working code serves as template for new domains
- Tests provide examples of patterns
- No loss of existing functionality

---

### 2024-12-13: Separate XTDB Per Domain

**Decision**: Each domain gets its own XTDB node/storage directory.

**Rationale**:
- Domain isolation (health data separate from trading)
- Independent backup/restore
- Can tune storage per domain
- Aligns with XTDB's multi-database capabilities

**Trade-offs**:
- Cross-domain queries more complex
- More resources (multiple DB nodes)
