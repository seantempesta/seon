---
type: research
status: blocked
created: 2026-09-17
tags: [reset, schema, admission, call-preparation, verification]
---

# Tier 1 admission: two premises require correction before landing

Read the owner's [unbreakable-connections inventory](../../steward-platform/research/unbreakable-connections-2026-09-16.md)
end to end. No production commit landed. The proposed source/test edits are
preserved as exact patches, and only this lane's unlanded source hunks were
removed after checking their bytes against the saved patch. Shared foreign
hunks remain untouched. Default remains pid **41413**, alive; no reset,
restart, Datahike fork change, universal living-ref enforcement, or AST
removal was performed.

## Blocking evidence and owner decision

1. **File provenance:** the live read-only query found **802** core function
   identities without files. `fn/desired-rows` manufactures external targets
   and stamps them core. They disappear with G1/G2; requiring file before
   that step refuses canonical population. The held `program-fn-row` fixture
   helper also emits core rows without files. [Probe and completion boundary](../../../seon/issues/core-program-stubs-prevent-required-file-provenance.md).
2. **Arity:** the complete read-only probe returned source count **0** for
   `(my.message/inbox)`, declared arity **1**, and prepared source counts
   **[0 1]**. The raw-count invariant would refuse an agent definition using
   this documented, supported call. [Probe and three priced options](../../../seon/issues/source-call-arity-is-not-prepared-call-arity.md).
   Recommendation: derive source-count compatibility from the existing
   preparation plans; do not independently reimplement argument placement.
   Core-only coverage or retaining the advisory report are explicit smaller
   alternatives. This is the AGENTS §2.5 design boundary, not a request to
   weaken a failing regression.

The owner question was sent asynchronously; no answer was received before
this review boundary. Strict arity enforcement was removed from working
production code, so another publication cannot pick it up accidentally.

## Reviewable implementation

Base when the patches were saved: `8a507aa8f966574815ed4ea845f8c1085eb29c47`.

- [Render/ref candidate](../../steward-platform/research/reset-tier1-render-candidate-2026-09-17.patch):
  `src/seon/db.clj`, `src/seon/cluster.clj`, `test/seon/db_test.clj`,
  `test/seon/fn_test.clj`. Final stored schema forms must name surviving
  renderer rows; schema+renderer can arrive or retract together. Complete
  publication defers canonical schema rows into the existing `index!`
  transaction that already includes the function rows. Incremental
  schema-only publication and branch acquisition retain reconciliation.
  Canonical regressions name surviving function/task referrers on deletion.
  One existing fixture now supplies the required map to `my.turn/wait`.
- [Raw arity candidate, **do not apply as written**](../../steward-platform/research/reset-tier1-raw-arity-candidate-2026-09-17.patch):
  applies on top of the render/ref patch. One shared report computes the
  complete sorted mismatch payload; public reads use tracked queries and
  final admission uses uncapped database queries. Component-only changes and
  atomic caller repairs have regressions. The preparation counterexample
  invalidates this candidate's blanket enforcement.

No source/schema path remains modified by this lane. At the stop check,
`src/seon/test/selection.clj` was clean (released at `d6659f21a`), while
`test/seon/test_support.clj` remained held. The earlier selection hold is
not the current boundary. The reset plan records the remaining ordering.

## Verification limits

Only `bin/test-fast --paths` was invoked, never `bin/test`. Three shared
slots were respected. The requested `seon.render-test` namespace does not
exist; `seon.render.entity-pairs-test` was substituted.

- Live advisory arity census: **8,939 checked / 46,999 unchecked / 0
  mismatches**, **395 ms**. This does not cover the preparation counterexample.
- `tmp/reset-tier1-fast-5.log`: canonical population with render enforcement
  succeeded; all new required-ref, render-target and arity regressions passed.
  The database namespace completed **51 tests**, with one failure in the
  existing strict five-millisecond query assertion (samples included
  **7,260,375 ns** and **32,419,292 ns**, with correct results).
- That run exited **124** when the 300-second silence watchdog fired during
  `indexing-uses-a-prebuilt-manifest-without-analysis`. Thread observations
  progressed from transaction work to row comparison. This is recorded
  against the existing [liveness issue](../../../seon/issues/the-cold-fixture-base-outruns-the-liveness-silence-backstop.md),
  whose held note was not edited.
- A bounded rerun used `SEON_TEST_SILENCE_SECONDS=900`; it was stopped when
  the preparation counterexample made its strict candidate unsuitable for
  publication. No full six-namespace green result is claimed. Earlier
  superseded runs caught a test parenthesis error and the absent namespace;
  those were corrected before the measured candidate run.
- Standalone lint after refreshing production dependency caches retained one
  generated-constructor finding, with no other errors in the five checked
  source/test paths. [Dependency evidence](../../../seon/issues/kondo-does-not-resolve-datalog-parser-generated-variable-constructor.md).

All this lane's test processes ended and their snapshot roots were removed.
The next action is the owner arity ruling, then applying/correcting the
reviewed candidates and obtaining the full green proof. **RESET NEEDED**
remains the pending value-edge retype, not the render/ref candidate.
