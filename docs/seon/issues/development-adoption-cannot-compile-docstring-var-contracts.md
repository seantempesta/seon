---
type: issue
status: open
severity: blocker
tags: [issue, schema, instrumentation, wave/publication-velocity]
---

# Development adoption cannot compile docstring Var contracts

## Problem

Same-tree development adoption reaches instrumentation and refuses
`seon.dev.docstring/check-file`: its contract references
`#'seon.dev.docstring/check-file-request-schema` and
`#'seon.dev.docstring/check-response-schema`; recompilation reports the
request Var as `:malli.core/invalid-schema`. The worker cannot re-arm after
this failed adoption, so unrelated following tests do not execute.

## Evidence

`tmp/publication-dissolution/morning-publication-head-retry.log`:
`seon.cluster.publication-host-test` boots, records the producer generation,
passes the live-producer guard, then fails in `seon.instrument/apply!`.
The failure includes `:seon.instrument/fn seon.dev.docstring/check-file`
and `:diagnostic-offending #'seon.dev.docstring/check-file-request-schema`.
The subsequent re-arm fails on the same Var contract with 218 installed
wrappers versus 1494 armed at initialization. The test's finally stops its
scratch cluster. No second JVM or cold gate was launched.

## Owner

Development loaded-definition adoption and schema contract compilation.
`src/seon/schema.clj` and its implementation are held by the bridge walker;
the publication lane did not edit them or the docstring owner.

## Acceptance

Run `seon.cluster.publication-adoption-test` explicitly in its own gate.
A freshly booted host records its generation and adopts the same tree;
contract arming succeeds after adoption, including Var-based contracts.
The test is long and excluded from implicit fast/bare/platform selection.
