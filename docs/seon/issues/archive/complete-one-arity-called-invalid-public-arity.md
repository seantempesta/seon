---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, cljs]
---

# Keep the one-argument completion call inside its contract

## Problem

The public one-argument `complete` implementation called its own two-argument
public arity with a nil result ref. Its Malli contract correctly requires the
second argument to be a Datahike entity ref, so live instrumentation rejected
the ordinary `(complete "result")` form before lifecycle work ran.

## Evidence

A real agent successfully activated and completed its plan step, then
`(seon.agent.lifecycle/complete "17*19=323")` failed with
`malli/instrument-input`: argument 1 expected `:seon.db/ref`, got nil. The
one-argument body was `(complete result nil)`, crossing the public two-argument
contract with an internal absence value.

## Owner

The one `seon.agent.lifecycle/complete` public function and its private shared
implementation.

## Acceptance

- Both documented public arities delegate to one private implementation.
- The one-argument form never calls the two-argument public contract with nil.
- A fresh instrumented agent completes in its first response.

## Resolution

Resolved by `7a76959a`. Both public arities delegate to one private
implementation. The focused lifecycle suite passes 6 tests/36 assertions, and
the final fresh agent's one-argument `complete` call succeeded on its first
response.
