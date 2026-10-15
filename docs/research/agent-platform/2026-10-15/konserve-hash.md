---
title: "konserve-hash: a read hashes its key once"
type: research
status: complete, premise refuted, no fork change (lane konserve-hash, 2026-10-15)
created: 2026-10-15
---

# konserve-hash: a read hashes its key once

Fork `reference-code/konserve` stays at `af35a974` (nothing to push). Root's bump is unchanged.

## Finding

`konserve.core/get` does not hash its key twice. The "two" was `hasch.benc/digest` plus `hasch.platform/uuid5`, the two
calls inside the one `konserve.impl.defaults/key->store-key`.

- Counted on a filestore with the var wrapped, `get`, `get-meta` and `exists?` each call `key->store-key` exactly once.
  The only call on the read path is in `konserve.impl.defaults/io-operation`, which puts the result in the env as
  `:store-key`; the pending-migration check and the blob open reuse it. `-exists?` is the only other reader and is a
  separate operation.
- The lock key is the raw key (`konserve.core/get-lock`), never hashed.
- `update-blob` already takes the `store-key` from the caller.

## Measured (warm, ThreadMXBean bytes per call, 200 calls after a 3,000-call warmup, fork `af35a974`)

| call | bytes |
|---|---:|
| `key->store-key` `[:datahike/commit-graph uuid]` | 4,792 |
| `get` tiny value | 32,048 |
| `get` 800-long vector (index-node-sized) | 78,168 |
| `get-meta` | 33,646 |
| `exists?` | 6,498 |

`exists?` is hash + backing probe at 6.5 KB, which confirms one hash. Removing a nonexistent second hash would save
nothing; the hash is 15% of a tiny get. The other ~27 KB is the env maps, `async+sync` closures, the header and the
deserializer, spread over many small allocations with no single owner worth a change now.

## No change made

The brief's proofs (allocation bound red before, on-disk keys byte-identical) have nothing to prove: the key derivation
is untouched and no read allocates less. The fork suite was not rerun for the same reason.
