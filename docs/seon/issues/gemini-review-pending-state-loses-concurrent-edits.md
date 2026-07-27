---
type: issue
status: open
severity: friction
tags: [issue, tooling, testing, agent]
---

# Preserve concurrent edits in the Gemini review backlog

## Problem

The restored Gemini review backlog is a shared file updated by unlocked
read-modify-write operations. Concurrent hooks can overwrite each other's
pending paths. An edit to the same file while a review is running is also
removed when the older review clears by path.

The review cadence can therefore treat absence from the pending file as proof
that an edit was reviewed when it was silently lost.

## Evidence

- `bin/seon-hook:338-380` implements `read-pending`, `write-pending!`,
  `accumulate-pending!`, and `clear-reviewed-pending!` with separate `slurp`
  and `spit` calls and no lock or generation.
- If hooks A and B both read the same pending vector, A writes path A and B
  writes path B; the last write drops the other path.
- `clear-reviewed-pending!` removes a set of path strings from the current
  file. If path P is edited again during the model call, the new occurrence is
  indistinguishable from the reviewed snapshot and is removed.
- This repository intentionally runs multiple edit lanes in one checkout, so
  concurrent PostToolUse hooks are a normal operating condition.

The model call itself is total at the hook boundary, timeout destruction was
probed to kill both a shell root and its child, and feedback is clipped below
the configured character/token budget. The defect is isolated to shared
backlog ownership.

## Owner

The Gemini-review state transition in `bin/seon-hook`. Use the existing
repository lock/state idiom or one generation-stamped atomic owner; do not
introduce a second review queue.

## Acceptance

- Concurrent hooks for distinct files preserve both edits.
- A same-file edit that occurs after a review snapshot remains pending after
  that snapshot is cleared.
- Only the exact reviewed generation is removed after its full artifact is
  written.
- A killed/timed-out review preserves every pending generation for a later
  attempt.
- A concurrent-process regression exercises publication, review, re-edit, and
  clear.
