---
type: issue
status: open
severity: friction
tags: [issue, testing, contracts, fixtures]
---

# Selected source indexing gate retains invalid fixtures

Observed 2026-09-08 on `7bc62158b` plus only the inherited
`src/seon/fn.clj` diff (callable default observer and blocking findings):
`bin/test --paths src/seon/fn.clj -- seon.fn-test` reports 30 tests / 154
assertions / 2 failures / 4 errors. Evidence:
`tmp/runner-resume-fn-gate.log`, retained root `run.FCw8iW` (disposable after
recording). Each failing test reproduced in confirmation.

- `changed-file-planning-is-conservative-and-explicit`: explicit nil
  current/desired artifacts violate their contracts; absent means no key.
- `file-artifacts-and-manifests-are-byte-digested-and-deterministic`: the
  literal `changed` is not a valid artifact digest.
- `indexing-uses-a-prebuilt-manifest-without-analysis`: its fake connection
  and manifest fail the real armed input contract before indexing runs.
- `agent-source-reaches-the-evaluator-through-one-visible-path`: expects
  only the web preview caller; the actual set also includes
  `seon.turn/system-turn`.
- `settled-agent-form-has-static-index-edge-parity`: expected synthetic
  declaration facts differ from the returned program facts. Its complete
  assertion output is unreadably large (the selected diagnostic lines
  exceeded 81,000 tokens); failure reporting needs a bounded useful view
  while preserving full evidence in the run output.

The shared working tree already contains a broader sibling edit to
`test/seon/fn_test.clj`, including canonical fixture repairs. This probe
excluded it deliberately. The two production changes are coherent on review,
but their independent gate is red; they remain uncommitted under the
assignment's explicit “gate and commit, or explain” alternative. This is
not an attribution of these failures to the production diff. Reconcile and
gate the fixture changes in their owning slice before landing it.
