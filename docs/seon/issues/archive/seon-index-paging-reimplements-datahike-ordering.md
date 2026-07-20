---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Make bounded index paging a Datahike capability

## Resolution

Datahike `343650464dcb8e6007bede7de5cdf9bfaad21310` owns one eager,
bounded `index-page` capability using its native current and temporal index
comparators. Seon now passes the pinned database value and ordinary request
options to `d/index-page`; it retains only protocol datom conversion and the
coordinate/index/direction/history cursor seal.

The dependency proof covers forward and reverse concatenation, history
polarity, same-transaction retract/add rows, lookup refs, ref values,
content-equal byte arrays, absent/wrong-prefix cursors, exact limits, bounded
`limit + 1` realization, and result-weight rejection. It passes 96 tests and
450 assertions. The focused Seon protocol and writer integration proof passes
21 tests and 200 assertions, including protocol-classified wrong-prefix
failure.

## Original problem

The first Seon index-page interpreter sought and compared Datahike datoms
itself. That duplicated dependency-owned index semantics and was incorrect for
temporal retractions, byte values, and lookup references.

## Evidence

- A retraction stores a negative internal transaction field; using it as a
  public cursor makes the next seek reject the transaction ID.
- A retract and add of the same E/A/V in one transaction share the same public
  transaction ID, so `added?` must participate in the exact cursor order.
- Transit reconstructs byte arrays, while ordinary Clojure equality compares
  those arrays by identity.
- Datahike resolves lookup-ref components before seeking, so a raw request
  prefix does not equal the resulting numeric datom components.
