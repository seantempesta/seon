---
type: issue
status: open
severity: blocker
tags: [issue, architecture, render, context, caching]
---

# Reconcile context architecture with append-only refreshed read entries

## Problem

The current context architecture says every model call re-derives the walk and
nothing accumulates. The 2026-08-11 owner direction instead requires retained
render observations whose prior bytes never change and whose refreshed values
append as new entries. The always-current architecture now describes the
superseded replacement behavior.

## Evidence

- `docs/seon/architecture/context.md:16-27` specifies a complete walk on every
  model call and states “Nothing is accumulated.”
- `docs/seon/architecture/context.md:41-46` describes process-local retained
  fragments but says a pass compares/replaces newly produced bytes rather than
  appending a new historical observation.
- `src/seon/render.clj:433-461` and `src/seon/render/web.clj:786-800` implement
  the replacement behavior with maps keyed by call id.
- The ruled 2026-08-11 incremental invalidation deliverable requires every
  stale re-derivation to append and prior entries to remain byte-stable for
  provider prefix caches; the grounded design is recorded in
  `docs/prds/sci-execution-runtime/research/incremental-invalidation-design-2026-08-11.md`.

## Owner

`docs/seon/architecture/context.md` and the bounded successor PRD that turns the
research design into implementation order.

## Acceptance

- The architecture distinguishes the disposable latest-call/index projection
  from the append-only ordered prompt-entry projection.
- It states the prefix guarantee's process/crash scope and how compaction starts
  a new prompt generation rather than mutating an existing one.
- It targets the schema-derived root pull and existing wake/render proc, without
  restoring a second traversal, parser, listener, or durable invalidation log.
