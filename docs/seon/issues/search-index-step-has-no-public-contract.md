---
type: issue
status: open
severity: friction
tags: [issue, search, contracts, testing]
---

# Give `seon.search/index-step` its public contract

## Problem

`seon.search/index-step` is public but has no complete Malli contract, so it
fails the standing public-function census.

## Evidence

The bare 2026-08-05 gate failed
`seon.public-contract-test/every-fresh-public-function-has-a-complete-contract`
at `test/seon/public_contract_test.clj:81` with exactly:

```clojure
[#:seon.public-contract{:name index-step,
                        :schema nil,
                        :file "src/seon/search.clj"}]
```

The same sole missing contract appeared at pre-rename commit `401fd300e`.
This is separate from [[search-index-property-collides-with-process-index-id]]:
that issue owns the conflicting meanings of the search key and the runtime
`apply-report!` input, while this issue owns a public Var with no declaration
at all. It is also distinct from [[public-contract-census-can-pass-with-no-subjects]],
which owns the census's false-green discovery mechanics.

## Owner

`seon.search/index-step` in `src/seon/search.clj` and the global contract
registry.

## Acceptance

The public function has a complete input/output contract grounded in its real
Flow call shape. The public-function census remains nonempty and reports no
missing contract for `index-step`.
