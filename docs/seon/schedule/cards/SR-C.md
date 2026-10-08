---
title: "Card SR-C Large printed output goes to a blob"
type: reference
status: active
created: 2026-10-08
---

# SR-C Large printed output goes to a blob

Ledger row SR-C (group C). Storage rule: text over `:seon.config.eval.result/blob-threshold` (UTF-8 bytes) is a blob,
the fact keeping digest and size. Lane: Sonnet.

## Files
`resources/seon/schemas/seon.cluster.eval.edn`, `src/seon/sci/eval.clj` (producer `:3874`, `:3909`),
`src/seon/repl.clj` (readers `:180-192`, `:204`, `:340`, `:557`), `src/seon/render/transcript.clj` (`:49`),
`src/seon/eval/drive.clj` (`:142-153`), `test/seon/repl_differential_p1_test.clj`.

## Functions
- SCHEMA: `:output-size [:int {:min 0}]` and `:output-blob :seon.blob/digest` beside `:output`
  (`seon.cluster.eval.edn:57`), optional receipt members.
- CHANGE the two producers in `seon.sci.eval` that `assoc :seon.cluster.eval/output`: call
  `(seon.blob/inline-or-stage connection output :seon.cluster.eval/output :seon.cluster.eval/output-size :seon.cluster.eval/output-blob)`
  (`src/seon/blob.clj:217`), merge `:seon.blob/fact`, carry `:seon.blob/staged-writes` into the settlement's staged
  writes (as the reply's are).
- ADD `seon.repl/output-text`
  `{:malli/schema [:=> [:cat :seon.db/connection :seon.repl/emission] [:or :nil :string]]}` `[connection emission]`:
  `:seon.cluster.eval/output`, else `(seon.blob/get connection digest)` (`src/seon/blob.clj:412`). Every reader above
  reads through it; `shown-output`'s requery form names the blob read when `output-blob` is present.

## Proof
`seon.repl-differential-p1-test/large-out-is-a-blob-ref` (new): through `seon.mcp/call`,
`(print (apply str (repeat 5000 "x")))`; the evaluation entity has `:seon.cluster.eval/output-blob` and
`:seon.cluster.eval/output-size` 5000 and no `:seon.cluster.eval/output`; the reply's `:out` equals the printed text
as the value printer shows it (budget applies).

## Other tests reaching it
`seon.repl-differential-p1-test`, `seon.repl-differential-p5-test` (bulk output), `seon.eval.drive-test`,
`seon.render.transcript-test`.

## Cost
D: one size comparison per form with output; one blob stage only over 4096 bytes.

## Deleted
Nothing.

## Commit
`bin/lane commit <lane> -m "SR-C: large printed output goes to a blob" --test seon.repl-differential-p1-test seon.repl-differential-p5-test resources/seon/schemas/seon.cluster.eval.edn src/seon/sci/eval.clj src/seon/repl.clj src/seon/render/transcript.clj src/seon/eval/drive.clj test/seon/repl_differential_p1_test.clj`

## Dependencies
None (SR-B landed). Group C order puts it after LR-STALE3/RED-2 (ledger §5). `render/transcript.clj` conflicts with
S4-03/S4-07b3: serialize by claim.

## Reset
None.
