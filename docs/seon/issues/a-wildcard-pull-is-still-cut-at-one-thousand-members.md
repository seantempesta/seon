---
type: issue
status: open
severity: friction
tags: [seon.db, datahike, pull, class/absence-as-health]
opened: 2026-09-17
---

# A wildcard pull is still cut at 1 000 members

## Problem

Datahike's pull limits every cardinality-many attribute to
`+default-limit+` = 1 000 members and reports nothing about the cut
(`reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`, `:323`).
`seon.db`'s `total-pull-selector` now gives every attribute a caller NAMES
the dependency's `:limit nil`, so named readers are total. A WILDCARD clause
names no attribute: `(seon.db/pull db '[*] eid)` and `seon.db/entity`, which
is a wildcard pull (`src/seon/db.clj`, `entity-call`), still return a short
answer as if it were complete.

The exposure is real: `:seon.test/reach` holds 484 412 datoms on `default`,
so any wildcard pull of a test row is already cut, and `:seon.fn/calls` and
`:seon.fn/references` are large sets too.

## Wanted

A wildcard pull that is either total or says it was cut — never a short
answer presented as complete. The dependency accepts the widening in
ordinary selector syntax: `[* [:a :limit nil]]` parses to an `:attrs` entry
whose options the wildcard expansion path looks up
(`pull_api.cljc:423-426`), and `pull-spec-attribute-dependencies` already
answers `:all` for a wildcard (`:110-120`), so read evidence is unaffected.

What stopped the fix inside the small-fixes lane was cost, not
correctness: widening every cardinality-many attribute means reading
`(dbi/-attrs-by db :db.cardinality/many)` and rebuilding — and reparsing —
a projection-sized selector on every `entity` call. A compiled-plan cache
keyed on that attribute set is the shape of the answer; measure it before
landing it.

Evidence and the named-attribute half:
[small fixes, 2026-09-17](../../prds/steward-platform/research/small-fixes-2026-09-17.md).
