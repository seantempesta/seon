---
type: issue
status: open
severity: platform
created: 2026-09-24
tags: [issue, database, storage, test-evidence, instrumentation, bounded-presentation]
---

# A 105 MB failure message rides every new-entity commit

Seen 2026-09-24 by lane fix-test-run-cost on default (pid 90471, started
2026-09-23T22:18:14Z), datahike fork `c79cd03a`, index
`:datahike.index/persistent-set` with `:branching-factor 4096`.

## Problem

One datom, entity 64270 `:seon.test.failure/message`, holds a 105,624,664-character
string. It was written at tx 536872568 (run `b17cca9910a1`, 2026-09-23T18:08Z) for member
`seon.schema-test/an-incremental-projection-equals-its-full-build`. The entity sits in the
rightmost EAVT leaf, so every transaction that creates an entity rewrites that leaf. This
applies to every writer in the cluster, not only tests.

The effect was measured on an isolated branch of default's commit (raw `d/transact`, with
files newer than a marker summed under `data/store`):

| Transaction | ms | bytes written | largest file |
|---|---:|---:|---:|
| `[]` (tx entity only) | 38–71 | 502,984 | 206,613 |
| one new entity (`:seon.test.member/pass-count`) | 532–1,666 | 106,367,075–106,475,277 | 105,719,803 |
| one new entity (`:seon.issue/problem "x"`) | 506 | (not measured) | |

Stack samples during a one-test `seon.test/run` point the same way. Of the samples on the
Datahike writer threads, 632 of 1,065 were in `datahike.index.persistent_set$reify.write`,
under `org.fressian.FressianWriter.writeString`, and 168 were in `FileDispatcherImpl.force0`.
The admission and result commits cost 1,248–1,639 ms of the 1.5–2.0 s request. The armed
code inside them (`admit-run`, `record-tx`, the write validators) took about 235 ms. The
store is 61 GB.

## Cause chain

1. `seon.test.runner` stores `testing` contexts raw
   (`src/seon/test/runner.clj:195`, `(mapv vector (range) (reverse test/*testing-contexts*))`).
   The schema test passes a lookup-ref vector as its `testing` label
   (`test/seon/schema_test.clj:1719`), so the output contract of `run-vars!` refuses
   ("expected a string, got a lookup-ref vector").
2. The `seon.instrument` refusal embeds the whole offending value, meaning every captured
   result with its `actual` values, in its ex-message and ex-data.
3. `seon.test` records the refusal as an execution failure,
   `(str "Test execution failed:\n" (throwable-text …))` (`src/seon/test.clj:206`), with no
   bound. The message then becomes a datom.

Step 3 is the storage defect. A presentation-unbounded diagnostic is stored inline in an
index datom. Content that size belongs in the blob tier, or should be clipped once by the
value renderer. Steps 1 and 2 are the producers of this instance. The same class was seen
for render producers in
[a render producer's contract refusal is too large to admit](a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md).

## Wanted

- `seon.test` stores an execution failure's text bounded, or stores it through the blob tier.
  Owner: `src/seon/test.clj`.
- `seon.instrument` refusals name the offending path and shape, not the whole value.
  Owner: `src/seon/instrument.clj`.
- The runner stringifies `testing` contexts as `clojure.test/testing-contexts-str` does.
  Owner: `src/seon/test/runner.clj`.
- Root repairs default's store: purge the datom, or reset. Retraction alone moves the value
  into temporal EAVT/AEVT. On the probe branch, entity creation after
  `[:db/retract 64270 :seon.test.failure/message]` still cost 1.0–2.0 s.
