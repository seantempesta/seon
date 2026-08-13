---
type: issue
status: open
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
