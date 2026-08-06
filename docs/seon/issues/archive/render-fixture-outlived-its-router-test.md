---
type: issue
status: resolved
severity: cleanup
tags: [issue, deletion, render, testing]
---

# Delete the render fixture that outlived its router test

## Problem

`test/seon/render_fixture.clj` was an undiscovered support namespace whose only
consumer was deleted. Its docstring still claimed a current router test loaded
it, but no test did.

## Evidence

- `test/seon/render_fixture.clj` defined the support namespace and said the
  deleted `test/seon/render_test.clj` consumed its symbol.
- Commit `67bd2f216` deleted that test file.
- Exact first-party search for `seon.render-fixture` found only the fixture's
  own namespace declaration.
- `bin/test` discovers only `*_test.clj` and `*_test.cljc`, so the fixture could
  not run independently.

## Owner

The current render tests and their explicitly consumed support fixtures.

## Acceptance

Delete `test/seon/render_fixture.clj`; exact first-party search finds no
`seon.render-fixture`, and the surviving render-focused tests remain green.

## Resolution

Resolved by the audit-finding-6 commit that archives this issue. The orphan
fixture is deleted, exact search finds no surviving namespace reference, and
the focused render suites remain green.
