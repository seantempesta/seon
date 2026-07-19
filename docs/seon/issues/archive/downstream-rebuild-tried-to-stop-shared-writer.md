---
type: issue
status: resolved
severity: blocker
tags: [issue, component, flow]
---

# Keep downstream rebuilds off the shared writer

## Problem

The first `bin/acme up` after publishing a changed writer artifact attempted a
local `rebuild-writer` stop even though ACME declares the default cluster's JVM
writer as an external dependency. ACME has no local writer generation to stop,
so the operator failed with `containment-uncertain`; an identical second `up`
succeeded only because the first attempt had already published the new
manifest.

## Evidence

The failed first start published writer digest `502798270f…`, matching the
ready default writer, then passed `#{writer}` to ACME's local
`clean-or-force!`. Immediate status showed the external writer healthy and the
new ACME manifest current. The same command then started ACME normally.

`seon.dev.cli/reconcile-development!` decided to stop the writer from the
artifact change set alone. It did not distinguish an operator-owned writer
record from the external writer dependency in ACME's process specifications.

## Owner

The source-development reconciliation sequence in `seon.dev.cli`.

## Acceptance

- A changed writer artifact stops the writer when the selected target owns a
  live managed writer.
- A downstream target never sends a stop request for its external writer.
- Default and ACME remain ready together through an ACME restart.
- ACME shutdown leaves the default writer, watcher, and pod unchanged.

## Resolution

Commit `d15097fa` requires a live target-owned writer before adding the
`rebuild-writer` transition. External dependency compatibility remains checked
by the existing process specification and readiness gate.

## Verification

- Focused `seon.dev.cli-test` passed 38 tests and 100 assertions, including a
  deterministic changed-artifact case that permits only the reader stop for an
  external-writer target.
- ACME restarted cleanly with writer digest `502798270f…`; its watcher and pod
  were replaced while default watcher PID `61610`, writer PID `61939`, and pod
  PID `62040` remained unchanged and ready.
- Agent `curly-lizards-shop`, created through ACME's ordinary `POST /agents`,
  remained addressable and rendered through its Datastar feed after restart.
- Normal `bin/acme down` retired only ACME's watcher and pod.
