---
type: issue
status: open
tags: [agent, cljs, issue]
severity: friction
---

# Preflight repair consumed referred macros

## Problem

The compile-only symbol-repair gate expanded referred macros before the real
agent eval. A valid persisted namespace declaration could therefore be followed
by an undeclared-symbol failure for its own referred macro, preventing agents
from defining ordinary `cljs.test/deftest` tests.

## Evidence

Agent `plain-chefs-do` successfully committed
`(ns my.units (:require [cljs.test :refer [deftest is]]))`. Its next eval,
`h2p0yp4zru0n`, attempted `deftest` and failed with
`` `my.units/deftest` is not defined ``. A direct pair of `seon.eval/eval`
calls against the same bootstrap compiler state succeeded, isolating the
difference to `eval-batch!`'s preflight repair pass.

ClojureScript's self-host path executes macro loading and expansion during
analysis (`reference-code/clojurescript/src/main/cljs/cljs/js.cljs`,
`ns-side-effects`). The existing preflight exclusion already recognizes this
rule for loader forms but did not apply it to ordinary macro invocation heads.

## Owner

`seon.eval/preflight-eligible?` owns whether a form may enter compile-only
repair. It derives macro invocation from the current compiler namespace's
`:use-macros` and `:require-macros` maps and skips the repair trial while
leaving the one real eval and its normal error recording unchanged.

## Acceptance

- Focused proof shows referred macro invocations bypass compile-only repair
  while ordinary function-symbol repair remains enabled.
- A real execution child defines and records a passing `deftest` after its
  namespace declaration.
- The test and function remain available after a canonical pod restart.
