---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, schema, test]
---

# Schema declaration regression disagrees with the current row shape

The S3 HEAD-plus-paths fast run at `e481946f6` failed
`seon.sci.eval-test/declared-row-evaluates-a-schema-once-inside-its-delta`.
Its exact-map assertion expected `:seon.schema/ns [:seon.ns/name 'user]` and
no `:seon.schema/shape`. The actual row had no namespace ref and carried a
canonical shape with type `:int`, form `"[:int {:min 0}]"`, properties
`"{:min 0}"`, comparison `:exact`, normalization revision
`"malli-80138076960e7820523b4cb932c5b5d1936d4e7f/p12-v2"`, and fingerprint
`"cb1ee409905c529ad2da958495af559bdccd2e73effab843b624865e4407a9d8"`.
Both maps agreed on key `:user/direct-schema`, source `:agent`, form, and
generatability.

This records an observed disagreement, not an attribution to the S3 query
change or a decision that either side is correct. Verify the schema declaration
authority and update the owning behavior or regression accordingly. The S3
run changed only its new query regression in this test namespace; this
existing assertion and the held evaluator were untouched.

Evidence and command:
[S3 landing note](../../prds/steward-platform/research/acquisition-by-provenance-s3-2026-09-16.md).

## Verdict (2026-09-17, baseline-reds lane)

Both sides verified against `6312fcef0` "Unify indexed and evaluated
declaration analysis" (2026-09-16T13:11). The disagreement is two separate
facts, and the two sides are not symmetric:

- `:seon.schema/shape` is correct accretion. That commit extended
  `seon.program/with-contract-facts` (`src/seon/program.cljc:875-880`) to
  assoc the canonical shape row onto every schema row and updated
  `seon.program-test` in the same commit; this assertion was missed. The
  regression's expectation is stale.
- The missing `:seon.schema/ns` is a defect at the writer. The same commit
  narrowed `seon.sci.eval/row` (`src/seon/sci/eval.clj:303`) from
  `(select-keys event [:seon.schema/key :seon.schema/form :seon.schema/ns])`
  to `(select-keys event [:seon.schema/key :seon.schema/form])`. The reader
  supplies the fact (`src/seon/sci/reader.cljc:410`) and nothing downstream
  re-derives it — the committed row is `(or var-row reader-row)` — so an
  agent-declared schema now records no declaring namespace. Consumers:
  `src/seon/render/ns.clj:893`, `src/my/program.clj:264`, and the regression
  that owns the claim,
  `seon.cluster.turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute`
  (`test/seon/cluster/turn_test.clj:1067`), which did not run in batch 115.

Not landed here: `src/seon/sci/eval.clj` and `test/seon/sci/eval_test.clj`
are both held dirty by the S3 acquisition lane. The exact hunks for both
sides are in
[the landing note](../../prds/steward-platform/research/baseline-reds-sci-eval-documentation-2026-09-17.md).
