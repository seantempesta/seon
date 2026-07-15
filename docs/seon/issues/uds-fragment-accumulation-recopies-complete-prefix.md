---
type: issue
status: open
severity: friction
tags: [issue, database, cljs]
---

# Make UDS frame accumulation linear

## Problem

The pod's request and publication decoders concatenate the complete accumulated
buffer with every newly received socket chunk. A large frame delivered in many
small fragments therefore repeatedly copies bytes already received and can do
quadratic total copying on the single JavaScript thread.

## Evidence

`src/seon/db/transport/uds.cljs` calls `Buffer.concat` over the current buffer
and each new chunk in both `rpc` and `connect-publisher!`. The protocol admits
frames up to 16 MiB, so adversarial or naturally fragmented delivery can turn a
bounded frame into disproportionate allocation, CPU, and event-loop delay.

The Bun runtime audit found the same cost before any engine migration. A native
`Bun.connect` substitution would not fix it automatically and additionally has
partial-write/drain semantics that must be handled explicitly.

## Owner

`seon.db.transport.uds` owns length framing and incremental decode. Strengthen
that one mechanism without changing the Transit protocol, writer, replay, or
replica contracts.

## Acceptance

- Fragmented and coalesced request/reply/publication frames decode identically.
- Total copied bytes and allocations grow linearly for a maximum-size frame
  split into one-byte, small, and random chunks.
- Multiple complete frames plus a partial tail remain ordered and lossless.
- Oversize, truncated, timeout, reconnect, and callback-failure behavior stays
  bounded and passes the existing replica/protocol matrix.
- A live transaction/replay proof shows no missed or duplicated committed
  effect and no material event-loop spike under the fragmentation workload.
