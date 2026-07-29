---
type: issue
status: open
severity: friction
tags: [issue, operator, runtime, config]
---

# Refresh instrumentation before the fresh operator calls start

## Problem

Adding a cluster to an already-running development JVM can enter
`seon.cluster/start!` through a stale Malli wrapper before the fresh operator
reapplies instrumentation. A source-level request-schema change can therefore
make the operator refuse a valid current request until the whole JVM is
restarted.

## Evidence

At the 2026-07-29 checkpoint, current source declares `start!` against
`:seon.boot/start-request`, which admits `:seon.config/manifest`. The existing
default JVM still wrapped the var with the prior `:seon.boot/overrides`
contract. This command:

```bash
bin/seon start checkpoint-audit-stale-instrumentation
```

failed before boot with:

```text
seon.cluster/start! violated its contract (invalid-input):
[#:seon.config{:manifest ["disallowed key"]}]
```

The failure data names schema `:seon.boot/overrides`, while current source at
`src/seon/cluster.clj` names `:seon.boot/start-request`.

`script/seon/fresh_operator.clj` constructs its launch form in this order:
require current namespaces, call `seon.cluster/start!`, then call
`seon.instrument/apply!` using the new instance's config. That ordering cannot
refresh the wrapper that guards the first call. The archived
`operator-stop-crashes-instead-of-sigterm-fallback.md` records the same stale
instrumented-schema class on the stop seam; the current failure is the add
path and has no fallback.

## Owner

The fresh operator's prepl launch operation and `seon.instrument` hot-reload
discipline. Refresh or remove the stale process-global wrapper before the
current `start!` request enters it, using config facts from an already-live
cluster when needed rather than inventing a second instrumentation mode.

## Acceptance

- Change `start!`'s input schema in a live development JVM, reload the
  namespace, and add a scratch cluster without restarting the JVM.
- The first `start!` call is checked against the current schema, not a wrapper
  installed before the edit.
- The operator still applies the selected new cluster's instrumentation dial
  after config facts commit.
- A regression exercises a genuinely stale wrapper; a fresh-process-only test
  is insufficient.
