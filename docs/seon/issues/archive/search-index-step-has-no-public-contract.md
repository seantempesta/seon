---
type: issue
status: resolved
severity: friction
tags: [issue, contracts, testing]
---

# Give `seon.search/index-step` its public contract

## Problem

`seon.search/index-step` was public but had no complete Malli contract, so it
failed the standing public-function census.

## Evidence

The bare 2026-08-06 gate failed
`seon.public-contract-test/every-fresh-public-function-has-a-complete-contract`
at `test/seon/public_contract_test.clj:81` with exactly:

```clojure
[#:seon.public-contract{:name index-step,
                        :schema nil,
                        :file "src/seon/search.clj"}]
```

The function must remain public: `seon.cluster/cluster-graph-definition`
passes `#'search/index-step` across namespaces to `seon.flow/var-process`,
preserving Flow's Var-backed hot-reload mechanism.

## Owner

`seon.search/index-step` in `src/seon/search.clj` and the global contract
registry.

## Acceptance

The public function has a complete input/output contract grounded in its real
Flow call shape. The public-function census remains nonempty and reports no
missing contract for `index-step`.

## Resolution

Resolved by the path-limited fix that archives this note. The function now
declares all four core.async Flow arms: describe, init, lifecycle transition,
and transaction-report transform. The contract names the required state,
channels, lifecycle transitions, exact input port, raw database values, real
Datahike datoms, and `[state nil]` transform result without `:any` or `:some`.
Its process-local predicate schemas have honest generators spanning real
Datahike Datom values and meaningful unary ping projections.

Proof on 2026-08-06:

`bin/test seon.search-test seon.public-contract-test`

It ran 9 tests and 24 assertions with zero failures and zero errors, including
exact transaction-report index advancement and the nonempty public-contract
census. `bin/issues-index --check` reported the issue schedule clean.
