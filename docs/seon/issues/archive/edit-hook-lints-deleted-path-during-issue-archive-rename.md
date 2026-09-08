---
type: issue
status: resolved
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

## Resolution — 2026-09-08

Issues-sweep reproduced this exact hook error while archiving the changed-test
selector note. The pin validator read Git's cached paths as though they were
the working tree. It now includes tracked and untracked Markdown subjects,
excludes Git-reported deletions from content reads, and returns those deletions
as `:seon.dev.markdown/deleted-paths`. It does not guess rename identity.
Explicit validation of a deleted file still returns `:file-not-found`.

The real-Git regression stages an original document and a gitlink, moves the
document to an untracked archive destination, verifies its valid pin, then
changes that destination to a stale pin and requires the exact destination
violation. A deletion without a destination remains reported. The existing
Babashka Markdown suite passed 31 tests / 376 assertions / zero failures and
errors. The test owns a project-local temporary repository and deletes it
without following links.
