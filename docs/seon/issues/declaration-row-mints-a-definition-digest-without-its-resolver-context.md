---
type: issue
status: open
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

## Proposed fix (src/seon/program.cljc, root grant needed)

Mint only for namespace and schema rows, the families whose digest takes no resolver context. A function
or test row without a digest then refuses by name as "missing :seon.program/definition-digest"
at the entity schema, not as a mismatch.
