---
type: issue
status: open
severity: friction
tags: [issue, test, operator, wave/dev-tooling-face-hygiene]
---

# Post-adoption check times out on the message contract test

## Problem

A successful development adoption's automatic check did not produce terminal
check evidence within its declared bound.

## Evidence

2026-09-15, default PID 69622. Hook publication
`a73853f1-699d-4f39-a521-6cc3449694db` converged to source commit
`6aa9d9dc-366b-543c-bb78-501397725cab`, then reported:

```text
check unavailable: :check-completion never arrived for :seon.test/check
within the declared :seon.test/check-time-limit-ms bound of 120000 ms.
Pending: my.message-test/a-contract-forbidden-argument-is-a-value-the-agent-reads
```

The four focused N1 tests subsequently passed through the same live
`seon.test/run` owner. This observation does not identify the cause of the
pending test or attribute it to any concurrent editor.

## Owner

`seon.test/check` and the named test's canonical fixture.
`src/seon/test.clj` and test-support were concurrently edited during this
observation; n1-render-substitution did not alter them.

## Acceptance

Reproduce the named test independently, identify the missing completion,
and prove the automatic post-adoption check returns terminal evidence.
