---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# Make program-acquisition tests select the installed package schema branch

## Resolution

Authored-program acquisition now derives its query and pull-pattern contract
through one pure projection of the installed schema. The regression test
controls package-present and package-absent schema values against that
projection, and the full acquisition fixture admits both structurally selected
namespace and function pull-pattern families.

Focused evidence is
`tmp/orchestrator/loop-slice-focused-acquisition-fixed.log`: 2 tests / 14
assertions / 0 failures / 0 errors. The final full CLJS gate is
`tmp/orchestrator/loop-slice-full-cljs-final.log`: 1,566 tests / 7,732
assertions / 0 failures / 0 errors.

## Original problem

The package-loading change made authored-program acquisition select its queries
and pull patterns from the immutable database value's installed schema. The
existing full-path acquisition test neither controlled that selection nor
admitted both structurally selected pull-pattern families, so it took the
package-empty branch while its fake `pull-many` recognized only the
package-aware patterns. Function and namespace source rows disappeared even
though the production selection was intentional.

## Owner

`src/seon/execution.cljs` owns the pure installed-schema selection.
`test/seon/execution_test.cljs` owns branch and full acquisition coverage.
