---
title: "Card SR-F Large shown text goes to a blob"
type: reference
status: active
created: 2026-10-08
---

# SR-F Large shown text goes to a blob

Ledger row SR-F (group C). Same rule as SR-C for `:seon.eval/shown`. Lane: Sonnet.

## Files

`resources/seon/schemas/seon.cluster.eval.edn`, `src/seon/sci/eval.clj` (settlement producer of `:seon.eval/shown`,
`shown-result` `:3795-3800`), `src/seon/repl.clj` (`value-text` `:164`), `src/seon/context.clj` (`:131`),
`src/seon/mcp.clj` (`:123`, `:231`), `test/seon/repl_differential_p1_test.clj`.

## Functions

- SCHEMA: `:seon.eval/shown-size [:int {:min 0}]`, `:seon.eval/shown-blob :seon.blob/digest`
  in `seon.cluster.eval.edn`, optional; these are full keys, not that family's shorthand.
- SCHEMA (SP-2): when SP-2 has landed, `:seon.eval/shown` is declared `:seon.blob/inline-text` in this commit; 20 of 604 stored values on default exceed 4,096 bytes today, so it cannot be declared before this card (storage-point §2).
- CHANGE where the settlement writes `:seon.eval/shown` (the evaluation map from `shown-result`): store through
  `seon.blob/inline-or-stage` with `:seon.eval/shown :seon.eval/shown-size :seon.eval/shown-blob`.
- CHANGE `seon.repl/value-text` (`src/seon/repl.clj:164`) to the one reader:
  `{:malli/schema [:=> [:cat :seon.db/connection :seon.repl/emission] [:maybe :string]]}` `[connection emission]`:
  `:seon.eval/shown`, else `(seon.blob/get connection (:seon.eval/shown-blob emission))`, else `:seon.repl/value`.
  Convert every direct reader of `:seon.eval/shown` that reads STORED rows to it: `context.clj:131`, `mcp.clj:123,231`,
  and `turn.clj`'s stored-row readers (`:1567,1833,2145,2204,3407,3679,3732`) — `turn.clj` is group B: send those
  hunks to its owner as a patch, or land after S4-07b3 deletes most of them. In-memory evaluation maps (the live pass)
  keep their `:seon.eval/shown` string and need no change.

## Proof

`seon.repl-differential-p1-test/large-shown-is-a-blob-ref` (new): through `seon.mcp/call`,
`(binding [seon.print/*budget* 4000] (range 3000))`; the evaluation entity has `:seon.eval/shown-blob`, no
`:seon.eval/shown`; the MCP reply text `=` the blob's content.

## Other tests reaching it

`seon.repl-differential-p1-test`, `seon.mcp-entrance-test`, `seon.context-test`, `seon.render.transcript-run-test`
(S4-03 history reads shown text through `value-text`).

## Cost

H: history and reply read one blob only for shown text over 4096 bytes.

## Deleted

Nothing.

## Commit

`bin/lane commit <lane> -m "SR-F: large shown text goes to a blob" --test seon.repl-differential-p1-test --test seon.mcp-entrance-test --test seon.context-test resources/seon/schemas/seon.cluster.eval.edn src/seon/sci/eval.clj src/seon/repl.clj src/seon/context.clj src/seon/mcp.clj test/seon/repl_differential_p1_test.clj`

## Dependencies

PR-11 (the `*budget*` override). Land after S4-07b3 to avoid converting `turn.clj` readers that are about to be
deleted (prefer deletion).

## Reset

None.
