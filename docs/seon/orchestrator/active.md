---
type: orchestrator
tags: [index, issue]
status: active
---
# Active Work

## Recovery

>
> Read this after context loss. It tells you where we are.

**Work:** Documentation Audit & Repair
**Branch:** feature/refinement
**Last updated:** 2026-03-11

## Doc Audit Pipeline

Systematic verification of ~230 active docs against source code and REPL.
Agents fix docs in place, orchestrator reviews and commits per wave.
Multiple verification passes expected — first pass catches structural issues,
subsequent passes deepen accuracy. After active docs are solid, archive
review expands milestones and surfaces undocumented/untested work.

### Wave 1: Component Notes vs Code (5 agents)

| # | Agent | Scope | Status | Verified |
|---|-------|-------|--------|----------|
| V1 | Data layer | `components/database.md`, `schema-system.md`, `runtime.md` | complete | 5 fixes |
| V2 | Flow layer | `components/flow-topology.md`, `harness.md`, `context.md` | complete | 5 fixes |
| V3 | Graph+Render | `components/code-graph.md`, `renderer.md`, `namespace-lifecycle.md` | complete | 4 fixes |
| V4 | Web+Agent | `components/web-layer.md`, `agent-system.md` | complete | 6 fixes |
| V5 | Dev+Testing | `components/dev-tools.md`, `testing.md`, `system-lifecycle.md` | complete | 5 fixes |

### Wave 2: Architecture + Concepts + Conventions (3 agents)

| # | Agent | Scope | Status |
|---|-------|-------|--------|
| V6 | Architecture | `architecture/overview.md`, `decisions/001-007` | complete | 6 fixes |
| V7 | Concepts | `concepts/` (9 files) | complete | 7 fixes |
| V8 | Conventions+Indexes | `conventions.md`, `_dashboard.md`, `namespaces.md`, `prds.md` | complete | 7 fixes |

### Wave 3: Issues + Reference (2 agents)

| # | Agent | Scope | Status |
|---|-------|-------|--------|
| V9 | Issue triage | `orchestrator/issues/` (39 files) | complete | 1 resolved, 3 annotated |
| V10 | Reference staleness | `reference/` (14 files) | complete | 5 fixed, 1 issue created |

### Wave 4: Vision + Capabilities (2 agents)

| # | Agent | Scope | Status |
|---|-------|-------|--------|
| V11 | Milestones | `vision/index.md`, `vision/m1-m8` | complete | 2 fixes, 1 issue |
| V12 | Capabilities | `vision/capabilities/` (30 files) | complete | 5 fixes |

### Wave 5: PRDs (1 agent)

| # | Agent | Scope | Status |
|---|-------|-------|--------|
| V13 | PRD audit | `prds/` (22 dirs, ~105 files) | complete | 10 status fixes |

### Wave 6: Reference Cleanup + Skills Audit

| # | Agent | Scope | Status |
|---|-------|-------|--------|
| V14 | Reference rewrites | Fix 5 partially-stale reference docs (XTDB→Datalevin, dead paths) | in-progress |
| V15 | Skills audit | Audit `.claude/skills/` — verify patterns match source, move duplicated knowledge into docs, add doc cross-refs so skills don't go stale | in-progress |

### Follow-up Passes

- **Pass 2+**: Re-verify docs that had significant fixes, deepen accuracy
- **Archive review**: Mine `docs/archive/` for undocumented capabilities, incomplete work, and lessons that should inform milestones and issue tracking

<!-- Status: planned -> in-progress -> complete -> verified | failed -->

## Session Notes

- 2026-03-11: Vault reorganization. Moved from iCloud to docs/. Created orchestrator workspace, issue notes, PRD index.
- 2026-03-11: Phase B complete. 8 milestone docs, 30 capability notes, 36 issues, 14 component notes. M1-M5 verified.
- 2026-03-11: Doc audit started. Wave 1 launched — 5 verifier agents checking component notes against source code.
- 2026-03-11: Wave 1 complete. 25 fixes across 14 component docs. 3 new issues created.
- 2026-03-11: Wave 2 complete. 20 fixes across architecture, concepts, conventions, indexes. 1 new issue.
- 2026-03-11: Wave 3 complete. 1 issue resolved, 3 annotated, 34 confirmed open. 5 reference docs fixed, 1 issue created.
- 2026-03-11: Waves 4+5 complete. 2 milestone fixes, 5 capability fixes, 10 PRD status corrections. 1 new issue (nippy-transitive-dep). Wave 6 launched.
