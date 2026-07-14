---
type: issue
status: resolved
severity: blocker
tags: [issue, database, pod]
---

# Preserve the protocol stale-basis discriminator

## Problem

The remote database writer rewrites a rejected protocol response into an
exception whose error-kind key belongs to `seon.db.replica`, so callers cannot
recognize the protocol's stale-basis result and perform their bounded retry.

## Evidence

A fresh current-ACME pod reached a ready writer and replica, then failed its
boot-managed `seon.state/reconcile!` with `Transaction basis is stale` instead
of retrying. `seon.db.replica/rejected-response-error` stores the discriminator
as `:seon.db.replica/error-kind`, while `seon.state/stale-basis-envelope?`
correctly reads `:seon.db.protocol/error-kind`. The ACME pod log therefore says
`reconcile! transact failed` rather than the bounded-retry exhaustion message.

## Owner

`seon.db.replica/rejected-response-error`, the one remote response-to-error
translation boundary.

## Acceptance

A remote stale-basis rejection retains
`:seon.db.protocol/error-kind`, expected basis, and current basis through the
public transaction error envelope; `seon.state/reconcile!` recognizes it and
retries rather than treating it as an ordinary transaction failure.

## Resolution

`seon.db.replica/rejected-response-error` now keeps the response discriminator
under the protocol-owned `:seon.db.protocol/error-kind` key, alongside the
expected and current bases. This restores the existing one-mechanism contract:
the public database envelope compacts but does not rename protocol data, and
`seon.state/reconcile!` can take its already-bounded stale-basis retry branch.

The focused remote-writer regression drives a real rejected wire response and
passes 16 tests with 91 assertions in `seon.db.replica-test`. Before the fix,
the new assertions reproduced the key drift with two failures.
