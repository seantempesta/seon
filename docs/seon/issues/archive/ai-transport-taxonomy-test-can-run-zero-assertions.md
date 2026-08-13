---
type: issue
status: resolved
severity: friction
tags: [issue, ai, test, class/n2, wave/ai-provider-integrity]
---

# Make the AI transport taxonomy test assert its premise

## Problem

The test for JDK transport-phase evidence puts every assertion behind a
conditional. Any result other than the expected transport failure makes the
test pass with zero assertions.

## Evidence

- `test/seon/ai_test.clj:658-671` makes a real request to loopback port 1 and
  wraps all three assertions in `(when (= :seon.ai/transport-failure ...))`.
- `test/seon/ai_test.clj:673-680` demonstrates the honest neighboring shape by
  asserting the error kind before inspecting its evidence.

## Owner

The deterministic transport-leaf test fixture in `seon.ai-test`.

## Acceptance

The test deterministically produces the intended JDK failure classification,
asserts that classification first, then asserts transmission and disposition
evidence. No branch can complete with zero assertions.

## Resolution

Resolved by commit `270a66fd4`.

The fixture now asks the OS for an ephemeral loopback port, closes its listener,
and invokes the production HTTP boundary with explicit no-auth configuration.
The test first asserts the resulting `:seon.ai/transport-failure` and validates
the complete typed error value before inspecting transmission and disposition
evidence. It also retains the counterexample: changing the evidence to
`:transport-unknown` makes backup failover ineligible.

Source verification confirmed that no assertion remains conditional on the
expected error kind. The focused 2026-08-13 run executed
`seon.ai-test/the-leaf-records-phase-from-the-jdks-own-taxonomy` successfully.
