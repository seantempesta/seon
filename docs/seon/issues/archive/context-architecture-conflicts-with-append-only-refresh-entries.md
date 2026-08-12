---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, render, context, caching]
---

# Reconcile context architecture with append-only refresh entries

## Problem

The context architecture said every model call re-derived the walk and nothing
accumulated. The sealed 2026-08-11 design instead retains render observations,
never changes their prior bytes, and appends refreshed values as new entries.
The always-current architecture described superseded replacement behavior.

## Evidence

- `docs/seon/architecture/context.md:16-27` specified a complete walk on every
  model call and stated “Nothing is accumulated.”
- `docs/seon/architecture/context.md:41-46` described process-local retained
  fragments but replaced newly produced bytes instead of appending a historical
  observation.
- `src/seon/render.clj:433-461` and `src/seon/render/web.clj:786-800` implement
  the replacement behavior with maps keyed by call id.
- The ruled incremental-invalidation deliverable requires every stale
  re-derivation to append and prior entries to remain byte-stable for provider
  prefix caches.

## Owner

`docs/seon/architecture/context.md` and
`docs/seon/architecture/ui.md`.

## Acceptance

- The architecture distinguishes the disposable latest-call projection from
  the append-only ordered prompt-entry projection.
- It states the prefix guarantee's process/crash scope without settling the
  still-open compaction policy.
- It targets the schema-derived root pull and existing wake/render proc, without
  restoring a second traversal, parser, listener, or durable invalidation log.

## Resolution

Resolved by the commit containing this note. The two architecture authorities
now express one target contract, grounded claim by claim in the sealed design:

- Render calls execute once and refresh only when captured reads become stale
  ([sealed PRD lines 29–39](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L29-L39)).
- One schema-derived pull at the agent root acquires membership, and each unit's
  form, AI, and HTML projections use one selection chain
  ([sealed PRD lines 81–121](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L81-L121)).
- The render proc separates its disposable latest-call lookup from immutable,
  ordered, basis-labelled history entries, so prompt N+1 is prompt N plus a
  suffix
  ([sealed PRD lines 123–138](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L123-L138);
  [invalidation design lines 246–260](../../../prds/sci-execution-runtime/research/incremental-invalidation-design-2026-08-11.md#L246-L260)).
- Agent work wakes only for addressed facts, while render refresh is passive and
  never starts a turn
  ([sealed PRD lines 48–54](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L48-L54)).
- Attribute interest routing selects candidates, retained read evidence proves
  exact staleness, and unchanged acquisition performs zero database reads
  ([sealed PRD lines 123–138](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L123-L138)).
- Required constructor-assigned authorship permits only system-authored reads to
  refresh, making re-execution of agent-authored forms unrepresentable
  ([sealed PRD lines 68–77](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L68-L77);
  [sealed PRD lines 140–150](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L140-L150)).
- Derivation order teaches define-before-use and refreshed calls append in the
  root derivation's established order
  ([sealed PRD lines 92–94](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L92-L94);
  [sealed PRD lines 175–195](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L175-L195)).
- Prompt, agent page, and root preview share retained block artifacts at
  different fits; root shows attached agents as live windows
  ([sealed PRD lines 34–47](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L34-L47)).
- The byte-stable prefix is process-local to one retained prompt generation and
  is not promised across a crash
  ([invalidation design lines 326–344](../../../prds/sci-execution-runtime/research/incremental-invalidation-design-2026-08-11.md#L326-L344)).
- Compaction and tile ordering remain open, so the architecture does not choose
  either
  ([sealed PRD lines 219–229](../../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md#L219-L229)).
