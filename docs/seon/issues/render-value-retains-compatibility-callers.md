---
type: issue
status: open
severity: cleanup
tags: [issue, deletion, render]
---

# Delete the two render-value compatibility calls

## Problem

`seon.render.value` retains two explicitly labeled compatibility functions
beside their surviving owners. Each has one caller, so the wrappers add a
second call shape and, for structural fitting, a private legacy render profile
with magic limits.

## Evidence

- `src/seon/render/value.clj:13-17` defines `transacted` as a compatibility
  call using `requiring-resolve`; its sole caller is
  `src/seon/error.clj:875-880`.
- `src/seon/render/value.clj:329-353` defines `print-node-window` as a
  compatibility call into `seon.print/fit`, synthesizing
  `:seon.render.profile/legacy-window`, a 1,048,576-token budget, and a
  legacy-caller refusal.
- Its sole production caller is the result-blob projection at
  `src/seon/cluster.clj:283-290`; `seon.cluster` already requires `seon.print`
  at `src/seon/cluster.clj:49`.
- Exact symbol searches find no other production callers. `seon.render` does
  not require `seon.error`, so repointing the error caller does not introduce
  the cycle the dynamic wrapper would otherwise suggest.

## Owner

`seon.render` owns transaction-shape selection and `seon.print` owns
profile-derived structural fitting.

## Acceptance

Repoint both callers to the owning functions and delete the compatibility
functions. The result-blob path uses a declared render profile rather than a
private legacy profile, focused error/result rendering tests remain green, and
search finds no `legacy-window` or compatibility call in `seon.render.value`.
