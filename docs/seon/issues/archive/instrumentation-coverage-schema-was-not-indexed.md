---
type: issue
status: resolved
severity: major
tags: [issue, cljs, schema, index]
---

# Index the instrumentation coverage contract

## Problem

Startup omitted `seon.instrument/coverage-gaps` from the indexed function
contracts because its Malli input used the unavailable `:coll` schema. The
warning was deterministic, and the missing contract weakened the coverage
check that reports live functions without instrumentation.

## Owner

The `seon.instrument/coverage-gaps` function contract and core program index.

## Acceptance

- The contract describes the native Datahike query relation as a set of
  string pairs.
- Malli resolves and validates the contract.
- Core indexing retains the function and its serialized contract.
- Existing instrumentation behavior remains green.

## Resolution

The input is now `[:set [:tuple :string :string]]`, matching the actual
Datahike relation passed by the warnings block. A live pod probe resolved the
complete function schema, accepted a representative relation, and returned an
empty result for an empty relation. The focused core-index gate passed 20 tests
and 145 assertions; the instrumentation-delta gate passed 11 tests and 128
assertions, both without failures, errors, or compiler warnings.
