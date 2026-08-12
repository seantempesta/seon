---
type: issue
status: resolved
severity: friction
tags: [issue, schema, diagnostics]
---

# Name the offending Var in Malli registration failures

## Problem

The function-contract registration gate prints a long generic
`:malli.core/register-function-schema` stack trace without naming the public
Var or authored schema that failed. A malformed contract therefore looks like
a registry-wide failure and forces a second diagnostic pass to find its owner.

## Evidence

`bin/test seon.schedule-test seon.operator-test` failed while registering
`seon.schedule/fire-call`, but the visible error did not name that Var. Reading
the exception data separately exposed both the qualified Var and the hidden
`:malli.core/infinitely-expanding-schema` error for `inst?`. The authored
source was `resources/seon/schemas/seon.schedule.fire.edn`'s indexed instant
attribute. This cost the maintenance-portfolio lane time after the original
two invalid return contracts had already been corrected.

## Owner

`seon.instrument/apply!`, at the boundary that collects and registers public
function contracts.

## Acceptance

- A public function with an invalid contract produces a bounded diagnostic
  naming its qualified Var and authored function schema.
- The diagnostic retains Malli's specific cause and offending nested schema.
- A regression drives the real instrumentation collection boundary and fails
  if the qualified Var is absent from the visible error.

## Resolution

Commit `fef44a5a8` makes registration Var-local: the exact Var and its authored
contract remain beside Malli's registration operation, and a failure is built
with `seon.error/diagnostic` from the deepest Malli cause. The focused
`seon.instrument-test/registration-failure-names-the-var-and-authored-contract`
regression passes.
