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

The durable fact owner is also implemented independently in
`seon.db.restore`. It colocates the compact completion identity and all thirteen
architecture payload attributes, then records or proves one exact completion
through `seon.db`. An equal identity retry returns its completion transaction
coordinate without a write while that transaction remains the current head;
any required-value or optional-digest conflict fails closed. The operation
neither reads nor changes admission.

The later-head retry boundary is now implemented through the one database
protocol. A closed main-lineage request gives the authoritative writer a frozen
head and transaction id. It walks retained immutable commit maps, proves that
head remains an ancestor, excludes branch/force metadata commits that repeat a
parent's `t`, and requires exactly one ordinary transaction origin. The pod's
existing replica RPC owner carries the request; `seon.db` preserves typed
writer failures as a structured error value. An equal completion retry after a
later transaction now returns the original completion coordinate without a
write or a shadow commit attribute. Exact grounding is in
[[../../prds/database-lifecycle-recovery/research/restore-completion-transaction-coordinate-2026-07-15]].

The issue remains open because the restore-aware cold caller has not yet
composed the closed forced-main result, exact completion transaction and
read-back, reconstruction, and final admission. That caller must consume the
settled resolver rather than reimplement commit-graph inference. No contract is
represented by a new admission status here.

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
- An exact retry after a later branch transaction resolves and returns the
  original completion coordinate without writing or inventing stored commit
  metadata.

Focused writer proof passes four tests/12 assertions, including a real
repeated-`t` force commit, abandoned non-ancestor head, missing transaction,
wrong attachment, and branch-head alias rejection. Replay plus resolver proof
passes six tests/33 assertions. The request and response production Transit
gate passes ten tests/32 assertions, and the focused CLJS restore gate passes
six tests/34 assertions for the pod wrapper, original-coordinate result,
structured error kind, and zero-write retry.
