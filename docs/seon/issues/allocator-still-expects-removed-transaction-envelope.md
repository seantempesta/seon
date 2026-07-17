---
type: issue
status: active
severity: blocker
tags: [issue, database, flow, agent]
---

# Allocator still expects the removed transaction envelope

## Problem

The one CLJS generated-identity allocator now sends its generator-policy read
and allocation transaction through `seon.db` at one immutable database value.
Its response handling still predates that facade: it recognizes success only
through `:seon.db/ok?` and reads failures from a nested `:seon.db/error` map.
The current `seon.db/transact!` instead returns a native-shaped transaction
report on success and a direct `:seon.error/*` value on failure.

The direct error conversion also omits the protocol's
`:seon.db.protocol/generated-candidate`. Without that ordinary value the
allocator cannot distinguish its own generated-value collision from an
unrelated transaction failure and therefore cannot safely retry.

## Evidence

- `seon.db/submit-transaction!` selects the native report keys and adds only
  `:seon.db.id/eids` plus optional recovery evidence.
- `seon.db/response-error` constructs a direct `:seon.error/*` value but does
  not retain the generated candidate from a transaction error response.
- `seon.db.id/allocate-attempt!` still branches on `:seon.db/ok?` and its
  collision classifier reads `[:seon.db/error :seon.error/data]`.

## Owner

`seon.db/transact!` owns the one public transaction result shape.
`seon.db.id/allocate!` consumes that shape and adds allocated ids; neither may
restore a compatibility envelope.

## Acceptance

- A successful remote allocation consumes the native transaction report and
  returns the allocated identity values and entity ids.
- An exact generated-candidate collision carries the protocol candidate into
  the direct error value and retries the complete pure transaction builder.
- An unrelated transaction error returns unchanged and never retries.
- No `:seon.db/ok?` or nested `:seon.db/error` compatibility result is added to
  `seon.db/transact!`.
- Focused proof exercises success, exact collision retry, retry exhaustion,
  and unrelated failure through the real facade response shapes.
