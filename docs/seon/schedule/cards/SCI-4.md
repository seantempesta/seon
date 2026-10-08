---
title: "Card SCI-4 (later) Dense per-context Var slots: make a written Var's read cost about 1 ns"
type: reference
status: active
created: 2026-10-08
---

# SCI-4 Dense per-context Var slots (later optimization)

After SCI-1..3, when profiles show the per-context read path. Owner ruling 2026-10-08: correct now, fast later.
Research: docs/research/agent-platform/2026-10-08/sci-fork-vars/sci-vars-impl.md (option C). Lane: Astra (hot
path), dual review.

## What becomes true

A Var some fork wrote reads in about 1 ns instead of +10-15 ns. Each Var gets a lineage slot index at birth; each
context holds an object array of its own bindings, shared at fork and copied on its first write (O(V): 5,700 refs,
about 23 KB, a few microseconds). Analyzed Var read and call nodes index the slot through the ctx argument they
already receive; an SCI fn body runs with the active context, read once per call at entry (a `ThreadLocal` set by
`sci.ctx-store/with-ctx`, 0.9 ns, instead of the `*ctx*` thread-binding lookup, 4.7 ns plus a 32-byte MapEntry per
read today). Host reads keep the `*ctx*` path.

## Baseline (fork `seon-env-hook` `cff2cefb`, `sci.vars-bench`)

| ns per loop iteration | 97de2004 | cff2cefb |
|---|---|---|
| read, Var never written by a fork | 12-21 | 16-21 |
| call, Var never written by a fork | 26-37 | 31-36 |
| read, Var written by a fork | 12-21 | 28-40 |
| call, Var written by a fork | 26-36 | 42-53 |

Target: the "written by a fork" rows within 10% of the 97de2004 column; fork creation at most a few microseconds on
its first write.

## Proof

`sci.fork-bindings-test` unchanged and green; `sci.vars-bench` numbers in the fork commit; the Seon proofs of SCI-3
green after the bump.
