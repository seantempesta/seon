---
type: issue
status: open
severity: friction
created: 2026-09-25
tags: [issue, error, malli, contracts]
---

# Contract refusals describe a bounded string as "a string", hiding Malli's own message

## Problem

`seon.sci.eval/evaluate` with `:seon.cluster.eval/source ""` refuses at its input
contract (`:seon.cluster.eval/source` is `[:string {:min 1}]`,
`resources/seon/schemas/seon.cluster.eval.edn:6`) with:

> seon.sci.eval/evaluate refused argument 0 (0-based) at [:seon.cluster.eval/source]:
> expected a string, got a string. Fix: Supply a string at [:seon.cluster.eval/source].

`seon.error.problem/schema-expectation` (`src/seon/error.clj:759`) answers `"a string"`
for every `:string` schema before consulting Malli, so the `:min`/`:max`
properties vanish. Malli already owns the message:
`reference-code/malli/src/malli/error.cljc:127-134` answers
"should be at least 1 character". The same short-circuit applies to every
bounded `:int`, `:vector`, `:set`, `:map` case in that `case`.

## Evidence

Default JVM, 2026-09-25 ~21:25Z, lane fix-one-reader-errors: `(evaluate {... :seon.cluster.eval/source ""})`
returned the text above (thrown by the input contract; the reader never runs).

## Owner

`src/seon/error.clj` `schema-expectation`: use `me/error-message` (already
computed there as `declared-message`) when the schema carries properties Malli
describes, before the type-name `case`. Regression: an empty source through
`evaluate` names "at least 1 character". This unblocks the last row of
`one-reader-refusals-lose-the-bridge-pre-read-positions.md` (`""`).
