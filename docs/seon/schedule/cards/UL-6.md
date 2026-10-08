---
title: "UL-6: The wake channel uses core.async's sliding buffer"
type: reference
status: ready after led-cacheb
created: 2026-10-08
---

# UL-6: The wake channel uses core.async's sliding buffer

Ledger row UL-6 (group B). Sources: `reinvention/reinvention-opus.md` item 5, `reinvention-astra.md` rank 27,
`clojure-way-opus-clojure-way.md` §3.2, plan-review-opus3 finding 5. Model: Sonnet. No owner decision: nothing reads
the dropped count (`rg ':dropped' src test` finds only delimiter-repair tuples, unrelated), so no capability is lost.

`CountedSlidingBuffer` (`src/seon/cluster/agent.clj:107-128`) is `sliding-buffer 1` plus an atom counting overwritten
wakes, visible only through Flow's `ping` datafy. core.async's `sliding-buffer` is the library's
(`reference-code/core.async/src/main/clojure/clojure/core/async.clj`, `sliding-buffer`; impl
`core/async/impl/buffers.clj:60-81`). This also removes one atom (AT-4 no longer lists it).

## Files

- `src/seon/cluster/agent.clj`

## Functions

- Delete the `deftype CountedSlidingBuffer` (`agent.clj:107-128`).
- Change `seon.cluster.agent/wake-channel` (`agent.clj:130-134`), contract unchanged
  `{:malli/schema [:=> [:cat] :seon.flow/channel]}`: body `(async/chan (async/sliding-buffer 1))`; docstring "A
  sliding-one wake channel: a newer wake replaces an unread one."
- Change the episode channel option (`agent.clj:596`): `{:buf-or-n (async/sliding-buffer 1)}`.
- Remove from the `ns` form what only the deftype used: `[clojure.core.protocols :as core.protocols]` (`:70`),
  `[clojure.core.async.impl.protocols :as async.protocols]` (`:71`) and `LinkedList` from the `java.util` import
  (`:97`). Check each with `rg` in the file first; delete only if no other use remains.

## Data

Unchanged: the channel still holds at most the newest wake. A Flow `ping` of the proc shows core.async's own
`SlidingBuffer` datafy (`{:type ... :count ... :capacity 1}`), without `:dropped`.

## Proof

Existing tests reaching the wake channel stay green: `seon.turn-test` (`turn_test.clj:1836-1873` polls the cluster's
wake channel: one wake readable, then empty). D: `rg CountedSlidingBuffer src test` is empty.

`--test seon.turn-test seon.cluster.host-test`

## Cost

Expected: one fewer `swap!` per overwritten wake; −25 lines.

## Commit

`bin/lane commit <lane> -m "UL-6: the wake channel is core.async's sliding buffer; CountedSlidingBuffer deleted" --test seon.turn-test seon.cluster.host-test src/seon/cluster/agent.clj`

## Dependencies

`src/seon/cluster/agent.clj` is held by led-cacheb: claim after it releases. Lands before AT-4 (which then has one
fewer site).
