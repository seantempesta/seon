---
type: issue
status: open
severity: blocker
tags: [adoption, reload, operator, platform, class/absence-as-health]
created: 2026-09-17
---

# A first-party namespace under `resources/` is never reloaded by development adoption

## Problem

`seon.operator.state` lives at `resources/seon/operator/state.clj` (the
operator script loads it as a resource). Development adoption
(`bin/seon init --dev default`) reloads changed namespaces under the source
roots only, so a change to that file is never reloaded into the running JVM.
On 2026-09-17 04:25Z adoption on default (pid 33583, booted 00:57Z) reached
"development reload seon.operator" and failed with

```
Syntax error compiling at (seon/operator.clj:280:3).
No such var: state/cleanup-root-under-lock!
```

because `seon.operator` (reloaded) now calls
`seon.operator.state/cleanup-root-under-lock!`, added by `c4d1be3ac`
(reset-is-total, 01:35Z) to a namespace the JVM had loaded at boot and would
never reload. Log: `data/operator/operations/init-init-58066.log`.

Adoption had already passed branch publication (the earlier blocker, an
invalid issue note, was fixed at `9d2d2d5da`), so this was the next gate.

## Why it is a class member

The reload set is derived from "changed files under the source roots"; a
first-party namespace outside those roots is invisible to it. The check
reads the absence of that namespace from the changed set as "nothing to
reload" — absence of signal as health.

## Fix (choose one, at the root)

1. Move `seon.operator.state` under `src/` and give the operator script its
   own thin resource loader, so every first-party namespace is under one
   root the reload set covers (recommended: one rule, no exception list).
2. Declare the resource-hosted namespaces as a publication input class whose
   change reloads them (accretes a second root; needs the same treatment
   for any future resource-hosted namespace).

Either way, the regression: edit a resource-hosted namespace, run a
development adoption, assert the reloaded consumer resolves the new var.

## Repair on 2026-09-17

`bin/seon reset --force` (owner-authorized: "reset the system if you need
to"); a fresh JVM boots from the tree and loads the current file.
