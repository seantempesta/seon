---
type: issue
status: resolved
severity: blocker
tags: [issue, render, agent-runtime, schema]
---

# Keep root acquisition independent of a render projection

## Problem

`seon.render.walk/root-acquisition` declared the full neighborhood request,
which requires `:seon.render/output`, even though acquisition neither reads nor
selects a projection. The live prompt path correctly constructed a
projection-neutral request, so development instrumentation stopped the turn
before any AI attempt was created.

## Evidence

The retained isolated-root log at
`tmp/flash-sent-body-proof-20260812-01/data/clusters/flash-sent-body-proof-20260812-01/logs/seon.log`
records 716 instrumented Vars followed by the missing-output violation and no
provider attempt. The live call chain is message submission → agent wake →
`seon.cluster.prompt/acquire-within-budget` →
`seon.render.web/context-pass` → `seon.render.walk/root-acquisition`; neither
the prompt nor HTML acquisition request owns a projection selector.

The W2 fixture had hidden the mismatch by adding
`:seon.render/output :seon.render/ai` to every acquisition request even though
only `neighborhood` consumes that key.

## Owner

The root-acquisition request declaration in
`resources/seon/schemas/seon.render.walk.edn` and
`seon.render.walk/root-acquisition`.

## Acceptance

- A root-acquisition request without `:seon.render/output` satisfies its
  declared contract and returns the acquired membership.
- The neighborhood request remains projection-specific.
- The production-shaped instrumented turn reaches its attempt seam without a
  render contract violation.

## Resolved 2026-08-12

Commit `66bf3fca3` introduced the smaller
`:seon.render.walk/acquisition-request` and made `root-acquisition` declare it.
The regression invokes and validates acquisition with no output selector; the
focused JVM proof passed with the fault-recording regression (2 tests, 79
assertions, zero failures/errors). The retained live failure was pre-provider,
so this repair makes no paid call.
