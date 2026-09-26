---
type: issue
status: fixed-unverified
severity: defect
created: 2026-09-26
tags: [issue, program, definition-digest, writer]
---

# declaration-row mints a definition digest without its resolver context

`seon.program/declaration-row` (`src/seon/program.cljc:1284-1292`) stamps
`(definition-digest candidate)` on a function or test row that arrives without one. The writer
(`seon.db/write-digest-error`, `src/seon/db.clj:4280`) digests every changed function/test row through
`definition-digest-of`, which includes the namespace's resolver context
(`seon.fn/stored-namespace-context`). The minted digest therefore never matches, and the refusal names
the digest instead of the missing input.

## Evidence (2026-09-26, default pid 76020, `d/db-with` value, read-only)

For `(defn ^{:malli/schema [:=> [:cat] :int]} target [] 1)` in a new namespace `fixture.batch`:
`declaration-row` minted `26f21bc4…`, the writer expects `1a8a8f01…`; `seon.test-support/program-row`
(through `definition-digest-of`) answers `12f6ad5e…`, equal to the writer's digest of that row.
Production settlement never reaches the mint, because `seon.sci.eval/definition-row` digests every row
first. Three turn-test fixtures reached it by handing `seon.fn/analyze-form` rows to settlement; they now
use `program-row` (0d3b96015).

## Proposed fix (src/seon/program.cljc granted; test/seon/program_test.clj held)

Mint only for namespace and schema rows, the families whose digest takes no resolver context. A function
or test row without a digest then refuses at the writer by the missing key. Every production function/test
path digests through `definition-digest-of` before this stage (`seon.sci.eval/definition-row`,
`seon.fn` source rows at `src/seon/fn.clj:1552`), and `:seon.program/declaration-row` does not require the
digest on those families, so the output contract holds. Landed in eb848eacd with the program_test update.

It must land in the same slice as `program_test`'s `reader-events-have-one-canonical-declaration-row`
(`test/seon/program_test.clj:833`, asserting a 64-character digest on every case). That assertion should
expect a digest on the namespace cases only. The file is held by impl-shape-mirror-malli, so the mint stays
until its owner or root takes both.
