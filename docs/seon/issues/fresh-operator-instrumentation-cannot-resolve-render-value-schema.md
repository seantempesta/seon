---
type: issue
status: open
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

## Attempt-3 class correction

The first resolution was caller-local and therefore incomplete.
`fresh_operator.clj` generates more than one form that applies
instrumentation: the anchor launch form and the add form. Only the add form
loaded the schema EDN population, so a fresh anchor could still collect
contracts before `:seon.render/value` existed.

Commit `b69310347` moves the dependency to the one owner:
`seon.instrument/apply!` now calls `seon.schema.edn/load!` before every
`mi/clj-collect!`. The add form's schema require and load call were deleted;
neither operator form now knows the ordering rule. The stale-wrapper refresh
from `79d02f6fd` remains in place.

The child-JVM regression now evaluates the production generated launch form
with `:seon.render/value` absent, waits for the anchor's real readiness
publication, removes the schema again through that anchor's io-prepl, and
drives the production operator add path. Both paths complete schema load →
instrumentation refresh → instrumented cluster start, and the scratch cluster
publishes its web URL.

Proof on 2026-07-29:

```text
bin/test seon.instrument-test seon.dev.fresh-operator-test
Ran 14 tests containing 59 assertions.
0 failures, 0 errors.

bin/test
Ran 549 tests containing 2314 assertions.
0 failures, 0 errors.
```

The requested literal shared-tree proof is not yet countable. The existing
`default` process (PID 61316, start instant
`2026-07-29T10:07:59.839Z`) predates the new schema population. After
hot-reloading `seon.instrument`, its live state reports
`{:candidate true, :active false}` for `:seon.render/value`: `load!`
correctly contributes the resource candidate, but Malli's stable registry
must read the already-published active database projection, which does not
contain that later schema. Consequently the exact command
`bin/seon start instrument-class-audit-20260729` still refuses
`seon.render.value/sample`; it creates no scratch advertisement.

Replacing, stopping, or mutating that live process would cross another
session's runtime boundary. Per the lane stop rule, this issue is reopened
until the owner makes a genuinely fresh anchor available and the exact
literal command can be rerun. The source and recurring full gate are green;
only that shared live-process acceptance proof remains.
