---
type: decision
status: completed
tags: [decision, archive, database]
---

# Query Architecture - Architectural Decisions

This document records key architectural decisions for XTDB query patterns.

---

## Decision Log

### ADR-001: Separate Query Languages for System vs Domain Code

**Date**: 2025-12-17
**Status**: Accepted

**Context**:
We need multi-database support (ATTACH DATABASE requires SQL) and LLM-accessible domain code. However, rewriting all queries to SQL may not be optimal.

**Decision**:
- **System code** (db layer, web stats, ingestion) uses whatever is fastest (XTQL or SQL based on benchmarks)
- **Domain code** (trading, health, finance) uses SQL for LLM accessibility

**Consequences**:
- (+) Optimal performance for system code
- (+) LLMs can read/write domain queries
- (+) Enables future ATTACH DATABASE usage
- (-) Two query patterns in codebase (but cleanly separated)

---

### ADR-002: Frozen-Time Database Pattern

**Date**: 2025-12-17
**Status**: Accepted (implementation pending research)

**Context**:
Domain functions currently accept `:as-of` options and apply temporal filtering internally. This leaks temporal concerns into domain logic and makes it possible for domain code to "cheat" by querying future data.

**Decision**:
- System layer creates "frozen" database views at specific times
- Domain functions receive a pre-filtered `db` and just query it
- Domain functions have no temporal awareness
- Agent sessions receive frozen views that cannot see future data

**Consequences**:
- (+) Clean separation of concerns
- (+) Agents truly cannot see future data
- (+) Simpler domain function signatures
- (-) Need to implement frozen-db pattern (research required)

---

### ADR-003: Query Language for Domain Code

**Date**: 2025-12-17
**Status**: Accepted

**Context**:
Domain code needs to be accessible to LLM agents for reading, understanding, and writing queries.

**Decision**:
Domain code will use SQL because:
1. LLMs are trained extensively on SQL
2. SQL is the industry standard
3. Required for future ATTACH DATABASE cross-domain queries

**Consequences**:
- (+) LLMs can read/write queries correctly
- (+) Prepares for multi-database queries
- (-) Lose some XTQL composability (acceptable tradeoff)

---

## Pending Decisions

- [ ] **ADR-004**: Frozen-time implementation approach (after Phase 2 research)
- [ ] **ADR-005**: XTQL vs SQL for system code (after Phase 1 benchmarks)
- [ ] **ADR-006**: Query builder vs raw SQL for domain code
- [ ] **ADR-007**: Cross-database query patterns

---

## Research-Dependent Decisions

These decisions require research findings:

### Query Builder Approach

**Options**:
1. Raw SQL strings
2. HoneySQL (Clojure data → SQL)
3. Hybrid

**Will decide after**: Testing LLM's ability to work with each

### System Code Query Language

**Options**:
1. Keep XTQL (if faster)
2. Convert to SQL (if comparable or for consistency)

**Will decide after**: Phase 1 performance benchmarks
