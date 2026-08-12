---
type: issue
status: resolved
severity: blocker
tags: [issue, test, indexing]
---

# Preserve a function row's declared test subject

## Problem

Static indexing and settled authored-form analysis both derived
`:seon.test/subject` for a function, but the shared function-row
canonicalizer did not own that declared attribute. Static rows therefore lost
the subject while settled form rows retained it.

## Evidence

The W1 integration gate failed
`seon.fn-test/settled-agent-form-has-static-index-edge-parity`: its static
function row had no subject while the settled form carried
`[:seon.fn/sym "sample.settlement-parity/helper"]`.
`resources/seon/schemas/seon.fn.edn` declares the optional attribute on
`:seon.fn/fn`, and `seon.fn/var-row` plus the authored-form analyzer both
produce it. `seon.program/shapes` was the only dropping boundary.

## Owner

The exact owned-attribute projection in `src/seon/program.cljc`.

## Resolution

Commit `28aca2409` adds the declared attribute to the function-row ownership
shape and adds a canonical-row regression. `seon.program-test` passed 20 tests
/ 237 assertions, and the integrated function/REPL proof passed 92 tests / 281
assertions with no failures or errors.
