---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, operator, runtime]
---

# Register the generic render value schema before instrumentation

## Problem

Adding a scratch cluster to the live fresh JVM fails before `start!`.
The operator's pre-start instrumentation refresh cannot compile
`seon.render.value/sample` because its contract references the unresolved
`:seon.render/value` schema.

## Evidence

On the clean source tree at `bc204722d`:

```text
bin/seon start seam-reaudit-20260729
✗ The cluster rejected the prepl operation.
:malli.core/register-function-schema
ns=seon.render.value name=sample
:malli.core/invalid-schema
schema=:seon.render/value
```

The failure is inside `script/seon/fresh_operator.clj`'s
`refresh-instrument-form`, before its generated add form calls
`seon.cluster/start!`. No scratch-cluster advertisement was published.
This blocked the required refusal-seam re-audit.

## Owner

The fresh schema population and the instrumentation collection boundary.
`:seon.render/value` is a genuinely polymorphic generic-render input, but its
named contract must resolve in the same registry that instruments
`seon.render.value/sample`.

## Acceptance

- A fresh instrumentation apply compiles every contracted public function with
  zero unresolved schema references.
- `bin/seon start <scratch-name>` succeeds against an already-running cluster
  whose JVM predates the latest source reload.
- A regression drives the generated add form through the real pre-start
  refresh, not only a fresh-process function-schema check.
- The refusal-seam scratch-cluster re-audit can then execute through the
  current transaction encoder.

## Resolution

Resolved by `bf9d9425e` (`Load schemas before fresh instrumentation refresh`).

The generated add form now calls `seon.schema.edn/load!` before refreshing
process-global instrumentation and before entering `seon.cluster/start!`.
This preserves the stale-wrapper repair from `79d02f6fd`: current function
contracts are still recollected before the first start call, but every named
schema they reference is present first.

The correctly ordered refresh exposed a second, latent contract defect one
step later. `seon.cluster/arm-agents!` adds `:seon.render.web/view` to the
started instance, while the closed `:seon.boot/instance` schema did not admit
that key and no named view schema existed. Instrumented `start!` therefore
booted the scratch web server and then refused its own output. The web schema
family now declares the exact six-key process-local view:
render, pages, and completion channels use the existing
`:seon.flow/channel` predicate schema; registration uses the existing atom
predicate schema; page fan-out uses the existing mult predicate schema; and
the run process uses its non-empty string schema. `:seon.boot/instance`
references that named shape as an optional armed-layer key. No opaque value
was widened to `:any`.

`fresh-process-loads-schema-before-refresh-and-start` runs in a real child JVM.
It starts an isolated anchor, restores a registry state missing
`:seon.render/value`, evaluates the production generated add form, and proves
that schema load → instrumentation refresh → instrumented `start!` returns
`"scratch"` with a published web URL. Before the fix it failed at
`:malli.core/register-function-schema`; after the ordering fix it exposed the
missing view key; after both repairs it instrumented 364 vars and booted green.
The genuinely stale-wrapper regression remains alongside it.

Proof on 2026-07-29:

```text
bin/test seon.dev.fresh-operator-test
Ran 6 tests containing 28 assertions.
0 failures, 0 errors.

bin/test
Ran 549 tests containing 2313 assertions.
0 failures, 0 errors.
```
