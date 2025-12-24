# Primer Decisions Log

Record architectural decisions with rationale.

---

## Decision 1: Separate XTDB Node for Primer

**Date:** 2024-12-24
**Context:** Primer needs to store sessions, scenes, child profiles. Could share the main Seon node or have its own.
**Decision:** Separate XTDB node (`:seon.primer/xtdb-node`)
**Rationale:**
- Domain isolation (per Seon architecture)
- Can evolve schema independently
- Easier to backup/restore just primer data
- Follows pattern from PLAN.md Stage 4

---

## Decision 2: Ctx Atom with Schema Validation

**Date:** 2024-12-24
**Context:** Need central state for UI rendering. Options: raw atom, spec'd atom, database-only.
**Decision:** Atom with Malli validation on every update
**Rationale:**
- Matches existing pattern (web/jobs.clj)
- Catches agent errors immediately
- Specs document what's valid
- Can still checkpoint to XTDB for persistence

---

## Decision 3: HTML/CSS for Initial UI Layer

**Date:** 2024-12-24
**Context:** Need to render scenes with layers, positions, animations. Options: HTML/CSS, Canvas, Three.js.
**Decision:** Start with HTML/CSS, upgrade later if needed
**Rationale:**
- Fastest to prototype with Datastar
- CSS handles layers (z-index), positions (absolute), animations (transitions)
- Can always add Canvas/Three.js components later
- Matches existing Seon web patterns

---

## Decision 4: Pre-computed Actions in Scene

**Date:** 2024-12-24
**Context:** How should user interactions work?
**Decision:** Each scene has `:scene/actions` vector; actions are pre-defined with handlers
**Rationale:**
- Agent pre-computes available actions (planning phase)
- Runtime just looks up and executes (fast)
- Dynamic actions (voice input) can fall back to AI
- Spec constrains what actions look like
