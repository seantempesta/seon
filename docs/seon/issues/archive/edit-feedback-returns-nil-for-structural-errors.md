---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, testing]
---

# Return structural edit feedback instead of nil

## Problem

The edit-feedback boundary returned nil for two structural-error cases. The
tests then produced cascading `clojure.string/includes?` NPEs instead of one
useful mismatch naming why feedback was absent. Analysis failures could also
emit roughly 96 KB of advisory findings ahead of the actionable errors.

## Evidence

The 2026-08-05 focused baseline reproduced one failure and five errors. The
hook dynamically resolved the repository's complete dependency graph through
`babashka.deps/add-deps`; Babashka's Maven `babashka/fs` coordinate conflicted
with the maintained local fork in `deps.edn`. The outer exceptions had blank
messages, and both the exact-reconstruction catch and the top-level catch
discarded the useful cause.

## Owner

`bin/seon-hook` owns structured pre-edit and post-edit feedback.

## Acceptance

Both edit modes always return their declared structured response. A syntax
error and a valid sibling finding coexist in post-edit output; exact
reconstruction returns a structural block with a non-nil reason. Blocking
findings precede a bounded advisory tail.

## Resolution

Resolved by the path-limited hook-owner commit that archives this note. The
hook now adds only the maintained `src` and `script` source roots through
Babashka's classpath owner, preserves the first concrete exception message in
the cause chain, orders error findings first, and limits the advisory tail to
20 findings by default (configurable through
`:feedback :max-advisory-findings`).

`bin/test seon.dev.edit-feedback-test` passed 8 tests and 44 assertions,
including both originally red vars. The deliberately broken two-file face
placed `[error/syntax]` before warnings, retained three advisory findings,
reported that 27 were omitted, and remained below 2,500 characters.
