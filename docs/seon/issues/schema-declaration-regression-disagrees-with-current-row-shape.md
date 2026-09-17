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

## Message-wake review observation — 2026-09-17

The isolated message-wake review at base `8dff32220` also ran
`seon.cluster.turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute`.
Its namespace assertion errors because `(:db/id (:seon.schema/ns persistent-row))`
is nil and the armed `seon.db/pull` refuses that entity id. No schema declaration
writer or this regression was changed by the message-wake slice. This records
the matching observable; it does not independently re-prove the attribution
above. See the [landing evidence](../../prds/steward-platform/research/message-wake-model-2026-09-17.md).

## Adoption-margin baseline verification — 2026-09-17

`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`
now observes the namespace on the evaluated `:sample.s1/value` schema and
none on its indexed resource counterpart. The 05:56Z reconciliation
iteration reported that exact mismatch. A second armed HEAD-plus-paths run
using the unchanged `c772db2d3` indexer and original test reproduced it:
27 tests / 227 assertions, one failure / zero errors. Thus the mismatch
exists without the adoption-margin reference-reader change. This observation
does not reverse the resource-versus-evaluation ruling in the archived parity
note or decide what namespace a resource schema should name. Logs and the
bounded verification scope are recorded in
[the adoption landing note](../../prds/steward-platform/research/adoption-margin-2026-09-17.md).

## Batch 122 B review — 2026-09-17

The raw failure at `program_test.clj:1183` has the same canonical shape
fingerprint `0448f7805c0c1a5a2a18b9ead43c42688fd522274ac08246271ceccf7db052d2`
on **both** rows. Only the evaluated row carries namespace ref
`[:seon.ns/name sample.s1]`. This is not a missing shape. The exact
`ad75bab51` test diff only supplies the normalized-row helper its explicit
entity reader; it changes no parity expectation and neither deletion test.
The earlier unchanged-indexer counterfactual above already reproduced this
namespace difference. Its owners remain the resource constructor
`seon.schema/canonical-schema-rows`, the evaluated declaration constructor
`seon.sci.eval/row`, and the S1 parity comparison; changing memoization cannot
supply missing resource provenance.

The review retry at `6108f27f5` ran 68 tests / 519 assertions; this exact
namespace-only mismatch was its sole failure (zero errors). The cleanup and
deletion-expectation changes passed in that same armed JVM.
