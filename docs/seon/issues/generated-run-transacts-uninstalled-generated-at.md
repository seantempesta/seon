---
type: issue
status: open
severity: blocker
tags: [issue, schema, runtime, wave/live-drive-context]
---

# Stop generated runs from transacting an uninstalled generated-at attribute

## Problem

The Drive 1 cluster attempted to transact
`:seon.cluster.run/generated-at`, but that attribute was absent from the
installed schema. The writer rejected the transaction instead of recording a
valid generated-run fact or a typed domain refusal.

## Evidence

`tmp/drive-1-root/data/clusters/default/logs/seon.log` records at
`2026-08-14T05:39:52.591002Z`:

```text
Bad entity attribute :seon.cluster.run/generated-at ... not defined in current schema
data: {:error :transact/schema, :attribute :seon.cluster.run/generated-at, ...}
```

Current source and schema discovery contain no declaration for that attribute.
The drive therefore crossed a writer boundary with transaction data its own
published program could not store.

## Owner

The generated-run/Drive 1 transaction constructor and its publication basis.

## Acceptance

A fresh drive never submits an uninstalled attribute. If generation time is
derivable, the transaction omits it; if it is genuinely required, its one
schema declaration is published and installed before the writer sees it. A
regression proves absence of `:transact/schema` errors during the full opening.
