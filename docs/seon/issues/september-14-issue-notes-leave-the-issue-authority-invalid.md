---
type: issue
status: open
severity: friction
tags: [issue, docs, test, wave/verification-audit]
---

# Restore issue metadata and schedule coverage after the September 14 landings

## Problem

`bin/issues-index --check` exits 1. Several day's notes lack the required
`issue` tag or remain top-level with resolved status. New valid notes,
including audit-1's, have no row in the owner-maintained schedule. This is
a documentation verification boundary, not evidence about the product defects
those notes describe.

## Evidence

Audit-1, 2026-09-15, after reviewing through `1f18b99fc`:

- `docs/seon/issues/derived-map-render-pairs-compete-with-less-specific-entity-pairs.md:3`
  declares resolved while the note remains outside archive; line 5 lacks `issue`.
- `docs/seon/issues/acquire-context-ignores-the-turn-id-so-per-turn-prompts-are-the-current-context.md:3`
  has the same lifecycle/location mismatch.
- `docs/seon/issues/reader-errors-make-the-model-believe-the-reader-is-stateful.md:5`
  lacks the required query tag and uses non-area tags.
- The checker reports `missing-schedule-row` for the audit findings, the existing
  runtime-message duplication note, and other recent notes. Audit-1 is explicitly
  instructed not to edit `docs/seon/issues/index.md`.

Earlier issue-authority drift is archived at
[the historical issue](archive/issue-authority-frontmatter-drift-blocks-index.md).
Its old generated-index repair is not applicable: the current index is a
human-maintained ranked schedule.

## Owner and acceptance

The orchestrator normalizes note metadata, archives actually resolved notes,
and assigns every open note one destination in the existing index. Preserve
issue substance and do not infer resolution from a metadata repair.
`bin/issues-index --check` must then pass. Audit-1's new notes use
`wave/verification-audit`; the existing runtime-message note keeps
`wave/ui-watchability`.
