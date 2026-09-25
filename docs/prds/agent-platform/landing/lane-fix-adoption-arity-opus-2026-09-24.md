---
type: landing
status: landed; adopted on default 6ab6d14e and 6ab6d18c
created: 2026-09-25
---

# Lane fix-adoption-arity-opus (2026-09-25)

## Broken things first

- Once, not reproduced: the first adoption after the fix refused with a different error: "Program indexing transaction
  was refused. A named error schema requires a non-base domain member. {} Entity: nil". Its occurrence is
  `332e16e31daa`, 19:53:24Z, operation `seon.db/pull-many`, instrumented fn `seon.fn/require-committed!` (fn.clj:55),
  and it was raised by `seon.schema.internal/assert-error-declaration!` (internal.cljc:259). Two later identical runs
  succeeded. That refusal does not name its schema: it passes `{}` as data, and the chain stringifies it. Not fixed
  here (internal.cljc is outside this lane).
- The 16–31 s adoption is over the one-second law. The biggest costs are `seon.cluster/refresh-source!` and
  `seon.fn/analyzed-artifacts`. Existing issue: docs/seon/issues/the-thirty-second-write-bound-fails-program-publication-under-load.md.

## Cause

d4b3c0e13 gave `seon.fn/analyze-forms` two fixed source arities, `[database requests]` and
`[database requests dependencies]`, but its contract used a single `[:=> [:cat db requests [:? deps]] ...]`. Malli
reads a trailing `:?` as ONE `:varargs` arity with `{:min 2 :max 3}`, and `seon.program/malli-arity-key` keys that
as `[:variadic 2]`. The source keys are `#{[:fixed 2] [:fixed 3]}`, so `contract-facts` refused every publication
that reindexed seon.fn. JVM probe:
`(mapv m/-function-info (m/-function-schema-arities (m/function-schema [:=> [:cat :int :int [:? [:maybe :map]]] :any])))`
returned `[{:arity :varargs :min 2 :max 3 ...}]`.

## Fix

- src/seon/fn.clj: `analyze-forms` declares `[:function [:=> 2-arity] [:=> 3-arity]]`, one per source arity.
- src/seon/program.cljc: the `contract-facts` and `arity-row` refusals carry `:seon.error/member function-symbol`,
  the base error's declared member slot (`[:or :qualified-keyword :qualified-symbol]`). The subject was never in the
  ex-data. Admission's `remainder` clip did not drop it: the full value is kept in the occurrence's data blob.
- test/seon/program_test.clj: a class regression inside
  `source-contract-join-refuses-disagreement-and-records-overrides`. An optional trailing slot against two fixed
  arities refuses and names the member, `#{[:variadic 1]}` and `#{[:fixed 1] [:fixed 2]}`.

## Evidence

- Before: `bin/seon init --dev default --changed ...` refused in about 21 s (tmp/adopt-err.txt).
- After: adoption succeeded twice, commit ids 6ab6d14e (30.7 s) and 6ab6d18c (16.3 s). The installed row
  `seon.fn/analyze-forms` has arities "2" and "3", and the Var has both arglists.
- `bin/test-check default --test seon.program-test/source-contract-join-refuses-disagreement-and-records-overrides`:
  10 assertions passed, 4.6 s.
- `bin/test-check default --test seon.fn-test/a-dependency-macro-sci-cannot-expand-is-host-bound`: 7 passed, 5.6 s.
- Net lines: fn.clj +16/−10, program.cljc +2, program_test.clj +10/−1.
