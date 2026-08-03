---
type: issue
status: resolved
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

## Resolution

Commit `0f704f589` fixes both sides of the cold acquisition defect at the one
SCI install seam. `acquire!` now reads every published namespace row, including
the source-less rows created by `seon.bootstrap/seed-tx`. For compiled
first-party namespaces, only Vars named by declared refer facts become SCI
Vars, and each such SCI Var holds the live JVM Var rather than a copied root.
SCI can therefore install the resolver binding while hot reload still reaches
the current compiled root. No bootstrap name list was added.

The recurring restart falsifier failed first because cold acquisition returned
an empty refer map. It now passes one real process restart with 32 assertions:
`help`, `dir`, and `doc` retain their exact `seon.bootstrap/*` targets, the
reopened `help` macro expands, and the prior agent function executes without
replay. The adjacent SCI gate passed 44 tests / 221 assertions and the turn
gate passed 46 tests / 264 assertions.

A new isolated operator root at `tmp/cold-reopen-proof-root` published digest
`695e2d2deb21474d2233858f7cd163eadc6b18ff7a33d464395efdfa948c4662`,
booted, rendered the completed bootstrap, stopped gracefully, and reopened the
same branch in PID `18391`. The cold JVM reported READY with 463 instrumented
Vars; `/` rendered `seon · root` with the root agent idle. Direct inspection of
that cold context returned all three bootstrap refers, a callable `help`, and
the pre-restart `largest` result `{:label "x", :amount 9}`. Probe 6's prior
mid-pass evidence already established that no partial package was published;
this closes its blocked cold re-derivation half.
