---
type: issue
status: resolved
severity: friction
created: 2026-09-23
tags: [schema-shape, testing, class/stored-derived, wave/publication-velocity]
---

# Program shape regressions still expect expanded subtrees

The authored-shape ruling removes registry expansion and duplicate subtree
strings. Two regressions outside the schema-shape lane's owned test directory
still assert the retired representation:

- `seon.program-test/empty-composite-schema-shapes-remain-canonical-and-queryable`,
  `test/seon/program_test.clj:103`: follows entries directly from a named
  request shape, then expects `row-form` to expand its registry references
  (`:132–143`). The authored request is `:seon.reconcile/request`; consumers
  needing its map use the compiled registry.
- `seon.program-test/regex-rest-tail-is-complete-and-element-is-only-derived-when-proven`,
  `test/seon/program_test.clj:246`: expects complete subtree strings in
  `:seon.schema.shape/form` (`:260–271`). Assert the reconstructed authored
  form through `row-form`, and assert the stored node is only its head.

These are source-confirmed stale expectations, not a claim that the platform
suite was run. The bounded lane ran only its path-selected fast namespace;
the orchestrator owns the cold/platform proof and these out-of-scope tests.
The replacement size, roundtrip and identity regressions are in
`test/seon/fn/schema_shape_test.clj`. Measurements and the accepted semantics:
[authored shape landing note](../../prds/steward-platform/research/schema-shape-authored-2026-09-23.md).

## Resolution — bounded follow-up, 2026-09-23

Updated only the two named regressions in `test/seon/program_test.clj`.
The named request reconstructs as `:seon.reconcile/request` and has no
expanded entry rows. The authored desired declaration reconstructs as
`[:vector :map]` (Malli normalizes the empty map to `:map`), while its
stored vector node is `[:vector]` and its scalar map leaf is `:map`.
Regex tails reconstruct through `row-form`; their stored strings contain
only `[:*]` or `[:alt]`. The scalar rest element remains `:string` and the
unproven composed rest element remains absent.

The owner explicitly limited this follow-up to the test file and this issue;
the resolved note therefore remains at its existing path.

Verification used `bin/test-fast --paths test/seon/program_test.clj -- seon.program-test`.
The shared run `ef09f6f20806` was refused before execution at
`seon.test.runner/record-snapshot!`: the recording process could not resolve
`:seon.config/applied-manifest-digest` while registering `:seon.config/compiled`.
Continued in the explicitly authorized HEAD worktree
`tmp/schema-shape-expectations-wt` (`f0e2fa7c8`), overlaying only this test file.
The existing immutable published fixture store was copied and reidentified
with `seon.cluster.export/reidentify!` for isolated result recording.
No cold gate, new baseline publication, default operation, or foreign file
edit was performed. This issue's resolution section is the follow-up landing
record, keeping the owner's two-file scope.

Final isolated run `4d077f653067`: **both changed regressions passed**.
Namespace tally: **27 executed, 0 unchanged, 219 assertions, 2 failures,
3 errors**. The remaining results are outside the two requested expectations:

- `changed-runtime-redeclaration-builds-a-real-replacement`: 6,438.889 ms
  exceeded its 5,000 ms bound (the preceding run took 4,631 ms).
- `opening-basis-divergence-is-only-claimed-when-it-is-measurable`,
  `test/seon/program_test.clj:627`: expected the unreadable-opening-basis
  rule; observed nil.
- `indexed-and-evaluated-declarations-are-the-same-entities` and
  `typed-cross-namespace-deletion-retracts-function-and-test`: the fixture's
  armed `seon.config/compiled` contract still requires the removed
  `:seon.config/applied-manifest-digest`, rejecting `compile-manifest`'s return.
- `runtime-schema-declarations-project-namespaced-properties`: publication
  refuses `:sample/error` because `sample/render-ai` has no declared input.

These results do not make the whole namespace green, and this bounded
expectation repair does not alter those other tests or production owners.
The resolved issue is specifically the two obsolete expanded-form expectations.
Namespace loading is checked before and after the path-limited commit with
`clojure -M -e "(require 'seon.program 'seon.fn.schema-shape 'seon.call-preparation)"`.
The disposable worktree, private store, and scratch log are removed; the
shared base and other lanes' edits are preserved.
