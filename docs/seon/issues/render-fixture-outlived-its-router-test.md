---
type: issue
status: open
severity: cleanup
tags: [issue, deletion, render, testing]
---

# Delete the render fixture that outlived its router test

## Problem

`test/seon/render_fixture.clj` is an undiscovered support namespace whose only
consumer was deleted. Its docstring still claims a current router test loads
it, but no test does.

## Evidence

- `test/seon/render_fixture.clj:1-21` defines the support namespace and says
  `test/seon/render_test.clj` consumes its symbol.
- That test file is absent; commit `67bd2f216` deleted it.
- Exact first-party search for `seon.render-fixture` finds only the fixture's
  own namespace declaration.
- `bin/test:121-128` discovers only `*_test.clj` and `*_test.cljc`, so the
  fixture cannot run independently.

## Owner

The current render tests and their explicitly consumed support fixtures.

## Acceptance

Delete `test/seon/render_fixture.clj`; exact first-party search finds no
`seon.render-fixture`, and the surviving render-focused tests remain green.
