---
type: issue
status: resolved
severity: friction
tags: [issue, testing, config, repl]
---

# SCI eval gate retained stale config and doc expectations

## Problem

The explicit `seon.sci.eval-test` gate retained five failures after the open-map
contract landing. Four assertions built a sparse config row and therefore read
the production `:record` fallback instead of the requested `:panic` mode. The
remaining assertion expected an older `my.fs/read` docstring even though the
current function describes the same bounded read as “Read a bounded window of
one file.”

## Evidence

The archived open-map issue recorded the exact two failing tests and the five
assertions. A direct probe of the contract fixture returned
`:seon.config/on-core-error :record` plus a missing-effective-config value;
after compiling a complete config row, both acquisition paths installed the
interpreted contract and returned `:seon.instrument/contract-violated` for the
bad call. Printing the current `doc` face showed that only the introductory
sentence had changed; both input and output contract projections remained.

## Resolution

The contract fixture now transacts `config/compile-manifest`'s complete desired
row with its intended overrides. The doc assertion follows the current public
docstring and includes the rendered output when it fails. No production
behavior changed.

## Proof

`bin/test seon.sci.eval-test` passes 51 tests and 243 assertions with zero
failures or errors.
