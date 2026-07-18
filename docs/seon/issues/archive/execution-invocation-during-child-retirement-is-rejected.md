---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, pod]
---

# Invocation during execution child retirement is rejected

## Problem

`seon.execution.host/stop-child!` marks a child `:retiring?` and requests its
normal shutdown, but `ensure-child!` still returns that ready child until its
exit callback removes it. An invocation in that short interval is sent to the
retiring process and returns "The execution child is no longer current."

## Evidence

A live root-child proof rendered a committed prompt successfully, called the
public supervisor stop function, and immediately invoked the same compiled
prompt. The immediate invocation returned the host's current-child error. One
second later, after the exit callback removed the child, the same invocation
started a fresh child and returned a 3,051-byte result at the identical
database value.

## Owner

`seon.execution.host/ensure-child!` owns selection of an existing ready child
or construction of a replacement. `stop-child!` and `exit-child!` retain their
existing normal-shutdown and removal responsibilities.

## Acceptance

An invocation arriving after a child enters `:retiring?` waits for that child
to exit or starts its replacement without being sent to the retiring process.
Normal idle retirement, explicit stop, cancellation, and unexpected exit each
settle every active or waiting invocation once. Focused host tests cover the
retirement interval, and live proof returns the same compiled prompt without a
caller retry.

## Resolution

Commit `31cfbb5e` gives each child an exit Promise and makes `ensure-child!`
wait through retirement before selecting or starting its replacement. Thirteen
focused host tests pass with 59 assertions. In the live default cluster, an
immediate prompt invocation after `stop-child!` returned from the replacement
without caller retry: both results were 107,036 characters at the identical
database value, with no error.
