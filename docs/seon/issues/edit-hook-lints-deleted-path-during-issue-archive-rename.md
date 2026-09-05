---
type: issue
status: open
severity: friction
tags: [issue, docs, wave/dev-tooling-face-hygiene]
---

# Make the edit hook validate an issue's destination after archival

## Problem

Moving a resolved issue from `docs/seon/issues/` to
`docs/seon/issues/archive/` leaves the edit hook validating the deleted source
path. A subsequent edit to the archived file reports a false markdown-lint
error even though the destination exists and its content is valid.

## Evidence

On 2026-09-05, after moving
`init-program-population-can-still-trip-the-silence-backstop.md` into
`docs/seon/issues/archive/`, two edits to the destination both emitted:

```text
Dependency pin validation could not derive repository evidence:
/Users/sean/src/seon/docs/seon/issues/init-program-population-can-still-trip-the-silence-backstop.md
(No such file or directory)
```

`git diff --check` passed for the source deletion, archived destination, and
the related issue update. The hook named only the intentionally deleted
source path.

## Owner

The edit hook's markdown dependency-pin validation and its rename/path
selection.

## Acceptance

- Archiving a resolved issue validates the destination content and reports no
  missing-source error.
- A genuinely broken dependency pin at the destination still fails loudly.
- A source deletion without a corresponding destination remains observable
  rather than being treated as a successful rename.
