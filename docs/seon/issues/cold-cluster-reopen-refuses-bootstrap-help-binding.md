---
type: issue
status: open
severity: blocker
tags: [issue, cluster, sci, render]
---

# Make a cold cluster reopen acquire every published first-party binding

## Problem

A stopped cluster cannot reopen from its published branch. Cold SCI acquisition
refuses the first-party `seon.bootstrap/help` binding before the cluster becomes
ready. This blocks the render crash-recovery proof and any other proof that
requires reopening the same database facts in a fresh JVM.

## Evidence

- Two isolated operator roots reproduced the same failure after a clean
  `reset --force`, successful start, graceful or forced stop, and cold start.
- The second root contained no agent-authored renderer. Its cold start failed
  with `seon.bootstrap/help does not name an installed SCI Var` from
  `sci.core/install-namespace-bindings!`, called by `seon.sci.eval/acquire!` at
  `src/seon/sci/eval.clj:1068`.
- Before the forced stop in the render recovery probe, the database advanced
  from basis transaction `536870953` to `536870954` while the proc-owned
  package remained at revision `1` and basis transaction `536870953`. No
  partial package was delivered. The atomicity half is therefore observed;
  the cold re-derivation half is blocked at acquisition.

## Owner

The cold `seon.sci.eval/acquire!` namespace-binding installation path and the
published first-party program rows it consumes.

## Acceptance

An isolated cluster starts, stops during a render pass, and reopens from the
same branch without source republishing. Acquisition installs
`seon.bootstrap/help`, the render graph re-derives the page from the advanced
database value, no run re-executes, and the first delivered package is a
complete keyframe with no partial predecessor.
