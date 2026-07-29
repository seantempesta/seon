---
type: issue
status: open
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
