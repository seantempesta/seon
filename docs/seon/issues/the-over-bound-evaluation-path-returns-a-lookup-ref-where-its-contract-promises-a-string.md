---
type: issue
status: open
severity: blocker
tags: [sci, evaluation, contract, admission, platform]
created: 2026-09-17
---

# The over-bound evaluation path returns a lookup ref where its contract promises a string

## Observed on a fresh default (pid 66052, 2026-09-17 04:22:54Z, ~1 minute after boot)

Fault entity `7710efbc…`, kind `:seon.instrument/contract-violated`, function
`seon.sci.eval/evaluate`, arm `:output`:

```
seon.sci.eval/evaluate refused return value at [:seon.cluster.eval/error]:
expected a string, got a lookup-ref vector.
Fix: Pass a string id at [:seon.cluster.eval/error].
Contract: :seon.sci.eval/evaluation.
```

Arguments at the fault: `#:seon.sci.admit{:reason :over-bound, :bytes 3996}`
— the evaluation was refused by admission for exceeding its byte bound, and
the refusal path built the evaluation value with `:seon.cluster.eval/error`
as a lookup ref (`[:seon.error/id …]`-shaped) instead of the string id the
schema declares. Every over-bound evaluation therefore fails its own output
contract, and the fault carries 2,219,064 bytes of evidence
(`:seon.error/data-size`), which is its own ugliness.

## Where

`src/seon/sci/eval.clj`, the admission-refusal arm of `evaluate` (search for
`:over-bound` and the construction of the evaluation map's
`:seon.cluster.eval/error`); the contract is `:seon.sci.eval/evaluation`
(`resources/seon/schemas/seon.sci.eval.edn`). Decide at the schema which
grammar the key carries — the error entity's string id, per the contract —
and make the refusal arm supply exactly that; do not widen the contract.

## Regression

An evaluation whose source exceeds the admission byte bound returns a value
that satisfies `:seon.sci.eval/evaluation` (armed contract), with
`:seon.cluster.eval/error` a string id resolving to the recorded error, and
records no contract-violation fault.
