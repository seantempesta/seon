---
type: issue
status: resolved
severity: blocker
tags: [issue, flow, database, pod]
---

# Validate the final pod coordinate before schema projection detach

## Problem

The first two fully contained default restarts classified the pod shutdown as
forced even though its containment generation and requested drain trigger were
exact. `drain-runtime-owners!` detached the active schema projection before it
resolved the final database coordinate. The later coordinate validation then
looked up its symbolic schema in the intentionally empty projection and failed
with `:malli.core/invalid-schema`.

Writer shutdown remained clean with one released database, and watcher
shutdown remained clean. The aggregate correctly refused to claim a clean
restart because the pod returned `:seon.client/quiesced? false`.

## Owner

`seon.client/drain-runtime-owners!` owns the inverse order. It must resolve the
final immutable coordinate while the active projection is valid, then detach
the projection, release the connection, and return generation-bound evidence.

The source audit in
`docs/prds/runtime-reliability/research/schema-generation-lifecycle-audit-2026-07-15.md`
rules out a broader already-executing-wrapper race. Malli `0.20.0` compiles and
closes over the function schema validators when it installs a wrapper; it does
not resolve the symbolic schema through the active registry again during output
validation. Unstrumenting the live var and activating the empty projection
therefore affects future calls, not a wrapper already executing. The observed
fault was the new `db/head-coordinate` call made after detach.

## Acceptance

- Focused lifecycle proof asserts coordinate resolution precedes projection
  detach.
- A fully contained default restart reports the pod, writer, watcher, and
  aggregate clean.
- The pod result carries its exact process generation and final complete
  database coordinate; writer evidence reports every attachment released.
- A following restart remains clean and preserves the same database
  attachment without replay or replacement.

## Resolution

Commit `09bd9c0f` resolves the final coordinate before activating the empty
schema projection and adds a focused 1-test/13-assertion ordering regression.
The maintained Malli source audit at `b0c69616` confirms active wrappers retain
compiled validators, so this is the owning inverse-order fix rather than a
workaround for a dynamic registry lookup.

The default cluster then completed two consecutive contained `bin/seon
restart` transitions as clean. The second replacement published watcher
generation `92bc9b6a-0ccc-41a0-af1c-9404c505aed8`, writer generation
`d4acbc73-1131-4507-a414-cb7d263ccb4f`, and pod generation
`58c773a2-59d6-49af-ae77-7ef6e9568e8d`. The database retained attachment
`54b5b7e7-51fb-3220-b079-81a81914d86f/:db` and advanced normally to
transaction `536871003`; no replacement database or replay path appeared.
