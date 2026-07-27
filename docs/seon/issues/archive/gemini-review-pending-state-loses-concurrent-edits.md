---
type: issue
status: resolved
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

- `bin/seon-hook:339-381` implements `read-pending`, `write-pending!`,
  `accumulate-pending!`, and `clear-reviewed-pending!` with separate `slurp`
  and `spit` calls and no lock or generation.
- If hooks A and B both read the same pending vector, A writes path A and B
  writes path B; the last write drops the other path.
- `clear-reviewed-pending!` removes a set of path strings from the current
  file. If path P is edited again during the model call, the new occurrence is
  indistinguishable from the reviewed snapshot and is removed.
- `gemini-review-feedback` clears the complete `pending` snapshot at
  `bin/seon-hook:593-631`, not the subset of paths successfully read into
  `files`. A deleted, empty, or transiently unreadable path is cleared as
  reviewed when some other file produces an artifact.
- `utc-review-path` has second-level filename precision. Concurrent reviews in
  one second may overwrite the same full-review artifact.
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
- An unreadable or empty pending file remains pending; absence of source text
  is not recorded as successful review.
- Concurrent successful reviews publish distinct artifacts.
- A concurrent-process regression exercises publication, review, re-edit, and
  clear.

## Resolution

Resolved by `f973b2d88`.

`bin/seon-hook` now serializes pending-file transitions with an exclusive file
lock and stores a UUID with each edit generation. Accumulation still applies
newest-kept path deduplication and the 50-file cap, while review completion
removes only UUIDs from the successfully materialized drained entries. A later
edit of the same path therefore remains distinguishable and pending. Legacy
path-only lines are read with stable legacy identities and rewritten in the
new format on the next state transition.

The deterministic Babashka probe
`tmp/review-backlog-race-probe.clj` captured a batch, accumulated a distinct
path and a new generation of the captured path, then cleared the captured
batch:

```text
before-drain-clear [/repo/a.clj]
arrived-during-review [/repo/b.clj /repo/a.clj]
after-exact-clear [/repo/b.clj /repo/a.clj]
```

A live external review timed out twice and left all three pending UUID/path
entries intact, proving the timeout path does not clear the backlog. A
repository-local deterministic `agy` response then exercised a complete
PostToolUse edit-to-review cycle:

```text
Gemini review (source.clj) —
tmp/hook-e2e/tmp/reviews/20260727T153537.711Z.md:
source.clj: review completed
pending-after=
```

`bb bin/seon-hook <<<'{}'` and `git diff --check -- bin/seon-hook` also passed.
