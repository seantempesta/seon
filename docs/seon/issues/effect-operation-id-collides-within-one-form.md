---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Give each effect in a form a distinct replay identity

## Problem

The run loop gives every effect executed by one form the same
`:seon.capability/op-id`. If a form performs two different transactions or
sends two different messages, the writer receives one request identity with
two logical transaction hashes. Durable replay correctly rejects that as an
identity conflict.

The current `(run, ordinal, epoch)` derivation identifies a form attempt, not an
effect request within that attempt.

## Evidence

- `src/seon/agent/driver.clj:672-696` constructs one request context for the
  form and stores one `receipt/receipt-id` as its op-id.
- `src/seon/agent/driver.clj:698-709` installs that single context in the SCI
  base used to evaluate the entire form.
- `src/seon/effect.cljc:199-201` reuses the ambient op-id for every request that
  omits one, as all `my.*` wrappers do.
- `src/seon/db/protocol.cljc:2077-2089` fingerprints transaction data, metadata,
  expected database, and generated candidates independently of request id.
  Different effects therefore have different hashes.
- `src/seon/db/writer.clj:1364-1373` reports the expected and actual request
  hashes when an id is reused for different logical work.

## Owner

`seon.effect` owns the one request identity. The run loop may supply stable
executing-form coordinates, but the effect owner must derive a stable
per-effect address without adding a second replay ledger.

## Acceptance

- A generated form containing two different database effects commits both
  exactly once.
- Re-executing the same form replays each corresponding effect and commits
  neither a third nor fourth write.
- Reordering or inserting an effect has an explicitly ruled identity behavior;
  no random retry-local counter decides it.
- The writer continues to reject one identity reused for different logical
  transaction data.
