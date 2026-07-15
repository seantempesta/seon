---
type: issue
status: open
severity: blocker
tags: [issue, database, pod, flow]
---

# Keep restore publication closed through completion

## Problem

Restore requires one completion transaction after successful program
reconstruction but before any executable boundary opens. Ordinary publication
must still admit immediately, while the restore-aware cold owner must retain
the verified projection under closed admission through completion read-back.

Adding a restore-only registry, instrumentation pass, or `:restoring` phase
would duplicate the existing publication mechanism rather than close this seam.

## Evidence

`src/seon/runtime/admission.cljs` now factors preparation from exact-generation
admission, and its ordinary wrapper composes both synchronously.
`src/seon/client.cljs` calls that wrapper during cold start and only afterward
resumes hosts and starts runtime surfaces. The exact restore-aware order and
predecessor inputs are grounded in
`docs/prds/database-lifecycle-recovery/research/restore-blob-and-cold-reconstruction-contract-2026-07-15.md`.

## Owner

The one `seon.runtime.admission` committed-program publication transition,
composed by the existing `seon.client/start-runtime!` cold entry.

## Current state

The admission-owner seam is implemented in place. `prepare-committed!` builds,
reconciles, activates, and retains one exact verified projection fingerprint
while the existing state remains `:publishing`. `admit-prepared!` opens only
when its preparation result names that retained fingerprint; a stale or
different generation remains closed. Ordinary publication immediately composes
those same two functions through `publish-committed!`.

The focused `prepared-publication-stays-closed-through-an-injected-completion`
regression places an injected completion-verification effect between those
halves and rejects a mismatched generation before admitting the exact prepared
result.

The issue remains open because the restore-aware cold caller is not yet
implementable: Slice 3 still owns the closed forced-main result and Slice 5
still owns the architecture-defined completion transaction and read-back.
Neither contract is inferred or represented by a new admission status here.

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
