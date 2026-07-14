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
