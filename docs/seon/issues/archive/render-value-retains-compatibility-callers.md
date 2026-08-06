---
type: issue
status: resolved
severity: cleanup
tags: [issue, deletion, render]
---

# Delete the two render-value compatibility calls

## Problem

`seon.render.value` retained two explicitly labeled compatibility functions
beside their surviving owners. Each had one caller, so the wrappers added a
second call shape and, for structural fitting, a private legacy render profile
with magic limits.

## Evidence

- `seon.render.value/transacted` delegated by `requiring-resolve` to
  `seon.render/transacted`; its sole caller was `seon.error`.
- `seon.render.value/print-node-window` delegated to `seon.print/fit` while
  synthesizing `:seon.render.profile/legacy-window`; its sole production caller
  was the MCP result-blob projection in `seon.cluster`.
- `seon.cluster` already used `seon.print`, and `seon.render` does not depend on
  `seon.error`.

## Owner

`seon.render` owns transaction-shape selection and `seon.print` owns
profile-derived structural fitting.

## Acceptance

Repoint both callers to the owning functions and delete the compatibility
functions. The result-blob path uses a declared render profile rather than a
private legacy profile, focused error/result rendering tests remain green, and
search finds no `legacy-window` or compatibility call in `seon.render.value`.

## Resolution

Resolved by the audit-finding-4 commit that archives this issue. Error values
call `seon.render/transacted` directly, oversized MCP results fit through the
declared agent render profile with their blob requery identity, and exact
searches find no compatibility function or legacy profile.
