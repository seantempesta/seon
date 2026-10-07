# value-storage lane (2026-10-07)

Task: seon.sci.eval/value-storage-walks-serializes-and-stores-every-value-up-to-eight-megabytes.

## Reproduced (default, jvm mode, thread-allocated bytes)

`seon.sci.eval/value-storage` alone, caps = `(seon.config/result-caps seon.config/defaults)`:

| value | ms | alloc | stored copy | realized |
|---|---|---|---|---|
| (vec (range 1e5)) | 417 | 740 MB | 5,288 KB | — |
| infinite (map f (range)) | 64 | 291 MB | none (over-bound) | 157,403 |
| 1 MB string | 45 | 9 MB | 1,000 KB | — |

The floor alone (`shown-result` with no caps, so no storage): vec 1e3/1e5/1e6 = 0-1 ms, ~1.4 MB each
(independent of length); infinite seq = 0 ms, 32 elements realized. So value-storage is the whole cost:
admit-value walks the value (print node per element + semantic-value rebuild + an EDN builder it then
discards), then canonical-edn serializes the print node a third time, and settlement stores it.

## Who consumes the stored copy

`:seon.eval/value-digest` has one reader: `bind-handles!` binding `result/eN` in a FRESH fork (after a
restart). `/data?value=` and `walk/continue`'s digest branch read artifacts but no producer links an
evaluation's digest to them (error and test-failure blobs are their own artifacts and stay).

## Decision

Store no copy of an evaluation's value. CLAUDE.md, Failure and execution: "Recover facts, not execution
... never resume interrupted execution or serialize private objects or live results"; an evaluation's value
is a live result and stays live and whole in its agent's world. What outlives the process is what the
evaluation recorded: its shown text, its handle (evaluation id), its size and its value class. An agent
that wants a value to outlive the process stores it deliberately (Data: "Store a value when it must
outlive its subject"). This reverses root ruling (a) of 80a02b4a4 (restart survival of cut values); the
handle still never dangles: a fresh fork binds every handle to its marker (`:live-only`, class).

The task's own done-when (allocation independent of length, an infinite seq realized only as far as
shown) cannot hold while a whole copy is serialized per evaluation, so "take the artifact from the floor's
walk" is not available: the floor deliberately does not walk what it does not show.

## Change

- `seon.sci.eval`: delete `value-storage`; `shown-result` records `:seon.eval/value-class` (the repl
  def-note's consumer) for an evaluation with caps. `settled-results`/`bind-handles!` bind markers only.
- `seon.turn`: `settlement-projection` stages no value blob; value-digest/unstored leave the receipt keys.
- `seon.turn.batch`: carries value-class only.
- Schemas: `:seon.eval/value-edn` deleted; `:seon.eval/value-digest` and the `:unstored` kinds no
  writer produces any more removed from the shown/entity maps; `:unstored` keeps the marker's kinds.

Cost after: (vec (range 1e5)) storage 417 ms / 740 MB / 5.3 MB stored -> 0; infinite seq realized 32.

## Not doing

No new cache, no lazy/shutdown serialization, no clipping. Not touching error.clj or test-runner blobs
(their own artifacts), nor docs/seon/specs/agent-loop.md:143 ("Staged blobs (cut values, reasoning)"),
which is uncommitted-modified by another owner: root corrects "cut values" there.
