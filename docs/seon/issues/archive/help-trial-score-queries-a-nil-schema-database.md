---
type: issue
status: resolved
severity: friction
tags: [issue, test, database, wave/agent-context]
created: 2026-09-15
---

# Help trial scoring reaches a nil schema database

Run4-blockers' HEAD-plus-owned-reader-paths fast gate at `6785c980c` ran
51 tests / 416 assertions. All 12 failures were in
`seon.help-trial-test/trial-scores-actual-forms-and-fails-on-absence`.
Its score returned database error entries instead of scoring data:

`seon.db/schema-database violated its contract (invalid-input): must be an immutable Datahike database value`

The diagnostic offending arguments were `[nil]`, caller
`seon.db (db.clj:909)`. The run4 reader test, reader/reply namespaces and
REPL grammar tests had no failures. This is the exact observed boundary,
not a verified attribution to a particular database change.

The score owner is the committed help trial script; database decoding is
owned by `src/seon/db.clj`. Both are excluded or concurrently edited in
run4-blockers. Acceptance: the canonical armed trial again returns its
12 scoring decisions and its absence cases fail as intended.

Resolved independently in `eef44fcc3` (relation-only Datalog queries).
The run4-blockers isolated AI/grammar/help gate at `1d5edb65c` passed
61 tests / 307 assertions, including both help-trial tests, on 2026-09-15.
