---
type: issue
status: open
severity: friction
tags: [seon.db, datahike, pull, seon.test, reach, class/absence-as-health]
opened: 2026-09-17
---

# `pull` caps cardinality-many at 1000, so recorded reach reads truncate silently

## Problem

Datahike's pull applies `+default-limit+ 1000` to cardinality-many
attributes (`reference-code/datahike/src/datahike/pull_api.cljc:16`). Two
Seon readers pull `:seon.test/reach` memberships through it:
`src/seon/test.clj:63` (`changed-since-green`'s reach read) and the recording
writer's `held-members` (`src/seon/test/runner.clj:2229`). A test whose reach
exceeds 1000 members has members past the cap that are never read, so the
writer never retracts stale ones and the reader compares against a truncated
set. Nothing reports the cut: absence read as health.

Measured context (`docs/prds/steward-platform/research/selection-efficiency-2026-09-17.md`
§1d, §6): the recorded reach covers only 281 of 1,829 tests and is not the
selection authority; the worst seed reaches 1,009 tests, so the cap is
reachable today.

## Wanted

Readers that need a complete cardinality-many set use a query (`seon.db/q`)
or pull with an explicit `:limit nil`, never the default pull; the recording
writer's replacement compares complete sets. If a bound is wanted, it is a
declared query-work bound whose cut is reported as an elision value, never
the dependency's silent default.
