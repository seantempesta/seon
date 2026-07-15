---
type: issue
status: open
severity: blocker
tags: [issue, database, pod, flow]
---

# Keep restore publication closed through completion

## Problem

`seon.runtime.admission/publish-committed!` reconstructs and verifies the
committed projection, activates it, and immediately changes admission to
`:available`. Restore requires one completion transaction after successful
program reconstruction but before any executable boundary opens. Calling the
current function would admit agent, eval, web-command, schedule, wake, run-loop,
or ticker work before the durable restore fact exists.

Adding a restore-only registry, instrumentation pass, or `:restoring` phase
would duplicate the existing publication mechanism rather than close this seam.

## Evidence

`src/seon/runtime/admission.cljs` composes reconciliation, activation, and
`admit-generation!` in one synchronous function. `src/seon/client.cljs` calls it
during cold start and only afterward resumes hosts and starts runtime surfaces.
The exact restore-aware order and predecessor inputs are grounded in
`docs/prds/database-lifecycle-recovery/research/restore-blob-and-cold-reconstruction-contract-2026-07-15.md`.

## Owner

The one `seon.runtime.admission` committed-program publication transition,
composed by the existing `seon.client/start-runtime!` cold entry.

## Acceptance

- The existing `:publishing` state can retain one verified, activated projection
  while an owning cold transition commits restore completion.
- Completion read-back precedes the exact transition to `:available`.
- Ordinary boot and hot reload still use the same factored publication owner;
  there is no second registry, restore instrumentation path, force-open, or new
  admission status.
- Completion failure leaves admission closed and process retry reconstructs
  disposable projection state from committed facts.
- A crash after completion but before admission observes the same completion,
  does not repeat guarded force or overlays, and safely reconstructs runtime
  state before opening.
