---
type: issue
status: open
severity: blocker
tags: [issue, web, database, flow, architecture]
---

# Capture dependencies when a lazy view unit activates

## Problem

Activating a lazy debug unit renders its producer for the immediate Datastar
response but does not capture or install that producer's database reads. The
view is rebound to a new active-unit fingerprint while retaining the prior
inactive subscription's dependency set. A later transaction that changes only
the active producer's inputs can therefore leave the already-open unit stale.

## Evidence

`seon.web.datastar/handle-view-unit!` calls `active-unit`, which invokes the
descriptor producer directly, serializes the result, and updates only
`::active-tokens`. `rebind-view` clones the old subscription authority and its
old `::dependencies` while changing the active fingerprint. No
`db/capture-reads` surrounds the activation producer and no observation result
is merged into the new subscription.

The retained tests prove that activation invokes the producer once, returns a
Datastar event, replaces an exclusive unit, and releases the active set. They
do not transact a fact read only by the newly active producer and prove a later
morph. This source audit was read-only, so it did not mutate the user's open
debug view to manufacture live evidence.

## Owner

The one lifecycle transition in `seon.web.view-unit`. Activation, observation,
rendering, serialization, suppression, and final-consumer cleanup must update
one normalized unit state atomically; `seon.web.datastar` should retain only
transport and socket ownership.

## Acceptance

- Activation captures the producer's exact immutable database reads and
  installs them before the active response becomes authoritative.
- A transaction changing only an activated producer's helper-indirected input
  updates that unit without reopening or refreshing the page.
- Deactivation and final-consumer close remove the unit's observations and
  retained serialized output.
- Two equivalent views activating the same normalized unit share one producer
  execution and one observation/output authority.
- A fresh full render and any incremental activation/update sequence converge
  to the same stable-ID DOM.

## Partial implementation — 2026-07-15

The first non-canvas HTML twin in each real debug catalog now exercises the
committed `seon.web.view-unit` lifecycle. `handle-view-unit!` snapshots one
immutable database value, attaches the view as a consumer, captures the
producer's reads, and returns the retained serialized stable-ID element.
`broadcast!` advances all shared units once before subscription fan-out and
distributes the same emitted bytes to every owning view, including views in
different active-fingerprint subscriptions. Final socket close calls the same
detach transition and removes the last observations and output.

Focused `seon.web.datastar-test` evidence is 37 tests/198 assertions. The new
regression proves one producer execution for two distinct subscriptions, both
consumer ids retained, no producer or output for an unrelated immutable
snapshot, one serialized update for a relevant snapshot, first-close
retention, and final-close release. A server-side gzip probe against the live
default debug feed observed a 24,853-byte initial frame and a successful 200
`datastar-patch-elements` activation response for the committed unit. No pod
restart or database write was used for that live proof.

The issue remains open because the other raw/HTML debug descriptors still use
the legacy activation path, and the whole-debug projection still captures an
ambient database value. Full acceptance requires migrating those consumers
and deleting the superseded path rather than expanding this transitional
opt-in.

Top-level integration review found that two views sharing one normalized
subscription initially contributed the same managed serialized element twice
to that subscription's patch. The transport now deduplicates managed elements
after consumer fan-in, and the focused lifecycle regression covers that common
same-subscription case without changing cross-subscription delivery.

The ordinary agent header is now the second production consumer and proves the
same lifecycle for an always-demanded page unit. Feed attachment reconciles it
before first paint against the same immutable database value used by page
composition; reconnect reuses the retained output, structural full-page and
standalone header patches converge through one DOM id, and historical feeds
inherit no demanded live unit. Its former declared-attribute veto and custom
captured-observation branch are deleted. Focused evidence is 38 tests/226
assertions in `seon.web.datastar-test` and 17 tests/73 assertions in
`seon.ui.agent-view-test`.

Default-cluster browser and server-side gzip evidence for this second consumer
is still pending a coordinated restart after concurrent source lanes converge.
The restart attempted during implementation failed closed in watcher
reconciliation when source changed after artifact publication; no process was
started outside the operator. This does not close the issue: the remaining
debug descriptors and whole-debug projection still own the legacy path named
above.
