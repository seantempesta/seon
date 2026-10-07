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

# printed-copy (owner order "kill all the other printers", 2026-10-07)

Landed: c08e2c766 (evaluation value copy). Slice 1 (refusal messages -> data): wake, prompt, mcp, batch observe.

## Stored serializers still alive (need one decision before cutting)

`seon.sci.admit/canonical-edn` = `seon.id/printed`. Storage callers:
- seon.error value-edn / bounded-text (fault evidence text and fault fields; errors.md §1.3 "stores the
  first occurrence's referenced map as one blob by content") and error.clj:592/782 artifact-edn
  (:seon.error/data-content, the data-blob dig form at error.clj:2225).
- seon.effect staged-result (effect.clj:541, result blob) and :seon.effect/request-edn (effect.clj:866).
- seon.test.runner failure actual blob (runner.clj:1406; held by reach-review).
- seon.render/unknown text (render.clj:1112).
- seon.sci.admit's own walk: emits print-node EDN into a StringBuilder per admission to count bytes;
  `:seon.sci.admit/edn` has NO reader (only docstrings), so the emission is pure waste — admission can
  count bytes without building text, but its leaf sizes come from canonical-edn (leaf-edn).
- seon.render.value/artifact-edn (held by deep-review).
Other id/printed callers (not storage of a value copy): render/schema.clj:136 route param, mcp.clj:332
source form, instrument.clj:714 key projection (stored :seon.error.key/projection string),
schema/datahike.clj:527-562 (attribute storage encoding/ordering: the database owner's codec),
turn.clj:1721 (form source text).

Decision for root/owner: the one stored representation of a value that must outlive the process
(fault evidence, effect results, test-failure actual). Options: (a) the value printer's whole print
(seon.print full) read back by the one reader; (b) Datahike values (attribute per member, refs);
(c) store nothing but the shown text + identities (as for evaluations). Recommendation (c) for test
failure actual and effect request-edn, (a) for fault evidence and effect results (which have readers).

## Remaining printed-copy sites by owner
- held by printers-rest: cluster.clj:1520, config.clj:673, test.clj:1728/2076.
- held by deep-review: render/value.clj:255. reach-review: runner. error-frames: kernel.
- free, need schema change (stored string facts -> data): my/background.clj:16, background.clj:55,
  cluster/message.clj:197, error.clj:1264/2743, instrument.clj:714, turn.clj:4192/4194, batch.clj:232.
- free, text assembled outside the printer: repl.clj:211/228/246, turn.clj:2160/2201/3309/5001,
  sci/eval.clj:3437 and shown-result fallbacks 3601/3604/3630, cluster/boot.clj:298/823,
  test/accretion.clj:87/92 (a render returning text), env.clj:60 print-method, schema.clj:1775,
  error.clj:1022/2535, render.clj:1951.
