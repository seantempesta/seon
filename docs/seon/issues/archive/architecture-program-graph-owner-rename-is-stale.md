---
type: issue
status: resolved
severity: cleanup
tags: [issue, architecture, schema]
---

# Remove the stale program-graph owner rename

## Problem

The maintained architecture still says the top-level `:seon.fn`,
`:seon.ns`, `:seon.schema`, and `:seon.test` attributes are pending a rename to
`seon.code.*`. The later owner ruling explicitly supersedes that rename and
keeps the established top-level attribute namespaces.

This is not only vocabulary drift. N5 implementers reading the architecture
could create new `seon.code.*` schemas or plan a migration while the fresh
canonical schemas and current roadmap require the existing identities.

## Evidence

- `docs/seon/architecture/architecture.md:229-231` defines the program graph
  and says its owning attribute namespaces are pending `seon.code.*`.
- `docs/seon/architecture/architecture.md:499-504` repeats the pending rename.
- `docs/seon/architecture/data-model.md:821-822` repeats it in the data-model
  target.
- `docs/seon/architecture/context.md:287-298` repeats it a third time.
- `docs/prds/sci-execution-runtime/plan/README.md:125-135` records the later
  owner ruling: program-graph facts stay top-level, explicitly superseding the
  `seon.code.*` rename.
- Fresh canonical identities remain `:seon.fn/sym`, `:seon.ns/name`, and
  `:seon.schema/key` in `resources/seon/schema/program.edn:4-54`.

## Owner

The program-graph vocabulary in `docs/seon/architecture/architecture.md` and
`docs/seon/architecture/context.md`.

## Acceptance

- The maintained architecture names only the settled top-level
  `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts.
- No active architecture or current roadmap text describes a pending
  `seon.code.*` attribute rename.
- A repository search for `seon.code.fn`, `seon.code.ns`,
  `seon.code.schema`, and `seon.code.test` returns only historical research or
  archived evidence, not active target documentation.

## Triage 2026-07-27

- **OPEN-CURRENT.** The superseded rename is still active target prose at
  `docs/seon/architecture/architecture.md:231,504`,
  `docs/seon/architecture/context.md:297-298`, and
  `docs/seon/architecture/data-model.md:821-822`, while the current ruling at
  `docs/prds/sci-execution-runtime/plan/README.md:384-386` keeps the program
  graph owners top-level.

## Resolution 2026-07-29

Resolved by `f77c4066e`. The architecture map, context target, and data-model
target now name `:seon.fn`, `:seon.ns`, `:seon.schema`, and `:seon.test` as the
settled owning attribute namespaces. The program-graph glossary and code-as-data
sections no longer describe a future owner migration.

Proof after the change:

- a search of active `docs/seon/architecture/*.md` finds no
  `seon.code.fn`, `seon.code.ns`, `seon.code.schema`, or `seon.code.test`;
- the current roadmap names that migration only as superseded history; and
- fresh source continues to register the established top-level identities.
