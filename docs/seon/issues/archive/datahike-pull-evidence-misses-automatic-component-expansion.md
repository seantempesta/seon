---
type: issue
status: resolved
severity: blocker
tags: [issue, database, datahike, render, caching]
---

# Include automatic component expansion in Datahike pull dependencies

## Problem

Pulling a forward component ref without an explicit subpattern automatically
expands the component entity, but `pull-dependency-plan` records only the named
component ref attribute. A child-attribute-only transaction can therefore
change the pull result without advancing any retained dependency revision.

## Evidence

- `reference-code/datahike/src/datahike/pull_api.cljc:18-37` recursively records
  explicit subpatterns and otherwise only `:attr`.
- `reference-code/datahike/src/datahike/pull_api.cljc:187-233` detects a forward
  component ref and expands it with a wildcard `PullSpec` even when the parsed
  selector has no subpattern.
- `reference-code/datahike/src/datahike/pull_api.cljc:53-69,437-454` publishes
  that narrower plan as the evidence for `pull` and `pull-many`.
- `src/seon/db.clj:262-270,316-343,395-415` trusts the published plan for
  retained read currency before falling back to replay. If the omitted child
  attribute revision is unchanged in the plan, replay is not reached.

## Owner

The maintained Datahike pull dependency projection. Seon must not add a second
pull analyzer.

## Acceptance

- A component ref without a subpattern either widens the dependency plan to
  `:all` or derives the concrete expanded attributes from schema semantics.
- A generated regression changes only an automatically expanded component
  child's attribute and proves that changed pull result implies changed
  dependency revision/replay.
- Explicit nested selectors remain concrete; reverse refs and recursion retain
  their current canonical attributes.

## Resolution

Resolved in the maintained Datahike fork at
`407e9328851ccce318148188f1d284646eb64132` and pinned by Seon commit
`5ad4fed91`.

`pull-with-evidence` and `pull-many-with-evidence` now pass the database value
into `pull-dependency-plan`. The dependency projection consults Datahike's
schema semantics and widens a bare forward component ref to `:all`, exactly
matching the wildcard `PullSpec` that execution installs for automatic
component expansion. Explicit nested selectors, reverse refs, and recursion
continue to return their concrete canonical attributes. The database-free
experimental arity widens bare forward attributes conservatively rather than
claiming schema precision it does not have.

### Evidence

- Before the fix, a raw JVM REPL over a schema with component ref
  `:root/child` pulled `:child/value` from the child but returned evidence
  attributes `#{:root/child}`.
- After the fix, the identical form returns evidence attributes `:all` while
  preserving the same expanded result.
- Datahike's focused pull API gate passed all three configured suites: 78
  tests, 255 assertions, zero failures.
- `bin/test seon.db-test seon.datahike-fork-test` passed 25 tests and 219
  assertions. Its Seon regression changes only the expanded component
  child's value and proves `read-evidence-current?` returns false.
- `bin/test --changed test/seon/db_test.clj` passed its platform and derived
  selections: 95 tests, 535 assertions, zero failures.
