---
type: issue
status: resolved (bin/seon-hook clause removed 2026-09-19; the misdefined predicate in seon.test.cache remains open below)
severity: blocker
created: 2026-09-19
tags: [issue, hook, publication, operator, test-selection]
---

# The edit hook published every markdown edit as a source change

## Problem and evidence

Since `e9af61d87` (2026-09-15, "Run reaching tests in the development JVM
and record adoption checks") the hook's publication predicate
`source-index-path?` (`bin/seon-hook:1458`) admitted any path for which
`seon.test.selection/widening-path?` was true. That predicate
(`src/seon/test/cache.clj:101`) is "not under `src/` or `test/`", so every
edit to a document, a note under `tmp/`, or a log queued
`bin/seon init --dev default --changed <path>`: a complete publication and
adoption of default (60–400 s, holding `data/operator/root-lifecycle.lock`).
Records: `tmp/source-publications/85a63e1d-….edn` (paths = one plan
markdown), `9541df3a-….edn`, `89b4323f-….edn` (research note and issue
note), each ending in a lock-hold or silence refusal. On 2026-09-19 two
orchestrator sessions writing notes were therefore competing with the
publication repair through their own documentation, and "adoption refuses
while agents write" (working edge 2026-09-18) had this as a contributing cause.

The intent of the widening concept is gate SELECTION: a changed input outside
the program graph widens the cold gate to every eligible test (AGENTS.md §5).
It never meant publication, and its own definition is too wide for selection
too: documentation under `docs/` is not a gate input.

## Repair

`bin/seon-hook` no longer consults `widening-path?`; publication admits
first-party Clojure under `src/` and `test/`, `config/default.edn`, and schema
resources only. Regression owed: a hook event for a markdown path queues no
publication (the hook's own test surface).

## Still open (stage-1 owner, `src/seon/test/cache.clj`)

`widening-path?` should name the declared gate inputs (`deps.edn`, `bin/test`,
`bin/_test-slot`, `.claude/seon-hook.edn`, `config/`, `resources/seon/schemas/`)
rather than the complement of two roots, so a documentation-only change never
widens a gate. `bin/test` reads the same predicate through
`seon.test.selection`.
