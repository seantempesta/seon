---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, error, durability]
---

# Keep evaluation failures inside total admission

## Problem

The failed-evaluation path prints raw SCI exception data directly into the
durable receipt. That data can contain a volatile call stack with live SCI
namespace objects, bypassing the bounded, dereference-free admission boundary.

## Evidence

`src/seon/sci/eval.clj:269-281` applies `pr-str` directly to `ex-data`, and
`src/seon/sci/eval.clj:354-363` prints the resulting error value into
`:seon.cluster.eval/result-edn`. `src/seon/sci/admit.clj:99-106` states the
opposite invariant: bounded projection makes raw unbounded `pr-str`
unreachable. SCI installs the volatile call stack at
`reference-code/sci/src/sci/impl/utils.cljc:173-180`.

These paths remain unchanged after commits `21215ce28`, `ba723b2d1`, and
`a6d426983`.

## Owner

`seon.sci.eval`, using the existing `seon.sci.admit` total codec.

## Acceptance

- Every value stored in a failed evaluation's `result-edn` has first passed
  through the bounded admission projection.
- SCI call-stack data is converted to ordinary bounded data without
  dereferencing or printing live host objects.
- A regression with SCI location and call-stack data proves the durable result
  is finite, readable EDN within configured caps.

Resolved by `1c7abb6a7`: the failure value now passes through
`seon.sci.admit/admit` before `result-edn` is returned for persistence.
