---
type: issue
status: open
severity: cleanup
tags: [issue, docstrings, program-graph, links]
---

# Docstring links name deleted functions

## Problem

`seon.schema/restore!` (`src/seon/schema.clj:3657-3663`) ends its docstring with
"exact test-state capture remains [[snapshot-state]] / [[restore-state!]]". Neither
function exists: no Var, no program row, no mention anywhere else in `src/` or `test/`
(`rg -n "snapshot-state|restore-state!" src test` returns only this line). The last
commits touching the names are `f2903354a` and `2b6d84dc2`. An agent reading the
docstring is sent to functions it cannot call.

Nothing ingests docstring links today, so the rot is invisible. The references-as-refs
design (`docs/research/agent-platform/references-as-refs-design-2026-09-25.md` §3e, slice
S1) makes an unresolved docstring link a refusal at `seon.fn/var-row`; this docstring
would be its first refusal.

## Evidence

Fresh default (pid 39484, 2026-09-25): of 44 `[[x]]` links in stored function
docstrings, 41 resolve in the holder's namespace. The three that do not are the two
above and `seon.ai.tokens/estimate`'s `[[shipped-calibration]]`. That third one names an
existing Var that has no program row, a separate class filed as
`a-computed-def-in-seon-ai-tokens-has-no-program-row.md`.

## Fix

Delete the stale clause, or name the functions that now own exact test-state capture,
in the same cut that lands S1's refusal. `src/seon/schema.clj` is held by a running lane
(see `tmp/orchestrator/file-ownership.md`), so the design lane did not edit it.

## Owner

`src/seon/schema.clj` owner; S1 of the references-as-refs design.
