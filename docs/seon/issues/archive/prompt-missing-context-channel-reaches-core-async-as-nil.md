---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, schema, testing]
---

# Validate prompt input before retained-context acquisition

## Problem

`seon.cluster.prompt/prompt` declares `:seon.render/context-channel` as a
required member of `:seon.cluster.prompt/request`, but an uninstrumented caller
could omit it and reach `clojure.core.async/>!!` with nil. The resulting
protocol exception named core.async rather than the missing input contract.

## Evidence

The retained fast-gate failure repeatedly recorded
`No implementation of method: :put! ... for class: nil`. The request schema in
`resources/seon/schemas/seon.cluster.prompt.edn` requires the channel, while
`src/seon/cluster/prompt.clj` previously dereferenced the key without validating
the declared request shape.

## Owner

`seon.cluster.prompt` owns validation before any retained-context channel I/O.

## Acceptance

- Omitting the channel refuses before `seon.render/acquire-context!`.
- The refusal is a registered `:seon.error/value` naming
  `:seon.render/context-channel` and the `:seon.cluster.prompt/missing-input`
  rule.
- Valid prompt requests retain the existing acquisition path.

## Resolution

The containing path-limited repair validates the complete declared prompt
request through `seon.schema/explain-candidate-value`, structurally derives
Malli's missing-key findings, and throws only the already-valid flat refusal
that the loop records. The regression deliberately omits the context channel
and proves the refusal value validates against `:seon.error/value`.
