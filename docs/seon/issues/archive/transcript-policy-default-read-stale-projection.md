---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, schema, pod]
---

# Transcript policy default read used a stale activated schema projection

## Problem

The transcript namespace read four newly declared policy defaults by resolving
their keys through Malli's process-global registry. After database activation,
that registry intentionally represents the last committed schema projection;
it can lag current source declarations until reconciliation commits. A warm
database whose transcript schema predated `:default` therefore produced a nil
retention window and crashed the pod when instrumentation validated
`recent-html-events`.

## Evidence

The default cluster restarted through `bin/seon restart`, reached HTTP
readiness, then persisted a core `:malli.core/invalid-input` fault for
`seon.agent.ctx.transcript/recent-html-events`. Its arguments were `[[] nil
[]]`; the second positional schema requires an integer. The manifest carried
25, but existing block entities can predate that attribute, so the renderer's
source-owned fallback must remain valid during schema reconciliation.

Malli's `schema` implementation resolves keyword references through its active
registry, while a vector form is compiled directly. `seon.schema/register!`
stores the current declaration in its candidate collector and exposes it
through `schema-definition`; publication of the database-derived projection is
a separate atomic step.

## Resolution

The transcript's one default reader now compiles the current raw declaration
returned by `schema-definition` before reading its Malli properties. It no
longer asks the possibly older activated projection to resolve the declaration
keyword. One regression test pins all four policy defaults.

## Verification

- The complete CLJS checkpoint passed 911 tests and 4,645 assertions with zero
  failures or errors.
- A public rebuild restarted the existing default database, replayed both load
  units, instrumented 799 functions, resumed `root` and
  `frank-radios-wait`, and served `/` with HTTP 200.
- The live repository REPL read the current declaration's retention default as
  25.
- Five seconds after an exact historical-coordinate REPL read, watcher,
  writer, and pod remained ready; the resolved view reported t 536870953 and
  queried the root agent successfully.

Closed with the implementation commit recorded in Git history.
